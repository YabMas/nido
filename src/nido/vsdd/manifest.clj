(ns nido.vsdd.manifest
  "VSDD run manifest — the structured record of a complete VSDD run.
   Manifests are the data model for introspection (UI, history, debugging)."
  (:require [nido.io :as io]))

(defn create
  "Create a new run manifest."
  [{:keys [run-id module-path run-dir]}]
  {:run-id run-id
   :module module-path
   :run-dir run-dir
   :started-at (str (java.time.Instant/now))
   :status :in-progress
   :iterations []
   :final-verdict nil})

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
