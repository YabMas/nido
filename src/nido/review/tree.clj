;; src/nido/review/tree.clj
(ns nido.review.tree
  "Which tree a record round reads when its caller names none, and producing it.

   A record is ABOUT a revision, and the session worktree is not one. A baseline describes the
   area before the change, so a worktree carrying the change is the one tree it must not be
   judged against: the judge reports the change's new modules as omissions and the amender
   writes them into the survey. A design adds its declaration to that same base and no code, so
   it is judged against the base with the worktree's spec dirs laid over it — the base alone
   would not declare its subjects, and the worktree would show code the design has not yet been
   decided on.

   The base is the fork point of the worktree's @ with main, the same commit a diff review
   measures the branch from. When the worktree does not differ from what a round wants, the
   worktree IS that tree and nothing is produced; otherwise a jj workspace is added at the fork
   point for the length of the round and removed after it."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [nido.design.check :as design]
   [nido.review.pass :as pass]
   [nido.vsdd.jj :as jj]))

(def ^:private base
  "What the fork point is taken with — the diff review's default base, so the tree a baseline
   is judged against is the one the branch's review diffs from."
  "main")

(defn- changed-paths
  "The paths @ changes relative to `rev` in `worktree`, or nil when jj will not say."
  [worktree rev]
  (let [{:keys [exit out]} (try (jj/jj! worktree "diff" "--name-only" "--from" rev "--to" "@")
                                (catch Exception _ {:exit 1}))]
    (when (zero? (long exit))
      (vec (remove str/blank? (str/split-lines out))))))

(defn- inside?
  "Whether `path` lies under one of `dirs`, each relative to the worktree root."
  [dirs path]
  (some #(str/starts-with? path (str % "/")) dirs))

(defn ^{:malli/schema [:=> [:cat [:enum :baseline :design] [:maybe :ProjectName] :Path] :map]}
  reading
  "Where a `kind` round in `worktree` reads when it is given no tree:

     {:dir <worktree>}                            the worktree is already that tree
     {:dir <worktree> :unresolved <why>}           no fork point could be found; the worktree is
                                                   all there is, and the round says so
     {:rev <commit> :overlay [dir …] :ahead n}     the fork point, with those of the worktree's
                                                   spec dirs laid over it; n paths of the
                                                   worktree differ from what the round reads, or
                                                   :unknown when jj would not diff them — a known
                                                   fork point is read even then

   `:overlay` is empty for a baseline, and the project's configured spec dirs for a design —
   whether or not the worktree's still hold a declaration, since emptying them is a change the
   design round must read — and empty with no project to ask. Reads jj and writes nothing, and
   never throws: a worktree with no fork point jj can find is read as it stands."
  [kind project worktree]
  (let [rev (try (pass/merge-base worktree base) (catch Exception e e))]
    (if (instance? Exception rev)
      {:dir worktree :unresolved (ex-message rev)}
      (let [overlay (if (and (= :design kind) project)
                      (design/spec-dirs project)
                      [])
            ahead   (some->> (changed-paths worktree rev) (remove #(inside? overlay %)) count)]
        (cond
          ;; The fork point is known, so a diff jj refuses cannot pick another tree: read the
          ;; fork point, as a worktree that differs from it would be.
          (nil? ahead)  {:rev rev :overlay overlay :ahead :unknown}
          (zero? ahead) {:dir worktree}
          :else         {:rev rev :overlay overlay :ahead ahead})))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :string]]}
  line
  "What a round reading `plan` says about its tree before it launches anything, or nil when it
   reads the worktree as it is."
  [{:keys [rev overlay ahead unresolved]}]
  (cond
    unresolved
    (str "note: judging the worktree as it stands — its fork point with " base
         " could not be read (" unresolved ")")

    rev
    (str "judging " (subs rev 0 (min 12 (count rev))) ", this worktree's fork point with " base
         (when (seq overlay) (str ", with the worktree's " (str/join ", " overlay) "/"))
         " — " (if (= :unknown ahead)
                 "jj would not say which paths here differ from it"
                 (str ahead " path(s) here differ from it"))
         ". Name :code-cwd to judge another tree.")))

(defn- working-copy-rev
  "The commit `dir`'s working copy is at — jj's @, which holds its uncommitted edits, or git's HEAD
   in a plain git checkout — or nil. git is asked only when jj says `dir` is in no jj repository: a
   jj workspace nested in a git checkout is one git would read as the outer repository."
  [dir]
  (try
    (let [{:keys [exit out]} (jj/jj! dir "log" "-r" "@" "--no-graph" "-T" "commit_id")]
      (if (zero? (long exit))
        (not-empty (str/trim out))
        (when-not (zero? (long (:exit (jj/jj! dir "root"))))
          (let [{:keys [exit out]} (p/shell {:dir dir :out :string :err :string :continue true}
                                            "git" "rev-parse" "HEAD")]
            (when (zero? (long exit)) (not-empty (str/trim out)))))))
    (catch Throwable _ nil)))

(defn ^{:malli/schema [:=> [:cat :map :Path] :map]}
  stamp
  "Which revision a round reading `plan` in `dir` judges, as `nido.coordinator.report/JudgedTree`
   records it: the fork point with its overlay and how far the worktree is ahead of it, or — when
   the round reads a tree as it stands — the commit `dir`'s working copy is at, with why no fork
   point could be read when that is why. Reads jj, or git, and never throws; a revision nothing
   would name is left out rather than guessed."
  [{:keys [rev overlay ahead unresolved]} dir]
  (if rev
    (cond-> {:rev rev}
      (seq overlay) (assoc :overlay (vec overlay))
      (some? ahead) (assoc :ahead ahead))
    (let [at (working-copy-rev dir)]
      (cond-> {}
        at         (assoc :rev at)
        unresolved (assoc :unresolved unresolved)))))

(defn- remove-workspace!
  "Take the round's workspace out of the repo and off the disk. Its working-copy commit is
   abandoned first: an overlay makes it non-empty, and forgetting a workspace keeps a non-empty
   one as a visible head nobody asked for.

   Both run with --ignore-working-copy: the round's jj use in the workspace can leave the
   worktree's working copy stale, and a stale one would refuse the snapshot they otherwise
   take. Throws on the first step jj refuses, leaving the rest undone — the directory is
   deleted only once the workspace is forgotten, so a failure never leaves a registered
   workspace with no directory behind it."
  [worktree ws-name dir]
  (doseq [[what args] [["abandon its working-copy commit" ["abandon" (str ws-name "@")]]
                       ["forget it" ["workspace" "forget" ws-name]]]]
    (let [{:keys [exit err]} (apply jj/jj! worktree "--ignore-working-copy" args)]
      (when-not (zero? (long exit))
        (throw (ex-info (str "could not remove workspace " ws-name " — jj would not " what
                             ": " err)
                        {:ws-name ws-name :dir (str dir) :err err})))))
  (fs/delete-tree dir))

(defn- registered?
  "Whether the repo lists a workspace named `ws-name` — true, false, or nil when jj will not
   say. Run with --ignore-working-copy for the reason `remove-workspace!` gives."
  [worktree ws-name]
  (let [{:keys [exit out]} (jj/jj! worktree "--ignore-working-copy" "workspace" "list"
                                   "-T" "name ++ \"\\n\"")]
    (when (zero? (long exit))
      (boolean (some #{ws-name} (str/split-lines out))))))

(defn- undo-failed-add!
  "Clear what a `jj workspace add` that failed left behind. It can fail after registering the
   workspace, and deleting the directory alone would leave the repo listing a workspace with no
   directory — so a registered one is removed as a round's is. Where jj will not say whether it
   was registered, or will not remove it, the directory stays for the same reason
   `remove-workspace!` keeps it; the second case throws."
  [worktree ws-name dir]
  (case (registered? worktree ws-name)
    true  (remove-workspace! worktree ws-name dir)
    false (fs/delete-tree dir)
    nil))

(defn ^{:malli/schema [:=> [:cat :Path :map :string :Path [:=> [:cat :Path] :any]] :any]}
  with-reading!
  "Call `f` with the directory `plan` names, and answer what it answers.

   A plan naming a directory is handed through. A plan naming a revision is produced first — a
   jj workspace named `ws-name` at `dir`, checked out at the revision, with the worktree's
   overlay dirs copied over the revision's — and removed when `f` returns or throws, so the
   repo lists no workspace of the round's after it. `dir` must not exist. Throws when the
   workspace cannot be added, before `f` is called: a round has no tree to fall back to that
   would not be the wrong one. Throws, too, when the workspace cannot be removed, rather than
   answer as though it were gone; when `f` threw as well, that failure rides on f's throw."
  [worktree {:keys [rev overlay] :as plan} ws-name dir f]
  (if-not rev
    (f (:dir plan))
    (let [{:keys [exit err]} (jj/jj! worktree "workspace" "add" "--name" ws-name
                                     "--revision" rev (str dir))]
      (when-not (zero? (long exit))
        (let [failure (ex-info (str "could not check out " rev " to judge against: " err)
                               {:rev rev :dir (str dir) :err err})]
          (try (undo-failed-add! worktree ws-name dir)
               (catch Throwable removal (.addSuppressed failure removal)))
          (throw failure)))
      (let [answer (try
                     (doseq [d overlay]
                       (fs/delete-tree (fs/path dir d))
                       (when (fs/directory? (fs/path worktree d))
                         (fs/copy-tree (fs/path worktree d) (fs/path dir d))))
                     (f (str dir))
                     (catch Throwable t
                       ;; A removal that fails too is carried on f's throw, not put in its place.
                       (try (remove-workspace! worktree ws-name dir)
                            (catch Throwable removal (.addSuppressed t removal)))
                       (throw t)))]
        (remove-workspace! worktree ws-name dir)
        answer))))
