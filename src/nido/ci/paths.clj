(ns nido.ci.paths
  "Run-id minting and on-disk path derivation for local CI runs.

   A Run is rooted at <session.instance-state-dir>/<runs-root>/<run-id>/
   with the aggregate status manifest at status.edn. Each StepRun nests
   one level deeper at .../steps/<step-name>/ with its own status.edn
   and logs/."
  (:require
   [babashka.fs :as fs]))

(def ^:private run-id-tail-alphabet
  "0123456789abcdefghijkmnpqrstuvwxyz")

(defn- rand-tail [n]
  (apply str (repeatedly n #(rand-nth run-id-tail-alphabet))))

(defn mint-run-id
  "Mint an opaque run-id: YYYYMMDD-HHMMSS-<6char>. Sorts chronologically."
  []
  (let [now (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)
        fmt (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")]
    (str (.format now fmt) "-" (rand-tail 6))))

(defn run-paths
  "Derive a RunPaths map for an aggregate Run. Arguments:
     :instance-state-dir - absolute base directory provided by the session
     :runs-root          - relative subdirectory, default \"runs\"
     :run-id             - opaque run-id (minted via mint-run-id)."
  [{:keys [instance-state-dir runs-root run-id]
    :or {runs-root "runs"}}]
  (let [root (str (fs/path instance-state-dir runs-root run-id))]
    {:root root
     :status-manifest-path (str (fs/path root "status.edn"))
     :steps-dir (str (fs/path root "steps"))}))

(defn step-run-paths
  "Derive a StepRunPaths map nested under a parent RunPaths:
     <run-root>/steps/<step-name>/{status.edn, logs/, tmp/, cache/, work/}.
   :work-dir is the per-step APFS-cloned worktree the command runs in;
   isolating it keeps concurrent steps from racing on .cpcache, target/,
   .shadow-cljs/, node_modules/, etc."
  [run-paths step-name]
  (let [root (str (fs/path (:steps-dir run-paths) (name step-name)))]
    {:root root
     :status-manifest-path (str (fs/path root "status.edn"))
     :logs-dir  (str (fs/path root "logs"))
     :tmp-dir   (str (fs/path root "tmp"))
     :cache-dir (str (fs/path root "cache"))
     :work-dir  (str (fs/path root "work"))}))

(defn service-log-path
  "Path for a named service's log file within a step-run's logs dir."
  [step-run-paths service-name]
  (str (fs/path (:logs-dir step-run-paths) (str (name service-name) ".log"))))
