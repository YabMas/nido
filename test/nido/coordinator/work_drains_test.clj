(ns nido.coordinator.work-drains-test
  "A drain reaches the gate inbox as one row: a slice that owes a person a move is never a card of
   its own, and a drain that owes one is."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.drive :as drive]
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

(deftest a-drain-is-one-row-in-the-inbox
  (with-tmp
    (fn []
      (let [d (drain/start-drain! :brian {:established-by {:commit "abcdef1"} :exit "x"})
            v (drain/approve-recipe! :brian d (drain/propose-recipe! :brian d {:reason "r" :recipe a-recipe}))
            c (drain/record-census! :brian d {:recipe v :remaining 3 :groups []})
            s (drain/start-slice! :brian d (drain/plan-slice! :brian d {:sites ["a"] :rests-on [] :census c}) :nido)]
        (drain/clear-slice! :brian d s [])
        (is (not-any? #(= s (:ws-id %)) (work/gates :brian)) "a cleared slice owes the person its landing, on the drains page")
        (drain/propose-recipe! :brian d {:reason "amend" :recipe a-recipe})
        (is (= [d] (map :ws-id (work/gates :brian))) "the drain owes the person the approval: one row")))))

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

(deftest a-slices-own-escalation-reaches-the-inbox-through-its-drain
  ;; A failed reseat parks a blocker on the slice's own ledger; no hand-back carries it to the
  ;; drain, so the drain's row is the only place it can reach the person.
  (with-tmp
    (fn []
      (let [d (drain/start-drain! :brian {:established-by {:commit "abcdef1"} :exit "x"})
            v (drain/approve-recipe! :brian d (drain/propose-recipe! :brian d {:reason "r" :recipe a-recipe}))
            c (drain/record-census! :brian d {:recipe v :remaining 3 :groups []})
            s (drain/start-slice! :brian d (drain/plan-slice! :brian d {:sites ["a"] :rests-on [] :census c}) :nido)]
        (drive/park! :brian s {:stage :reseat :outcome :uncarried :summary "the seat cannot start"
                               :needs "fix the seat" :recommendation "r" :after "a"})
        (let [gs (work/gates :brian)]
          (is (= [d] (map :ws-id gs)) "one row, the drain's")
          (is (= [s] (:raised-by (first gs))) "naming the slice that raised it"))
        (is (= [s] (keep :slice (:escalations (view/drain :brian d))))
            "and the drains page lists it among the drain's escalations")))))

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

(deftest a-held-inbox-shows-a-slices-escalation-on-its-drains-row
  ;; The dashboard and the TUI hold the model, and `gates` then answers from it: the held inbox
  ;; must read a slice's escalation onto its drain exactly as an unheld read does.
  (with-tmp
    (fn []
      (with-redefs [project/list-projects           (constantly {:brian {}})
                    work/live-session-names         (fn [_] #{})
                    notion-cache/project-page-facts (constantly {})]
        (let [d (drain/start-drain! :brian {:established-by {:commit "abcdef1"} :exit "x"})
              v (drain/approve-recipe! :brian d (drain/propose-recipe! :brian d {:reason "r" :recipe a-recipe}))
              c (drain/record-census! :brian d {:recipe v :remaining 3 :groups []})
              s (drain/start-slice! :brian d (drain/plan-slice! :brian d {:sites ["a"] :rests-on [] :census c}) :nido)]
          (drive/park! :brian s {:stage :reseat :outcome :uncarried :summary "the seat cannot start"
                                 :needs "fix the seat" :recommendation "r" :after "a"})
          (try
            (work/hold! {:sweep? false})
            (is (work/holding?))
            (let [gs (work/gates :brian)]
              (is (= [d] (map :ws-id gs)) "one row, the drain's")
              (is (= [s] (:raised-by (first gs))) "naming the slice that raised it"))
            (finally (work/release!))))))))

(deftest a-paused-drain-is-not-raised-by-its-slices
  ;; A pause on the drain is the person's choice not to be asked about it — its slices'
  ;; escalations included — held or unheld.
  (with-tmp
    (fn []
      (with-redefs [project/list-projects           (constantly {:brian {}})
                    work/live-session-names         (fn [_] #{})
                    notion-cache/project-page-facts (constantly {})]
        (let [d (drain/start-drain! :brian {:established-by {:commit "abcdef1"} :exit "x"})
              v (drain/approve-recipe! :brian d (drain/propose-recipe! :brian d {:reason "r" :recipe a-recipe}))
              c (drain/record-census! :brian d {:recipe v :remaining 3 :groups []})
              s (drain/start-slice! :brian d (drain/plan-slice! :brian d {:sites ["a"] :rests-on [] :census c}) :nido)]
          (drive/park! :brian s {:stage :reseat :outcome :uncarried :summary "the seat cannot start"
                                 :needs "fix the seat" :recommendation "r" :after "a"})
          (work/pause! :brian d nil)
          (is (empty? (work/gates :brian)) "unheld: the paused drain is not listed")
          (try
            (work/hold! {:sweep? false})
            (is (empty? (work/gates :brian)) "held: nor here")
            (finally (work/release!)))
          (work/resume! :brian d)
          (is (= [d] (map :ws-id (work/gates :brian))) "resumed, its slice raises it again"))))))
