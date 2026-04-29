(ns nido.session.launcher
  "Per-session artifacts for launching Claude Code from the nido orchestrator
   against a running session. Both files are written under
   ~/.nido/state/<instance-id>/ at session:up and removed at session:down.

     mcp.json            — MCP server config wired to this session's Postgres
                           port; passed to claude via --mcp-config so the
                           postgres MCP talks to the per-session DB rather
                           than a project-static port.

     session-context.md  — short briefing appended to claude's system prompt
                           via --append-system-prompt so the agent (running
                           with cwd=nido) knows which worktree to operate on
                           and which ports the live services are bound to."
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

(defn write-artifacts!
  "Write per-session launcher artifacts. Called from start-services! after
   services are up. session-edn is passed in so we can read DB credentials
   without re-loading from disk."
  [ctx session-edn]
  (let [instance-id  (get-in ctx [:session :instance-id])
        worktree     (get-in ctx [:session :project-dir])
        project-name (get-in ctx [:session :project-name])
        pg-port      (get-in ctx [:pg :port])
        pg-svc       (pg-service-def session-edn)]
    (when (and pg-svc pg-port)
      (let [path (mcp-config-path instance-id)]
        (io/write-json! path (mcp-config pg-svc pg-port))
        (core/log-step (str "Wrote " path))))
    (let [path (context-path instance-id)]
      (io/write-text!
       path
       (render-context {:instance-id  instance-id
                        :project-name project-name
                        :worktree     worktree
                        :app-port     (get-in ctx [:app :port])
                        :app-url      (get-in ctx [:app :url])
                        :nrepl-port   (get-in ctx [:repl :port])
                        :pg-port      pg-port}))
      (core/log-step (str "Wrote " path)))))

(defn remove-artifacts!
  "Remove per-session launcher artifacts. Called from stop-session!."
  [instance-id]
  (doseq [path [(mcp-config-path instance-id) (context-path instance-id)]]
    (when (fs/exists? path)
      (fs/delete path)
      (core/log-step (str "Removed " path)))))
