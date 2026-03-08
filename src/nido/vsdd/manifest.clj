(ns nido.vsdd.manifest
  "VSDD run manifest — the structured record of a complete VSDD run.
   Manifests are the data model for introspection (UI, history, debugging)."
  (:require [nido.io :as io]
            [nido.process :as proc]))

(defn create
  "Create a new run manifest."
  [{:keys [run-id module-path run-dir]}]
  {:run-id run-id
   :module module-path
   :run-dir run-dir
   :pid (.pid (java.lang.ProcessHandle/current))
   :started-at (str (java.time.Instant/now))
   :status :in-progress
   :iterations []
   :final-verdict nil})

(defn check-liveness
  "Check if an in-progress manifest's process is still alive.
   Returns the manifest with :status updated to :interrupted if the
   owning process is gone or PID is missing. Does not mutate the file on disk."
  [manifest]
  (if (= :in-progress (:status manifest))
    (if-let [pid (:pid manifest)]
      (if (proc/process-alive? pid)
        manifest
        (assoc manifest :status :interrupted))
      ;; No PID recorded — can't verify liveness, assume interrupted
      (assoc manifest :status :interrupted))
    manifest))

(defn add-iteration
  "Append an iteration record to the manifest."
  [manifest iteration-data]
  (update manifest :iterations conj iteration-data))

(defn finalize
  "Mark the manifest as complete with a final verdict."
  [manifest verdict]
  (assoc manifest
         :status (case verdict
                   :converged :converged
                   :route-to-spec :escalated
                   :exhausted :exhausted
                   :error :error
                   :halted)
         :finished-at (str (java.time.Instant/now))
         :final-verdict verdict))

(defn manifest-path
  "Path to the manifest file within a run directory."
  [run-dir]
  (str run-dir "/manifest.edn"))

(defn save!
  "Persist the manifest to disk."
  [manifest]
  (io/write-edn! (manifest-path (:run-dir manifest)) manifest))

(defn load-manifest
  "Load a manifest from a run directory."
  [run-dir]
  (io/read-edn (manifest-path run-dir)))
