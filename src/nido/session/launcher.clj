(ns nido.session.launcher
  "Per-session artifacts for launching agents against a running session.
   Two parallel layouts during the migration to a user-cd-able session home:

   Legacy (instance-state-dir, opaque hash key):
     ~/.nido/state/<instance-id>/mcp.json
     ~/.nido/state/<instance-id>/session-context.md

   Session-home (human-readable, what users cd into):
     ~/.nido/sessions/<project>/<session>/.mcp.json
     ~/.nido/sessions/<project>/<session>/CLAUDE.md
     ~/.nido/sessions/<project>/<session>/worktree -> <wt-path>

   Same content in both, until readers (claude launch, TUI) move over."
  (:require
   [babashka.fs :as fs]
   [nido.core :as core]
   [nido.io :as io]
   [nido.session.state :as state]))

(defn- pg-service-def [session-edn]
  (->> (:services session-edn)
       (filter #(= :postgresql (:type %)))
       first))

(defn mcp-config-path [instance-id]
  (str (fs/path (state/instance-state-dir instance-id) "mcp.json")))

(defn context-path [instance-id]
  (str (fs/path (state/instance-state-dir instance-id) "session-context.md")))

;; -- Session-home paths (human-readable, mirror the legacy artifacts) ------

(defn session-home-mcp-path [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) ".mcp.json")))

(defn session-home-claude-md-path [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) "CLAUDE.md")))

(defn session-home-worktree-link [project-name session-name]
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
  [{:keys [instance-id project-name worktree
           app-port app-url nrepl-port pg-port]}]
  (str
   "# Active nido session\n"
   "\n"
   "You are working through the nido orchestrator. Source-code edits land in\n"
   "the worktree below — NOT in nido's source tree. Use absolute paths when\n"
   "reading/writing files there.\n"
   "\n"
   "- session: " instance-id "\n"
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
   `cd <session-home>/worktree` reaches the code without callers needing
   to know the project's worktrees layout."
  [project-name session-name worktree]
  (let [link (session-home-worktree-link project-name session-name)]
    ;; fs/exists? follows symlinks; a dangling link returns false but
    ;; fs/sym-link? still recognises it. Always remove the existing link
    ;; (if any) before recreating so a moved worktree is reflected.
    (when (or (fs/exists? link) (fs/sym-link? link))
      (fs/delete link))
    (fs/create-sym-link link worktree)))

(defn write-artifacts!
  "Write per-session launcher artifacts. Called from start-services! after
   services are up. Writes the legacy instance-state-dir layout always; if
   session-name is present in ctx, also writes the new human-readable
   session-home layout. session-edn is passed in so we can read DB
   credentials without re-loading from disk."
  [ctx session-edn]
  (let [instance-id  (get-in ctx [:session :instance-id])
        session-name (get-in ctx [:session :name])
        worktree     (get-in ctx [:session :project-dir])
        project-name (get-in ctx [:session :project-name])
        pg-port      (get-in ctx [:pg :port])
        pg-svc       (pg-service-def session-edn)
        ctx-doc      (render-context {:instance-id  instance-id
                                      :project-name project-name
                                      :worktree     worktree
                                      :app-port     (get-in ctx [:app :port])
                                      :app-url      (get-in ctx [:app :url])
                                      :nrepl-port   (get-in ctx [:repl :port])
                                      :pg-port      pg-port})
        mcp-doc      (when (and pg-svc pg-port) (mcp-config pg-svc pg-port))]
    ;; Legacy instance-state-dir layout
    (when mcp-doc
      (let [path (mcp-config-path instance-id)]
        (io/write-json! path mcp-doc)
        (core/log-step (str "Wrote " path))))
    (let [path (context-path instance-id)]
      (io/write-text! path ctx-doc)
      (core/log-step (str "Wrote " path)))
    ;; Session-home layout (skip if session-name unknown — legacy callers)
    (when session-name
      (let [home (state/session-home-dir project-name session-name)]
        (fs/create-dirs home)
        (when mcp-doc
          (let [path (session-home-mcp-path project-name session-name)]
            (io/write-json! path mcp-doc)
            (core/log-step (str "Wrote " path))))
        (let [path (session-home-claude-md-path project-name session-name)]
          (io/write-text! path ctx-doc)
          (core/log-step (str "Wrote " path)))
        (try
          (ensure-worktree-symlink! project-name session-name worktree)
          (catch Exception e
            (core/log-step (str "warning: worktree symlink: " (ex-message e)))))))))

(defn remove-artifacts!
  "Remove per-session launcher artifacts. Called from stop-session!. Cleans
   the legacy instance-state-dir files and, if the session-home layout was
   populated, removes the session-home dir too."
  [instance-id project-name session-name]
  (doseq [path [(mcp-config-path instance-id) (context-path instance-id)]]
    (when (fs/exists? path)
      (fs/delete path)
      (core/log-step (str "Removed " path))))
  (when (and project-name session-name)
    (let [home (state/session-home-dir project-name session-name)]
      (when (fs/exists? home)
        (fs/delete-tree home)
        (core/log-step (str "Removed " home))))))
