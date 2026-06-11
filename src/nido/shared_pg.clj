(ns nido.shared-pg
  "Per-project shared Postgres cluster. One long-lived RUNNING cluster at
   ~/.nido/shared/<project>/pg-data, seeded once by APFS-cloning the (stopped)
   template. All :shared-mode sessions connect to it instead of cloning their
   own PGDATA.

   Lifecycle (later tasks):
     ensure-up! / status / down! / reset! / destroy!"
  (:require
   [babashka.fs :as fs]
   [nido.process :as proc]
   [nido.session.state :as state]))

(def ^:private shared-port-range
  "Shared clusters draw from the same range sessions use, so they never collide
   with a worktree's deterministic port (different seed → different value)."
  [5500 7500])

(defn resolve-shared-port
  "Deterministic port for a project's shared cluster, seeded by its shared dir
   so it is stable across runs and distinct from per-session ports."
  [project-name]
  (let [[low high] shared-port-range]
    (proc/deterministic-port (state/project-shared-dir project-name) low high)))

(defn with-lock
  "Run (f) while holding an exclusive OS file lock on lock-path. Creates the
   parent dir and lock file as needed. Blocks until the lock is acquired.

   Uses RandomAccessFile + FileChannel.lock() — the JVM releases the OS lock
   when the channel is closed, so no explicit FileLock.release() call is needed
   (and avoids the FileLock class not being in Babashka's class allowlist)."
  [lock-path f]
  (fs/create-dirs (fs/parent lock-path))
  (let [raf (java.io.RandomAccessFile. lock-path "rw")
        ch  (.getChannel raf)]
    (try
      (.lock ch)  ;; blocks until exclusive lock is acquired
      (try (f)
           (finally (.close ch)))  ;; closing channel releases the OS lock
      (finally (.close raf)))))
