(ns nido.coordinator.report-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.coordinator.report :as report]))

(def ^:private valid-report
  {:format :triage-report
   :ticket-key "BR-7"
   :determination :bug
   :title "Checkout off by a cent"
   :summary "Rounding applied per-line instead of on the total."
   :confidence {:level :high :reason "reproduced in the calc fn"}
   :directions [{:label "A" :shape "round once on the total"
                 :effort :M :confidence {:level :medium :reason "touches money math"}}]
   :notion-writes {:type "bug" :effort :M
                   :status-transition ["Needs verification" "Not started"]
                   :title "Checkout off by a cent"
                   :description-prepend "Rounding bug in order totals."}
   :trail [{:ref "src/order.clj:88" :note "per-line round here"}]})

(deftest validate-accepts-a-well-formed-report
  (is (= valid-report (report/validate valid-report))))

(deftest validate-rejects-bad-enum
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate (assoc valid-report :determination :maybe)))))

(deftest validate-rejects-extra-key
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate (assoc valid-report :dismiss-reason "x")))))

(deftest validate-allows-nil-notion-writes-for-slack
  (is (report/validate (assoc valid-report :notion-writes nil))))

(deftest report->markdown-triage-has-sections-and-trail-last
  (let [md (report/report->markdown valid-report)]
    (is (str/includes? md "Checkout off by a cent"))
    (is (str/includes? md "Solution directions"))
    (is (str/includes? md "round once on the total"))
    (is (str/includes? md "Investigation trail"))
    (is (< (str/index-of md "Solution directions")
           (str/index-of md "Investigation trail"))
        "trail (§5) renders last")
    (is (not (str/includes? md "dismiss instead")) "§4 is gone")))

(deftest report->markdown-passthrough-for-markdown-payload
  (is (= "# hi\n\nbody"
         (report/report->markdown {:format :markdown :markdown "# hi\n\nbody"}))))

(deftest report->markdown-nil-is-blank
  (is (= "" (report/report->markdown nil))))

(deftest report->markdown-notion-writes-without-status-transition
  (let [md (report/report->markdown
            (update valid-report :notion-writes dissoc :status-transition))]
    (is (not (str/includes? md "Status:")) "no Status line when transition absent")
    (is (str/includes? md "- Effort:"))
    (is (str/includes? md "- Title:"))
    (is (not (re-find #"- Effort:[^\n]*\n\n- Title:" md))
        "no spurious blank line between Effort and Title")))
