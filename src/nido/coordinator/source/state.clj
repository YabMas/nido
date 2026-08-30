(ns nido.coordinator.source.state
  "Per-source-config state files under ~/.nido/coordinator/sources/.

   Each file holds the source's last-poll snapshot, breaker state,
   consecutive-failures counter, etc. Filename is the source-config-hash
   (see nido.coordinator.source.registry/config-hash)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  sources-dir []
  (str (fs/path (cstate/coordinator-root) "sources")))

(defn ^{:malli/schema [:=> [:cat :string] :Path]}
  state-path [config-hash]
  (str (fs/path (sources-dir) (str config-hash ".edn"))))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :map]]}
  read-state
  "Read the state file for a source-config-hash. Returns nil if absent
   or unparseable."
  [config-hash]
  (let [p (state-path config-hash)]
    (when (fs/exists? p)
      (try (io/read-edn p) (catch Exception _ nil)))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  write-state!
  "Write state for a source-config-hash. Creates the sources dir if missing."
  [config-hash state]
  (fs/create-dirs (sources-dir))
  (io/write-edn! (state-path config-hash) state))

(defn ^{:malli/schema [:=> [:cat :string] :any]}
  delete-state! [config-hash]
  (let [p (state-path config-hash)]
    (when (fs/exists? p) (fs/delete p))))

(defn ^{:malli/schema [:=> [:cat] [:vector :string]]}
  list-state-hashes
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
