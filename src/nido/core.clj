(ns nido.core
  (:require [babashka.fs :as fs]))

(defn nido-home
  "Returns the nido home directory. Defaults to ~/.nido, overridable via $NIDO_HOME."
  []
  (or (System/getenv "NIDO_HOME")
      (str (fs/path (System/getProperty "user.home") ".nido"))))

(defn log-step [message]
  (println (str "[nido] " message)))

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

