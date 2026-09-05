(ns nido.review.provenance-test
  "The stamp is only worth carrying if it names a tree that exists and a
   revision the analysis can read the loop at. Both are ambient facts about
   however this suite was launched — a classpath and a jj workspace — so
   nothing else in the suite would notice them going wrong."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.review.provenance :as provenance]
   [nido.vsdd.jj :as jj]))

(deftest names-the-tree-the-running-loop-came-from
  (let [{:keys [root]} (provenance/loaded-from)]
    (is (some? root)
        "a report with no reviewer stamp is the state this exists to end")
    (is (fs/exists? (fs/path root "nido" "review" "report.clj"))
        (str "the stamp answers WHICH copy of the loop ran, so it has to name the"
             " source root the namespaces were loaded from; " root
             " does not hold them"))))

(deftest stamps-a-revision-the-loop-can-be-read-back-at
  (let [{:keys [root rev]} (provenance/loaded-from)]
    (is (re-matches #"[0-9a-f]{40}" (str rev))
        "nido and its session worktrees are jj workspaces, so a run here has a revision")
    (is (zero? (:exit (jj/jj! root "--ignore-working-copy" "file" "show"
                              "-r" rev "nido/review/report.clj")))
        (str "the analysis reads the machinery at this revision instead of at nido's"
             " current checkout; a revision jj cannot resolve sends it back to the"
             " checkout, which is what filed already-fixed defects as fresh bugs"))))
