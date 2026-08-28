;; test/nido/platform/process_child_test.clj
(ns nido.platform.process-child-test
  "Children this process started, and stopping them when it stops.

   nido disables babashka.process's destroy-tree hook globally to cure a macOS
   shutdown hang, and the cost is orphans: a killed review loop left its judge
   running, billing, and writing an answer nothing would read."
  (:require
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.process :as nprocess]))

(defn- sleeper []
  (:proc (p/process {:out :inherit} "sleep" "120")))

(deftest a-registered-child-is-stopped
  (let [proc (sleeper)]
    (future (nprocess/with-child-registered proc #(Thread/sleep 10000)))
    (Thread/sleep 300)
    (is (.isAlive proc) "still running while the work is in flight")
    (is (pos? (nprocess/stop-live-children!)))
    (Thread/sleep 300)
    (is (not (.isAlive proc)) "and stopped when this process would stop")))

(deftest a-finished-child-is-deregistered
  ;; The registry must not grow across a long run, and a reaper must not sit
  ;; waiting on processes that ended rounds ago.
  (let [proc (:proc (p/process {:out :inherit} "true"))]
    (nprocess/with-child-registered proc #(Thread/sleep 200))
    (is (zero? (nprocess/stop-live-children!)))))

(deftest a-child-is-deregistered-even-when-the-work-throws
  (let [proc (sleeper)]
    (is (thrown? Exception
                 (nprocess/with-child-registered proc #(throw (ex-info "boom" {})))))
    (is (zero? (nprocess/stop-live-children!))
        "a round that blew up must not leave its agent in the registry")
    (.destroy proc)))

(deftest the-helper-returns-what-the-work-returned
  (let [proc (:proc (p/process {:out :inherit} "true"))]
    (is (= :answer (nprocess/with-child-registered proc (constantly :answer))))))
