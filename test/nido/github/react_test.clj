(ns nido.github.react-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.github.react :as react]))

(deftest people-without-removes-target-keeps-others
  (is (= {:people [{:id "other"}]}
         (react/people-without {:people [{:id "jaap"} {:id "other"}]} "jaap"))))

(deftest people-without-clears-when-only-target
  (is (= {:people []}
         (react/people-without {:people [{:id "jaap"}]} "jaap"))))

(deftest people-without-handles-absent-property
  (is (= {:people []}
         (react/people-without nil "jaap"))))
