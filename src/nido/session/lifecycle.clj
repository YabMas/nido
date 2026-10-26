(ns nido.session.lifecycle
  "Bundled per-session workflow: a single command creates a worktree,
   starts an isolated nido session against it, and the inverse tears it
   all down. Sessions are named (the name is the git branch / jj bookmark
   and the leaf path under the project's worktrees directory by default).
   Which runtime a session is on is the engine's: it is keyed by the key a caller passes, else
   the one the session is up under, else the session's own name (see `with-context`).

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
   [nido.platform.config :as config]
   [nido.platform.core :as core]
   [nido.platform.lock :as lock]
   [nido.platform.process :as proc]
   [nido.session.engine :as engine]
   [nido.session.failure :as failure]
   [nido.session.launcher :as launcher]
   [nido.session.links :as links]
   [nido.session.profiles :as profiles]
   [nido.session.resume-shim :as resume-shim]
   [nido.session.services.postgresql :as pg]
   [nido.session.state :as state]
   [nido.session.test-pg :as test-pg]))

;; ---------------------------------------------------------------------------
;; Project & worktree path resolution
;; ---------------------------------------------------------------------------

(defn- abs-path [s]
  (str (fs/normalize (fs/absolutize s))))

(defn- inside? [child parent]
  (str/starts-with? (abs-path child) (abs-path parent)))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :ProjectName]]}
  resolve-project
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :Path] :Path]}
  worktrees-dir
  "Base directory for this project's worktrees. Resolution:
     - session.edn :worktrees-dir absolute path or `~/...` → used as-is
     - session.edn :worktrees-dir relative path             → resolved
                                                              against project-dir
     - unset                                                → default
                                                              <parent>/<project>-worktrees
   The relative form lets a project keep its worktrees inside its own
   checkout (e.g. \".worktrees\" so files like .claude/ apply to them).

   `:worktrees-dir` is optional, so this reads session.edn softly: a registered
   but unconfigured project (no session.edn) resolves to the default rather than
   throwing, which keeps read-only enumeration (the TUI project list / board)
   alive. Booting such a session still fails loudly in `load-session-edn`."
  [project-name project-dir]
  (let [session-edn (engine/read-session-edn project-name)
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :Path :string] :Path]}
  worktree-path
  "Filesystem path for a named session's worktree."
  [project-name project-dir name]
  (str (fs/path (worktrees-dir project-name project-dir) name)))

(defn ^{:malli/schema [:=> [:cat :any] :Path]}
  canonical
  "Absolute, symlink-resolved path string. Falls back to abs-path when the
   path doesn't exist on disk (e.g. in tests / not-yet-created dirs)."
  [p]
  (try (str (fs/canonicalize p))
       (catch Exception _ (abs-path p))))

(defn ^{:malli/schema [:=> [:cat [:? :any]] [:maybe :map]]}
  session-from-cwd
  "Resolve which session a cwd belongs to via the worktree-keyed registry
   (longest-prefix wins), deriving the session name by relativizing the
   matched worktree path against the project's worktrees-dir. Home-independent.
   Returns {:project :session :worktree :instance-id} or nil.

   Nil whenever the registry cannot NAME the session the caller is standing in.
   Callers guard on nil and fall back to what they were given; none can guard
   against a confident wrong answer, which is what a session name escaping its
   worktrees-dir — `..`, `../nido` — used to be. Such a name reaches no
   workstream, no state dir and no session home, so every write keyed on it
   lands nowhere and reports success."
  ([] (session-from-cwd (System/getProperty "user.dir")))
  ([cwd]
   (let [cwd*     (str (canonical cwd) "/")
         projects (config/read-projects)
         ;; One session.edn read per project rather than per registry entry —
         ;; a project's lite sessions all canonicalize to the same root, so the
         ;; same base is asked for repeatedly within one resolution.
         base-of  (memoize
                   (fn [project]
                     (canonical (worktrees-dir project
                                               (:directory (get projects project))))))
         match    (->> (state/read-registry)
                       (filter
                        (fn [[wt entry]]
                          (let [project (:project-name entry)
                                wt*     (canonical wt)]
                            (and
                             ;; Only entries for a registered project are
                             ;; resolvable nido sessions; skip foreign/legacy
                             ;; entries (e.g. a codex worktree with
                             ;; :project-name nil) so cwd inside one resolves to
                             ;; nil rather than crashing in worktrees-dir /
                             ;; load-session-edn.
                             (get projects project)
                             (str/starts-with? cwd* (str wt* "/"))
                             ;; A `:lite` worktree is a symlink to the project
                             ;; checkout, so it canonicalizes to the checkout
                             ;; ROOT — which prefixes every worktree the project
                             ;; owns, and is the identical path for every lite
                             ;; session running at once. Such an entry answers
                             ;; the bare checkout, and every worktree under it,
                             ;; with a session picked arbitrarily from the ones
                             ;; that tied. Containment is what refuses it.
                             (str/starts-with? wt* (str (base-of project) "/"))))))
                       (sort-by (fn [[wt _]] (count (canonical wt))))
                       last)]
     (when match
       (let [[wt entry] match
             project (:project-name entry)]
         {:project     project
          :session     (str (fs/relativize (base-of project) (canonical wt)))
          :worktree    wt
          :instance-id (:instance-id entry)})))))

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

(defn ^{:malli/schema [:=> [:cat :Path :Path] :any]}
  create-symlink-worktree!
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

(defn ^{:malli/schema [:=> [:cat :Path] :any]}
  remove-symlink-worktree!
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
  "Run a jj command in `dir`. Throws on non-zero exit unless :continue?.

   `dir` is the source repo's *default* workspace, which nido never edits. Metadata
   ops (bookmark list/create, workspace forget) pass `--ignore-working-copy` so jj's
   incidental snapshot of that default working copy — possibly stale once concurrent
   session workspaces advance the shared op log — doesn't trip the stale-working-copy
   guard and abort session creation. Pass `:ignore-wc? false` for a command that MUST
   update a working copy (`jj workspace add`), since jj rejects the flag there."
  [dir args & {:keys [continue? ignore-wc?] :or {continue? false ignore-wc? true}}]
  (let [opts (cond-> {:out :string :err :string :dir dir}
               continue? (assoc :continue true))
        argv (cond->> args
               ignore-wc? (cons "--ignore-working-copy"))
        result (apply shell opts "jj" argv)]
    (when (and (not continue?) (not (zero? (:exit result))))
      (throw (ex-info (str "jj " (str/join " " args) " failed")
                      {:exit (:exit result) :err (:err result)})))
    result))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  classify-push
  "What a `jj git push` actually did, read from its output rather than its exit
   code. Pure, and separated from the push for that reason: this is the fact a
   landing is recorded on, and a fact that can only be established by pushing is
   one nobody can test.

   Reading the OUTPUT is not fastidiousness. jj exits 0 for three outcomes that
   a landing must tell apart:

     Changes to push to origin: …          the remote moved          :advanced
     Bookmark x@origin already matches x   the remote already had it :already-there
     Warning: No matching bookmarks…       nothing was pushed at all :no-such-bookmark

   The last two both print `Nothing changed.`, so a caller keying on exit status
   would record a landing for a bookmark it never managed to set.

   :advanced and :already-there are BOTH safe to record against, and collapsing
   them would be the subtler bug: a process that dies between a successful push
   and its first ledger append leaves a retry finding the remote already at that
   revision, so a rule of `record only if it moved` would leave that landing
   unwritten forever.

   A non-zero exit is `:rejected` and carries what jj said. Neither convention
   already in this namespace fits a push — the local commands throw on any
   non-zero exit, which would abort a landing on a transient timeout, and
   `fetch-base!` swallows failure so offline work still functions, which would
   let a rejected push be recorded as a landing. The caller is handed the
   outcome and decides."
  [{:keys [exit out err]}]
  (let [text (str out "\n" err)]
    (cond
      (not (zero? exit))
      {:outcome :rejected :detail (str/trim text)}

      (str/includes? text "No matching bookmarks")
      {:outcome :no-such-bookmark :detail (str/trim text)}

      (re-find #"already matches" text)
      {:outcome :already-there}

      (str/includes? text "Changes to push to origin")
      {:outcome :advanced}

      :else
      {:outcome :rejected :detail (str "unrecognised push result: " (str/trim text))})))

(defn ^{:malli/schema [:=> [:cat :Path :string :string] :map]}
  advance-remote!
  "Point `bookmark` at `rev` in `worktree` and push it to origin. Returns what
   `classify-push` made of the result.

   The first operation in nido that advances a remote ref. Everything nido has
   pushed until now was pushed by an agent following a skill's prose, which is
   why no landing could be made conditional on a check the system enforces: the
   party running the push was the party choosing whether to obey the gate.

   Never throws on a push that did not land. The outcome is the caller's to
   read, because the caller is the only one that knows what an unpushed
   revision means for the record it was about to write."
  [worktree bookmark rev]
  (jj! worktree ["bookmark" "set" bookmark "-r" rev])
  (classify-push (jj! worktree ["git" "push" "-b" bookmark] :continue? true)))

(defn- jj-source-repo?
  "True if `dir` is a jj-colocated source repo. The source's `.jj/repo` is a
   directory (the actual jj data); a workspace's `.jj/repo` is a pointer file."
  [dir]
  (fs/directory? (str (fs/path dir ".jj" "repo"))))

(defn- jj-workspace?
  "True if `wt-path` is a jj workspace (has a `.jj/` dir of any shape)."
  [wt-path]
  (fs/exists? (str (fs/path wt-path ".jj"))))

(defn- jj-worktree-poisoned?
  "True when `wt-path` is a jj workspace whose working copy was never
   materialized — a failed `jj workspace add` leaves the dir + `.jj/` but
   strands `@` on the root commit (all-zeros) with nothing checked out.
   Such a worktree exists on disk yet can't host a session.

   CONSERVATIVE: only true on a DEFINITIVE root-parent signal (jj succeeds
   AND reports `@-` as the all-zeros commit). On any jj error or ambiguity
   it returns false — we never destroy a worktree we can't positively prove
   is empty, so a healthy worktree with uncommitted work is never at risk."
  [wt-path]
  (and (jj-workspace? wt-path)
       (let [r (jj! wt-path ["log" "-r" "@-" "--no-graph" "-T" "commit_id"] :continue? true)]
         (and (zero? (:exit r))
              (boolean (re-matches #"0+" (str/trim (str (:out r)))))))))

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

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  workspace-stale?
  "True when a non-zero jj result is the stale-working-copy failure: another
   operation rewrote the invoking workspace's working-copy commit and the files
   on disk were never updated to match. jj's message is `The working copy is
   stale (not updated since operation …). … Run `jj workspace update-stale` to
   update it.`

   The one jj failure that names its own remedy, and whether running the remedy
   is safe is the caller's to decide. `jj-workspace-add!` runs it, because the
   workspace it snapshots holds nothing jj has not recorded. A caller whose
   files on disk may be an agent's unrecorded edits must not: jj 0.45's update
   records them as a divergent copy of the working-copy commit and checks the
   other copy out, so the edits leave the disk."
  [result]
  (and (not (zero? (:exit result)))
       (str/includes? (str (:err result)) "working copy is stale")))

(defn- jj-workspace-add!
  "Materialize the new session workspace at `wt-path` on bookmark `branch`,
   self-healing the harness's most common session-creation abort.

   `jj workspace add` is the only nido jj call that can't take the global
   --ignore-working-copy shield (jj rejects it — the command's whole job is
   to update a working copy). So it snapshots the source repo's *default*
   workspace first, and when that workspace lags the shared op log — routine
   once a concurrent session advances it — jj aborts with the stale-working-
   copy error. The fix is mechanical and is the very command the error
   prescribes: `jj workspace update-stale` fast-forwards the default
   workspace to the latest op (it leaves untracked files alone). So on a
   stale failure we run it and retry the add exactly ONCE.

   The stale check fires at jj's pre-op snapshot, before the destination is
   created, so the retry starts from a clean slate. Any other failure — or a
   second stale failure after update-stale — throws, so we never loop."
  [project-dir wt-path branch]
  (core/log-step (str "jj workspace add " wt-path
                      " --name " branch " --revision " branch))
  (let [add-args ["workspace" "add" "--name" branch "--revision" branch wt-path]
        r (jj! project-dir add-args :continue? true :ignore-wc? false)]
    (cond
      (zero? (:exit r)) r

      (workspace-stale? r)
      (do
        (core/log-step (str "default workspace is stale — running "
                            "`jj workspace update-stale` and retrying the add"))
        (jj! project-dir ["workspace" "update-stale"] :ignore-wc? false)
        (jj! project-dir add-args :ignore-wc? false))

      :else
      (throw (ex-info "jj workspace add failed"
                      {:exit (:exit r) :err (:err r)})))))

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
  (jj-workspace-add! project-dir wt-path branch))

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

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :string]]}
  session-name-problem
  "Why `s` cannot name a session, or nil when it can. A session name becomes a
   directory under the worktree root and, unless `:branch` is given, the jj
   bookmark and workspace name — and nido hands the bookmark to `jj bookmark
   list` as a revset pattern. So the rule is the intersection: ASCII letters,
   digits, `.`, `_`, `-` and `/` (slash-namespaced names like `feat/x` are
   real), starting with a letter or digit, never `..`, `//`, or a trailing `/`
   or `.`. Narrower than git's ref rules on purpose: `(`, `|` and quotes are
   legal refs yet break the revset, and a space is neither."
  [s]
  (cond
    (not (string? s))                           "it is not a string"
    (not (re-matches #"[A-Za-z0-9][A-Za-z0-9._/-]*" s))
    "it may hold only ASCII letters, digits, `.`, `_`, `-` and `/`, starting with a letter or digit"
    (re-find #"\.\.|//|[/.]$" s)              "it may not contain `..` or `//`, or end in `/` or `.`"))

(defn- refuse-unnameable!
  "Throw when `name`, or the bookmark it starts on, cannot name a session. A
   refusal: thrown before the start, so it is never kept as a start failure
   that a recovery would retry forever."
  [name branch]
  (doseq [[what s] [["session name" name] ["branch" branch]]]
    (when-let [why (session-name-problem s)]
      (throw (ex-info (str "Refusing " what " " (pr-str s) ": " why
                           " — e.g. " (pr-str (-> (str s) str/trim str/lower-case
                                                  (str/replace #"[^a-z0-9._/-]+" "-")
                                                  (str/replace #"^[^a-z0-9]+|[/.-]+$" ""))))
                      {:reason :invalid-session-name :session name :value s})))))

(defn- with-context
  "Where the session `name` lives and which runtime it is on. `:runtime-key` is the key it comes up
   under: the caller's `:runtime-key`, else the one it is up under, else its own whole name.
   `:instance-id` is that runtime's; `:on` is the runtime it is on now, nil when it is down."
  [name opts]
  (let [[project-name {:keys [directory]}] (resolve-project opts)
        wt-path (or (some-> (:path opts) str fs/expand-home str)
                    (worktree-path project-name directory name))
        branch (or (:branch opts) name)
        base   (or (:base opts) "origin/main")
        runtime-key (or (:runtime-key opts) (engine/runtime-key-of wt-path) name)]
    {:project-name project-name
     :project-dir  directory
     :name name
     :wt-path wt-path
     :branch branch
     :base base
     :runtime-key runtime-key
     :instance-id (engine/runtime-instance-id wt-path project-name runtime-key)
     :on (engine/resolve-instance-id wt-path)}))

(defn- effective-profile
  "The resolved session profile: an explicit pre-resolved :profile in opts wins;
   otherwise resolve the :session-profile keyword (default :full) for the project.
   Lets a 'start' reuse a session's persisted profile instead of re-defaulting to
   :full (which would start ALL services on a :lite session)."
  [project-name opts]
  (or (:profile opts)
      (profiles/resolve-profile project-name (or (:session-profile opts) :full))))

(def ^:private kept-opts
  "The options a failed start keeps, so a restore can start it again the same
   way. Plain data only: a resolved :profile is kept too, because a session
   brought up with its persisted profile must come back with it. Never the
   :runtime-key: which workstream holds a session is nido's to say when it is
   started again, and may have changed — or ended — since the start failed."
  [:project :session-profile :profile :run-dir :base :branch :path])

(defn- keeping-failure
  "Run `start`, the part of a lifecycle verb that brings a session up; when it
   throws, keep the failure and rethrow it carrying the record's id under
   :session-failure/id, with its message and ex-data otherwise as they were.

   Only the start is wrapped, never the verb's refusals before it: an unknown
   project, a missing worktree or a reset refused on the shared cluster is the
   caller being told no, not a session that failed to come up.

   The caller's :origin option — on whose behalf the start ran — is kept as it
   stands, a person's start when there is none. An exception that already
   carries an id was kept by an inner start and is rethrown untouched."
  [verb name opts {:keys [project-name instance-id]} worktree-existed? start]
  (try
    (start)
    (catch Exception e
      (if (:session-failure/id (ex-data e))
        (throw e)
        (let [kept (failure/record! {:verb              verb
                                     :session           name
                                     :project           project-name
                                     :instance-id       instance-id
                                     :opts              (select-keys opts kept-opts)
                                     :worktree-existed? worktree-existed?
                                     :origin            (or (:origin opts) {:kind :person})}
                                    e)]
          (throw (if kept
                   (ex-info (or (ex-message e) "")
                            (assoc (or (ex-data e) {}) :session-failure/id (:id kept))
                            e)
                   e)))))))

;; ---------------------------------------------------------------------------
;; One lifecycle verb per session at a time
;; ---------------------------------------------------------------------------

(def ^:private session-wait-ms
  "How long a verb waits for another verb on the same session before refusing.
   Long enough to outlast a cold `up` (clone + JVM boot + app start), so a
   second caller waits rather than being refused."
  (* 15 60 1000))

(defonce ^:private local-locks (atom {}))

(defn- local-lock ^java.util.concurrent.locks.ReentrantLock [instance-id]
  (get (swap! local-locks update instance-id
              #(or % (java.util.concurrent.locks.ReentrantLock.)))
       instance-id))

(defn- lock-name
  "The machine-wide lock name of lifecycle lock `id` — a runtime's instance id, or a session's
   `member-` key — spelled `state/file-safe`, so the lock file it names is distinct for every
   distinct id whatever characters the lock's own file naming would fold."
  [id]
  (str "session-" (state/file-safe id)))

(defn- with-session-lock
  "Run `f` holding the session's lifecycle lock, waiting while another verb
   holds it. Throws when the wait runs out. The throw is a refusal, so it is
   never kept as a start failure.

   Every verb that starts or stops services takes this lock. A start rolls back
   every service it started when it fails: it stops the session's Postgres on
   its PGDATA and deletes the nido-written config file. Two overlapping verbs
   share that PGDATA and that file, so the first one's rollback tears them down
   under the second. The second then boots an app with no DB and no local.edn.
   An `up` that waits instead finds the session running and is a no-op.

   The lock has two layers. A thread-reentrant in-process lock covers the TUI
   and the daemon, which run verbs on several threads of one process. A file
   lock covers separate bb processes. `nido.platform.lock` treats every thread
   of the owning pid as the holder, so it cannot serialize threads on its own.
   It is taken only at the outermost hold, because the file lock's release is
   not counted: a nested verb (restart! -> down!) would drop it early."
  [instance-id verb f]
  (let [l (local-lock instance-id)]
    (when-not (.tryLock l session-wait-ms java.util.concurrent.TimeUnit/MILLISECONDS)
      (throw (ex-info (str "Another " instance-id " lifecycle verb in this process is still running")
                      {:instance-id instance-id :verb verb :waited-ms session-wait-ms})))
    (try
      (if (> (.getHoldCount l) 1)
        (f)
        (lock/with-lock* (lock-name instance-id) (str (name verb) " " instance-id)
          {:wait-ms session-wait-ms
           :on-wait (fn [{:keys [label pid]}]
                      (core/log-step (str "Waiting for `" label "` (pid " pid
                                          ") to finish before " (name verb) "...")))}
          f))
      (finally (.unlock l)))))

(defn- with-lifecycle-locks
  "Run `(f ctx)` holding every lifecycle lock a verb on the session `name` needs, taken in one
   order by every verb: the session's own lock first, then — `ctx` read under it, so the runtime
   the session is on is not chosen by a verb that has not waited — the locks of the runtime it is
   on and the one it comes up on, sorted by id. One session's verbs therefore run one at a time
   whatever runtime keys their callers pass, and two verbs moving sessions between the same two
   runtimes in opposite directions take those locks in the same order."
  [name opts verb f]
  (let [[project-name _] (resolve-project opts)]
    (with-session-lock
     (str "member-" (state/session-key project-name name)) verb
     (fn []
       (let [{:keys [instance-id on wt-path] :as ctx} (with-context name opts)]
         ((reduce (fn [inner rt] #(with-session-lock rt verb inner))
                  (fn []
                    ;; Before the verb's first stop: a start refused after it would leave the
                    ;; session down and detached.
                    (when-not (#{:down :destroy} verb)
                      (engine/refuse-start-in-backing-worktree! name wt-path instance-id))
                    (f ctx))
                  (reverse (sort (distinct (remove nil? [on instance-id])))))))))))

(defn ^{:malli/schema [:=> [:cat :string :map] :map]}
  session-coords
  "Resolve {:wt-path :instance-id} for a named session: its worktree, and the runtime it is on —
   or, while it is down, the one it would come up on."
  [name opts]
  (let [{:keys [wt-path instance-id on]} (with-context name opts)]
    {:wt-path wt-path :instance-id (or on instance-id)}))

(defn ^{:malli/schema [:=> [:cat :string :map] [:maybe [:map [:instance-id :string] [:key [:maybe :string]]
                                                           [:owes-checkout? :boolean]]]]}
  runtime-of
  "The runtime the named session is up on, as {:instance-id :key :owes-checkout?}, or nil when it
   is down. `:key` is nil for a runtime started before runtimes recorded the key they were started
   under; `:owes-checkout?` holds for one that runs out of a member's worktree though it should run
   out of a checkout of its own (`engine/owes-checkout?`)."
  [name opts]
  (let [{:keys [wt-path on]} (with-context name opts)]
    (when on
      {:instance-id    on
       :key            (engine/runtime-key-of wt-path)
       :owes-checkout? (engine/owes-checkout? (state/read-session on))})))

(defn- weight-from-worktree
  "Fallback reading of provisioned weight for a session whose profile snapshot
   did not survive — its state dir was reclaimed, or it predates profile.edn.
   The worktree SHAPE is the profile's `:worktree :strategy` made durable: the
   :lite strategy symlinks the project's own checkout, the full strategy builds a
   real worktree. nil when there is no worktree left to read."
  [wt-path]
  (cond
    (fs/sym-link? wt-path)  :light
    (fs/directory? wt-path) :heavy
    :else                   nil))

(defn ^{:malli/schema [:=> [:cat :string :map] :keyword]}
  session-weight
  "The `:weight` a named session's record should carry, read back from what
   `up!` actually provisioned rather than from what a caller assumed: the
   profile snapshot in its state dir, else the worktree shape. nil when neither
   survives — the provisioning is unknown, and the caller must not guess.

   This is the seam that keeps a session record honest: a full session (services
   + real worktree) is a workstream's runnable environment (`work/environment`
   selects on :heavy), a :lite one is not.

   Never throws. An unresolvable project or unreadable state dir is just another
   way of not knowing, and the daemon's every-5-minutes orphan sweep calls this
   per session — a throw there would take down the adoption invariant to get a
   display detail wrong."
  [name opts]
  (try
    (let [{:keys [wt-path project-name]} (with-context name opts)]
      (or (profiles/profile-weight (engine/read-profile-for-session project-name name))
          (weight-from-worktree wt-path)))
    (catch Exception _ nil)))


;; ---------------------------------------------------------------------------
;; Booting in the background
;;
;; A session a person starts is usable — its worktree, its home, `nido work` —
;; long before a cold runtime has finished booting its app, so `up!` can return
;; at that point and leave the services to a detached bb running the ordinary
;; blocking `up!`. Until that boot finishes, the session's registry entry is a
;; PLACEHOLDER: it names the runtime the session comes up on and the boot's pid,
;; and no port, so `session-from-cwd` resolves the session while no liveness
;; reading takes it for up. The boot's own registration replaces it whole.
;; ---------------------------------------------------------------------------

(defn- registry-entry [wt-path]
  (get (state/read-registry) (str wt-path)))

(defn- live-boot-pid
  "The pid of the background boot this worktree's placeholder names, while that
   process is still running; nil once it has ended or when there is none."
  [wt-path]
  (let [entry (registry-entry wt-path)]
    (when (and (= :booting (state/entry-phase entry)) (proc/process-alive? (:boot-pid entry)))
      (:boot-pid entry))))

(defn- clear-placeholder!
  "Drop the worktree's registry entry when it is a boot placeholder. A full
   entry is never touched: it is the record of services that are running."
  [wt-path]
  (when (= :booting (state/entry-phase (registry-entry wt-path)))
    (state/remove-from-registry! (str wt-path))))

(def ^:private boot-opts
  "The options a background boot is started with: those that shape what the
   start provisions and which runtime it comes up on. Keyed the way the
   session:up task reads them back."
  [:runtime-key :session-profile :base :branch :path :jvm-heap-max :jvm-aliases :jvm-extra-opts])

(defn- boot-log
  "Where the background boot of session `name` writes its output — the session
   start's log when no terminal is watching it. Under the runtime it boots, and
   named for the session: sessions sharing a runtime each have a boot of their own."
  [instance-id project-name name]
  (state/log-file instance-id (str "boot-" (state/session-key project-name name))))

(defn- spawn-boot!
  "Run the blocking `up!` for `name` in a bb detached from this process, and
   return that process's pid. nohup and a log file rather than a pipe, so it
   outlives a CLI that returns and a TUI that is quit."
  [name {:keys [project-name instance-id]} opts]
  (let [log  (boot-log instance-id project-name name)
        args (concat ["--config" (str (fs/path (core/nido-source-dir) "bb.edn"))
                      "nido:session:up" ":project" project-name name
                      ":wait" "true" ":yes" "true"]
                     (mapcat (fn [k] (when-some [v (get opts k)] [(str k) (pr-str v)]))
                             boot-opts))
        cmd  (str "nohup bb " (str/join " " (map proc/quoted args))
                  " > " (proc/quoted log) " 2>&1 < /dev/null & printf %s $!")]
    (fs/create-dirs (state/log-dir instance-id))
    (let [{:keys [exit out err]} (shell {:continue true :out :string :err :string
                                         :dir (str (core/nido-source-dir)) :shutdown nil}
                                        "bash" "-c" cmd)
          pid (parse-long (str/trim (str out)))]
      (when-not (and (zero? exit) (pos-int? pid))
        (throw (ex-info (str "Failed to start the background boot of " name)
                        {:session name :error err :output out})))
      pid)))

(defn- runtime-up?
  "Whether runtime `instance-id` is recorded and up, read by `state/session-live?` over the ports
   it recorded — a lite runtime, which records none, is up while its record stands."
  [instance-id]
  (when-let [runtime (state/read-session instance-id)]
    (state/session-live? {:instance-id instance-id
                          :app-port    (get-in runtime [:context :app :port])
                          :nrepl-port  (get-in runtime [:context :repl :port])})))

(defn- open!
  "The `:wait false` half of `up!`: make the session addressable and hand a cold
   runtime to a background boot. Runs under the lifecycle locks, so the boot —
   which takes the same locks — starts only after the placeholder is written.
   Returns the BootHandoff of the boot that carries the session's services, or
   what an in-place start returns when no boot does."
  [name {:keys [project-name wt-path instance-id runtime-key] :as ctx} profile opts]
  (let [start! #(engine/start-session! wt-path (assoc opts :session-name name :profile profile
                                                      :runtime-key runtime-key))]
    (cond
      (live-boot-pid wt-path)
      (let [log (boot-log instance-id project-name name)]
        (core/log-step (str "Session " name " is already starting in the background (log: " log ")."))
        {:session name :boot-pid (live-boot-pid wt-path) :log log})

      ;; Nothing to wait for: a lite session, or a runtime already up, which the session joins.
      (or (not (profiles/services-provisioned? profile))
          (runtime-up? instance-id))
      (start!)

      :else
      (do
        (launcher/write-artifacts! {:session {:name         name
                                              :project-dir  (str wt-path)
                                              :project-name project-name
                                              :instance-id  instance-id}}
                                   (engine/load-session-edn project-name)
                                   (:run-dir opts))
        (let [pid (spawn-boot! name ctx (assoc opts :runtime-key runtime-key))
              log (boot-log instance-id project-name name)]
          (state/upsert-registry! (str wt-path) {:project-dir  (str wt-path)
                                                 :project-name project-name
                                                 :instance-id  instance-id
                                                 :runtime-key  runtime-key
                                                 :session-name name
                                                 :boot-pid     pid
                                                 :created-at   (core/now-iso)})
          (core/log-step (str "Starting services in the background (pid " pid ", log: " log ")"))
          {:session name :boot-pid pid :log log})))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  up!
  "Bring the named session up: create its worktree if missing, then start
   PG + JVM + app. Idempotent — running on an existing live session is a
   no-op (engine/start-session! detects and short-circuits).

   By default returns once the services are up, or throws. `:wait false`
   returns as soon as the worktree, the session home and a registry placeholder
   exist, and boots a cold runtime in a detached `nido:session:up :wait true`
   whose output is `boot-log`, returning that boot's BootHandoff
   {:session :boot-pid :log}; a start that fails there is kept like any other
   (nido.session.failure), not thrown here. A runtime already up is joined in
   place either way.

   Worktree creation routes by source-repo VCS: jj-colocated → `jj
   workspace add`; plain git → `git worktree add`."
  [name opts]
  (let [{:keys [project-name project-dir branch base]} (with-context name opts)
        _       (refuse-unnameable! name branch)
        profile (effective-profile project-name opts)]
    (with-lifecycle-locks
     name opts :up
     (fn [{:keys [wt-path runtime-key] :as ctx}]
       ;; Read under the lock: a verb we waited for may have just created it.
       (let [existed?  (fs/exists? wt-path)
             poisoned? (and existed? (jj-worktree-poisoned? wt-path))]
         ;; A refusal, so before the start that keeps a failure: recreating removes the worktree.
         (when poisoned? (engine/refuse-backing-worktree! :recreate name wt-path nil))
         (keeping-failure
          :up name opts ctx existed?
          (fn []
            (cond
              poisoned?
              (do
                (core/log-step (str "Worktree at " wt-path " is poisoned (empty working copy, @ on the root commit) — recreating."))
                (remove-jj-workspace! project-dir wt-path branch branch false) ; forget + rm dir; KEEP the bookmark (it's correctly placed)
                (create-jj-workspace! project-dir wt-path branch base))

              existed?
              (core/log-step (str "Worktree already exists at " wt-path " — starting session."))

              (= :symlink (-> profile :worktree :strategy))
              (create-symlink-worktree! wt-path (-> profile :worktree :target))

              (jj-source-repo? project-dir)
              (create-jj-workspace! project-dir wt-path branch base)

              :else
              (create-git-worktree! project-dir wt-path branch base))
            ;; The one write over a recorded profile: this start made the working copy it describes.
            ;; Before the session home, which reads it back to choose the briefing.
            (when-not existed?
              (engine/write-profile-for-session! project-name name profile))
            (if (false? (:wait opts))
              (open! name ctx profile opts)
              (try
                (engine/start-session! wt-path (assoc opts :session-name name :profile profile
                                                      :runtime-key runtime-key))
                (catch Exception e
                  ;; A boot that failed leaves the session down, so the entry
                  ;; that said it was coming up goes with it.
                  (clear-placeholder! wt-path)
                  (throw e)))))))))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  down!
  "Stop the named session. Worktree and on-disk state are preserved."
  [name opts]
  (let [{:keys [wt-path]} (with-context name opts)]
    (when-not (fs/exists? wt-path)
      (throw (ex-info "Worktree does not exist" {:path wt-path :name name})))
    (with-lifecycle-locks name opts :down (fn [_] (engine/stop-session! wt-path)))))

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe :Path]]}
  sync!
  "Bring the checkout of the runtime the session in `worktree` is on up to date with every
   member's working copy (`engine/sync-runtime!`), holding that runtime's lifecycle lock, so its
   membership is read and its checkout refreshed with no join or departure between them."
  [worktree]
  (let [instance-id (engine/resolve-instance-id worktree)]
    (if instance-id
      (with-session-lock instance-id :sync #(engine/sync-runtime! worktree instance-id))
      (engine/sync-runtime! worktree nil))))

(defn- carried-across-stop
  "`opts` carrying what the session `name` was started with and a restart would lose: the
   Run directory its home is linked to, which the stop removes with the home, and the profile it
   came up with, which a start with none replaces by every service. Read before the stop, so the
   start that follows restores both."
  [opts project-name name]
  (assoc opts
         :run-dir (or (:run-dir opts)
                      (resume-shim/run-dir (state/session-home-dir project-name name)))
         :profile (or (:profile opts) (engine/read-profile-for-session project-name name))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  restart!
  "Internal: stop then start the named session (worktree must exist).
   Used by the dashboard's restart button. Not exposed as a bb task —
   `bb nido:session:down` followed by `:up` covers the CLI case."
  [name opts]
  (let [{:keys [wt-path project-name]} (with-context name opts)]
    (with-lifecycle-locks
     name opts :restart
     (fn [{:keys [runtime-key] :as ctx}]
       (let [opts (carried-across-stop opts project-name name)]
         (down! name opts)
         (keeping-failure :restart name opts ctx true
                          #(engine/start-session! wt-path (assoc opts :session-name name
                                                                :runtime-key runtime-key))))))))

(defn- effective-pg-mode
  "Resolved PG mode for a session: the per-session override if set, else the
   project's :postgresql service-def mode."
  [project-name instance-id]
  (or (:mode (state/read-pg-mode-override instance-id))
      (let [svc (->> (:services (engine/load-session-edn project-name))
                     (filter #(= :postgresql (:type %)))
                     first)]
        (pg/resolve-pg-mode (or svc {})))))

(defn ^{:malli/schema [:=> [:cat :keyword :string :map :any] :any]}
  with-runtime-to-itself
  "Run `(f ctx)`, a verb that alters or measures a runtime as a whole — resets or re-provisions its
   database, reboots it to time the boot — holding the lifecycle lock of every runtime the session
   `name` touches — the one it comes up on and the one it is on now — and refusing, under those
   locks, while any other session is on either: the verb would do it under all of them. Read under
   the lock, so a session joining meanwhile is waited for, not missed. The verbs `f` runs on the
   same session keep the hold."
  [verb name opts f]
  (with-lifecycle-locks
   name opts verb
   (fn [{:keys [instance-id on] :as ctx}]
     (doseq [rt (distinct (remove nil? [instance-id on]))
             :let [others (remove #{name} (engine/sessions-on rt))]
             :when (seq others)]
       (throw (ex-info (str "Refusing " (clojure.core/name verb) " of " name " — its runtime " rt
                            " is shared with " (str/join ", " others)
                            "; bring those down first.")
                       {:reason :runtime-shared :verb verb :session name :with (vec others)})))
     (f ctx))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  reset!
  "Nuclear recovery for a session in a bad state: stop the session,
   drop its PGDATA, then start it again so the :postgresql service
   re-clones a fresh PGDATA from the current template. Distinct from
   `down!` + `up!` (which preserves PGDATA) — call this when the local
   DB is wedged or after `bb nido:template:pg:refresh` to pick up the
   new template content.

   Refuses for sessions on the shared cluster — a per-session reset there
   would reset the DB for every session. Use `bb nido:shared:pg:reset`."
  [name opts]
  (let [{:keys [wt-path project-name instance-id]} (with-context name opts)
        opts        (carried-across-stop opts project-name name)]
    (when (= :shared (effective-pg-mode project-name instance-id))
      (throw (ex-info (str "This session uses the shared cluster — a per-session "
                           "reset would reset the shared DB for every session. Use "
                           "`bb nido:shared:pg:reset :project " project-name "` instead, "
                           "or `bb nido:session:isolate` this session first.")
                      {:project-name project-name :session name})))
    (when-not (fs/exists? wt-path)
      (throw (ex-info "Worktree does not exist" {:path wt-path :name name})))
    (with-runtime-to-itself
     :reset name opts
     (fn [{:keys [instance-id runtime-key] :as ctx}]
      (keeping-failure
       :reset name opts ctx true
       (fn []
         (try (engine/stop-session! wt-path)
              (catch Exception e
                (core/log-step (str "warning: stop during reset: " (ex-message e)))))
         (let [pg-data (state/pg-data-dir instance-id)]
           (when (fs/exists? pg-data)
             (core/log-step (str "Dropping PGDATA at " pg-data))
             (fs/delete-tree pg-data)))
         (engine/start-session! wt-path (assoc opts :session-name name :runtime-key runtime-key))))))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  isolate!
  "Switch a session to a PRIVATE Postgres clone (for destructive tests): set a
   per-session :isolated override, then stop+start so it re-provisions its own
   PGDATA from the template. Other sessions are unaffected. Reverse with `share!`."
  [name opts]
  (let [{:keys [wt-path project-name]} (with-context name opts)
        opts        (carried-across-stop opts project-name name)]
    (when-not (fs/exists? wt-path)
      (throw (ex-info "Worktree does not exist" {:path wt-path :name name})))
    (with-runtime-to-itself
     :isolate name opts
     (fn [{:keys [instance-id runtime-key] :as ctx}]
       (state/write-pg-mode-override! instance-id :isolated)
       (keeping-failure
        :isolate name opts ctx true
        (fn []
          (try (engine/stop-session! wt-path)
               (catch Exception e
                 (core/log-step (str "warning: stop during isolate: " (ex-message e)))))
          (engine/start-session! wt-path (assoc opts :session-name name :runtime-key runtime-key))))))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  share!
  "Switch a session back to the shared cluster: clear its :isolated override,
   drop its private PGDATA, then stop+start so it re-provisions against the
   shared cluster. Safe to call on an already-shared session."
  [name opts]
  (let [{:keys [wt-path project-name]} (with-context name opts)
        opts        (carried-across-stop opts project-name name)]
    (when-not (fs/exists? wt-path)
      (throw (ex-info "Worktree does not exist" {:path wt-path :name name})))
    (with-runtime-to-itself
     :share name opts
     (fn [{:keys [instance-id runtime-key] :as ctx}]
       (state/clear-pg-mode-override! instance-id)
       (keeping-failure
        :share name opts ctx true
        (fn []
          (try (engine/stop-session! wt-path)
               (catch Exception e
                 (core/log-step (str "warning: stop during share: " (ex-message e)))))
          (let [pg-data (state/pg-data-dir instance-id)]
            (when (fs/exists? pg-data)
              (core/log-step (str "Dropping private PGDATA at " pg-data))
              (fs/delete-tree pg-data)))
          (engine/start-session! wt-path (assoc opts :session-name name :runtime-key runtime-key))))))))

(defn- drop-test-databases!
  "Drop the test database keyed by `db-key` — a runtime's instance id, or a session's own key for
   the one it was handed on a runtime it did not run out of — in each test cluster the project
   declares. Stop keeps a test database and the service protocol has no destroy, so this is the
   one place a test database ends."
  [project-name db-key]
  (try
    (doseq [svc (:services (engine/read-session-edn project-name))
            :when (= :test-pg (:type svc))]
      (test-pg/drop-session-db! project-name
                                (assoc (select-keys svc [:db-user :max-connections :settings :locale :db-stem])
                                       :instance-id db-key)))
    (catch Exception e
      (core/log-step (str "warning: dropping test database: " (ex-message e))))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  destroy!
  "Bring the named session down, drop its instance state-dir (PGDATA,
   logs, session.edn) and its links, and remove its worktree. The state-dir
   stays when the stop left a pid file: that file is the only record of a
   process the stop could not show was gone.
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
  (let [{:keys [project-dir project-name wt-path branch]} (with-context name opts)
        delete-branch? (boolean (or (:delete-branch? opts) (:delete-branch opts)))]
    (with-lifecycle-locks
     name opts :destroy
     (fn [{:keys [on] :as ctx}]
      (let [instance-id (or on (:instance-id ctx))
            profile     (engine/read-profile-for-session project-name name)]
         (try
           (when (fs/exists? wt-path)
             (engine/stop-session! wt-path))
           (catch Exception e
             (core/log-step (str "warning: stop-session error: " (ex-message e)))))
         ;; After the stop, which takes the session off a runtime others are still on — its
         ;; membership, registry entry and home go with it — and before anything is removed: a
         ;; refusal here leaves the session detached and its worktree where the runtime needs it.
         (engine/refuse-backing-worktree! :destroy name wt-path nil)
         ;; The test database it was handed on a runtime it did not run out of is its own.
         (let [own (state/session-key project-name name)]
           (when (not= own instance-id) (drop-test-databases! project-name own)))
         ;; A runtime other sessions are still on keeps its state and its test database.
         (when (empty? (engine/sessions-on instance-id))
           (drop-test-databases! project-name instance-id)
           (let [state-dir (state/instance-state-dir instance-id)
                 survivors (state/pid-files instance-id)]
             (cond
               ;; A pid file the stop left names a process it could not show was
               ;; gone; the state dir is its only record, and reclaim keeps it
               ;; and reports it while that process runs.
               (seq survivors)
               (core/log-step (str "warning: keeping " state-dir " — a process it records is still"
                                   " running (" (str/join ", " survivors) ")"))

               (fs/exists? state-dir)
               (do (core/log-step (str "Dropping instance state-dir at " state-dir))
                   (fs/delete-tree state-dir)))))
         ;; Links, the profile and the MCP config live outside the state-dir (they have to survive
         ;; reclaim, which eats it an hour after `down`), so destroy is the one verb that has to
         ;; say so out loud. Without this they would outlive the session that owned them.
         (links/delete-links! (state/session-key project-name name))
         (fs/delete-if-exists (state/session-profile-path project-name name))
         (fs/delete-if-exists (launcher/session-mcp-path project-name name))
         (cond
           (= :symlink (-> profile :worktree :strategy))
           (remove-symlink-worktree! wt-path)

           (and (fs/exists? wt-path) (jj-workspace? wt-path))
           (remove-jj-workspace! project-dir wt-path branch branch delete-branch?)

           :else
           (remove-git-worktree! project-dir wt-path branch delete-branch?)))))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  status
  "Print status for a named session."
  [name opts]
  (let [{:keys [wt-path instance-id project-name]} (with-context name opts)]
    (println "session:" name)
    (println "worktree:" wt-path)
    (println "exists?:" (fs/exists? wt-path))
    (if-let [pid (live-boot-pid wt-path)]
      (println (str "services: starting in the background (pid " pid ", log: "
                    (boot-log instance-id project-name name) ")"))
      (when (fs/exists? wt-path)
        (engine/session-status wt-path)))))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  cd-target-file
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

(defn ^{:malli/schema [:=> [:cat :string :map] [:maybe :Path]]}
  resolve-cd-target
  "Resolve (as a path string) the cwd a session `:enter` should land in, or
   throw with a hint. Shared by `enter!` (writes it to `cd-target-file` for the
   parent-shell handoff) and `spawn-tab!` (opens it as a Warp tab).

   `:cd` selects the target:
     :home (default) — the session-home (CLAUDE.md, .mcp.json live here)
     :worktree       — the worktree symlink inside session-home, with a
                       fallback to the on-disk worktree path when the
                       session-home is gone (downed sessions retain
                       their worktree).

   Throws if `:cd home` and the session-home is missing, or if `:cd worktree`
   and neither the session-home symlink nor the on-disk worktree exists."
  [name opts]
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
        session-home)

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
        resolved))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  enter!
  "Hand off a cwd to the parent shell via `cd-target-file`. bb cannot change
   its parent's cwd, so a tiny zsh function (see Nido's CLAUDE.md) reads
   this file after the bb task exits and `cd`s the user there. Used by the
   CLI verb and, in non-Warp terminals, by the TUI (see `spawn-tab!` for the
   Warp path).

   `:cd` selects the target (see `resolve-cd-target`).

   `:auto-up?` (default false) — call `up!` first. Idempotent on a running
   session. Used by the TUI runs screen so `↵` on a downed run
   transparently resumes the session."
  [name opts]
  (when (:auto-up? opts) (up! name opts))
  (let [resolved (resolve-cd-target name opts)
        target   (cd-target-file)]
    (fs/create-dirs (fs/parent target))
    (spit target resolved)
    (core/log-step (str "Selected " resolved))))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  warp?
  "True when running inside Warp. Warp is the only terminal whose URI scheme
   can open a tab in the *current* window, so it's the only one that gets the
   in-app spawn; every other terminal falls back to the `cd-target-file`
   handoff (`enter!`)."
  []
  (= "WarpTerminal" (System/getenv "TERM_PROGRAM")))

(defn- warp-new-tab-uri
  "`warp://action/new_tab` URI that lands a fresh tab at `path`. The path is
   URL-encoded (slashes kept literal — Warp accepts them and they're the common
   case) so spaces or specials in a session path don't break the URI."
  [path]
  (str "warp://action/new_tab?path="
       (-> (java.net.URLEncoder/encode (str path) "UTF-8")
           (str/replace "+" "%20")
           (str/replace "%2F" "/"))))

(defn- sh-quote [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn- toml-str [s]
  (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn- warp-tab-config-uri!
  "Write a Warp tab config that opens at `path` and runs `command` as though typed
   at its prompt, and return the `warp://tab_config/` URI that opens it. The
   config is for this one opening: its command deletes it first, so it never
   lingers in Warp's tab-config menu."
  [path command]
  (let [id  (str "nido-" (subs (str (random-uuid)) 0 8))
        cfg (fs/path (System/getProperty "user.home") ".warp" "tab_configs" (str id ".toml"))]
    (fs/create-dirs (fs/parent cfg))
    (spit (str cfg)
          (str "name = " (toml-str id) "\n\n"
               "[[panes]]\n"
               "id = \"main\"\n"
               "type = \"terminal\"\n"
               "directory = " (toml-str path) "\n"
               "commands = [" (toml-str (str "rm -f " (sh-quote cfg) "; " command)) "]\n"))
    (str "warp://tab_config/" id)))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  spawn-tab!
  "Open a new Warp tab in the *current* window at the session's `:enter` cwd and
   return that path. The title is set by the zsh precmd hook (see CLAUDE.md)
   from $PWD. The TUI calls this in Warp so nido stays put; other terminals use
   `enter!` + the parent-shell handoff instead.

   `:command`, when given, runs in the tab as though typed at its prompt. It
   has to: Warp hands the keyboard only to a program started that way, so one
   a shell hook starts runs deaf to its own tab. Warp's new-tab URI cannot
   carry a command, so a command goes through a tab config instead.

   `:dir`, when given, is where the tab opens instead — a resumed conversation reopens in the cwd
   it ran in, which may lie below the home or the worktree. Otherwise `:cd` / `:auto-up?` behave
   as in `enter!`."
  [name {:keys [command dir] :as opts}]
  (when (:auto-up? opts) (up! name opts))
  (let [path (or dir (resolve-cd-target name opts))]
    (shell {:out :string :err :string} "open"
           (if command (warp-tab-config-uri! path command) (warp-new-tab-uri path)))
    path))

(def ^:private kickoff-file
  "Name of the staged kickoff in a session-home. It is not `.nido-kickoff`, the
   name the retired zsh precmd hook consumes, so a hook left in a `~/.zshrc`
   cannot start the agent deaf before the tab runs it."
  ".nido-kickoff.zsh")

(defn ^{:malli/schema [:=> [:cat :string :map] :string]}
  stage-kickoff!
  "Leave a kickoff in the session-home that starts its agent through
   `nido work` in the worktree, named `:agent-name`, given `:prompt` as its
   first turn, and run on `:model` when given — in the permission mode the
   worktree's launch baseline sets, which a kickoff never overrides.
   Returns the command that runs it — what spawn's tab runs, and what a
   person runs outside Warp. The prompt is kept beside it rather than
   inlined, because a brief of any length survives a file where it would not
   survive quoting.

   Staging again replaces the earlier kickoff; nothing queues."
  [name {:keys [agent-name prompt model] :as opts}]
  (let [home     (resolve-cd-target name (assoc opts :cd :home))
        worktree (resolve-cd-target name (assoc opts :cd :worktree))
        prompt-f (str (fs/path home ".nido-kickoff-prompt.md"))
        script   (str "cd " (sh-quote worktree) " || exit 1\n"
                      "bb --config " (sh-quote (str (fs/path (core/nido-source-dir) "bb.edn")))
                      " nido:work :name " (sh-quote agent-name)
                      " :prompt-file " (sh-quote prompt-f)
                      (when model (str " :model " (sh-quote model)))
                      "\n")]
    (spit prompt-f prompt)
    ;; Written aside and renamed in, so nothing ever runs a half-written file.
    (let [tmp (str (fs/path home (str kickoff-file ".tmp")))]
      (spit tmp script)
      (fs/move tmp (fs/path home kickoff-file) {:replace-existing true :atomic-move true}))
    (str "cd " (sh-quote home) " && zsh " kickoff-file)))

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

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  list-all-data
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

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  list-all
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

(defn ^{:malli/schema [:=> [:cat [:? :any]] [:maybe :map]]}
  session-home-coords-from-cwd
  "If cwd is a session-home (~/.nido/sessions/<project>/<session>/),
   return [project session]. Otherwise nil. The session name may itself
   contain slashes (feat/x), so it's everything after the first segment
   (the project) — not merely the second path segment."
  ([] (session-home-coords-from-cwd (System/getProperty "user.dir")))
  ([cwd]
   (let [cwd  (abs-path cwd)
         root (abs-path (state/sessions-root))]
     (when (and (str/starts-with? cwd (str root "/"))
                (not= cwd root))
       (let [rel   (subs cwd (inc (count root)))
             slash (str/index-of rel "/")]
         (when slash
           [(subs rel 0 slash) (subs rel (inc slash))]))))))

(defn ^{:malli/schema [:=> [:cat [:? :any]] [:maybe :Path]]}
  worktree-from-cwd
  "Resolve the worktree path for a cwd anywhere in a session's footprint —
   inside the worktree (registry → session-from-cwd) OR at the session-home
   (~/.nido/sessions/<p>/<s>/, which is not itself a jj workspace). This is the
   home-aware union that lets cwd-based verbs (e.g. review:loop) reach the code
   regardless of where in the session the agent stands. Returns the worktree
   path string, or nil when cwd belongs to no known session."
  ([] (worktree-from-cwd (System/getProperty "user.dir")))
  ([cwd]
   (or (:worktree (session-from-cwd cwd))
       (when-let [[project session] (session-home-coords-from-cwd cwd)]
         (when-let [{:keys [directory]} (get (config/read-projects) project)]
           (worktree-path project directory session))))))

(defn- resolve-link-coords
  "Resolve [project-name session-name links-key worktree] for a link
   command. Order of project/session resolution:
     1. explicit :project + positional <session>
     2. cwd inside a worktree (registry) → session-from-cwd
     3. cwd is a session-home → legacy derivation
   Throws with a useful hint if none work."
  [opts session-arg]
  (let [explicit-project (some-> (:project opts) name)
        from-cwd         (session-from-cwd)
        home-coords      (session-home-coords-from-cwd)
        project-name     (or explicit-project (:project from-cwd) (first home-coords))
        session-name     (or session-arg (:session from-cwd) (second home-coords))]
    (when-not (and project-name session-name)
      (throw (ex-info "Could not resolve project + session for link command"
                      {:hint (str "Pass :project <p> <session> explicitly, "
                                  "or cd into the worktree (or session home) first.")
                       :project project-name :session session-name})))
    (let [projects (config/read-projects)]
      (when-not (contains? projects project-name)
        (throw (ex-info (str "Unknown project: " project-name)
                        {:registered (vec (keys projects))}))))
    (let [{:keys [directory]} (get (config/read-projects) project-name)
          worktree    (worktree-path project-name directory session-name)]
      [project-name session-name (state/session-key project-name session-name) worktree])))

(defn ^{:malli/schema [:=> [:cat :any :map] :any]}
  link-add!
  "Append/replace a link for the resolved session. opts must include
   :type and :url; :title is optional. project + session may be passed
   explicitly or auto-resolved from cwd."
  [session-arg opts]
  (let [[project session links-key worktree] (resolve-link-coords opts session-arg)]
    (links/add! links-key (select-keys opts [:type :url :title]))
    (try (launcher/rerender-briefing! project session (engine/resolve-instance-id worktree))
         (catch Exception e
           (core/log-step (str "warning: briefing rerender: " (ex-message e)))))
    (println (str "Added link to " project "/" session
                  " (" (name (:type opts)) "  " (:url opts) ")"))))

(defn ^{:malli/schema [:=> [:cat :any :map] :any]}
  link-remove!
  "Drop the link with matching :url from the resolved session."
  [session-arg opts]
  (let [[project session links-key worktree] (resolve-link-coords opts session-arg)
        url (:url opts)]
    (when-not url
      (throw (ex-info "Missing :url" {:hint "Pass :url <url-to-remove>"})))
    (links/remove-by-url! links-key url)
    (try (launcher/rerender-briefing! project session (engine/resolve-instance-id worktree))
         (catch Exception e
           (core/log-step (str "warning: briefing rerender: " (ex-message e)))))
    (println (str "Removed link from " project "/" session " (" url ")"))))

(defn ^{:malli/schema [:=> [:cat :any :map] :any]}
  link-list
  "Print the resolved session's links grouped by type. No-op message
   when empty."
  [session-arg opts]
  (let [[project session links-key _] (resolve-link-coords opts session-arg)
        entries (links/read-links links-key)]
    (println (str "links for " project "/" session ":"))
    (if (empty? entries)
      (println "  (none)")
      (doseq [[t ls] (links/group-by-type entries)]
        (println (str "  " (links/display-labels t (name t))))
        (doseq [{:keys [url title]} ls]
          (println (str "    " url
                        (when (seq title) (str " — " title)))))))))
