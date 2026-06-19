(ns nido.coordinator.migrate-dispositions-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.migrate-dispositions :as md]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest run!-migrates-skipped-to-dismissed
  (with-tmp
    (fn [_]
      (tickets/set-status! :brian "BR-OLD" :skipped)
      (is (= 1 (md/run-project! :brian)))
      (is (= :dismissed (tickets/status :brian "BR-OLD"))))))

(deftest run!-leaves-other-statuses-untouched-and-is-idempotent
  (with-tmp
    (fn [_]
      (tickets/set-status! :brian "BR-T" :triaged)
      (tickets/set-status! :brian "BR-S" :skipped)
      (is (= 1 (md/run-project! :brian)))
      (is (= 0 (md/run-project! :brian)) "second run is a no-op")
      (is (= :triaged   (tickets/status :brian "BR-T")))
      (is (= :dismissed (tickets/status :brian "BR-S"))))))

(deftest run-all!-spans-projects
  (with-tmp
    (fn [_]
      (tickets/set-status! :brian "BR-1" :skipped)
      (tickets/set-status! :other "BR-2" :skipped)
      (is (= 2 (md/run-all!)))
      (is (= :dismissed (tickets/status :brian "BR-1")))
      (is (= :dismissed (tickets/status :other "BR-2"))))))
