(ns nido.ui.view-state-test
  (:require [clojure.test :refer [deftest is]]
            [nido.ui.view-state :as vs]))

(deftest parse-defaults
  (let [v (vs/parse {:uri "/workstreams" :query-string nil})]
    (is (= :workstreams (:surface v)))
    (is (= "all" (:scope v)))
    (is (= :all (:source v)))
    (is (= {} (:facets v)))
    (is (nil? (:selection v)))
    (is (nil? (:entry v)))))

(deftest parse-surface-from-path
  (is (= :needs (:surface (vs/parse {:uri "/" :query-string nil}))))
  (is (= :workstreams (:surface (vs/parse {:uri "/workstreams" :query-string nil})))))

(deftest parse-filters-and-selection
  (let [v (vs/parse {:uri "/workstreams"
                     :query-string "scope=brian&source=notion&app-domain=Tutor&sel=brian:ws-7&entry=3"})]
    (is (= "brian" (:scope v)))
    (is (= :notion (:source v)))
    (is (= {:app-domain "Tutor"} (:facets v)))
    (is (= {:project "brian" :ws-id "ws-7"} (:selection v)))
    (is (= 3 (:entry v)))))

(deftest parse-unclassified-facet
  (is (= {:app-domain :unclassified}
         (:facets (vs/parse {:uri "/workstreams" :query-string "app-domain=unclassified"})))))

(deftest parse-url-decodes-facet-values
  ;; a space-encoded facet value round-trips to the raw string (moved here from
  ;; the old server parse-filters test)
  (is (= {:app-domain "Onboarding Flow"}
         (:facets (vs/parse {:uri "/workstreams" :query-string "app-domain=Onboarding%20Flow"})))))

(deftest parse-ignores-datastar-signals-param
  ;; Datastar appends its signals JSON as a `datastar` query param on every @get
  ;; poll. It must NOT be mistaken for a facet filter — that facet matches zero
  ;; rows and empties the polled list. Real facets alongside it still parse.
  (let [v (vs/parse {:uri "/_fragment/workstreams"
                     :query-string "source=notion&app-domain=Tutor&datastar=%7B%22foo%22%3A1%7D"})]
    (is (= :notion (:source v)))
    (is (= {:app-domain "Tutor"} (:facets v))
        "the `datastar` signals param must not leak into :facets")))
