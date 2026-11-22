(ns tasks.nido-commit-gate-core-test
  "A person's act run in a turn the core runs is refused unless an unspent grant in that
   workstream's core state admits it; a turn the core does not run is the person's."
  (:require [clojure.test :refer [deftest is testing]]
            [nido.test.fake-core :as fake-core]
            [tasks.nido-commit-gate :as gate]))

(defn- bash [command cwd] {:tool_name "Bash" :tool_input {:command command} :cwd cwd})

(def ^:private land "bb --config /n/bb.edn nido:workstream:pr:land")

(deftest a-persons-act-in-a-core-turn-needs-their-grant
  (fake-core/with-fake-core
    (fn [c]
      (fake-core/seed! c :brian "ws-1" [{:command :open-unit :unit "u" :project "brian" :goal "g"
                                         :session-cwd "/wt/u"}])
      (let [unit-env {"NIDO_CORE_WS" "brian/ws-1" "NIDO_CORE_UNIT" "u"}
            conductor-env {"NIDO_CORE_WS" "brian/ws-1"}]
        (testing "outside any core turn, the person's own session may act"
          (is (nil? (gate/refusal (bash land "/wt/u") {}))))
        (testing "with no grant, a unit's turn may not land"
          (is (re-find #"person's to do" (gate/refusal (bash land "/wt/u") unit-env))))
        (fake-core/seed! c :brian "ws-1" [{:command :give-grant :grant :merge :by "person"}])
        (testing "under the person's :merge grant, the unit's turn lands from its own working copy"
          (is (nil? (gate/refusal (bash land "/wt/u") unit-env)))
          (is (some? (gate/refusal (bash land "/elsewhere") unit-env)) "not from another directory"))
        (testing "the conductor is granted nothing, and a raw merge is never granted"
          (is (some? (gate/refusal (bash land "/wt/u") conductor-env)))
          (is (re-find #"even under their grant" (gate/refusal (bash "gh pr merge 12" "/wt/u") unit-env))))
        (testing "pushing is not a person's act"
          (is (nil? (gate/refusal (bash "jj git push -b feat" "/wt/u") conductor-env))))))))
