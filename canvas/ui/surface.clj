(ns canvas.ui.surface
  "Self-spec: `nido.ui.*` — the TUI, the web dashboard, and the views behind them.

   The band that is SEALED behind the work plane: these reach `WorkPlane` to act, `View` to
   read, `Control` for the daemon and `Report` for the value they render, and nothing beneath.
   That is what stops a surface inventing a stage transition or reading a record the work plane
   has an opinion about."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.control :as control]
            [canvas.coordinator.record.state :refer [SessionName WorkstreamId]]
            [canvas.coordinator.report :as report]
            [canvas.coordinator.work :as work :refer [Screen]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind ViewState
  "What a request or a keypress means, as state: which scope, which tab, which selection.

   The single input the screen is derived from — so a URL and a TUI keystroke that mean the same
   thing produce the same screen, and the dashboard is bookmarkable for free.")

(Module ui-view-state
  "Turning a request into view-state."
  {:child [ViewState]}
  (Operation parse "A request as view-state."
    {:signature [:=> [:catn [:req :map]] ViewState]}))

(Module ui-markdown
  "Markdown as renderable markup."
  (Operation render "A markdown string as hiccup."
    {:signature [:=> [:catn [:s [:maybe :string]]] :any]}))

(Module ui-stylesheet
  "The dashboard's stylesheet, as text a page chrome emits.

   HIDES which hue, which size and which rule. Out of `ui-views` because a second chrome — the
   component catalog's — has to render blocks in the same visual language, and with the block
   inside `shell` the only way to get it was to copy it. A copy is the second stored set of hues
   the token test exists to prevent.

   What it holds is the tokens and the class rules the dashboard's pages and blocks name. The
   catalog's chrome is not a dashboard page: its rules are the catalog's, written over these
   tokens through var(--…), as every level above writes its colours and sizes."
  (Operation stylesheet
    "The stylesheet every dashboard chrome emits: the one :root custom-property block, and every
     class rule the dashboard's pages and blocks name."
    {:signature [:=> [:catn] :string]}))

(Module ui-components-state
  "A workstream's state as a reader sees it: the words each facet is said in, and the mark that
   says it.

   HIDES how a state is worded and how it is marked — which word a position reads as, which
   glyph and which semantic colour mark it, and when a mark is lit rather than muted. One table
   per facet, so a second wording cannot exist. What it publishes is the marks, and the words
   that sit beside them where a mark is not enough — a review round's progress, an arc stage's
   heading, the status heading — each over the part of the state it says; a chip's title also
   carries any words its caller adds.

   The state level reaches `View` for one function — the single wording of what a workstream is doing, which
   the TUI says too. Taking that wording as a caller's string instead would let two surfaces
   word it differently, which is the thing the one labeller exists to prevent, so the dependency
   is kept and the level is not self-contained."
  (Operation origin-badge "A workstream's origin as a badge."
    {:signature [:=> [:catn [:origin :keyword]] :any]})
  (Operation stage-chip "A board stage as a chip."
    {:signature [:=> [:catn [:stage :any]] :any]})
  (Operation position-chip
    "Where the pipeline says a row is. Muted by default and lit only when the next move is a
     person's, because that is the distinction a board is scanned for; what would move it, and
     any caller's extra tip, are the chip's title."
    {:signature [:=> [:catn [:position [:maybe :map]] [:tip [:? :any]]] :any]})
  (Operation state-mark
    "Whether anything is happening in a workstream, as one mark and no words. What runs and who
     owes the next move are the mark's title; a merge keeps its words."
    {:signature [:=> [:catn [:status [:maybe :map]] [:doing [:maybe :map]]] :any]})
  (Operation round-label
    "The words beside a running stage row: where a review round has got, and nothing for any
     other activity — which is why it answers with a string or nothing, where every other member
     here answers with markup."
    {:signature [:=> [:catn [:doing [:maybe :map]]] [:maybe :string]]})
  (Operation phase-chip "Which landing of a phased plan a row is in, or nothing."
    {:signature [:=> [:catn [:phase [:maybe :map]]] :any]})
  (Operation status-heading "The status section's heading."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation arc-name "One arc stage's heading."
    {:signature [:=> [:catn [:stage :any]] :any]})
  (Operation arc-mark
    "The mark for one arc stage: what is happening there right now if anything is, and otherwise
     what the stage's records amount to."
    {:signature [:=> [:catn [:state :any] [:now :any]] :any]}))

(Module ui-components-row
  "The scannable list rows: the Workstreams board row, the needs-you row, the winding-down row
   and the arc row.

   HIDES what a row puts where and how dense it is. Each takes the row's value plus how a click
   on it is routed — a URL, or the expression a click evaluates — so a row names no page's URL
   scheme and any surface can list one. Written in `ui-components-state`'s marks."
  (Operation board-row
    "One selectable Workstreams row: its origin, its title, its tag, where the pipeline says it
     is, whether anything is happening in it, and — on a gate — what the gate waits on. `opts`
     carries the URL the row links to and whether it is the open one."
    {:signature [:=> [:catn [:project ProjectName] [:row :map] [:opts :map]] :any]
     :delegates [origin-badge position-chip state-mark phase-chip]})
  (Operation gate-row
    "One needs-you row, linking to the workstream the gate is answered on, previewing the
     question it asks."
    {:signature [:=> [:catn [:gate :map] [:href :string]] :any]
     :delegates [origin-badge stage-chip state-mark]})
  (Operation winddown-row
    "One winding-down row: a closed workstream still holding live sessions. Muted, one action.
     `post-url` is where its one button posts."
    {:signature [:=> [:catn [:row :map] [:post-url :string]] :any]
     :delegates [origin-badge]})
  (Operation arc-row
    "One stage of a workstream's arc: its mark, its name, the one line of detail it carries, and
     its re-entry count when the work left the stage and came back. `opts` carries whether it is
     expanded and the expression a click on it evaluates, or nothing where it holds no records."
    {:signature [:=> [:catn [:facet :map] [:round-label [:maybe :string]] [:opts :map]] :any]
     :delegates [arc-name arc-mark]}))

(Module ui-components-ask
  "One Ask, offered for answer.

   HIDES how a decision is offered — one question at a time or all at once, which control is
   primary, and where a consequence sits relative to the button that carries it. Not hidden: the
   page-state key its choices are held under, which it publishes as `card-signal`, and the literal
   names the server repeats from its markup."
  (Operation card-signal
    "The key a workstream's question card keeps its step and choices under, in page state —
     one per workstream, so two cards on one page never share them, and the server reads a
     submit's choices back from the same key."
    {:signature [:=> [:catn [:ws-id WorkstreamId]] :string]})
  (Operation action-bar "One action set, rendered. The single renderer behind every gate."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:actions :any]
                            [:session :any] [:route :any]] :any]})
  (Operation question-card
    "One Ask, answered: its questions one at a time, their options, and every action the gate
     offers beside what it does. Reads nothing but its arguments — the page supplies only how a
     click is routed — so any pane can drop it in."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:ask :map]
                            [:actions :any] [:opts :map]] :any]
     :delegates [action-bar card-signal]}))

(Module ui-views
  "The dashboard's pages and fragments, and the twenty per-kind cards a ledger record reads as.

   Fragment-first, because the dashboard updates in place — a click re-renders one fragment
   rather than a page, which is why every region a click or a poll replaces is addressable on
   its own.

   HIDES which blocks each page is composed of, and how a record of each kind reads. The blocks
   themselves are not here: the stylesheet, the state marks, the rows and the ask are levels of
   their own, and a page is written in them. The record cards are the one vocabulary still
   sharing this boundary with the pages, and the reason is in the design that moved the others
   out — a card is read from a ledger record's shape, and reading twenty of those apart is a
   different job from the blocks a person iterates on."
  (Operation rail-status-fragment "The live rail: what needs you, and how the daemon is."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation fire-signal "The signal name for one field of a fire form."
    {:signature [:=> [:catn [:trigger-name :any] [:k :keyword]] :string]})
  (Operation fleet-card "What the fleet costs, and whether another session fits."
    {:signature [:=> [:catn [:fleet :map]] :any]})
  (Operation ops-panel-fragment "The ambient ops chrome: daemon, halt, breakers, triggers, fleet."
    {:signature [:=> [:catn [:ctx :map]] :any] :delegates [fleet-card fire-signal]})
  (Operation shell "The page chrome every page sits inside."
    {:signature [:=> [:catn [:ctx :map] [:body :any]] :any] :delegates [stylesheet datastar-script]})
  (Operation needs-fragment "The needs-you column."
    {:signature [:=> [:catn [:ctx :map]] :any] :delegates [gate-row]})
  (Operation gate-action-confirm-fragment "The immediate confirmation a gate action swaps in."
    {:signature [:=> [:catn [:action-id :any] [:project ProjectName] [:ws-id WorkstreamId]
                            [:pane-id :string]] :any]})
  (Operation gate-action-skip-fragment "What is rendered when a gate action is not offered."
    {:signature [:=> [:catn [:msg :string] [:project ProjectName] [:ws-id WorkstreamId]
                            [:pane-id :string]] :any]})
  (Operation needs-page "The home page: the list of what waits on you, each row opening its workstream."
    {:signature [:=> [:catn [:ctx :map] [:data :map]] :any]
     :delegates [shell needs-fragment]})
  (Operation workstreams-fragment "The selected tab's stage groups."
    {:signature [:=> [:catn [:ctx :map]] :any] :delegates [board-row winddown-row]})
  (Operation workstream-pane "The read-only ledger pane for one workstream."
    {:signature [:=> [:catn [:ws :map] [:session-dev-states [:? :any]]] :any]
     :delegates [status-heading state-mark arc-row question-card action-bar
                 report/report->markdown render]})
  (Operation pickup-bar "The paste-a-ticket bar."
    {:signature [:=> [:catn [:project ProjectName]] :any]})
  (Operation pickup-result-fragment "What a pickup attempt swaps in."
    {:signature [:=> [:catn [:result :map] [:opts :map]] :string]})
  (Operation workstreams-page "The overview and its ledger pane."
    {:signature [:=> [:catn [:ctx :map] [:data :map]] :any]
     :delegates [shell workstreams-fragment workstream-pane pickup-bar]})
  (Operation operations-fragment "The proposal list."
    {:signature [:=> [:catn [:proposals :any]] :any]})
  (Operation recovery-fragment
    "Session recovery: counts, one row per live or recently settled cause, and the activity
     feed — or a notice when recovery could not be read."
    {:signature [:=> [:catn [:overview [:maybe :map]]] :string]})
  (Operation sweep-fragment
    "What holds the improvement sweep, above the backlog it stops: a hold nothing is working on
     as a notice, one parked at a gate as a pointer to it, one being worked on as a line — or a
     notice when the holds could not be read."
    {:signature [:=> [:catn [:holds [:maybe [:vector :map]]]] :string]})
  (Operation operations-home-cards
    "One card per operational concern — its name, whether it needs a person, a line or two —
     read from the same values its own page renders, so a card and its page cannot disagree.
     A concern that could not be read says so on its card rather than reading as zero; the
     review-queue card appears only where some project declares the grooming trigger. Beside
     them, the operational notices — host casualties to revive, permission denials — each only
     when there is one: what nido wants a person to know that is no workstream's question."
    {:signature [:=> [:catn [:proposals :any] [:recovery [:maybe :map]]
                  [:holds [:maybe [:vector :map]]] [:queues [:maybe [:vector :map]]]
                  [:notices [:maybe :map]]] :any]})
  (Operation review-queue-fragment
    "Each project's latest review-queue grooming: where it stands, the levers that fit that
     stage (run, approve the undecided, apply the approved), the queue in rank order with each
     ticket's proposed writes and their full text, and the flags."
    {:signature [:=> [:catn [:queues [:maybe [:vector :map]]] [:requested [:set :any]]] :string]})
  (Operation review-queue-page
    "The review-queue grooming: run it, go through its plan ticket by ticket, approve or skip
     each write, and apply what was approved."
    {:signature [:=> [:catn [:ctx :map] [:queues [:maybe [:vector :map]]]
                  [:requested [:set :any]]] :any]
     :delegates [shell review-queue-fragment]})
  (Operation operations-home-fragment "The home's card grid."
    {:signature [:=> [:catn [:cards [:vector :any]]] :string]})
  (Operation operations-home-page
    "Operations' home: a card per concern nido operates on itself, each opening its own page."
    {:signature [:=> [:catn [:ctx :map] [:cards [:vector :any]]] :any]
     :delegates [shell operations-home-fragment]})
  (Operation improvements-page
    "What holds the improvement sweep, then every proposal the review analyses have made."
    {:signature [:=> [:catn [:ctx :map] [:proposals :any] [:holds [:maybe [:vector :map]]]] :any]
     :delegates [shell sweep-fragment operations-fragment]})
  (Operation recovery-page "Session recovery on its own page."
    {:signature [:=> [:catn [:ctx :map] [:recovery [:maybe :map]]] :any]
     :delegates [shell recovery-fragment]})
  (Operation proposal-result-fragment "What deciding a proposal swaps in."
    {:signature [:=> [:catn [:result :map] [:proposals :any]] :any] :delegates [operations-fragment]})
  (Operation not-found-page "The 404."
    {:signature [:=> [:catn] :any] :delegates [shell]}))

(Module ui-server
  "The dashboard's HTTP surface.

   `read-rail-daemon` is a named seam over the daemon reading so a test can stub it."
  (Operation read-rail-daemon "The rail's daemon reading. A stubbing seam."
    {:signature [:=> [:catn] :map] :delegates [control/read-daemon-health]})
  (Operation derive-screen
    "Gather what the screen needs and derive it — the gate inbox's casualty card from the
     workstreams owed a revive, beside the gates and never among them."
    {:signature [:=> [:catn [:view-state :any]] Screen] :delegates [work/screen work/owed-revivals]})
  (Operation wrap-slow-request-log
    "Wrap a handler so a request slower than the threshold leaves one line in the daemon log.
     The dashboard's own latency is otherwise unobservable after the fact."
    {:signature [:=> [:catn [:handler :any]] :any]})
  (Operation parse-findings-lines "A textarea of findings as items."
    {:signature [:=> [:catn [:s :string]] :any]})
  (Operation resolve-failure-msg "Why a gate action did not do what was asked."
    {:signature [:=> [:catn [:result :map]] :string]})
  (Operation handle-request "One request, routed."
    {:signature [:=> [:catn [:req :map]] :map]
     :delegates [derive-screen parse-findings-lines resolve-failure-msg
                 work/session-recovery
                 work/improvement-holds]})
  (Operation start! "Start the dashboard server."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [handle-request]})
  (Operation stop! "Stop it."
    {:signature [:=> [:catn] :any]}))

(Module ui-tui
  "The terminal surface.

   One public entry, and everything else is private: the TUI is a rendering of the same screen
   the dashboard shows, so its internals are not a second model."
  (Operation run-once "Run the TUI to completion."
    {:signature [:=> [:catn] :any]}))

(Module ui-dev
  "The development-environment lever: what a session's app is doing, and how to restart it.

   Separate from the work plane on purpose — restarting an app server is a fact about a machine,
   not a move on a workstream, and putting it in `work` would have made the work vocabulary
   answer questions about processes."
  (Operation set-app-state! "Record what a session's app is doing."
    {:signature [:=> [:catn [:instance-id :any] [:state :any] [:error-msg [:? :any]]] :any]})
  (Operation clear-app-state! "Forget it."
    {:signature [:=> [:catn [:instance-id :any]] :any]})
  (Operation current-app-state "What a session's app was last reported doing."
    {:signature [:=> [:catn [:instance-id :any]] :any]})
  (Operation pending-resolve-keys "The gate resolutions currently in flight."
    {:signature [:=> [:catn] :any]})
  (Operation pending-winddown-keys "The wind-downs currently in flight."
    {:signature [:=> [:catn] :any]})
  (Operation failed-ws-errors "The workstreams whose last action failed, and why."
    {:signature [:=> [:catn] :map]})
  (Operation dev-state-for "A session's dev-resource state. Pure derivation."
    {:signature [:=> [:catn [:wt-path :any] [:instance-id :any] [:registry :map] [:probe :any]
                            [:alive? :any] [:app-state-fn :any]] :map]})
  (Operation session-dev-state "The derived dev state for one session."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id [:maybe :string]] [:session :any]
                            [:registry [:? :map]]] :map]
     :delegates [dev-state-for]})
  (Operation ws-session-dev-states "Dev state for every session of a workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws :map]] :map] :delegates [session-dev-state]})
  (Operation app-port-for-instance "The app port an instance holds."
    {:signature [:=> [:catn [:instance-id :any]] [:maybe :int]]})
  (Operation stop-session! "Bring one session down."
    {:signature [:=> [:catn [:project ProjectName] [:session :any]] :any]})
  (Operation dev-action! "Run a dev-environment lever against a session."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session :any]
                            [:action :keyword]] :any]
     :delegates [set-app-state!]}))
