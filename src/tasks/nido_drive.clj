(ns tasks.nido-drive
  "Manage which workstreams the coordinator's driver may advance.

   The allow-list is empty by default and this is the only way to change it, so
   the whole of phase three's blast radius is what somebody has typed here."
  (:require
   [nido.coordinator.drive :as drive]
   [nido.coordinator.workstream :as ws]
   [nido.coordinator.pipeline :as pipeline]
   [nido.platform.task-args :as task-args]))

(defn- require-opt [opts k]
  (or (some-> (get opts k) name)
      (throw (ex-info (str "Missing " k) {:hint (str "pass " k " <value>")}))))

(defn- decision-text
  "What the driver would do on the next tick, in words.

   The log records what it DID and stays quiet about routine skips, which is
   right for a line printed every second and wrong for the question an operator
   actually asks: why is this one not moving? That question is asked on demand,
   so it is answered here."
  [position]
  (let [d (drive/fireable position)]
    (if-let [f (:fire d)]
      (str "would fire " (name f))
      (case (:skip d)
        :terminal            "nothing left to do"
        :waiting-on-a-human  "waiting on you"
        :not-mechanical      (str "needs " (name (:stage d)) ", which no phase runs yet")
        :no-runner           (str "no runner for " (name (:stage d)))
        (str "skipped: " (name (:skip d)))))))

(defn- show [[project ws-id]]
  (let [p (pipeline/of project ws-id)]
    (println (format "  %-8s %-22s %-18s %s"
                     (name project) ws-id (name (:at p)) (decision-text p)))))

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
