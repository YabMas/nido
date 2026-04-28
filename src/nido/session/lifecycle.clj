(ns nido.session.lifecycle
  "Bundled per-session workflow: a single command creates a git worktree,
   starts an isolated nido session against it, and the inverse tears it all
   down. Sessions are named (the name is the git branch and the leaf path
   under the project's worktrees directory by default). State isolation
   relies on the engine's instance-id derivation (see
   `nido.session.engine/resolve-instance-id`).

   The worktree is an implementation detail — what the user manages here is
   the *session*. Lifecycle (single-phase: init boots the app; stop
   tears everything down):

     init     — create worktree (if missing) + start session (PG + JVM + app)
     stop     — stop session, leave worktree
     restart  — stop + start session in existing worktree
     destroy  — stop session + remove worktree
     status   — print state for one named session
     list-all — list all sessions for a project"
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
        base   (or (:base opts) "main")
        instance-id (engine/resolve-instance-id wt-path)]
    {:project-name project-name
     :project-dir  directory
     :name name
     :wt-path wt-path
     :branch branch
     :base base
     :instance-id instance-id}))

(defn init!
  "Create the named session's worktree (if missing) and start the session.
   Idempotent: existing worktree → just start the session."
  [name opts]
  (let [{:keys [project-dir wt-path branch base]} (with-context name opts)]
    (if (fs/exists? wt-path)
      (core/log-step (str "Worktree already exists at " wt-path " — starting session."))
      (create-git-worktree! project-dir wt-path branch base))
    (engine/start-session! wt-path opts)))

(defn stop!
  "Stop the named session. The worktree is left in place."
  [name opts]
  (let [{:keys [wt-path]} (with-context name opts)]
    (when-not (fs/exists? wt-path)
      (throw (ex-info "Worktree does not exist" {:path wt-path :name name})))
    (engine/stop-session! wt-path)))

(defn restart!
  "Stop then start the named session (worktree must exist)."
  [name opts]
  (stop! name opts)
  (let [{:keys [wt-path]} (with-context name opts)]
    (engine/start-session! wt-path opts)))

(defn destroy!
  "Stop the named session and remove its worktree.
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
