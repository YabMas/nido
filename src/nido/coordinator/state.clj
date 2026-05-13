(ns nido.coordinator.state
  "Filesystem paths the coordinator owns.

   Layout (per spec §Directory layout summary):
     ~/.nido/coordinator/
       config.edn
       status.edn
       halted.edn   (only when auto-halted — stage 2)
       queue/<uuid>.edn
     ~/.nido/projects/<project>/triggers.edn
     ~/.nido/runs/<run-id>/{run.edn, _run-status.edn, artifacts/, agent.log, session-home}"
  (:require [babashka.fs :as fs]))

(defn nido-root []
  (fs/path (fs/home) ".nido"))

(defn coordinator-root []
  (fs/path (nido-root) "coordinator"))

(defn coordinator-dir [] (coordinator-root))

(defn queue-dir []
  (fs/path (coordinator-root) "queue"))

(defn status-path []
  (fs/path (coordinator-root) "status.edn"))

(defn config-path []
  (fs/path (coordinator-root) "config.edn"))

(defn runs-dir []
  (fs/path (nido-root) "runs"))

(defn run-dir [run-id]
  (fs/path (runs-dir) run-id))

(defn run-edn-path [run-id]
  (fs/path (run-dir run-id) "run.edn"))

(defn run-status-path [run-id]
  (fs/path (run-dir run-id) "_run-status.edn"))

(defn run-artifacts-dir [run-id]
  (fs/path (run-dir run-id) "artifacts"))

(defn run-agent-log [run-id]
  (fs/path (run-dir run-id) "agent.log"))

(defn run-session-home-link [run-id]
  (fs/path (run-dir run-id) "session-home"))

(defn triggers-path [project]
  (fs/path (nido-root) "projects" (name project) "triggers.edn"))

(defn ensure-dirs!
  "Create the coordinator + runs directories if absent. Idempotent."
  []
  (fs/create-dirs (coordinator-root))
  (fs/create-dirs (queue-dir))
  (fs/create-dirs (runs-dir)))
