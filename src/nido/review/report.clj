(ns nido.review.report
  "The review report: a single immutable value that is BOTH the defined report
   shape and the per-round ledger. Built by folding the engine's typed events
   (apply-event). Pure — persistence is one explicit fn (persist!)."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]))

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
        review (ph "review") arbiter (ph "arbiter") fix (ph "fix")]
    (cond
      (some #(= "error" (:status %)) phases)                       "failed"
      (and review (= "ok" (:status review))
           (empty? (:findings review)) (nil? arbiter))              "clean"
      (= "escalate" (:decision arbiter))                            "escalated"
      (and fix (seq (:fixes fix)))                                "continued"
      (= "stop" (:decision arbiter))                                "stopped"
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

(defn- finish-phase
  [ph phase ctx at]
  (let [ph (assoc ph :status "ok" :ended-at at)]
    (case phase
      :review (assoc ph :overall-correctness (:overall-correctness ctx)
                        :findings (vec (:findings ctx)))
      :arbiter (let [a (:arbiter ctx)]
                 (assoc ph :decision (some-> (:decision a) name)
                        :reason (:reason a)
                        :rulings (mapv #(select-keys % [:id :owner-layer :disposition
                                                        :authority :of :because])
                                       (:rulings a))))
      :warden (assoc ph :dispositions (vec (:dispositions ctx)))
      :fix    (let [h (last (filter #(= (:iter ctx) (:iter %)) (:history ctx)))]
                (assoc ph :fixes (vec (:fixes h)) :fixed-count (:fixed-count h)))
      ph)))

(defn- patch-target
  "On the first review with a base-rev, populate target base-rev + files."
  [report ctx]
  (if (and (nil? (:base-rev (:target report))) (:base-rev ctx))
    (-> report
        (assoc-in [:target :base-rev] (:base-rev ctx))
        (assoc-in [:target :files]
                  (vec (remove str/blank? (str/split-lines (or (:manifest ctx) ""))))))
    report))

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
    (let [ctx (assoc (:ctx ev) :iter (:iter ev))
          r   (update-current-phase report (name (:phase ev))
                                    #(finish-phase % (:phase ev) ctx (:at ev)))]
      (if (= :review (:phase ev)) (patch-target r ctx) r))

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
