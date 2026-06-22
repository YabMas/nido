(ns nido.template
  "Per-project Postgres template cluster. Lives at
   ~/.nido/templates/<project-name>/pg-data/ and acts as the source for APFS
   clones when a session starts with :clone-from-template on its :postgresql
   service.

   Lifecycle:
     init     — initdb a fresh template, start, apply baseline/extensions,
                stop cleanly. Idempotent on PGDATA existence.
     refresh  — start template, run project-declared :refresh-steps
                (e.g. fetch & restore a staging dump), stop cleanly.
     status   — report whether the template exists, its port, last-refresh.
     destroy  — stop if running and remove PGDATA + metadata.

   A template must always be left stopped when no nido operation is actively
   using it — cloning a running cluster would give the clone a stale
   postmaster.pid and refuse to start."
  (:require
   [babashka.fs :as fs]
   [nido.commands :as commands]
   [nido.config :as config]
   [nido.core :as core]
   [nido.session.engine :as engine]
   [nido.session.services.postgresql :as pg]
   [nido.session.state :as state]))

(defn- load-template-config
  "Extract the :templates :pg config from a project's session.edn."
  [session-edn]
  (or (get-in session-edn [:templates :pg])
      (throw (ex-info "No :templates :pg config in session.edn"
                      {:hint (str "Declare template config under "
                                  ":templates :pg in ~/.nido/projects/<name>/session.edn")}))))

(defn- resolve-project-dir
  "Look up the project's canonical source directory from the project registry."
  [project-name]
  (or (get-in (config/read-projects) [project-name :directory])
      (throw (ex-info (str "Project not registered: " project-name)
                      {:hint "Run `bb nido:project:add <name> <directory>` first."
                       :project-name project-name}))))

(defn- template-context
  "Build the template substitution context used by refresh-steps commands."
  [project-name project-dir template-cfg pg-port data-dir bin-dir]
  {:project {:name project-name :dir project-dir}
   :template {:pg (merge {:port pg-port
                          :data-dir data-dir
                          :bin-dir bin-dir}
                         (select-keys template-cfg
                                      [:db-name :db-user :schema]))}})

(defn- ensure-stopped!
  "Ensure the template cluster at data-dir is stopped. No-op if not running."
  [data-dir]
  (when (and (fs/exists? data-dir)
             (not (pg/template-stopped? data-dir)))
    (pg/pg-ctl-stop! data-dir)))

(defn- update-meta!
  "Merge fields into the template metadata file."
  [project-name patch]
  (let [existing (or (state/read-template-meta project-name) {})]
    (state/write-template-meta! project-name (merge existing patch))))

(defn- with-template-rollback
  "Protect the template from a failed refresh. APFS-clone the (stopped) template
   at `data-dir` to a `<data-dir>.refresh-bak` snapshot, run `thunk`, and on
   success discard the snapshot. On ANY throw, restore `data-dir` from the
   snapshot and rethrow — so a failed fetch or a half-completed restore can
   never leave the template destroyed.

   The protective snapshot is taken BEFORE `thunk` runs anything destructive,
   so if it can't be made (uninitialized template, cross-volume PGDATA),
   `clone-pgdata!` throws here and `thunk` never runs — the template is left
   untouched. The caller must have already stopped the cluster (cloning a
   running cluster is refused)."
  [data-dir thunk]
  (let [backup-dir (str data-dir ".refresh-bak")]
    (when (fs/exists? backup-dir)
      (fs/delete-tree backup-dir))            ; clear a stale snapshot from a prior crash
    (pg/clone-pgdata! data-dir backup-dir)
    (try
      (let [result (thunk)]
        (fs/delete-tree backup-dir)
        result)
      (catch Throwable t
        (core/log-step
         (str "Refresh failed — restoring template from pre-refresh snapshot at " data-dir))
        (ensure-stopped! data-dir)            ; can't swap a running cluster's dir
        (fs/delete-tree data-dir)
        (fs/move backup-dir data-dir)
        (throw t)))))

;; ---------------------------------------------------------------------------
;; init
;; ---------------------------------------------------------------------------

(defn init!
  "Create a fresh template cluster from scratch: initdb, start, apply
   extensions/schema, stop. Idempotent: if the template already exists, does
   nothing unless :force is set."
  [project-name & {:keys [force?] :or {force? false}}]
  (let [session-edn (engine/load-session-edn project-name)
        template-cfg (load-template-config session-edn)
        {:keys [db-name db-user port schema extensions]
         :or {db-user "user" db-name "postgres"}} template-cfg
        data-dir (state/template-pg-data-dir project-name)
        log-path (state/template-log-file project-name)
        bin-dir (pg/find-pg-bin-dir)
        already-initialized? (fs/exists? (str (fs/path data-dir "PG_VERSION")))]
    (cond
      (and already-initialized? (not force?))
      (do (core/log-step (str "Template already initialized for " project-name
                              " — use :force? true to rebuild."))
          nil)

      :else
      (do
        (when already-initialized?
          (core/log-step "Rebuilding template (force)...")
          (ensure-stopped! data-dir)
          (fs/delete-tree data-dir))
        (fs/create-dirs (state/project-template-dir project-name))
        (pg/initdb! bin-dir data-dir db-user)
        (pg/pg-ctl-start! bin-dir data-dir port log-path)
        (pg/wait-for-tcp! port)
        (try
          (let [project-dir (resolve-project-dir project-name)]
            ;; Create application DB and apply schema/extensions via the same
            ;; helper the session pg service uses. No baseline here — template
            ;; refresh is the mechanism for loading real data.
            (pg/setup-fresh-database! {:bin-dir bin-dir :pg-port port
                                         :db-user db-user :db-name db-name
                                         :schema schema :extensions extensions
                                         :baseline nil
                                         :project-dir project-dir}))
          (finally
            (pg/pg-ctl-stop! data-dir)))
        (update-meta! project-name
                      {:project-name project-name
                       :data-dir data-dir
                       :port port
                       :initialized-at (core/now-iso)})
        (core/log-step (str "Template initialized at " data-dir))))))

;; ---------------------------------------------------------------------------
;; refresh
;; ---------------------------------------------------------------------------

(defn refresh!
  "Start the template cluster and run project-declared :refresh-steps against
   it (e.g. :db/get-dump then :db/restore). Stops the cluster cleanly at the
   end so the result is APFS-clone-ready."
  [project-name]
  (let [session-edn (engine/load-session-edn project-name)
        template-cfg (load-template-config session-edn)
        project-commands (:project-commands session-edn)
        {:keys [port refresh-steps db-name db-user schema extensions]
         :or {db-user "user" db-name "postgres"}} template-cfg
        data-dir (state/template-pg-data-dir project-name)
        log-path (state/template-log-file project-name)
        bin-dir (pg/find-pg-bin-dir)
        project-dir (resolve-project-dir project-name)]
    (when-not (fs/exists? (str (fs/path data-dir "PG_VERSION")))
      (throw (ex-info "Template not initialized. Run `bb nido:template:pg:init` first."
                      {:project-name project-name})))
    (when-not (seq refresh-steps)
      (throw (ex-info "No :refresh-steps declared in :templates :pg"
                      {:project-name project-name})))
    (ensure-stopped! data-dir)
    ;; Snapshot the known-good template before the destructive dropdb!. If any
    ;; step throws (a failed staging fetch, a half-completed restore), the
    ;; snapshot is rolled back over data-dir so the template is never left
    ;; destroyed. Only on a clean run do we discard the snapshot and stamp meta.
    (with-template-rollback data-dir
      (fn []
        (pg/pg-ctl-start! bin-dir data-dir port log-path)
        (pg/wait-for-tcp! port)
        (try
          ;; pg_restore --clean cannot CASCADE; FKs from a previous refresh block
          ;; per-object DROPs. Reset the DB to init-clean state so every refresh
          ;; starts from a known empty target.
          (pg/dropdb! bin-dir port db-user db-name)
          (pg/setup-fresh-database! {:bin-dir bin-dir :pg-port port
                                     :db-user db-user :db-name db-name
                                     :schema schema :extensions extensions
                                     :baseline nil
                                     :project-dir project-dir})
          (let [ctx (template-context project-name project-dir template-cfg port data-dir bin-dir)]
            (doseq [step refresh-steps]
              (commands/run-command! project-commands step ctx)))
          (finally
            (pg/pg-ctl-stop! data-dir)))))
    (update-meta! project-name {:last-refresh-at (core/now-iso)})
    (core/log-step (str "Template refreshed for " project-name))))

;; ---------------------------------------------------------------------------
;; status / destroy
;; ---------------------------------------------------------------------------

(defn status
  "Print template status for a project."
  [project-name]
  (let [data-dir (state/template-pg-data-dir project-name)
        meta (state/read-template-meta project-name)
        initialized? (fs/exists? (str (fs/path data-dir "PG_VERSION")))
        running? (and initialized? (not (pg/template-stopped? data-dir)))]
    (println "nido template:")
    (println "  project:" project-name)
    (println "  data-dir:" data-dir)
    (println "  initialized?:" initialized?)
    (println "  running?:" running?)
    (when meta
      (when-let [port (:port meta)] (println "  port:" port))
      (when-let [t (:initialized-at meta)] (println "  initialized-at:" t))
      (when-let [t (:last-refresh-at meta)] (println "  last-refresh-at:" t)))))

(defn stop!
  "Stop the template cluster if running. No-op when not initialized or already
   stopped. Handles a stale postmaster.pid (e.g. left over from an aborted
   refresh) via pg-ctl-stop!'s kill fallback."
  [project-name]
  (let [data-dir (state/template-pg-data-dir project-name)]
    (cond
      (not (fs/exists? (str (fs/path data-dir "PG_VERSION"))))
      (core/log-step (str "Template not initialized for " project-name " — nothing to stop."))

      (pg/template-stopped? data-dir)
      (core/log-step (str "Template already stopped at " data-dir))

      :else
      (do (pg/pg-ctl-stop! data-dir)
          (core/log-step (str "Template stopped at " data-dir))))))

(defn destroy!
  "Stop (if running) and delete the template cluster entirely."
  [project-name]
  (let [data-dir (state/template-pg-data-dir project-name)]
    (ensure-stopped! data-dir)
    (when (fs/exists? data-dir)
      (fs/delete-tree data-dir)
      (core/log-step (str "Deleted template PGDATA at " data-dir)))
    (fs/delete-if-exists (state/template-meta-file project-name))))
