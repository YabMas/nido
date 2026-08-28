(ns nido.run-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.config :as config]
   [nido.run :as run]
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
