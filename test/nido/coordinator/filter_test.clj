(ns nido.coordinator.filter-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.filter :as f]))

(deftest empty-filter-accepts-everything
  (is (true? (f/accept? {} {})))
  (is (true? (f/accept? {} {:status "any"}))))

(deftest equality-on-top-level
  (is (true?  (f/accept? {:status "Untriaged"} {:status "Untriaged"})))
  (is (false? (f/accept? {:status "Untriaged"} {:status "Done"}))))

(deftest equality-on-properties
  (is (true? (f/accept? {:status "Untriaged"} {:properties {:status "Untriaged"}}))))

(deftest set-membership
  (is (true?  (f/accept? {:priority ["P0" "P1"]} {:priority "P0"})))
  (is (true?  (f/accept? {:priority ["P0" "P1"]} {:priority "P1"})))
  (is (false? (f/accept? {:priority ["P0" "P1"]} {:priority "P2"}))))

(deftest set-membership-with-set-literal
  (is (true? (f/accept? {:priority #{"P0" "P1"}} {:priority "P0"}))))

(deftest all-keys-must-match
  (is (true?  (f/accept? {:status "Untriaged" :priority "P0"}
                         {:status "Untriaged" :priority "P0"})))
  (is (false? (f/accept? {:status "Untriaged" :priority "P0"}
                         {:status "Untriaged" :priority "P1"}))))

(deftest top-level-shadows-properties
  ;; If a key appears both at top-level and under :properties, top-level wins.
  (is (true? (f/accept? {:status "Untriaged"}
                        {:status     "Untriaged"
                         :properties {:status "Wrong"}}))))

(deftest missing-key-fails
  (is (false? (f/accept? {:status "Untriaged"} {:priority "P0"}))))

(deftest contains-operator-matches-substring
  (is (true?  (f/accept? {:text {:contains "provider attempted"}}
                         {:text "Unsupported video provider attempted\nDomain: `vimeo.com`"})))
  (is (false? (f/accept? {:text {:contains "provider attempted"}}
                         {:text "Some unrelated product alert"}))))

(deftest contains-operator-missing-key-is-false
  (is (false? (f/accept? {:text {:contains "x"}} {:title "no :text key here"}))))

(deftest contains-operator-non-string-value-is-false
  (is (false? (f/accept? {:count {:contains "x"}} {:count 5}))))

(deftest existing-equality-and-set-membership-unaffected
  (is (true?  (f/accept? {:status "Untriaged"} {:status "Untriaged"})))
  (is (false? (f/accept? {:status "Untriaged"} {:status "Done"})))
  (is (true?  (f/accept? {:priority ["P0" "P1"]} {:priority "P0"}))))
