(ns nido.coordinator.work-drains-test
  "A drain's acts from the drains page and the gate inbox, and the positions its records place it
   at."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.source.drain :as source-drain]
   [nido.coordinator.source.notion-cache :as notion-cache]
   [nido.coordinator.view.drains :as view]
   [nido.coordinator.work :as work]
   [nido.platform.core :as core]
   [nido.platform.project :as project]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(def ^:private a-recipe
  {:steps ["s"] :ready-gates ["g"] :standing-orders [] :counter {:command "bb countdown"}})

(deftest the-page-start-refuses-what-lane-drain-refuses
  (with-tmp
    (fn []
      (is (:refused (work/start-drain! "brian" {:exit "" :established-by-ws "x"})) "an exit is named")
      (is (:refused (work/start-drain! "brian" {:exit "x"})) "what established the pattern is named")
      (is (re-find #"holds no workstream" (:refused (work/start-drain! "brian" {:exit "x" :established-by-ws "nope"}))))
      (let [base (drain/start-drain! :brian {:established-by {:commit "abcdef1"} :exit "x"})
            {:keys [ok]} (work/start-drain! "brian" {:exit "y" :established-by-ws base})]
        (is (string? ok) "a held workstream starts the drain")
        (is (contains? (set (map :ws-id (work/drains "brian"))) ok))))))

(deftest marking-a-slice-done-is-no-landing
  (with-tmp
    (fn []
      (let [d (drain/start-drain! :brian {:established-by {:commit "abcdef1"} :exit "x"})
            v (drain/approve-recipe! :brian d (drain/propose-recipe! :brian d {:reason "r" :recipe a-recipe}))
            c (drain/record-census! :brian d {:recipe v :remaining 3 :groups []})
            s (drain/start-slice! :brian d (drain/plan-slice! :brian d {:sites ["a"] :rests-on [] :census c}) :nido)]
        (is (= :refused (:decision (work/set-stage! :brian s :done))) "a slice is done only by landing")
        (is (not (source-drain/landed? :brian s)))
        (is (= :refused (:decision (work/set-stage! :brian d :done)))
            "a drain whose exit is not observed is not closed done")))))


(deftest a-pending-proposal-places-the-drain-whatever-lands-above-it
  (with-tmp
    (fn []
      (let [d  (drain/start-drain! :brian {:established-by {:commit "abcdef1"} :exit "x"})
            v  (drain/approve-recipe! :brian d (drain/propose-recipe! :brian d {:reason "r" :recipe a-recipe}))
            p2 (drain/propose-recipe! :brian d {:reason "again" :recipe a-recipe})
            _  (drain/record-census! :brian d {:recipe v :remaining 3 :groups []})
            pos (pipeline/of :brian d)]
        (is (= :recipe-proposed (:at pos)))
        (is (= p2 (:placed-by pos)) "the proposal pending approval placed it, not the census above it")
        (is (= {:decision :done} (work/resolve-gate! :brian d :approve-recipe (:placed-by pos)))
            "and approving the record it names is accepted")))))
