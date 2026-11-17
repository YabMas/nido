(ns tasks.nido-coordinator-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tasks.nido-coordinator :as sut]))

(def ^:private hold
  {:project "nido" :ws-id "ws-20260923-48ad37" :address "ws-20260923-05d20a/1#0"
   :started-at "2026-09-23T11:00:13Z"})

(deftest sweep-hold-lines-test
  (testing "nothing holds the sweep: nothing is said"
    (is (= [] (sut/sweep-hold-lines []))))
  (testing "unreadable holds are said, never read as no hold"
    (is (= ["Improvement sweep: holds could not be read"] (sut/sweep-hold-lines nil))))
  (testing "a stuck hold is flagged with the close that releases it"
    (let [[flag remedy :as lines]
          (sut/sweep-hold-lines [(assoc hold :state :stuck :ended-at "2026-09-23T11:08:59Z")])]
      (is (= 2 (count lines)))
      (is (str/includes? flag "STUCK"))
      (is (str/includes? flag "ws-20260923-48ad37"))
      (is (str/includes? flag "2026-09-23T11:08:59Z"))
      (is (str/includes? remedy "bb nido:workstream:close :project nido :ws-id ws-20260923-48ad37 :outcome vetoed"))))
  (testing "a stuck hold with no session history still says what holds it"
    (is (str/includes? (first (sut/sweep-hold-lines [(assoc hold :state :stuck)])) "no session is working on it")))
  (testing "a working or gated hold is reported without a remedy"
    (is (= 1 (count (sut/sweep-hold-lines [(assoc hold :state :working)]))))
    (is (str/includes? (first (sut/sweep-hold-lines [(assoc hold :state :waiting-on-you)]))
                       "parked at a gate"))))

(deftest held-lines-test
  (let [lines #(#'sut/held-lines % (.toEpochMilli (java.time.Instant/parse "2026-11-17T17:00:00Z")))]
    (testing "nothing held: nothing is said"
      (is (empty? (lines nil)))
      (is (empty? (lines []))))
    (testing "a held conductor is listed with how long it has waited"
      (let [ls (lines [{:project :fukan :ws-id "ws-20261117-2786b5" :session "ws-20261117-2786b5--conductor"
                        :since "2026-11-17T16:33:12Z"}])]
        (is (str/includes? (first ls) "1 session(s)"))
        (is (str/includes? (second ls) "fukan/ws-20261117-2786b5 ws-20261117-2786b5--conductor"))
        (is (str/includes? (second ls) "waiting 26m"))))
    (testing "a hold that cannot be timed is still listed"
      (is (str/includes? (second (lines [{:project :nido :ws-id "ws-1" :session "kid" :since "?"}]))
                         "since an unknown time")))))
