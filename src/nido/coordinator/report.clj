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

(def Direction
  [:map {:closed true}
   [:label      string?]
   [:shape      string?]
   [:effort     [:enum :XS :S :M :L :XL]]
   [:confidence Confidence]])

(def NotionWrites
  "Nil/omitted for Slack runs (no Notion writes)."
  [:map {:closed true}
   [:type               [:maybe string?]]
   [:effort             [:enum :XS :S :M :L :XL]]
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
   [:trail         [:vector [:map {:closed true}
                             [:ref  string?]
                             [:note string?]]]]]) ; §5 log-only

(defn validate
  "Return `report` if it conforms to TriageReport; else throw ex-info carrying a
   malli explain under :explain (the bb task prints it so the skill can fix + retry)."
  [report]
  (if (m/validate TriageReport report)
    report
    (throw (ex-info "Invalid triage report"
                    {:explain (m/explain TriageReport report) :report report}))))

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
