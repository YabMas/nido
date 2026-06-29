(ns nido.help-test
  (:require [clojure.test :refer [deftest is]]
            [tasks.nido-help :as help]))

(deftest help-covers-the-coordination-layer
  (let [titles (set (map :title @#'help/groups))
        all-tasks (set (map first (mapcat :tasks @#'help/groups)))]
    (is (contains? titles "Workstreams / coordination"))
    (is (contains? all-tasks "nido:tui"))
    (is (contains? all-tasks "nido:coordinator:up"))
    (is (contains? all-tasks "nido:tickets:list"))))
