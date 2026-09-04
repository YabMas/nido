(ns nido.review.report
  "The review report: a single immutable value that is BOTH the defined report
   shape and the per-round ledger. Built by folding the engine's typed events
   (apply-event). Pure — persistence is one explicit fn (persist!)."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]))

(def schema-version 1)

(defn ^{:malli/schema [:=> [:cat :map] :ReviewReport]}
  init
  "The empty report a run folds its events into.

   `:context` on the target says what this run could reach — the ledger, the
   cache, a design record, a stance. A review outside a nido session runs
   without any of them and reports exactly as a complete one does, so a thin run
   and a full one were indistinguishable from their reports and only the second
   was worth trusting."
  [{:keys [run-id cwd base started-at context]}]
  {:schema     schema-version
   :run-id     run-id
   :status     "running"
   :target     (cond-> {:cwd cwd :base base :base-rev nil :files []}
                 context (assoc :context context))
   :started-at started-at
   :ended-at   nil
   :rounds     []
   :summary    nil
   ;; Filled by `finalize` from the terminal ctx; nil while the run is going and
   ;; nil at the end of one whose status is the whole story. See `stopped-on`.
   :reason     nil})

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  stopped-on
  "What the run stopped ON, read off its terminal ctx — as against `:status`,
   which is what it stopped AS. nil when the status says everything.

   `:unfixable` is what the loop gave up on, by the identity the pipeline tells
   findings apart with; `:parked` is every question still standing when it
   ended, oldest first, each with the round it was raised in. Both were on the
   ctx and reached nothing durable: the artifact for a run whose entire point
   was that it had something specific to hand over said `unfixable` and left the
   rest to be inferred from the last warden's prose.

   A park's identity is its handle, so it is the key of the carried map and is
   folded back into each entry — a list of anonymous questions is not a
   handover."
  [ctx]
  (let [parks (get-in ctx [:carry :parks])]
    (not-empty
     (cond-> {}
       (seq (:unfixable ctx))
       (assoc :unfixable (vec (:unfixable ctx)))

       (seq parks)
       (assoc :parked (->> parks
                           (map (fn [[handle p]] (assoc p :handle (str handle))))
                           (sort-by (juxt #(or (:since %) 0) :handle))
                           vec))))))

;; ---- round/phase helpers -------------------------------------------------

(defn- round-status
  "Derive a closed round's status from its phases. Order matters."
  [round]
  (let [phases (:phases round)
        ph     (fn [n] (last (filter #(= n (:phase %)) phases)))
        review (ph "review") warden (ph "warden") fix (ph "fix")
        judge  (ph "judge")  amend  (ph "amend")
        ;; Rows a reviewer actually opened. A skipped row is a converged layer
        ;; deliberately not re-read, which says nothing about whether the rest
        ;; of the round had anything in it.
        read-rows (remove #(= "skipped" (:status %)) (:layers review))
        nothing?  (and (seq read-rows)
                       (every? #(= "nothing-to-review" (:status %)) read-rows))]
    (cond
      (some #(= "error" (:status %)) phases)                       "failed"
      ;; A record round, whose two stages tell the same story the review's three
      ;; do: nothing left to say, something given up, or another round earned.
      (and judge (= "ok" (:status judge)) (empty? (:findings judge))) "clean"
      (and amend (seq (:retreats amend)))                          "weakened"
      (and judge amend)                                            "continued"
      judge                                                        "ended"

      ;; Before both readings below, which are the ones a round with no findings
      ;; and no warden otherwise falls into. This round has neither because it
      ;; never fanned out: the stack was holding conflict markers when it was
      ;; asked, so nothing was reviewed and "clean" would be the report asserting
      ;; a clean bill nobody issued.
      (seq (:conflicted review))                                   "stack-conflicted"

      ;; Before "clean", because the two are indistinguishable by findings
      ;; alone — both have none — and only this one had no reviewer read a line.
      (and review (= "ok" (:status review)) (nil? warden) nothing?) "nothing-to-review"
      (and review (= "ok" (:status review))
           (empty? (:findings review)) (nil? warden))              "clean"

      ;; Before both of the warden's own decisions, because the stage can end
      ;; the run over the warden's head: a park that has stood long enough stops
      ;; it whatever the warden returned. Read from the decision alone, the
      ;; round that ended a run printed `escalated` while the run printed
      ;; `unfixable`, and the two words came out of the same phase.
      (seq (:unfixable warden))                                    "unfixable"
      (= "escalate" (:decision warden))                            "escalated"

      ;; Before "continued", which is what a round holding a landed fix reads as
      ;; and what this one used to be called. A round that stopped on a conflict
      ;; it could not roll back continued nothing: the stack is holding markers,
      ;; and the fixers above the conflict were never launched.
      (seq (:conflicted fix))                                      "fix-conflicted"
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

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  in-stack-order
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
  "A display row for `target`: its identity, its kind, its place in the stack,
   the change it sits on and the patch it is identified by, plus whatever this
   particular row reports.

   :index is OMITTED rather than nil when the target has none — the composition
   pass, and anything from a stack too short to have layers — so an unnumbered
   row is exactly the map it was before numbering existed. :change, :patch-hash
   and :range-hash are omitted the same way, so a target the cache cannot key
   says nothing rather than nil.

   :change is the layer's jj change id, which is what `layers/conflicted`
   reports a conflicted stack under. It is the only join between the two, and
   without it a fix-conflicted report names twelve-character ids and holds
   nothing that maps them to layers — so the ids can be resolved only against a
   branch the reader has not opened. The composition row has none, being no
   layer.

   :patch-hash is on every row that has one, REVIEWED as much as skipped. A
   skipped row needs it to justify the skip; a reviewed row needs it to explain
   why there was no skip, and that is the direction that costs agents — the
   report could prove a hit and said nothing about a miss. With it on both, `why
   didn't this skip?` is a comparison of two numbers across rounds, rather than
   an excavation of review-cache.edn, which lives outside the run and is
   overwritten by the next run on the same branch.

   :range-hash is the composition row's only other component: its :patch-hash is
   not a patch hash at all but a key derived from the range's patch and the cut
   (see stages/composition-key), and every layer's half of that cut is now the
   :patch-hash of its own row. Carrying the range's makes the derived key
   re-derivable from the report — so a composition that failed to skip names the
   component that moved instead of leaving a reader to infer it. Not carrying it
   is why a key that missed on every rebase survived fourteen review runs."
  [target extra]
  (let [change (get-in target [:layer :change])]
    (merge (cond-> {:label  (:label target)
                    :stack? (boolean (:stack? target))}
             (:index target)      (assoc :index (:index target))
             change               (assoc :change change)
             (:patch-hash target) (assoc :patch-hash (:patch-hash target))
             (:range-hash target) (assoc :range-hash (:range-hash target)))
           extra)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  review-layers
  "One entry per review target this round: what it found, or that it was not
   looked at because its patch had already converged. This is what makes the
   round legible per layer instead of as one number.

   A reporter that contributed findings but is no layer of the stack — the
   mechanical design reviewer — gets a row too. It is not in `:reviews`,
   because it reviews the worktree rather than a range and has neither a brief
   nor a manifest to put in the warden's table of contents. Without a row here
   its findings would still be listed while every counted row read zero, which
   is the one way a summary can be worse than no summary."
  [ctx]
  (let [counts   (frequencies (keep :from-layer (:findings ctx)))
        reviewed (into #{} (map (comp :label :target)) (:reviews ctx))
        skipped  (into #{} (map :label) (:skipped ctx))
        accounted (into reviewed skipped)]
    (in-stack-order
     (-> (mapv (fn [{:keys [target] :as r}]
                 ;; A target whose diff was empty is rowed as what it was, not
                 ;; as a review that found nothing: "reviewed · 0" is the same
                 ;; two words a genuinely clean layer earns, and the reader who
                 ;; most needs them apart is the one asking why a run was free.
                 (if (= :nothing-to-review (:status r))
                   (row target {:status "nothing-to-review"})
                   (row target {:status   "reviewed"
                                :findings (get counts (:label target) 0)})))
               (:reviews ctx))
         ;; On top of the patch every row carries, a skipped row names the round
         ;; the convergence it is standing on was recorded in. Without it the
         ;; report asserts a layer needed no review and offers nothing to check
         ;; that against — and a wrongly cached convergence is precisely the
         ;; failure that hides a finding for as long as the layer sits unchanged.
         (into (mapv (fn [t] (row t (cond-> {:status "skipped"}
                                      (:converged-at t) (assoc :converged-at (:converged-at t)))))
                     (:skipped ctx)))
         (into (for [[label n] counts
                     :when (and label (not (contains? accounted label)))]
                 (row {:label label} {:status "reported" :findings n})))))))

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
      ;; What the stage actually DID, not what its name suggests. An amend phase
      ;; that spent its round re-surveying and never reached an amendment must
      ;; not report itself as having amended anything.
      :amend  (assoc ph :retreats (vec (:retreats ctx))
                        :disputes (vec (:disputes ctx))
                        :amended? (boolean (:amended? ctx))
                        :resurveyed (some-> (:resurveyed ctx) name))

      ;; :conflicted is what ended THIS round, where the copy on the target is
      ;; what the stack looked like when the run last asked. A round that stopped
      ;; on it has no findings and no warden, so without it here the only two
      ;; facts left about the round say a reviewer read the branch and liked it.
      :review (cond-> (assoc ph :overall-correctness (:overall-correctness ctx)
                             :findings (vec (:findings ctx))
                             :layers (review-layers ctx))
                (seq (:conflicted ctx)) (assoc :conflicted (vec (:conflicted ctx))))
      ;; :cause as well as :reason, and they are not alternatives: a reason says
      ;; what was wrong with the answer, a cause says whether there WAS one. A
      ;; phase that kept only the first renders a 429 exactly like a malformed
      ;; ruling.
      ;; :handle and :same-as are kept because they are the run's cross-round
      ;; identity — what no-progress?, unfixable and the answered cache all key
      ;; on. Dropped from the report, every conclusion those checks reach is
      ;; unreadable from the artifact that is supposed to explain the run: a
      ;; reader could see the same defect ruled on in four rounds and had no way
      ;; to confirm the loop knew it was the same defect.
      ;; :unfixable is the stage overruling the warden — a park that has stood
      ;; too long ends the run whatever the warden decided. It belongs on this
      ;; phase because the phase is the only place both facts sit, and a round
      ;; carrying only the overruled decision is the report contradicting the
      ;; run in its own words.
      :warden (let [a (:warden ctx)]
                 (cond-> (assoc ph :decision (some-> (:decision a) name)
                                :cause (some-> (:cause a) name)
                                :reason (:reason a)
                                :rulings (mapv #(select-keys % [:id :handle :same-as
                                                                :owner-layer :disposition
                                                                :authority :of :because])
                                               (:rulings a)))
                   (seq (:unfixable ctx)) (assoc :unfixable (vec (:unfixable ctx)))))
      ;; The finding ids a fixer was handed, not only how many. It is the join
      ;; every cross-round question needs — did this fix stop that finding coming
      ;; back — and the report held one side of it and threw the other away.
      ;; :declined comes off the ctx rather than the history entry, because a
      ;; round in which EVERY fixer declined writes no history entry at all —
      ;; which is exactly the round whose reasons a reader needs.
      ;; :rolled-back beside them, because a repair the stack refused leaves no
      ;; trace anywhere else: the commit is gone, the fixer's log says it
      ;; succeeded, and the findings come back next round looking untouched.
      ;; :conflicted and :unattempted are the account of an abort: what the stack
      ;; is holding, and which layers the stage was still going to reach. Without
      ;; the second, the only record of a fixer that never ran is the ABSENCE of
      ;; its fix-<layer>-round-N.err.log from the run dir — so the four lists
      ;; together are what makes the phase add up to every :fix ruling it held.
      :fix    (let [h (last (filter #(= (:iter ctx) (:iter %)) (:history ctx)))]
                (cond-> (assoc ph :fixes (vec (:fixes h)) :fixed-count (:fixed-count h))
                  (seq (:declined ctx))    (assoc :declined (vec (:declined ctx)))
                  (seq (:rolled-back ctx)) (assoc :rolled-back (vec (:rolled-back ctx)))
                  (seq (:conflicted ctx))  (assoc :conflicted (vec (:conflicted ctx)))
                  (seq (:unattempted ctx)) (assoc :unattempted (vec (:unattempted ctx)))))
      ;; What the stage decided about each recut, whether or not it could act.
      ;; Kept because a reshape is the only remedy a recut has — the warden
      ;; withholds it from the fixers — so an empty reshape phase is the report
      ;; saying nothing about the one path the finding was left.
      :reshape (assoc ph :reshapes (vec (:reshapes ctx)))
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

(defn- record-conflicts
  "Put the round's conflict preflight on the target, whatever it said.

   Written even when it is empty, and that is the point: `[]` is the stack read
   and found clean, and an ABSENT key is a run that never got an answer — a
   review outside a jj workspace, or a report written before this was asked.
   Four consecutive runs on one branch ended holding the same two change ids
   with no artifact saying whether they had been standing since the run before,
   which is the question that decides whether anything upstream can help."
  [report {:keys [conflicted]}]
  (assoc-in report [:target :conflicted] (vec conflicted)))

(def ^:private row-rank
  "How far along a row is. A row only ever moves forward: events cross threads
   and can be folded out of order, and a target that flickered back to `running`
   after reporting would be the display lying about work that is done."
  {"pending" 0 "running" 1 "reviewed" 2 "skipped" 2 "error" 2 "nothing-to-review" 2})

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

(defn- total-handed-to-fixers
  "How many findings were HANDED to a fixer across the run — not how many were
   verified fixed, which is a fact no stage in the loop produces.

   A fixer reporting success is a fixer's claim about its own work. What turns
   that into evidence is the next round re-reviewing the layer and not raising
   the finding again, and a run's LAST fix round has no such round after it. The
   number is honest as a count of work dispatched and dishonest as a count of
   defects removed, so it is named for the first."
  [report]
  (->> (:rounds report)
       (mapcat :phases)
       (filter #(= "fix" (:phase %)))
       (keep :fixed-count)
       (reduce + 0)))

(defn- finalize
  [report status ctx at]
  (let [s (name status)]
    (assoc report
           :status   s
           :ended-at at
           :reason   (stopped-on ctx)
           :summary  {:rounds         (count (:rounds report))
                      :findings-fixed (total-handed-to-fixers report)
                      :final-status   s})))

;; ---- fold ----------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :ReviewReport :map] :ReviewReport]}
  apply-event
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

    :stack-conflicts
    (record-conflicts report ev)

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
        (finalize (:status ev) (:ctx ev) (:at ev)))

    report))

;; ---- the design verdict --------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :ReviewReport :map] :ReviewReport]}
  with-verdict
  "The report, carrying what became of the design-verdict pass.

   Applied to an already-finalized report rather than folded as an event,
   because the pass judges the whole run and so cannot start until the run has
   ended. `:ended-at` is deliberately left where it was — it is when the REVIEW
   ended, and moving it would silently redefine the field for every report ever
   written.

   `outcome` describes the pass and not what it decided. `:outcome` is what the
   pass returned (`:answered`, `:no-answer`), `:ledger` is whether the workstream
   took the verdict (`:appended`, `:refused`, `:no-workstream`), and either half
   can be the one that went wrong: a verdict the ledger refuses is as lost as a
   verdict that was never produced, and both used to leave one stderr line. The
   verdict value is carried whole, byte-identical to what the ledger is offered,
   so a refusal can be diagnosed from the report alone.

   Keywords are written out as strings to match the rest of the report, whose
   statuses have been strings since it was a JSON document."
  [report {:keys [outcome ledger because verdict]}]
  (assoc report :design-verdict
         (cond-> {:outcome (name outcome)}
           ledger  (assoc :ledger (name ledger))
           because (assoc :because because)
           verdict (assoc :verdict verdict))))

;; ---- persistence ---------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :ReviewReport :Path] :any]}
  persist!
  "Atomically write `report` to `path` as pretty JSON: write <path>.tmp then
   rename over `path`, so a concurrent reader never sees a half-written file."
  [report path]
  (let [tmp (str path ".tmp")]
    (fs/create-dirs (fs/parent path))
    (spit tmp (json/generate-string report {:pretty true}))
    (fs/move tmp path {:replace-existing true :atomic-move true})))
