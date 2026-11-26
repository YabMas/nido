(ns nido.ui.components.state
  "A workstream's state as a reader sees it: the words each facet is said in, and the mark that
   says it.

   One table per facet — the position, the next stage it owes, who owes it, the arc stage and the
   mark for an arc stage's record state — and every surface that says a facet asks here rather
   than wording it. That is what stops two pages describing one workstream two ways, and it is
   why the tables are private: what this publishes is the mark, never the word.

   Reads nothing. Each function takes its facet's own value, and the one call out of this level
   is `wsv/doing-label` — the single wording of what a workstream is doing, a pure function of
   the value it is given and shared with the TUI. It is a call rather than an argument for
   exactly that reason: a caller handed the wording could word it differently, and then the
   board and the pane would disagree about one workstream."
  (:require [clojure.string :as str]
            [nido.coordinator.view.workstreams :as wsv]))

;; ---------------------------------------------------------------------------
;; Where it came from, and which band it sits in

(defn ^{:malli/schema [:=> [:cat :keyword] :any]}
  origin-badge [origin]
  (let [[ch cls] (case origin
                   :notion ["N" "b-notion"] :github ["G" "b-github"]
                   :slack ["S" "b-slack"]   ["·" "b-scratch"])]
    [:span {:class (str "badge " cls)} ch]))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  stage-chip
  "A board stage as a chip. The band a row is grouped under is the board's own word rather than
   one of the workstream's own facets, which is why it is not held to a table the way a position
   is: there is nothing else that says it."
  [stage]
  [:span {:class (str "chip c-" (name stage))} (name stage)])

;; ---------------------------------------------------------------------------
;; Where the pipeline says it is

(def ^:private position-label
  "What each position reads as to a person. The keyword is the value's, this is
   the reader's — a pane that printed `:premise-retracted` would be leaking a
   vocabulary at somebody who only wants to know what is going on."
  {:intake            "Not started"
   :intent-stated     "Intent stated"
   :baselined          "Baseline written"
   :baseline-verified "Baseline verified"
   :designed          "Design written"
   :design-decided    "Decision made"
   :design-cleared    "Cleared"
   :design-approved   "Approved"
   :implemented       "Implemented"
   :implementing      "Implementing"
   :triage-awaiting   "Triage to accept"
   :reviewed          "Reviewed"
   :awaiting-gate     "Awaiting gate"
   :published         "Draft PR open"
   :land-granted      "Landing"
   :release-candidate "Release candidate"
   :shipped           "Merged"
   :findings-open     "Findings open"
   :blocked           "Blocked"
   :premise-retracted "Premise retracted"
   :design-retracted  "Design retracted"
   :intent-retracted  "Intent retracted"
   :design-invalidated "Design invalidated"
   :goal-superseded   "Goal moved"
   :unplaceable       "Cannot place"
   :core-asking       "Asking you"
   :core-under-way    "Under way"})

(def ^:private stage-label
  {:establish-intent      "establish intent"
   :write-baseline        "write the baseline"
   :verify-baseline       "verify the baseline"
   :design                "write the design"
   :decide-design         "decide on it"
   :implement             "implement it"
   :review-implementation "review the diff"
   :publish-draft-pr      "open the draft PR"
   :rebaseline            "re-do the baseline"
   :address-findings      "address findings"
   :answer-blocker        "your answer"
   :answer-ask            "your answer"
   :assert-gate           "your gate"
   :land                  "your landing"
   :accept-triage         "your triage call"})

(declare needs-words)

(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:? :any] [:? [:maybe :keyword]]] :any]}
  position-chip
  "Where the pipeline says this row is, and what would move it — the one thing a
   board row could not say before.

   The band alone could not say it. Everything between authoring an intent and
   opening a draft PR is :in-progress, so forty rows read identically while one
   waited on a baseline, one on a decision and one on a person. The chip is muted
   by default and lit while `needs` — the activity's :needs, the category the person
   is needed in — names one, because that is the distinction a board is scanned for.
   It is the activity's answer, never the position's mode read here: a position whose
   next move is a person's on a workstream nido's recipe does not run on, or that a
   pause holds, needs nobody. The category, in needs-words, leads the title, before
   what would move it and the caller's `tip`.

   Renders nothing for a row with no position — a bare watched page, or a band
   the projection is not computed for."
  ([position] (position-chip position nil nil))
  ([position tip] (position-chip position tip nil))
  ([{:keys [at next] :as position} tip needs]
   (when position
     (let [title (remove nil? [(when needs (needs-words needs))
                               (when next (str "next: " (get stage-label (:stage next) (name (:stage next)))))
                               tip])]
       [:span (cond-> {:class (str "pos" (when needs " pos-you"))}
                (seq title) (assoc :title (str/join " · " title)))
        [:span.pos-at (get position-label at (name at))
         (when-let [{:keys [done of]} (:layers position)] (str " " done "/" of))]]))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :any]}
  phase-chip
  "Where a phased workstream is in its plan — `phase 2/3` — or nothing for an
   unphased one. Beside the position chip rather than inside it: the position is
   where this landing's work stands, the phase is which landing it is."
  [{:keys [current of]}]
  (when current
    [:span.phase-chip {:title "phase of the design's plan"} (str "phase " current "/" of)]))

;; ---------------------------------------------------------------------------
;; Whether anything is happening in it

(declare needs-mark)

(def ^:private needs-label
  "The words for each kind the person is needed in, one set every surface says them in: the three
   categories a gate is in (`nido.coordinator.view.activity/needs-categories`), and :chat, a side
   chat pinned to a workstream with something the person has not seen — which no gate carries,
   and the dashboard counts beside the gates."
  {:ask "ask" :landing "landing" :system-error "system error" :chat "unread chat"})

(def ^:private kind-heading
  "How a list of one kind's items is headed."
  {:ask "Asks" :landing "Landings" :system-error "System errors" :chat "Unread chats"})

(defn ^{:malli/schema [:=> [:cat :keyword] :string]}
  needs-kind-heading
  "The heading of a list holding only kind `kind`'s items."
  [kind]
  (get kind-heading kind (name kind)))

(defn ^{:malli/schema [:=> [:cat [:maybe :keyword]] :string]}
  needs-words
  "How a surface says the person is needed in category `needs`: \"needs you · landing\", or
   \"needs you\" for an unnamed one."
  [needs]
  (if-let [w (get needs-label needs)] (str "needs you · " w) "needs you"))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  gate-standing
  "How a needs-you card says where its request stands: \"working…\" while its resolve runs, parked
   on the session it waits in, else the word for the category the person is needed in."
  [{:keys [pending? session needs]}]
  (cond pending? "working…"
        session  (str "parked · " session)
        :else    (get needs-label needs "decide")))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  winddown-words
  "How a closed workstream still holding live sessions says where it stands: \"stopping…\" while
   its bring-down runs, else how it closed, how many sessions it still holds and what they hold."
  [{:keys [pending? outcome session-count rss-str]}]
  (if pending?
    "stopping…"
    (str "closed:" (name (or outcome :done)) " · " session-count " live session(s)"
         (when rss-str (str " · " rss-str)))))

(def ^:private owed-label
  {:person "you" :agent "agent" :machine "nido" :nobody "nobody"})

(defn- doing-badge
  "A merge's words — `merging · queued` — as the mark says them: muted text, in its phase's
   severity class so a stuck merge still reads red on a board that is scanned rather than read.
   It is often the last span of a `.gate-sub`, which flexes to fill the line, so it carries no
   background."
  [doing]
  (when-let [d (wsv/doing-label doing)]
    [:span {:class (str "doing-badge"
                        (when-let [phase (and (= :merge (:source doing)) (:phase doing))]
                          (str " ship-" (name phase))))}
     d]))

(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :any]}
  state-mark
  "Whether anything is happening in a workstream, as one mark and no words — the one mark every
   surface puts beside a workstream, read off its activity (`status`: the activity's :needs-you,
   :paused, :stalled, :live and :owed-by) and what is underway (`doing`):
     the needs-you dot while the person owes the next move, titled with the category they are
       needed in (`needs-words`), and a merge's words (`doing-badge`) while a merge is underway —
       a blocked merge has to read red on a board that is scanned rather than read — both side by
       side when both hold;
   and where neither holds, in precedence:
     ‖ in the dormant colour while a person's pause stands;
     ⚠ in the failed colour when it is stalled — an agent owes the move, nothing runs and nobody
       paused it, which is a fault and never a resting state;
     the breathing dot in the working colour while something is live — the one the arc's running
       stage carries;
     nothing otherwise.
   What runs, and who owes the next move, are the mark's title. Only :live lights the working dot:
   the activity already makes everything underway live, so a second test here could only
   disagree with it."
  [{:keys [owed-by live stalled paused needs-you needs]} doing]
  (let [what (wsv/doing-label doing)
        tip  (str/join " · " (remove nil? [what (when owed-by (str "owed by " (owed-label owed-by (name owed-by))))]))]
    (cond
      (or needs-you (= :merge (:source doing)))
      (let [dot   (when needs-you (needs-mark (str (needs-words needs) (when what (str " · " what)))))
            badge (when (= :merge (:source doing)) (doing-badge doing))]
        (if (and dot badge) (list dot " " badge) (or dot badge)))
      paused                     [:span.st-mark.paused {:title "paused by you"} "‖"]
      stalled                    [:span.st-mark.stalled {:title (str "stalled — nothing runs and nothing asks you"
                                                                     (when (seq tip) (str " · " tip)))} "⚠"]
      live                       [:span.st-mark.live {:title (if (seq tip) tip "running")}])))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :string]]}
  round-label
  "The words beside the running stage row: where a review round has got (`design round 3 ·
   judging · 0 findings`), and nothing for any other activity. Which agent runs and on what is
   detail the person does not scan a workstream for — the running mark already says something
   runs — so it stays in the state mark's title."
  [doing]
  (when (#{:claim :core} (:source doing)) (wsv/doing-label doing)))

;; ---------------------------------------------------------------------------
;; The status section's heading

(defn ^{:malli/schema [:=> [:cat :map [:? [:maybe :map]] [:? [:maybe :map]]] :any]}
  status-heading
  "The Status section's heading, carrying the position label, which nothing replaces — what
   runs, a merge's words included, is said by the state mark beside it (`state-mark`, which is
   what reads `doing`; the heading ignores it) — and, while the workstream's
   activity (`status`) says the person owes the next move, saying so in the category they are
   needed in (`needs-words`), so the heading cannot read as a position alone while Needs you
   lists it.

   The position was a bold word at the top of the section body, immediately under
   a heading that said nothing. Two lines to say one thing, and the heading was
   the emptier of them — so the position moved into it and the body kept only
   what comes next."
  ([position] (status-heading position nil nil))
  ([position doing] (status-heading position doing nil))
  ([{:keys [at]} _doing status]
   (let [head (if at (str "Status — " (get position-label at (name at))) "Status")]
     (cond-> head
       (and (:needs-you status) (not= :core-asking at)) (str " · " (needs-words (:needs status)))))))

;; ---------------------------------------------------------------------------
;; The arc

(def ^:private arc-label
  "Arc stage -> its heading. A separate vocabulary from `stage-label`, which names
   the NEXT ACTION (`:verify-baseline`, `:decide-design`) rather than the place a
   record belongs to. They read alike and answer different questions, so they are
   kept apart rather than merged into a table that would have to mean both."
  {:intent         "Intent"
   :baseline       "Baseline"
   :design         "Design"
   :implementation "Implementation"
   ;; Not a stage: the Land rung the pane draws past the arc's stages.
   :land           "Land"})

(def ^:private arc-glyph
  "Stage state -> its mark. Five marks because there are five states, and no two
   of them mean the same thing: `⊘` is a stage the work went past without writing
   one, `·` is one it has not reached, and `↺` is one it wrote records for that
   the ledger no longer stands behind — owed again rather than behind you, which
   is the distinction a ✓ there would destroy."
  {:done "✓" :current "●" :stale "↺" :skipped "⊘" :ahead "·"})

(defn- land-step-word
  "One recorded landing step, as the Land rung's line says it."
  [{:keys [format step pr reason]}]
  (if (= :merged format)
    "merged"
    (case step
      :submitted (str "submitted" (when-let [n (some->> pr (re-find #"#(\d+)$") second)] (str " #" n)))
      :refused   "refused"
      :queued    "queued"
      :ejected   (str "ejected" (when reason (str " (" reason ")")))
      (some-> step name))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :string]]}
  land-trail
  "The words of the Land rung's line: `granted` while the core holds the person's grant, then each
   step the landing recorded, oldest first — `granted · submitted #57 · queued · ejected (CI
   failed) · queued`. An ejection keeps its place after the re-queue that follows it. Nothing for
   a landing with neither."
  [{:keys [granted? steps]}]
  (let [words (cond->> (keep land-step-word steps) granted? (cons "granted"))]
    (when (seq words) (str/join " · " words))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  arc-name
  "One arc stage's heading."
  [stage]
  (get arc-label stage (name stage)))

(defn ^{:malli/schema [:=> [:cat [:? :string]] :any]}
  needs-mark
  "The needs-you mark: the dot in the colour that says the next move is a person's. `title` is
   what hovering it reads, \"needs you\" unless the caller says how this one waits."
  ([] (needs-mark "needs you"))
  ([title] [:span.needs {:title title}]))

(def ^:private kind-paths
  "Each needs kind's glyph, as the SVG paths drawing it in the side panel's icon form: a raised
   hand for an ask, a rocket for a landing, an octagon holding an exclamation for a system error,
   a speech bubble for an unread chat."
  {:ask          ["M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2"
                  "M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2"
                  "M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8"
                  "M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"]
   :landing      ["M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09z"
                  "m12 15-3-3a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.35 22.35 0 0 1-4 2z"
                  "M9 12H4s.55-3.03 2-4c1.62-1.08 5 0 5 0"
                  "M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5"]
   :system-error ["M12 16h.01" "M12 8v4"
                  "M15.312 2a2 2 0 0 1 1.414.586l4.688 4.688A2 2 0 0 1 22 8.688v6.624a2 2 0 0 1-.586 1.414l-4.688 4.688a2 2 0 0 1-1.414.586H8.688a2 2 0 0 1-1.414-.586l-4.688-4.688A2 2 0 0 1 2 15.312V8.688a2 2 0 0 1 .586-1.414l4.688-4.688A2 2 0 0 1 8.688 2z"]
   :chat         ["M7.9 20A9 9 0 1 0 4 16.1L2 22Z"]})

(def needs-kinds
  "The kinds the person is needed in, in the order every surface lists them."
  [:ask :landing :system-error :chat])

(defn ^{:malli/schema [:=> [:cat :keyword [:? :string]] :any]}
  kind-mark
  "The mark of needs kind `kind` — one per kind, the only glyph any surface says that kind with:
   a 24-unit stroked SVG in currentColor, coloured --needs, since every kind is a move the
   person owes. `title` is what hovering it reads, the kind's needs-words unless the caller says
   how this one waits."
  ([kind] (kind-mark kind (needs-words kind)))
  ([kind title]
   [:span {:class (str "kind-mark kind-" (name kind)) :title title :aria-label title}
    (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"
                 :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}]
          (map (fn [d] [:path {:d d}]) (get kind-paths kind)))]))

(defn ^{:malli/schema [:=> [:cat [:? :string]] :any]}
  error-mark
  "The error mark: the machine stopped on a failure, and the person's move is its recovery — the
   system-error kind's mark. It heads a system error's account of what failed. `title` is what
   hovering it reads."
  ([] (error-mark "failed — needs recovering"))
  ([title] [:span.error-mark (kind-mark :system-error title)]))

(defn ^{:malli/schema [:=> [:cat :any :any [:? [:maybe :keyword]]] :any]}
  arc-mark
  "The mark for one arc stage: what is happening there right now if anything is — a person being
   waited on, or an agent working — and otherwise what the stage's records amount to. `now`
   outranks `state` because a stage waiting on a person is the one fact a reader scans an arc
   for. Both are dots, in the two hues that tell a person's move from an agent's: the needs-you
   dot the board rows carry, titled with the category the person is needed in (`needs`), and a
   breathing dot in the working colour."
  ([state now] (arc-mark state now nil))
  ([state now needs]
   (case now
     :awaiting (needs-mark (needs-words needs))
     :running  [:span.arc-live {:title "an agent is working"}]
     (get arc-glyph state "·"))))
