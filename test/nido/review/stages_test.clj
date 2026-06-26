;; test/nido/review/stages_test.clj
(ns nido.review.stages-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.review.stages :as stages]))

(deftest parse-judge-decision-reads-fenced-json
  (let [txt (str "Here is my call.\n\n```json\n"
                 "{\"decision\":\"continue\",\"reason\":\"2 real bugs\",\"fix_findings\":[0,2]}\n"
                 "```\n")]
    (is (= {:decision :continue :reason "2 real bugs" :fix-findings [0 2]}
           (stages/parse-judge-decision txt)))))

(deftest parse-judge-decision-stop-without-fix-findings
  (let [txt "```json\n{\"decision\":\"stop\",\"reason\":\"clean\"}\n```"]
    (is (= {:decision :stop :reason "clean" :fix-findings nil}
           (stages/parse-judge-decision txt)))))

(deftest parse-judge-decision-malformed-is-indeterminate
  (is (= :indeterminate (:decision (stages/parse-judge-decision "no json here"))))
  (is (= :indeterminate (:decision (stages/parse-judge-decision nil)))))
