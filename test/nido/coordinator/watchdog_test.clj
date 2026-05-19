(ns nido.coordinator.watchdog-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.watchdog :as wd]))

(def ^:private denylist [".git" ".jj" "target" "node_modules"])

(defn- touch! [path]
  (fs/create-dirs (fs/parent path))
  (spit (str path) ""))

(deftest recent-fs-change?-finds-recent-edits-at-root
  (let [wt (fs/create-temp-dir)]
    (try
      (touch! (fs/path wt "core.clj"))
      (is (true? (wd/recent-fs-change? (str wt) denylist 2)))
      (finally (fs/delete-tree wt)))))

(deftest recent-fs-change?-ignores-denylisted-dirs
  (testing "edits only inside .git → not active"
    (let [wt (fs/create-temp-dir)]
      (try
        (touch! (fs/path wt ".git" "HEAD"))
        (touch! (fs/path wt ".git" "objects" "ab" "deadbeef"))
        (is (false? (wd/recent-fs-change? (str wt) denylist 2)))
        (finally (fs/delete-tree wt)))))
  (testing "edits only inside .jj → not active"
    (let [wt (fs/create-temp-dir)]
      (try
        (touch! (fs/path wt ".jj" "repo" "op_heads"))
        (is (false? (wd/recent-fs-change? (str wt) denylist 2)))
        (finally (fs/delete-tree wt)))))
  (testing "edits inside non-denylisted subdir → active"
    (let [wt (fs/create-temp-dir)]
      (try
        (touch! (fs/path wt ".jj" "repo" "op_heads"))
        (touch! (fs/path wt "src" "main.clj"))
        (is (true? (wd/recent-fs-change? (str wt) denylist 2)))
        (finally (fs/delete-tree wt))))))

(deftest recent-fs-change?-respects-lookback-window
  (let [wt (fs/create-temp-dir)
        old (fs/path wt "old.clj")]
    (try
      (touch! old)
      ;; Backdate by 10 minutes; lookback of 2 should not match.
      (fs/set-last-modified-time
       old (java.nio.file.attribute.FileTime/fromMillis
            (- (System/currentTimeMillis) (* 10 60 1000))))
      (is (false? (wd/recent-fs-change? (str wt) denylist 2)))
      (finally (fs/delete-tree wt)))))

(deftest session-active?-ors-the-three-signals
  (let [base {:app-port nil :nrepl-port nil :worktree nil
              :fs-denylist denylist :fs-lookback-min 2}]
    (testing "no signals → not active"
      (with-redefs [wd/established-connections? (constantly false)
                    wd/recent-fs-change?        (constantly false)]
        (is (not (wd/session-active? base)))))
    (testing "app port traffic → active"
      (with-redefs [wd/established-connections? (fn [p] (= p 3100))
                    wd/recent-fs-change?        (constantly false)]
        (is (wd/session-active? (assoc base :app-port 3100)))))
    (testing "nREPL traffic → active even without app traffic"
      (with-redefs [wd/established-connections? (fn [p] (= p 7888))
                    wd/recent-fs-change?        (constantly false)]
        (is (wd/session-active? (assoc base :app-port 3100 :nrepl-port 7888)))))
    (testing "FS edits → active even without socket traffic"
      (with-redefs [wd/established-connections? (constantly false)
                    wd/recent-fs-change?        (constantly true)]
        (is (wd/session-active? (assoc base :worktree "/tmp/wt")))))))

(deftest established-connections?-nil-port-returns-false
  (is (false? (wd/established-connections? nil))))
