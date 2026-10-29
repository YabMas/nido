(ns nido.session.run
  "Run a project-declared :project-commands entry inside a session's worktree.
   The generic 'adopt a target-project command' primitive: nido resolves where
   to run (the session worktree) and forwards the command via nido.session.commands;
   the command's behaviour and output belong to the target project.

   A command declaring `:checkout :committed` runs instead in a throwaway git
   checkout of the session's committed tip, reachable as {{session.checkout}}.
   A jj-workspace worktree nests inside the colocated source repo, so git run in
   it binds to the PARENT repo; a command that needs `git` to see the session's
   own files as a checkout of their own (brian's CI lists its image context with
   `git ls-files`) has no other way to get one."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.session.commands :as commands]
   [nido.platform.config :as config]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]))

(defn ^{:malli/schema [:=> [:cat :ProjectName :Path :string :Path] :map]}
  session-context
  "Substitution context for a session-scoped project-command. Adds the
   :session layer (so commands can template {{session.worktree}}) on top of
   the :project layer the existing template steps already use."
  [project-name project-dir session-name worktree]
  {:project {:name project-name :dir project-dir}
   :session {:name session-name :worktree worktree}})

(defn- resolve-project-dir
  [project-name]
  (or (get-in (config/read-projects) [project-name :directory])
      (throw (ex-info (str "Project not registered: " project-name)
                      {:hint "Run `bb nido:project:add <name> <directory>` first."
                       :project-name project-name}))))

(defn- sh!
  "Run argv in `dir`, returning its trimmed stdout. Throws on a non-zero exit,
   naming the command and its stderr."
  [dir & argv]
  (let [r (apply shell {:dir (str dir) :out :string :err :string :continue true} argv)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str (str/join " " argv) " failed in " dir ": " (str/trim (:err r)))
                      {:reason :checkout :dir (str dir) :argv (vec argv) :err (:err r)})))
    (str/trim (:out r))))

(defn ^{:malli/schema [:=> [:cat :Path]
                       [:map [:git-dir :string] [:commit :string] [:dirty [:maybe :string]]]]}
  committed-tip
  "What a committed checkout of `worktree` is made from: the git directory that
   holds its commits, the commit, and — when the working copy carries changes no
   commit holds — the listing of those changes (nil when clean).

   For a jj workspace the commit is `@` after jj has snapshotted the files, so
   on a clean working copy its tree is exactly the files on disk. Untracked but
   ignored files are in no commit, and so in no checkout."
  [worktree]
  (if (fs/exists? (fs/path worktree ".jj"))
    ;; `jj diff -s` first: it snapshots, so `@` read after it is the tree on disk.
    (let [dirty (not-empty (sh! worktree "jj" "diff" "-s"))]
      {:dirty   dirty
       :commit  (sh! worktree "jj" "log" "-r" "@" "--no-graph" "-T" "commit_id")
       :git-dir (sh! worktree "jj" "git" "root")})
    {:dirty   (not-empty (sh! worktree "git" "status" "--porcelain"))
     :commit  (sh! worktree "git" "rev-parse" "HEAD")
     :git-dir (sh! worktree "git" "rev-parse" "--path-format=absolute" "--git-common-dir")}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] :Path]}
  checkout-root
  "Where one run's committed checkout is made. Under nido's home, not the system
   temp dir: Docker Desktop shares the home directory by default, and a project
   command may bind-mount files of the checkout into a container."
  [project-name session-name]
  (str (fs/path (core/nido-home) "run-checkouts" project-name session-name
                (str (System/currentTimeMillis) "-" (rand-int 1000000)))))

(defn- git-at [git-dir & args]
  ;; hooksPath off: a checkout made for one command must run none of the
  ;; project's hooks (brian's post-checkout/commit hooks expect a dev tree).
  (apply sh! (core/nido-home) "git" (str "--git-dir=" git-dir) "-c" "core.hooksPath=/dev/null" args))

(defn- keep-outputs!
  "Copy each path in `keep` (relative) from `dir` into `worktree`, replacing what
   the worktree had there; a path the command did not write is skipped. Never
   throws: a failed copy must not stop the checkout being removed."
  [dir worktree keep]
  (doseq [rel keep
          :let [from (fs/path dir rel) to (fs/path worktree rel)]
          :when (fs/exists? from)]
    (try
      (fs/delete-tree to)
      (some-> (fs/parent to) fs/create-dirs)
      (if (fs/directory? from)
        (fs/copy-tree from to {:replace-existing true})
        (fs/copy from to {:replace-existing true}))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "nido: could not keep " rel " from the checkout: " (ex-message e))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :Path [:sequential :string] ifn?] :any]}
  with-committed-checkout*
  "Call (f checkout-dir) with a detached git worktree of `worktree`'s committed
   tip, removing it afterwards whatever f does. The checkout's directory has the
   worktree's basename, so a command that names things after its checkout's
   directory names them as it would in the worktree.

   `keep` names paths, relative to the checkout, that the command writes for a
   reader to open afterwards (test reports); they are copied into the worktree
   before the checkout goes, so they land where they did when the command ran
   there. They should be paths the project ignores, or the copy dirties the
   working copy the next run is gated on.

   Refuses a dirty working copy — prints its changes and returns {:exit 1}
   without calling f: the checkout would silently drop them, and a command run
   on it would report on code nobody committed."
  [project-name session-name worktree keep f]
  (let [{:keys [dirty commit git-dir]} (committed-tip worktree)]
    (if dirty
      (do (binding [*out* *err*]
            (println (str "nido: working copy is dirty — commit your changes first. This command "
                          "runs on a checkout of the committed tip, which would not hold them:"))
            (println dirty))
          {:exit 1})
      (let [root (checkout-root project-name session-name)
            dir  (str (fs/path root (fs/file-name worktree)))]
        (fs/create-dirs root)
        (try
          (core/log-step (str "Checking out " (subs commit 0 (min 12 (count commit)))
                              " at " dir))
          (git-at git-dir "worktree" "add" "--detach" "--quiet" dir commit)
          (f dir)
          (finally
            (keep-outputs! dir worktree keep)
            (try (git-at git-dir "worktree" "remove" "--force" dir)
                 (catch Exception _ nil))
            (fs/delete-tree root)
            (try (git-at git-dir "worktree" "prune") (catch Exception _ nil))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :any] :any]}
  run-command-in-session!
  "Resolve the session worktree and run the named :project-commands entry there
   with live (inherited) IO. Returns the babashka.process result map (:exit ...).
   Throws if the project is unregistered, the worktree is missing, or the ref
   is not a declared command. A command with `:checkout :committed` runs with
   {{session.checkout}} bound to a checkout of the committed tip, and copies
   the paths its `:keep` names back into the worktree; see
   with-committed-checkout*."
  [project-name session-name ref]
  (let [project-dir (resolve-project-dir project-name)
        worktree    (lifecycle/worktree-path project-name project-dir session-name)]
    (when-not (fs/exists? worktree)
      (throw (ex-info (str "Worktree not found for " project-name "/" session-name)
                      {:worktree worktree
                       :hint "Bring the session up first: bb nido:session:up :project <p> <session>"})))
    (let [session-edn (engine/load-session-edn project-name)
          commands    (:project-commands session-edn)
          cmd-def     (commands/resolve-command commands ref)
          context     (session-context project-name project-dir session-name worktree)
          run!        (fn [ctx]
                        (commands/run-command! commands ref ctx
                                               {:continue? true :out :inherit :err :inherit}))]
      (case (:checkout cmd-def)
        nil        (run! context)
        :committed (with-committed-checkout* project-name session-name worktree
                     (vec (:keep cmd-def))
                     #(run! (assoc-in context [:session :checkout] %)))
        (throw (ex-info (str "Unknown :checkout for project-command " ref)
                        {:ref ref :checkout (:checkout cmd-def)
                         :hint "The only :checkout nido knows is :committed."}))))))
