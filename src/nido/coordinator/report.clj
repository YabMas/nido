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

(def SeamLegacy
  "LEGACY READ SHAPE — a seam from before :closed-by existed, when visibility was
   the whole of the obligation. Not registered for writing; see Seam."
  [:map {:closed true}
   [:what        string?]
   [:visible-how string?]])

(def Seam
  "Deliberate, visible incompleteness. /spin-out's veto turns on this: invisibly
   incomplete is a defect, visibly incomplete is a decision — so say how a reader
   sees it.

   :closed-by is the second half of that, and it was missing. Visibility told a
   reader the seam was intentional; nothing told anyone it would ever be closed,
   because :seams lives in a record that stops being read the moment the
   workstream merges. So a seam names its closure and there are exactly three
   honest answers: a phase of this record's own plan closes it, a spun-out
   follow-up closes it (and then the ref is the deferral — \"later\" in a PR
   comment is a wish), or nothing closes it, which is a decision that has to
   carry its reason.

   The :phase case names a phase by its :claim; workstream/append-entry! rejects
   one that names no phase on the same record, which is also what stops a record
   with no phase plan from claiming a phase will close anything."
  [:multi {:dispatch :closed-by}
   [:phase     [:map {:closed true}
                [:what        string?]
                [:visible-how string?]
                [:closed-by   [:= :phase]]
                [:phase       string?]]]
   [:spun-out  [:map {:closed true}
                [:what        string?]
                [:visible-how string?]
                [:closed-by   [:= :spun-out]]
                [:ref         string?]]]
   [:permanent [:map {:closed true}
                [:what        string?]
                [:visible-how string?]
                [:closed-by   [:= :permanent]]
                [:why         string?]]]])

(def Invariant
  "One invariant, with the moment it holds. A design that lands once has only one
   such moment, which is why this used to be a bare string and why a bare string
   is still what an unphased record writes.

   A phase plan has several landings, and between them some invariants are false
   BY DESIGN — during an expand/migrate/contract, \"there is exactly one writer\"
   is deliberately untrue for the whole middle phase. Without :holds the review
   arbiter reads that deliberate state as an invalidated design and escalates a
   decision that was already made.

     :always         holds at EVERY phase boundary, so review checks it every time
     :on-completion  holds only once the last phase lands; checked there, and
                     nowhere before"
  [:map {:closed true}
   [:invariant string?]
   [:holds     [:enum :always :on-completion]]])

(def Gate
  "A phase's exit criterion: what must be observed of the RUNNING system before
   the next phase may start. This is the field that separates a phase from a
   to-do — a phase carrying no gate is a wish with an ordinal, the same failure
   /spin-out §6 names for a deferral filed without its :reason.

   :kind is required because it decides how the criterion is checked, and getting
   that wrong is how a gate becomes a formality:

     :observation  a measurement on the live system (a counter, a discrepancy
                   rate, an error budget)
     :soak         elapsed time with nothing observed — a period, not a metric
     :completion   a finite job done (a backfill drained, N of N callers moved)

   nido observes no production today, so nothing here is machine-checked: the
   criterion is asserted with its evidence when the phase advances, the way a
   spin-out's :reason is required and not verified."
  [:map {:closed true}
   [:kind      [:enum :observation :soak :completion]]
   [:criterion string?]])

(def PhaseUndo
  "How this phase is taken back, answered before it ships rather than during the
   incident. Every branch carries its own justification, because the three
   answers are not interchangeable and the difference is the whole value of a
   phase plan — it is what tells you WHICH phase is the one you cannot undo.

     :revert   roll back to the previous phase; :by says how
     :forward  no way back, but a further landing fixes it; :by says what
     :none     the point of no return; :why says what makes it irreversible"
  [:multi {:dispatch :how}
   [:revert  [:map {:closed true} [:how [:= :revert]]  [:by  string?]]]
   [:forward [:map {:closed true} [:how [:= :forward]] [:by  string?]]]
   [:none    [:map {:closed true} [:how [:= :none]]    [:why string?]]]])

(def Phase
  "One landing of a phased change — the temporal cut, /phase's unit, sibling of
   Layer. The two are not the same unit and the difference is sharp: a stack's
   layers all land in one `gh stack merge`, so no intermediate layer state is
   ever observed by a running system, and a layer's obligation is only that the
   build is green there. A phase boundary IS a deploy, so its obligation is that
   the system is habitable there.

   :claim is about the RUNNING system, not the diff — what is true in production
   once this lands. One sentence, no \"and\", same test as a layer's.

   :habitable is what makes \"could you live here indefinitely?\" answerable from
   the record instead of from memory. It is the field that catches the phase that
   is really half a phase."
  [:map {:closed true}
   [:claim     string?]
   [:habitable string?]
   [:exit      Gate]
   [:undo      PhaseUndo]])

(defn invariant
  "Normalise one :invariants entry to {:invariant <string> :holds <keyword>}.

   A plain string is what every record written before phasing carries, and it
   means :always — a change that lands once has exactly one moment for an
   invariant to hold at, so there was nothing else it could have meant. Every
   reader goes through here rather than testing string? at the point of use,
   which is how the two shapes stay one concept."
  [x]
  (if (string? x) {:invariant x :holds :always} x))

(defn seam-closure
  "The one-line rendering of what closes `seam`, or nil for a legacy seam that
   names no closure. nil is a real answer here and is rendered as absence rather
   than as \"unknown\": the record predates the obligation, so saying nothing is
   more honest than implying its author declined to answer."
  [{:keys [closed-by phase ref why]}]
  (case closed-by
    :phase     (str "closed by phase — " phase)
    :spun-out  (str "spun out as " ref)
    :permanent (str "permanent — " why)
    nil))

(def Intent
  "What the task is FOR, and what would make it done. Authored BEFORE the design
   that cites it, and closed against everything that needs the change to fill in
   — the same test the baseline is held to, for the same reason.

   It exists because intent was the last yardstick a round could not resolve from
   durable state. Everything else a decision is judged against — the baseline, the
   stance, the rejected alternatives — is written down and citable; the goal
   arrived as an argument typed by whoever ran the round. Two decisions over
   identical records could therefore disagree, and on this workstream's own ledger
   two of them did.

   Authoring order carries as much weight as the content. Goals written after the
   design are goals the design satisfies, which is the same contamination that
   moved the current-design inference out of :assumes and into its own event. The
   ledger cannot check when you wrote it, but it can check that you cited it, and
   the schema can refuse anything that only makes sense once you know the answer.

   :done-when is what makes the goal falsifiable. A goal nobody can fail to meet
   cannot tell an over-serving design from a right-sized one, which is the whole
   job of the goal-served check."
  [:map {:closed true}
   [:format    [:= :intent]]
   [:goal      string?]
   [:done-when [:vector {:min 1} string?]]
   [:context   {:optional true} string?]])

(def IntentRelation
  "Which entry states what this change is for. :seq may name an :intent entry or
   an existing :triage entry — a workstream whose intent is already written down
   does not restate it — and the append boundary refuses anything else.

   A citation rather than a lookup, for the reason the baseline is one: resolving
   'the latest intent' would let an entry appended later silently change what an
   already-judged decision was judged against."
  [:map {:closed true}
   [:seq int?]])

(def Route
  "Where one health observation from the cited baseline goes. The baseline
   observes and never routes, because routing needs the change; this is the
   other half of that split, and the reason the observations carry ids.

   Four destinations, and the asymmetry between them is deliberate. :fix-here is
   the conservative default and defends itself — you are doing the work. The
   other three are all forms of NOT doing it here, so each has to say why, the
   same way :conforms needs no note and :extends does.

   :spin-out additionally requires a :ref. 'We should clean this up later' in a
   PR comment is a wish; the doctrine's rule is that there is no spin-out
   without a ref, and this is where it stops being a matter of nerve.

   :constrains is the destination for an observation that is not work at all —
   it bounds what this change may leave behind. An observation the baseline
   marked :invisibly-incomplete? can never be spun out, and the ledger enforces
   that when the design is appended."
  [:multi {:dispatch :to}
   [:fix-here   [:map {:closed true}
                 [:health-id string?]
                 [:to        [:= :fix-here]]
                 [:why       {:optional true} string?]]]
   [:constrains [:map {:closed true}
                 [:health-id string?]
                 [:to        [:= :constrains]]
                 [:why       string?]]]
   [:spin-out   [:map {:closed true}
                 [:health-id string?]
                 [:to        [:= :spin-out]]
                 [:why       string?]
                 [:ref       string?]]]
   [:declined   [:map {:closed true}
                 [:health-id string?]
                 [:to        [:= :declined]]
                 [:why       string?]]]])

(def Supersedes
  "Set when this record amends one the review found wrong. The superseded entry
   stays in the ledger — a design is amended and cited, never silently rewritten."
  [:map {:closed true}
   [:seq int?]
   [:why string?]])

(def LoadBearing
  "One property the CURRENT code already relies on — not what ought to hold, but
   what would break if you violated it. This is the field that makes the routing
   question answerable by derivation instead of taste: behaviour that violates one
   of these is an implementation defect, and behaviour that honours every one of
   them and is still wrong indicts the design itself.

   :evidence is required and non-empty. A load-bearing property with nothing to
   point at is a guess, and a guess is exactly what this record exists to replace."
  [:map {:closed true}
   [:property string?]
   [:evidence [:vector {:min 1} string?]]
   [:drift    {:optional true} string?]])

(def ExtensionPoint
  "Somewhere the current design already admits extension, and how. The mirror of
   :load-bearing — that names what cannot move, this names where it can. A change
   landing on one of these extends the design; one that needs a point which is not
   here is asking for the design to be revisited.

   Deliberately NOT called a seam. In /design and /spin-out a seam is a *change's*
   own visible incompleteness, which is a different thing, and one word for both
   would collide two ideas this vocabulary was built to keep apart."
  [:map {:closed true}
   [:at  string?]
   [:how string?]])

(def HealthObservation
  "One thing about the area's health that the survey ENCOUNTERED while
   establishing what the operating design is. Deliberately not an audit: a pass
   that goes looking will always find something, and the area would then be
   reviewed in full at the start of every workstream that touches it. Bounded by
   the same :bounded-by as everything else in the record.

   :axis carries the weight, because the two halves fail differently and the
   remedy differs with them. A strong design shakily implemented is debt — no
   threat to what is about to be built, and the most routable class there is. A
   weak design cleanly implemented is the dangerous one: it looks healthy,
   everything downstream inherits it, and it is what should turn a change's
   declared relation from :within into :revisit. Collapsing them yields the
   useless survey output ('this area is a bit messy') instead of the useful one
   ('the boundary is in the wrong place, and the code honouring it is why nobody
   noticed').

   :invisibly-incomplete? marks the class /spin-out's veto turns on — an
   inconsistency a future reader would read as intentional. It is an is-claim
   about the code, true whoever is building whatever, which is why it can be
   stated here without the record crossing into routing.

   :id is how a design record names this observation when it routes it, so it
   has to be unique within the baseline."
  [:map {:closed true}
   [:id          string?]
   [:observation string?]
   [:axis        [:enum :design :implementation]]
   [:evidence    [:vector {:min 1} string?]]
   [:invisibly-incomplete? {:optional true} boolean?]])

(defn- distinct-health-ids?
  "Health observation ids are unique within one baseline. A duplicate would make
   'routed exactly once' unanswerable for the design record that cites it."
  [{:keys [health]}]
  (or (< (count health) 2) (apply distinct? (map :id health))))

(def Baseline
  "An area's current design, as it is — the yardstick every later judgement in the
   workstream is made against. Authored BEFORE the design record and independent
   of it, which is the whole point: an inference made by someone who already knows
   the fix is an inference bent toward the fix.

   :health is the one field that is a judgement rather than a reading, and it is
   still an `is`: whether what is here holds, not whether it should be different.
   It never carries a destination — routing needs the change, and a survey by
   someone who already knows the change is worth nothing. The design record that
   cites this baseline is what routes them.

   THE TEST FOR WHAT BELONGS HERE: every field must be fillable without knowing the
   change. That is why nothing about effort, direction or intended shape appears —
   those are the design record's business, and a field that needs them has crossed
   from `is` to `ought`.

   Scoped to the design that GOVERNS the behaviour, not to the files a change would
   touch. Those differ, and the difference is where design flaws hide: the blast
   radius is defined by the fix, while the flaw is routinely upstream of it. Hence
   :bounded-by — the scoping decision is the first claim this record makes, and the
   only guard against both failure modes (reading the whole codebase, and reading
   three files and calling it a design).

   Per workstream, and never written into the codebase. A checked-in current design
   rots and then lies, which is worse than one that is absent, because it is
   citable. /design §4 anticipates harvesting a written one from records like these
   later; this is not that."
  [:and
   [:map {:closed true}
    [:format           [:= :baseline]]
    [:area             string?]
    [:bounded-by       string?]
    [:shape            string?]
    [:load-bearing     [:vector {:min 1} LoadBearing]]
    [:extension-points {:optional true} [:vector ExtensionPoint]]
    [:health           {:optional true} [:vector HealthObservation]]
    [:governing        {:optional true} [:vector string?]]
    [:drift            {:optional true} [:vector string?]]
    [:read             [:vector {:min 1} string?]]
    [:unknowns         {:optional true} [:vector string?]]]
   [:fn {:error/message "health observation ids must be unique within a baseline"}
    distinct-health-ids?]])

(def BaselineRelation
  "How this change relates to the area's CURRENT design — the layer-2 question,
   and deliberately NOT :standing, which relates it to the project's stance. The
   two are independent: a change can conform to the stance perfectly and still
   require the current design to be torn up, and it is the second question that
   decides whether a bug is a fix or a decision.

     :within   the design already accommodates this. A defect here is an
               implementation defect; a feature here needs no new boundary.
     :extends  the design holds, and this lands where it already admits change —
               or adds a point that contradicts nothing already load-bearing.
     :revisit  a load-bearing property has to change. A defect here indicts the
               design; a feature here is asking the core to move.

   :revisit REQUIRES :breaks, naming properties from the cited baseline that
   cannot survive. Without it \"the design needs revisiting\" is a feeling, and
   deriving that answer instead of feeling it is the only reason the baseline
   exists. :extends requires a note for the same reason Standing/:extends does —
   a commitment added without saying why is the silent-erosion case.

   :seq points at the :baseline entry this was judged against. The schema sees
   one record and cannot resolve it; append-entry! checks that it names a real
   baseline on the same workstream."
  [:multi {:dispatch :relation}
   [:within  [:map {:closed true}
              [:seq      int?]
              [:relation [:= :within]]
              [:note     {:optional true} string?]]]
   [:extends [:map {:closed true}
              [:seq      int?]
              [:relation [:= :extends]]
              [:at       {:optional true} string?]
              [:note     string?]]]
   [:revisit [:map {:closed true}
              [:seq      int?]
              [:relation [:= :revisit]]
              [:breaks   [:vector {:min 1} string?]]
              [:note     string?]]]])

(def ^:private design-common
  "The fields a :design record carries in every era. The three that differ across
   eras and across the phased/unphased split — :invariants, :seams, :phases — are
   spliced in per shape rather than repeated.

   :routes is here rather than spliced because routing the cited baseline's health
   observations is orthogonal to how many landings a change has: a phased design
   answers for them exactly as an unphased one does."
  [[:format     [:= :design]]
   [:summary    string?]
   [:shape      string?]
   [:standing   Standing]
   [:baseline   BaselineRelation]
   [:routes     {:optional true} [:vector Route]]
   [:rejected   {:optional true} [:vector Rejected]]
   [:layers     {:optional true} [:vector Layer]]
   [:open       {:optional true} [:vector string?]]
   [:supersedes {:optional true} Supersedes]
   [:effort     Effort]])

(def UnphasedDesign
  "A change that reaches production in ONE landing — the ordinary case, and
   unchanged by phasing. :invariants stays a vector of plain strings precisely
   because there is only one moment for them to hold at: asking such a record to
   say WHEN each invariant holds would be ceremony with one legal answer."
  (into [:map {:closed true}]
        (concat design-common
                [[:intent     IntentRelation]
                 [:invariants [:vector {:min 1} string?]]
                 [:seams      {:optional true} [:vector Seam]]])))

(def PhasedDesign
  "A change that reaches production in more than one landing, each of which the
   system has to live in. Two fields tighten, and both tighten for the same
   reason — a phase plan creates intermediate states that are correct by design,
   and a record that cannot express that is read as a broken one.

   :invariants moves to the Invariant map, so each says whether it holds at every
   phase boundary or only on completion. Without it the arbiter judges the middle
   of a migration against the end of it.

   :phases has :min 2 because one phase is not a plan, it is a shipment. There is
   no ceiling in the schema and none in the doctrine either: what says a plan has
   gone wrong is not its length but whether its phases share a cause — phases that
   would each be answered by moving the same boundary are one boundary in the
   wrong place, however few of them there are, and a genuinely long migration with
   a distinct habitable state per landing is not mis-scoped for having them. Same
   test /stack applies to layers and /spin-out to what leaves the branch."
  (into [:map {:closed true}]
        (concat design-common
                [[:intent     IntentRelation]
                 [:invariants [:vector {:min 1} Invariant]]
                 [:phases     [:vector {:min 2} Phase]]
                 [:seams      {:optional true} [:vector Seam]]])))

(def DesignVision
  "The high-level design one workstream commits to — authored by the impl session
   before any code, and resolving a triage :squirrel into a concrete effort.
   Replaces ImplementationPlan, and drops its :steps: a step list is working
   memory, and the ledger holds what survives the session.

   :invariants is required and non-empty on purpose. It is what the review arbiter
   checks findings against; a design that names none is unfalsifiable, and every
   finding against it becomes a matter of taste.

   :routes is where the cited baseline's health observations get their
   destinations. It is not free-form commentary: the ledger checks that every
   observation the baseline recorded is routed exactly once and that none is
   invented, because 'nothing is lost and nothing is smuggled' is only true if
   something counts.

   :baseline is required for the same class of reason and a sharper one. The
   inference about what was ALREADY there used to live here, as :assumes — a
   field inside the record that also states the commitment, so it was written by
   someone who already knew the fix, which is the one condition under which an
   inference is worth nothing. It now lives in its own :baseline event, authored
   first, and this field is where the change declares its relation to it. Two
   questions are being kept apart on purpose: :standing relates the change to the
   project's stance, :baseline relates it to the current design, and a change can
   satisfy either while breaking the other.

   The dispatch is on :phases, and it is a dispatch rather than a pair of
   optional fields on purpose: the two shapes make DIFFERENT claims about when
   the design is true, so a record that carries a phase plan and plain-string
   invariants is not a lenient case to wave through — it is a phase plan whose
   author has not said which of its claims survive the middle of it.

   THIS IS THE WRITE CONTRACT. Records appended before :baseline existed do not
   satisfy it and are not supposed to — see DesignVisionLegacy and read-schemas."
  [:multi {:dispatch (fn [r] (if (contains? r :phases) :phased :unphased))}
   [:phased   PhasedDesign]
   [:unphased UnphasedDesign]])

(def DesignVisionLegacy
  "LEGACY READ SHAPE — a :design record from before the baseline event existed:
   no :baseline, and carrying the :assumes that the baseline replaced.

   Not registered for writing. It exists so history stays readable, which is not
   a courtesy: ws/latest-entry and work/entry->report validate on READ and
   swallow the failure, so without this every design record written before the
   cutover would quietly vanish from the panes and from the review judge — the
   design would not be contradicted, it would simply stop being there."
  [:map {:closed true}
   [:format     [:= :design]]
   [:summary    string?]
   [:shape      string?]
   [:invariants [:vector {:min 1} string?]]
   [:standing   Standing]
   [:assumes    {:optional true} [:vector Assumption]]
   [:rejected   {:optional true} [:vector Rejected]]
   [:layers     {:optional true} [:vector Layer]]
   [:seams      {:optional true} [:vector SeamLegacy]]
   [:open       {:optional true} [:vector string?]]
   [:supersedes {:optional true} Supersedes]
   [:effort     Effort]])

(def DesignVisionRead
  "The wide READ shape for a post-baseline :design record: anything that was
   writable in any era since the baseline event landed. Two widenings, each
   naming the tightening that made it necessary —

     :invariants accepts a plain string OR an Invariant map, because every record
     written before :holds existed carries strings and means :always by them.

     :seams accepts SeamLegacy OR Seam, because every record written before
     :closed-by existed carries the two-field shape.

   Both are wide here and strict in the write shapes above. A record that omits
   what it should carry must fail on write, where the author can fix it — not on
   read, months later, where the only available behaviour is to make the design
   silently disappear from the panes and the arbiter."
  (into [:map {:closed true}]
        (concat design-common
                [[:invariants [:vector {:min 1} [:or string? Invariant]]]
                 [:phases     {:optional true} [:vector {:min 1} Phase]]
                 [:seams      {:optional true} [:vector [:or Seam SeamLegacy]]]])))

(def DesignVisionReadCurrent
  "The wide READ shape for a design written since :intent became required — the
   pre-intent read shape plus the citation. Built from that shape rather than
   beside it, so the two cannot drift: a widening added for some later tightening
   reaches both tiers automatically."
  (into DesignVisionRead [[:intent IntentRelation]]))

(def DesignVisionAny
  "The READ contract for :design — anything that was legitimately writable at the
   time it was written.

   Deliberately NOT how the write contract is relaxed. Dispatching the one schema
   on whether :baseline happens to be present would make the requirement
   toothless in the exact case it exists for: a session that skips the baseline
   omits the field, lands in the lenient branch, and validates. Strict on write,
   wide on read — the tightening has teeth going forward and costs no history.

   The :current branch is a read shape rather than a write shape for the same
   reason at one more remove: the write shape splits on :phases, and reading a
   record through the branch it was WRITTEN by would mean re-deriving the era it
   was written in from the fields it happens to carry.

   Three tiers now, because :design has been tightened twice. :intent is the
   newest and is dispatched on first; :baseline is the older one. The pre-intent
   tier is DesignVisionRead UNCHANGED — the era before a tightening is exactly
   the read shape that preceded it, so a tightening adds a tier rather than
   rewriting one."
  [:multi {:dispatch (fn [r] (cond
                               (contains? r :intent)   :current
                               (contains? r :baseline) :pre-intent
                               :else                   :legacy))}
   [:current    DesignVisionReadCurrent]
   [:pre-intent DesignVisionRead]
   [:legacy     DesignVisionLegacy]])


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
  "What KIND of thing a finding turned out to be. :baseline is the premise case —
   the finding shows a property the survey claimed is simply not true of the code.
   It matters that it is not :design: the remedy is to re-survey, and a sound
   design resting on a wrong premise is a different failure from a wrong design."
  [:map {:closed true}
   [:finding string?]
   [:as      [:enum :implementation :design :stance :baseline]]])

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
     [:load-bearing-held {:optional true} [:vector string?]]
     [:findings-classified {:optional true} [:vector ClassifiedFinding]]]]
   [:strained
    [:map {:closed true}
     [:format [:= :design-verdict]] [:verdict [:= :strained]]
     [:round int?] [:design-seq {:optional true} [:maybe int?]]
     [:reason string?]
     [:invariants-held {:optional true} [:vector string?]]
     [:invariants-broken {:optional true} [:vector BrokenInvariant]]
     [:load-bearing-held {:optional true} [:vector string?]]
     [:load-bearing-broken {:optional true} [:vector BrokenInvariant]]
     [:findings-classified {:optional true} [:vector ClassifiedFinding]]]]
   [:invalidated
    [:map {:closed true}
     [:format [:= :design-verdict]] [:verdict [:= :invalidated]]
     [:round int?] [:design-seq {:optional true} [:maybe int?]]
     [:reason string?]
     [:invariants-broken [:vector {:min 1} BrokenInvariant]]
     [:load-bearing-held {:optional true} [:vector string?]]
     [:load-bearing-broken {:optional true} [:vector BrokenInvariant]]
     [:findings-classified {:optional true} [:vector ClassifiedFinding]]
     [:needs string?]]]
   [:standing-challenged
    [:map {:closed true}
     [:format [:= :design-verdict]] [:verdict [:= :standing-challenged]]
     [:round int?] [:design-seq {:optional true} [:maybe int?]]
     [:reason string?]
     [:invariants-broken {:optional true} [:vector BrokenInvariant]]
     [:load-bearing-held {:optional true} [:vector string?]]
     [:load-bearing-broken {:optional true} [:vector BrokenInvariant]]
     [:findings-classified {:optional true} [:vector ClassifiedFinding]]
     [:needs string?]]]])

(def RecordFinding
  "One finding from a judgment over a RECORD rather than over a diff.

   :cites is required and non-empty, and it carries the whole anti-theatre rule.
   A pre-implementation round has no diff to be wrong about, so an agent asked
   'is this any good?' produces fluent, unfalsifiable findings forever. A finding
   has to name the thing it falsifies — a load-bearing property, a health
   observation, an invariant, a stance principle, an :open item, or a rejected
   alternative whose reason no longer holds. A finding citing nothing is not a
   finding, and the schema is where that stops being a hope."
  [:map {:closed true}
   [:cites    [:vector {:min 1} string?]]
   [:claim    string?]
   [:evidence {:optional true} [:vector string?]]])

(def BaselineReview
  "The verification round over a survey: is this true, and is it complete enough
   to decide against?

   Near-mechanical by construction — every load-bearing property and health
   observation carries file:line evidence, so checking one is 'go read it'. That
   makes this the cheapest and most decidable round in the lifecycle, and the one
   that protects everything above it: a design can be perfectly sound on a survey
   that was wrong, and nothing else detects that before the code is written.

   Two failure modes, and they are the two the skill names. :falsified — a stated
   property is not true of the code. :underscoped — the bound excludes something
   that governs the behaviour, which is the one that hides design flaws, since
   the flaw is routinely upstream of the blast radius. Both mean re-survey, never
   redesign.

   :accurate is the expected outcome and names what it CONFIRMED, for the same
   reason the design verdict's :sound does: a clean round should accumulate trust
   rather than evaporate."
  (let [common [[:format       [:= :baseline-review]]
                [:baseline-seq int?]
                [:reason       string?]
                [:confirmed    {:optional true} [:vector string?]]]
        shape  (fn [verdict & extra]
                 (into [:map {:closed true}]
                       (concat common [[:verdict [:= verdict]]] extra)))]
    [:multi {:dispatch :verdict}
     [:accurate    (shape :accurate)]
     [:falsified   (shape :falsified   [:findings [:vector {:min 1} RecordFinding]])]
     [:underscoped (shape :underscoped [:findings [:vector {:min 1} RecordFinding]])]]))

(def DerivedCheck
  "One thing the decision round worked out for itself, so a human does not have
   to. The four are the derivable no-answers — each is decidable from the records
   plus the code, and none of them is a matter of taste:

     :relation-honest  — the design declares :within, but its own shape needs a
                         load-bearing property to move
     :goal-served      — the goal is met by a strictly smaller design the record
                         already rejected, for a reason that no longer holds
     :decomposable     — the layering cannot be stated, so there is nothing to
                         approve yet
     :routing-coherent — the health routes make this two stories rather than one

   :status has three values, not two. A round that COULD NOT derive a check is
   not a round that derived it and found it wanting, and collapsing the two lets
   an absent yardstick read as a failed one — the single confusion this whole
   arc exists to prevent, applied to the check itself.

   :checks is required and non-empty. A decision round that derived nothing did
   not reduce anything, and handing a human an unreduced question is the rubber
   stamp this round exists to avoid."
  [:map {:closed true}
   [:check  [:enum :relation-honest :goal-served :decomposable :routing-coherent]]
   [:status [:enum :held :broken :underivable]]
   [:note   string?]])

(def DerivedCheckPreUnderivable
  "READ SHAPE — a check from before the third outcome existed, carrying :held?
   as a boolean. Not writable.

   The era exists because records in this shape are real: dogfooding a record
   kind on the workstream that designs it writes immutable entries under an
   unmerged contract, so \"nothing has merged, we can still change the shape\"
   was false the first time a round ran."
  [:map {:closed true}
   [:check [:enum :relation-honest :goal-served :decomposable :routing-coherent]]
   [:held? boolean?]
   [:note  string?]])

(def DesignDecision
  "The pre-implementation decision round — the only point in the lifecycle where
   'don't build this' is still cheap, and the one round that is a DECISION rather
   than a verification.

   Which is why it never decides. Asked 'should we?', a model returns yes-with-
   suggestions forever, so the question is split by who can answer it: everything
   derivable is derived into :checks, and :asks carries the irreducible judgement
   — worth doing, now, at this cost — to a human. :asks is required on every
   branch, :recommend included: this round prepares an approval, it does not
   grant one.

   :recommend is what the derivation supports. :proceed means nothing derivable
   blocks it. The other three each name a different remedy, and saying the wrong
   one is worse than saying nothing: :amend fixes the record, :recut redoes the
   decomposition, :resurvey fixes the premise and leaves the commitment alone."
  (let [common [[:format     [:= :design-decision]]
                [:design-seq int?]
                [:reason     string?]
                [:checks     [:vector {:min 1} DerivedCheck]]
                [:asks       string?]]
        shape  (fn [recommend & extra]
                 (into [:map {:closed true}]
                       (concat common [[:recommend [:= recommend]]] extra)))]
    [:multi {:dispatch :recommend}
     [:proceed  (shape :proceed)]
     [:amend    (shape :amend    [:findings [:vector {:min 1} RecordFinding]])]
     [:recut    (shape :recut    [:findings [:vector {:min 1} RecordFinding]])]
     [:resurvey (shape :resurvey [:findings [:vector {:min 1} RecordFinding]])]]))

(def DesignDecisionPreUnderivable
  "READ SHAPE for :design-decision — the same record with two-outcome checks.
   Not writable; it keeps every decision already recorded readable after the
   third outcome was added."
  (let [common [[:format     [:= :design-decision]]
                [:design-seq int?]
                [:reason     string?]
                [:checks     [:vector {:min 1} DerivedCheckPreUnderivable]]
                [:asks       string?]]
        shape  (fn [recommend & extra]
                 (into [:map {:closed true}]
                       (concat common [[:recommend [:= recommend]]] extra)))]
    [:multi {:dispatch :recommend}
     [:proceed  (shape :proceed)]
     [:amend    (shape :amend    [:findings [:vector {:min 1} RecordFinding]])]
     [:recut    (shape :recut    [:findings [:vector {:min 1} RecordFinding]])]
     [:resurvey (shape :resurvey [:findings [:vector {:min 1} RecordFinding]])]]))

(def DesignDecisionAny
  "The READ contract for :design-decision — current shape first, so a record
   satisfying both reads as current. Dispatches on the check shape actually
   present rather than on the record, because that is what changed."
  [:multi {:dispatch (fn [r] (if (some #(contains? % :status) (:checks r))
                               :current :pre-underivable))}
   [:current         DesignDecision]
   [:pre-underivable DesignDecisionPreUnderivable]])

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
   :intent                   Intent
   :baseline                 Baseline
   :design                   DesignVision
   :implementation-plan      ImplementationPlan
   :implementation-completed ImplementationCompleted
   :blocker                  Blocker
   :pr-opened                PrOpened
   :merged                   Merged
   :ship-submitted           ShipSubmitted
   :review                   ReviewReport
   :baseline-review          BaselineReview
   :design-decision          DesignDecision
   :design-verdict           DesignVerdict
   :findings                 FindingsRound
   :proposed-ticket          ProposedTicket})

(def read-schemas
  "Kinds whose READ contract is wider than their write contract, because records
   written before a tightening must stay readable. Consulted only by parse-event;
   a kind absent here reads under the same schema it was written by.

   This map is the standing acknowledgement of something the ledger otherwise
   pretends is not true: entries are immutable, so a schema is not one contract
   but one per era, and the reader's job is 'was this valid when written', not
   'would I accept this today'. Every entry here should name the tightening that
   put it here, and may be dropped once no record of the old shape survives."
  {;; Two tightenings on :design, one era each. :baseline became required —
   ;; DesignVisionLegacy is the pre-baseline shape, which also carries the
   ;; :assumes the baseline event replaced. Then :intent became required, and
   ;; DesignVisionPreIntent is everything written between the two.
   :design DesignVisionAny
   ;; :status replaced :held? on a derived check, adding :underivable as a third
   ;; outcome. Records in the two-outcome shape exist — a round writes them as
   ;; soon as it runs, merged or not.
   :design-decision DesignDecisionAny})

(defn- validate-against
  [schema kind report]
  (if (m/validate schema report)
    report
    (throw (ex-info "Invalid event report"
                    {:explain (m/explain schema report) :report report :kind kind}))))

(defn validate-event
  "THE WRITE CONTRACT. Validate `report` (parsed EDN) against the schema registered
   for entry `kind` — what may be appended today. Returns the report on success;
   throws ex-info carrying a malli :explain on mismatch (the bb task prints it so
   the emitting skill can fix + retry). Throws for an unregistered kind."
  [kind report]
  (validate-against (or (event-schemas kind)
                        (throw (ex-info "No schema for entry kind" {:kind kind})))
                    kind report))

(defn parse-event
  "THE READ CONTRACT. Like validate-event, but accepts anything that was
   legitimately writable when it was written (read-schemas), not only what is
   writable now.

   Every reader must use this rather than validate-event. Both readers validate
   on read and SWALLOW the failure — ws/latest-entry returns nil, work/entry->report
   degrades to raw markdown — so tightening a write schema without widening the
   read one does not surface as an error anywhere. It deletes history from the
   panes and from the review judge, silently, which is the failure mode the
   ledger's immutability is supposed to rule out."
  [kind report]
  (validate-against (or (read-schemas kind)
                        (event-schemas kind)
                        (throw (ex-info "No schema for entry kind" {:kind kind})))
                    kind report))

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

(defn- health->markdown
  "Health grouped by axis, design first. The axis is the routing signal, so it is
   the heading rather than a suffix — a reader scanning for what threatens the
   change should not have to read every line to find the ones that do."
  [health]
  (mapcat (fn [[axis label]]
            (when-let [items (seq (filter #(= axis (:axis %)) health))]
              (cons (str "\n### " label)
                    (for [{:keys [id observation evidence invisibly-incomplete?]} items]
                      (str "- `" id "` " observation
                           " — " (str/join ", " (map #(str "`" % "`") evidence))
                           (when invisibly-incomplete?
                             "\n  - invisibly incomplete: deferring this leaves the branch untrue"))))))
          [[:design "Design health"] [:implementation "Implementation health"]]))

(defn- intent->markdown
  [{:keys [goal done-when context]}]
  (str/join
   "\n"
   (concat
    ["# Intent — what this is for" "" goal "" "## Done when"]
    (for [d done-when] (str "- " d))
    (when context ["\n## Context" context]))))

(defn- baseline->markdown
  [{:keys [area bounded-by shape load-bearing extension-points health governing
           drift read unknowns]}]
  (str/join
   "\n"
   (concat
    ["# Baseline — the current design"
     (str "**Area:** " area)
     (str "*Bounded by: " bounded-by "*")]
    (when (seq governing)
      [(str "**Governed by:** " (str/join ", " governing))])
    ["" "## Shape" shape ""
     "## Load-bearing — what breaks if you violate it"]
    (for [{:keys [property evidence] lb-drift :drift} load-bearing]
      (str "- " property
           " — " (str/join ", " (map #(str "`" % "`") evidence))
           (when lb-drift (str "\n  - drift from the stance: " lb-drift))))
    (when (seq extension-points)
      (cons "\n## Extension points — where the design already admits change"
            (for [{:keys [at how]} extension-points]
              (str "- " at " — " how))))
    (when (seq health)
      (cons "\n## Health — what the survey ran into" (health->markdown health)))
    (when (seq drift)
      (cons "\n## Drift from the stance" (for [d drift] (str "- " d))))
    (when (seq unknowns)
      (cons "\n## Not determined" (for [u unknowns] (str "- " u))))
    ["\n## Read" (str/join ", " (map #(str "`" % "`") read))])))

(defn- baseline-relation->markdown
  ;; :seq is bound as entry-seq — {:keys [seq ...]} would shadow clojure.core/seq
  ;; and the next line calls it.
  [{:keys [relation at breaks note] entry-seq :seq}]
  (str "**Against the baseline:** " (name relation) " (entry " entry-seq ")"
       (when at (str " — at: " at))
       (when (seq breaks)
         (str "\n> Breaks: " (str/join "; " breaks)))
       (when note (str "\n> " note))))

(defn- design->markdown
  [{:keys [summary shape invariants standing baseline intent assumes routes
           rejected layers phases seams open supersedes effort]}]
  (str/join
   "\n"
   (concat
    ["# Design"
     (str "**Stance:** " (name (:relation standing))
          (when-let [ps (seq (:principles standing))]
            (str " — " (str/join ", " ps)))
          "  ·  **Effort:** " (name effort))]
    (when-let [n (:note standing)] [(str "> " n)])
    (when baseline [(baseline-relation->markdown baseline)])
    (when intent [(str "**For:** entry " (:seq intent))])
    (when supersedes
      [(str "*Supersedes entry " (:seq supersedes) " — " (:why supersedes) "*")])
    ["" summary "" "## Shape" shape "" "## Invariants"]
    (for [i invariants]
      (let [{t :invariant h :holds} (invariant i)]
        (str "- " t
             (when (= :on-completion h)
               " *(holds on completion — not at every phase boundary)*"))))
    (when (seq phases)
      (cons "\n## Phases"
            (map-indexed
             (fn [i {:keys [claim habitable exit undo]}]
               (str (inc i) ". " claim
                    "\n   - live meanwhile: " habitable
                    "\n   - exit (" (name (:kind exit)) "): " (:criterion exit)
                    "\n   - undo: " (case (:how undo)
                                       :revert  (str "revert — " (:by undo))
                                       :forward (str "forward only — " (:by undo))
                                       :none    (str "**point of no return** — " (:why undo)))))
             phases)))
    (when (seq assumes)
      (cons "\n## Assumes — the current design, as inferred"
            (for [{:keys [about read drift]} assumes]
              (str "- " about
                   (when (seq read)
                     (str " — read: " (str/join ", " (map #(str "`" % "`") read))))
                   (when drift (str "\n  - drift from the stance: " drift))))))
    (when (seq routes)
      (cons "\n## Routed from the baseline's health"
            (for [{:keys [health-id to why ref]} routes]
              (str "- `" health-id "` → **" (name to) "**"
                   (when why (str " — " why))
                   (when ref (str " (" ref ")"))))))
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
            (for [{:keys [what visible-how] :as seam} seams]
              (str "- " what " — visible as: " visible-how
                   (when-let [c (seam-closure seam)] (str "; " c))))))
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

(defn- record-findings->markdown
  "Findings from a round over a record. What each one CITES leads, because that
   is what makes it a finding rather than an opinion."
  [heading findings]
  (when (seq findings)
    (cons (str "\n## " heading)
          (for [{:keys [cites claim evidence]} findings]
            (str "- **" (str/join "; " cites) "** — " claim
                 (when (seq evidence)
                   (str "\n  - " (str/join ", " (map #(str "`" % "`") evidence)))))))))

(defn- baseline-review->markdown
  [{:keys [verdict baseline-seq reason confirmed findings]}]
  (str/join
   "\n"
   (remove nil?
     (concat
      [(str "# Baseline review: " (name verdict))
       (str "of entry " baseline-seq)
       "" reason]
      (when (seq confirmed)
        (cons "\n## Confirmed against the code"
              (for [c confirmed] (str "- " c))))
      (record-findings->markdown
       (case verdict
         :underscoped "What the bound leaves out"
         "Claims the code does not support")
       findings)
      (when (not= :accurate verdict)
        ["\n> Re-survey — the design may be sound on a bad premise."])))))

(defn- design-decision->markdown
  [{:keys [recommend design-seq reason checks asks findings]}]
  (str/join
   "\n"
   (remove nil?
     (concat
      [(str "# Design decision: " (name recommend))
       (str "of entry " design-seq)
       "" reason
       "\n## Derived — already ruled on, so you do not have to"]
      (for [{:keys [check status held? note]} checks]
        (str "- " (case (or status (if held? :held :broken))
                    :held "✓" :broken "✗" :underivable "—")
             " " (name check) " — " note))
      (record-findings->markdown "What the derivation found" findings)
      ["\n## For you to decide" asks]))))

(defn- design-verdict->markdown
  [{:keys [verdict round reason invariants-held invariants-broken
           load-bearing-held load-bearing-broken findings-classified needs]}]
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
      (when (seq load-bearing-held)
        (cons "\n## Load-bearing properties still standing"
              (for [i load-bearing-held] (str "- " i))))
      (when (seq load-bearing-broken)
        (cons "\n## Load-bearing properties broken without being declared"
              (for [{:keys [invariant finding]} load-bearing-broken]
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
    :intent                   (intent->markdown report)
    :baseline                 (baseline->markdown report)
    :design                   (design->markdown report)
    :implementation-plan      (plan->markdown report)
    :implementation-completed (completed->markdown report)
    :blocker                  (blocker->markdown report)
    :pr-opened                (pr-opened->markdown report)
    :merged                   (merged->markdown report)
    :ship-submitted           (ship-submitted->markdown report)
    :review-report            (review->markdown report)
    :baseline-review          (baseline-review->markdown report)
    :design-decision          (design-decision->markdown report)
    :design-verdict           (design-verdict->markdown report)
    :findings                 (findings->markdown report)
    :proposed-ticket          (proposed-ticket->markdown report)
    ""))

(def ^:private index-title-cap
  "The index is a table, one row per entry. Several of these titles are drawn
   from prose fields with no length discipline of their own — a design's :shape
   and a baseline's :area are paragraphs — so first-line is not enough on its
   own: a paragraph written without newlines is one line, and it is the whole
   paragraph."
  110)

(defn- clamp
  "Bound a title to one index row, ellipsis included so a reader can see it was
   cut rather than wondering whether the record just says that."
  [s]
  (when s
    (if (> (count s) index-title-cap)
      (str (str/trimr (subs s 0 (dec index-title-cap))) "…")
      s)))

(defn report-title
  "Index title for the typed events that carry no top-level :title — design / plan
   / completed / blocker. nil otherwise (triage, pr-opened, merged and markdown carry a usable :title that
   the caller falls back to).

   Every branch is clamped: the index is a table, and a title that wraps to six
   lines makes the entry list unreadable exactly when there are many entries to
   scan, which is when the index matters."
  [report]
  (clamp
   (case (:format report)
    :intent                   (str "Intent: " (first-line (:goal report)))
    :baseline                 (str "Baseline: " (first-line (:area report)))
    :design                   (first-line (:shape report))
    :implementation-plan      (:direction report)
    :implementation-completed (first-line (:summary report))
    :blocker                  (or (:needs report) (first-line (:summary report)))
    :review-report            (str "Review: " (name (:status report)))
    :baseline-review          (str "Baseline review: " (name (:verdict report)))
    :design-decision          (str "Design decision: " (name (:recommend report)))
    :design-verdict           (str "Design verdict: " (name (:verdict report)))
    :findings                 (str "Findings round " (:round report)
                                   " (" (count (:items report)) " items)")
     :ship-submitted           "Ship submitted"
     nil)))
