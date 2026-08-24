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

(def ^:private frame
  {:defect-layer :design
   :governing    ["two registers of data — values in motion vs state at rest"]
   :violated     [{:rule "closed maps for domain entities"
                   :source "docs/reference/malli.md"
                   :evidence "src/order/calc.clj:88"}]
   :note "the row shape reaches domain logic unparsed"})

(deftest triage-accepts-a-design-frame
  (is (report/validate (assoc valid-report :design-frame frame))))

(deftest triage-without-a-design-frame-still-validates
  (is (report/validate valid-report)
      "pre-spine reports must keep reading as typed events"))

(deftest triage-rejects-an-unknown-defect-layer
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate (assoc valid-report :design-frame
                                       (assoc frame :defect-layer :architectural))))))

(deftest triage-design-frame-renders-with-its-citations
  (let [md (report/report->markdown (assoc valid-report :design-frame frame))]
    (is (str/includes? md "## Design frame"))
    (is (str/includes? md "**Defect layer:** design"))
    (is (str/includes? md "the row shape reaches domain logic unparsed"))
    (is (str/includes? md "Governed by:"))
    (is (str/includes? md "Violates:"))
    (is (str/includes? md "docs/reference/malli.md"))
    (is (str/includes? md "src/order/calc.clj:88"))))

(deftest triage-without-a-frame-renders-no-frame-section
  (is (not (str/includes? (report/report->markdown valid-report) "Design frame"))))

(deftest triage-shallow-frame-needs-only-the-layer
  (is (report/validate (assoc valid-report :design-frame {:defect-layer :unknown}))
      "a shallow route does not root-cause, so it cites nothing"))

(def ^:private valid-design
  {:format     :design
   :summary    "Rounding moves to a single point on the order total."
   :shape      "One rounding boundary at the order aggregate; line items stay exact."
   :invariants ["a total is rounded exactly once"
                "no line item carries a rounded amount"]
   :standing   {:relation :conforms :principles ["shape of the data is the design"]}
   :baseline   {:seq 1 :relation :within}
   :intent     {:seq 2}
   :rejected   [{:alternative "round at render time"
                 :why-not     "moves money math into the view layer"}]
   :layers     [{:claim "extract the total aggregate" :mode :judgment}
                {:claim "drop per-line rounding at all 12 call sites" :mode :mechanical}]
   :seams      [{:what "the legacy per-line path stays for invoices"
                 :visible-how "old fn kept, marked deprecated, both callers listed"
                 :closed-by :spun-out :ref "FU-12"}]
   :open       ["whether invoices should follow in the same arc"]
   :effort     :M})

(def ^:private legacy-design
  "A :design record from before the baseline event: no :baseline, carrying the
   :assumes it replaced. Not writable any more; must stay readable forever."
  {:format     :design
   :summary    "Rounding moves to a single point on the order total."
   :shape      "One rounding boundary at the order aggregate."
   :invariants ["a total is rounded exactly once"]
   :standing   {:relation :conforms}
   :assumes    [{:about "line totals are computed per-item in order/calc"
                 :read  ["src/order/calc.clj"]
                 :drift "rounding is applied per line — copied, never decided"}]
   :seams      [{:what "the legacy per-line path stays for invoices"
                 :visible-how "old fn kept, marked deprecated"}]
   :effort     :M})

(def ^:private phased-design
  "A change that reaches production in three landings. The middle one is the
   reason :holds exists: while it is live there are two writers, so the record's
   own second invariant is false ON PURPOSE."
  {:format     :design
   :summary    "The order address moves to its own column."
   :shape      "Two writers during the migration; one reader throughout."
   :invariants [{:invariant "no request reads a column no writer maintains" :holds :always}
                {:invariant "exactly one writer maintains the address"      :holds :on-completion}]
   :standing   {:relation :conforms}
   :intent     {:seq 2}
   :baseline   {:seq 1 :relation :within}
   :phases     [{:claim     "both writers maintain the new column; nothing reads it"
                 :habitable "readers are unchanged; the new column is write-only and unobserved"
                 :exit      {:kind :observation
                             :criterion "shadow-read discrepancy counter flat at zero for 7 days"}
                 :undo      {:how :revert :by "stop dual-writing; nothing reads the new column"}}
                {:claim     "reads move to the new column"
                 :habitable "the old column is still written, so a revert is a config flip"
                 :exit      {:kind :soak :criterion "one full billing cycle with no incident"}
                 :undo      {:how :revert :by "flip the read path back"}}
                {:claim     "the old column is dropped"
                 :habitable "one writer, one reader — the end state"
                 :exit      {:kind :completion :criterion "nothing follows; the migration is done"}
                 :undo      {:how :none :why "the column and its data are gone; no backup past 30 days"}}]
   :seams      [{:what "the old column is still written through phases 1 and 2"
                 :visible-how "both writers sit side by side in one namespace"
                 :closed-by :phase :phase "the old column is dropped"}]
   :effort     :L})

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
    (is (str/includes? md "**Against the baseline:** within (entry 1)"))
    (is (str/includes? md "## Rejected"))
    (is (str/includes? md "## Intended layers"))
    (is (str/includes? md "*(mechanical)*"))
    (is (str/includes? md "## Seams"))
    (is (str/includes? md "## Open"))))

(deftest report->markdown-design-omits-empty-optional-sections
  (let [md (report/report->markdown
            (dissoc valid-design :rejected :layers :seams :open))]
    (is (str/includes? md "## Invariants"))
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

;; ---------------------------------------------------------------------------
;; Phasing — the temporal cut. A phase plan makes the record claim things about
;; a RUNNING system across several landings, which is why two fields tighten.

(deftest validate-event-accepts-a-phased-design
  (is (= phased-design (report/validate-event :design phased-design))))

(deftest a-phase-plan-forces-every-invariant-to-say-when-it-holds
  ;; The whole point: without :holds the verdict pass judges the middle of a
  ;; migration against the end of it, and reports the plan working as a defect.
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :design (assoc phased-design
                               :invariants ["exactly one writer maintains the address"])))))

(deftest an-unphased-design-keeps-plain-string-invariants
  ;; And may not use the map form: one landing has exactly one moment for an
  ;; invariant to hold at, so :holds there is ceremony with one legal answer.
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :design (assoc valid-design
                               :invariants [{:invariant "a total is rounded exactly once"
                                             :holds :always}])))))

(deftest one-phase-is-not-a-plan
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :design (update phased-design :phases (comp vec (partial take 1)))))))

(deftest a-phase-must-carry-its-gate-and-its-undo
  (doseq [missing [:exit :undo :habitable :claim]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (report/validate-event
                  :design (update phased-design :phases
                                  (fn [ps] (vec (cons (dissoc (first ps) missing) (rest ps)))))))
        (str "a phase missing " missing " must be refused"))))

(deftest a-seam-must-name-what-closes-it
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :design (assoc valid-design
                               :seams [{:what "the legacy path stays"
                                        :visible-how "old fn kept, marked deprecated"}])))))

(deftest every-closure-kind-carries-its-own-justification
  (doseq [seam [{:closed-by :phase     :phase "the old column is dropped"}
                {:closed-by :spun-out  :ref   "FU-12"}
                {:closed-by :permanent :why   "the old path is a supported mode, not debt"}]]
    (is (report/validate-event
         :design (assoc phased-design
                        :seams [(merge {:what "w" :visible-how "v"} seam)]))))
  ;; …and a bare :closed-by with nothing behind it is not one of them.
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :design (assoc phased-design
                               :seams [{:what "w" :visible-how "v" :closed-by :permanent}])))))

(deftest a-two-field-seam-fails-on-write-and-survives-on-read
  ;; Strict on write, wide on read: the tightening has teeth going forward and
  ;; costs no history. A record written before :closed-by must never become
  ;; invalid — read validation swallows failures, so it would silently vanish
  ;; from the panes and from the verdict pass rather than being contradicted.
  (let [old-shape (assoc valid-design
                         :seams [{:what "the legacy path stays"
                                  :visible-how "old fn kept, marked deprecated"}])]
    (is (thrown? clojure.lang.ExceptionInfo (report/validate-event :design old-shape)))
    (is (= old-shape (report/parse-event :design old-shape)))))

(deftest a-string-invariant-survives-on-read
  (is (= valid-design (report/parse-event :design valid-design))))

(deftest invariant-normalises-both-shapes
  (is (= {:invariant "x" :holds :always} (report/invariant "x")))
  (is (= {:invariant "x" :holds :on-completion}
         (report/invariant {:invariant "x" :holds :on-completion}))))

(deftest seam-closure-renders-each-kind-and-nothing-for-a-legacy-seam
  (is (= "closed by phase — drop the old column"
         (report/seam-closure {:closed-by :phase :phase "drop the old column"})))
  (is (= "spun out as FU-12" (report/seam-closure {:closed-by :spun-out :ref "FU-12"})))
  (is (= "permanent — supported mode" (report/seam-closure {:closed-by :permanent :why "supported mode"})))
  (is (nil? (report/seam-closure {:what "w" :visible-how "v"}))))

(deftest report->markdown-phased-design-shows-the-plan
  (let [md (report/report->markdown phased-design)]
    (is (str/includes? md "## Phases"))
    (is (str/includes? md "1. both writers maintain the new column; nothing reads it"))
    (is (str/includes? md "exit (observation): shadow-read discrepancy counter flat at zero for 7 days"))
    (is (str/includes? md "live meanwhile: the old column is still written"))
    (is (str/includes? md "**point of no return**"))
    ;; the marker a reader needs to know an invariant is not expected to hold yet
    (is (str/includes? md "*(holds on completion — not at every phase boundary)*"))
    ;; and the always-invariant carries no marker
    (is (str/includes? md "- no request reads a column no writer maintains\n"))))

(deftest report->markdown-unphased-design-has-no-phases-section
  (is (not (str/includes? (report/report->markdown valid-design) "## Phases"))))

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

(deftest completed-accepts-a-design-delta-that-held
  (is (report/validate-event
       :implementation-completed
       (assoc valid-completed :design-delta {:held? true}))
      "the honest default must be free — one word, no ceremony"))

(deftest a-design-that-did-not-hold-must-name-what-deviated
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :implementation-completed
                (assoc valid-completed :design-delta {:held? false})))
      "held? false with nothing named records only that someone felt uneasy")
  (is (report/validate-event
       :implementation-completed
       (assoc valid-completed :design-delta
              {:held? false :deviations ["the rounding boundary moved to the line item"]}))))

(deftest completed-without-a-delta-still-validates
  (is (= valid-completed (report/validate-event :implementation-completed valid-completed))
      "pre-spine completions, and workstreams with no design record, stay valid"))

(deftest design-delta-is-a-field-not-a-ledger-kind
  (is (nil? (get report/event-schemas :design-delta))
      "ship/classify-outcome routes on latest-ledger-kind — a separate :design-delta
       entry would become the latest entry and misroute shipping branches to :blocked")
  (is (some? (get report/event-schemas :implementation-completed))))

(deftest completed-renders-the-delta-both-ways
  (let [held (report/report->markdown
              (assoc valid-completed :design-delta
                     {:held? true :note "boundary landed where the record said"}))
        broke (report/report->markdown
               (assoc valid-completed :design-delta
                      {:held? false :deviations ["rounding moved to the line item"]}))]
    (is (str/includes? held "## Design held"))
    (is (str/includes? held "boundary landed where the record said"))
    (is (str/includes? broke "## Design did NOT hold"))
    (is (str/includes? broke "Deviations from the record:"))
    (is (str/includes? broke "rounding moved to the line item"))))

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

(deftest validate-merged-and-ship-submitted
  (let [merged {:format :merged :pr "org/repo#42" :url "https://gh/42"
                :title "Fix rounding" :merged-at "2026-08-20T10:00:00Z"}]
    (is (= merged (report/validate-event :merged merged)))
    (is (= (dissoc merged :merged-at) (report/validate-event :merged (dissoc merged :merged-at)))
        ":merged-at is optional — gh can report it nil")
    (is (thrown? clojure.lang.ExceptionInfo
                 (report/validate-event :merged (dissoc merged :pr)))
        ":pr is the correlation key and is required"))
  (let [ship {:format :ship-submitted :session "impl-br-42"}]
    (is (= ship (report/validate-event :ship-submitted ship)))))

(deftest merged-and-ship-submitted-render
  (let [md (report/report->markdown {:format :merged :pr "org/repo#42" :url "https://gh/42"
                                     :title "Fix rounding" :merged-at "2026-08-20T10:00:00Z"})]
    (is (str/includes? md "# Merged"))
    (is (str/includes? md "org/repo#42")))
  ;; :merged carries a usable :title, so report-title leaves it to the caller;
  ;; :ship-submitted carries none, so it must name itself in the index.
  (is (nil? (report/report-title {:format :merged :pr "org/repo#42" :url "u" :title "t"})))
  (is (= "Ship submitted" (report/report-title {:format :ship-submitted :session "s"}))))

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

;; ── Baseline ────────────────────────────────────────────────────────────────
;; The area's design as it IS, authored before the change design and independent
;; of it. The schema's job is to keep it descriptive: every field has to be
;; fillable without knowing the change, which is what stops the inference being
;; bent toward the fix someone already has in mind.

(def ^:private valid-baseline
  {:format       :baseline
   :area         "order totalling — calc, the aggregate, and the invoice reader"
   :bounded-by   "everything that reads or writes a money amount on an order; the
                  render layer is out, it only formats what it is handed"
   :shape        "Line items hold exact amounts; the order aggregate is the only
                  thing that sums them; invoices read the aggregate, never lines."
   :load-bearing [{:property "a line item's amount is never rounded in place"
                   :evidence ["src/order/calc.clj:41"]}
                  {:property "the aggregate is the only summing path"
                   :evidence ["src/order/aggregate.clj:12" "src/order/invoice.clj:88"]
                   :drift    "invoice.clj re-sums defensively — copied, never decided"}]
   :extension-points [{:at "the aggregate's reducer"
                       :how "a new money kind adds a case; nothing else changes"}]
   :governing    ["two registers of data — values in motion vs state at rest"]
   :drift        ["most of this area parses at the DB edge; order/invoice re-parses"]
   :read         ["src/order/calc.clj" "src/order/aggregate.clj" "src/order/invoice.clj"]
   :unknowns     ["whether the legacy CSV importer bypasses the aggregate"]})

(deftest validate-event-accepts-a-baseline
  (is (= valid-baseline (report/validate-event :baseline valid-baseline))))

(deftest baseline-requires-at-least-one-load-bearing-property
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :baseline (assoc valid-baseline :load-bearing [])))
      "a baseline naming nothing load-bearing cannot answer whether a defect is
       implementation or design — which is the only reason it exists"))

(deftest baseline-requires-evidence-for-every-load-bearing-property
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :baseline (assoc valid-baseline
                                 :load-bearing [{:property "totals are exact" :evidence []}])))
      "a property with nothing to point at is a guess"))

(deftest baseline-requires-what-was-read
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :baseline (assoc valid-baseline :read [])))
      "an inference with no sources cannot be checked, only believed"))

(deftest baseline-requires-its-bound
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :baseline (dissoc valid-baseline :bounded-by)))
      "scoping is the first claim the record makes, and the guard against both
       reading everything and reading three files"))

(deftest baseline-refuses-to-carry-the-change
  ;; The authoring test, enforced: a field that needs to know the change has
  ;; crossed from `is` to `ought` and belongs on the design record.
  (doseq [k [:effort :invariants :standing :summary :relation]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (report/validate-event :baseline (assoc valid-baseline k :anything)))
        (str "closed map rejects " k))))

(deftest report->markdown-baseline-has-its-sections
  (let [md (report/report->markdown valid-baseline)]
    (is (str/includes? md "# Baseline — the current design"))
    (is (str/includes? md "**Area:** order totalling"))
    (is (str/includes? md "*Bounded by:"))
    (is (str/includes? md "**Governed by:** two registers"))
    (is (str/includes? md "## Load-bearing"))
    (is (str/includes? md "the aggregate is the only summing path"))
    (is (str/includes? md "`src/order/aggregate.clj:12`"))
    (is (str/includes? md "drift from the stance: invoice.clj re-sums"))
    (is (str/includes? md "## Extension points"))
    (is (str/includes? md "## Drift from the stance"))
    (is (str/includes? md "## Not determined"))
    (is (str/includes? md "## Read"))))

(deftest report->markdown-baseline-omits-empty-optional-sections
  (let [md (report/report->markdown
            (dissoc valid-baseline :extension-points :governing :drift :unknowns))]
    (is (str/includes? md "## Load-bearing"))
    (is (not (str/includes? md "**Governed by:**")))
    (is (not (str/includes? md "## Extension points")))
    (is (not (str/includes? md "## Drift from the stance")))
    (is (not (str/includes? md "## Not determined")))
    (is (not (str/includes? md "\n\n\n"))
        "an absent optional leaves no blank hole where its line would have been")))

;; ── Health — what the survey ran into ───────────────────────────────────────
;; The one field of the baseline that is a judgement rather than a reading, and
;; still an `is`. Its job is to make "leave the place cleaner than you found it"
;; a record rather than a virtue, without letting the survey start routing.

(def ^:private healthy-baseline
  (assoc valid-baseline
         :health
         [{:id          "invoice-resums"
           :observation "invoice.clj re-sums line items instead of reading the
                         aggregate, so there are two summing paths where the
                         design claims one"
           :axis        :design
           :evidence    ["src/order/invoice.clj:88"]}
          {:id          "csv-importer-untested"
           :observation "the CSV importer writes amounts with no test covering
                         rounding"
           :axis        :implementation
           :evidence    ["src/order/import.clj:14"]
           :invisibly-incomplete? true}]))

(deftest validate-event-accepts-a-baseline-with-health
  (is (= healthy-baseline (report/validate-event :baseline healthy-baseline))))

(deftest health-is-optional
  (is (= valid-baseline (report/validate-event :baseline valid-baseline))
      "a survey that ran into nothing worth recording is legitimate; the smell
       lives in the skill, not in the schema"))

(deftest health-observation-requires-an-axis
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :baseline (assoc valid-baseline
                                 :health [{:id "x" :observation "messy"
                                           :evidence ["src/a.clj:1"]}])))
      "design health and implementation health route differently — an
       unaxed observation is the useless 'a bit messy' output"))

(deftest health-observation-requires-evidence
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :baseline (assoc valid-baseline
                                 :health [{:id "x" :observation "shaky"
                                           :axis :design :evidence []}])))
      "same rule as a load-bearing property: nothing to point at is a guess"))

(deftest health-observation-rejects-an-unknown-axis
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :baseline (assoc valid-baseline
                                 :health [{:id "x" :observation "shaky"
                                           :axis :process
                                           :evidence ["src/a.clj:1"]}])))))

(deftest health-observation-cannot-carry-a-destination
  (doseq [k [:route :destination :spin-out :fix-here]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (report/validate-event
                  :baseline (assoc valid-baseline
                                   :health [{:id "x" :observation "shaky"
                                             :axis :design
                                             :evidence ["src/a.clj:1"]
                                             k :anything}])))
        (str "the baseline never routes — closed map rejects " k))))

(deftest health-ids-must-be-unique-within-a-baseline
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :baseline (assoc valid-baseline
                                 :health [{:id "dup" :observation "a" :axis :design
                                           :evidence ["src/a.clj:1"]}
                                          {:id "dup" :observation "b" :axis :design
                                           :evidence ["src/b.clj:1"]}])))
      "a duplicate id makes 'routed exactly once' unanswerable for the design
       record that cites this baseline"))

(deftest report->markdown-baseline-groups-health-by-axis
  (let [md (report/report->markdown healthy-baseline)]
    (is (str/includes? md "## Health — what the survey ran into"))
    (is (str/includes? md "### Design health"))
    (is (str/includes? md "### Implementation health"))
    (is (str/includes? md "`invoice-resums`"))
    (is (str/includes? md "`src/order/invoice.clj:88`"))
    (is (str/includes? md "invisibly incomplete"))
    (is (< (str/index-of md "### Design health")
           (str/index-of md "### Implementation health"))
        "design health first — it is the half that can change the declared
         relation, so it is what a reader is scanning for")))

(deftest report->markdown-baseline-omits-health-when-absent
  (is (not (str/includes? (report/report->markdown valid-baseline) "## Health"))))

(deftest an-index-title-is-bounded
  ;; first-line is not enough on its own: a paragraph written without newlines
  ;; is one line, and it is the whole paragraph. Every design record in this
  ;; project has a :shape like that.
  (let [long-shape (apply str (repeat 40 "one rounding boundary at the aggregate "))
        t (report/report-title (assoc valid-design :shape long-shape))]
    (is (<= (count t) 110))
    (is (str/ends-with? t "…") "cut is visible, so a reader does not read the
                                truncation as the record being terse"))
  (is (= (report/report-title valid-design)
         (report/report-title valid-design))
      "a short title is returned unchanged")
  (is (not (str/ends-with? (report/report-title valid-design) "…"))))

(deftest baseline-titles-itself-by-area
  (is (= "Baseline: order totalling — calc, the aggregate, and the invoice reader"
         (report/report-title valid-baseline))
      "the ledger index shows what was surveyed, not the first line of its shape")
  (is (= "Baseline: order totalling"
         (report/report-title (assoc valid-baseline :area "order totalling\nand its readers")))
      "one line per entry — the index is a table, and :area is prose that wraps"))

(def ^:private valid-intent
  {:format    :intent
   :goal      "Checkout totals match the invoice to the cent."
   :done-when ["a multi-line order's total equals the sum of its invoice lines"
               "no support ticket about a one-cent discrepancy for a full month"]
   :context   "Reported three times this quarter; each was closed as unreproducible."})

(deftest validate-event-accepts-an-intent
  (is (= valid-intent (report/validate-event :intent valid-intent))))

(deftest intent-requires-something-that-would-make-it-done
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :intent (assoc valid-intent :done-when [])))
      "a goal nobody can fail to meet cannot tell an over-serving design from a
       right-sized one, which is the whole job of the goal-served check"))

(deftest intent-refuses-to-carry-the-answer
  (doseq [k [:shape :effort :layers :direction :invariants]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (report/validate-event :intent (assoc valid-intent k :anything)))
        (str "a field that needs the change belongs on the design record — " k))))

(deftest report->markdown-intent-has-its-sections
  (let [md (report/report->markdown valid-intent)]
    (is (str/includes? md "# Intent — what this is for"))
    (is (str/includes? md "## Done when"))
    (is (str/includes? md "no support ticket"))
    (is (str/includes? md "## Context"))))

(deftest design-requires-an-intent-citation
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :design (dissoc valid-design :intent)))
      "the last yardstick that was not written down is now required, the same
       way :baseline is"))

(deftest a-pre-intent-design-is-unwritable-but-still-readable
  (let [pre (dissoc valid-design :intent)]
    (is (thrown? clojure.lang.ExceptionInfo (report/validate-event :design pre))
        "strict on write")
    (is (= pre (report/parse-event :design pre))
        "wide on read — without this era every existing design would fail
         validation, and ws/latest-entry swallows that, so they would not be
         contradicted, they would simply stop being there")))

(deftest all-three-design-eras-read
  (doseq [[label r] {"current"    valid-design
                     "pre-intent" (dissoc valid-design :intent)
                     "pre-baseline" legacy-design}]
    (is (= r (report/parse-event :design r)) label)))

;; ── Routes — the other half of "the baseline never routes" ──────────────────
;; The survey observes and cannot route; the design routes and cannot observe.
;; The schema's job here is the asymmetry: doing the work needs no defence,
;; every form of not-doing-it-here does.

(deftest design-accepts-routes-for-each-destination
  (let [routed (assoc valid-design
                      :routes [{:health-id "a" :to :fix-here}
                               {:health-id "b" :to :constrains
                                :why "this change may not leave it half-applied"}
                               {:health-id "c" :to :spin-out
                                :why "revealed, not created; cheap to resume cold"
                                :ref "FU-88"}
                               {:health-id "d" :to :declined
                                :why "cold corner, nothing walks through here"}])]
    (is (= routed (report/validate-event :design routed)))))

(deftest fix-here-needs-no-reason
  (is (report/validate-event :design (assoc valid-design
                                            :routes [{:health-id "a" :to :fix-here}]))
      "the conservative default defends itself — you are doing the work"))

(deftest not-doing-it-here-always-needs-a-reason
  (doseq [to [:constrains :declined]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (report/validate-event :design (assoc valid-design
                                                       :routes [{:health-id "a" :to to}])))
        (str (name to) " without a why is a shrug, not a decision"))))

(deftest a-spin-out-needs-a-ref
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :design (assoc valid-design
                               :routes [{:health-id "a" :to :spin-out
                                         :why "cheap to resume cold"}])))
      "no spin-out without a ref — 'later' in a PR comment is a wish"))

(deftest routes-reject-an-unknown-destination
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :design (assoc valid-design
                               :routes [{:health-id "a" :to :maybe-someday}])))))

(deftest report->markdown-design-renders-its-routes
  (let [md (report/report->markdown
            (assoc valid-design
                   :routes [{:health-id "invoice-resums" :to :spin-out
                             :why "revealed, not created" :ref "FU-88"}]))]
    (is (str/includes? md "## Routed from the baseline's health"))
    (is (str/includes? md "`invoice-resums`"))
    (is (str/includes? md "**spin-out**"))
    (is (str/includes? md "(FU-88)"))))

(deftest report->markdown-design-omits-routes-when-absent
  (is (not (str/includes? (report/report->markdown valid-design)
                          "Routed from the baseline's health"))))

;; ── The baseline relation, and the write/read split ─────────────────────────

(deftest design-requires-a-baseline-relation
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event :design (dissoc valid-design :baseline)))
      "the requirement is the teeth: a session that skips the baseline cannot
       file the design record that was supposed to be judged against it"))

(deftest design-no-longer-accepts-assumes
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :design (assoc valid-design :assumes [{:about "x" :read ["y"]}])))
      "the inference has exactly one home now, and it is not inside the record
       that also states the commitment"))

(deftest baseline-revisit-must-name-what-it-breaks
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :design (assoc valid-design
                               :baseline {:seq 1 :relation :revisit
                                          :note "the aggregate has to go"})))
      "without :breaks, \"the design needs revisiting\" is a feeling")
  (is (report/validate-event
       :design (assoc valid-design
                      :baseline {:seq 1 :relation :revisit
                                 :breaks ["the aggregate is the only summing path"]
                                 :note "invoices need their own total"}))))

(deftest baseline-extends-requires-a-note-and-within-does-not
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate-event
                :design (assoc valid-design :baseline {:seq 1 :relation :extends})))
      "adding a commitment without saying why is the silent-erosion case")
  (is (report/validate-event
       :design (assoc valid-design
                      :baseline {:seq 1 :relation :extends
                                 :at "the aggregate's reducer"
                                 :note "a new money kind adds a case"})))
  (is (report/validate-event
       :design (assoc valid-design :baseline {:seq 1 :relation :within}))
      ":within is the normal case and carries no obligation"))

(deftest baseline-relation-is-not-standing
  ;; The two questions are independent: this change conforms to the stance and
  ;; still tears up the current design. A schema that let one stand in for the
  ;; other would collapse exactly the distinction the field was added for.
  (is (report/validate-event
       :design (assoc valid-design
                      :standing {:relation :conforms}
                      :baseline {:seq 1 :relation :revisit
                                 :breaks ["the aggregate is the only summing path"]
                                 :note "conforming to the stance says nothing about this"}))))

(deftest legacy-design-is-unwritable-but-still-readable
  (is (thrown? clojure.lang.ExceptionInfo (report/validate-event :design legacy-design))
      "the write contract tightened")
  (is (= legacy-design (report/parse-event :design legacy-design))
      "the read contract did not — an entry is immutable, so the question is
       whether it was valid when written")
  (is (= valid-design (report/parse-event :design valid-design))
      "a record satisfying both is read as current"))

(deftest read-contract-still-refuses-what-was-never-valid
  (is (thrown? clojure.lang.ExceptionInfo
               (report/parse-event :design (dissoc legacy-design :invariants)))
      "wider is not lax: no era of this schema allowed a design with no invariants"))

(deftest report->markdown-renders-a-revisit-with-its-breaks
  (let [md (report/report->markdown
            (assoc valid-design
                   :baseline {:seq 2 :relation :revisit
                              :breaks ["the aggregate is the only summing path"]
                              :note "invoices need their own total"}))]
    (is (str/includes? md "**Against the baseline:** revisit (entry 2)"))
    (is (str/includes? md "Breaks: the aggregate is the only summing path"))
    (is (str/includes? md "invoices need their own total"))))

(deftest report->markdown-still-renders-a-legacy-design
  (let [md (report/report->markdown legacy-design)]
    (is (str/includes? md "# Design"))
    (is (str/includes? md "## Assumes"))
    (is (not (str/includes? md "Against the baseline")))))
