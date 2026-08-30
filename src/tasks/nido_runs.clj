(ns tasks.nido-runs
  "Bb task entry points for inspecting Run records.

   Usage:
     bb nido:runs:list [:state <kw>] [:trigger <kw>] [:project <kw>]
     bb nido:runs:show <run-id>"
  (:require
   [babashka.fs :as fs]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.task-args :as task-args]))

(defn- all-runs []
  (let [d (cstate/runs-dir)]
    (when (fs/exists? d)
      (->> (fs/list-dir d)
           (filter fs/directory?)
           (map (comp str fs/file-name))
           sort
           (keep runs/read-run)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  list-runs
  "bb nido:runs:list [:state <kw>] [:trigger <kw>] [:project <kw>]"
  [& args]
  (let [[_ {:keys [state trigger project]}] (task-args/split-args args)
        filter-fn (every-pred
                    (if state   #(= (keyword (str state))   (:state %))   (constantly true))
                    (if trigger #(= (keyword (str trigger)) (:trigger %)) (constantly true))
                    (if project #(= (keyword (str project)) (:project %)) (constantly true)))
        rs        (filter filter-fn (or (all-runs) []))]
    (if (empty? rs)
      (println "No runs.")
      (doseq [r rs]
        (println (format "[%-15s] %s · %s · %s"
                         (name (:state r))
                         (name (:project r))
                         (name (:trigger r))
                         (:id r)))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  show
  "bb nido:runs:show <run-id>"
  [& args]
  (let [[positionals _] (task-args/split-args args)
        run-id          (some-> (first positionals) str)]
    (if-let [r (and run-id (runs/read-run run-id))]
      (do
        (pp/pprint r)
        (let [log (cstate/run-agent-log run-id)]
          (when (fs/exists? log)
            (println "\n--- last 50 lines of agent.log ---")
            (->> (slurp log) str/split-lines (take-last 50)
                 (run! println)))))
      (do (println "No run" run-id) (System/exit 4)))))
