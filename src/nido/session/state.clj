(ns nido.session.state
  (:require
   [babashka.fs :as fs]
   [nido.core :as core]
   [nido.io :as io]))

;; ---------------------------------------------------------------------------
;; Path helpers — all state lives under ~/.nido/state/<project-name>/
;; ---------------------------------------------------------------------------

(defn state-dir
  "Root state directory: ~/.nido/state/"
  []
  (str (fs/path (core/nido-home) "state")))

(defn project-state-dir
  "Per-project state directory: ~/.nido/state/<project-name>/"
  [project-name]
  (str (fs/path (state-dir) project-name)))

(defn session-state-file
  "Session state file: ~/.nido/state/<project-name>/session.edn"
  [project-name]
  (str (fs/path (project-state-dir project-name) "session.edn")))

(defn log-dir
  "Log directory: ~/.nido/state/<project-name>/logs/"
  [project-name]
  (str (fs/path (project-state-dir project-name) "logs")))

(defn log-file
  "Log file for a named service: ~/.nido/state/<project-name>/logs/<name>.log"
  [project-name service-name]
  (str (fs/path (log-dir project-name) (str (name service-name) ".log"))))

(defn pg-data-dir
  "PostgreSQL data directory: ~/.nido/state/<project-name>/pg-data/"
  [project-name]
  (str (fs/path (project-state-dir project-name) "pg-data")))

;; ---------------------------------------------------------------------------
;; Session read/write
;; ---------------------------------------------------------------------------

(defn read-session [project-name]
  (io/read-edn (session-state-file project-name)))

(defn write-session! [project-name data]
  (io/write-edn! (session-state-file project-name) data))

(defn delete-session! [project-name]
  (fs/delete-if-exists (session-state-file project-name)))

;; ---------------------------------------------------------------------------
;; Registry — global session index at ~/.nido/state/sessions.edn
;; ---------------------------------------------------------------------------

(def ^:private registry-file-path
  (delay (str (fs/path (state-dir) "sessions.edn"))))

(defn- legacy-registry-paths []
  (let [codex-home (or (System/getenv "CODEX_HOME")
                       (str (System/getProperty "user.home") "/.codex"))]
    [(str (fs/path codex-home "nido" "sessions.edn"))
     (str (fs/path codex-home "agent-cockpit" "sessions.edn"))]))

(defn read-registry []
  (let [legacy (reduce (fn [acc path]
                         (merge acc (or (io/read-edn path) {})))
                       {}
                       (legacy-registry-paths))]
    (merge legacy (or (io/read-edn @registry-file-path) {}))))

(defn write-registry! [registry]
  (io/write-edn! @registry-file-path registry))

(defn upsert-registry! [project-dir entry]
  (write-registry! (assoc (read-registry) project-dir entry)))

(defn remove-from-registry! [project-dir]
  (write-registry! (dissoc (read-registry) project-dir)))
