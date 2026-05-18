(ns nido.session.engine-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.session.engine :as engine]))

(def fake-services
  [{:type :postgresql :name :pg}
   {:type :process    :name :repl}
   {:type :eval       :name :app}])

(deftest filter-services-by-profile-allowlist
  (testing ":all returns every service"
    (is (= 3 (count (engine/filter-services fake-services :all)))))
  (testing "empty allowlist returns nothing"
    (is (= 0 (count (engine/filter-services fake-services [])))))
  (testing "specific allowlist returns matching :type"
    (is (= [:postgresql]
           (mapv :type (engine/filter-services fake-services [:postgresql])))))
  (testing "specific allowlist with multiple matches"
    (is (= [:postgresql :process]
           (mapv :type (engine/filter-services fake-services [:postgresql :process]))))))
