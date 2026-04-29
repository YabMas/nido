(ns nido.session.lifecycle
  "Bundled per-session workflow: a single command creates a git worktree,
   starts an isolated nido session against it, and the inverse tears it all
   down. Sessions are named (the name is the git branch and the leaf path
   under the project's worktrees directory by default). State isolation
   relies on the engine's instance-id derivation (see
   `nido.session.engine/resolve-instance-id`).

   The worktree is an implementation detail — what the user manages here is
   the *session*. Lifecycle:

     up       — create worktree (if missing) + start session (PG + JVM + app);
                idempotent (no-op for an already-running session)
     down     — stop session, leave worktree + state on disk
     reset    — nuclear: down → drop PGDATA → re-clone template → up
     destroy  — down + remove the worktree
     status   — print state for one named session
     list-all — list all sessions for a project"
  (:refer-clojure :exclude [reset!])
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.config :as config]
   [nido.core :as core]
   [nido.session.engine :as engine]
   [nido.session.launcher :as launcher]
   [nido.session.state :as state]))

;; ---------------------------------------------------------------------------
;; Project & worktree path resolution
;; ---------------------------------------------------------------------------

(defn- abs-path [s]
  (str (fs/normalize (fs/absolutize s))))

(defn- inside? [child parent]
  (str/starts-with? (abs-path child) (abs-path parent)))

(defn resolve-project
  "Resolve the active nido project. Order:
     1. explicit :project opt
     2. cwd is at or inside a registered project's :directory
     3. exactly one project registered → use it
   Returns [project-name {:directory ... ...}]."
  [opts]
  (let [projects (config/read-projects)
        explicit (some-> (:project opts) name)
        cwd (System/getProperty "user.dir")
        from-cwd (some (fn [[name entry]]
                         (when (inside? cwd (:directory entry)) name))
                       projects)
        single (when (= 1 (count projects)) (first (keys projects)))
        chosen (or explicit from-cwd single)]
    (when-not chosen
      (throw (ex-info "Could not resolve project — pass :project <name>."
                      {:registered (vec (keys projects))})))
    (when-not (contains? projects chosen)
      (throw (ex-info (str "Unknown project: " chosen)
                      {:registered (vec (keys projects))})))
    [chosen (get projects chosen)]))

(defn worktrees-dir
  "Base directory for this project's worktrees. Resolution:
     - session.edn :worktrees-dir absolute path or `~/...` → used as-is
     - session.edn :worktrees-dir relative path             → resolved
                                                              against project-dir
     - unset                                                → default
                                                              <parent>/<project>-worktrees
   The relative form lets a project keep its worktrees inside its own
   checkout (e.g. \".worktrees\" so files like .claude/ apply to them)."
  [project-name project-dir]
  (let [session-edn (engine/load-session-edn project-name)
        configured (:worktrees-dir session-edn)]
    (cond
      (and configured (or (str/starts-with? configured "/")
                          (str/starts-with? configured "~")))
      (str (fs/expand-home configured))

      configured
      (str (fs/path project-dir configured))

      :else
      (str (fs/path (str (fs/parent project-dir))
                    (str project-name "-worktrees"))))))

(defn worktree-path
  "Filesystem path for a named session's worktree."
  [project-name project-dir name]
  (str (fs/path (worktrees-dir project-name project-dir) name)))

;; ---------------------------------------------------------------------------
;; Git worktree primitives
;; ---------------------------------------------------------------------------

(defn- git!
  "Run a git command in project-dir. Throws on non-zero exit unless :continue?."
  [project-dir args & {:keys [continue?] :or {continue? false}}]
  (let [opts (cond-> {:out :string :err :string :dir project-dir}
               continue? (assoc :continue true))
        result (apply shell opts "git" args)]
    (when (and (not continue?) (not (zero? (:exit result))))
      (throw (ex-info (str "git " (str/join " " args) " failed")
                      {:exit (:exit result) :err (:err result)})))
    result))

(defn- branch-exists? [project-dir branch]
  (zero? (:exit (git! project-dir
                      ["rev-parse" "--verify" "--quiet"
                       (str "refs/heads/" branch)]
                      :continue? true))))

(defn- fetch-base!
  "If base is an origin/* ref, fetch it so the worktree branches from the
   fresh upstream tip. For local refs, no-op. Best-effort: a failed fetch
   logs a warning rather than aborting (so offline work still functions)."
  [project-dir base]
  (when-let [[_ remote-branch] (re-matches #"origin/(.+)" base)]
    (core/log-step (str "git fetch origin " remote-branch))
    (let [r (git! project-dir ["fetch" "origin" remote-branch] :continue? true)]
      (when-not (zero? (:exit r))
        (core/log-step (str "warning: fetch failed, using local " base
                            (when-let [e (:err r)] (str " — " (str/trim e)))))))))

(defn- create-git-worktree!
  "Create a git worktree at wt-path. Checks out branch if it exists locally,
   otherwise creates a new branch from base."
  [project-dir wt-path branch base]
  (fs/create-dirs (str (fs/parent wt-path)))
  (if (branch-exists? project-dir branch)
    (do
      (core/log-step (str "git worktree add " wt-path " " branch " (existing branch)"))
      (git! project-dir ["worktree" "add" wt-path branch]))
    (do
      (fetch-base! project-dir base)
      (core/log-step (str "git worktree add " wt-path " -b " branch " " base))
      (git! project-dir ["worktree" "add" wt-path "-b" branch base]))))

(defn- remove-git-worktree!
  "Remove a git worktree. Forced (dev worktrees often have local edits).
   Optionally also delete the branch."
  [project-dir wt-path delete-branch?]
  (when (fs/exists? wt-path)
    (core/log-step (str "git worktree remove --force " wt-path))
    (git! project-dir ["worktree" "remove" "--force" wt-path] :continue? true))
  (when delete-branch?
    (let [branch (str (fs/file-name wt-path))]
      (core/log-step (str "git branch -D " branch))
      (git! project-dir ["branch" "-D" branch] :continue? true))))

;; ---------------------------------------------------------------------------
;; Public lifecycle
;; ---------------------------------------------------------------------------

(defn- with-context [name opts]
  (let [[project-name {:keys [directory]}] (resolve-project opts)
        wt-path (or (some-> (:path opts) str fs/expand-home str)
                    (worktree-path project-name directory name))
        branch (or (:branch opts) name)
        base   (or (:base opts) "origin/main")
        instance-id (engine/resolve-instance-id wt-path)]
    {:project-name project-name
     :project-dir  directory
     :name name
     :wt-path wt-path
     :branch branch
     :base base
     :instance-id instance-id}))

(defn up!
  "Bring the named session up: create its worktree if missing, then start
   PG + JVM + app. Idempotent — running on an existing live session is a
   no-op (engine/start-session! detects and short-circuits)."
  [name opts]
  (let [{:keys [project-dir wt-path branch base]} (with-context name opts)]
    (if (fs/exists? wt-path)
      (core/log-step (str "Worktree already exists at " wt-path " — starting session."))
      (create-git-worktree! project-dir wt-path branch base))
    (engine/start-session! wt-path opts)))

(defn down!
  "Stop the named session. Worktree and on-disk state are preserved."
  [name opts]
  (let [{:keys [wt-path]} (with-context name opts)]
    (when-not (fs/exists? wt-path)
      (throw (ex-info "Worktree does not exist" {:path wt-path :name name})))
    (engine/stop-session! wt-path)))

(defn restart!
  "Internal: stop then start the named session (worktree must exist).
   Used by the dashboard's restart button. Not exposed as a bb task —
   `bb nido:session:down` followed by `:up` covers the CLI case."
  [name opts]
  (down! name opts)
  (let [{:keys [wt-path]} (with-context name opts)]
    (engine/start-session! wt-path opts)))

(defn reset!
  "Nuclear recovery for a session in a bad state: stop the session
   (which drops its PGDATA), then start it again so the :postgresql
   service re-clones a fresh PGDATA from the current template. Same
   shape as the previous `refresh!` — renamed because the operation
   destroys local DB state."
  [name opts]
  (let [{:keys [wt-path]} (with-context name opts)]
    (when-not (fs/exists? wt-path)
      (throw (ex-info "Worktree does not exist" {:path wt-path :name name})))
    (try (engine/stop-session! wt-path)
         (catch Exception e
           (core/log-step (str "warning: stop during reset: " (ex-message e)))))
    (engine/start-session! wt-path opts)))

(defn destroy!
  "Bring the named session down and remove its worktree.
   opts: {... :delete-branch? bool (default false)}
   Also accepts :delete-branch (no `?`) since `?` is a zsh glob char."
  [name opts]
  (let [{:keys [project-dir wt-path]} (with-context name opts)
        delete-branch? (boolean (or (:delete-branch? opts) (:delete-branch opts)))]
    (try
      (when (fs/exists? wt-path)
        (engine/stop-session! wt-path))
      (catch Exception e
        (core/log-step (str "warning: stop-session error: " (ex-message e)))))
    (remove-git-worktree! project-dir wt-path delete-branch?)))

(defn status
  "Print status for a named session."
  [name opts]
  (let [{:keys [wt-path]} (with-context name opts)]
    (println "session:" name)
    (println "worktree:" wt-path)
    (println "exists?:" (fs/exists? wt-path))
    (when (fs/exists? wt-path)
      (engine/session-status wt-path))))

(defn claude!
  "Launch Claude Code against a running session. Invokes the system `claude`
   binary with the worktree added via --add-dir, the session's mcp.json
   loaded via --mcp-config, and the per-session briefing appended to the
   system prompt via --append-system-prompt. CWD stays at whatever the
   caller's cwd is (typically nido itself) — the worktree is reachable but
   nido's CLAUDE.md still owns the harness."
  [name opts]
  (let [{:keys [instance-id wt-path]} (with-context name opts)
        mcp-path (launcher/mcp-config-path instance-id)
        ctx-path (launcher/context-path instance-id)]
    (when-not (fs/exists? wt-path)
      (throw (ex-info (str "Worktree does not exist for session '" name "'")
                      {:path wt-path :hint "Run `bb nido:session:up` first."})))
    (when-not (fs/exists? mcp-path)
      (throw (ex-info (str "No launcher artifacts for session '" name
                           "' — is it running?")
                      {:expected mcp-path
                       :hint "Run `bb nido:session:up` to bring it up."})))
    (let [ctx-content (when (fs/exists? ctx-path) (slurp ctx-path))
          nido-dir    (core/nido-source-dir)
          base-args   ["claude"
                       "--add-dir" wt-path
                       "--mcp-config" mcp-path]
          args        (cond-> base-args
                        ctx-content (into ["--append-system-prompt" ctx-content]))]
      (core/log-step (str "Launching claude --add-dir " wt-path
                          " --mcp-config " mcp-path
                          (when ctx-content " --append-system-prompt …")
                          " (cwd=" nido-dir ")"))
      (apply shell {:dir nido-dir} args))))

(defn list-all
  "List every session for a project, with quick liveness info."
  [opts]
  (let [[project-name {:keys [directory]}] (resolve-project opts)
        base (worktrees-dir project-name directory)
        registry (state/read-registry)]
    (println "project:" project-name)
    (println "worktrees-dir:" base)
    (if-not (fs/exists? base)
      (println "(no sessions)")
      (let [names (->> (fs/list-dir base)
                       (filter fs/directory?)
                       (map (comp str fs/file-name))
                       sort)]
        (if (empty? names)
          (println "(no sessions)")
          (doseq [n names]
            (let [wt-path (str (fs/path base n))
                  entry (get registry wt-path)]
              (println (str "- " n
                            (when entry
                              (str "  [pg=" (or (:pg-port entry) "-")
                                   " app=" (or (:app-port entry) "-")
                                   " repl=" (or (:nrepl-port entry) "-") "]")))))))))))
