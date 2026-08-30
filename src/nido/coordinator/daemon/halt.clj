(ns nido.coordinator.daemon.halt
  "Halted-state file: presence of ~/.nido/coordinator/halted.edn means
   the coordinator has paused. The daemon checks `halted?` each tick
   before draining the queue. See spec §Safety brakes / Kill switch."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  halted? []
  (fs/exists? (cstate/halted-path)))

(defn ^{:malli/schema [:=> [:cat] [:maybe :HaltInfo]]}
  read-halt-info
  "Map with :source (:user | :auto), :reason (optional kw), :note (optional str),
   :halted-at (iso). Returns nil when not halted."
  []
  (when (halted?)
    (try (io/read-edn (cstate/halted-path))
         (catch Exception _ nil))))

(defn ^{:malli/schema [:=> [:cat :HaltInfo] :any]}
  halt!
  "Write halted.edn with the given metadata. Always stamps :halted-at."
  [info]
  (io/write-edn! (cstate/halted-path)
                 (assoc info :halted-at (clock/now-iso))))

(defn ^{:malli/schema [:=> [:cat] :any]}
  resume!
  "Remove halted.edn. Idempotent — no-op if absent."
  []
  (when (halted?)
    (fs/delete (cstate/halted-path))))
