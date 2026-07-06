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
