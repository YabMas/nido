(ns nido.coordinator.breakers-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest read-all-empty-when-absent
  (with-tmp (fn [] (is (= {} (breakers/read-all))))))

(deftest record-failure!-increments-and-trips-at-3
  (with-tmp
    (fn []
      (breakers/record-failure! :brian :investigate-bug 3)
      (is (= 1 (breakers/consecutive-failures :brian :investigate-bug)))
      (is (false? (breakers/tripped? :brian :investigate-bug)))
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (is (= 3 (breakers/consecutive-failures :brian :investigate-bug)))
      (is (true? (breakers/tripped? :brian :investigate-bug))))))

(deftest record-success!-resets-counter
  (with-tmp
    (fn []
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-success! :brian :investigate-bug)
      (is (= 0 (breakers/consecutive-failures :brian :investigate-bug)))
      (is (false? (breakers/tripped? :brian :investigate-bug))))))

(deftest user-disable-takes-priority
  (with-tmp
    (fn []
      (breakers/disable-by-user! :brian :investigate-bug "manually testing")
      (is (true? (breakers/tripped? :brian :investigate-bug)))
      ;; Even a success doesn't auto-re-enable a user-disabled trigger.
      (breakers/record-success! :brian :investigate-bug)
      (is (true? (breakers/tripped? :brian :investigate-bug))))))

(deftest enable!-clears-everything
  (with-tmp
    (fn []
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/disable-by-user! :brian :other "")
      (breakers/enable! :brian :investigate-bug)
      (is (false? (breakers/tripped? :brian :investigate-bug)))
      (is (true? (breakers/tripped? :brian :other))
          "enabling one trigger should not touch others"))))

(deftest tripped-triggers-summary
  (with-tmp
    (fn []
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/disable-by-user! :fukan :other "off")
      (let [t (breakers/tripped-triggers)]
        (is (= 2 (count t)))
        (is (every? #(contains? % :project) t))
        (is (every? #(contains? % :trigger) t))))))
