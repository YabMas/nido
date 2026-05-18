(ns nido.session.engine-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.core :as core]
   [nido.session.engine :as engine]))

(def fake-services
  [{:type :postgresql :name :pg}
   {:type :process    :name :repl}
   {:type :eval       :name :app}])

(deftest filter-services-by-profile-allowlist
  (testing ":all returns every service"
    (is (= 3 (count (engine/filter-services fake-services :all)))))
  (testing "empty allowlist returns nothing"
    (is (= 0 (count (engine/filter-services fake-services [])))))
  (testing "specific allowlist returns matching :type"
    (is (= [:postgresql]
           (mapv :type (engine/filter-services fake-services [:postgresql])))))
  (testing "specific allowlist with multiple matches"
    (is (= [:postgresql :process]
           (mapv :type (engine/filter-services fake-services [:postgresql :process]))))))

(deftest profile-persists-across-read
  ;; resolve-instance-id falls back to the leaf path component when the path
  ;; isn't in project config; the leaf "test-session" becomes the instance-id.
  ;; With core/nido-home mocked, state/instance-state-dir("test-session") resolves
  ;; to tmp/state/test-session/ — no config required.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))]
        (let [wt (str (fs/path tmp "worktrees" "test-session"))
              _  (fs/create-dirs wt)
              p  {:services [] :worktree {:strategy :symlink :target "/tmp/x"}}]
          (engine/write-profile-for-session! wt p)
          (is (= p (engine/read-profile-for-session wt)))))
      (finally (fs/delete-tree tmp)))))

(deftest read-profile-returns-nil-when-absent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))]
        (let [wt (str (fs/path tmp "worktrees" "no-profile-session"))]
          (fs/create-dirs wt)
          (is (nil? (engine/read-profile-for-session wt)))))
      (finally (fs/delete-tree tmp)))))
