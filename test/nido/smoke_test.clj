(ns nido.smoke-test
  (:require [clojure.test :refer [deftest is]]))

(deftest smoke
  (is (= 4 (+ 2 2)) "test runner is wired up"))
