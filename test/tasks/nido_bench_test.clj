(ns tasks.nido-bench-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.bench.memory :as bench]
   [nido.coordinator.record.session :as csession]
   [tasks.nido-bench :as nido-bench]))

(deftest a-held-session-is-benchmarked-on-its-workstreams-runtime
  ;; The benchmark stops and starts the session; only nido knows the workstream holding it, so the
  ;; entry point names that workstream's runtime for every start.
  (let [seen (atom nil)]
    (with-redefs [csession/workstream-id-for (fn [p s] (when (= [:brian "b"] [p s]) "ws-1"))
                  bench/run-all!             (fn [_ & kvs] (reset! seen (apply hash-map kvs)))]
      (nido-bench/memory ":project" "brian" ":session" "b")
      (is (= "ws-1" (:runtime-key @seen)))
      (nido-bench/memory ":project" "brian" ":session" "c")
      (is (not (contains? @seen :runtime-key)) "a session no workstream holds keeps its own"))))
