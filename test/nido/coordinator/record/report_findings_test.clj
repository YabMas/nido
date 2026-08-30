(ns nido.coordinator.record.report-findings-test
  (:require
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.record.report :as report]))

(def ^:private good
  {:format :findings
   :round 1
   :staging-ref "https://staging.example/build/42"
   :note "review pass 1"
   :items [{:id "f1" :summary "Header overlaps on mobile" :severity :tweak :area "Login"}
           {:id "f2" :summary "Save button 500s" :severity :blocker}]})

(deftest schema-accepts-a-good-round
  (is (m/validate report/FindingsRound good)))

(deftest schema-registered-under-findings-kind
  (is (= report/FindingsRound (get report/event-schemas :findings))))

(deftest schema-rejects-bad-severity-and-extra-keys
  (is (not (m/validate report/FindingsRound
                       (assoc-in good [:items 0 :severity] :critical))))
  (is (not (m/validate report/FindingsRound (assoc good :bogus 1))))
  (is (not (m/validate report/FindingsRound
                       (update good :items conj {:id "f3" :summary "x"}))))) ; missing :severity

(deftest markdown-and-title-render
  (let [md (report/report->markdown good)]
    (is (re-find #"Findings round 1" md))
    (is (re-find #"\*\*blocker\*\*" md))
    (is (re-find #"\[f1\]" md)))
  (is (= "Findings round 1 (2 items)" (report/report-title good))))
