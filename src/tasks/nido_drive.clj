(ns tasks.nido-drive
  "Manage which workstreams the coordinator's driver may advance.

   The allow-list is empty by default and this is the only way to change it, so
   the whole of phase three's blast radius is what somebody has typed here."
  (:require
   [nido.coordinator.drive :as drive]
   [nido.coordinator.workstream :as ws]
   [nido.pipeline :as pipeline]
   [nido.task-args :as task-args]))

(defn- require-opt [opts k]
  (or (some-> (get opts k) name)
      (throw (ex-info (str "Missing " k) {:hint (str "pass " k " <value>")}))))

(defn- show [[project ws-id]]
  (let [p (pipeline/of project ws-id)]
    (println (format "  %-8s %-22s %-18s %s"
                     (name project) ws-id (name (:at p))
                     (if-let [n (:next p)]
                       (str "→ " (name (:stage n)) " (" (name (:mode n)) ")")
                       "—")))))

(defn list-cmd [& _]
  (let [d (drive/driven)]
    (if (empty? d)
      (println "Nothing is being driven. `bb nido:drive:add :project <p> :ws-id <id>` to start.")
      (do (println (str (count d) " workstream(s) driven:"))
          (run! show (sort-by (comp str second) d))))))

(defn add-cmd [& args]
  (let [[_ opts] (task-args/split-args args)
        project  (keyword (require-opt opts :project))
        ws-id    (require-opt opts :ws-id)]
    (when-not (ws/read-ws project ws-id)
      (throw (ex-info (str "No workstream " ws-id " under " (name project))
                      {:project project :ws-id ws-id})))
    (drive/drive! project ws-id)
    (println "driving:")
    (show [project ws-id])
    (println "\nThe coordinator advances it one stage per tick, and only stages it")
    (println "can run without an agent of its own. `bb nido:drive:remove` stops it.")))

(defn remove-cmd [& args]
  (let [[_ opts] (task-args/split-args args)
        project  (keyword (require-opt opts :project))
        ws-id    (require-opt opts :ws-id)]
    (drive/undrive! project ws-id)
    (println (str "no longer driving " (name project) "/" ws-id
                  " — anything already in flight for it finishes on its own"))))
