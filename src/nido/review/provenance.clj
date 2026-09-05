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
   than at whatever nido's checkout has since become."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [nido.vsdd.jj :as jj]))

(def ^:private anchor
  "A review source file, spelled the way the classpath spells it.

   Resolving THIS rather than asking for a directory is what makes the answer
   the code that is running: `bb --config <elsewhere>/bb.edn` puts a source root
   on the classpath bearing no relation to the cwd, and every namespace of the
   loop is loaded from that root while every path the run otherwise handles
   points at the tree under review."
  "nido/review/report.clj")

(defn ^{:malli/schema [:=> [:cat] [:maybe :map]]}
  loaded-from
  "`{:root <the source root the nido.review.* namespaces came from> :rev <the
   commit it stands at>}`, or nil when the loop is not running off files on
   disk.

   `:rev` is nil when that root is in no jj workspace — a copied tree, an
   archive — which is worth saying rather than a reason to say nothing: the path
   alone already separates nido's own checkout from a session worktree, and
   which of the two supplied the code was the question a stale run could not
   answer.

   Read with `--ignore-working-copy`, so `:rev` is the last recorded
   working-copy commit and edits not yet snapshotted are outside it. The
   alternative is a review writing to a workspace it does not own, and that
   workspace is normally nido's root checkout, which the sessions this run
   spawns read from."
  []
  (when-let [url (io/resource anchor)]
    (when (= "file" (.getProtocol url))
      (let [path (str (fs/path (.toURI url)))
            root (str (fs/path (subs path 0 (- (count path) (count anchor)))))
            {:keys [exit out]} (try
                                 (jj/jj! root "--ignore-working-copy" "log"
                                         "-r" "@" "-T" "commit_id" "--no-graph")
                                 (catch Exception _ nil))]
        {:root root
         :rev  (when (and (= 0 exit) (not (str/blank? out))) out)}))))
