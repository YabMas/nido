(ns nido.coordinator.core-test
  "Integration smoke test: envelope → executor → run-blocking! → terminal state."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is use-fixtures]]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.core :as core]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]))

(defn- reset-executor! [f]
  (executor/configure! {:global-cap 1})
  (executor/clear!)
  (f))

(use-fixtures :each reset-executor!)

(deftest envelope-drives-run-to-terminal-via-executor
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    agent/launch!    (fn [_]
                                       {:exit-code         0
                                        :claude-session-id "sess-x"
                                        :timed-out?        false})
                    runs/spawn-session-for-run! (fn [_] nil)]
        (cstate/ensure-dirs!)
        (let [trigger  {:name    :t
                        :source  {:type :test}
                        :skill   :noop
                        :payload "x"}
              run      (runs/create-run!
                         {:project :p :trigger trigger :payload {} :priority 0}
                         {})]
          ;; submit directly to the executor (bypassing process-envelope!)
          (executor/submit! (:id run) 0)
          ;; first tick: promotes the Run into a future that calls run-blocking!
          (executor/tick! #'nido.coordinator.core/run-blocking!)
          ;; wait for the future to finish (agent stub is instant)
          (Thread/sleep 200)
          ;; second tick: reaps the finished future
          (executor/tick! #'nido.coordinator.core/run-blocking!)
          (is (contains? #{:done :failed :awaiting-review}
                         (:state (runs/read-run (:id run)))))))
      (finally (fs/delete-tree tmp)))))
