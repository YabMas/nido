(ns nido.platform.core
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]))

(defn nido-home
  "Returns the nido home directory. Defaults to ~/.nido, overridable via $NIDO_HOME."
  []
  (or (System/getenv "NIDO_HOME")
      (str (fs/path (System/getProperty "user.home") ".nido"))))

(defn nido-source-dir
  "The directory containing nido's bb.edn — i.e. the nido project root.
   Derived at runtime from where this namespace was loaded so the value is
   correct regardless of the caller's cwd. Resolves
   <root>/src/nido/platform/core.clj → <root>."
  []
  (let [url (io/resource "nido/platform/core.clj")]
    (when-not url
      (throw (ex-info "Could not resolve nido source dir from classpath" {})))
    (-> url
        .toURI
        java.io.File.
        .getParentFile      ; platform/
        .getParentFile      ; nido/
        .getParentFile      ; src/
        .getParentFile      ; project root
        .getAbsolutePath)))

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

