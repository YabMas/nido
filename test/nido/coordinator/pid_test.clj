(ns nido.coordinator.pid-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.pid :as pid]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest read-nil-when-absent
  (with-tmp (fn [] (is (nil? (pid/read))))))

(deftest write-then-read-round-trips
  (with-tmp
    (fn []
      (pid/write! 12345)
      (is (= 12345 (pid/read))))))

(deftest delete!-is-idempotent
  (with-tmp
    (fn []
      (pid/write! 999)
      (pid/delete!)
      (is (nil? (pid/read)))
      ;; Second delete should be a no-op, not an error.
      (pid/delete!)
      (is (nil? (pid/read))))))

(deftest alive?-false-when-no-pid-file
  (with-tmp (fn [] (is (false? (pid/alive?))))))

(deftest alive?-true-for-this-process
  (with-tmp
    (fn []
      ;; Our own JVM process is definitely alive.
      (pid/write! (long (.pid (java.lang.ProcessHandle/current))))
      (is (true? (pid/alive?))))))

(deftest alive?-false-for-stale-pid
  (with-tmp
    (fn []
      ;; PID 1 is init/launchd — exists but isn't *this* coordinator.
      ;; Use a definitely-dead PID instead: 2^31-1 is well past any real pid.
      (pid/write! 2147483647)
      (is (false? (pid/alive?))))))
