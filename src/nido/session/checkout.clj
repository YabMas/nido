(ns nido.session.checkout
  "A runtime's own checkout: a jj workspace of the project, outside every member's worktree, whose
   working copy is the merge of every member's working copy. A runtime of jj-workspace sessions
   runs out of it, so every file a member changes — code, resources, templates, config — reaches
   the runtime once the checkout is brought up to date, and no member's worktree backs anyone
   else's runtime.

   The checkout's working-copy commit is a COPY of the merge, parented on the root, never the
   merge itself: a merge is a descendant of the members' working-copy commits, which jj rebases
   every time a member snapshots, and a workspace sitting on a rebased commit goes stale and comes
   back with whatever conflicts the rebase wrote. A copy is no one's descendant."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.session.state :as state]))

(defn ^{:malli/schema [:=> [:cat :Path] :boolean]}
  jj-workspace?
  "Is `dir` a jj workspace of a colocated source — its `.jj/repo` a pointer file, not the repo?"
  [dir]
  (fs/regular-file? (str (fs/path dir ".jj" "repo"))))

(defn ^{:malli/schema [:=> [:cat :InstanceId] :Path]}
  checkout-dir
  "Where runtime `instance-id`'s checkout lives: inside its state directory, which no worktree is."
  [instance-id]
  (str (fs/path (state/instance-state-dir instance-id) "checkout")))

(defn- workspace-name
  "The jj workspace name of runtime `instance-id`'s checkout, spelled `state/file-safe` so two
   runtimes with two state directories — `p--feat~x` and `p--feat-x` — get two workspaces."
  [instance-id]
  (str "nido-runtime-" (state/file-safe instance-id)))

(defn- jj!
  "Run jj in `dir`. `snapshot?` false passes `--ignore-working-copy`, so `dir`'s files are neither
   read nor written. Throws on a non-zero exit, naming the command and its stderr."
  [dir snapshot? & args]
  (let [argv (cond->> args (not snapshot?) (cons "--ignore-working-copy"))
        r    (apply shell {:dir (str dir) :out :string :err :string :continue true} "jj" argv)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "jj " (str/join " " args) " failed in " dir ": " (str/trim (:err r)))
                      {:reason :jj :dir (str dir) :args (vec args) :err (:err r)})))
    (str/trim (:out r))))

(defn- commit-id [dir snapshot? rev]
  (jj! dir snapshot? "log" "-r" rev "--no-graph" "-T" "commit_id"))

(defn- created
  "The change `jj new --no-edit -m token` just made in `dir`, found by its unique description. A
   change id, not a commit id: a merge is rebased whenever a member snapshots while it exists."
  [dir token]
  (jj! dir false "log" "-r" (str "latest(description(exact:\"" token "\\n\"))")
       "--no-graph" "-T" "change_id"))

(defn- stale?
  "Is `e`, a failed `jj!`, jj's refusal of a workspace whose working-copy commit another
   operation rewrote while its files were left as they were?"
  [e]
  (str/includes? (str (:err (ex-data e))) "working copy is stale"))

(defn- unsnapshottable
  "The refusal of a refresh because the working copies of `dirs` (members' worktrees, in
   `members`) cannot be snapshotted without moving edits only their files carry: the runtime keeps
   serving the checkout it had, and nothing reads such a member as having no edits."
  [members dirs why]
  (ex-info (str "The working copies of " (str/join ", " (sort (map #(or (members %) %) dirs)))
                " cannot be snapshotted — " why
                "; bring them up to date (`jj workspace update-stale` in each) and sync again."
                " The runtime keeps serving the checkout it had")
           {:reason :checkout-unsnapshottable :members (mapv members dirs) :dirs (vec dirs)}))

(defn- snapshot-members!
  "Snapshot the working copy of every member of `members` (worktree to session name) without
   losing an edit any of them holds, or refuse (`unsnapshottable`). Snapshotting a member rewrites
   its working-copy commit and rebases every commit on top of it — another member's, when their
   working copies are stacked — which leaves that member's workspace stale. So members are
   snapshotted descendants first, each before anything is rebased under it, and each one this
   leaves stale, holding nothing unrecorded, is then brought up to date. Members sharing one
   working-copy commit are refused before anything is snapshotted, since the first snapshot would
   strand the other's edits; a member already stale is refused once the others are brought up to
   date, since only its files carry what it changed since."
  [members]
  (let [dirs      (keys members)
        by-commit (group-by #(commit-id % false "@") dirs)
        shared    (into [] (comp (filter #(< 1 (count %))) cat) (vals by-commit))
        _         (when (seq shared)
                    (throw (unsnapshottable members shared "they share one working-copy commit")))
        order     (str/split-lines
                   (jj! (first dirs) false "log" "-r" (str/join "|" (keys by-commit))
                        "--no-graph" "-T" "commit_id ++ \"\\n\""))
        snapped   (group-by #(try (commit-id % true "@") true
                                  (catch clojure.lang.ExceptionInfo e
                                    (if (stale? e) false (throw e))))
                            (mapcat by-commit order))]
    (doseq [d (get snapped true)]
      (try (commit-id d true "@")
           (catch clojure.lang.ExceptionInfo e
             (if (stale? e) (jj! d true "workspace" "update-stale") (throw e)))))
    (when-let [stale (seq (get snapped false))]
      (throw (unsnapshottable members stale
                              "another workspace rewrote their commits before this sync")))))

(defn- merge-of
  "A new commit merging the working copies of `members` (worktree to session name): each member's
   working copy is snapshotted first (`snapshot-members!`, which refuses one it cannot snapshot),
   and a member whose working copy another's contains adds no parent. Returns its commit id."
  [dir members token]
  (snapshot-members! members)
  (let [ids   (mapv #(commit-id % false "@") (keys members))
        heads (str/split-lines
               (jj! dir false "log" "-r" (str "heads(" (str/join "|" ids) ")")
                    "--no-graph" "-T" "commit_id ++ \"\\n\""))]
    (apply jj! dir false "new" "--no-edit" "-m" token heads)
    (created dir token)))

(defn- conflicted-paths [dir rev]
  (let [r (shell {:dir (str dir) :out :string :err :string :continue true}
                 "jj" "--ignore-working-copy" "resolve" "--list" "-r" rev)]
    (->> (str/split-lines (:out r))
         (keep #(some-> (re-find #"^(\S+)" %) second))
         vec)))

(defn- conflict? [dir rev]
  (= "y" (jj! dir false "log" "-r" rev "--no-graph" "-T" "if(conflict, \"y\", \"n\")")))

(defn- generated-paths
  "The files runtime `instance-id`'s services rendered into where it runs out of — its config
   files — read from its own record, so every refresh of its checkout keeps them whoever asks for
   it. None for a runtime with no record yet."
  [instance-id]
  (let [runtime (state/read-session instance-id)]
    (into []
          (keep (fn [{:keys [type name]}]
                  (when (= :config-file type) (get-in runtime [:service-states name :path]))))
          (:service-defs runtime))))

;; A checkout's workspace outlives its directory whenever something deletes the state directory
;; without asking jj — reclaim of an orphan, a host reboot's prune, a hand `rm` — and `workspace
;; add` refuses a name jj still holds. The name is this runtime's alone and its working-copy
;; commit a copy nothing else descends from, so forgetting both loses nothing.
(defn- forget-workspace!
  "Forget runtime `instance-id`'s checkout workspace, running jj in `at` (any working copy of the
   project), and abandon its working-copy commit. A no-op when jj holds no such workspace."
  [instance-id at]
  (let [ws (workspace-name instance-id)]
    (when (some #{ws} (str/split-lines (jj! at false "workspace" "list" "-T" "name ++ \"\\n\"")))
      (let [copy (jj! at false "log" "-r" (str ws "@") "--no-graph" "-T" "change_id")]
        (jj! at false "workspace" "forget" ws)
        (jj! at false "abandon" copy)))))

(defn ^{:malli/schema [:function
                       [:=> [:cat :InstanceId [:map-of :string [:maybe :string]]] :Path]
                       [:=> [:cat :InstanceId [:map-of :string [:maybe :string]] :any] :Path]]}
  refresh!
  "Bring runtime `instance-id`'s checkout up to date with `members` (worktree to session name) —
   every one a jj workspace — creating it on first use, or when its directory is gone though jj
   still holds its workspace, and return its directory. `setup!` is
   called with the directory once, when it is created, so the steps that prepare a working copy
   for a JVM prepare this one too. The files the runtime's services rendered into the checkout
   (`generated-paths`) hold across the refresh what they held before it, since the members' copies
   of them carry none of the runtime's ports or database.

   A merge that conflicts is refused: nothing is changed, the checkout keeps the files it had, and
   the ex-info names the members and the conflicted paths (`:reason :checkout-conflict`). So is a
   refresh whose members' working copies cannot all be snapshotted (`:reason
   :checkout-unsnapshottable`): the checkout keeps the files it had, never one missing a member's
   edits."
  ([instance-id members] (refresh! instance-id members (fn [_])))
  ([instance-id members setup!]
   (let [dir   (checkout-dir instance-id)
         from  (first (keys members))
         token (str "nido runtime " instance-id " " (System/nanoTime))
         merge (merge-of from members (str token " merge"))]
     (when (conflict? from merge)
       (let [paths (conflicted-paths from merge)]
         (jj! from false "abandon" merge)
         (throw (ex-info (str "The working copies of " (str/join ", " (sort (remove nil? (vals members))))
                              " conflict in " (str/join ", " paths)
                              "; the runtime keeps serving the checkout it had")
                         {:reason :checkout-conflict :members (vec (vals members)) :paths paths}))))
     (jj! from false "new" "--no-edit" "-m" token "root()")
     (let [copy (created from token)]
       (jj! from false "restore" "--from" merge "--into" copy)
       (jj! from false "abandon" merge)
       (if (fs/exists? (fs/path dir ".jj"))
         (let [prior (jj! dir false "log" "-r" "@" "--no-graph" "-T" "change_id")
               held  (into {} (keep #(when (fs/exists? %) [% (fs/read-all-bytes %)]))
                           (generated-paths instance-id))]
           (jj! dir true "edit" copy)
           (doseq [[path content] held] (fs/write-bytes path content))
           (when (not= prior copy) (jj! dir false "abandon" prior)))
         (do (fs/create-dirs (fs/parent dir))
             (forget-workspace! instance-id from)
             (jj! from true "workspace" "add" "--name" (workspace-name instance-id) "-r" copy dir)
             ;; `workspace add` puts an empty commit on top; edit the copy itself, which abandons it.
             (jj! dir true "edit" copy)
             (setup! dir))))
     dir)))

(defn ^{:malli/schema [:=> [:cat :InstanceId [:maybe :Path]] :nil]}
  remove!
  "Forget runtime `instance-id`'s checkout workspace and delete its directory, abandoning its
   working-copy commit. `from` is any working copy of the project, used when the checkout's own
   is already gone; nil skips the forget. Never throws: a runtime is stopped whatever is left."
  [instance-id from]
  (let [dir (checkout-dir instance-id)]
    (try
      (when-let [at (if (fs/exists? (fs/path dir ".jj")) dir from)]
        (forget-workspace! instance-id at))
      (catch Exception _ nil))
    (when (fs/exists? dir) (fs/delete-tree dir))
    nil))
