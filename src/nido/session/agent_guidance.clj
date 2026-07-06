(ns nido.session.agent-guidance
  "Writes agent guidance files at the worktree root declaring the session as
   nido-managed. Claude Code auto-loads CLAUDE.md and Codex auto-loads
   AGENTS.md while walking up from CWD, so these files compose with — and
   explicitly override — any ancestor guidance in the project tree, steering
   coding agents away from spinning up their own services inside a nido
   session."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.core :as core]))

(def ^:private marker
  "<!-- nido-managed: DO NOT EDIT (remove this line to take ownership) -->")

(def ^:private legacy-file-names ["CLAUDE.md" "AGENTS.md"])
(def ^:private codex-override-file-name "AGENTS.override.md")

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
   "- `bb nido:session:down <name>` then `:up` — restart services in this worktree\n"
   "- `bb nido:session:down <name>` — stop (worktree + state preserved)\n"
   "- `bb nido:session:reset <name>` — nuclear recovery (drops PGDATA, re-clones template)\n"
   "- `bb nido:session:destroy <name>` — stop and remove worktree\n"
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
  "Render session-specific guidance files into the worktree root. Skips each
   write if an existing file lacks the nido marker (user has taken
   ownership). `ctx` is the session context produced by start-services!;
   reads :session {:instance-id :project-dir} and optional
   :app :repl :pg port maps."
  [ctx]
  (let [worktree (get-in ctx [:session :project-dir])
        content (render {:instance-id (get-in ctx [:session :instance-id])
                         :worktree worktree
                         :app-port (get-in ctx [:app :port])
                         :nrepl-port (get-in ctx [:repl :port])
                         :pg-port (get-in ctx [:pg :port])})]
    (doseq [file-name legacy-file-names
            :let [path (str (fs/path worktree file-name))]]
      (if (user-owned? path)
        (core/log-step (str "Skipping " file-name " write at " path
                            " — file lacks nido marker (user-owned)"))
        (do (spit path content)
            (core/log-step (str "Wrote " path)))))))

(defn write-codex-override!
  "Write Codex's worktree-local override file from the rendered session briefing.
   This leaves project-owned AGENTS.md alone while making nido's live-session
   rules the closest instruction layer when Codex starts in the worktree."
  [worktree-path briefing]
  (let [path (str (fs/path worktree-path codex-override-file-name))
        content (str marker "\n" briefing)]
    (if (user-owned? path)
      (core/log-step (str "Skipping " codex-override-file-name " write at " path
                          " — file lacks nido marker (user-owned)"))
      (do (spit path content)
          (core/log-step (str "Wrote " path))))))

(defn remove!
  "Remove nido-managed guidance files from the worktree root. No-op for files
   that are missing or have been taken over by the user."
  [worktree-path]
  (doseq [file-name (conj legacy-file-names codex-override-file-name)
          :let [path (str (fs/path worktree-path file-name))]]
    (cond
      (not (fs/exists? path)) nil
      (user-owned? path) (core/log-step (str "Leaving " path " alone — user-owned"))
      :else (do (fs/delete path)
                (core/log-step (str "Removed " path))))))
