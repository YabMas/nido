(ns nido.coordinator.report
  "Typed triage report: the schema the triage-bug skill emits, validated at the
   ledger boundary, plus a format-agnostic markdown renderer. See spec
   docs/superpowers/specs/2026-06-22-typed-triage-report-and-action-primitive-design.md."
  (:require
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

(def event-schemas
  "Entry :kind → its Malli schema. Drives ledger-boundary validation + rendering.
   A :kind absent here is stored as verbatim markdown (legacy / freeform)."
  {:triage                   TriageReport
   :implementation-plan      ImplementationPlan
   :implementation-completed ImplementationCompleted
   :blocker                  Blocker
   :pr-opened                PrOpened})

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

(defn report->markdown
  "Render a `:format`-tagged report payload to markdown. :markdown → its :markdown;
   :triage-report → full markdown (§5 last); nil/other → \"\"."
  [report]
  (case (:format report)
    :markdown      (or (:markdown report) "")
    :triage-report (triage->markdown report)
    ""))
