(ns nido.review.provenance-test
  "The stamp is only worth carrying if it names a tree that exists and a
   revision the analysis can read the loop at. Both are ambient facts about
   however this suite was launched — a classpath and a jj workspace — so
   nothing else in the suite would notice them going wrong."
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [clojure.string :as str]
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

;; ── How the loaded copy stands to main ──────────────────────────────────────

(defn- write! [dir rel text]
  (let [f (fs/path dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(defn- commit-id [dir rev]
  (:out (jj/jj! dir "log" "--no-graph" "-r" rev "-T" "commit_id")))

(defn- a-checkout
  "A jj repo shaped like nido's: machinery at `old`, and a `main` that has
   since gained one commit changing the loop and one that does not. `@` is
   left an empty working-copy commit on `old`, which is what a root checkout
   nobody rebased looks like."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "nido-provenance"}))]
    (jj/jj! dir "git" "init" ".")
    (write! dir "src/nido/review/report.clj" "(ns r)\n")
    (write! dir "src/tasks/nido_review.clj" "(ns t)\n")
    (write! dir "resources/review/findings_schema.json" "{}\n")
    (write! dir "src/nido/other.clj" "(ns o)\n")
    (jj/jj! dir "commit" "-m" "old")
    (let [old (commit-id dir "@-")]
      (write! dir "src/nido/review/report.clj" "(ns r) ;; fixed\n")
      (jj/jj! dir "commit" "-m" "fix the loop")
      (write! dir "src/nido/other.clj" "(ns o) ;; elsewhere\n")
      (jj/jj! dir "commit" "-m" "unrelated")
      (jj/jj! dir "bookmark" "create" "main" "-r" "@-")
      (jj/jj! dir "new" old)
      {:dir dir :old old})))

(deftest a-copy-behind-main-names-the-loop-commits-it-lacks
  (let [{:keys [dir old]} (a-checkout)]
    (try
      (let [m (#'provenance/stamp (str (fs/path dir "src")) nil)]
        (is (= old (:main-base m))
            (str "the working-copy commit is on no branch, so the analysis needs the"
                 " newest commit on main the copy contained to read the loop at"))
        (is (= 2 (:behind-main m)))
        (is (= 1 (count (:lacks m))) "only a commit that changes the loop makes the run's code suspect")
        (is (str/includes? (first (:lacks m)) "fix the loop"))
        (is (str/includes? (str (provenance/warning m)) "lacks 1 commit")
            (str "twelve runs executed a stale copy and nothing at launch said so;"
                 " the warning is what makes it visible before a round is spent")))
      (finally (fs/delete-tree dir)))))

(deftest a-copy-behind-main-only-elsewhere-is-not-warned-about
  (let [{:keys [dir]} (a-checkout)]
    (try
      (jj/jj! dir "edit" "main-")
      (let [m (#'provenance/stamp (str (fs/path dir "src")) nil)]
        (is (= 1 (:behind-main m)) "the lag is stamped whatever it touches")
        (is (empty? (:lacks m)))
        (is (nil? (provenance/warning m))
            "every checkout lags main by something; a warning on every run is one nobody reads"))
      (finally (fs/delete-tree dir)))))

(deftest the-hash-names-what-was-loaded-and-an-unsnapshotted-edit-is-dirty
  (let [{:keys [dir]} (a-checkout)]
    (try
      (let [src   (str (fs/path dir "src"))
            clean (#'provenance/stamp src nil)
            shown (:out (babashka.process/shell
                         {:dir dir :out :string}
                         "sh" "-c" (str "jj --ignore-working-copy file show -r " (:rev clean)
                                        " resources/review src/nido/review src/tasks/nido_review.clj"
                                        " | shasum -a 256")))]
        (is (false? (:dirty? clean)))
        (is (str/starts-with? shown (:hash clean))
            "the hash is only a name for the code if it can be recomputed at a revision")
        (write! dir "resources/review/findings_schema.json" "{\"changed\": true}\n")
        (let [dirty (#'provenance/stamp src nil)]
          (is (true? (:dirty? dirty))
              (str "a schema edited mid-run gave a round code no revision holds;"
                   " a stamp naming only :rev hid that"))
          (is (not= (:hash clean) (:hash dirty)))
          (is (str/includes? (str (provenance/warning dirty)) "no commit"))))
      (finally (fs/delete-tree dir)))))

(deftest a-branch-changing-the-loop-is-self-review
  (let [{:keys [dir]} (a-checkout)]
    (try
      (jj/jj! dir "new" "main")
      (write! dir "src/nido/review/report.clj" "(ns r) ;; the branch\n")
      (jj/jj! dir "commit" "-m" "branch changes the loop")
      (let [src (str (fs/path dir "src"))
            m   (#'provenance/stamp src dir)]
        (is (true? (:self-review? m))
            "a branch changing the loop is judged by that change or by what it replaces, and a reader must know which")
        (is (true? (:own-copy? m)))
        (is (false? (:behind-base? m)))
        (is (str/includes? (str (provenance/warning m)) "executing that change")))
      (finally (fs/delete-tree dir)))))

(deftest a-copy-older-than-the-reviewed-fork-point-is-behind-base
  (let [{:keys [dir]} (a-checkout)
        target (str (fs/create-temp-dir {:prefix "nido-provenance-target"}))]
    (try
      (fs/delete-tree target)
      (jj/jj! dir "workspace" "add" "--name" "target" "-r" "main" target)
      (let [m (#'provenance/stamp (str (fs/path dir "src")) target)]
        (is (true? (:behind-base? m))
            "machinery older than the branch's base judges newer code by older rules")
        (is (false? (:self-review? m)))
        (is (nil? (:own-copy? m))))
      (finally (fs/delete-tree dir) (fs/delete-tree target)))))
