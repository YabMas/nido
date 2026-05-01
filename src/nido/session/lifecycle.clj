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
   Optionally also delete the named branch."
  [project-dir wt-path branch delete-branch?]
  (when (fs/exists? wt-path)
    (core/log-step (str "git worktree remove --force " wt-path))
    (git! project-dir ["worktree" "remove" "--force" wt-path] :continue? true))
  (when delete-branch?
    (core/log-step (str "git branch -D " branch))
    (git! project-dir ["branch" "-D" branch] :continue? true)))

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
    (engine/start-session! wt-path (assoc opts :session-name name))))

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
    (engine/start-session! wt-path (assoc opts :session-name name))))

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
    (engine/start-session! wt-path (assoc opts :session-name name))))

(defn destroy!
  "Bring the named session down and remove its worktree.
   opts: {... :delete-branch? bool (default false)}
   Also accepts :delete-branch (no `?`) since `?` is a zsh glob char."
  [name opts]
  (let [{:keys [project-dir wt-path branch]} (with-context name opts)
        delete-branch? (boolean (or (:delete-branch? opts) (:delete-branch opts)))]
    (try
      (when (fs/exists? wt-path)
        (engine/stop-session! wt-path))
      (catch Exception e
        (core/log-step (str "warning: stop-session error: " (ex-message e)))))
    (remove-git-worktree! project-dir wt-path branch delete-branch?)))

(defn status
  "Print status for a named session."
  [name opts]
  (let [{:keys [wt-path]} (with-context name opts)]
    (println "session:" name)
    (println "worktree:" wt-path)
    (println "exists?:" (fs/exists? wt-path))
    (when (fs/exists? wt-path)
      (engine/session-status wt-path))))

(defn enter!
  "Drop the user into a subshell rooted at the session-home. The shell
   inherits stdio so the user can run claude / codex / whatever — those
   agents discover .mcp.json and CLAUDE.md from cwd, and `worktree/` is a
   symlink to the code inside the session-home. Returns when the user
   exits the shell."
  [name opts]
  (let [[project-name _] (resolve-project opts)
        session-home (state/session-home-dir project-name name)]
    (when-not (fs/exists? session-home)
      (throw (ex-info (str "No session home for '" name "' — is it running?")
                      {:expected session-home
                       :hint "Run `bb nido:session:up` to bring it up."})))
    (let [shell-bin (or (System/getenv "SHELL") "/bin/bash")]
      (core/log-step (str "Entering " session-home " (" shell-bin ")"))
      (shell {:dir session-home :continue true} shell-bin))))

(defn- worktree-dir?
  "A git worktree's root contains a `.git` entry (a file in linked
   worktrees, a directory in the main checkout)."
  [dir]
  (fs/exists? (fs/path dir ".git")))

(defn- find-session-names
  "Recursively enumerate worktree roots under base. A directory that is
   itself a worktree terminates the descent — its session name is its path
   relative to base (so slash-namespaced names like `fix/foo` survive)."
  [base]
  (letfn [(walk [dir]
            (cond
              (worktree-dir? dir)  [dir]
              (fs/directory? dir)  (mapcat walk (fs/list-dir dir))
              :else                []))]
    (->> (walk base)
         (map #(str (fs/relativize base %)))
         sort)))

(defn list-all-data
  "Programmatic counterpart to `list-all`. Returns
   `{:project-name <name> :worktrees-dir <path> :sessions [{...}]}` where each
   session map carries `:name :worktree :pg-port :app-port :nrepl-port :repl-pid`.
   Registry-derived ports are nil for sessions that aren't tracked (i.e. down)."
  [opts]
  (let [[project-name {:keys [directory]}] (resolve-project opts)
        base (worktrees-dir project-name directory)
        registry (state/read-registry)
        sessions (when (fs/exists? base)
                   (mapv (fn [n]
                           (let [wt-path (str (fs/path base n))
                                 entry (get registry wt-path)]
                             {:name n
                              :worktree wt-path
                              :pg-port (:pg-port entry)
                              :app-port (:app-port entry)
                              :nrepl-port (:nrepl-port entry)
                              :repl-pid (:repl-pid entry)}))
                         (find-session-names base)))]
    {:project-name project-name
     :worktrees-dir base
     :sessions (or sessions [])}))

(defn list-all
  "List every session for a project, with quick liveness info."
  [opts]
  (let [{:keys [project-name worktrees-dir sessions]} (list-all-data opts)]
    (println "project:" project-name)
    (println "worktrees-dir:" worktrees-dir)
    (if (empty? sessions)
      (println "(no sessions)")
      (doseq [{:keys [name pg-port app-port nrepl-port]} sessions]
        (let [tracked? (or pg-port app-port nrepl-port)]
          (println (str "- " name
                        (when tracked?
                          (str "  [pg=" (or pg-port "-")
                               " app=" (or app-port "-")
                               " repl=" (or nrepl-port "-") "]")))))))))
