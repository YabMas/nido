;; test/nido/review/stages_test.clj
(ns nido.review.stages-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.agent :as agent]
   [nido.review.codex :as codex]
   [nido.review.stages :as stages]
   [nido.vsdd.jj :as jj]))

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

(deftest review-stage-sets-findings
  (with-redefs [codex/review! (fn [_] {:status nil :findings [{:title "x"}]
                                       :overall-correctness "incorrect"})]
    (let [ctx ((:run stages/review-stage)
               {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})]
      (is (= [{:title "x"}] (:findings ctx)))
      (is (= "incorrect" (:overall-correctness ctx)))
      (is (nil? (:control ctx))))))

(deftest review-stage-clean-diff-stops
  (with-redefs [codex/review! (fn [_] {:status :clean :findings []})]
    (let [ctx ((:run stages/review-stage)
               {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})]
      (is (= :stop (:control ctx)))
      (is (= :clean (:status ctx))))))

(deftest judge-stage-continue
  (with-redefs [agent/launch! (fn [_] {:num-turns 3 :result-error? false
                                       :result-text "```json\n{\"decision\":\"continue\",\"reason\":\"r\",\"fix_findings\":[0]}\n```"})
                stages/discover-design-doc (fn [_] nil)]
    (let [ctx ((:run stages/judge-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 1 :findings [{:title "x"}]})]
      (is (= :continue (:control ctx)))
      (is (= [0] (-> ctx :judge :fix-findings))))))

(deftest judge-stage-noop-is-indeterminate
  (with-redefs [agent/launch! (fn [_] {:num-turns 0 :result-error? false :result-text ""})
                stages/discover-design-doc (fn [_] nil)]
    (let [ctx ((:run stages/judge-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 1 :findings []})]
      (is (= :stop (:control ctx)))
      (is (= :judge-indeterminate (:status ctx))))))

(deftest fix-stage-commits-when-changed
  (let [commits (atom [])]
    (with-redefs [agent/launch! (fn [_] {:num-turns 4 :result-error? false :result-text "done"})
                  stages/working-copy-dirty? (fn [_] true)
                  jj/jj! (fn [_dir & args] (swap! commits conj (vec args))
                           {:exit 0 :out "" :err ""})]
      (let [ctx ((:run stages/fix-stage)
                 {:config {:cwd "/w" :run-id "r1"} :iter 2
                  :findings [{:title "x"}] :judge {:fix-findings nil}})]
        (is (= 1 (count (:history ctx))))
        (is (some #(= "commit" (first %)) @commits))
        (is (some #(= ["commit" "-m" "review-loop: iter 2 fixes"] %) @commits))
        (is (nil? (:control ctx)))))))

(deftest fix-stage-noop-when-not-dirty
  (with-redefs [agent/launch! (fn [_] {:num-turns 3 :result-error? false :result-text "done"})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:title "x"}] :judge {:fix-findings nil}})]
      (is (= :stop (:control ctx)))
      (is (= :fix-noop (:status ctx))))))

(deftest fix-stage-noop-stops
  (with-redefs [agent/launch! (fn [_] {:num-turns 0 :result-error? false :result-text ""})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:title "x"}] :judge {:fix-findings nil}})]
      (is (= :stop (:control ctx)))
      (is (= :fix-noop (:status ctx))))))
