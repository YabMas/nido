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

(def ^:private valid-plan
  {:format :implementation-plan :summary "Round on the total."
   :direction "Round once on the order total" :effort :M
   :steps ["add a render test" "fix the calc"]})

(def ^:private valid-completed
  {:format :implementation-completed :summary "Shipped the fix."
   :artifacts [{:kind :pr :ref "org/repo#42" :url "https://github.com/org/repo/pull/42"}]
   :open ["mark PR ready"]})

(def ^:private valid-blocker
  {:format :blocker :summary "Waiting on a Stripe test key." :needs "Stripe test key from ops"})

(def ^:private valid-pr
  {:format :pr-opened :url "https://github.com/org/repo/pull/42" :title "Fix rounding"})

(deftest validate-event-accepts-each-typed-event
  (is (= valid-plan      (report/validate-event :implementation-plan valid-plan)))
  (is (= valid-completed (report/validate-event :implementation-completed valid-completed)))
  (is (= valid-blocker   (report/validate-event :blocker valid-blocker)))
  (is (= valid-pr        (report/validate-event :pr-opened valid-pr))))

(deftest validate-event-rejects-wrong-format-tag
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :blocker (assoc valid-blocker :format :implementation-plan)))))

(deftest validate-event-rejects-extra-key
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :implementation-plan (assoc valid-plan :extra 1)))))

(deftest validate-event-rejects-unknown-kind
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :nope valid-plan))))

(deftest triage-accepts-squirrel-and-defer-note
  (is (report/validate
       (-> valid-report
           (assoc :defer-note "Direction depends on whether we refactor the totals pipeline.")
           (assoc-in [:directions 0 :effort] :squirrel)
           (assoc-in [:notion-writes :effort] :squirrel)))))

(deftest report->markdown-implementation-plan-has-headings
  (let [md (report/report->markdown valid-plan)]
    (is (str/includes? md "Implementation plan"))
    (is (str/includes? md "Round once on the order total"))
    (is (str/includes? md "Steps"))
    (is (str/includes? md "fix the calc"))))

(deftest report->markdown-completed-blocker-pr
  (is (str/includes? (report/report->markdown valid-completed) "Artifacts"))
  (is (str/includes? (report/report->markdown valid-completed) "org/repo#42"))
  (is (str/includes? (report/report->markdown valid-blocker) "Needs"))
  (is (str/includes? (report/report->markdown valid-blocker) "Stripe test key from ops"))
  (is (str/includes? (report/report->markdown valid-pr) "Fix rounding")))

(deftest report-title-per-event
  (is (= "Round once on the order total" (report/report-title valid-plan)))
  (is (= "Shipped the fix." (report/report-title valid-completed)))
  (is (= "Stripe test key from ops" (report/report-title valid-blocker)))
  (is (nil? (report/report-title valid-report)) "triage falls through to (:title) at the call site")
  (is (nil? (report/report-title valid-pr))      "pr-opened falls through to (:title) at the call site"))

(deftest entry-payload-validates-typed-and-passes-markdown
  (let [[ext payload] (report/entry-payload :implementation-plan (pr-str valid-plan))]
    (is (= "edn" ext))
    (is (str/includes? payload ":implementation-plan"))
    (is (str/includes? payload "Round once on the order total")))
  (let [[ext payload] (report/entry-payload :note "# free\nform")]
    (is (= "md" ext))
    (is (= "# free\nform" payload))))

(deftest entry-payload-rejects-malformed-typed
  (is (thrown? clojure.lang.ExceptionInfo (report/entry-payload :blocker "not-an-edn-map"))))
