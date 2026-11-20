(ns canvas.coordinator.lane.conductor
  "Self-spec: `nido.coordinator.lane.conductor` — the conductor standing between a person and the
   seats executing a workstream."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.agent.substrate :as substrate]
            [canvas.coordinator.lane.drive :as drive]
            [canvas.coordinator.lane.embodied-strata :as embodied-strata]
            [canvas.coordinator.lane.landing :as landing]
            [canvas.coordinator.lane.seats :as seats]
            [canvas.coordinator.record.seats :as rseats :refer [SeatId]]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.session-events :as session-events]
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
                                    :already-asked :overtaken :not-a-seat]]])

(Module lane-conductor
  "Who decides what a workstream's seats do next, and what reaches the person.

   HIDES what a hand-back is — a turn end returns to whoever gave the instruction it ended, as
   recorded when nido queued it: one recorded as given for a Run is that Run's; every other
   instruction nido gives — a seat message, a decided instruction, an occupant's brief, a gate
   reply no Run awaits — is the conductor's, on every session nido drives on the workstream but
   the conductor; a turn nido did not give is a person's, and on an activated workstream its end
   is a hand-back too, marked as a person's turn — and how each becomes
   exactly one decision: the hand-back logged once to the conductor with the session and its seat, the turn's last
   message and the position the pipeline derives at that moment; the conductor's answer recorded
   as instructions to one or more seats or an escalation, with the position it was decided at; a
   conductor turn that ends leaving the hand-back it was
   given unanswered closed by an escalation nido writes. An instruction goes to each seat it names
   as a seat message; an escalation parks the workstream on a blocker the person owes, which the
   person's act at the step is recorded as answering when the escalation was at a person's step;
   and each act of the person's recorded on the workstream is one hand-back to the conductor — the
   blocker's answer nido records for an act being carried by that act's. Also the
   conductor's lifecycle against its workstream's — started by a conduct pass while the activated
   workstream is open and worked, a person having typed a prompt into one of its sessions within
   the last day, or owes its conductor a hand-back, and its session is not running; retired by a
   pass once it owes none and is closed or no longer worked — and
   what it is briefed with: the records at its start and an escalation guidance document edited
   without changing code. And that a workstream a person or a Run starts its first session on, or a
   person activates from a session, is carried: that session occupies its whole seat, and whenever
   the seats change the embodiment that carries them occupies them; and when an undelivered
   instruction no occupant nido drives can take is raised to the person. Which commands are a person's act is its own; how a command is read for what it
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
     conversation, and by a person's activation from a session — which marks the occupant carried
     by activation and starts no conversation on it — never by birth, the orphan sweep or the
     activation a start makes of the session it is itself starting."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session SessionName]
                      [:brief [:? [:maybe :string]]] [:model [:? [:maybe :string]]]
                      [:given-by [:? [:maybe [:enum :start :activation]]]]] :map]
     :delegates [seats/default-embodiment seats/occupy!]})
  (Operation prompted
    "The sessions a person typed a prompt in within the last day, as #{[project session-name]} —
     read once a conduct pass and handed to conductors! and every reseat! — or :unread when the
     prompt log could not be read, on which neither decides anything."
    {:signature [:=> [:catn] [:or [:set [:tuple :string :string]] [:enum :unread]]]
     :delegates [session/prompted-sessions session-events/prompts-since]})
  (Operation conductors!
    "On every conduct pass: start, on its one conductor record and a brief of the seats, the
     governing design and the position as the records give them at that start and the escalation
     guidance, the conductor of each activated workstream of a project whose conductor's session
     is not running and that owes its conductor a hand-back — one posted after its latest close,
     or ever, that no decision answers — open or closed, or that is open and worked — a person
     typed a prompt into one of its sessions within the window the pass read; and retire the
     conductor of each that owes it none and is closed or unworked — a ticket a person settled in
     Notion closing it as the pipeline places it. A prompt nido sent or the harness delivered from
     another agent is no person's, so nothing nido starts makes a workstream worked, and nothing
     worked long ago keeps it so."
    {:signature [:=> [:catn [:project ProjectName] [:prompted [:? [:or [:set [:tuple :string :string]] [:enum :unread]]]]]
                 [:sequential :map]]
     :delegates [prompted workstream/list-ids workstream/read-ws seats/conductor!
                 seats/retire-conductor! seats/hand-backs rseats/seats standing/governing-design drive/of
                 drive/settled-in-notion]})
  (Operation reseat!
    "On every conduct pass, for a carried workstream that is worked — as conductors! reads it, so a
     workstream nobody types into is left as it is: retire each occupant whose seat the
     current seats do not include, and occupy each current seat with the embodiment that carries
     it — the default embodiment while the generation fixes no ids, the strata embodiment while it
     fixes strata — starting an occupant activation carried once a seat message is owed to it, on
     those messages; escalating, by name, seats no embodiment carries and a seat whose start
     failed, and answering the latter once every seat is held. Then, whatever it decided, name by
     a blocker each undelivered instruction to a current seat no occupant nido drives can take,
     answering it once the instruction is delivered or its seat is no longer current."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]
                      [:prompted [:? [:or [:set [:tuple :string :string]] [:enum :unread]]]]] :map]
     :delegates [prompted seats/reseat! seats/held seats/default-embodiment seats/occupy! embodied-strata/carry! embodied-strata/refused? drive/park!]})
  (Operation undelivered!
    "After a pass's reseat! of a workstream, whatever it decided and whatever the workstream's
     activation, pause or closure: name, by a blocker carrying the seat-message's id, each
     undelivered instruction to one of its current seats that no live session nido drives is owed
     — once per instruction — and answer, as nido, each such blocker once its instruction is
     delivered, is owed to a live session nido drives, or its seat is no longer current."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:sequential :map]]
     :delegates [seats/traffic-all seats/occupants seats/owed rseats/seats workstream/read-ws]})
  (Operation grant-landing!
    "Record the person's Land on the gate as their :merge grant to the seats of an activated
     workstream: one :grant entry, which instruct-landing! then instructs. Writes it only while the
     workstream is activated and its position is :published (drive/of); anywhere else — a drain's
     slice at :slice-cleared included — it writes nothing and answers why."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [workstream/read-ws standing/activated? drive/of]})
  (Operation instruct-landing!
    "For each activated workstream holding a person's unspent :merge grant
     (workstream/unspent-grants), post each seat it holds one instruction per grant, keyed on the
     :seq of the entry that gave it, to land by `bb nido:workstream:pr:land` — naming the
     workstream's own PR where the project's landing names a PR (landing/names-pr?). Run every
     daemon pass, so a grant however recorded — the gate, an escalation's answer, the chat — is
     instructed once. Answers the instructions posted."
    {:signature [:=> [:catn [:project ProjectName]] [:sequential :map]]
     :delegates [workstream/read-ws workstream/unspent-grants standing/activated? seats/held seats/post! landing/names-pr?]})
  (Operation tell!
    "Pass word on to the workstream's conductor from the workstream's chat, which it must run in:
     posted once as a hand-back naming the chat's session — and, when the person's word grants the
     seats an act, recorded first as their :grant, whose hand-back it then is. Refused from
     any other session, for blank text, for a grant of an act no grant may hand, and on a
     workstream that is closed or not activated."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:text :string]
                      [:grants [:? [:maybe [:sequential :keyword]]]]] :map]
     :delegates [session/read-session workstream/read-ws standing/activated? seats/post!]})
  (Operation hand-backs!
    "From one conduct pass's report, log to an activated workstream's conductor each turn end of a
     session on it other than the conductor and the workstream's chat, faceted or not, that ended
     no instruction recorded for a Run — once per turn end, a person's turn marked as such — with
     the session, its seat when it has one, the turn's last message, the position the pipeline
     derives at that moment, and the governing design and seats as the records give them then."
    {:signature [:=> [:catn [:report :map]] [:sequential :map]]
     :delegates [drive/of seats/occupants seats/post! standing/governing-design rseats/seats]})
  (Operation stops!
    "One pass over a project's open workstreams with a session nido drives, a turn conduct holds
     open, or a dead-turn blocker still standing: a blocker nido wrote
     that no longer asks anything is answered by nido, saying why — a review stage's halt, at any
     outcome, once a record of the kind that stage reviews that no review loop's amender wrote is
     on the ledger after it, and
     nido's escalation of a hand-back the conductor did not decide once the conductor decides a later
     hand-back from the same session; a dialog a driven session has stood on for
     two minutes becomes a :blocker nido writes for a person, worded with what the dialog asks and
     answered by nido once it closes; on an activated workstream not paused, an open turn whose
     process every witness says is gone becomes a :blocker for a person carrying the command that
     resumes its conversation, answered by nido once the turn closes or a process shows; an
     activated workstream that has read stalled for ten minutes
     since its last turn end and its ledger's newest entry is handed back to its conductor, once
     per turn end, with why. Sends nothing to a session and starts none."
    {:signature [:=> [:catn [:project ProjectName] [:now-ms :int]] [:sequential :map]]
     :delegates [drive/status drive/reviewed-kind seats/post! substrate/pending-dialog
                 workstream/entries-of workstream/amended-seqs workstream/append-entry!]})
  (Operation answers-in-place!
    "From one conduct pass's report, answer as nido each open escalation the conductor decided on
     a hand-back marked as a person's turn, and the blocker it parked, once the person prompts that
     same session after the decision, citing that prompt. No other escalation is answered by a
     prompt."
    {:signature [:=> [:catn [:report :map]] [:sequential :map]]
     :delegates [seats/traffic workstream/entries-of workstream/append-entry!]})
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
  (Operation answer-escalation!
    "Record a person's answer given on the card — a picked branch, or one answer per question — to
     the blocker at a position when a conductor's escalation parked it, for the conductor, resuming
     no seat; :closed, recording nothing, when that blocker is answered already; nil when no
     conductor's escalation parked it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:blocker-seq :int] [:answer :map]]
                 [:or :nil :map :keyword]]
     :delegates [workstream/entries-of workstream/append-entry!]})
  (Operation answers!
    "On every conduct pass, for each open workstream of a project: record the person's act at the
     step a conductor's escalation at a person's step named as that escalation's blocker's answer,
     citing the act; and log each act of the person's recorded on the workstream since its
     conductor's record was made — a reply recorded as a blocker's answer among them, never a
     blocker's answer nido recorded for an act, nor the close that closes the workstream as its
     ledger stands — to its conductor as a hand-back, once per entry, with the position the
     pipeline derives at that moment."
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
     conductor's session, or from any other session's conversation in a turn whose latest prompt
     since its previous Stop carries the token of an instruction the conductor answers for and
     that no start gave, judged over what the commit gate reads the command to do. A turn whose
     latest prompt carries no such token is the person's, and its acts are allowed. Pushing, whatever the branch, and opening or readying a PR are not a person's
     act, and neither is a design round, whose carried grant grants nothing the person did not."
    {:signature [:=> [:catn [:input :map]] [:maybe :string]]
     :delegates [commit-gate/acts]})
  (Operation starter
    "Who gives the first instruction of a session a start made from a directory carries or drives:
     the start, unless the directory is in a nido session whose driven conversation's turn runs
     on an instruction the conductor answers for and no start gave — then no one, so the conductor answers for it and a
     start made on its instruction hands its session no act the gate refuses the conductor."
    {:signature [:=> [:catn [:cwd :string]] [:maybe [:enum :start]]]}))
