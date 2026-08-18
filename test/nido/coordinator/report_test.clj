(ns nido.coordinator.report-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [nido.coordinator.report :as report]))

(def ^:private valid-report
  {:format :triage-report
   :ticket-key "BR-7"
   :determination :bug
   :title "Checkout off by a cent"
   :summary "Rounding applied per-line instead of on the total."
   :confidence {:level :high :reason "reproduced in the calc fn"}
   :routing {:owner :jaap :app-domain "Teacher" :depth :deep}
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

(deftest validate-accepts-a-pre-routing-report-missing-routing
  ;; Backward-compat: reports written before the routing arc have NO :routing key.
  ;; :routing is {:optional true} so they still validate — else entry->report falls
  ;; back to dumping the raw EDN into the pane (regression).
  (is (report/validate (dissoc valid-report :routing))))

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

(def ^:private valid-design
  {:format     :design
   :summary    "Rounding moves to a single point on the order total."
   :shape      "One rounding boundary at the order aggregate; line items stay exact."
   :invariants ["a total is rounded exactly once"
                "no line item carries a rounded amount"]
   :standing   {:relation :conforms :principles ["shape of the data is the design"]}
   :assumes    [{:about "line totals are computed per-item in order/calc"
                 :read  ["src/order/calc.clj"]
                 :drift "rounding is applied per line — copied, never decided"}]
   :rejected   [{:alternative "round at render time"
                 :why-not     "moves money math into the view layer"}]
   :layers     [{:claim "extract the total aggregate" :mode :judgment}
                {:claim "drop per-line rounding at all 12 call sites" :mode :mechanical}]
   :seams      [{:what "the legacy per-line path stays for invoices"
                 :visible-how "old fn kept, marked deprecated, both callers listed"}]
   :open       ["whether invoices should follow in the same arc"]
   :effort     :M})

(deftest validate-event-accepts-a-design
  (is (= valid-design (report/validate-event :design valid-design))))

(deftest design-requires-at-least-one-invariant
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :design (assoc valid-design :invariants [])))))

(deftest design-conforms-needs-no-note
  (is (report/validate-event :design (assoc valid-design :standing {:relation :conforms}))))

(deftest design-extends-and-challenges-require-a-note
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :design (assoc valid-design :standing {:relation :extends}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :design (assoc valid-design :standing {:relation :challenges}))))
  (is (report/validate-event
       :design (assoc valid-design :standing {:relation :challenges
                                              :note "money math needs a mutable accumulator here"}))))

(deftest design-rejects-a-step-list
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :design (assoc valid-design :steps ["do the thing"])))
      "steps are working memory — the ledger holds what survives the session"))

(deftest design-rejects-squirrel-effort
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :design (assoc valid-design :effort :squirrel)))
      "the design is where a triage :squirrel resolves into a concrete size"))

(deftest report->markdown-design-has-its-sections
  (let [md (report/report->markdown valid-design)]
    (is (str/includes? md "# Design"))
    (is (str/includes? md "**Stance:** conforms"))
    (is (str/includes? md "## Invariants"))
    (is (str/includes? md "a total is rounded exactly once"))
    (is (str/includes? md "## Assumes"))
    (is (str/includes? md "drift from the stance:"))
    (is (str/includes? md "## Rejected"))
    (is (str/includes? md "## Intended layers"))
    (is (str/includes? md "*(mechanical)*"))
    (is (str/includes? md "## Seams"))
    (is (str/includes? md "## Open"))))

(deftest report->markdown-design-omits-empty-optional-sections
  (let [md (report/report->markdown
            (dissoc valid-design :assumes :rejected :layers :seams :open))]
    (is (str/includes? md "## Invariants"))
    (is (not (str/includes? md "## Assumes")))
    (is (not (str/includes? md "## Rejected")))
    (is (not (str/includes? md "## Intended layers")))
    (is (not (str/includes? md "## Seams")))
    (is (not (str/includes? md "## Open")))))

(deftest report->markdown-design-shows-note-and-supersedes
  (let [md (report/report->markdown
            (assoc valid-design
                   :standing {:relation :extends :note "adds a rounding boundary"}
                   :supersedes {:seq 4 :why "review showed line-level rounding is load-bearing"}))]
    (is (str/includes? md "> adds a rounding boundary"))
    (is (str/includes? md "Supersedes entry 4"))))

(deftest report-title-for-design-is-the-shape
  (is (= "One rounding boundary at the order aggregate; line items stay exact."
         (report/report-title valid-design))))

(deftest entry-payload-accepts-design
  (let [[ext payload] (report/entry-payload :design (pr-str valid-design))]
    (is (= "edn" ext))
    (is (str/includes? payload ":design"))
    (is (= valid-design (edn/read-string payload)))))

(def ^:private valid-plan
  {:format :implementation-plan :summary "Round on the total."
   :direction "Round once on the order total" :effort :M
   :steps ["add a render test" "fix the calc"]})

(deftest implementation-plan-stays-readable-after-the-cutover
  (is (= valid-plan (report/validate-event :implementation-plan valid-plan))
      "legacy ledgers must still read as typed events"))

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

(deftest entry-payload-accepts-pr-opened
  (let [edn (pr-str {:format :pr-opened
                     :url "https://github.com/brian-study/brian/pull/412"
                     :title "fix(ui): firefox modal close"
                     :summary "Draft PR for BR-4659."})
        [ext payload] (report/entry-payload :pr-opened edn)]
    (is (= "edn" ext))
    (is (= :pr-opened (:format (edn/read-string payload))))))

(deftest entry-payload-accepts-implementation-plan
  (let [edn (pr-str {:format :implementation-plan
                     :summary "Guard the close handler against a nil node."
                     :direction "Null-check in close-modal!"
                     :effort :S
                     :steps ["repro test" "guard" "regression test"]})
        [ext _] (report/entry-payload :implementation-plan edn)]
    (is (= "edn" ext))))

(deftest entry-payload-accepts-implementation-completed
  (let [edn (pr-str {:format :implementation-completed
                     :summary "Fixed; CI green; on the merge queue."
                     :artifacts [{:kind :pr :ref "brian-study/brian#412"
                                  :url "https://github.com/brian-study/brian/pull/412"}]})
        [ext _] (report/entry-payload :implementation-completed edn)]
    (is (= "edn" ext))))

(deftest entry-payload-accepts-blocker
  (let [edn (pr-str {:format :blocker
                     :summary "Root cause is in the statechart, not the UI."
                     :needs "Confirm whether to widen scope or re-triage."})
        [ext _] (report/entry-payload :blocker edn)]
    (is (= "edn" ext))))

(def ^:private valid-review
  {:format :review-report
   :status :converged
   :base "main"
   :base-rev "a1b2c3d"
   :rounds 2
   :findings-fixed 3
   :findings-remaining 0
   :report-path "/Users/x/.nido/runs/review-abc/report.json"})

(deftest validate-event-accepts-review
  (is (= valid-review (report/validate-event :review valid-review))))

(deftest validate-event-rejects-bad-review-status
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :review (assoc valid-review :status :bogus)))))

(deftest validate-event-rejects-review-extra-key
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :review (assoc valid-review :extra 1)))))

(deftest review-allows-nil-base-rev-and-report-path
  (is (report/validate-event :review (assoc valid-review :base-rev nil :report-path nil))))

(deftest report->markdown-review-has-verdict-and-counts
  (let [md (report/report->markdown valid-review)]
    (is (str/includes? md "Review"))
    (is (str/includes? md "converged"))
    (is (str/includes? md "3 fixed"))
    (is (str/includes? md "report.json"))))

(deftest report-title-review
  (is (= "Review: converged" (report/report-title valid-review))))

(deftest entry-payload-accepts-review
  (let [[ext payload] (report/entry-payload :review (pr-str valid-review))]
    (is (= "edn" ext))
    (is (= :review-report (:format (edn/read-string payload))))))

(def ^:private valid-proposed-ticket
  {:format :proposed-ticket
   :title "Logo bug on the pricing page"
   :ticket-type "bug"
   :priority "2 - Should"
   :source-url "https://myco.slack.com/archives/C123/p456"
   :problem "The logo disappears on mobile Safari."
   :root-cause "A media query hides the header on narrow viewports."
   :fix "Scope the media query to exclude the logo. header.css:42."
   :watch-out "src/order.clj:88 — grounded here"})

(deftest validate-event-accepts-proposed-ticket
  (is (= valid-proposed-ticket (report/validate-event :proposed-ticket valid-proposed-ticket))))

(deftest validate-event-accepts-proposed-ticket-without-optional-keys
  (let [minimal (dissoc valid-proposed-ticket :priority :watch-out)]
    (is (= minimal (report/validate-event :proposed-ticket minimal)))))

(deftest validate-event-accepts-proposed-ticket-with-nil-priority-and-watch-out
  (is (report/validate-event :proposed-ticket
        (assoc valid-proposed-ticket :priority nil :watch-out nil))))

(deftest validate-event-rejects-proposed-ticket-missing-required-key
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :proposed-ticket (dissoc valid-proposed-ticket :source-url)))))

(deftest validate-event-rejects-proposed-ticket-extra-key
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :proposed-ticket (assoc valid-proposed-ticket :extra 1)))))

(deftest validate-event-rejects-proposed-ticket-wrong-format-tag
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :proposed-ticket (assoc valid-proposed-ticket :format :triage-report)))))

(deftest entry-payload-accepts-proposed-ticket
  (let [[ext payload] (report/entry-payload :proposed-ticket (pr-str valid-proposed-ticket))]
    (is (= "edn" ext))
    (is (= :proposed-ticket (:format (edn/read-string payload))))))

(deftest report->markdown-proposed-ticket-has-title-and-source
  (let [md (report/report->markdown valid-proposed-ticket)]
    (is (str/includes? md "Logo bug on the pricing page"))
    (is (str/includes? md "bug"))
    (is (str/includes? md "https://myco.slack.com/archives/C123/p456"))
    (is (str/includes? md "disappears on mobile Safari"))))

(def ^:private compact-proposal
  {:format :proposed-ticket
   :title "Restore active-students count on admin courses tab"
   :ticket-type "bug" :priority "2 - Should"
   :source-url "https://slack/x"
   :problem "Admin courses tab lost the per-course active-students count."
   :root-cause "Collateral from PR #3802 (bea3fac); verified live in REPL."
   :fix "Restore the column on the admin tab only. courses.clj:913, admin.clj:684."
   :watch-out "Confirm the reporter means the admin tab, not the Org detail page."})

(deftest proposed-ticket-compact-schema
  (is (= compact-proposal (report/validate-event :proposed-ticket compact-proposal)))
  ;; optional fields absent is fine
  (is (report/validate-event :proposed-ticket (dissoc compact-proposal :priority :watch-out)))
  ;; missing a required field is rejected
  (is (thrown? clojure.lang.ExceptionInfo
        (report/validate-event :proposed-ticket (dissoc compact-proposal :root-cause))))
  ;; the old essay key is rejected (closed schema)
  (is (thrown? clojure.lang.ExceptionInfo
        (report/validate-event :proposed-ticket (assoc compact-proposal :description "essay")))))

(deftest proposed-ticket-compact-markdown
  (let [md (report/report->markdown compact-proposal)]
    (is (str/includes? md "Restore active-students count on admin courses tab"))
    (is (str/includes? md "**Problem**"))
    (is (str/includes? md "**Root cause**"))
    (is (str/includes? md "**Fix**"))
    (is (str/includes? md "**Watch out**"))
    (is (str/includes? md "2 - Should")))
  ;; Watch-out line omitted when nil; Priority clause omitted when nil
  (let [md (report/report->markdown (dissoc compact-proposal :watch-out :priority))]
    (is (not (str/includes? md "**Watch out**")))
    (is (not (str/includes? md "Priority")))))

(def ^:private improvement-proposal
  {:format :proposed-ticket
   :title "Bulk-export button on the admin courses tab"
   :ticket-type "improvement" :priority "3 - Could"
   :source-url "https://slack/x"
   :request "No way to export the admin courses list; staff copy rows by hand."
   :proposed-change "Add an Export CSV toolbar action reusing export/csv. admin.clj:684, handlers/export.clj:20."
   :rationale "Recurring manual toil for ops; data + CSV helper already exist."
   :watch-out "Confirm filtered view vs all courses."})

(deftest proposed-ticket-multi-schema-dispatches-on-type
  ;; improvement validates against ChangeProposal
  (is (= improvement-proposal (report/validate-event :proposed-ticket improvement-proposal)))
  (is (report/validate-event :proposed-ticket (dissoc improvement-proposal :priority :watch-out)))
  ;; bug still validates (compact-proposal defined earlier in this file)
  (is (= compact-proposal (report/validate-event :proposed-ticket compact-proposal)))
  ;; wrong body for the type is rejected: bug fields under "improvement"
  (is (thrown? clojure.lang.ExceptionInfo
        (report/validate-event :proposed-ticket
          (assoc compact-proposal :ticket-type "improvement"))))
  ;; improvement fields under "bug" is rejected
  (is (thrown? clojure.lang.ExceptionInfo
        (report/validate-event :proposed-ticket
          (assoc improvement-proposal :ticket-type "bug"))))
  ;; an unlisted type (no default) is rejected
  (is (thrown? clojure.lang.ExceptionInfo
        (report/validate-event :proposed-ticket (assoc improvement-proposal :ticket-type "chore"))))
  ;; a required change field missing is rejected
  (is (thrown? clojure.lang.ExceptionInfo
        (report/validate-event :proposed-ticket (dissoc improvement-proposal :rationale)))))

(deftest proposed-ticket-improvement-markdown
  (let [md (report/report->markdown improvement-proposal)]
    (is (str/includes? md "Bulk-export button on the admin courses tab"))
    (is (str/includes? md "**Request**"))
    (is (str/includes? md "**Proposed change**"))
    (is (str/includes? md "**Rationale**"))
    (is (str/includes? md "**Watch out**"))
    (is (str/includes? md "3 - Could"))
    (is (not (str/includes? md "**Root cause**"))))
  ;; bug still renders the bug card (regression guard)
  (let [md (report/report->markdown compact-proposal)]
    (is (str/includes? md "**Root cause**"))
    (is (not (str/includes? md "**Request**")))))

(def ^:private shallow-report
  {:format :triage-report :ticket-key "BR-8" :determination :needs-info
   :title "Login loops on SSO" :summary "Looks like auth — Eric's area; not investigated."
   :confidence {:level :low :reason "routed without root-causing"}
   :routing {:owner :eric :app-domain "Backend" :depth :shallow}
   :directions [] :notion-writes nil :trail []})

(deftest validate-accepts-a-shallow-routed-report
  (is (= shallow-report (report/validate shallow-report))))

(deftest validate-accepts-nil-routing-for-slack
  (is (report/validate (assoc valid-report :routing nil :notion-writes nil :directions []))))

(deftest validate-rejects-bad-owner
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate (assoc-in valid-report [:routing :owner] :bob)))))

(deftest validate-rejects-bad-app-domain
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate (assoc-in valid-report [:routing :app-domain] "Mobile")))))

(deftest report->markdown-deep-shows-routing
  (is (str/includes? (report/report->markdown valid-report) "Routing:")))

(deftest report->markdown-shallow-has-routing-no-directions-no-writes
  (let [md (report/report->markdown shallow-report)]
    (is (str/includes? md "Routing:"))
    (is (str/includes? md "Eric"))
    (is (str/includes? md "Backend"))
    (is (not (str/includes? md "Solution directions")) "shallow omits the directions section")
    (is (not (str/includes? md "Proposed Notion writes")) "shallow has no notion-writes")))

(deftest owner->user-id-covers-the-owner-enum
  (doseq [o [:ataberk :eric :jaap]]
    (is (string? (report/owner->user-id o)) (str o " maps to a user-id"))
    (is (not (clojure.string/blank? (report/owner->user-id o)))))
  (is (= "955b4c25-7bce-4ca2-ab5e-d99acbcd423a" (report/owner->user-id :eric))))
