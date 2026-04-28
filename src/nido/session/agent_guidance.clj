(ns nido.session.agent-guidance
  "Writes a CLAUDE.md at the worktree root declaring the session as
   nido-managed. Claude Code auto-loads every CLAUDE.md it finds walking
   up from CWD, so this file composes with — and explicitly overrides —
   any ancestor CLAUDE.md in the project tree, steering coding agents
   away from spinning up their own services inside a nido session."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.core :as core]))

(def ^:private marker
  "<!-- nido-managed: DO NOT EDIT (remove this line to take ownership) -->")

(def ^:private file-name "CLAUDE.md")

(defn- render
  [{:keys [instance-id worktree pg-port nrepl-port app-port]}]
  (str
   marker "\n"
   "# Nido-managed session\n"
   "\n"
   "This worktree is a nido-managed development session. The rules below\n"
   "override any conflicting instructions in ancestor CLAUDE.md files.\n"
   "\n"
   "## Services are already running — do not start your own\n"
   "\n"
   "The REPL, app server, and database for this worktree are already up and\n"
   "managed by nido. Do not invoke project-local scripts, tasks, or dev\n"
   "workflows that spin up a REPL, an app server, or a database for this\n"
   "worktree. Connect to what is already live.\n"
   "\n"
   "- instance-id: " instance-id "\n"
   "- worktree: " worktree "\n"
   (when pg-port    (str "- postgres port: " pg-port "\n"))
   (when nrepl-port (str "- nrepl port: " nrepl-port "\n"))
   (when app-port   (str "- app port: " app-port "\n"))
   "\n"
   "## Lifecycle\n"
   "\n"
   "Use nido to manage this session:\n"
   "\n"
   "- `bb nido:session:status <name>` — inspect\n"
   "- `bb nido:session:restart <name>` — restart services in this worktree\n"
   "- `bb nido:session:stop <name>` — tear down (worktree stays)\n"
   "- `bb nido:session:destroy <name>` — tear down and remove worktree\n"
   "\n"
   "## Connecting\n"
   "\n"
   "- nREPL: this worktree's `.nrepl-port` points at the live nREPL.\n"
   "- Database: connect on the postgres port above.\n"))

(defn- user-owned?
  "The file exists and does not carry the nido marker — leave it alone."
  [path]
  (and (fs/exists? path)
       (not (str/starts-with? (slurp path) marker))))

(defn write!
  "Render a session-specific CLAUDE.md into the worktree root. Skips the
   write if an existing file lacks the nido marker (user has taken
   ownership). `ctx` is the session context produced by start-services!;
   reads :session {:instance-id :project-dir} and optional
   :app :repl :pg port maps."
  [ctx]
  (let [worktree (get-in ctx [:session :project-dir])
        path (str (fs/path worktree file-name))
        content (render {:instance-id (get-in ctx [:session :instance-id])
                         :worktree worktree
                         :app-port (get-in ctx [:app :port])
                         :nrepl-port (get-in ctx [:repl :port])
                         :pg-port (get-in ctx [:pg :port])})]
    (if (user-owned? path)
      (core/log-step (str "Skipping CLAUDE.md write at " path
                          " — file lacks nido marker (user-owned)"))
      (do (spit path content)
          (core/log-step (str "Wrote " path))))))

(defn remove!
  "Remove the nido-managed CLAUDE.md from the worktree root. No-op if the
   file is missing or has been taken over by the user."
  [worktree-path]
  (let [path (str (fs/path worktree-path file-name))]
    (cond
      (not (fs/exists? path)) nil
      (user-owned? path) (core/log-step (str "Leaving " path " alone — user-owned"))
      :else (do (fs/delete path)
                (core/log-step (str "Removed " path))))))
