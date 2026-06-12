(ns tasks.nido-session-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]
   [tasks.nido-session :as task]))

(deftest up-births-a-loose-workstream-for-the-session
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!          (fn [s o] (swap! calls conj [:up s o]))
                  state/session-home-dir (fn [_ _] "/tmp/home")
                  scratch/birth!         (fn [p s] (swap! calls conj [:birth p s]))]
      (task/up ":project" "brian" "refshot")
      (is (some #(= "refshot" (second %)) (filter #(= :up (first %)) @calls)) "lifecycle up still runs")
      (is (some #(= [:birth :brian "refshot"] %) @calls) "loose workstream born"))))

(deftest destroy-reaps-the-loose-workstream
  (let [calls (atom [])]
    (with-redefs [lifecycle/destroy! (fn [s o] (swap! calls conj [:destroy s o]))
                  scratch/reap!      (fn [p s] (swap! calls conj [:reap p s]))]
      (task/destroy ":project" "brian" "refshot")
      (is (some #(= "refshot" (second %)) (filter #(= :destroy (first %)) @calls)) "lifecycle destroy still runs")
      (is (some #(= [:reap :brian "refshot"] %) @calls) "loose workstream reaped"))))
