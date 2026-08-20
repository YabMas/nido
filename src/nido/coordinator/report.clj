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

(def AppDomain
  "Notion App Domain multi_select value used for routing."
  [:enum "Student" "Teacher" "Backend" "Misc"])

(def Owner
  "Semantic triage owner; the skill maps it to a Notion Ball Holder user-id."
  [:enum :ataberk :eric :jaap])

(def owner->user-id
  "Semantic triage owner → Notion Ball Holder user-id. Single source; the skill emits
   the keyword and nido resolves it at apply time."
  {:ataberk "3eb98667-d12e-4e9e-9342-48fec803b571"
   :eric    "955b4c25-7bce-4ca2-ab5e-d99acbcd423a"
   :jaap    "169d872b-594c-8160-b432-000250f98e86"})

(def Routing
  "Where triage routed the report. nil on a TriageReport means no routing (Slack,
   or a legacy pre-routing report). :depth :shallow = Ball Holder + App Domain only
   (Notion status stays Needs verification); :deep = full triage (status → Not started)."
  [:map {:closed true}
   [:owner      Owner]
   [:app-domain AppDomain]
   [:depth      [:enum :shallow :deep]]])

(def NotionWrites
  "Nil/omitted for Slack runs (no Notion writes)."
  [:map {:closed true}
   [:type               [:maybe string?]]
   [:effort             TriageEffort]
   [:status-transition  {:optional true} [:maybe [:tuple string? string?]]]
   [:title              string?]
   [:description-prepend string?]])

(def Violation
  "A concrete rule this behaviour breaks, with where the rule is written and the
   evidence. Cites the *checkable* layer — a lane or a reference doc — never the
   stance: a priming document cannot adjudicate a line, and asking it to produces
   confabulated specificity."
  [:map {:closed true}
   [:rule     string?]
   [:source   string?]      ; e.g. "docs/reference/malli.md" / "lane-malli"
   [:evidence string?]])    ; file:line

(def DesignFrame
  "The design reading of a triage deepdive. :defect-layer is the bit that routes
   the work: :implementation means the design is right and the code doesn't honour
   it (a fix), :design means the code faithfully implements a design that is wrong
   (a decision). :unknown is honest for a shallow route, which does not root-cause.

   :governing cites the stance — what frames this area — because that is what makes
   :defect-layer answerable at all. :violated cites the checkable layer, because
   that is the only layer a diff can actually break."
  [:map {:closed true}
   [:defect-layer [:enum :implementation :design :unknown]]
   [:governing    {:optional true} [:vector string?]]
   [:violated     {:optional true} [:vector Violation]]
   [:note         {:optional true} string?]])

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
   [:routing       {:optional true} [:maybe Routing]] ; §2 — absent on pre-routing reports, nil for Slack
   [:design-frame  {:optional true} DesignFrame] ; absent on pre-design-spine reports
   [:directions    [:vector Direction]]   ; §2
   [:notion-writes [:maybe NotionWrites]] ; §3 — nil for slack
   [:defer-note    {:optional true} string?]   ; why the plan was deferred (paired with :squirrel)
   [:trail         [:vector [:map {:closed true}
                             [:ref  string?]
                             [:note string?]]]]]) ; §5 log-only

(def ^:private proposed-ticket-common
  "Fields shared by both proposed-ticket templates."
  [[:format      [:= :proposed-ticket]]
   [:title       :string]
   [:ticket-type :string]                        ; Notion Type: "bug" | "improvement"
   [:priority    {:optional true} [:maybe :string]]
   [:source-url  :string]])

(def BugProposal
  "Bug template: a defect, root-caused. :fix carries the file:line targets."
  (into [:map {:closed true}]
        (concat proposed-ticket-common
                [[:problem    :string]
                 [:root-cause :string]
                 [:fix        :string]
                 [:watch-out  {:optional true} [:maybe :string]]])))

(def ChangeProposal
  "Improvement template: a request for new/better behavior. :proposed-change
   carries the file:line touchpoints."
  (into [:map {:closed true}]
        (concat proposed-ticket-common
                [[:request         :string]
                 [:proposed-change :string]
                 [:rationale       :string]
                 [:watch-out       {:optional true} [:maybe :string]]])))

(def ProposedTicket
  "A grounded ticket the triage-slack skill proposes for human approval. The
   template is chosen by :ticket-type — \"bug\" (problem/root-cause/fix) or
   \"improvement\" (request/proposed-change/rationale). No default: an unlisted
   type is rejected, forcing the skill to classify. Rendered by
   proposed-ticket->markdown; the render is BOTH the gate card and the created
   Notion ticket body (compact everywhere)."
  [:multi {:dispatch :ticket-type}
   ["bug"         BugProposal]
   ["improvement" ChangeProposal]])

(def ^:private standing-principles
  [:principles {:optional true} [:vector string?]])

(def Standing
  "How this change relates to the project's stance (its durable architectural
   convictions). :extends and :challenges MUST carry the note: a commitment added
   or contradicted without saying why is exactly the silent-erosion case the
   relation exists to prevent. :conforms is the normal case and needs no note."
  [:multi {:dispatch :relation}
   [:conforms   [:map {:closed true}
                 [:relation [:= :conforms]]
                 standing-principles]]
   [:extends    [:map {:closed true}
                 [:relation [:= :extends]]
                 standing-principles
                 [:note string?]]]
   [:challenges [:map {:closed true}
                 [:relation [:= :challenges]]
                 standing-principles
                 [:note string?]]]])

(def Assumption
  "One inferred fact about the area's *current* design, plus what it was read
   from. The current design is deliberately unwritten — each change infers the
   slice it needs — so this is where that inference is captured rather than lost
   with the session. :drift names where the current design departs from the
   stance: is-vs-ought, stated instead of silently codified."
  [:map {:closed true}
   [:about string?]
   [:read  [:vector string?]]
   [:drift {:optional true} string?]])

(def Rejected
  [:map {:closed true}
   [:alternative string?]
   [:why-not     string?]])

(def Layer
  "One intended layer of the vertical cut — claim + review mode only. Bookmarks,
   slugs and ordering are /stack's mechanics, not the design's claim."
  [:map {:closed true}
   [:claim string?]
   [:mode  [:enum :mechanical :judgment]]])

(def Seam
  "Deliberate, visible incompleteness. /spin-out's veto turns on this: invisibly
   incomplete is a defect, visibly incomplete is a decision — so say how a reader
   sees it."
  [:map {:closed true}
   [:what        string?]
   [:visible-how string?]])

(def Supersedes
  "Set when this record amends one the review found wrong. The superseded entry
   stays in the ledger — a design is amended and cited, never silently rewritten."
  [:map {:closed true}
   [:seq int?]
   [:why string?]])

(def DesignVision
  "The high-level design one workstream commits to — authored by triage (obvious)
   or the impl session (deferred); resolves a triage :squirrel into a concrete
   effort. Replaces ImplementationPlan, and drops its :steps: a step list is
   working memory, and the ledger holds what survives the session.

   :invariants is required and non-empty on purpose. It is what the review arbiter
   checks findings against; a design that names none is unfalsifiable, and every
   finding against it becomes a matter of taste."
  [:map {:closed true}
   [:format     [:= :design]]
   [:summary    string?]
   [:shape      string?]
   [:invariants [:vector {:min 1} string?]]
   [:standing   Standing]
   [:assumes    {:optional true} [:vector Assumption]]
   [:rejected   {:optional true} [:vector Rejected]]
   [:layers     {:optional true} [:vector Layer]]
   [:seams      {:optional true} [:vector Seam]]
   [:open       {:optional true} [:vector string?]]
   [:supersedes {:optional true} Supersedes]
   [:effort     Effort]])

(def ImplementationPlan
  "LEGACY — superseded by DesignVision (:design). Kept registered so ledgers
   written before the cutover still read + render as typed events; nothing emits
   this kind any more."
  [:map {:closed true}
   [:format    [:= :implementation-plan]]
   [:summary   string?]
   [:direction string?]
   [:effort    Effort]
   [:steps     {:optional true} [:vector string?]]])

(def DesignDelta
  "Did what landed match what the design record said? Asked once, at ship time,
   while the author still holds the whole change — a week later it costs a re-read.

   :held? false REQUIRES :deviations: a design that didn't hold, with nothing
   named, records only that someone felt uneasy. The point of the field is that
   the record stops aging into fiction — the next change in this area infers its
   :assumes from code, and declares :conforms against a stance nobody re-checked."
  [:multi {:dispatch :held?}
   [true  [:map {:closed true}
           [:held? [:= true]]
           [:deviations {:optional true} [:vector string?]]
           [:note {:optional true} string?]]]
   [false [:map {:closed true}
           [:held? [:= false]]
           [:deviations [:vector {:min 1} string?]]
           [:note {:optional true} string?]]]])

(def ImplementationCompleted
  "NOTE: :design-delta is a FIELD here, deliberately, not a ledger kind of its
   own. nido.coordinator.ship/classify-outcome routes a merge Run by
   latest-ledger-kind — a separate :design-delta entry would become the latest
   entry, miss the :implementation-completed case, and misroute shipping
   branches to :blocked."
  [:map {:closed true}
   [:format       [:= :implementation-completed]]
   [:summary      string?]
   [:artifacts    [:vector [:map {:closed true}
                            [:kind [:enum :commit :pr :branch]]
                            [:ref  string?]
                            [:url  {:optional true} string?]]]]
   [:design-delta {:optional true} DesignDelta]
   [:open         {:optional true} [:vector string?]]])

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

(def Merged
  "The landing, appended by the GitHub poller at the moment it closes the
   workstream (nido.coordinator.github-merge/react-to-merge!).

   Nido-emitted, and every field is something the poller already holds — nothing
   here needs judgement. That is the point: the events an agent has to remember
   to write are the events that go missing (28 workstreams carried a PR ref and
   13 carried the matching :pr-opened), so the one event that ends the timeline
   is written by the code that ends the work."
  [:map {:closed true}
   [:format    [:= :merged]]
   [:pr        string?]                      ; owner/repo#number — the correlation key
   [:url       string?]
   [:title     string?]
   [:merged-at {:optional true} [:maybe string?]]])

(def ShipSubmitted
  "The branch handed to the merge lane by `nido ship`. Carries no judgement — the
   session name is the whole fact — but it is load-bearing twice over: it marks
   where a shipment began on the timeline, and it RESETS the ledger fingerprint
   that nido.coordinator.ship/classify-outcome reads. Without it a re-ship after a
   halt inherits the previous attempt's trailing :implementation-completed, and a
   drive-home that writes nothing at all reads as success."
  [:map {:closed true}
   [:format  [:= :ship-submitted]]
   [:session string?]])

(def ReviewReport
  "The review-loop outcome as one terminal ledger event (verdict + counts). Points
   at the full report.json rather than embedding it. `:at` is stamped by the ledger."
  [:map {:closed true}
   [:format             [:= :review-report]]
   [:status             [:enum :converged :escalated :clean :no-progress
                               :max-iters :review-failed :dry-run
                               :fix-noop :arbiter-indeterminate
                               ;; pre-rename ledger entries stay readable
                               :judge-indeterminate]]
   [:base               string?]
   [:base-rev           [:maybe string?]]
   [:rounds             int?]
   [:findings-fixed     int?]
   [:findings-remaining int?]
   [:report-path        [:maybe string?]]
   ;; Dormant extension point: no caller populates :summary yet (review-event omits it).
   ;; Kept for a future emitter wanting a one-line human note on the timeline card.
   [:summary            {:optional true} string?]])

(def ClassifiedFinding
  [:map {:closed true}
   [:finding string?]
   [:as      [:enum :implementation :design :stance]]])

(def BrokenInvariant
  [:map {:closed true}
   [:invariant string?]
   [:finding   string?]])

(def DesignVerdict
  "Whether a review round's findings were the ironing-out of implementation
   details of a sound design, or evidence the design itself is wrong. Emitted
   after the rounds terminate, judged against the workstream's :design record.

   :sound is the expected case and names the invariants this round CONFIRMED —
   trust accumulating, rather than a clean run evaporating. :strained exists so
   the gap between fine and wrong isn't rounded to fine every time, which is how
   a design decays with nobody deciding to let it. :invalidated and
   :standing-challenged are decisions, not fixes, so both require :needs — the
   question put to the human."
  [:multi {:dispatch :verdict}
   [:sound
    [:map {:closed true}
     [:format [:= :design-verdict]] [:verdict [:= :sound]]
     [:round int?] [:design-seq {:optional true} [:maybe int?]]
     [:reason string?]
     [:invariants-held {:optional true} [:vector string?]]
     [:findings-classified {:optional true} [:vector ClassifiedFinding]]]]
   [:strained
    [:map {:closed true}
     [:format [:= :design-verdict]] [:verdict [:= :strained]]
     [:round int?] [:design-seq {:optional true} [:maybe int?]]
     [:reason string?]
     [:invariants-held {:optional true} [:vector string?]]
     [:invariants-broken {:optional true} [:vector BrokenInvariant]]
     [:findings-classified {:optional true} [:vector ClassifiedFinding]]]]
   [:invalidated
    [:map {:closed true}
     [:format [:= :design-verdict]] [:verdict [:= :invalidated]]
     [:round int?] [:design-seq {:optional true} [:maybe int?]]
     [:reason string?]
     [:invariants-broken [:vector {:min 1} BrokenInvariant]]
     [:findings-classified {:optional true} [:vector ClassifiedFinding]]
     [:needs string?]]]
   [:standing-challenged
    [:map {:closed true}
     [:format [:= :design-verdict]] [:verdict [:= :standing-challenged]]
     [:round int?] [:design-seq {:optional true} [:maybe int?]]
     [:reason string?]
     [:invariants-broken {:optional true} [:vector BrokenInvariant]]
     [:findings-classified {:optional true} [:vector ClassifiedFinding]]
     [:needs string?]]]])

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
   :design                   DesignVision
   :implementation-plan      ImplementationPlan
   :implementation-completed ImplementationCompleted
   :blocker                  Blocker
   :pr-opened                PrOpened
   :merged                   Merged
   :ship-submitted           ShipSubmitted
   :review                   ReviewReport
   :design-verdict           DesignVerdict
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

(def ^:private owner-display
  {:ataberk "Ataberk" :eric "Eric" :jaap "Jaap"})

(defn- triage->markdown [{:keys [title determination summary confidence
                                 routing design-frame directions notion-writes trail]}]
  (str/join
   "\n"
   (concat
    [(str "# Triage: " title)
     (str "**Determination:** " (name determination)
          "  ·  **Confidence:** " (name (:level confidence)) " — " (:reason confidence))]
    (when routing
      [(str "**Routing:** " (owner-display (:owner routing) (name (:owner routing)))
            " · " (:app-domain routing) " · " (name (:depth routing)))])
    ["" summary ""]
    (when-let [{:keys [defect-layer governing violated note]} design-frame]
      (concat
       ["## Design frame"
        (str "**Defect layer:** " (name defect-layer)
             (when note (str " — " note)))]
       (when (seq governing)
         [(str "Governed by: " (str/join ", " governing))])
       (when (seq violated)
         (cons "Violates:"
               (for [{:keys [rule source evidence]} violated]
                 (str "- " rule " (" source ") — `" evidence "`"))))
       [""]))
    (when (seq directions)
      (cons "## Solution directions"
            (for [{:keys [label shape effort confidence]} directions]
              (str "- **" label "** — " shape ". Effort: " (name effort)
                   ". Confidence: " (name (:level confidence)) " — " (:reason confidence)))))
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

(defn- design->markdown
  [{:keys [summary shape invariants standing assumes rejected layers seams open
           supersedes effort]}]
  (str/join
   "\n"
   (concat
    ["# Design"
     (str "**Stance:** " (name (:relation standing))
          (when-let [ps (seq (:principles standing))]
            (str " — " (str/join ", " ps)))
          "  ·  **Effort:** " (name effort))]
    (when-let [n (:note standing)] [(str "> " n)])
    (when supersedes
      [(str "*Supersedes entry " (:seq supersedes) " — " (:why supersedes) "*")])
    ["" summary "" "## Shape" shape "" "## Invariants"]
    (for [i invariants] (str "- " i))
    (when (seq assumes)
      (cons "\n## Assumes — the current design, as inferred"
            (for [{:keys [about read drift]} assumes]
              (str "- " about
                   (when (seq read)
                     (str " — read: " (str/join ", " (map #(str "`" % "`") read))))
                   (when drift (str "\n  - drift from the stance: " drift))))))
    (when (seq rejected)
      (cons "\n## Rejected"
            (for [{:keys [alternative why-not]} rejected]
              (str "- **" alternative "** — " why-not))))
    (when (seq layers)
      (cons "\n## Intended layers"
            (map-indexed (fn [i {:keys [claim mode]}]
                           (str (inc i) ". " claim " *(" (name mode) ")*"))
                         layers)))
    (when (seq seams)
      (cons "\n## Seams"
            (for [{:keys [what visible-how]} seams]
              (str "- " what " — visible as: " visible-how))))
    (when (seq open)
      (cons "\n## Open" (for [o open] (str "- " o)))))))

(defn- plan->markdown [{:keys [summary direction effort steps]}]
  (str/join "\n"
    (concat
     ["# Implementation plan"
      (str "**Direction:** " direction "  ·  **Effort:** " (name effort))
      "" summary]
     (when (seq steps) (concat ["" "## Steps"] (for [s steps] (str "- " s)))))))

(defn- completed->markdown [{:keys [summary artifacts design-delta open]}]
  (str/join "\n"
    (concat
     ["# Implementation completed" "" summary "" "## Artifacts"]
     (for [{:keys [kind ref url]} artifacts]
       (str "- " (name kind) " `" ref "`" (when url (str " — " url))))
     (when design-delta
       (let [{:keys [held? deviations note]} design-delta]
         (concat
          ["" (str "## Design " (if held? "held" "did NOT hold"))]
          (when note [note])
          (when (seq deviations)
            (cons "Deviations from the record:"
                  (for [d deviations] (str "- " d)))))))
     (when (seq open) (concat ["" "## Still open"] (for [o open] (str "- " o)))))))

(defn- blocker->markdown [{:keys [summary needs]}]
  (str/join "\n" ["# Blocker" "" summary "" "## Needs" needs]))

(defn- pr-opened->markdown [{:keys [url title summary]}]
  (str/join "\n"
    (remove nil? ["# PR opened" (str "**" title "** — " url) (when summary (str "\n" summary))])))

(defn- merged->markdown [{:keys [pr url title merged-at]}]
  (str/join "\n"
    (remove nil? ["# Merged"
                  (str "**" title "** — " url)
                  (str "`" pr "`" (when merged-at (str " · " merged-at)))])))

(defn- ship-submitted->markdown [{:keys [session]}]
  (str/join "\n" ["# Ship submitted" "" (str "`" session "` handed to the merge lane.")]))

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

(defn- design-verdict->markdown
  [{:keys [verdict round reason invariants-held invariants-broken
           findings-classified needs]}]
  (str/join
   "\n"
   (remove nil?
     (concat
      [(str "# Design verdict: " (name verdict))
       (str "after round " round)
       "" reason]
      (when (seq invariants-held)
        (cons "\n## Invariants this round confirmed"
              (for [i invariants-held] (str "- " i))))
      (when (seq invariants-broken)
        (cons "\n## Invariants contradicted"
              (for [{:keys [invariant finding]} invariants-broken]
                (str "- " invariant "\n  - by: " finding))))
      (when (seq findings-classified)
        (cons "\n## Findings by layer"
              (for [{:keys [finding as]} findings-classified]
                (str "- [" (name as) "] " finding))))
      (when needs ["\n## Needs a decision" needs])))))

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

(defn- proposed-ticket-head
  [{:keys [title ticket-type priority]}]
  [(str "# " title)
   (str "**Type:** " ticket-type
        (when priority (str "  ·  **Priority:** " priority)))
   ""])

(defn- proposed-ticket->markdown
  [{:keys [ticket-type source-url problem root-cause fix
           request proposed-change rationale watch-out] :as report}]
  (str/join "\n"
    (remove nil?
      (concat
        (proposed-ticket-head report)
        (if (= "improvement" ticket-type)
          [(str "**Request** — " request)
           (str "**Proposed change** — " proposed-change)
           (str "**Rationale** — " rationale)]
          [(str "**Problem** — " problem)
           (str "**Root cause** — " root-cause)
           (str "**Fix** — " fix)])
        [(when-not (str/blank? watch-out) (str "**Watch out** — " watch-out))
         ""
         (str "Source: " source-url)]))))

(defn report->markdown
  "Render a `:format`-tagged report payload to markdown. Each event type → its own
   headed markdown; :markdown → its body; nil/unknown → \"\"."
  [report]
  (case (:format report)
    :markdown                 (or (:markdown report) "")
    :triage-report            (triage->markdown report)
    :design                   (design->markdown report)
    :implementation-plan      (plan->markdown report)
    :implementation-completed (completed->markdown report)
    :blocker                  (blocker->markdown report)
    :pr-opened                (pr-opened->markdown report)
    :merged                   (merged->markdown report)
    :ship-submitted           (ship-submitted->markdown report)
    :review-report            (review->markdown report)
    :design-verdict           (design-verdict->markdown report)
    :findings                 (findings->markdown report)
    :proposed-ticket          (proposed-ticket->markdown report)
    ""))

(defn report-title
  "Index title for the typed events that carry no top-level :title — design / plan
   / completed / blocker. nil otherwise (triage, pr-opened, merged and markdown carry a usable :title that
   the caller falls back to)."
  [report]
  (case (:format report)
    :design                   (first-line (:shape report))
    :implementation-plan      (:direction report)
    :implementation-completed (first-line (:summary report))
    :blocker                  (or (:needs report) (first-line (:summary report)))
    :review-report            (str "Review: " (name (:status report)))
    :design-verdict           (str "Design verdict: " (name (:verdict report)))
    :findings                 (str "Findings round " (:round report)
                                   " (" (count (:items report)) " items)")
    :ship-submitted           "Ship submitted"
    nil))
