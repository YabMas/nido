(ns nido.ui.components.row-test
  "rows-say-states-through-marks: a row says a workstream's state, the needs-you mark included,
   only by calling nido.ui.components.state."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
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

(deftest the-needs-you-rows-carry-the-state-modules-mark
  (let [mark (str (h/html (state/needs-mark)))]
    (testing "a board row whose next move is the person's"
      (is (str/includes? (str (h/html (row/board-row "nido" {:ws-id "w" :label "L" :needs-you true}
                                                     {:href "#"})))
                         mark)))
    (testing "a needs-you row"
      (is (str/includes? (str (h/html (row/gate-row {:project "nido" :label "L" :stage :triage
                                                       :ask {:question "Q?"}} "#"))) mark)))))
