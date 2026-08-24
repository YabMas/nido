(ns nido.review.report
  "The review report: a single immutable value that is BOTH the defined report
   shape and the per-round ledger. Built by folding the engine's typed events
   (apply-event). Pure — persistence is one explicit fn (persist!)."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]))

(def schema-version 1)

(defn init
  [{:keys [run-id cwd base started-at]}]
  {:schema     schema-version
   :run-id     run-id
   :status     "running"
   :target     {:cwd cwd :base base :base-rev nil :files []}
   :started-at started-at
   :ended-at   nil
   :rounds     []
   :summary    nil})

;; ---- round/phase helpers -------------------------------------------------

(defn- round-status
  "Derive a closed round's status from its phases. Order matters."
  [round]
  (let [phases (:phases round)
        ph     (fn [n] (last (filter #(= n (:phase %)) phases)))
        review (ph "review") warden (ph "warden") fix (ph "fix")
        judge  (ph "judge")  amend  (ph "amend")]
    (cond
      (some #(= "error" (:status %)) phases)                       "failed"
      ;; A record round, whose two stages tell the same story the review's three
      ;; do: nothing left to say, something given up, or another round earned.
      (and judge (= "ok" (:status judge)) (empty? (:findings judge))) "clean"
      (and amend (seq (:retreats amend)))                          "weakened"
      (and judge amend)                                            "continued"
      judge                                                        "ended"

      (and review (= "ok" (:status review))
           (empty? (:findings review)) (nil? warden))              "clean"
      (= "escalate" (:decision warden))                            "escalated"
      (and fix (seq (:fixes fix)))                                "continued"
      (= "stop" (:decision warden))                                "stopped"
      :else                                                       "ended")))

(defn- close-current-round
  "Close the last round if still running. ended-at is the last phase's ended-at
   (when the round actually finished) and falls back to `at` (the closing
   event's time) for a round with no completed phase."
  [report at]
  (let [idx (dec (count (:rounds report)))]
    (if (and (>= idx 0) (= "running" (get-in report [:rounds idx :status])))
      (let [round (get-in report [:rounds idx])
            end   (or (some-> (last (:phases round)) :ended-at) at)]
        (-> report
            (assoc-in [:rounds idx :status] (round-status round))
            (assoc-in [:rounds idx :ended-at] end)))
      report)))

(defn- open-round
  [report iter at]
  (if (some #(= iter (:round %)) (:rounds report))
    report
    (-> (close-current-round report at)
        (update :rounds conj {:round iter :status "running"
                              :started-at at :ended-at nil :phases []}))))

(defn- append-phase
  [report ph]
  (let [idx (dec (count (:rounds report)))]
    (update-in report [:rounds idx :phases] conj ph)))

(defn- update-current-phase
  [report phase-name f]
  (let [ridx   (dec (count (:rounds report)))
        phases (get-in report [:rounds ridx :phases])
        pidx   (last (keep-indexed (fn [i ph] (when (= phase-name (:phase ph)) i)) phases))]
    (if pidx (update-in report [:rounds ridx :phases pidx] f) report)))

(defn in-stack-order
  "Rows as the stack has them: layers bottom→top by :index, the composition pass
   last.

   Ordering belongs here rather than in the renderer because report.json is the
   durable artifact — a renderer that sorted would leave every other reader of
   the file holding an order nobody chose. And the order nobody chose is what
   was there: rows arrived split into reviewed-then-skipped simply because
   `to-review` returns two vectors, so a layer appeared to leave the stack in
   the round it converged.

   `sort-by` is stable, so rows with no :index keep the order they came in —
   which is what a report written before :index existed relies on, and what
   keeps an unnumbered orphan next to its neighbours instead of at the front."
  [rows]
  (vec (sort-by (juxt #(if (:stack? %) 1 0) #(or (:index %) 0)) rows)))

(defn- row
  "A display row for `target`: its identity, its kind, and its place in the
   stack, plus whatever this particular row reports.

   :index is OMITTED rather than nil when the target has none — the composition
   pass, and anything from a stack too short to have layers — so an unnumbered
   row is exactly the map it was before numbering existed."
  [target extra]
  (merge (cond-> {:label  (:label target)
                  :stack? (boolean (:stack? target))}
           (:index target) (assoc :index (:index target)))
         extra))

(defn review-layers
  "One entry per review target this round: what it found, or that it was not
   looked at because its patch had already converged. This is what makes the
   round legible per layer instead of as one number."
  [ctx]
  (let [counts (frequencies (keep :from-layer (:findings ctx)))]
    (in-stack-order
     (into (mapv (fn [{:keys [target]}]
                   (row target {:status   "reviewed"
                                :findings (get counts (:label target) 0)}))
                 (:reviews ctx))
           (mapv (fn [t] (row t {:status "skipped"})) (:skipped ctx))))))

(defn- finish-phase
  [ph phase ctx at]
  (let [ph (assoc ph :status "ok" :ended-at at)]
    (case phase
      ;; A record loop's two stages. What each keeps is what a reader of the
      ;; finished report has to be able to reconstruct: what the judge decided
      ;; and against what, and what the amendment cost.
      ;; :outcome as well as :verdict, and they are not alternatives to each
      ;; other: a verdict is what the judge decided, an outcome is why there is
      ;; no verdict. A phase that kept only the first renders a codex failure
      ;; exactly like a clean round.
      :judge  (assoc ph :verdict (some-> (get-in ctx [:record :verdict]) name)
                        :outcome (some-> (get-in ctx [:record :outcome]) name)
                        :findings (vec (:findings ctx)))
      :amend  (assoc ph :retreats (vec (:retreats ctx))
                        :disputes (vec (:disputes ctx)))

      :review (assoc ph :overall-correctness (:overall-correctness ctx)
                        :findings (vec (:findings ctx))
                        :layers (review-layers ctx))
      :warden (let [a (:warden ctx)]
                 (assoc ph :decision (some-> (:decision a) name)
                        :reason (:reason a)
                        :rulings (mapv #(select-keys % [:id :owner-layer :disposition
                                                        :authority :of :because])
                                       (:rulings a))))
      :fix    (let [h (last (filter #(= (:iter ctx) (:iter %)) (:history ctx)))]
                (assoc ph :fixes (vec (:fixes h)) :fixed-count (:fixed-count h)))
      ph)))

(defn- resolve-target
  "Record what the round is about to review, and seed one row per target on the
   running review phase.

   This replaces deriving the same three values from the whole-stack target's
   RESULT. They were never results: the fork point, the target list and the
   manifest are all known before an agent starts, and reading them off the
   slowest target of the round meant a watcher learned what was under review
   only once it no longer mattered — and an interrupted run never learned it.

   The seeded rows are put in stack order here, the same way the finished
   payload is, so a target sits in the same place from the moment it is named to
   the moment it reports."
  [report {:keys [base-rev files targets]}]
  (-> report
      (assoc-in [:target :base-rev] base-rev)
      (assoc-in [:target :files] (vec files))
      (assoc-in [:target :layers] (count (remove :stack? targets)))
      (update-current-phase
       "review"
       (fn [ph] (assoc ph :layers (in-stack-order targets))))))

(def ^:private row-rank
  "How far along a row is. A row only ever moves forward: events cross threads
   and can be folded out of order, and a target that flickered back to `running`
   after reporting would be the display lying about work that is done."
  {"pending" 0 "running" 1 "reviewed" 2 "skipped" 2 "error" 2})

(defn- move-target
  "Advance one target's row on the running review phase.

   Rows are matched by label, which is unique within a round: it is the layer's
   bookmark slug, or `stack` for the composition pass."
  [report {:keys [label status findings]}]
  (update-current-phase
   report "review"
   (fn [ph]
     (update ph :layers
             (fn [rows]
               (mapv (fn [row]
                       (if (and (= label (:label row))
                                (> (row-rank status 0) (row-rank (:status row) 0)))
                         (cond-> (assoc row :status status)
                           findings (assoc :findings findings))
                         row))
                     (vec rows)))))))

(defn- total-fixed
  [report]
  (->> (:rounds report)
       (mapcat :phases)
       (filter #(= "fix" (:phase %)))
       (keep :fixed-count)
       (reduce + 0)))

(defn- finalize
  [report status at]
  (let [s (name status)]
    (assoc report
           :status   s
           :ended-at at
           :summary  {:rounds         (count (:rounds report))
                      :findings-fixed (total-fixed report)
                      :final-status   s})))

;; ---- fold ----------------------------------------------------------------

(defn apply-event
  [report {:keys [event] :as ev} _clock]
  (case event
    :run-started
    (init {:run-id (:run-id ev) :cwd (:cwd ev) :base (:base ev)
           :started-at (:at ev)})

    :phase-started
    (-> report
        (open-round (:iter ev) (:at ev))
        (append-phase {:phase (name (:phase ev)) :status "running"
                       :started-at (:at ev) :ended-at nil}))

    :phase-finished
    (let [ctx (assoc (:ctx ev) :iter (:iter ev))]
      (update-current-phase report (name (:phase ev))
                            #(finish-phase % (:phase ev) ctx (:at ev))))

    :targets-resolved
    (resolve-target report ev)

    :target-moved
    (move-target report ev)

    :phase-errored
    (update-current-phase report (name (:phase ev))
                          #(assoc % :status "error" :error (:error ev)
                                    :ended-at (:at ev)))

    :run-finalized
    (-> report
        (close-current-round (:at ev))
        (finalize (:status ev) (:at ev)))

    report))

;; ---- persistence ---------------------------------------------------------

(defn persist!
  "Atomically write `report` to `path` as pretty JSON: write <path>.tmp then
   rename over `path`, so a concurrent reader never sees a half-written file."
  [report path]
  (let [tmp (str path ".tmp")]
    (fs/create-dirs (fs/parent path))
    (spit tmp (json/generate-string report {:pretty true}))
    (fs/move tmp path {:replace-existing true :atomic-move true})))
