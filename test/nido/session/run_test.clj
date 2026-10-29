(ns nido.session.run-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.config :as config]
   [nido.platform.core]
   [nido.session.run :as run]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]))

(deftest session-context-shape
  (is (= {:project {:name "brian" :dir "/x"}
          :session {:name "feat" :worktree "/x/.worktrees/feat"}}
         (run/session-context "brian" "/x" "feat" "/x/.worktrees/feat"))))

(deftest runs-command-in-worktree
  (let [tmp  (fs/create-temp-dir)
        pdir (str (fs/path tmp "proj"))
        wt   (str (fs/path tmp "wt" "feat"))]
    (fs/create-dirs pdir)
    (fs/create-dirs wt)
    (try
      (with-redefs [config/read-projects   (constantly {"p" {:directory pdir}})
                    lifecycle/worktree-path (constantly wt)
                    engine/load-session-edn (constantly
                                             {:project-commands
                                              {:marker {:cwd "{{session.worktree}}"
                                                        :cmd "touch ran-here.txt"}}})]
        (let [result (run/run-command-in-session! "p" "feat" :marker)]
          (testing "command exits zero"
            (is (zero? (:exit result))))
          (testing "command ran in the worktree (cwd = {{session.worktree}})"
            (is (fs/exists? (fs/path wt "ran-here.txt"))))))
      (finally (fs/delete-tree tmp)))))

(deftest unknown-ref-throws
  (let [tmp (fs/create-temp-dir)
        wt  (str (fs/path tmp "wt" "feat"))]
    (fs/create-dirs wt)
    (try
      (with-redefs [config/read-projects    (constantly {"p" {:directory (str tmp)}})
                    lifecycle/worktree-path  (constantly wt)
                    engine/load-session-edn  (constantly {:project-commands {:ci {:cmd "true"}}})]
        (is (thrown-with-msg? Exception #"Unknown project-command"
              (run/run-command-in-session! "p" "feat" :nope))))
      (finally (fs/delete-tree tmp)))))

(deftest missing-worktree-throws
  (let [tmp (fs/create-temp-dir)
        wt  (str (fs/path tmp "does-not-exist"))]
    (try
      (with-redefs [config/read-projects    (constantly {"p" {:directory (str tmp)}})
                    lifecycle/worktree-path  (constantly wt)
                    engine/load-session-edn  (constantly {:project-commands {}})]
        (is (thrown-with-msg? Exception #"Worktree not found"
              (run/run-command-in-session! "p" "feat" :ci))))
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; :checkout :committed
;; ---------------------------------------------------------------------------

(defn- sh [dir & args]
  (apply babashka.process/shell {:dir (str dir) :out :string :err :string} args))

(defn- with-nested-workspace
  "Call (f src worktree) with a colocated jj repo `src` holding committed
   `f.txt`, a jj workspace of it nested at src/.worktrees/feat — the shape of a
   nido session worktree, where bare git binds to `src` — and the nido home in
   the same temp dir."
  [f]
  (let [tmp (fs/canonicalize (fs/create-temp-dir))
        src (fs/path tmp "src")
        wt  (str (fs/path src ".worktrees" "feat"))]
    (try
      (fs/create-dirs src)
      (sh src "jj" "git" "init" "--colocate")
      (spit (str (fs/path src ".gitignore")) ".worktrees/\n")
      (spit (str (fs/path src "f.txt")) "base\n")
      (sh src "jj" "commit" "-m" "base")
      (fs/create-dirs (fs/parent wt))
      (sh src "jj" "workspace" "add" "--name" "feat" wt)
      (with-redefs [nido.platform.core/nido-home (constantly (str (fs/path tmp "home")))]
        (f (str src) wt))
      (finally (fs/delete-tree tmp)))))

(deftest a-committed-checkout-is-a-git-checkout-of-its-own
  (with-nested-workspace
    (fn [src wt]
      (spit (str (fs/path wt "g.txt")) "feat\n")
      (sh wt "jj" "commit" "-m" "feat")
      (testing "bare git in the workspace binds to the source repo"
        (is (= src (str/trim (:out (sh wt "git" "rev-parse" "--show-toplevel"))))))
      (let [seen (atom nil)
            res  (run/with-committed-checkout* "p" "feat" wt []
                   (fn [dir]
                     (reset! seen {:dir      dir
                                   :toplevel (str/trim (:out (sh dir "git" "rev-parse" "--show-toplevel")))
                                   :files    (set (str/split-lines (:out (sh dir "git" "ls-files"))))})
                     :ran))]
        (is (= :ran res))
        (testing "git's toplevel there is the checkout itself"
          (is (= (str (fs/canonicalize (:dir @seen))) (:toplevel @seen))))
        (testing "it holds the session's committed files"
          (is (= #{".gitignore" "f.txt" "g.txt"} (:files @seen))))
        (testing "named after the worktree, so names derived from it do not change"
          (is (= "feat" (str (fs/file-name (:dir @seen))))))
        (testing "it is gone afterwards, from disk and from git's worktree list"
          (is (not (fs/exists? (:dir @seen))))
          (is (not (str/includes? (:out (sh src "git" "worktree" "list")) (:dir @seen)))))))))

(deftest a-committed-checkout-is-removed-when-the-command-throws
  (with-nested-workspace
    (fn [src wt]
      (let [seen (atom nil)]
        (is (thrown? Exception
              (run/with-committed-checkout* "p" "feat" wt []
                (fn [dir] (reset! seen dir) (throw (ex-info "boom" {}))))))
        (is (not (fs/exists? @seen)))
        (is (= 1 (count (str/split-lines (:out (sh src "git" "worktree" "list"))))))))))

(deftest a-dirty-working-copy-is-refused
  (with-nested-workspace
    (fn [_ wt]
      (spit (str (fs/path wt "uncommitted.txt")) "x\n")
      (let [called (atom false)
            res    (binding [*err* (java.io.StringWriter.)]
                     (run/with-committed-checkout* "p" "feat" wt []
                       (fn [_] (reset! called true))))]
        (is (= {:exit 1} res))
        (is (false? @called))))))

(deftest run-command-binds-the-checkout-for-a-committed-command
  (with-nested-workspace
    (fn [src wt]
      (with-redefs [config/read-projects    (constantly {"p" {:directory src}})
                    lifecycle/worktree-path (constantly wt)
                    engine/load-session-edn (constantly
                                             {:project-commands
                                              {:probe {:checkout :committed
                                                       :cwd "{{session.checkout}}"
                                                       :cmd "test \"$(git rev-parse --show-toplevel)\" = \"$(pwd -P)\" && test -f f.txt && test \"{{session.worktree}}\" != \"$(pwd)\""}}})]
        (is (zero? (:exit (run/run-command-in-session! "p" "feat" :probe))))))))

(deftest kept-outputs-reach-the-worktree
  (with-nested-workspace
    (fn [_ wt]
      (spit (str (fs/path wt ".gitignore")) ".worktrees/\nresults/\n")
      (sh wt "jj" "commit" "-m" "ignore results")
      (fs/create-dirs (fs/path wt "results"))
      (spit (str (fs/path wt "results" "stale.txt")) "old\n")
      (run/with-committed-checkout* "p" "feat" wt ["results" "never-written"]
        (fn [dir]
          (fs/create-dirs (fs/path dir "results" "shard-1"))
          (spit (str (fs/path dir "results" "shard-1" "junit.xml")) "<ok/>")))
      (is (= "<ok/>" (slurp (str (fs/path wt "results" "shard-1" "junit.xml")))))
      (is (not (fs/exists? (fs/path wt "results" "stale.txt"))) "replaced, not merged")
      (is (not (fs/exists? (fs/path wt "never-written"))))
      (is (= "" (str/trim (:out (sh wt "jj" "diff" "-s")))) "ignored, so the next run is not gated"))))
