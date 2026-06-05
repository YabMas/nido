(ns nido.coordinator.state
  "Filesystem paths the coordinator owns.

   Layout (per spec §Directory layout summary):
     ~/.nido/coordinator/
       config.edn
       status.edn
       halted.edn   (only when auto-halted — stage 2)
       queue/<uuid>.edn
     ~/.nido/projects/<project>/triggers.edn
     ~/.nido/runs/<run-id>/{run.edn, _run-status.edn, artifacts/, agent.log, session-home}
     ~/.nido/projects/<project>/workstreams/<ws-id>/{workstream.edn, entries/, sessions/<name>/session.edn}
     ~/.nido/projects/<project>/_pre-unification/   (legacy run/ticket records archived by migration)"
  (:require [babashka.fs :as fs]
            [nido.core :as core]))

(defn nido-root []
  (str (core/nido-home)))

;; coordinator-root is the with-redefs seam (private redirect target);
;; coordinator-dir is the public name callers use elsewhere.
(defn coordinator-root []
  (str (fs/path (nido-root) "coordinator")))

(defn coordinator-dir [] (coordinator-root))

(defn queue-dir []
  (str (fs/path (coordinator-root) "queue")))

(defn status-path []
  (str (fs/path (coordinator-root) "status.edn")))

(defn halted-path []
  (str (fs/path (coordinator-root) "halted.edn")))

(defn pid-path []
  (str (fs/path (coordinator-root) "coordinator.pid")))

(defn log-path []
  (str (fs/path (coordinator-root) "coordinator.log")))

(defn config-path []
  (str (fs/path (coordinator-root) "config.edn")))

(defn breakers-path []
  (str (fs/path (coordinator-root) "breakers.edn")))

(defn runs-dir []
  (str (fs/path (nido-root) "runs")))

(defn run-dir [run-id]
  (str (fs/path (runs-dir) run-id)))

(defn run-edn-path [run-id]
  (str (fs/path (run-dir run-id) "run.edn")))

(defn run-status-path [run-id]
  (str (fs/path (run-dir run-id) "_run-status.edn")))

(defn run-artifacts-dir [run-id]
  (str (fs/path (run-dir run-id) "artifacts")))

(defn run-agent-log [run-id]
  (str (fs/path (run-dir run-id) "agent.log")))

(defn run-session-home-link [run-id]
  (str (fs/path (run-dir run-id) "session-home")))

(defn triggers-path [project]
  (str (fs/path (nido-root) "projects" (name project) "triggers.edn")))

(defn workstreams-dir [project]
  (str (fs/path (nido-root) "projects" (name project) "workstreams")))

(defn workstream-dir [project ws-id]
  (str (fs/path (workstreams-dir project) ws-id)))

(defn workstream-edn-path [project ws-id]
  (str (fs/path (workstream-dir project ws-id) "workstream.edn")))

(defn ws-entries-dir [project ws-id]
  (str (fs/path (workstream-dir project ws-id) "entries")))

(defn ws-sessions-dir [project ws-id]
  (str (fs/path (workstream-dir project ws-id) "sessions")))

(defn session-dir [project ws-id session-name]
  (str (fs/path (ws-sessions-dir project ws-id) session-name)))

(defn session-edn-path [project ws-id session-name]
  (str (fs/path (session-dir project ws-id session-name) "session.edn")))

(defn pre-unification-dir [project]
  (str (fs/path (nido-root) "projects" (name project) "_pre-unification")))

(defn ensure-dirs!
  "Create the coordinator + runs directories if absent. Idempotent."
  []
  (fs/create-dirs (coordinator-root))
  (fs/create-dirs (queue-dir))
  (fs/create-dirs (runs-dir)))
