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
            [canvas.coordinator.view.host :refer [HostModel]]
            [canvas.coordinator.record.state :refer [SessionName WorkstreamId]]
            [canvas.coordinator.report :as report]
            [canvas.coordinator.work :as work :refer [Screen]]
            [canvas.integration.acp :as acp]
            [canvas.libraries.ui :as lib]
            [canvas.session.chat :as chat]
            [canvas.ui.presentation :as presentation]
            [canvas.ui.structure :as structure]
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
  (Operation render "A markdown string as hiccup; `{:links? false}` renders its links as their
    text, for a host that is itself interactive (an option inside its <button>)."
    {:signature [:function
                 [:=> [:catn [:s [:maybe :string]]] :any]
                 [:=> [:catn [:s [:maybe :string]] [:opts [:map [:links? {:optional true} :boolean]]]] :any]]}))

(Module ui-stylesheet
  "nido's visual language, as the UI library takes it.

   HIDES the token values and nido's shared and per-page layout rules, among them nido's catalog
   page's: the token values, read from resources/nido/tokens.edn — colour, type, space, radius,
   shadow, motion — and every class rule no block registration carries. None of its own rules
   names a control element or a control by its container. The control set's rules are not its
   secret: it passes them on beside its own. It assembles nothing; `ui-registry` hands these to
   the library beside the registrations, so no chrome can emit a stylesheet of its own.

   Its interface is the class names markup may write, and `stylesheet` — the tokens, its own
   rules and the control set's, handed to the library."
  (Operation stylesheet
    "nido's inputs to the library's stylesheet call: `:tokens`, the token map, and `:rules`, the
     text of every rule no block registration carries — its own and the control set's."
    {:signature [:=> [:catn] :map]}))

(Module ui-components-control
  "The dashboard's house controls: one public function per kind of control, and the one rule
   that styles each.

   HIDES how each kind of control is drawn: its element, its class, its variants and the one rule
   that styles it. The kinds are the button (default, primary, danger), the quiet and icon-only
   button, the link drawn as a button, the selectable choice card, the sunken text input,
   textarea and select at one size, the labelled field, the form section with its heading, the
   meter that shows a fraction (the fleet bar's fill), the sparkline that draws a series (a
   drain's census trend), and the hidden and file inputs.

   Its interface is one public function per kind, returning that control's hiccup over the
   caller's own attributes (data-bind, data-on:*, placeholder, label, title) and a closed set of
   emphases — the sparkline, a series of non-negative numbers drawn as an SVG figure, among them; and the rules for those classes, which reach the assembled stylesheet through
   ui-stylesheet. It is the only writer of a button, input, textarea or select element in the
   dashboard's markup."
  (Operation button "A button in one of three emphases: default, primary, danger."
    {:signature [:=> [:catn [:attrs :map] [:content [:* :any]]] :any]})
  (Operation link-button "A link drawn as a button, in the button's emphases."
    {:signature [:=> [:catn [:attrs :map] [:content [:* :any]]] :any]})
  (Operation quiet-button "A borderless button for chrome, accent-tinted while it is on."
    {:signature [:=> [:catn [:attrs :map] [:content [:* :any]]] :any]})
  (Operation icon-button
    "A quiet button showing only a glyph; refused without the label that is its accessible name."
    {:signature [:=> [:catn [:attrs :map] [:content [:* :any]]] :any]})
  (Operation choice "A selectable option card, with its chosen, primary and static states."
    {:signature [:=> [:catn [:attrs :map] [:content [:* :any]]] :any]})
  (Operation text-input "A one-line field in the one sunken look, at the one size."
    {:signature [:=> [:catn [:attrs :map]] :any]})
  (Operation textarea "A multi-line field in the same look and size."
    {:signature [:=> [:catn [:attrs :map] [:content [:* :any]]] :any]})
  (Operation select "A choice among fixed values, in the same look and size."
    {:signature [:=> [:catn [:attrs :map] [:options [:sequential :any]]] :any]})
  (Operation labelled "A field with its label above it."
    {:signature [:=> [:catn [:label :any] [:control :any]] :any]})
  (Operation form-section "A small form: its heading, then its fields and actions."
    {:signature [:=> [:catn [:heading :any] [:content [:* :any]]] :any]})
  (Operation meter "A fraction as a bar, carried by the element's own value."
    {:signature [:=> [:catn [:attrs :map]] :any]})
  (Operation sparkline
    "A series of non-negative numbers as a line, oldest at the left: its points computed into the
     SVG's own geometry, no style attribute, and the readings in its accessible name."
    {:signature [:=> [:catn [:attrs :map]] :any]})
  (Operation hidden-input "A value the page's script writes and a signal reads."
    {:signature [:=> [:catn [:attrs :map]] :any]})
  (Operation file-input "The hidden file picker a visible control opens."
    {:signature [:=> [:catn [:attrs :map]] :any]}))

(Module ui-catalog-scenes
  "The state functions' named renderings over literal sample data. A block's scenes are carried by
   its own registration; these are the scenes of the functions that stay plain functions.

   HIDES the sample data — which values make a state mark show the state worth looking at. A scene is
   a literal value and a router that goes nowhere, which is what the catalog and its test both
   need, and what makes the blocks' independence checkable rather than asserted."
  (Operation groups
    "How the catalog groups its scenes: one group per vocabulary, in reading order — each
     registered block's scenes as its registration carries them, and the scenes this module
     supplies for each public function of the state marks. ui-registry is the registry; this is
     only its grouping."
    {:signature [:=> [:catn] :any]}))

(Module ui-registry
  "nido's registry of blocks, and the stylesheet assembled over it.

   HIDES which block registrations nido publishes and that the stylesheet is assembled from them
   once, not per request. It collects every block registration of nido's component namespaces with
   the scenes each carries, adds the state functions' scenes `ui-catalog-scenes` supplies, and
   makes the library's one stylesheet call over the token values and rules `ui-stylesheet`
   supplies and those registrations. Every chrome takes the assembled text from here, so
   `ui-stylesheet` never reads a registration."
  (Operation registry
    "nido's registry: each block registration with its scenes, and the state functions' scenes —
     what the library's catalog-page reads."
    {:signature [:=> [:catn] :map] :delegates [groups]})
  (Operation assembled-stylesheet
    "The stylesheet every nido chrome emits, assembled once by the library."
    {:signature [:=> [:catn] :string] :delegates [stylesheet registry]}))

(Module ui-components-state
  "A workstream's state as a reader sees it: the words each facet is said in, and the mark that
   says it.

   HIDES how a state is worded and how it is marked — a drain's phase and a slice's state among
   them — which word a position reads as, which
   glyph and which semantic colour mark it, the kind the person is needed in among them, and
   when a mark is lit rather than muted. One table
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
    "Where the pipeline says a row is. Muted by default and lit while the category the caller
     hands it — the activity's :needs — names one, because that is the distinction a board is
     scanned for; that category in needs-words, what would move it, and any caller's extra tip are
     the chip's title."
    {:signature [:=> [:catn [:position [:maybe :map]] [:tip [:? :any]] [:needs [:? [:maybe :keyword]]]] :any]})
  (Operation state-mark
    "Whether anything is happening in a workstream, as one mark and no words. What runs and who
     owes the next move are the mark's title; a merge keeps its words."
    {:signature [:=> [:catn [:status [:maybe :map]] [:doing [:maybe :map]]] :any]})
  (Operation round-label
    "The words beside a running stage row: where a review round has got, and nothing for any
     other activity — which is why it answers with a string or nothing, where every other member
     here answers with markup."
    {:signature [:=> [:catn [:doing [:maybe :map]]] [:maybe :string]]})
  (Operation land-trail
    "The words of the Land rung's line, of round-label's kind: `granted` while the core holds the
     person's grant, then each step the landing recorded, oldest first — an ejection kept after the
     re-queue that follows it — or nothing for a landing with neither."
    {:signature [:=> [:catn [:trail [:maybe :map]]] [:maybe :string]]})
  (Operation phase-chip "Which landing of a phased plan a row is in, or nothing."
    {:signature [:=> [:catn [:phase [:maybe :map]]] :any]})
  (Operation drain-phase-tag
    "A drain's phase as a chip in the hue of the party owing its next move: the person's where a
     recipe waits on their approval or the drain on their close, the working hue while nido or the
     coordinator has it, ok once closed."
    {:signature [:=> [:catn [:phase :keyword]] :any]})
  (Operation slice-state-tag
    "A drain slice's state as a chip: the person's hue once it is cleared for them to land, the
     working hue while it is built or landing, ok once landed."
    {:signature [:=> [:catn [:state :keyword]] :any]})
  (Operation status-heading "The status section's heading: the position label, which nothing replaces
    — what runs, a merge's words included, is the state mark's beside it — and that the person is
    needed, in the category's words, while the workstream's activity needs them."
    {:signature [:=> [:catn [:ctx :map] [:doing [:? [:maybe :map]]] [:status [:? [:maybe :map]]]] :any]})
  (Operation arc-name "One arc stage's heading."
    {:signature [:=> [:catn [:stage :any]] :any]})
  (Operation needs-mark
    "The needs-you mark: the dot that says the next move is a person's in no kind in particular
     — an aggregate such as a project's drop-down entry, or a category no kind names. Its title
     is the caller's to word."
    {:signature [:=> [:catn [:title [:? :string]]] :any]})
  (Operation error-mark
    "The error mark: the machine stopped on a failure, and the person's move is its recovery — the
     system-error kind's mark. Its title is the caller's to word."
    {:signature [:=> [:catn [:title [:? :string]]] :any]})
  (Operation gate-standing
    "How a needs-you card says where its request stands: working while its resolve runs, parked on
     the session it waits in, else the category's word."
    {:signature [:=> [:catn [:gate :map]] :string]})
  (Operation winddown-words
    "How a closed workstream still holding live sessions says where it stands: stopping while its
     bring-down runs, else how it closed and what it still holds."
    {:signature [:=> [:catn [:row :map]] :string]})
  (Operation needs-words
    "How every surface says the kind the person is needed in — an ask, a landing, a system
     error, an unread chat — so the mark, the heading, the arc and the card word it alike."
    {:signature [:=> [:catn [:needs [:maybe :keyword]]] :string]})
  (Operation needs-kind-heading "The heading of a list holding only one needs kind's items."
    {:signature [:=> [:catn [:kind :keyword]] :string]})
  (Operation kind-mark
    "The mark of one needs kind — a raised hand, a rocket, an octagon holding an exclamation, a
     speech bubble — the only glyph any surface says that kind with, in the needs colour, titled
     with the kind's needs-words unless its caller words it."
    {:signature [:=> [:catn [:kind :keyword] [:title [:? :string]]] :any]})
  (Operation needs-kind-mark
    "The mark a state is said with wherever the person is needed — a board row, a needs-you row,
     an arc stage: the kind's own mark when the category names one, the needs-you dot when none
     does, titled with the category's needs-words unless its caller words it."
    {:signature [:=> [:catn [:needs [:maybe :keyword]] [:title [:? :string]]] :any]})
  (Operation arc-mark
    "The mark for one arc stage: what is happening there right now if anything is — the person
     waited on, titled with the category they are needed in, or an agent working — and otherwise
     what the stage's records amount to."
    {:signature [:=> [:catn [:state :any] [:now :any] [:needs [:? [:maybe :keyword]]]] :any]}))

(Module ui-components-row
  "The scannable list rows, registered with the UI library as four blocks: `board-row`, one
   selectable Workstreams row; `gate-row`, one needs-you row in the category the person is needed
   in, previewing the question it asks or what it waits on; `winddown-row`, a closed workstream still
   holding live sessions, with one action; and `arc-row`, one stage of a workstream's arc, whose
   click is an action the pane routes.

   HIDES what a row puts where and how dense it is. Each renders over the row's value as props
   and the page's router, so a row names no page's URL scheme and any surface can list one. A row
   listing a workstream the page says has news in a side chat pinned to it draws the unread-chat
   kind's mark, which the marks level draws.
   Written in `ui-components-state`'s marks; its rules are nido's, because the classes it is
   drawn with are shared with the pages around it.")

(Module ui-components-ask
  "One Ask, offered for answer, registered with the UI library as two blocks: `card`, the Ask
   answered — its questions one at a time, their options, and every action the gate offers
   beside what it does — and `bar`, one action set, the single renderer behind every gate's
   controls. They are values, not functions: each renders through the library over props, the
   context and the page's router alone, on the workstream's id as its instance.

   HIDES how one Ask is offered — question order, which choice is primary, where consequence sits
   relative to the button that carries it — and the layout rules of its own regions; its
   options, stepper, reply and evidence fields and buttons are the control set's. Its interface
   is the card and bar block registrations.

   Each block declares its actions as data, and the server reads a gate's posted body only
   through the decoder the same declaration derives, so no posted name is repeated on two
   sides.")

(Module ui-components-chat
  "A conversation with an agent, registered with the UI library as two blocks: `panel`, the side
   panel — a bar with its toggle and New conversation, the transcript, the turn's status row and
   the composer with its picks and staged images, and, given several conversations, each as a
   full-width row stacked under the last with its mark and pin control, one open beneath its
   row — and `change-bar`, the conversation's unlanded change with Publish and Cancel, each
   confirmed in place.

   HIDES how a chat panel and its composer are laid out: item order, folding, the status row, the
   composer and its picks. Its icon buttons, composer textarea and attach input are the control
   set's. Its interface is the panel and change-bar block registrations.

   Each renders over a transcript value as props, the explicit context and a caller's router;
   every control is an action the panel declares — send, stop, reset, the stream, a permission
   answer, a pick removed, an image unstaged, the picks synced — and its draft, picks, busy flag,
   open state and pending reset are signals its morph contract seeds where missing. The panel
   renders one part alone when its props name it, on the same instance, which is how a stream
   patches an item, the status row, the chips, the staged images or the log in place.")

(Module ui-components-dialog
  "A dialog, registered with the UI library as one block: a native <dialog> opened by its own
   trigger, with a title that labels it, a body, and optionally a footer of closing controls.

   HIDES the native-dialog mechanics: the trigger names it in commandfor and asks for show-modal,
   so the platform owns the top layer, focus moving in and back out, and Escape, and whether it
   is open lives in the DOM alone — no script and no signal holds it. Its close button and
   footer buttons are the control set's. Its interface is the dialog block registration, whose
   props are a :title and :trigger, :body and optional :footer render functions.

   Its parts carry data-scope and data-part, and their ids and ARIA derive from one instance id,
   so its title labels it. The slots — trigger, body, footer — are render functions each handed
   the attrs its part must spread; which control closed it is its one signal, `returned`.")

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
   different job from the blocks a person iterates on. Its rows listing workstreams, on the board
   and on Needs you, say which workstreams have a side chat with news. The top bar's layout is
   its: one icon and count per needs kind for the focused project, and a project drop-down whose
   entries say where the person is needed, all read from the tally the server hands it."
  (Operation rail-status-fragment "The top bar's live bits, from the request's tally: the focused project's count of each needs kind, each drop-down entry's mark, and how the daemon is."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation fire-signal "The signal name for one field of a fire form."
    {:signature [:=> [:catn [:trigger-name :any] [:k :keyword]] :string]})
  (Operation fleet-card "What the fleet costs, and whether another session fits."
    {:signature [:=> [:catn [:fleet [:maybe :map]] [:scope [:? [:maybe :string]]]] :any]})
  (Operation ops-panel-fragment "The ambient ops chrome: daemon, halt, breakers, triggers, fleet."
    {:signature [:=> [:catn [:ctx :map]] :any] :delegates [fleet-card fire-signal]})
  (Operation shell
    "The page chrome every page sits inside: a top bar — the project drop-down, marking each project
     the person is needed in, and the focused project's needs kinds, an icon and count each — and a
     sidebar of the focused project's slices."
    {:signature [:=> [:catn [:ctx :map] [:body :any]] :any] :delegates [assembled-stylesheet lib/script]})
  (Operation needs-fragment "The home column: what needs the person in the focused project, one section per kind — or the one kind the request names — each headed by its mark and count."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation gate-action-confirm-fragment "The immediate confirmation a gate action swaps in."
    {:signature [:=> [:catn [:action-id :any] [:project ProjectName] [:ws-id WorkstreamId]
                            [:pane-id :string]] :any]})
  (Operation gate-action-skip-fragment "What is rendered when a gate action is not offered."
    {:signature [:=> [:catn [:msg :string] [:project ProjectName] [:ws-id WorkstreamId]
                            [:pane-id :string]] :any]})
  (Operation needs-page "The home page: the list of what waits on you beside the pane of the workstream a row selects."
    {:signature [:=> [:catn [:ctx :map] [:data :map]] :any]
     :delegates [shell needs-fragment workstream-pane]})
  (Operation workstreams-fragment "The selected tab's stage groups."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation workstream-pane "The read-only ledger pane for one workstream."
    {:signature [:=> [:catn [:ws :map] [:session-dev-states [:? :any]]] :any]
     :delegates [status-heading state-mark
                 report/report->markdown render]})
  (Operation pickup-bar "The paste-a-ticket bar."
    {:signature [:=> [:catn [:project ProjectName]] :any]})
  (Operation pickup-result-fragment "What a pickup attempt swaps in."
    {:signature [:=> [:catn [:result :map] [:opts :map]] :string]})
  (Operation workstreams-page "The Legacy slice: the focused project's board and its ledger pane."
    {:signature [:=> [:catn [:ctx :map] [:data :map]] :any]
     :delegates [shell workstreams-fragment workstream-pane pickup-bar]})
  (Operation workstream-page
    "One workstream's own page: its pane alone, at the content area's full width — the second host
     of workstream-pane, drawn from the same selection the board's pane is."
    {:signature [:=> [:catn [:ctx :map] [:data :map]] :any]
     :delegates [shell workstream-pane]})
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
     them, the operational notices — host casualties to revive — each only
     when there is one: what nido wants a person to know that is no workstream's question."
    {:signature [:=> [:catn [:proposals :any] [:recovery [:maybe :map]]
                  [:holds [:maybe [:vector :map]]] [:queues [:maybe [:vector :map]]]
                  [:notices [:maybe :map]] [:host HostModel]] :any]})
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
  (Operation host-fragment
    "The host page's body: the card's figures, then each kind's processes, heaviest first."
    {:signature [:=> [:catn [:host HostModel]] :string]})
  (Operation host-page
    "The host on its own page: the card's figures, then each kind's processes, heaviest first."
    {:signature [:=> [:catn [:ctx :map] [:host HostModel]] :any]
     :delegates [shell host-fragment]})
  (Operation recovery-page "Session recovery on its own page."
    {:signature [:=> [:catn [:ctx :map] [:recovery [:maybe :map]]] :any]
     :delegates [shell recovery-fragment]})
  (Operation proposal-result-fragment "What deciding a proposal swaps in."
    {:signature [:=> [:catn [:result :map] [:proposals :any]] :any] :delegates [operations-fragment]})
  (Operation slice-page "A slice nothing populates yet, pointing at the Legacy board."
    {:signature [:=> [:catn [:ctx :map] [:slice :keyword]] :any] :delegates [shell]})
  (Operation structure-page
    "The Structure slice: fukan's explorer framed edge to edge, or why there is nothing to draw."
    {:signature [:=> [:catn [:ctx :map] [:explorer :map] [:view :string]] :any] :delegates [shell]})
  (Operation presentation-page
    "The Presentation slice: the focused project's catalog framed edge to edge through the
     dashboard's own forward, or a notice saying why there is nothing to frame."
    {:signature [:=> [:catn [:ctx :map] [:target :map]] :any] :delegates [shell]})
  (Operation development-page "Pages for working on the dashboard itself, one tile each."
    {:signature [:=> [:catn [:ctx :map]] :any] :delegates [shell]})
  (Operation not-found-page "The 404."
    {:signature [:=> [:catn] :any] :delegates [shell]})
  (Operation drains-fragment
    "The drains list — each open drain leading with its phase and the one act it owes the person,
     then its escalations, its recipe proposals each diffed against the current recipe, its census
     and trend, its slices with their misfits, its open plans and its residue — and what an act
     was refused for; every act it renders posts under the scope the page was requested from.
     Nothing in it is folded, since the poll re-renders it whole."
    {:signature [:=> [:catn [:drains [:vector :map]] [:notice [:? [:maybe :string]]]
                 [:scope [:? [:maybe :string]]]] :string]})
  (Operation drains-page
    "Every open drain, with the person's acts on it: approve a recipe, land a slice, close the
     drain — and, below the drains and folded while any is open, the start of a drain."
    {:signature [:=> [:catn [:ctx :map] [:drains [:vector :map]]] :any]
     :delegates [shell drains-fragment]}))

(Module ui-server
  "The dashboard's HTTP surface.

   HIDES which reads a request needs and in what scope it reads them, and how the gates and the
   side chats' unseen anchors are combined into the tally of what needs the person, by project
   and kind — counted once per request and handed to the page's chrome, which reads its counts,
   lists and drop-down marks from it.

   `read-rail-daemon` is a named seam over the daemon reading so a test can stub it."
  (Operation read-rail-daemon "The rail's daemon reading. A stubbing seam."
    {:signature [:=> [:catn] :map] :delegates [control/read-daemon-health]})
  (Operation derive-screen
    "Gather what the screen needs and derive it — the gate inbox's casualty card from the
     workstreams owed a revive, beside the gates and never among them. Without the board it
     refreshes no row and reads only the gates read-gates answers: what Needs you and the rail
     badge render."
    {:signature [:=> [:catn [:view-state :any] [:opts [:? :map]]] Screen]
     :delegates [work/screen work/owed-revivals work/read-gates]})
  (Operation wrap-slow-request-log
    "Wrap a handler so a request slower than the threshold leaves one line in the daemon log.
     The dashboard's own latency is otherwise unobservable after the fact."
    {:signature [:=> [:catn [:handler :any]] :any]})
  (Operation parse-findings-lines "A textarea of findings as items."
    {:signature [:=> [:catn [:s :string]] :any]})
  (Operation resolve-failure-msg "Why a gate action did not do what was asked."
    {:signature [:=> [:catn [:result :map]] :string]})
  (Operation page-routes
    "Every page handle-get renders to a browser — a parameterised one with how a concrete
     instance of it is found — and every route it answers that is not one, with why. Kept by
     hand beside handle-get; what a census measures."
    {:signature [:=> [:catn] :map] :delegates [work/newest-workstream]})
  (Operation handle-request "One request, routed."
    {:signature [:=> [:catn [:req :map]] :map]
     :delegates [derive-screen parse-findings-lines resolve-failure-msg
                 work/session-recovery
                 work/improvement-holds
                 structure/ensure! structure/forward
                 presentation/target presentation/forward]})
  (Operation start! "Start the dashboard server."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [handle-request]})
  (Operation stop! "Stop it."
    {:signature [:=> [:catn] :any]}))

(Module ui-tui
  "The terminal surface.

   One public entry, and everything else is private: the TUI is a rendering of the same screen
   the dashboard shows, so its internals are not a second model. The surface benchmark alone
   reaches two of those private renders through their vars, to time the TUI's board, detail and
   navigation, so they are its interface too."
  (Operation run-once "Run the TUI to completion."
    {:signature [:=> [:catn] :any]})
  (Operation board-rows "The spine board's rows, as foldable bands — what the benchmark times as
     the TUI's board."
    {:signature [:=> [:catn [:project :any] [:origin :any] [:collapsed [:? :any]] [:facet-filter [:? :any]]] :any]})
  (Operation environment-block "A workstream's environment panel body — what the benchmark times
     as the TUI's detail."
    {:signature [:=> [:catn [:project :any] [:ws-id :any]] :any]}))

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

(Module ui-catalog
  "The component catalog: /_catalog, and the page that frames it beside the catalog chat.

   HIDES that nido's catalog is the library's catalog over nido's registry and stylesheet, and how
   it sits beside the catalog chat — the page around the frame, which owns the chat's open state,
   select mode and the marks on the scenes. The catalog's chrome, its selection and its picker are
   the library's; nothing here frames, labels or selects a scene."
  (Operation catalog-page
    "The catalog from its own query string: the library's catalog page over nido's registry, in
     the stylesheet every nido chrome emits."
    {:signature [:=> [:catn [:query-string [:maybe :string]]] :string]
     :delegates [lib/catalog-page registry assembled-stylesheet]})
  (Operation chat-page
    "The catalog with its chat: the scene page in a frame beside the chat's side panel — closed,
     its toggle alone; open, the panel with Pick, which puts the frame into select mode, under the
     conversation's change bar, and the picks marked on the scenes. Reads nothing: the
     conversation, its change, where the frame points and whether it starts open are its caller's."
    {:signature [:=> [:catn [:transcript acp/Conversation] [:frame-src :string] [:view :map]
                            [:open? :boolean] [:change-bar :any]] :any]
     :delegates [lib/render lib/kit]}))
