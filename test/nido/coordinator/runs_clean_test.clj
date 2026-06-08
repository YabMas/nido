(ns nido.coordinator.runs-clean-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.runs-clean :as clean]
   [nido.coordinator.state :as cstate]
   [nido.session.state :as sstate]))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(defn- with-tmp
  "Run `f` with a temporary directory as the nido-root. Cleans up afterwards."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    sstate/state-dir  (fn [] (str (fs/path tmp "state")))]
        (cstate/ensure-dirs!)
        (fs/create-dirs (str (fs/path tmp "state")))
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- write-test-run!
  "Write a minimal valid Run record for testing. Only overridden keys matter."
  [{:keys [id state project at session-name]
    :or   {project      :p
           at           "2026-05-15T00:00:00Z"
           session-name nil}}]
  (let [sname (or session-name (str "sess-" id))]
    (fs/create-dirs (cstate/run-dir id))
    (let [run {:id              id
               :project         project
               :trigger         :t
               :source          {:type :test}
               :event-payload   {}
               :skill           :noop
               :first-message   "x"
               :agent           :claude
               :session-name    sname
               :claude-session-id nil
               :limits          {:budget "10m" :max-failures 3}
               :priority        0
               :session-profile :full
               :uncapped?       false
               :state           state
               :state-history   [{:at at :state :queued}
                                 {:at at :state state}]
               :artifacts       []
               :error           nil}]
      (runs/write-run! run))))

;; ---------------------------------------------------------------------------
;; State filter
;; ---------------------------------------------------------------------------

(deftest plan-clean-respects-explicit-state-filter
  (with-tmp
    (fn [_]
      (write-test-run! {:id "r1" :state :done})
      (write-test-run! {:id "r2" :state :failed})
      (write-test-run! {:id "r3" :state :running})
      (let [plan (clean/plan-clean {:state #{:done}})]
        (is (= 1 (count plan)))
        (is (= "r1" (-> plan first :run :id)))))))

(deftest plan-clean-default-uses-safe-allowlist
  (with-tmp
    (fn [_]
      (write-test-run! {:id "r1" :state :done})
      (write-test-run! {:id "r2" :state :running})
      (write-test-run! {:id "r3" :state :failed})
      (write-test-run! {:id "r4" :state :dry-run-would-fire})
      (write-test-run! {:id "r5" :state :halted})
      (let [plan (clean/plan-clean {})
            ids  (set (map (comp :id :run) plan))]
        ;; running is excluded; others are in the safe allowlist
        (is (contains? ids "r1"))
        (is (contains? ids "r3"))
        (is (contains? ids "r4"))
        (is (contains? ids "r5"))
        (is (not (contains? ids "r2")))))))

(deftest plan-clean-refuses-live-states-in-explicit-filter
  (with-tmp
    (fn [_]
      (testing "running in filter throws"
        (is (thrown? clojure.lang.ExceptionInfo
                     (clean/plan-clean {:state #{:done :running}}))))
      (testing "queued in filter throws"
        (is (thrown? clojure.lang.ExceptionInfo
                     (clean/plan-clean {:state #{:queued}}))))
      (testing "awaiting-review in filter throws"
        (is (thrown? clojure.lang.ExceptionInfo
                     (clean/plan-clean {:state #{:awaiting-review :done}})))))))

(deftest plan-clean-allow-live-overrides-refusal
  (with-tmp
    (fn [_]
      (write-test-run! {:id "r1" :state :awaiting-review})
      (write-test-run! {:id "r2" :state :running})
      (testing "live state planned (not thrown) when :allow-live? true"
        (let [plan (clean/plan-clean {:state #{:awaiting-review} :allow-live? true})]
          (is (= 1 (count plan)))
          (is (= "r1" (-> plan first :run :id)))))
      (testing "still refuses without the override"
        (is (thrown? clojure.lang.ExceptionInfo
                     (clean/plan-clean {:state #{:running}})))))))

;; ---------------------------------------------------------------------------
;; Project filter
;; ---------------------------------------------------------------------------

(deftest plan-clean-project-filter
  (with-tmp
    (fn [_]
      (write-test-run! {:id "r1" :state :done :project :alpha})
      (write-test-run! {:id "r2" :state :done :project :beta})
      (let [plan (clean/plan-clean {:project :alpha})
            ids  (map (comp :id :run) plan)]
        (is (= ["r1"] ids))))))

;; ---------------------------------------------------------------------------
;; Age filter
;; ---------------------------------------------------------------------------

(deftest plan-clean-older-than-filter
  (with-tmp
    (fn [_]
      (write-test-run! {:id "old" :state :done :at "2024-01-01T00:00:00Z"})
      (write-test-run! {:id "new" :state :done :at "2099-12-31T00:00:00Z"})
      (let [plan (clean/plan-clean {:older-than "7d"})
            ids  (map (comp :id :run) plan)]
        (is (= ["old"] ids))))))

(deftest parse-duration-ms-handles-all-units
  (is (= (* 7 86400000) (clean/parse-duration-ms "7d")))
  (is (= (* 12 3600000) (clean/parse-duration-ms "12h")))
  (is (= (* 30 60000)   (clean/parse-duration-ms "30m")))
  (is (nil?             (clean/parse-duration-ms nil))))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(deftest execute-deletes-the-run-dir
  (with-tmp
    (fn [_]
      (write-test-run! {:id "r1" :state :failed})
      (is (fs/exists? (cstate/run-dir "r1")))
      (let [plan (clean/plan-clean {})]
        (clean/execute! plan))
      (is (not (fs/exists? (cstate/run-dir "r1")))))))

(deftest execute-tolerates-missing-paths
  (with-tmp
    (fn [_]
      (write-test-run! {:id "r1" :state :failed})
      (let [plan (clean/plan-clean {})]
        (clean/execute! plan)
        ;; Second call: all paths gone — must not throw
        (clean/execute! plan)
        (is (not (fs/exists? (cstate/run-dir "r1"))))))))

(deftest execute-also-removes-session-home
  (with-tmp
    (fn [_tmp]
      (write-test-run! {:id "r1" :state :done :project :brian :session-name "sess-r1"})
      ;; Manually create a fake session-home to verify it gets removed
      (let [home (sstate/session-home-dir "brian" "sess-r1")]
        (fs/create-dirs home)
        (is (fs/exists? home))
        (let [plan (clean/plan-clean {})]
          (clean/execute! plan))
        (is (not (fs/exists? home)))))))

;; ---------------------------------------------------------------------------
;; Empty / edge cases
;; ---------------------------------------------------------------------------

(deftest plan-clean-empty-when-no-runs-match
  (with-tmp
    (fn [_]
      (write-test-run! {:id "r1" :state :running})
      (let [plan (clean/plan-clean {})]
        (is (empty? plan))))))

(deftest plan-clean-empty-when-no-runs-dir
  ;; with-tmp creates the runs dir but let's just verify it handles zero runs
  (with-tmp
    (fn [_]
      (let [plan (clean/plan-clean {})]
        (is (empty? plan))))))
