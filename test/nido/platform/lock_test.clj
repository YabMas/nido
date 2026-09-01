(ns nido.platform.lock-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [nido.platform.lock :as lock]))

(def ^:private tmp (atom nil))

(use-fixtures :each
  (fn [f]
    (let [d (fs/create-temp-dir {:prefix "nido-lock-test"})]
      (reset! tmp d)
      (with-redefs [lock/locks-dir (constantly (fs/path d "locks"))]
        (f))
      (fs/delete-tree d))))

(defn- dead-pid
  "A pid that is genuinely gone: run a process to completion and reuse its pid.
   A made-up number can collide with a live process and make a test lie."
  []
  (let [p (babashka.process/process {:out :string} "true")]
    @p
    (.pid ^java.lang.Process (:proc p))))

(deftest unheld-lock-has-no-holder
  (is (nil? (lock/holder "ci"))))

(deftest acquire-then-holder-names-this-process
  (is (true? (lock/acquire! "ci" "brian/feat-x" {})))
  (is (= (.pid (java.lang.ProcessHandle/current)) (:pid (lock/holder "ci"))))
  (is (= "brian/feat-x" (:label (lock/holder "ci"))))
  (lock/release! "ci"))

(deftest a-claim-is-never-visible-without-its-owner
  (testing "the instant the lock exists, it already says who holds it"
    ;; Creating the lock and recording the owner have to be ONE step. Were they
    ;; two, a caller arriving between them would see a lock with no owner, read
    ;; it as debris from a killed run, delete it, and claim — leaving two
    ;; processes both believing they hold it.
    (is (true? (lock/acquire! "ci" "mine" {})))
    (let [f (fs/file (fs/path (lock/locks-dir) "ci.lock"))
          owner (edn/read-string (slurp f))]
      (is (fs/exists? f))
      (is (= (.pid (java.lang.ProcessHandle/current)) (:pid owner))
          "the file must carry its owner from the moment it exists")
      (is (some? (:label owner)))
      (is (some? (:since owner))))
    (lock/release! "ci")))

(deftest a-lock-held-by-a-dead-process-reads-as-free
  (testing "debris from a killed run is not a lock"
    (lock/acquire! "ci" "mine" {})
    (spit (fs/file (fs/path (lock/locks-dir) "ci.lock"))
          (pr-str {:pid (dead-pid) :label "killed-run" :since "2020-01-01T00:00:00Z"}))
    (is (nil? (lock/holder "ci")))
    (is (true? (lock/acquire! "ci" "next" {})))
    (is (= "next" (:label (lock/holder "ci"))))
    (lock/release! "ci")))

(deftest an-unparseable-lock-does-not-wedge-the-machine
  (testing "a half-written or corrupted lock reads as free rather than held"
    (fs/create-dirs (lock/locks-dir))
    (spit (fs/file (fs/path (lock/locks-dir) "ci.lock")) "{:pid ")
    (is (nil? (lock/holder "ci")))
    (is (true? (lock/acquire! "ci" "recovered" {})))
    (lock/release! "ci")))

(deftest the-same-process-may-re-enter
  (testing "a command composed by another command must not deadlock on its own lock"
    (let [heard (atom nil)]
      (is (true? (lock/acquire! "ci" "outer" {})))
      (is (true? (lock/acquire! "ci" "inner" {:wait-ms 0 :on-wait #(reset! heard %)})))
      (is (nil? @heard) "no wait happens when the lock is already ours")
      (lock/release! "ci"))))

(deftest with-lock-releases-even-when-the-body-throws
  (is (thrown? Exception
               (lock/with-lock* "ci" "boom" {} (fn [] (throw (ex-info "boom" {}))))))
  (is (nil? (lock/holder "ci")) "the lock must not survive a throwing body"))
