(ns canvas.claims.workstream-status
  "The claims the workstream-status design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [intake-kind lane-pipeline of status]]
            [canvas.coordinator.lane.github-issue-intake :refer [poll-and-reconcile!]]
            [canvas.coordinator.lane.notion-sync :refer [lane-notion-sync]]
            [canvas.coordinator.record.activity :refer [read-live]]
            [canvas.coordinator.record.runs :refer [live? record-runs]]
            [canvas.coordinator.record.session :refer [record-session]]
            [canvas.coordinator.record.standing :refer [open-findings]]
            [canvas.coordinator.record.tickets :refer [status-of]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.view.activity :as activity]
            [canvas.coordinator.view.workstreams :refer [workstream-row ws-source]]
            [canvas.coordinator.work :refer [list-workstreams]]))

(Claim one-status
  "Every surface's position for a workstream comes from status, and its owed-by and live from the one activity `of` reads over status's facts and the core's state of the workstream; no row, pane or TUI line decides any of the three from session phase, ticket status, a stored :stage or a cached Notion status."
  {:about [status activity/of workstream-row] :evidence "round"})

(Claim owed-by-from-mode
  "Owed-by is read off the mode of the next move status names, the action of's answer carries as :next, and off nothing else: :person when that move runs in :human mode — :published among the positions owing one, whose next move is landing — :machine for :mechanical, :agent for :authoring or :working-copy, :nobody when the position is terminal and no move is owed."
  {:about [status] :evidence "round"})

(Claim answers-are-recorded
  "An open workstream holding any blocker that no answer entry names is placed at :blocked, ahead of open findings and every arc position, and only closure outranks it; the question it waits on is the newest such blocker. Each blocker is cleared by an answer entry naming its seq and by nothing else: not a later entry of any kind, and not an answer naming another blocker — so answering a newer halt leaves an older unanswered one still holding the workstream at :blocked."
  {:about [lane-pipeline] :evidence "round"})

(Claim index-is-rebuildable
  "The index splits at this change. Rows for entries written before it are frozen as part of the ledger, because their :at and :amended-by exist nowhere else; every entry written after it carries those facts in its own file. The index is a cache rebuilt from the directory plus the frozen rows: a record that lost it reads the rebuilt one, and deleting it changes no answer."
  {:about [record-workstream] :evidence "round"})

(Claim notion-edits-are-entries
  "A status change a person makes in Notion reaches a workstream only as a ledger entry: one naming the status seen — appended by the Notion poller, or, for a status cached before the poller recorded entries, once by the status backfill — or the close or stage choice the Notion sync sweep records for it; status reads no Notion cache."
  {:about [lane-notion-sync status] :evidence "round"})

(Claim layers-are-entries
  "A finished stack layer is a ledger entry citing the design it was built under and naming its index and the count planned; a seat's layer names its seat too, its index being its position among that seat's layers and its count that seat's. :implementing is a trail position, placed where the fold places the trail: every clause ahead of the trail outranks it — closure, open findings, an unanswered blocker, a retraction, an invalidated design, a superseded goal — exactly as each outranks every other arc position. Beneath those, a workstream whose current design still stands and was approved or cleared, and has layer entries recorded since its newest phase gate with no implementation, review or draft-PR entry under it recorded since that gate, is at :implementing, owing :implement in :working-copy mode. A layer under a design not yet approved or cleared places nothing, and once any of those records exists in the phase, layer entries move nothing until the next gate. The position carries progress: done is the number of distinct layer identities — a seat with an index, or an index naming no seat — among all the layer entries citing that design, in any phase, and planned the sum, over each seat and over the layers naming none, of the count the earliest of its entries names; so recording a finished layer twice changes nothing, and two seats' layers at one index are two layers."
  {:about [coordinator-report of] :evidence "round"})

(Claim findings-from-ledger
  "Open findings are the items of the newest findings entry of the current generation's span that no later resolution entry names, answered by record-standing at the record-status level; the position and the board's count both ask it and compute neither themselves."
  {:about [open-findings] :evidence "round"})

(Claim no-stored-status
  "No mutable record holds a value status derives: a session has no autonomy phase, a workstream no stored :stage, findings tracker or :closed, and a ticket no :status; close, reopen, a person's choice of stage, and a ticket's acceptance or dismissal are entries. The one fact the stored :stage carried that status does not derive — that a one-off was born disposable — is the :scratch entry its birth appends, so retiring the field loses no answer the record gave."
  {:about [record-workstream record-session] :evidence "round"})

(Claim issue-is-an-intake-entry
  "A workstream raised from a GitHub issue holds an :issue entry naming the issue: a registered kind the issue intake appends as it mints the workstream, and the backfill appends for one minted before this change. The position vocabulary reads it as an intake record, as it reads :triage and :scratch: :issue is a member of the kinds of tells an empty ledger from an illegible one by, belongs to the intent stage, and carries no position of its own, so a workstream whose only entry is its :issue places at :intake. intake-kind answers :issue from that kind. The position level reads no external ref of a workstream and calls no view function: intake-kind stops asking ws-source, and of's diagnostic :read stops reading the ticket status it reached through the workstream's Notion ref."
  {:about [poll-and-reconcile! intake-kind of coordinator-report] :evidence "round"})

(Claim control-reads-runs
  "The coordinator's control decisions — the trigger budget, resume's precondition, the review sweep, the driver's park — read run records and status, never a session phase."
  {:about [record-runs status] :evidence "round"})

(Claim ticket-status-derived
  "A ticket's triage status is derived, never stored: status-of reads the workstream carrying it — dismissed while a dismissal stands; planning while a plan run is open or a choice to put it in progress holds; implementing once it is closed otherwise or records work past its triage; triaged once a person accepted its newest verdict, or the ticket as reported; investigating while an investigating run is open; awaiting input while a verdict waits — and nil otherwise, which is what makes it re-triable. A reopen that undoes a dismissal sets aside everything recorded before it."
  {:about [status-of] :evidence "round"})

(Claim board-stage-from-records
  "A non-bare board row's needs-you, failed, engagement, live and what is underway are fields of one activity, `of` over the row's one observation. A row whose ledger closure settles it — any outcome but :between-phases — or that the core closed has needs-you and failed off and engagement :settled; on any other non-bare row needs-you is whether the core asks the person a question and failed whether it holds a machine failure it raised as an ask — a row holding both carries both — and engagement is read off that same activity."
  {:about [workstream-row list-workstreams activity/of] :evidence "round"})
