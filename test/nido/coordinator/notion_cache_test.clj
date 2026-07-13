(ns nido.coordinator.notion-cache-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.notion-cache :as nc]))

(deftest parse-priority-rank-cases
  (is (= 0 (nc/parse-priority-rank "0 – Release Blocker")) "en-dash separator")
  (is (= 1 (nc/parse-priority-rank "1 - Must")))
  (is (= 2 (nc/parse-priority-rank "2 - Should")))
  (is (= 3 (nc/parse-priority-rank "3 - Could")))
  (is (= 5 (nc/parse-priority-rank "5 - Wont")))
  (is (nil? (nc/parse-priority-rank nil)) "nil in → nil")
  (is (nil? (nc/parse-priority-rank "")) "blank → nil")
  (is (nil? (nc/parse-priority-rank "Must")) "no leading digit → nil"))

(deftest pages-snapshot-shape
  (let [pages [{:page-id "p1" :status "Not started" :priority "2 - Should"
                :ball-holder {:type "people" :people [{:id "u1"} {:id "u2"}]}}
               {:page-id "p2" :status "In progress" :priority "0 – Release Blocker"
                :ball-holder nil}
               {:page-id "p3"}]                                  ; no props at all
        snap  (nc/pages-snapshot pages)]
    (is (= {:status "Not started" :priority 2 :ball-ids #{"u1" "u2"}} (get snap "p1")))
    (is (= {:status "In progress" :priority 0 :ball-ids #{}} (get snap "p2")))
    (is (= {:status nil :priority nil :ball-ids #{}} (get snap "p3")))))
