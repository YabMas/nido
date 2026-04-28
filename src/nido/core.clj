(ns nido.core
  (:require [babashka.fs :as fs]))

(defn nido-home
  "Returns the nido home directory. Defaults to ~/.nido, overridable via $NIDO_HOME."
  []
  (or (System/getenv "NIDO_HOME")
      (str (fs/path (System/getProperty "user.home") ".nido"))))

(def ^:private log-lock (Object.))

(defn log-step
  "Print a single nido status line. Synchronised so concurrent step-run
   futures can't interleave their messages — without the lock, parallel
   `println` calls corrupt each other (\"[nido] step-run a starting[nido] step-run b starting\")."
  [message]
  (locking log-lock
    (println (str "[nido] " message))
    (flush)))

(defn now-iso []
  (str (java.time.Instant/now)))

(def skeleton-dirs
  ["definitions" "definitions/rules" "definitions/commands"
   "definitions/skills" "definitions/agents"
   "projects" "state"])

(defn ensure-nido-home!
  "Creates the ~/.nido/ skeleton directory structure."
  []
  (let [home (nido-home)]
    (doseq [d skeleton-dirs]
      (fs/create-dirs (str (fs/path home d))))
    (log-step (str "Ensured nido home at " home))
    home))

