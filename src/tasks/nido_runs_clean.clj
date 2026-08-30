(ns tasks.nido-runs-clean
  "bb nido:runs:clean — list and optionally delete terminal-state Runs.

   Dry-run by default. Explicit :dry-run? false to actually delete.

   Usage:
     bb nido:runs:clean
     bb nido:runs:clean :state '#{:done :failed}'
     bb nido:runs:clean :older-than \"7d\"
     bb nido:runs:clean :project brian
     bb nido:runs:clean :dry-run? false"
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.coordinator.lane.runs-clean :as clean]
   [nido.platform.task-args :as task-args]))

(defn- short-path
  "Replace the user's home directory prefix with ~ for compact display."
  [p]
  (let [home (System/getProperty "user.home")]
    (if (str/starts-with? p home)
      (str "~" (subs p (count home)))
      p)))

(defn- coerce-state
  "Coerce :state opt to a set of keywords.
   When task-args hands us a string like \"#{:done :failed}\" (because the
   shell edn-reader preserved the set literal), read it with edn/read-string.
   Vectors, lists, and sets of keywords are normalised to a set of keywords."
  [raw]
  (cond
    (nil? raw)    nil
    (set? raw)    raw
    (coll? raw)   (into #{} (map keyword) raw)
    (string? raw) (let [parsed (try (edn/read-string raw) (catch Exception _ nil))]
                    (cond
                      (set? parsed)  parsed
                      (coll? parsed) (into #{} parsed)
                      :else          (throw (ex-info (str "Cannot parse :state: " raw) {}))))
    :else         (throw (ex-info (str "Cannot coerce :state: " raw) {}))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  clean-cmd
  "bb nido:runs:clean entry point."
  [& args]
  (let [[_ opts]  (task-args/split-args args)
        raw-state (:state opts)
        state     (coerce-state raw-state)
        project   (some-> (:project opts) keyword)
        older     (:older-than opts)
        dry?      (if (contains? opts :dry-run?)
                    (boolean (:dry-run? opts))
                    true)
        plan      (try
                    (clean/plan-clean (cond-> {}
                                       state   (assoc :state state)
                                       project (assoc :project project)
                                       older   (assoc :older-than older)))
                    (catch clojure.lang.ExceptionInfo e
                      (println "ERROR:" (ex-message e))
                      (System/exit 2)))]
    (if (empty? plan)
      (println "No runs match the filter.")
      (do
        (println (if dry? "Would delete (dry-run):" "Deleting:"))
        (doseq [{:keys [run paths]} plan]
          (println (str "  - " (:id run) "  [" (name (:state run)) "]"))
          (doseq [p paths]
            (println (str "    - " (short-path p)))))
        (println)
        (if dry?
          (println (count plan) "runs would be removed. Re-run with :dry-run? false to actually delete.")
          (do
            (clean/execute! plan)
            (println (count plan) "runs deleted.")))))))
