(ns nido.workspace
  "Per-project workspace Postgres cluster. Lives at
   ~/.nido/workspaces/<project-name>/pg-data/ and is shared across every
   session running in :shared :pg-mode. On first start the cluster is
   bootstrapped by APFS-cloning the template PGDATA, so the shared DB
   begins at the same baseline a fresh isolated session would.

   Lifecycle:
     start    — start the workspace cluster (bootstrap from template on
                first call). Idempotent.
     stop     — stop the cluster. Refuses while any session is attached.
     refresh  — start cluster, run project-declared :refresh-steps against
                it, stop again. Same recipe used for template refresh; the
                refresh steps are bound to the workspace port via context.
     status   — report bootstrap/running state and attached sessions.

   :refresh-steps are read from (:templates :pg :refresh-steps) in
   session.edn. They are rendered with `:template.pg.*` context keys
   pointing at the workspace cluster, which lets the same recipe be
   reused against either cluster without duplication."
  (:require
   [babashka.fs :as fs]
   [nido.commands :as commands]
   [nido.config :as config]
   [nido.core :as core]
   [nido.session.engine :as engine]
   [nido.session.services.postgresql :as pg]
   [nido.session.state :as state]))

(defn- load-shared-config
  "Pull :shared-config from the first :postgresql service in session.edn.
   Throws with a hint if the project hasn't declared shared-mode config."
  [session-edn]
  (let [pg-svc (some #(when (= :postgresql (:type %)) %) (:services session-edn))]
    (or (:shared-config pg-svc)
        (throw (ex-info "No :shared-config on :postgresql service"
                        {:hint (str "Declare :shared-config {:port ... :db-name ...} "
                                    "on the :postgresql service in session.edn")})))))

(defn- resolve-project-dir [project-name]
  (or (get-in (config/read-projects) [project-name :directory])
      (throw (ex-info (str "Project not registered: " project-name)
                      {:hint "Run `bb nido:project:add <name> <directory>` first."
                       :project-name project-name}))))

(defn- running?
  "True iff the workspace PGDATA exists AND it currently has a
   postmaster.pid (i.e. pg_ctl hasn't marked it stopped)."
  [project-name]
  (let [data-dir (state/workspace-pg-data-dir project-name)]
    (and (fs/exists? data-dir)
         (not (pg/template-stopped? data-dir)))))

(defn- initialized? [project-name]
  (fs/exists? (str (fs/path (state/workspace-pg-data-dir project-name) "PG_VERSION"))))

;; ---------------------------------------------------------------------------
;; Public commands
;; ---------------------------------------------------------------------------

(defn start!
  "Start (and if necessary bootstrap) the workspace PG for a project.
   Idempotent. Returns the workspace context map."
  [project-name]
  (let [session-edn (engine/load-session-edn project-name)
        {:keys [port]} (load-shared-config session-edn)]
    (when-not port
      (throw (ex-info ":shared-config :port not set" {:project-name project-name})))
    (pg/ensure-workspace-pg-running! project-name port)
    (core/log-step (str "Workspace PG for " project-name " is up on port " port))
    {:project-name project-name :port port}))

(defn stop!
  "Stop the workspace PG for a project. Refuses while any session in the
   registry is attached in :shared mode — stop those first."
  [project-name]
  (let [attached (state/attached-sessions project-name)]
    (when (seq attached)
      (throw (ex-info (str "Refusing to stop workspace PG: "
                           (count attached) " session(s) attached")
                      {:project-name project-name
                       :attached (mapv :instance-id attached)
                       :hint "Stop the attached sessions first, or start them isolated."}))))
  (pg/stop-workspace-pg! project-name)
  (core/log-step (str "Workspace PG for " project-name " is stopped")))

(defn status
  "Print workspace PG status for a project."
  [project-name]
  (let [data-dir (state/workspace-pg-data-dir project-name)
        session-edn (try (engine/load-session-edn project-name) (catch Exception _ nil))
        shared-cfg (when session-edn (try (load-shared-config session-edn) (catch Exception _ nil)))
        attached (state/attached-sessions project-name)]
    (println "nido workspace pg:")
    (println "  project:" project-name)
    (println "  data-dir:" data-dir)
    (println "  initialized?:" (initialized? project-name))
    (println "  running?:" (running? project-name))
    (when shared-cfg
      (println "  port (configured):" (:port shared-cfg))
      (println "  db-name:" (:db-name shared-cfg)))
    (println "  attached sessions:" (count attached))
    (doseq [s attached]
      (println "    -" (:instance-id s)))))

(defn refresh!
  "Run the project's :refresh-steps against the workspace cluster.
   Starts the cluster first if needed (idempotent). The session.edn
   refresh-steps reference {{template.pg.*}} — those keys are bound to
   the workspace cluster here so one recipe works for both refresh
   targets.

   Refuses while any session is attached — a dump/restore rewrites the
   shared DB under the session's feet."
  [project-name]
  (let [attached (state/attached-sessions project-name)]
    (when (seq attached)
      (throw (ex-info (str "Refusing to refresh workspace PG: "
                           (count attached) " session(s) attached")
                      {:project-name project-name
                       :attached (mapv :instance-id attached)
                       :hint "Stop the attached sessions first."}))))
  (let [session-edn (engine/load-session-edn project-name)
        shared-cfg (load-shared-config session-edn)
        project-commands (:project-commands session-edn)
        refresh-steps (get-in session-edn [:templates :pg :refresh-steps])
        project-dir (resolve-project-dir project-name)
        bin-dir (pg/find-pg-bin-dir)
        data-dir (state/workspace-pg-data-dir project-name)]
    (when-not (seq refresh-steps)
      (throw (ex-info "No :refresh-steps declared in :templates :pg"
                      {:project-name project-name})))
    ;; Ensure cluster is up for the pg_restore step to target.
    (pg/ensure-workspace-pg-running! project-name (:port shared-cfg))
    (let [ctx {:project {:name project-name :dir project-dir}
               :template {:pg (merge {:port (:port shared-cfg)
                                      :data-dir data-dir
                                      :bin-dir bin-dir}
                                     (select-keys shared-cfg
                                                  [:db-name :db-user]))}}]
      (doseq [step refresh-steps]
        (commands/run-command! project-commands step ctx)))
    (core/log-step (str "Workspace PG refreshed for " project-name))))

;; ---------------------------------------------------------------------------
;; Reclaim: delete per-instance state dirs no longer tracked in the registry.
;; Run after `session:destroy` leaves legacy state on disk, or after switching
;; a session from :isolated to :shared so its abandoned PGDATA can go away.
;; ---------------------------------------------------------------------------

(defn- tracked-instance-ids []
  (->> (state/read-registry)
       vals
       (keep :instance-id)
       set))

(defn- orphan-instance-dirs
  "State dirs under ~/.nido/state/ with no matching registry entry.
   Excludes top-level files (the registry itself, legacy metadata)."
  []
  (let [root (state/state-dir)
        tracked (tracked-instance-ids)]
    (when (fs/exists? root)
      (->> (fs/list-dir root)
           (filter fs/directory?)
           (map #(vector (str (fs/file-name %)) (str %)))
           (remove (fn [[id _]] (contains? tracked id)))
           (sort-by first)))))

(defn reclaim!
  "List orphaned per-instance state dirs. With :force? true, delete
   them. An instance is orphaned iff its id is not present in any
   registry entry. Safe to run at any time — never touches the
   registry itself or template/workspace state."
  [& {:keys [force?] :or {force? false}}]
  (let [orphans (orphan-instance-dirs)]
    (if (empty? orphans)
      (core/log-step "No orphaned state dirs found.")
      (do
        (println "Orphaned instance state dirs:")
        (doseq [[id path] orphans]
          (println (str "  " id "  — " path)))
        (if force?
          (do
            (doseq [[id path] orphans]
              (when (fs/exists? path)
                (core/log-step (str "Deleting " id " (" path ")"))
                (fs/delete-tree path)))
            (core/log-step (str "Reclaimed " (count orphans) " dir(s)."))
            :reclaimed)
          (do
            (println)
            (println (str (count orphans) " dir(s) listed. "
                          "Re-run with :force? true to delete."))
            :listed))))))
