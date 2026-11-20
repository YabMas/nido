(ns nido.session.change
  "A session's unlanded change: what it is, sealing and landing it, bringing the worktree onto main
   after a landing, and throwing the change away.

   HIDES the revsets that say what a session has not landed and what it is landing, how a change is
   sealed — the working copy moves to a new change above it — how the worktree is brought onto main
   — a rebase that drops what main's content empties — how a revert is made, and that publishing is
   the landing verb run as a process in the session's worktree, told the sealed revision.

   A change is a value, `{:description :files :rev :conflicts}`: the description of its newest
   described commit (what it would land as; blank when the agent has not described it), every path
   it touches, each `{:path :status}` with status one of :added :modified :deleted :renamed, the
   commit id of the working copy it was read at — what `seal!` is handed back, so a seal never
   fixes a change nobody saw — and the paths left conflicted in it, empty when none. No change is
   nil.

   Origin's main is read as the last fetch left it, so a change based on an older main still reads
   as only its own commits; publishing fetches for itself."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.session.lifecycle :as lifecycle]))

(def ^:private unlanded "main@origin..@")

(def ^{:doc "The commits that carry nothing: empty, and not a merge. A merge with nothing beyond its
   parents' merge is empty by construction and still joins them, so it is never one of these."}
  hollow
  "empty() ~ merges()")

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  tip-of
  "The revset naming the nearest commit at or below `rev` that is not `hollow` — the tip a change
   lands as. One commit, as no merge is passed over."
  [rev]
  (str "heads(::(" rev ") ~ (" hollow "))"))

(defn- worktree [project session]
  (:wt-path (lifecycle/session-coords session {:project (name project)})))

(defn- jj! [dir & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir dir :continue true :out :string :err :string}
                                      "jj" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "jj " (str/join " " args) " failed: " (str/trim (str err)))
                      {:exit exit :err err})))
    (str/trim (str out))))

(def ^:private statuses {"A" :added "M" :modified "D" :deleted "R" :renamed "C" :added})

(defn- parse-summary
  "`jj diff --summary` lines, `M path` or `R {old => new}`, as `{:path :status}`s."
  [out]
  (vec (for [line (str/split-lines out)
             :let [[_ code path] (re-matches #"([A-Z])\s+(.+)" (str/trim line))]
             :when code]
         {:path path :status (statuses code :modified)})))

(defn- described
  "The description of the newest described commit in `revset`, blank when none is described."
  [dir revset]
  (jj! dir "log" "--no-graph" "-r" (str "latest((" revset ") & description(regex:'.'))")
       "-T" "description"))

(defn ^{:malli/schema [:=> [:cat :any :string] [:maybe :string]]}
  one-commit
  "The commit id `revset` names in the repository at `dir` when it names exactly one commit, else
   nil — also when jj cannot answer."
  [dir revset]
  (try (let [ids (remove str/blank? (str/split-lines (jj! (str dir) "log" "--no-graph" "-r" revset
                                                          "-T" "commit_id ++ \"\\n\"")))]
         (when (= 1 (count ids)) (first ids)))
       (catch Exception _ nil)))

(defn- conflicts
  "The paths conflicted in any commit of `revset`, as a vector; empty when none is."
  [dir revset]
  (let [c (first (str/split-lines (jj! dir "log" "--no-graph" "-r" (str "(" revset ") & conflicts()")
                                       "-T" "change_id.short() ++ \"\\n\"")))]
    (if (str/blank? c)
      []
      (vec (keep #(some-> (re-find #"^(\S+)" (str/trim %)) second)
                 (str/split-lines (jj! dir "resolve" "--list" "-r" c)))))))

(defn ^{:malli/schema [:=> [:cat :any :string [:? [:maybe :string]]] [:maybe :map]]}
  pending
  "The change `session` holds that origin's main does not, or nil when it holds none. Given the
   `sealed` revision of a publish, only what the worktree holds above it: the diff from that
   revision to the working copy. Throws when the worktree cannot be read."
  ([project session] (pending project session nil))
  ([project session sealed]
   (let [dir   (worktree project session)
         rev   (jj! dir "log" "--no-graph" "-r" "@" "-T" "commit_id")
         files (parse-summary (if sealed
                                (jj! dir "diff" "--summary" "--from" sealed "--to" "@")
                                (jj! dir "diff" "--summary" "-r" unlanded)))]
     (when (seq files)
       {:description (described dir (if sealed (str sealed "..@") unlanded))
        :files       files
        :rev         rev
        :conflicts   (conflicts dir unlanded)}))))

(defn ^{:malli/schema [:=> [:cat :any :string :string] [:maybe :map]]}
  landing
  "What `sealed` lands — the commits of origin's main..`sealed` taken together, as
   {:description :files} — or nil when origin's main holds them all."
  [project session sealed]
  (let [dir   (worktree project session)
        arc   (str "main@origin.." sealed)
        files (parse-summary (jj! dir "diff" "--summary" "-r" arc))]
    (when (seq files)
      {:description (described dir arc) :files files})))

(defn ^{:malli/schema [:=> [:cat :any :string :string] [:maybe :string]]}
  seal!
  "Fix `session`'s change as a revision and start a new change above it, when its working copy is
   still `rev` — the revision a `pending` reading returned — sits on origin's main and holds an
   unconflicted change. Returns the sealed commit id, or nil, sealing nothing, otherwise. Never
   called while the session's agent runs a turn."
  [project session rev]
  (let [dir (worktree project session)]
    (when (and (= rev (jj! dir "log" "--no-graph" "-r" "@" "-T" "commit_id"))
               (not (str/blank? (jj! dir "log" "--no-graph" "-r" "main@origin & ::@" "-T" "commit_id")))
               (seq (parse-summary (jj! dir "diff" "--summary" "-r" unlanded)))
               (empty? (conflicts dir unlanded)))
      (if (str/blank? (jj! dir "log" "--no-graph" "-r" (str "@ & (" hollow ")") "-T" "commit_id"))
        (do (jj! dir "new") rev)
        (one-commit dir (tip-of "@"))))))

(defn ^{:malli/schema [:=> [:cat :any :string] [:vector :string]]}
  onto-main!
  "When origin's main is not an ancestor of `session`'s working copy, rebase the worktree onto it,
   dropping each commit main's content empties — a landing's own commits, once main holds their
   copies. Returns the paths left conflicted, empty when none; a conflicted rebase stays in the
   worktree for the agent to resolve. Never called while the session's agent runs a turn."
  [project session]
  (let [dir (worktree project session)]
    (when (str/blank? (jj! dir "log" "--no-graph" "-r" "main@origin & ::@" "-T" "commit_id"))
      (jj! dir "rebase" "-b" "@" "-d" "main@origin" "--skip-emptied"))
    (conflicts dir unlanded)))

(defn ^{:malli/schema [:=> [:cat :any :string] :any]}
  revert!
  "Throw away every change `session` holds that origin's main does not: its working copy becomes an
   empty change on origin's main, and the commits it had are abandoned. jj's operation log keeps
   them, so `jj op restore` brings them back. Throws when jj refuses, leaving what it had not done."
  [project session]
  (let [dir (worktree project session)
        old (jj! dir "log" "--no-graph" "-r" "@" "-T" "change_id")]
    (jj! dir "new" "main@origin")
    (jj! dir "abandon" (str "main@origin.." old))
    nil))

(defn ^{:malli/schema [:=> [:cat :any :string :string :any] :int]}
  publish!
  "Land `session`'s sealed revision `rev` on origin's main by running `bb nido:land :rev <rev>` in
   its worktree, calling `on-line` with each line it prints as it prints it; returns its exit code
   once it has finished. The verb is the worktree's own and loads before it prints its first line,
   so its code is the tree the worktree held then. Zero is not the sign of a landing: a landing
   whose recording or owed restart failed exits non-zero after printing `land pushed · `."
  [project session rev on-line]
  (let [proc (p/process ["bb" "nido:land" ":rev" rev]
                        {:dir (worktree project session) :err :out :shutdown p/destroy-tree})]
    (with-open [r (io/reader (:out proc))]
      (doseq [line (line-seq r)] (on-line line)))
    (:exit @proc)))
