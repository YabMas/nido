(ns nido.session.launcher
  "Per-session artifacts written into the user-cd-able session home:

     ~/.nido/sessions/<project>/<session>/.mcp.json
     ~/.nido/sessions/<project>/<session>/CLAUDE.md
     ~/.nido/sessions/<project>/<session>/worktree -> <wt-path>

   Populated on session:up, removed on session:destroy. Internal nido
   bookkeeping (registry, session.edn, pg-data, logs) still lives under
   ~/.nido/state/<instance-id>/ — see nido.session.state."
  (:require
   [babashka.fs :as fs]
   [nido.core :as core]
   [nido.io :as io]
   [nido.session.state :as state]))

(defn- pg-service-def [session-edn]
  (->> (:services session-edn)
       (filter #(= :postgresql (:type %)))
       first))

(defn mcp-path [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) ".mcp.json")))

(defn claude-md-path [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) "CLAUDE.md")))

(defn worktree-link [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) "worktree")))

(defn- mcp-config [pg-svc pg-port]
  (let [{:keys [db-name db-user db-password]} pg-svc
        url (format "postgresql://%s:%s@localhost:%s/%s"
                    db-user db-password pg-port db-name)]
    {:mcpServers
     {:postgres
      {:type    "stdio"
       :command "npx"
       :args    ["-y" "@modelcontextprotocol/server-postgres" url]
       :env     {}}}}))

(defn- render-context
  [{:keys [project-name session-name worktree
           app-port app-url nrepl-port pg-port]}]
  (str
   "# Active nido session\n"
   "\n"
   "You are working through the nido orchestrator. Source-code edits land in\n"
   "the worktree below — NOT in nido's source tree. Use absolute paths when\n"
   "reading/writing files there, or `cd worktree` from this session home.\n"
   "\n"
   "- session: " session-name "\n"
   "- project: " project-name "\n"
   "- worktree: " worktree "\n"
   (when app-url    (str "- app: " app-url "\n"))
   (when app-port   (str "- app port: " app-port "\n"))
   (when nrepl-port (str "- nrepl port: " nrepl-port "\n"))
   (when pg-port    (str "- postgres port: " pg-port "\n"))
   "\n"
   "## Services are already running\n"
   "\n"
   "The REPL, app server, and database for this worktree are managed by\n"
   "nido. Don't run project-local scripts that spin up a REPL/app/DB —\n"
   "connect to what's already live. The postgres MCP is preconfigured to\n"
   "this session's DB.\n"))

(defn- ensure-worktree-symlink!
  "Create or refresh the `worktree` symlink inside the session-home so
   `cd worktree` reaches the code without callers needing to know the
   project's worktrees layout."
  [project-name session-name worktree]
  (let [link (worktree-link project-name session-name)]
    ;; fs/exists? follows symlinks; a dangling link returns false but
    ;; fs/sym-link? still recognises it. Always remove the existing link
    ;; (if any) before recreating so a moved worktree is reflected.
    (when (or (fs/exists? link) (fs/sym-link? link))
      (fs/delete link))
    (fs/create-sym-link link worktree)))

(defn- purge-legacy-artifacts!
  "Delete pre-refactor launcher artifacts at ~/.nido/state/<instance-id>/
   that the previous launcher wrote there. Sessions created before the
   session-home migration leave these on disk with stale port info, so
   anything (or anyone) grepping ~/.nido reads obsolete data. Idempotent."
  [instance-id]
  (when instance-id
    (let [base (state/instance-state-dir instance-id)]
      (doseq [legacy ["session-context.md" "mcp.json"]
              :let [path (str (fs/path base legacy))]
              :when (fs/exists? path)]
        (fs/delete path)
        (core/log-step (str "Removed legacy " path))))))

(defn write-artifacts!
  "Write per-session launcher artifacts into the session home. Called from
   start-services! after services are up. session-edn is passed in so we
   can read DB credentials without re-loading from disk."
  [ctx session-edn]
  (let [session-name (get-in ctx [:session :name])
        worktree     (get-in ctx [:session :project-dir])
        project-name (get-in ctx [:session :project-name])
        pg-port      (get-in ctx [:pg :port])
        pg-svc       (pg-service-def session-edn)]
    (when-not session-name
      (throw (ex-info
              "Cannot write session-home artifacts: no :name in ctx :session"
              {:project-name project-name
               :hint (str "This session was started before the session-home "
                          "migration. Run `bb nido:session:down` then `:up` "
                          "to rebuild it.")})))
    (let [home    (state/session-home-dir project-name session-name)
          ctx-doc (render-context {:session-name session-name
                                   :project-name project-name
                                   :worktree     worktree
                                   :app-port     (get-in ctx [:app :port])
                                   :app-url      (get-in ctx [:app :url])
                                   :nrepl-port   (get-in ctx [:repl :port])
                                   :pg-port      pg-port})
          mcp-doc (when (and pg-svc pg-port) (mcp-config pg-svc pg-port))]
      (fs/create-dirs home)
      (when mcp-doc
        (let [path (mcp-path project-name session-name)]
          (io/write-json! path mcp-doc)
          (core/log-step (str "Wrote " path))))
      (let [path (claude-md-path project-name session-name)]
        (io/write-text! path ctx-doc)
        (core/log-step (str "Wrote " path)))
      (try
        (ensure-worktree-symlink! project-name session-name worktree)
        (catch Exception e
          (core/log-step (str "warning: worktree symlink: " (ex-message e)))))
      (try
        (purge-legacy-artifacts! (get-in ctx [:session :instance-id]))
        (catch Exception e
          (core/log-step (str "warning: purge legacy artifacts: " (ex-message e))))))))

(defn remove-artifacts!
  "Remove the session home. Called from stop-session!. No-op if the session
   was never written there (e.g. a stale session-name lookup)."
  [project-name session-name]
  (when (and project-name session-name)
    (let [home (state/session-home-dir project-name session-name)]
      (when (fs/exists? home)
        (fs/delete-tree home)
        (core/log-step (str "Removed " home))))))
