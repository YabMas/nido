(ns canvas.claims.workstream-status
  "The claims the workstream-status design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [ensure-workstream! intake-kind lane-pipeline of status]]
            [canvas.coordinator.lane.github-issue-intake :refer [poll-and-reconcile!]]
            [canvas.coordinator.lane.notion-sync :refer [lane-notion-sync]]
            [canvas.coordinator.lane.work :refer [birth! reap! scratch?]]
            [canvas.coordinator.record.activity :refer [read-live]]
            [canvas.coordinator.record.runs :refer [live? record-runs]]
            [canvas.coordinator.record.session :refer [record-session]]
            [canvas.coordinator.record.standing :refer [open-findings]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.view.workstreams :refer [workstream-row ws-source]]
            [canvas.coordinator.work :refer [adopt-orphans!]]))

(Claim one-status
  "Every surface's position, owed-by and live for a workstream come from status; no row, pane or TUI line decides any of the three from session phase, ticket status, a stored :stage or a cached Notion status."
  {:about [status workstream-row] :evidence "round"})

(Claim owed-by-from-mode
  "Owed-by is read off the mode of the next move status names, the action of's answer carries as :next, and off nothing else: :person when that move runs in :human mode — :published among the positions owing one, whose next move is landing — :machine for :mechanical, :agent for :authoring or :working-copy, :nobody when the position is terminal and no move is owed. A position does not fix the mode by itself, because the next move is resolved from more than the position — :design-decided owes a mechanical clearance when its design owes no person and a human approval when it does — so two workstreams at one position may be owed by different parties, and owed-by follows the move. Stalled is owed-by :agent with nothing live."
  {:about [status] :evidence "round"})

(Claim answers-are-recorded
  "An open workstream holding any blocker that no answer entry names is placed at :blocked, ahead of open findings and every arc position, and only closure outranks it; the question it waits on is the newest such blocker. Each blocker is cleared by an answer entry naming its seq and by nothing else: not a later entry of any kind, and not an answer naming another blocker — so answering a newer halt leaves an older unanswered one still holding the workstream at :blocked."
  {:about [lane-pipeline] :evidence "round"})

(Claim live-is-a-held-lock
  "Live is true only while a lock held for the life of the working process is held — the activity claim, or a run's lock taken by a wrapper that starts the agent and exits with it — or, for a human session, while its ports answer; no stored phase decides it, and the launcher's death does not release a run lock while its agent runs."
  {:about [status live? read-live] :evidence "round"})

(Claim index-is-rebuildable
  "The index splits at this change. Rows for entries written before it are frozen as part of the ledger, because their :at and :amended-by exist nowhere else; every entry written after it carries those facts in its own file. The index is a cache rebuilt from the directory plus the frozen rows: a record that lost it reads the rebuilt one, and deleting it changes no answer."
  {:about [record-workstream] :evidence "round"})

(Claim notion-edits-are-entries
  "A status change a person makes in Notion reaches a workstream only as a ledger entry the Notion poller appends, naming the status it saw; status reads no Notion cache."
  {:about [lane-notion-sync status] :evidence "round"})

(Claim layers-are-entries
  "A finished stack layer is a ledger entry citing the design it was built under and naming its index and the count planned. :implementing is a trail position, placed where the fold places the trail: every clause ahead of the trail outranks it — closure, open findings, an unanswered blocker, a retraction, an invalidated design, a superseded goal — exactly as each outranks every other arc position. Beneath those, a workstream whose current design has layer entries and no implementation, review or draft-PR entry under it is at :implementing, owing :implement in :working-copy mode; once any of those exists, layer entries are read for progress alone and move nothing. The position carries progress: done is the number of distinct indices among the layer entries citing that design, planned the count the earliest of them names, so recording a finished layer twice changes nothing."
  {:about [coordinator-report of] :evidence "round"})

(Claim findings-from-ledger
  "Open findings are the items of the newest findings entry that no later resolution entry names, answered by record-standing at the record-status level; the position and the board's count both ask it and compute neither themselves."
  {:about [open-findings] :evidence "round"})

(Claim no-stored-status
  "No mutable record holds a value status derives: a session has no autonomy phase, a workstream no stored :stage, findings tracker or :closed, and ticket status is no input to where a workstream is; close, reopen and a person's choice of stage are entries. The one fact the stored :stage carried that status does not derive — that a one-off was born disposable — is the :scratch entry its birth appends, so retiring the field loses no answer the record gave."
  {:about [record-workstream record-session] :evidence "round"})

(Claim scratch-is-a-birth-entry
  "A one-off's disposability is a :scratch entry, a registered kind birth! appends as it mints the workstream — the ledger's record that it was born with no ticket and may be discarded with its session, as :fork records how a child arrived. birth! is the marker's one writer: ensure-workstream! births a fire's workstream unmarked whatever its trigger says, and no other path appends the kind. The position vocabulary reads the kind as an intake record: :scratch is one more member of the kinds of tells an empty ledger from an illegible one by, belonging to the intent stage as :ticket and :triage do, and it carries no position of its own — so a one-off whose only entry is its birth places at :intake, as an empty ledger does, and is never :unplaceable. scratch? answers from the kind; reap! and the orphan sweep's yield discard a one-off only while it is marked, holds no external ref, holds no entry but its birth and owns no other session; intake-kind answers :scratch from the kind, as it answers :triaged and :proposal, and ws-source buckets a workstream :scratch from the same kind, so a described-intent workstream — ref-less, unmarked, entry-less until its agent writes the intent — is kept by both reapers and buckets as it does today."
  {:about [birth! scratch? reap! adopt-orphans! intake-kind ensure-workstream! of ws-source coordinator-report] :evidence "round"})

(Claim issue-is-an-intake-entry
  "A workstream raised from a GitHub issue holds an :issue entry naming the issue: a registered kind the issue intake appends as it mints the workstream, and the backfill appends for one minted before this change. The position vocabulary reads it as an intake record, as it reads :triage and :scratch: :issue is a member of the kinds of tells an empty ledger from an illegible one by, belongs to the intent stage, and carries no position of its own, so a workstream whose only entry is its :issue places at :intake. intake-kind answers :issue from that kind. The position level reads no external ref of a workstream and calls no view function: intake-kind stops asking ws-source, and of's diagnostic :read stops reading the ticket status it reached through the workstream's Notion ref."
  {:about [poll-and-reconcile! intake-kind of coordinator-report] :evidence "round"})

(Claim control-reads-runs
  "The coordinator's control decisions — the trigger budget, resume's precondition, the review sweep, the driver's park — read run records and status, never a session phase."
  {:about [record-runs status] :evidence "round"})
