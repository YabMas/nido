(ns nido.coordinator.sources.state
  "Per-source-config state files under ~/.nido/coordinator/sources/.

   Each file holds the source's last-poll snapshot, breaker state,
   consecutive-failures counter, etc. Filename is the source-config-hash
   (see nido.coordinator.sources/config-hash)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn sources-dir []
  (str (fs/path (cstate/coordinator-root) "sources")))

(defn state-path [config-hash]
  (str (fs/path (sources-dir) (str config-hash ".edn"))))

(defn read-state
  "Read the state file for a source-config-hash. Returns nil if absent
   or unparseable."
  [config-hash]
  (let [p (state-path config-hash)]
    (when (fs/exists? p)
      (try (io/read-edn p) (catch Exception _ nil)))))

(defn write-state!
  "Write state for a source-config-hash. Creates the sources dir if missing."
  [config-hash state]
  (fs/create-dirs (sources-dir))
  (io/write-edn! (state-path config-hash) state))

(defn delete-state! [config-hash]
  (let [p (state-path config-hash)]
    (when (fs/exists? p) (fs/delete p))))

(defn list-state-hashes
  "Return a vec of all source-config-hashes that have state on disk.
   Enumerates .edn files under sources-dir and strips the extension."
  []
  (if (fs/exists? (sources-dir))
    (->> (fs/list-dir (sources-dir))
         (filter #(str/ends-with? (fs/file-name %) ".edn"))
         (map #(let [n (fs/file-name %)]
                 (subs n 0 (- (count n) 4))))
         vec)
    []))
