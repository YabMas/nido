(ns nido.ci.manifest
  "Status manifest writer. The manifest is a full-snapshot EDN file that
   viewers read to display current run state. Writes are atomic: temp
   file + rename under the run's status-manifest-path.

   Concurrent service futures may ask to sync the run state; a single
   lock serialises the temp-write + rename so we never trip over a
   half-written .tmp from another writer."
  (:require
   [babashka.fs :as fs]
   [nido.io :as io]))

(def ^:private write-lock (Object.))

(defn write!
  "Atomically write data as EDN to path. Creates parent dirs. Thread-safe
   against concurrent callers targeting the same path."
  [path data]
  (when-let [parent (fs/parent path)]
    (fs/create-dirs parent))
  (locking write-lock
    (let [tmp (str path ".tmp")]
      (io/write-edn! tmp data)
      (fs/move tmp path {:replace-existing true :atomic-move true}))))

(defn sync-run!
  "Persist the current run snapshot to its status manifest."
  [run]
  (write! (get-in run [:paths :status-manifest-path]) run))
