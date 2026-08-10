(ns tasks.nido-session-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]
   [tasks.nido-session :as task]))

(deftest up-births-a-loose-workstream-for-the-session
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!            (fn [s o] (swap! calls conj [:up s o]))
                  state/session-home-dir   (fn [_ _] "/tmp/home")
                  lifecycle/session-weight (fn [s _] (swap! calls conj [:weight s]) :heavy)
                  scratch/birth!           (fn [p s w] (swap! calls conj [:birth p s w]))]
      (task/up ":project" "brian" "refshot")
      (is (some #(= "refshot" (second %)) (filter #(= :up (first %)) @calls)) "lifecycle up still runs")
      (is (some #(= [:birth :brian "refshot" :heavy] %) @calls)
          "loose workstream born carrying the provisioned weight")
      (is (< (.indexOf @calls [:up "refshot" {:project "brian"}])
             (.indexOf @calls [:weight "refshot"]))
          "weight is read AFTER up! — up! is what persists the profile it reads"))))

(deftest destroy-reaps-the-loose-workstream
  (let [calls (atom [])]
    (with-redefs [lifecycle/destroy! (fn [s o] (swap! calls conj [:destroy s o]))
                  scratch/reap!      (fn [p s] (swap! calls conj [:reap p s]))]
      (task/destroy ":project" "brian" "refshot")
      (is (some #(= "refshot" (second %)) (filter #(= :destroy (first %)) @calls)) "lifecycle destroy still runs")
      (is (some #(= [:reap :brian "refshot"] %) @calls) "loose workstream reaped"))))
