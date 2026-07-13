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
