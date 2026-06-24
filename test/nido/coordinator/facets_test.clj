(ns nido.coordinator.facets-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.facets :as facets]))

(def ^:private payload
  {:source :notion-view :page-id "p" :title "X"
   :app-domain ["Teacher"] :type "bug" :status "Needs verification"})

(deftest select-facets-pulls-only-configured
  (is (= {:app-domain ["Teacher"] :type "bug"}
         (facets/select-facets ["App Domain" "Type"] payload))))

(deftest select-facets-omits-absent
  (is (= {:type "bug"}
         (facets/select-facets ["App Domain" "Type"] {:type "bug"}))))

(deftest select-facets-omits-empty-vector
  (is (= {} (facets/select-facets ["App Domain"] {:app-domain []}))))

(deftest select-facets-empty-config-is-empty
  (is (= {} (facets/select-facets [] payload))))
