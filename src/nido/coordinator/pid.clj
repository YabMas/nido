(ns nido.coordinator.pid
  "PID file lifecycle for the background coordinator daemon. See spec
   §The coordinator daemon / Crash recovery."
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.state :as cstate]))

(defn read
  "Return the PID stored in coordinator.pid, or nil if absent / malformed."
  []
  (let [p (cstate/pid-path)]
    (when (fs/exists? p)
      (try
        (-> p slurp str/trim Long/parseLong)
        (catch Exception _ nil)))))

(defn write!
  "Write the given PID to coordinator.pid. Caller's responsibility to ensure
   the coordinator dir exists (cstate/ensure-dirs!)."
  [pid]
  (spit (cstate/pid-path) (str pid "\n")))

(defn delete!
  "Remove coordinator.pid. Idempotent."
  []
  (let [p (cstate/pid-path)]
    (when (fs/exists? p) (fs/delete p))))

(defn alive?
  "True iff the PID in coordinator.pid corresponds to a live OS process.
   Uses java.lang.ProcessHandle, which is available in babashka."
  []
  (boolean
    (when-let [pid (read)]
      (when-let [^java.util.Optional opt (java.lang.ProcessHandle/of (long pid))]
        (and (.isPresent opt)
             (.isAlive ^java.lang.ProcessHandle (.get opt)))))))
