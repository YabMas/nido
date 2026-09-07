;; src/tasks/nido_reentry.clj
(ns tasks.nido-reentry
  "Which open workstreams the ledger no longer stands behind, and where each one
   goes back to.

   The clamp moves work backwards on ledgers nobody is looking at right now, and
   a fallback discovered one push at a time — at the landing gate, by whoever was
   trying to ship — is the failure this exists to prevent. It is the same reading
   `pipeline/of` makes for one workstream, asked of all of them at once, so a
   person can see the whole effect before deciding anything about it.

   Reports and changes nothing. There is no verb here on purpose: what to do
   about a workstream that fell back is a judgement per workstream — redesign,
   re-approve, or accept — and a task that did any of them in bulk would be
   making that judgement in bulk."
  (:require
   [clojure.string :as str]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.project :as project]
   [nido.platform.task-args :as task-args]))

(defn- open-ids
  "The ids of `p`'s open workstreams, oldest first. `ws/list-ids` already answers
   [] for a project with no workstreams dir, which is the ordinary case for a
   fresh registry entry rather than something to report."
  [p]
  (->> (ws/list-ids p)
       sort
       ;; NOT some->: `:closed` is nil on exactly the workstreams this wants, so
       ;; threading through it short-circuits and the filter keeps nothing.
       (filter (fn [id] (when-let [w (ws/read-ws p id)] (nil? (:closed w)))))))

(defn ^{:malli/schema [:=> [:cat [:? :any]] [:vector :map]]}
  rows
  "One row per open workstream the ledger no longer stands behind:
   {:project :ws-id :at :next :stage :reason :detail}.

   `:at` is where the board shows it now, after the clamp; `:next` is the one
   action that moves it; `:stage` is the arc stage it OWES. The three differ and
   all three are wanted: a workstream owing :approval whose design was never
   decided is at :designed and its next action is the decision round, because the
   approval cannot be given until something has recommended it.

   A row here does not mean the clamp MOVED the workstream — a workstream that
   was already back at :designed owes its approval just as much, and leaving it
   out would report the change rather than the state."
  ([] (rows nil))
  ([projects]
   (into []
         (for [p (or (seq projects) (map keyword (keys (project/list-projects))))
               id (open-ids p)
               :let [pos (pipeline/of p id)
                     re  (:re-entry pos)]
               :when re]
           {:project p
            :ws-id   id
            :at      (:at pos)
            :next    (get-in pos [:next :stage])
            :stage   (:stage re)
            :reason  (get-in re [:because :reason])
            :detail  (get-in re [:because :detail])}))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  report
  "bb entry point. `:project <p>` narrows to one project; the default is every
   registered one. Always exits 0 — this is a reading, not a gate."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        ps       (when-let [p (:project opts)] [(keyword p)])
        rs       (rows ps)]
    (if (empty? rs)
      (println "reentry · nothing is owed — every open workstream's position is\n"
               "          carried by records the ledger still stands behind")
      (do
        (println (str "reentry · " (count rs) " open workstream"
                      (when (not= 1 (count rs)) "s")
                      " the ledger no longer stands behind\n"))
        (doseq [{:keys [project ws-id at next stage reason detail]} rs]
          (println (format "  %-7s %-22s  at %-19s next %-22s owes %-15s %s"
                           (name project) ws-id (str at) (str next)
                           (str stage) (str reason)))
          (when detail
            (doseq [l (str/split-lines (str "    " detail))] (println l)))
          (println))))
    0))
