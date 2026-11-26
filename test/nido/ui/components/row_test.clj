(ns nido.ui.components.row-test
  "rows-say-states-through-marks: a row says a workstream's state, the needs-you mark included,
   only by calling nido.ui.components.state."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [nido.libraries.ui.block :as block]
            [nido.ui.components.row :as row]
            [nido.ui.components.state :as state]))

(def ^:private mark-classes
  "The classes the state module's marks are drawn with. A row naming one is writing a mark."
  ["needs" "st-mark" "arc-live" "doing-badge" "pos" "chip"])

(deftest no-row-writes-a-mark-of-its-own
  (let [src (slurp "src/nido/ui/components/row.clj")]
    (doseq [c mark-classes]
      (testing c
        (is (not (re-find (re-pattern (str ":span\\." c "\\b|\"" c "\"")) src))
            (str "a row writes the ." c " mark itself rather than asking nido.ui.components.state"))))
    (is (not (str/includes? src "\"needs you\""))
        "the needs-you mark's words are the state module's")))

(deftest a-mark-saying-one-kind-is-that-kinds-mark
  (doseq [k [:ask :landing :system-error]
          :let [km (str "kind-mark kind-" (name k))]]
    (testing k
      (is (str/includes? (str (h/html (block/render row/gate-row "w"
                                                     {:gate {:project "nido" :label "L" :stage :triage :needs k}
                                                      :href "#"}
                                                     {} (fn [& _] "#"))))
                         km)
          "a needs-you row")
      (is (str/includes? (str (h/html (state/state-mark {:needs-you true :needs k} nil))) km)
          "a board row's state mark")
      (is (str/includes? (str (h/html (state/arc-mark :current :awaiting k))) km)
          "an awaiting arc stage"))))

(deftest the-needs-you-rows-carry-the-state-modules-mark
  (let [mark (str (h/html (state/needs-mark)))]
    (testing "a board row whose next move is the person's"
      (is (str/includes? (str (h/html (block/render row/board-row "w"
                                                     {:project "nido" :row {:ws-id "w" :label "L" :needs-you true :status {:needs-you true}}
                                                      :href "#"}
                                                     {} (fn [& _] "#"))))
                         mark)))
    (testing "a needs-you row"
      (is (str/includes? (str (h/html (block/render row/gate-row "w"
                                                     {:gate {:project "nido" :label "L" :stage :triage
                                                             :ask {:question "Q?"}}
                                                      :href "#"}
                                                     {} (fn [& _] "#")))) mark)))))

(deftest a-stuck-row-that-asks-carries-both-marks
  (let [html (str (h/html (block/render row/board-row "w"
                                        {:project "nido" :row {:ws-id "w" :label "L" :needs-you true
                                                               :status {:needs-you true
                                                                        :owed-by :person :stuck true}}
                                         :href "#"}
                                        {} (fn [& _] "#"))))]
    (is (str/includes? html (str (h/html (state/needs-mark)))) "the ask is the person's")
    (is (str/includes? html "process will not exit") "and the run that will not end is marked beside it")))
