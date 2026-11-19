(ns tasks.nido-desk
  "Bb task entry points for the desk: the person's machine as nido's boundary."
  (:require
   [nido.platform.desk :as desk]
   [nido.platform.task-args :as task-args]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  run-cmd
  "On the person's machine: hold the desk's connection to nido's host and
   perform what it asks for here."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        host     (some-> (:host opts) str)]
    (when-not host
      (binding [*out* *err*]
        (println "usage: bb nido:desk :host <ssh-host> [:remote-dir ~/Code/nido] [:remote-home <NIDO_HOME>] [:dashboard-port 8800] [:local-port <port>]"))
      (System/exit 2))
    (desk/run! (cond-> {:host host}
                 (:remote-dir opts)     (assoc :remote-dir (str (:remote-dir opts)))
                 (:remote-home opts)    (assoc :remote-home (str (:remote-home opts)))
                 (:dashboard-port opts) (assoc :dashboard-port (parse-long (str (:dashboard-port opts))))
                 (:local-port opts)     (assoc :local-port (parse-long (str (:local-port opts))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  stream-cmd
  "On nido's host, under a desk's ssh connection: print the effects requested
   from now on, one per line."
  [& args]
  (apply desk/stream! args))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  person-cmd
  "Declare where the person sits relative to this host: :here or :elsewhere."
  [& args]
  (let [where (some-> (first args) str (subs 1) keyword)]
    (if (#{:here :elsewhere} where)
      (do (desk/set-person! where)
          (println (str "desk: the person is " (name where)
                        (when (= :elsewhere where) " — effects are recorded for their desk"))))
      (do (binding [*out* *err*] (println "usage: bb nido:desk:person :here|:elsewhere"))
          (System/exit 2)))))
