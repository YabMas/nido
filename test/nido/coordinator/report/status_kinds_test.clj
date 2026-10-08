(ns nido.coordinator.report.status-kinds-test
  "The ledger kinds that record what was a stored status field: each validates as written, each
   renders a title and a body, and the worded blocker answer is told from a branch answer."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nido.coordinator.report :as report]))

(def ^:private samples
  {:closed            {:format :closed :outcome :between-phases :design {:seq 4} :by :nido}
   :reopened          {:format :reopened :stage :in-progress :by :person}
   :stage-set         {:format :stage-set :stage :shipping :by :nido}
   :findings-resolved {:format :findings-resolved :round 2 :items ["f1" "f3"] :by "PR #12"}
   :scratch           {:format :scratch :session "fix-typo"}
   :notion-status     {:format :notion-status :page-id "abc" :status "Review" :by :poller}
   :layer-completed   {:format :layer-completed :design {:seq 9} :layer 2 :of 5
                       :bookmark "s--status"}})

(deftest every-status-kind-validates-titles-and-renders
  (doseq [[kind r] samples]
    (testing (name kind)
      (is (= r (report/validate-event kind r)))
      (is (not (str/blank? (report/report-title r))))
      (is (not (str/blank? (report/report->markdown r)))))))

(deftest a-layer-names-its-design-and-a-positive-count
  (is (thrown? Exception (report/validate-event :layer-completed
                                                (dissoc (:layer-completed samples) :design))))
  (is (thrown? Exception (report/validate-event :layer-completed
                                                (assoc (:layer-completed samples) :layer 0)))))

(deftest a-stage-set-says-who-set-it
  (is (thrown? Exception (report/validate-event :stage-set {:format :stage-set :stage :ready}))))

(deftest a-blocker-answer-is-a-branch-or-words
  (let [branch {:format :blocker-answered :blocker-seq 3 :letter "A" :label "Keep"
                :summary "keep the old path"}
        words  {:format :blocker-answered :blocker-seq 3
                :summary "use the new column, and drop the flag in the same change"}]
    (testing "a branch picked at the gate"
      (is (= branch (report/validate-event :blocker-answered branch)))
      (is (= "Answered A — Keep" (report/report-title branch))))
    (testing "an answer given in words carries no letter"
      (is (= words (report/validate-event :blocker-answered words)))
      (is (str/starts-with? (report/report-title words) "Answered: use the new column")))
    (testing "half a branch is neither"
      (is (thrown? Exception (report/validate-event :blocker-answered (dissoc branch :label)))))))
