(ns nido.ui.view-state-test
  (:require [clojure.test :refer [deftest is]]
            [nido.ui.view-state :as vs]))

(deftest parse-defaults
  (let [v (vs/parse {:uri "/workstreams" :query-string nil})]
    (is (= :workstreams (:surface v)))
    (is (= "all" (:scope v)))
    (is (nil? (:selection v)))
    (is (nil? (:entry v)) "no ledger entry opens itself")
    (is (nil? (:rounds v)) "and no review round is unfolded")
    (is (not (contains? v :source)) "no source filter — the board shows every origin")
    (is (not (contains? v :facets)) "no facet filter")))

(deftest parse-surface-from-path
  (is (= :needs (:surface (vs/parse {:uri "/" :query-string nil}))))
  (is (= :workstreams (:surface (vs/parse {:uri "/workstreams" :query-string nil})))))

(deftest parse-scope-and-selection
  (let [v (vs/parse {:uri "/workstreams" :query-string "scope=brian&sel=brian%3Aws-1&entry=3"})]
    (is (= "brian" (:scope v)))
    (is (= {:project "brian" :ws-id "ws-1"} (:selection v)))
    (is (= 3 (:entry v)))))

(deftest parse-reading-position-in-the-pane
  (let [v (vs/parse {:uri "/workstreams" :query-string "entry=3&rounds=1,3"})]
    (is (= 3 (:entry v)) "the ledger entry the viewer has open")
    (is (= #{1 3} (:rounds v)) "the review rounds unfolded inside it")))

(deftest parse-tolerates-a-mangled-rounds-param
  ;; A reading position is not an argument — a bad one costs a fold, not the page.
  (is (= #{2} (:rounds (vs/parse {:uri "/workstreams" :query-string "rounds=2,x,"}))))
  (is (nil? (:rounds (vs/parse {:uri "/workstreams" :query-string "rounds=nope"})))))

(deftest parse-ignores-a-legacy-filter-bookmark
  ;; An old ?source=/facet bookmark must parse cleanly and filter nothing.
  (let [v (vs/parse {:uri "/workstreams" :query-string "source=scratch&app-domain=Teacher"})]
    (is (= "all" (:scope v)))
    (is (not (contains? v :source)))
    (is (not (contains? v :facets)))))

(deftest parse-tab
  (is (= :intake (:tab (vs/parse {:uri "/workstreams" :query-string nil})))
      "defaults to the first tab")
  (is (= :intake vs/default-tab))
  (is (= :active (:tab (vs/parse {:uri "/workstreams" :query-string "tab=active"}))))
  (is (= :intake (:tab (vs/parse {:uri "/workstreams" :query-string "tab=bogus"})))
      "an unknown tab falls back to the default rather than rendering an empty list"))
