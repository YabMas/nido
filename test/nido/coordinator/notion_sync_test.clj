(ns nido.coordinator.notion-sync-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is are testing]]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.notion-sync :as ns-sync]
   [nido.coordinator.sources.state :as sstate]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.notion.client :as notion]))

(def ^:private me "me-id")

(defn- action
  "Convenience: run sync-action with the defaults and a given status/ballholders/stage."
  [status ballholders current-stage]
  (ns-sync/sync-action {:status         status
                        :ballholder-ids (set ballholders)
                        :me             me
                        :terminal       ns-sync/default-terminal
                        :status->stage  ns-sync/default-status->stage
                        :current-stage  current-stage}))

(deftest sync-action-truth-table
  (testing "terminal statuses close :done regardless of ball holder"
    (are [status bh] (= :close-done (action status bh :triage))
      "Done"     []
      "Not Done" [me]
      "Done"     ["someone-else"]))
  (testing "non-terminal, claimed by someone else → drop"
    (is (= :close-dropped (action "In progress" ["someone-else"] :triage)))
    (is (= :close-dropped (action "Review" ["a" "b"] :triage))))
  (testing "non-terminal, mine or unassigned → advance to mapped stage"
    (is (= [:advance :in-progress] (action "In progress" [me] :triage)))
    (is (= [:advance :in-progress] (action "In progress" [] :triage)))
    (is (= [:advance :ready]       (action "Not Started" [] :triage)))
    (is (= [:advance :ready]       (action "On Hold" [] :in-progress)))
    (is (= [:advance :done]        (action "Review" [me] :in-progress))))
  (testing "idempotent: mapped stage already current → no-op"
    (is (= :noop (action "In progress" [] :in-progress)))
    (is (= :noop (action "Needs verification" [] :triage))))
  (testing "unknown / missing status, mine or unassigned → no-op"
    (is (= :noop (action "Some Weird Status" [] :triage)))
    (is (= :noop (action nil [] :triage))))
  (testing "unknown status but claimed by other still drops"
    (is (= :close-dropped (action "Some Weird Status" ["other"] :triage)))))
