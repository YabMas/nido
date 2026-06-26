(ns nido.review.codex-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.review.codex :as codex]
   [nido.vsdd.jj :as jj]
   [nido.coordinator.state :as cstate]
   [babashka.fs :as fs]
   [clojure.java.io :as io]))

(def sample-output
  (str "{\"findings\":[{\"title\":\"[P1] Remove the extra accumulation\","
       "\"body\":\"Overcharges every payment.\",\"confidence_score\":0.9,"
       "\"priority\":1,\"code_location\":{\"absolute_file_path\":\"/w/pay.js\","
       "\"line_range\":{\"start\":4,\"end\":4}}}],"
       "\"overall_correctness\":\"incorrect\"}"))

(deftest parse-output-normalizes-findings
  (let [{:keys [findings overall-correctness]} (codex/parse-output sample-output)]
    (is (= "incorrect" overall-correctness))
    (is (= 1 (count findings)))
    (is (= {:title "[P1] Remove the extra accumulation"
            :body "Overcharges every payment."
            :priority 1 :confidence 0.9
            :file "/w/pay.js" :line-start 4 :line-end 4}
           (first findings)))))

(deftest parse-output-handles-no-findings
  (let [{:keys [findings overall-correctness]}
        (codex/parse-output "{\"findings\":[],\"overall_correctness\":\"correct\"}")]
    (is (= [] findings))
    (is (= "correct" overall-correctness))))

(deftest review!-empty-diff-is-clean
  (with-redefs [jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (is (= {:status :clean :findings []}
           (codex/review! {:cwd "/w" :base "main" :run-id "r1"})))))

(deftest review!-parses-codex-output
  (let [tmp (str (fs/create-temp-dir))]
    (with-redefs [jj/jj!         (fn [_dir & args]
                                   (if (= "diff" (first args))
                                     {:exit 0 :out "diff --git a/x b/x\n+bug" :err ""}
                                     {:exit 0 :out "" :err ""}))
                  cstate/run-dir (fn [_] tmp)
                  codex/run-codex! (fn [_opts]
                                     (spit (str (fs/path tmp "review-out.json"))
                                           sample-output)
                                     {:exit 0})]
      (let [{:keys [status findings overall-correctness]}
            (codex/review! {:cwd "/w" :base "main" :run-id "r1"})]
        (is (nil? status))
        (is (= 1 (count findings)))
        (is (= "incorrect" overall-correctness))))))

(deftest review!-throws-on-codex-failure
  (let [tmp (str (fs/create-temp-dir))]
    (with-redefs [jj/jj!           (fn [_ & _] {:exit 0 :out "diff --git a/x b/x" :err ""})
                  cstate/run-dir   (fn [_] tmp)
                  codex/run-codex! (fn [_] {:exit 1})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (codex/review! {:cwd "/w" :base "main" :run-id "r1"}))))))
