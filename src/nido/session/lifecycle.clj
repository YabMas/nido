(ns nido.session.lifecycle
  "Bundled per-session workflow: a single command creates a worktree,
   starts an isolated nido session against it, and the inverse tears it
   all down. Sessions are named (the name is the git branch / jj bookmark
   and the leaf path under the project's worktrees directory by default).
   State isolation relies on the engine's instance-id derivation (see
   `nido.session.engine/resolve-instance-id`).

   Worktree backend depends on the source VCS:
   - jj-colocated source (has `.jj/repo/`) → `jj workspace add` produces a
     jj-only workspace (`.jj/` inside, no `.git/`). Agents in the worktree
     use `jj st` / `jj log` / `jj git push`.
   - plain git source → `git worktree add` (legacy path).
   `destroy!` detects the layer per worktree, so legacy git worktrees keep
   tearing down with `git worktree remove` even after the source becomes
   jj-colocated.

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
   [nido.session.links :as links]
   [nido.session.profiles :as profiles]
   [nido.session.services.postgresql :as pg]
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

(defn create-symlink-worktree!
  "Symlink wt-path to an existing checkout at target. Refuses if target
   is missing — we never silently point at non-existent code. Replaces a
   stale symlink at wt-path if one exists."
  [wt-path target]
  (when-not (fs/exists? target)
    (throw (ex-info (str "Symlink worktree target does not exist: " target)
                    {:wt-path wt-path :target target})))
  (fs/create-dirs (str (fs/parent wt-path)))
  (when (fs/exists? wt-path)
    (fs/delete wt-path))
  (fs/create-sym-link wt-path target)
  wt-path)

(defn remove-symlink-worktree!
  "Delete the symlink at wt-path. Refuses to recurse — the symlink
   target is shared state we never own. Safe no-op if wt-path doesn't
   exist or isn't a symlink (prevents accidental deletion of real dirs)."
  [wt-path]
  (when (and (fs/exists? wt-path) (fs/sym-link? wt-path))
    (fs/delete wt-path)))

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
;; jj workspace primitives
;; ---------------------------------------------------------------------------

(defn- jj!
  "Run a jj command in `dir`. Throws on non-zero exit unless :continue?."
  [dir args & {:keys [continue?] :or {continue? false}}]
  (let [opts (cond-> {:out :string :err :string :dir dir}
               continue? (assoc :continue true))
        result (apply shell opts "jj" args)]
    (when (and (not continue?) (not (zero? (:exit result))))
      (throw (ex-info (str "jj " (str/join " " args) " failed")
                      {:exit (:exit result) :err (:err result)})))
    result))

(defn- jj-source-repo?
  "True if `dir` is a jj-colocated source repo. The source's `.jj/repo` is a
   directory (the actual jj data); a workspace's `.jj/repo` is a pointer file."
  [dir]
  (fs/directory? (str (fs/path dir ".jj" "repo"))))

(defn- jj-workspace?
  "True if `wt-path` is a jj workspace (has a `.jj/` dir of any shape)."
  [wt-path]
  (fs/exists? (str (fs/path wt-path ".jj"))))

(defn- bookmark-exists?
  "True if a jj bookmark named `branch` exists in `project-dir`.

   `jj bookmark list <name>` exits 0 whether or not the name matches — a
   miss yields empty stdout, a hit yields the name. So existence is read
   from stdout, and any non-zero exit means jj couldn't answer (stale or
   locked working copy, corrupt repo, …). We deliberately don't pass
   `:continue?` here: `jj!` throws on that, surfacing the real failure
   rather than silently reporting the bookmark as missing — which would
   trigger a spurious `bookmark create` that fails the same way."
  [project-dir branch]
  (let [r (jj! project-dir ["bookmark" "list" branch "-T" "name ++ \"\\n\""])]
    (not (str/blank? (:out r)))))

(defn- git-ref->jj-revset
  "Translate a git-style ref (`origin/main`) into jj revset syntax
   (`main@origin`). Other refs pass through unchanged — jj accepts
   bookmark names, change/commit IDs, and arbitrary revsets directly."
  [ref]
  (if-let [[_ branch] (re-matches #"origin/(.+)" ref)]
    (str branch "@origin")
    ref))

(defn- create-jj-workspace!
  "Create a jj workspace at wt-path tracking bookmark `branch`. If the
   bookmark doesn't exist yet, fetch `base` (when it's a remote ref) and
   create the bookmark at it. The workspace lands at a new empty change on
   top of the bookmark, mirroring `git worktree add`'s behavior of putting
   you on a fresh branch tip ready to commit."
  [project-dir wt-path branch base]
  (fs/create-dirs (str (fs/parent wt-path)))
  (when-not (bookmark-exists? project-dir branch)
    (fetch-base! project-dir base)
    (let [jj-base (git-ref->jj-revset base)]
      (core/log-step (str "jj bookmark create " branch " -r " jj-base))
      (jj! project-dir ["bookmark" "create" branch "-r" jj-base])))
  (core/log-step (str "jj workspace add " wt-path
                      " --name " branch " --revision " branch))
  (jj! project-dir ["workspace" "add"
                    "--name" branch
                    "--revision" branch
                    wt-path]))

(defn- remove-jj-workspace!
  "Forget the jj workspace named `workspace-name`, then delete the
   workspace directory (which is what holds its `.jj/`). Optionally
   delete the bookmark too. `jj workspace forget` only drops jj's
   metadata pointer — the on-disk dir must be removed separately."
  [project-dir wt-path workspace-name branch delete-branch?]
  (when (fs/exists? wt-path)
    (core/log-step (str "jj workspace forget " workspace-name))
    (jj! project-dir ["workspace" "forget" workspace-name] :continue? true)
    (core/log-step (str "rm -rf " wt-path))
    (fs/delete-tree wt-path))
  (when delete-branch?
    (core/log-step (str "jj bookmark delete " branch))
    (jj! project-dir ["bookmark" "delete" branch] :continue? true)))

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
   no-op (engine/start-session! detects and short-circuits).

   Worktree creation routes by source-repo VCS: jj-colocated → `jj
   workspace add`; plain git → `git worktree add`."
  [name opts]
  (let [{:keys [project-name project-dir wt-path branch base]} (with-context name opts)
        profile-kw (or (:session-profile opts) :full)
        profile    (profiles/resolve-profile project-name profile-kw)]
    (if (fs/exists? wt-path)
      (core/log-step (str "Worktree already exists at " wt-path " — starting session."))
      (cond
        (= :symlink (-> profile :worktree :strategy))
        (create-symlink-worktree! wt-path (-> profile :worktree :target))

        (jj-source-repo? project-dir)
        (create-jj-workspace! project-dir wt-path branch base)

        :else
        (create-git-worktree! project-dir wt-path branch base)))
    (engine/start-session! wt-path (assoc opts :session-name name :profile profile))))

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

(defn- effective-pg-mode
  "Resolved PG mode for a session: the per-session override if set, else the
   project's :postgresql service-def mode."
  [project-name instance-id]
  (or (:mode (state/read-pg-mode-override instance-id))
      (let [svc (->> (:services (engine/load-session-edn project-name))
                     (filter #(= :postgresql (:type %)))
                     first)]
        (pg/resolve-pg-mode (or svc {})))))

(defn reset!
  "Nuclear recovery for a session in a bad state: stop the session,
   drop its PGDATA, then start it again so the :postgresql service
   re-clones a fresh PGDATA from the current template. Distinct from
   `down!` + `up!` (which preserves PGDATA) — call this when the local
   DB is wedged or after `bb nido:template:pg:refresh` to pick up the
   new template content.

   Refuses for sessions on the shared cluster — a per-session reset there
   would reset the DB for every session. Use `bb nido:shared:pg:reset`."
  [name opts]
  (let [{:keys [wt-path project-name instance-id]} (with-context name opts)]
    (when (= :shared (effective-pg-mode project-name instance-id))
      (throw (ex-info (str "This session uses the shared cluster — a per-session "
                           "reset would reset the shared DB for every session. Use "
                           "`bb nido:shared:pg:reset :project " project-name "` instead, "
                           "or `bb nido:session:isolate` this session first.")
                      {:project-name project-name :session name})))
    (when-not (fs/exists? wt-path)
      (throw (ex-info "Worktree does not exist" {:path wt-path :name name})))
    (try (engine/stop-session! wt-path)
         (catch Exception e
           (core/log-step (str "warning: stop during reset: " (ex-message e)))))
    (let [pg-data (state/pg-data-dir instance-id)]
      (when (fs/exists? pg-data)
        (core/log-step (str "Dropping PGDATA at " pg-data))
        (fs/delete-tree pg-data)))
    (engine/start-session! wt-path (assoc opts :session-name name))))

(defn isolate!
  "Switch a session to a PRIVATE Postgres clone (for destructive tests): set a
   per-session :isolated override, then stop+start so it re-provisions its own
   PGDATA from the template. Other sessions are unaffected. Reverse with `share!`."
  [name opts]
  (let [{:keys [wt-path instance-id]} (with-context name opts)]
    (when-not (fs/exists? wt-path)
      (throw (ex-info "Worktree does not exist" {:path wt-path :name name})))
    (state/write-pg-mode-override! instance-id :isolated)
    (try (engine/stop-session! wt-path)
         (catch Exception e
           (core/log-step (str "warning: stop during isolate: " (ex-message e)))))
    (engine/start-session! wt-path (assoc opts :session-name name))))

(defn share!
  "Switch a session back to the shared cluster: clear its :isolated override,
   drop its private PGDATA, then stop+start so it re-provisions against the
   shared cluster. Safe to call on an already-shared session."
  [name opts]
  (let [{:keys [wt-path instance-id]} (with-context name opts)]
    (when-not (fs/exists? wt-path)
      (throw (ex-info "Worktree does not exist" {:path wt-path :name name})))
    (state/clear-pg-mode-override! instance-id)
    (try (engine/stop-session! wt-path)
         (catch Exception e
           (core/log-step (str "warning: stop during share: " (ex-message e)))))
    (let [pg-data (state/pg-data-dir instance-id)]
      (when (fs/exists? pg-data)
        (core/log-step (str "Dropping private PGDATA at " pg-data))
        (fs/delete-tree pg-data)))
    (engine/start-session! wt-path (assoc opts :session-name name))))

(defn destroy!
  "Bring the named session down, drop its instance state-dir (PGDATA,
   logs, session.edn) and remove its worktree.
   opts: {... :delete-branch? bool (default false)}
   Also accepts :delete-branch (no `?`) since `?` is a zsh glob char.

   The worktree-removal layer is detected from the persisted profile
   (written at session-up). :symlink sessions remove the symlink only —
   the shared checkout is never touched. For VCS-backed worktrees the
   legacy detection applies: `.jj/` dir → `jj workspace forget`; a
   `.git` entry → `git worktree remove`. Mixed-vintage trees (a project
   that became jj-colocated after some sessions were already created with
   `git worktree`) tear down on the same path they were created with."
  [name opts]
  (let [{:keys [project-dir wt-path branch]} (with-context name opts)
        delete-branch? (boolean (or (:delete-branch? opts) (:delete-branch opts)))
        instance-id (engine/resolve-instance-id wt-path)
        ;; Read persisted profile BEFORE dropping state-dir (profile lives inside it).
        profile (engine/read-profile-for-session wt-path)]
    (try
      (when (fs/exists? wt-path)
        (engine/stop-session! wt-path))
      (catch Exception e
        (core/log-step (str "warning: stop-session error: " (ex-message e)))))
    (let [state-dir (state/instance-state-dir instance-id)]
      (when (fs/exists? state-dir)
        (core/log-step (str "Dropping instance state-dir at " state-dir))
        (fs/delete-tree state-dir)))
    (cond
      (= :symlink (-> profile :worktree :strategy))
      (remove-symlink-worktree! wt-path)

      (and (fs/exists? wt-path) (jj-workspace? wt-path))
      (remove-jj-workspace! project-dir wt-path branch branch delete-branch?)

      :else
      (remove-git-worktree! project-dir wt-path branch delete-branch?))))

(defn status
  "Print status for a named session."
  [name opts]
  (let [{:keys [wt-path]} (with-context name opts)]
    (println "session:" name)
    (println "worktree:" wt-path)
    (println "exists?:" (fs/exists? wt-path))
    (when (fs/exists? wt-path)
      (engine/session-status wt-path))))

(defn cd-target-file
  "File the parent shell wrapper polls after `bb nido:tui` exits to decide
   where to `cd`. Located inside `~/.nido/` so it's per-user and survives
   reboots. The wrapper is responsible for removing it before invoking bb;
   we only ever write."
  []
  (str (fs/path (core/nido-home) ".last-cd")))

(defn- parse-cd-target
  "Normalize the user-supplied `:cd` value (symbol from edn/read-string,
   keyword, or string) to one of `:home` / `:worktree`. Defaults to
   `:home` when nil. Throws on anything else with the valid set in the
   message."
  [v]
  (let [s (cond
            (nil? v)                    "home"
            (or (keyword? v) (symbol? v)) (name v)
            :else                       (str v))]
    (case s
      "home"     :home
      "worktree" :worktree
      (throw (ex-info (str "Invalid :cd value " (pr-str v))
                      {:value v
                       :valid #{"home" "worktree"}
                       :hint "Pass :cd home (default) or :cd worktree"})))))

(defn enter!
  "Hand off a cwd to the parent shell via `cd-target-file`. bb cannot change
   its parent's cwd, so a tiny zsh function (see Nido's CLAUDE.md) reads
   this file after the bb task exits and `cd`s the user there.

   `:cd` selects the target:
     :home (default) — the session-home (CLAUDE.md, .mcp.json live here)
     :worktree       — the worktree symlink inside session-home, with a
                       fallback to the on-disk worktree path when the
                       session-home is gone (downed sessions retain
                       their worktree).

   `:auto-up?` (default false) — call `up!` first. Idempotent on a running
   session. Used by the TUI runs screen so `↵` on a downed run
   transparently resumes the session.

   Throws if `:cd home` is requested without `:auto-up?` and the
   session-home is missing, or if `:cd worktree` is requested and neither
   the session-home symlink nor the on-disk worktree exists."
  [name opts]
  (when (:auto-up? opts) (up! name opts))
  (let [[project-name project] (resolve-project opts)
        cd-target    (parse-cd-target (:cd opts))
        session-home (state/session-home-dir project-name name)
        home-exists? (fs/exists? session-home)]
    (case cd-target
      :home
      (do
        (when-not home-exists?
          (throw (ex-info (str "No session home for '" name "' — is it running?")
                          {:expected session-home
                           :hint "Run `bb nido:session:up` to bring it up."})))
        (let [target (cd-target-file)]
          (fs/create-dirs (fs/parent target))
          (spit target session-home)
          (core/log-step (str "Selected " session-home))))

      :worktree
      (let [via-home   (str (fs/path session-home "worktree"))
            on-disk    (worktree-path project-name (:directory project) name)
            resolved   (cond
                         (and home-exists? (fs/exists? via-home)) via-home
                         (fs/exists? on-disk)                     on-disk
                         :else                                    nil)]
        (when-not resolved
          (throw (ex-info (str "Worktree no longer exists for '" name "'")
                          {:session-home via-home
                           :on-disk      on-disk
                           :hint "Run `bb nido:session:up` to recreate the worktree."})))
        (let [target (cd-target-file)]
          (fs/create-dirs (fs/parent target))
          (spit target resolved)
          (core/log-step (str "Selected " resolved)))))))

(defn- worktree-dir?
  "True if `dir` is a session worktree root. A git worktree's root has a
   `.git` entry (a file in linked worktrees, a directory in the main
   checkout); a jj workspace's root has a `.jj/` entry instead."
  [dir]
  (or (fs/exists? (fs/path dir ".git"))
      (fs/exists? (fs/path dir ".jj"))))

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

;; ---------------------------------------------------------------------------
;; Link tracking (notion tickets, GH PRs, slack threads, etc.)
;;
;; Persistent at ~/.nido/state/<instance-id>/links.edn — survives down/up,
;; removed by destroy. Mutations re-render the session-home CLAUDE.md so
;; the next session start sees the change. Project + session can be
;; auto-resolved when invoked from a session-home cwd.
;; ---------------------------------------------------------------------------

(defn- session-home-coords-from-cwd
  "If cwd is a session-home (~/.nido/sessions/<project>/<session>/),
   return [project session]. Otherwise nil."
  []
  (let [cwd  (abs-path (System/getProperty "user.dir"))
        root (abs-path (state/sessions-root))]
    (when (and (str/starts-with? cwd (str root "/"))
               (not= cwd root))
      (let [rel   (subs cwd (inc (count root)))
            parts (str/split rel #"/")]
        (when (>= (count parts) 2)
          [(first parts) (second parts)])))))

(defn- resolve-link-coords
  "Resolve [project-name session-name instance-id worktree] for a link
   command. Order:
     1. explicit :project + positional <session>
     2. cwd is a session-home → derive both
   Throws with a useful hint if neither works."
  [opts session-arg]
  (let [explicit-project (some-> (:project opts) name)
        cwd-coords       (session-home-coords-from-cwd)
        project-name     (or explicit-project (first cwd-coords))
        session-name     (or session-arg (second cwd-coords))]
    (when-not (and project-name session-name)
      (throw (ex-info "Could not resolve project + session for link command"
                      {:hint (str "Pass :project <p> <session> explicitly, "
                                  "or cd into the session home "
                                  "(~/.nido/sessions/<project>/<session>/) first.")
                       :project project-name :session session-name})))
    (let [projects (config/read-projects)]
      (when-not (contains? projects project-name)
        (throw (ex-info (str "Unknown project: " project-name)
                        {:registered (vec (keys projects))}))))
    (let [{:keys [directory]} (get (config/read-projects) project-name)
          worktree    (worktree-path project-name directory session-name)
          instance-id (engine/resolve-instance-id worktree)]
      [project-name session-name instance-id worktree])))

(defn link-add!
  "Append/replace a link for the resolved session. opts must include
   :type and :url; :title is optional. project + session may be passed
   explicitly or auto-resolved from cwd."
  [session-arg opts]
  (let [[project session instance-id _] (resolve-link-coords opts session-arg)]
    (links/add! instance-id (select-keys opts [:type :url :title]))
    (try (launcher/rerender-briefing! project session instance-id)
         (catch Exception e
           (core/log-step (str "warning: briefing rerender: " (ex-message e)))))
    (println (str "Added link to " project "/" session
                  " (" (name (:type opts)) "  " (:url opts) ")"))))

(defn link-remove!
  "Drop the link with matching :url from the resolved session."
  [session-arg opts]
  (let [[project session instance-id _] (resolve-link-coords opts session-arg)
        url (:url opts)]
    (when-not url
      (throw (ex-info "Missing :url" {:hint "Pass :url <url-to-remove>"})))
    (links/remove-by-url! instance-id url)
    (try (launcher/rerender-briefing! project session instance-id)
         (catch Exception e
           (core/log-step (str "warning: briefing rerender: " (ex-message e)))))
    (println (str "Removed link from " project "/" session " (" url ")"))))

(defn link-list
  "Print the resolved session's links grouped by type. No-op message
   when empty."
  [session-arg opts]
  (let [[project session instance-id _] (resolve-link-coords opts session-arg)
        entries (links/read-links instance-id)]
    (println (str "links for " project "/" session ":"))
    (if (empty? entries)
      (println "  (none)")
      (doseq [[t ls] (links/group-by-type entries)]
        (println (str "  " (links/display-labels t (name t))))
        (doseq [{:keys [url title]} ls]
          (println (str "    " url
                        (when (seq title) (str " — " title)))))))))
