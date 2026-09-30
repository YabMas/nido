;; src/nido/review/provenance.clj
(ns nido.review.provenance
  "Which copy of the review loop's own code a run is executing.

   The report pins the revision of the code REVIEWED and said nothing about the
   revision of the code that DID the reviewing, so nothing in a run separated a
   live defect in the loop from an artefact of a stale invocation. One run
   executed machinery eleven commits behind main — nine of them changes to the
   loop itself — and reproduced, one at a time, four record defects those
   commits had already fixed; establishing that took bisecting main. Stamped on
   every report so the analysis reads the loop at the revision that ran rather
   than at whatever nido's checkout has since become.

   A `bb --config ~/Code/nido/bb.edn` run loads whatever the root checkout's
   working copy holds, and that checkout drifts behind main whenever an arc
   lands from a session worktree without the root being rebased. Nothing here
   loads from anywhere else — the classpath is fixed before any of this runs —
   so the stamp says, beside the revision, how far that copy is from main and
   whether it is even a revision at all, and `warning` says it on the loop's
   first line."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)))

(def ^:private anchor
  "A review source file, spelled the way the classpath spells it.

   Resolving THIS rather than asking for a directory is what makes the answer
   the code that is running: `bb --config <elsewhere>/bb.edn` puts a source root
   on the classpath bearing no relation to the cwd, and every namespace of the
   loop is loaded from that root while every path the run otherwise handles
   points at the tree under review."
  "nido/review/report.clj")

(def machinery-paths
  "The loop's own code and the resources it hands its judges, relative to the
   checkout that holds the source root. What `:hash` covers, what a commit must
   touch to count in `:lacks`, and what a reviewed branch must touch to be
   `:self-review?`. Not the rest of `src/`: a change there is not a change to
   how a round judges, and counting it would make every stamp read as stale."
  ["resources/review" "src/nido/review" "src/tasks/nido_review.clj"])

(def ^:private machinery-fileset
  (str/join " | " (map pr-str machinery-paths)))

(defn- jj
  "jj's trimmed stdout in `dir`, or nil when it failed or said nothing.

   Always `--ignore-working-copy`: the directories read here are nido's root
   checkout and the reviewed worktree, neither of which this run owns, and a
   snapshot is a write. So `@` is the last recorded working-copy commit, and
   what the disk holds beyond it is `:dirty?`'s question, not jj's."
  [dir & args]
  (let [{:keys [exit out]} (apply p/shell {:dir dir :continue true :out :string :err :string}
                                  "jj" "--ignore-working-copy" args)
        out (str/trim (str out))]
    (when (and (zero? exit) (not (str/blank? out))) out)))

(defn- sha256 [^bytes bs]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (.update d bs)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- disk-files
  "The machinery files under `repo` as relative paths, in the order jj lists
   them — bytewise, which for these ASCII paths is `sort`."
  [repo]
  (sort (for [rel machinery-paths
              :let [f (fs/path repo rel)]
              path (cond (fs/directory? f)     (filter fs/regular-file? (fs/glob f "**"))
                         (fs/regular-file? f)  [f]
                         :else                 [])]
          (str/replace (str (fs/relativize repo path)) java.io.File/separator "/"))))

(defn- disk-hash
  "SHA-256 of the machinery files as the disk holds them, concatenated in path
   order. Chosen so it is reproducible at any revision with
   `jj file show -r <rev> <machinery-paths> | shasum -a 256`: equal exactly when
   that revision holds what was loaded."
  [repo files]
  (let [out (java.io.ByteArrayOutputStream.)]
    (doseq [f files] (.write out (fs/read-all-bytes (fs/path repo f))))
    (sha256 (.toByteArray out))))

(defn- dirty?
  "Whether the disk holds machinery that `rev` does not — an edit no commit
   names, which is how one run's rounds ran different code from each other.
   nil when jj could not answer."
  [repo rev files hash]
  (when-let [listed (apply jj repo "file" "list" "-r" rev machinery-paths)]
    (let [{:keys [exit out]} (apply p/shell {:dir repo :continue true :out :bytes :err :string}
                                    "jj" "--ignore-working-copy" "file" "show" "-r" rev
                                    machinery-paths)]
      (when (zero? exit)
        (or (not= (vec files) (sort (str/split-lines listed)))
            (not= hash (sha256 out)))))))

(defn- lag
  "How the checkout's `@` stands to the local `main` bookmark — the one landing
   here fast-forwards. No fetch: a main that moved on origin and was not fetched
   is not seen, and a review reaching the network to stamp itself is worse.

   `:main-base` is the newest commit on main's ancestry that `@` contains, which
   is the revision the analysis can read the loop at when `@` itself is an
   unnamed working-copy commit on nobody's history. `:lacks` names main's
   commits the checkout is missing that change the machinery."
  [repo]
  (when-let [base (jj repo "log" "--no-graph" "-r" "heads(::@ & ::main)" "-T" "commit_id")]
    {:main-base   base
     :behind-main (count (some-> (jj repo "log" "--no-graph" "-r" "::main ~ ::@"
                                     "-T" "commit_id ++ \"\\n\"")
                                 str/split-lines))
     :lacks       (vec (some-> (jj repo "log" "--no-graph"
                                   "-r" (str "(::main ~ ::@) & files(" machinery-fileset ")")
                                   "-T" "commit_id.short(8) ++ \" \" ++ description.first_line() ++ \"\\n\"")
                               str/split-lines))}))

(defn- against-target
  "How the loaded machinery stands to the tree under review at `target-cwd`.

   `:behind-base?` — the reviewed branch forked from main at a commit the
   loaded machinery does not contain, so older code is judging newer code. Only
   answerable when both are one repository; absent otherwise.

   `:self-review?` — the reviewed branch changes the machinery itself, so either
   the loop is judging its own change with that change (`:own-copy?`, loaded
   from inside the reviewed tree) or judging it with the code it replaces. A
   project that is not nido never touches these paths and is always false."
  [repo root target-cwd]
  (when (and target-cwd (fs/directory? target-cwd))
    (let [fork (jj target-cwd "log" "--no-graph" "-r" "heads(::@ & ::main)" "-T" "commit_id")
          own? (str/starts-with? (str (fs/canonicalize root))
                                 (str (fs/canonicalize target-cwd) java.io.File/separator))]
      (cond-> {:self-review? (some? (jj target-cwd "log" "--no-graph" "--limit" "1"
                                        "-r" (str "(::@ ~ ::main) & files(" machinery-fileset ")")
                                        "-T" "commit_id"))}
        own? (assoc :own-copy? true)
        ;; A fork this repo cannot resolve is another project's commit, and
        ;; says nothing about staleness.
        (and fork (jj repo "log" "--no-graph" "-r" fork "-T" "commit_id"))
        (assoc :behind-base? (some? (jj repo "log" "--no-graph" "-r" (str fork " ~ ::@")
                                        "-T" "commit_id")))))))

(defn- stamp
  "`loaded-from`'s answer for the source root `root`, which sits one level
   below the checkout holding `machinery-paths`."
  [root target-cwd]
  (let [repo  (str (fs/parent root))
        rev   (jj repo "log" "-r" "@" "-T" "commit_id" "--no-graph")
        files (disk-files repo)
        hash  (disk-hash repo files)]
    (merge {:root root :rev rev :hash hash :hashed machinery-paths}
           (when rev
             (merge (some->> (dirty? repo rev files hash) (hash-map :dirty?))
                    (lag repo)
                    (against-target repo root target-cwd))))))

(defn ^{:malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat [:maybe :Path]] [:maybe :map]]]}
  loaded-from
  "`{:root <the source root the nido.review.* namespaces came from> :rev <the
   commit it stands at> …}`, or nil when the loop is not running off files on
   disk. With `target-cwd`, the tree under review, it also says how the two
   stand to each other — see `against-target`.

   `:hash` is `disk-hash` of `machinery-paths` as they are on disk now, which is
   seconds after bb loaded them and before any round has run; the resources a
   round hands its judge are read at namespace load for the same reason, so
   one run's rounds share one copy. `:dirty?` is true when that copy is not
   what `:rev` holds.

   `:rev` and everything derived from jj are nil when the root is in no jj
   workspace — a copied tree, an archive — which is worth saying rather than a
   reason to say nothing: the path alone already separates nido's own checkout
   from a session worktree, and `:hash` still names the code."
  ([] (loaded-from nil))
  ([target-cwd]
   (when-let [url (io/resource anchor)]
     (when (= "file" (.getProtocol url))
       (let [path (str (fs/path (.toURI url)))]
         (stamp (str (fs/path (subs path 0 (- (count path) (count anchor)))))
                target-cwd))))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :string]]}
  warning
  "One line saying why this run's machinery should not be trusted to be
   main's, or nil when nothing does. Pure over `loaded-from`'s map — a report
   read back from JSON included — so the loop prints it at start and the
   analysis headline repeats it.

   A lag that touches none of `machinery-paths` is stamped and not said: every
   checkout is behind main by something, and a line on every run is a line
   nobody reads."
  [{:keys [root lacks dirty? behind-base? self-review? own-copy?]}]
  (let [parts (cond-> []
                (seq lacks)
                (conj (str "lacks " (count lacks) " commit" (when (not= 1 (count lacks)) "s")
                           " on main that change the loop (" (str/join "; " lacks) ")"
                           " — rebase " (some-> root fs/parent str) " onto main"))
                dirty?
                (conj "holds edits to the loop that no commit has, so no revision names the code that ran")
                behind-base?
                (conj "predates the reviewed branch's fork point, so older code is judging newer")
                self-review?
                (conj (if own-copy?
                        "the reviewed branch changes the loop, and this run is executing that change"
                        "the reviewed branch changes the loop, and this run is executing the code it replaces")))]
    (when (seq parts)
      (str "warning: review machinery at " root " " (str/join "; " parts)))))
