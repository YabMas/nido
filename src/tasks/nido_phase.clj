(ns tasks.nido-phase
  "bb task entry points for a phased workstream."
  (:require
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.work :as work]
   [nido.platform.task-args :as task-args]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  advance-cmd
  "bb nido:phase:advance :project <p> (:ws-id <id> | :ref <BR-####>) :evidence \"<what was observed>\"

   Asserts the current phase's gate with the evidence and opens the next phase. Exits 1,
   saying why, when the gate will not open: the workstream is not between phases, its
   phase has no landing recorded, the plan has no next phase, or the evidence is blank."
  [& args]
  (let [[_ {:keys [project ws-id ref evidence]}] (task-args/split-args args #{:evidence :ws-id :ref})
        p     (keyword (or project "brian"))
        id    (or ws-id (some-> (and ref (ws/find-by-ref p :notion ref)) :id))]
    (if-not id
      (do (println "phase:advance · pass :ws-id, or a :ref nido knows") (System/exit 1))
      (let [{:keys [decision opened phase of because]} (work/advance-phase! p id evidence)]
        (if (= :phase-opened decision)
          (println (str "phase:advance ok · " id " is on phase " phase " of " of " — " opened))
          (do (println (str "phase:advance refused · " because)) (System/exit 1)))))))
