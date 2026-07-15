(ns nido.coordinator.report
  "Typed triage report: the schema the triage-bug skill emits, validated at the
   ledger boundary, plus a format-agnostic markdown renderer. See spec
   docs/superpowers/specs/2026-06-22-typed-triage-report-and-action-primitive-design.md."
  (:require
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [malli.core :as m]))

(def Confidence
  [:map {:closed true}
   [:level  [:enum :high :medium :low]]
   [:reason string?]])

(def Effort
  "Concrete T-shirt size."
  [:enum :XS :S :M :L :XL])

(def TriageEffort
  "Triage may decline to size when the implementation direction is ambiguous —
   :squirrel is the joker that defers sizing to the implementation-plan event."
  [:enum :XS :S :M :L :XL :squirrel])

(def Direction
  [:map {:closed true}
   [:label      string?]
   [:shape      string?]
   [:effort     TriageEffort]
   [:confidence Confidence]])

(def NotionWrites
  "Nil/omitted for Slack runs (no Notion writes)."
  [:map {:closed true}
   [:type               [:maybe string?]]
   [:effort             TriageEffort]
   [:status-transition  {:optional true} [:maybe [:tuple string? string?]]]
   [:title              string?]
   [:description-prepend string?]])

(def TriageReport
  "The §1–3 + §5 report. No §4 field — the dismiss recommendation is dropped at the
   schema level. `:at` is NOT here: the ledger stamps it at read time."
  [:map {:closed true}
   [:format        [:= :triage-report]]
   [:ticket-key    string?]
   [:determination [:enum :bug :not-a-bug :needs-info]]
   [:title         string?]          ; §1 enriched title
   [:summary       string?]          ; §1 enriched description (markdown text)
   [:confidence    Confidence]       ; §1
   [:directions    [:vector Direction]]   ; §2
   [:notion-writes [:maybe NotionWrites]] ; §3 — nil for slack
   [:defer-note    {:optional true} string?]   ; why the plan was deferred (paired with :squirrel)
   [:trail         [:vector [:map {:closed true}
                             [:ref  string?]
                             [:note string?]]]]]) ; §5 log-only

(def ProposedTicket
  "A grounded ticket the triage-slack skill proposes for human approval before
   creation in the Notion Task DB."
  [:map {:closed true}
   [:format      [:= :proposed-ticket]]
   [:title       :string]
   [:description :string]           ; grounded findings → the page body
   [:ticket-type :string]           ; Notion Type option, e.g. "bug"
   [:priority    {:optional true} [:maybe :string]]  ; Notion Priority option or nil
   [:source-url  :string]           ; the Slack permalink
   [:trail       {:optional true} [:maybe :string]]])

(def ImplementationPlan
  "A high-level implementation plan — authored by triage (obvious) or the impl
   session (deferred); resolves a triage :squirrel into a concrete direction + effort."
  [:map {:closed true}
   [:format    [:= :implementation-plan]]
   [:summary   string?]
   [:direction string?]
   [:effort    Effort]
   [:steps     {:optional true} [:vector string?]]])

(def ImplementationCompleted
  [:map {:closed true}
   [:format    [:= :implementation-completed]]
   [:summary   string?]
   [:artifacts [:vector [:map {:closed true}
                         [:kind [:enum :commit :pr :branch]]
                         [:ref  string?]
                         [:url  {:optional true} string?]]]]
   [:open      {:optional true} [:vector string?]]])

(def Blocker
  [:map {:closed true}
   [:format  [:= :blocker]]
   [:summary string?]
   [:needs   string?]])

(def PrOpened
  [:map {:closed true}
   [:format  [:= :pr-opened]]
   [:url     string?]
   [:title   string?]
   [:summary {:optional true} string?]])

(def ReviewReport
  "The review-loop outcome as one terminal ledger event (verdict + counts). Points
   at the full report.json rather than embedding it. `:at` is stamped by the ledger."
  [:map {:closed true}
   [:format             [:= :review-report]]
   [:status             [:enum :converged :escalated :clean :no-progress
                               :max-iters :review-failed :dry-run
                               :fix-noop :judge-indeterminate]]
   [:base               string?]
   [:base-rev           [:maybe string?]]
   [:rounds             int?]
   [:findings-fixed     int?]
   [:findings-remaining int?]
   [:report-path        [:maybe string?]]
   ;; Dormant extension point: no caller populates :summary yet (review-event omits it).
   ;; Kept for a future emitter wanting a one-line human note on the timeline card.
   [:summary            {:optional true} string?]])

(def FindingItem
  [:map {:closed true}
   [:id       string?]
   [:summary  string?]
   [:severity [:enum :blocker :tweak :nice-to-have]]
   [:area     {:optional true} string?]])

(def FindingsRound
  "One staging-review findings round, filed by a human after reviewing the
   deployed staging build. Immutable snapshot; live per-item resolution status
   is tracked on the workstream record (:findings). `:at` is stamped by the
   ledger at read time."
  [:map {:closed true}
   [:format      [:= :findings]]
   [:round       int?]                        ; 1-based; increments per round on this workstream
   [:staging-ref {:optional true} string?]    ; where it was reviewed (URL / build / env)
   [:note        {:optional true} string?]    ; overall reviewer note
   [:items       [:vector FindingItem]]])

(def event-schemas
  "Entry :kind → its Malli schema. Drives ledger-boundary validation + rendering.
   A :kind absent here is stored as verbatim markdown (legacy / freeform)."
  {:triage                   TriageReport
   :implementation-plan      ImplementationPlan
   :implementation-completed ImplementationCompleted
   :blocker                  Blocker
   :pr-opened                PrOpened
   :review                   ReviewReport
   :findings                 FindingsRound
   :proposed-ticket          ProposedTicket})

(defn validate-event
  "Validate `report` (parsed EDN) against the schema registered for entry `kind`.
   Returns the report on success; throws ex-info carrying a malli :explain on mismatch
   (the bb task prints it so the emitting skill can fix + retry). Throws for an
   unregistered kind."
  [kind report]
  (let [schema (or (event-schemas kind)
                   (throw (ex-info "No schema for entry kind" {:kind kind})))]
    (if (m/validate schema report)
      report
      (throw (ex-info "Invalid event report"
                      {:explain (m/explain schema report) :report report :kind kind})))))

(defn validate
  "Backward-compatible triage validator — delegates to (validate-event :triage report)."
  [report]
  (validate-event :triage report))

(defn entry-payload
  "For a ledger append: given an entry `kind` and raw `content` string, return
   [ext payload]. A registered (typed) kind parses `content` as EDN, validates it
   (throws ex-info with :explain on mismatch), and returns [\"edn\" <pprinted report>].
   Any other kind returns [\"md\" content] verbatim."
  [kind content]
  (if (event-schemas kind)
    (let [report (validate-event kind (edn/read-string content))]
      ["edn" (with-out-str (pprint/pprint report))])
    ["md" content]))

(defn- triage->markdown [{:keys [title determination summary confidence
                                 directions notion-writes trail]}]
  (str/join
   "\n"
   (concat
    [(str "# Triage: " title)
     (str "**Determination:** " (name determination)
          "  ·  **Confidence:** " (name (:level confidence)) " — " (:reason confidence))
     ""
     summary
     ""
     "## Solution directions"]
    (for [{:keys [label shape effort confidence]} directions]
      (str "- **" label "** — " shape ". Effort: " (name effort)
           ". Confidence: " (name (:level confidence)) " — " (:reason confidence)))
    (when notion-writes
      (remove nil?
              ["" "## Proposed Notion writes (on `apply`)"
               (str "- Type: " (or (:type notion-writes) "unchanged"))
               (str "- Effort: " (name (:effort notion-writes)))
               (when-let [[from to] (:status-transition notion-writes)]
                 (str "- Status: `" from "` → `" to "`"))
               (str "- Title: " (:title notion-writes))]))
    ["" "## Investigation trail"]
    (for [{:keys [ref note]} trail]
      (str "- `" ref "` — " note)))))

(defn- first-line
  "First non-blank, trimmed line of `s`, or nil."
  [s]
  (some->> (some-> s str/split-lines) (map str/trim) (some not-empty)))

(defn- plan->markdown [{:keys [summary direction effort steps]}]
  (str/join "\n"
    (concat
     ["# Implementation plan"
      (str "**Direction:** " direction "  ·  **Effort:** " (name effort))
      "" summary]
     (when (seq steps) (concat ["" "## Steps"] (for [s steps] (str "- " s)))))))

(defn- completed->markdown [{:keys [summary artifacts open]}]
  (str/join "\n"
    (concat
     ["# Implementation completed" "" summary "" "## Artifacts"]
     (for [{:keys [kind ref url]} artifacts]
       (str "- " (name kind) " `" ref "`" (when url (str " — " url))))
     (when (seq open) (concat ["" "## Still open"] (for [o open] (str "- " o)))))))

(defn- blocker->markdown [{:keys [summary needs]}]
  (str/join "\n" ["# Blocker" "" summary "" "## Needs" needs]))

(defn- pr-opened->markdown [{:keys [url title summary]}]
  (str/join "\n"
    (remove nil? ["# PR opened" (str "**" title "** — " url) (when summary (str "\n" summary))])))

(defn- review->markdown [{:keys [status base base-rev rounds findings-fixed
                                 findings-remaining report-path summary]}]
  (str/join "\n"
    (remove nil?
      [(str "# Review: " (name status))
       (str findings-fixed " fixed  ·  " findings-remaining " remaining  ·  "
            rounds " rounds")
       (str "base " base (when base-rev (str "@" base-rev)))
       (when summary (str "\n" summary))
       (when report-path (str "\nfull report → " report-path))])))

(defn- findings->markdown [{:keys [round staging-ref note items]}]
  (str/join "\n"
    (remove nil?
      (concat
        [(str "# Findings round " round)
         (str (count items) " item(s)"
              (when staging-ref (str "  ·  staging → " staging-ref)))
         (when note (str "\n" note))
         ""]
        (for [{:keys [id summary severity area]} items]
          (str "- **" (name severity) "** "
               (when area (str "(" area ") ")) "[" id "] " summary))))))

(defn- proposed-ticket->markdown [{:keys [title description ticket-type priority source-url trail]}]
  (str/join "\n"
    (remove nil?
      [(str "# Proposed ticket: " title)
       (str "**Type:** " ticket-type
            (when priority (str "  ·  **Priority:** " priority)))
       (str "Source: " source-url)
       ""
       description
       (when trail (str "\n## Investigation trail\n" trail))])))

(defn report->markdown
  "Render a `:format`-tagged report payload to markdown. Each event type → its own
   headed markdown; :markdown → its body; nil/unknown → \"\"."
  [report]
  (case (:format report)
    :markdown                 (or (:markdown report) "")
    :triage-report            (triage->markdown report)
    :implementation-plan      (plan->markdown report)
    :implementation-completed (completed->markdown report)
    :blocker                  (blocker->markdown report)
    :pr-opened                (pr-opened->markdown report)
    :review-report            (review->markdown report)
    :findings                 (findings->markdown report)
    :proposed-ticket          (proposed-ticket->markdown report)
    ""))

(defn report-title
  "Index title for the typed events that carry no top-level :title — plan / completed
   / blocker. nil otherwise (triage, pr-opened and markdown carry a usable :title that
   the caller falls back to)."
  [report]
  (case (:format report)
    :implementation-plan      (:direction report)
    :implementation-completed (first-line (:summary report))
    :blocker                  (or (:needs report) (first-line (:summary report)))
    :review-report            (str "Review: " (name (:status report)))
    :findings                 (str "Findings round " (:round report)
                                   " (" (count (:items report)) " items)")
    nil))
