(ns nido.shared-pg
  "Per-project shared Postgres cluster. One long-lived RUNNING cluster at
   ~/.nido/shared/<project>/pg-data, seeded once by APFS-cloning the (stopped)
   template. All :shared-mode sessions connect to it instead of cloning their
   own PGDATA.

   Lifecycle (later tasks):
     ensure-up! / status / down! / reset! / destroy!"
  (:refer-clojure :exclude [reset!])
  (:require
   [babashka.fs :as fs]
   [nido.core :as core]
   [nido.process :as proc]
   [nido.session.services.postgresql :as pg]
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

;; ---------------------------------------------------------------------------
;; Lifecycle helpers
;; ---------------------------------------------------------------------------

(defn- template-data-dir [project-name]
  (state/template-pg-data-dir project-name))

(defn- assert-template-ready!
  "The shared cluster is seeded by cloning the template, which must exist and
   be stopped (clonefile needs no live postmaster.pid)."
  [project-name]
  (let [tdir (template-data-dir project-name)]
    (when-not (fs/exists? (str (fs/path tdir "PG_VERSION")))
      (throw (ex-info "Template not initialized — cannot seed shared cluster."
                      {:project-name project-name
                       :hint "Run `bb nido:template:pg:init :project <name>` first."})))
    (when-not (pg/template-stopped? tdir)
      (throw (ex-info "Template Postgres is running — stop it before seeding the shared cluster."
                      {:project-name project-name
                       :hint "Run `bb nido:template:pg:stop :project <name>`."})))))

(defn- start-existing!
  "Start (or adopt) the already-seeded shared cluster; return its live port."
  [project-name]
  (let [data-dir (state/shared-pg-data-dir project-name)
        log-path (state/shared-log-file project-name)
        bin-dir  (pg/find-pg-bin-dir)
        existing (pg/detect-running-postmaster data-dir)]
    (cond
      (= :running (:status existing))
      (do (core/log-step (str "Shared cluster already running (pid " (:pid existing)
                              ", port " (:port existing) ")"))
          (:port existing))

      :else
      (let [_     (when (= :stale (:status existing))
                    (core/log-step "Removing stale shared postmaster.pid")
                    (fs/delete-if-exists (:pid-file existing)))
            port  (proc/find-available-port (resolve-shared-port project-name) 200)]
        (pg/pg-ctl-start! bin-dir data-dir port log-path pg/socket-base-dir)
        (pg/wait-for-tcp! port)
        (state/write-shared-meta! project-name
                                  (merge (or (try (state/read-shared-meta project-name) (catch Exception _ nil)) {})
                                         {:project-name project-name :port port}))
        (core/log-step (str "Shared cluster running (port " port ", data-dir=" data-dir ")"))
        port))))

(defn ensure-up!
  "Ensure the project's shared cluster exists and is running. Seeds it by
   APFS-cloning the template on first call. Concurrency-safe. Returns {:port p}."
  [project-name]
  (with-lock (state/shared-lock-file project-name)
    (fn []
      (let [data-dir (state/shared-pg-data-dir project-name)
            seeded?  (fs/exists? (str (fs/path data-dir "PG_VERSION")))]
        (when-not seeded?
          (assert-template-ready! project-name)
          (fs/create-dirs (state/project-shared-dir project-name))
          (core/log-step "Seeding shared cluster: APFS-cloning template…")
          (pg/clone-pgdata! (template-data-dir project-name) data-dir)
          (state/write-shared-meta! project-name
                                    {:project-name project-name
                                     :seeded-at (core/now-iso)}))
        {:port (start-existing! project-name)}))))

(defn status
  "Print shared-cluster status for a project."
  [project-name]
  (let [data-dir (state/shared-pg-data-dir project-name)
        m        (try (state/read-shared-meta project-name) (catch Exception _ nil))
        seeded?  (fs/exists? (str (fs/path data-dir "PG_VERSION")))
        running? (and seeded? (not (pg/template-stopped? data-dir)))]
    (println "nido shared cluster:")
    (println "  project:" project-name)
    (println "  data-dir:" data-dir)
    (println "  seeded?:" seeded?)
    (println "  running?:" running?)
    (when m
      (when-let [p (:port m)] (println "  port:" p))
      (when-let [t (:seeded-at m)] (println "  seeded-at:" t)))))

(defn down!
  "Stop the shared cluster (preserve data). No-op if not running."
  [project-name]
  (let [data-dir (state/shared-pg-data-dir project-name)]
    (when (and (fs/exists? data-dir) (not (pg/template-stopped? data-dir)))
      (pg/pg-ctl-stop! data-dir)
      (core/log-step (str "Stopped shared cluster for " project-name)))))

(defn reset!
  "Stop, drop PGDATA, re-clone from template, start. Recovery path."
  [project-name]
  (with-lock (state/shared-lock-file project-name)
    (fn []
      (down! project-name)
      (let [data-dir (state/shared-pg-data-dir project-name)]
        (when (fs/exists? data-dir)
          (fs/delete-tree data-dir)
          (core/log-step (str "Dropped shared PGDATA at " data-dir))))))
  ;; ensure-up! re-takes the lock; keep reset! simple by composing.
  (ensure-up! project-name))

(defn destroy!
  "Stop and remove the shared cluster PGDATA + metadata."
  [project-name]
  (down! project-name)
  (let [dir (state/project-shared-dir project-name)]
    (when (fs/exists? dir)
      (fs/delete-tree dir)
      (core/log-step (str "Destroyed shared cluster for " project-name)))))
