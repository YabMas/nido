(ns nido.ui.server-findings-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.ui.server :as server]))

(deftest parse-findings-lines-splits-severity-area-summary
  (is (= [{:severity :blocker :summary "Save button 500s"}
          {:severity :tweak :area "Login" :summary "Header overlaps"}]
         (server/parse-findings-lines
          "blocker || Save button 500s\ntweak | Login | Header overlaps\n\n  "))))

(deftest parse-findings-lines-defaults-bad-severity-to-tweak
  (is (= [{:severity :tweak :summary "no sev given"}]
         (server/parse-findings-lines "| | no sev given"))))

(deftest parse-findings-lines-handles-line-with-no-pipe
  (is (= [{:summary "some text" :severity :tweak}]
         (server/parse-findings-lines "some text"))))
