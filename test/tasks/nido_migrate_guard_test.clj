(ns tasks.nido-migrate-guard-test
  (:require [clojure.test :refer [deftest is]]
            [nido.coordinator.lane.migrate]
            [tasks.nido-migrate :as m]))

(deftest refuses-without-override
  (let [called (atom false)]
    (with-redefs [nido.coordinator.lane.migrate/run-once!
                  (fn [_] (reset! called true) {:workstreams 0 :sessions 0})]
      (is (thrown-with-msg? Exception #"degrade" (m/migrate-cmd ":project" "brian")))
      (is (false? @called) "run-once! must not run without the override"))))

(deftest runs-with-override
  (let [called (atom false)]
    (with-redefs [nido.coordinator.lane.migrate/run-once!
                  (fn [_] (reset! called true) {:workstreams 1 :sessions 2})]
      (m/migrate-cmd ":project" "brian"
                     ":i-understand-this-degrades-the-spine" "true")
      (is (true? @called) "the override runs the migration"))))
