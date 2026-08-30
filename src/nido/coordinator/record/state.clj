(ns nido.coordinator.record.state
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
            [clojure.string :as str]
            [nido.platform.core :as core]))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  nido-root []
  (core/nido-root))

;; coordinator-root is the with-redefs seam (private redirect target);
;; coordinator-dir is the public name callers use elsewhere.
(defn ^{:malli/schema [:=> [:cat] :Path]}
  coordinator-root []
  (str (fs/path (nido-root) "coordinator")))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  coordinator-dir [] (coordinator-root))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  queue-dir []
  (str (fs/path (coordinator-root) "queue")))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  status-path []
  (str (fs/path (coordinator-root) "status.edn")))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  halted-path []
  (str (fs/path (coordinator-root) "halted.edn")))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  pid-path []
  (str (fs/path (coordinator-root) "coordinator.pid")))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  log-path []
  (str (fs/path (coordinator-root) "coordinator.log")))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  config-path []
  (str (fs/path (coordinator-root) "config.edn")))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  driving-path
  "The allow-list of workstreams the driver may advance. Absent = nobody."
  []
  (str (fs/path (coordinator-dir) "driving.edn")))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  breakers-path []
  (str (fs/path (coordinator-root) "breakers.edn")))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  runs-dir []
  (str (fs/path (nido-root) "runs")))

(defn ^{:malli/schema [:=> [:cat :RunId] :Path]}
  run-dir [run-id]
  (str (fs/path (runs-dir) run-id)))

(defn ^{:malli/schema [:=> [:cat :RunId] :Path]}
  run-edn-path [run-id]
  (str (fs/path (run-dir run-id) "run.edn")))

(defn ^{:malli/schema [:=> [:cat :RunId] :Path]}
  run-status-path [run-id]
  (str (fs/path (run-dir run-id) "_run-status.edn")))

(defn ^{:malli/schema [:=> [:cat :RunId] :Path]}
  run-artifacts-dir [run-id]
  (str (fs/path (run-dir run-id) "artifacts")))

(defn ^{:malli/schema [:=> [:cat :RunId] :Path]}
  run-agent-log [run-id]
  (str (fs/path (run-dir run-id) "agent.log")))

(defn ^{:malli/schema [:=> [:cat :RunId] :Path]}
  run-session-home-link [run-id]
  (str (fs/path (run-dir run-id) "session-home")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  triggers-path [project]
  (str (fs/path (nido-root) "projects" (name project) "triggers.edn")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  workstreams-dir [project]
  (str (fs/path (nido-root) "projects" (name project) "workstreams")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :Path]}
  workstream-dir [project ws-id]
  (str (fs/path (workstreams-dir project) ws-id)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :Path]}
  workstream-edn-path [project ws-id]
  (str (fs/path (workstream-dir project ws-id) "workstream.edn")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :Path]}
  ws-entries-dir [project ws-id]
  (str (fs/path (workstream-dir project ws-id) "entries")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :Path]}
  ws-sessions-dir [project ws-id]
  (str (fs/path (workstream-dir project ws-id) "sessions")))

(defn- session-key
  "Filesystem-safe directory key for a session name. Lifecycle session names can
   contain '/' (e.g. a 'feat/foo' branch); used raw as a path segment they would
   nest session.edn and break enumeration in list-sessions. Percent-encode the
   path separators so the key is always a single flat segment. The true name is
   preserved in the session record's :name — this is only the on-disk dir key.
   '%' is escaped first so the encoding is unambiguous."
  [session-name]
  (-> (str session-name)
      (str/replace "%" "%25")
      (str/replace "/" "%2F")
      (str/replace "\\" "%5C")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] :Path]}
  session-dir [project ws-id session-name]
  (str (fs/path (ws-sessions-dir project ws-id) (session-key session-name))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] :Path]}
  session-edn-path [project ws-id session-name]
  (str (fs/path (session-dir project ws-id session-name) "session.edn")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  pre-unification-dir [project]
  (str (fs/path (nido-root) "projects" (name project) "_pre-unification")))

(defn ^{:malli/schema [:=> [:cat] :any]}
  ensure-dirs!
  "Create the coordinator + runs directories if absent. Idempotent."
  []
  (fs/create-dirs (coordinator-root))
  (fs/create-dirs (queue-dir))
  (fs/create-dirs (runs-dir)))
