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

(defn config-path []
  (str (fs/path (coordinator-root) "config.edn")))

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

(defn ensure-dirs!
  "Create the coordinator + runs directories if absent. Idempotent."
  []
  (fs/create-dirs (coordinator-root))
  (fs/create-dirs (queue-dir))
  (fs/create-dirs (runs-dir)))
