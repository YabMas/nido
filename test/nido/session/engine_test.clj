(ns nido.session.engine-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
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

;; When a service dies during start, the cause lives in the :log-tail that
;; services/process attaches to its ex-data. The rollback handler used to print
;; only (ex-message e) and drop the rest, so a classpath error 400 lines deep in
;; ~/.nido/state/<instance>/logs/repl.log surfaced as a bare one-line failure and
;; the operator had to go find the log by hand.

(deftest start-failure-report-surfaces-the-service-log-tail
  (let [e (ex-info "repl process exited before writing .nrepl-port"
                   {:outcome :process-died
                    :log-path "/tmp/logs/repl.log"
                    :log-tail (str "Error building classpath.\n"
                                   "fatal: could not read Username for 'https://github.com'")})
        report (#'engine/start-failure-report e 2)]
    (is (str/includes? report "repl process exited before writing .nrepl-port")
        "keeps the original failure message")
    (is (str/includes? report "rolling back 2 started service(s)")
        "keeps the rollback count")
    (is (str/includes? report "Error building classpath.")
        "surfaces the log tail that actually explains the failure")
    (is (str/includes? report "/tmp/logs/repl.log")
        "points at the full log for everything the tail cut off")))

(deftest start-failure-report-omits-log-section-when-service-captured-none
  (let [e (ex-info "Timed out waiting for PostgreSQL" {:port 5499})
        report (#'engine/start-failure-report e 1)]
    (is (str/includes? report "Timed out waiting for PostgreSQL"))
    (is (str/includes? report "rolling back 1 started service(s)"))
    (is (not (str/includes? report "last output"))
        "no empty log heading when the service never captured output")))

(deftest start-failure-report-ignores-a-blank-log-tail
  (let [e (ex-info "boom" {:log-tail "   \n  "})
        report (#'engine/start-failure-report e 0)]
    (is (not (str/includes? report "last output"))
        "whitespace-only tail is treated as no tail")))
