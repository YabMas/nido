(ns canvas.coordinator.lane.conductor
  "Self-spec: `nido.coordinator.lane.conductor` — the conductor standing between a person and the
   seats executing a workstream."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.drive :as drive]
            [canvas.coordinator.lane.embodied-strata :as embodied-strata]
            [canvas.coordinator.lane.seats :as seats]
            [canvas.coordinator.record.seats :as rseats :refer [SeatId]]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.record.state :refer [WorkstreamId SessionName]]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.session.commit-gate :as commit-gate]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind ConductDecision
  "The conductor's answer to one hand-back: instructions to one or more seats, or an escalation
   naming what the person is needed for."
  [:map [:hand-back :string]
        [:decision [:or [:tuple [:enum :instruct]
                                [:vector {:min 1} [:map [:seat SeatId] [:text :string]]]]
                        [:tuple [:enum :escalate] [:map [:needs :string]]]]]])

(Kind DecideResult
  "What decide! answers: the decision accepted with the entry it appended, or refused by a named
   reason."
  [:or [:tuple [:enum :accepted] :map]
       [:tuple [:enum :refused] [:enum :not-the-conductor :not-a-hand-back :already-answered
                                    :not-a-seat]]])

(Module lane-conductor
  "Who decides what a workstream's seats do next, and what reaches the person.

   HIDES what a hand-back is — a turn end returns to whoever gave the instruction it ended, as
   recorded when nido queued it: one recorded as given for a Run is that Run's; every other
   instruction nido gives — a seat message, a decided instruction, an occupant's brief, a gate
   reply no Run awaits — is the conductor's, on every session nido drives on the workstream but
   the conductor; a turn nido did not
   give is a person's, which holds the session, so its end is no hand-back and the hold, handed
   back, is — on any session nido drives on the workstream — and how each becomes
   exactly one decision: the hand-back logged once to the conductor with the session and its seat, the turn's last
   message and the position the pipeline derives at that moment; the conductor's answer recorded
   as instructions to one or more seats or an escalation, with the position it was decided at; a
   conductor turn that ends leaving the hand-back it was
   given unanswered closed by an escalation nido writes. An instruction goes to each seat it names
   as a seat message; an escalation parks the workstream on a blocker the person owes, which the
   person's act at the step is recorded as answering when the escalation was at a person's step;
   and each act of the person's recorded on the workstream is one hand-back to the conductor — the
   blocker's answer nido records for an act being carried by that act's. Also the
   conductor's lifecycle against its workstream's — started by a conduct pass once work has started
   on the open workstream since conductors began on the host — a session recorded or a hand-back
   logged — and while its session is not running, retired by a pass once the workstream is closed — and
   what it is briefed with: the records at its start and an escalation guidance document edited
   without changing code. And that a workstream a person or a Run starts its first session on is
   carried: that session occupies its whole seat, and whenever the seats change the embodiment that carries them
   occupies them. Which commands are a person's act is its own; how a command is read for what it
   does is the commit gate's."
  {:child [ConductDecision DecideResult]}
  (Operation carry!
    "Carry the workstream a person's start just recorded a session on, once the start has brought
     it up, or a Run's session once its conversation is started: when its current generation fixes
     no ids and its whole seat has no occupant, that session becomes the occupant, its first
     instruction the brief its start was given when one was, on the model it was given when one
     was, given by the start when its caller says the start was not made on the conductor's
     instruction (starter); otherwise nothing changes. Called by
     the session-up verb, spawn, the work plane's start and the daemon's start of a Run's
     conversation, never by birth or the orphan sweep."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session SessionName]
                      [:brief [:? [:maybe :string]]] [:model [:? [:maybe :string]]]
                      [:given-by [:? [:maybe [:enum :start]]]]] :map]
     :delegates [seats/default-embodiment seats/occupy!]})
  (Operation conductors!
    "On every conduct pass: start, on its one conductor record and a brief of the seats, the
     governing design and the position as the records give them at that start and the escalation
     guidance, the conductor of
     each open workstream of a project that work has started on — a session other than its
     conductor recorded on it, or a hand-back logged on it, since conductors began on the host, an
     instant recorded once and never moved — whose conductor's session is not running, and retire
     the conductor of each closed or unworked one — a ticket a person settled in Notion closing it as
     the pipeline places it. A workstream not worked since gets none."
    {:signature [:=> [:catn [:project ProjectName]] [:sequential :map]]
     :delegates [workstream/list-ids workstream/read-ws seats/conductor! seats/retire-conductor!
                 seats/handed-back-since? rseats/seats standing/governing-design drive/of
                 drive/settled-in-notion]})
  (Operation reseat!
    "On every conduct pass, for a carried workstream: retire each occupant whose seat the current
     seats do not include, and occupy each current seat with the embodiment that carries it — the
     default embodiment while the generation fixes no ids, the strata embodiment while it fixes
     strata — escalating, by name, seats no embodiment carries and a seat whose start failed, and
     answering the latter once every seat is held."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [seats/reseat! seats/held seats/default-embodiment seats/occupy! embodied-strata/carry! embodied-strata/refused? drive/park!]})
  (Operation hand-backs!
    "From one conduct pass's report, log to the workstream's conductor each turn end, on a session
     nido drives other than the conductor, that ended an instruction nido gave and recorded as
     given for no Run — once per turn end — with the session, its seat when it has one, the
     position the pipeline derives at that moment, and the governing design and seats as the
     records give them then. A turn nido did not give is the person's, and
     its end is logged to no one."
    {:signature [:=> [:catn [:report :map]] [:sequential :map]]
     :delegates [drive/of seats/occupants seats/post! standing/governing-design rseats/seats]})
  (Operation hand-back!
    "Log a person's hold on any session nido drives on the workstream, handed back, to the
     conductor — once per hand-back — as hand-backs! logs a turn end."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session SessionName]] :map]
     :delegates [drive/of seats/post!]})
  (Operation decide!
    "Record the conductor's answer to a hand-back and carry it out, as one step under the ledger's
     append lock, so no seat it names can be removed between: everything is checked before
     anything is written, an escalation parks the workstream on the blocker it then cites, and an
     instruction is posted to each seat it names once the decision is recorded. Refused, by name,
     from any session but the conductor's, for no hand-back or one already answered, and for an
     addressee that is not a seat when it is recorded; a decision the ledger refuses writes and
     posts nothing."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:decision ConductDecision]] DecideResult]
     :delegates [drive/of drive/park-held! seats/post-held! workstream/appending!]})
  (Operation awaiting-answer?
    "Whether the workstream is parked on a blocker a conductor's escalation raised and the person
     has not answered, whatever session the work runs in."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :boolean]
     :delegates [workstream/entries-of]})
  (Operation answer!
    "Record a person's reply to the open blocker a conductor's escalation parked the workstream on
     as that blocker's answer; nil, recording nothing, when the open blocker is no conductor's."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:reply :string]] [:maybe :map]]
     :delegates [workstream/entries-of workstream/append-entry!]})
  (Operation answers!
    "On every conduct pass, for each open workstream of a project: record the person's act at the
     step a conductor's escalation at a person's step named as that escalation's blocker's answer,
     citing the act; and log each act of the person's recorded on the workstream since its
     conductor's record was made — a reply recorded as a blocker's answer among them, never a
     blocker's answer nido recorded for an act — to its conductor as a hand-back, once per entry,
     with the position the pipeline derives at that moment."
    {:signature [:=> [:catn [:project ProjectName]] [:sequential :map]]
     :delegates [workstream/list-ids workstream/entries-of workstream/append-entry! drive/of
                 seats/post!]})
  (Operation close-unanswered!
    "When a conductor turn ends, escalate the hand-back delivered to it for that turn — the one
     delivered under the ended instruction's token — if the turn left it unanswered, naming that
     the conductor reached no decision. A hand-back not yet delivered, or delivered for a turn that
     has not ended, stays owed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session SessionName]
                     [:instruction [:maybe :map]]] [:sequential :map]]
     :delegates [seats/traffic drive/park-held! workstream/appending!]})
  (Operation refusal
    "Why a command must not run, or nil: a person's act — an intent, a design grant, a triage
     acceptance, a phase gate, a blocker's answer, a landing, a PR merge or a close — run from the
     conductor's session, or from any other session's in a turn following an instruction the
     conductor answers for and that no start gave, judged over what the commit gate reads the
     command to do. Pushing, whatever the branch, and opening or readying a PR are not a person's
     act, and neither is a design round, whose carried grant grants nothing the person did not."
    {:signature [:=> [:catn [:input :map]] [:maybe :string]]
     :delegates [commit-gate/acts]})
  (Operation starter
    "Who gives the first instruction of a session a start made from a directory carries or drives:
     the start, unless the directory is in a nido session whose turn follows an instruction the
     conductor answers for and no start gave — then no one, so the conductor answers for it and a
     start made on its instruction hands its session no act the gate refuses the conductor."
    {:signature [:=> [:catn [:cwd :string]] [:maybe [:enum :start]]]}))
