(ns nido.tui-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.tui :as tui]))

(deftest view-order-is-notion-scratch-sessions
  (is (= [:notion :scratch :sessions] (mapv :id @#'tui/view-defs))))

(deftest cycling-wraps-both-directions
  (is (= :scratch  (#'tui/next-view :notion)))
  (is (= :sessions (#'tui/next-view :scratch)))
  (is (= :notion   (#'tui/next-view :sessions)) "wraps forward")
  (is (= :sessions (#'tui/prev-view :notion)) "wraps back")
  (is (= :notion   (#'tui/prev-view :scratch))))

(deftest view-for-id-resolves
  (is (= :scratch (:id (#'tui/view-for-id :scratch))))
  (is (= :ops     (:kind (#'tui/view-for-id :sessions))))
  (is (= :workstreams (:kind (#'tui/view-for-id :notion)))))
