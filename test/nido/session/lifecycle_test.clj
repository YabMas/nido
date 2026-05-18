(ns nido.session.lifecycle-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.session.lifecycle :as lifecycle]))

(deftest symlink-worktree-creates-symlink-to-target
  (let [tmp    (fs/create-temp-dir)
        target (str (fs/create-dirs (str (fs/path tmp "fake-checkout"))))
        wt     (str (fs/path tmp "wt-link"))]
    (try
      (lifecycle/create-symlink-worktree! wt target)
      (is (fs/sym-link? wt) "wt-link should be a symlink")
      (is (= (str (fs/real-path target))
             (str (fs/real-path wt)))
          "symlink should resolve to the target dir")
      (finally (fs/delete-tree tmp)))))

(deftest symlink-worktree-refuses-if-target-missing
  (let [tmp (fs/create-temp-dir)
        wt  (str (fs/path tmp "wt-link"))]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (lifecycle/create-symlink-worktree! wt "/no/such/path")))
      (finally (fs/delete-tree tmp)))))

(deftest symlink-worktree-replaces-stale-symlink
  (let [tmp     (fs/create-temp-dir)
        old-tg  (str (fs/create-dirs (str (fs/path tmp "old-target"))))
        new-tg  (str (fs/create-dirs (str (fs/path tmp "new-target"))))
        wt      (str (fs/path tmp "wt-link"))]
    (try
      (lifecycle/create-symlink-worktree! wt old-tg)
      (lifecycle/create-symlink-worktree! wt new-tg)
      (is (= (str (fs/real-path new-tg))
             (str (fs/real-path wt)))
          "second call should replace the stale link, pointing at the new target")
      (finally (fs/delete-tree tmp)))))
