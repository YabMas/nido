(ns canvas.claims.send-back
  "The claims of the send-back design: once the person's word to the conductor sends a
   workstream back for rework from a landing, its :sent-back takes back the landing request and
   what that word supersedes, so it reads as work underway.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :as drive]
            [canvas.coordinator.lane.reentry :as reentry]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.coordinator.report :as report]
            [canvas.coordinator.view.activity :as activity]
            [canvas.coordinator.work :as work]
            [canvas.core.buffer :as buffer]
            [canvas.core.domain :as domain]
            [canvas.tasks.nido-workstream :as nido-workstream]
            [canvas.ui.surface :as surface]))

(Claim send-back-is-a-record
  "That the person's word sent a workstream back is one typed ledger entry, :sent-back, carrying the person's words and the landing position it was sent back from. record-workstream refuses it in every generic append, as refuse-unguarded-gate! refuses a :phase-gate, and appends it only through a guarded writer that nido-workstream's send-back verb alone calls, under the append lock and only if the ledger has not moved since the verb read the position. The verb appends it only while the workstream is placed :release-candidate, :published or :land-granted; at any other position it refuses, naming the position, and appends nothing."
  {:about [report/coordinator-report nido-workstream/nido-workstream workstream/record-workstream] :evidence "round"})

(Claim send-back-withdraws-the-asks
  "The person's word reaches the conductor either posted to its chat or, when an operator ask that is no machine failure is open, as person-says!'s answer to the first such ask by id. An ask the word answered is closed by that answer and awaits nobody; the landing request it asked about is the position, which the :sent-back takes back [candidate-again-only-after-new-review]. The send_back tool names, among the operator asks open on the workstream, those the rework makes moot; the brief tells the conductor to name each that asks about landing or about the candidate it sends back, and none that concerns a decision the rework leaves standing. The send-back verb hands the core the asks named in the one :send-back command whose commit also appends the :sent-back [send-back-is-a-record]: accepted, it closes each named ask as :withdrawn with a reason naming the send-back and leaves every ask it was not handed as it stands; it is refused whole, changing nothing, when a named ask is not open or is a :conductor-stopped ask, or when the ledger has moved since the verb read it, and the verb then exits non-zero naming the refusal. A send-back refused at its position check sends no command."
  {:about [nido-workstream/nido-workstream domain/core-domain buffer/brief] :evidence "round"})

(Claim the-conductor-decides-and-records
  "The conductor that hears the person's word decides whether it sends the work back, and records that through its send_back tool before it directs any unit to the rework. Every brief says so, among its rules beside the rule never to ask the person whether to land, for a workstream at :release-candidate, :published or :land-granted, and says that a word which only asks a question or says to land sends nothing back. tell* and person-says! still append nothing to the ledger."
  {:about [buffer/core-buffer buffer/brief] :evidence "round"})

(Claim conductor-acts-through-core-commands
  "Each nido-conductor tool is a core command or a read of core state, except send_back, which runs nido-workstream's send-back verb as a subprocess and is the one nido-conductor tool that reaches the ledger. The brief still tells the conductor to act only through its tools and that units write every other record."
  {:about [buffer/core-buffer buffer/call-tool] :evidence "round"})

(Claim restart-makes-the-trail-history
  "A :phase-gate, a :findings or a :sent-back entry is a restart for the position's trail clauses: trail records written before the newest one no longer place :implemented, :reviewed or :published, nor does a :pr-opened before it make a PR project landable."
  {:about [reentry/lane-reentry reentry/standing-trail-records] :evidence "round"})

(Claim candidate-again-only-after-new-review
  "implementation-review-passed? reads only the :review entries written after the newest restart, its other conditions unchanged: the newest such review is :clean or :converged and no implementation was recorded after it. So after a :sent-back the workstream is placed off the landing rungs, and is placed :release-candidate again only once a passing :review is written after the :sent-back, on a project that lands a PR and on one that lands its working copy alike; it is placed :published again, as after any restart, by a :pr-opened written after the newest restart. A :findings or :phase-gate restart is read the same way, so a fixed findings round whose rerun review loop passes reaches the candidate as it does now."
  {:about [drive/implementation-review-passed? drive/of] :evidence "round"})

(Claim send-back-spends-the-grant
  "A :sent-back spends the person's :merge grant in both its homes, in the commit that appends it [send-back-withdraws-the-asks]. In the ledger, unspent-grants counts a :sent-back beside a :merged as spending each :merge grant older than it, and spends no other act's grant, so the pipeline reads merge-granted? after a send-back only on a :merge grant given after the newest :sent-back. In the core, each grant held carries the occurrence the core numbered it with when it was given; the :send-back command names the :merge occurrence the verb read held, and its commit spends the held :merge grant only when it is that occurrence, so a :merge grant given after the verb read the core stands."
  {:about [workstream/unspent-grants domain/core-domain nido-workstream/nido-workstream] :evidence "round"})

(Claim surfaces-follow-the-position
  "No surface and no activity rule reads the :sent-back: view-activity's `of`, the Needs you card, the Status heading, the arc and the state mark are unchanged, each reading the observation and records it reads now, and none gains a branch on a :sent-back. The state a send-back's commit leaves is the position the restart places — off the landing rungs [candidate-again-only-after-new-review], at a move next-by-position gives an agent — and the operator asks it was not handed [send-back-withdraws-the-asks]. Read by that unchanged rule, that state with none of those asks open and no failure standing is off Needs you, its heading carries no ' · needs you', its Land rung no awaiting mark, and its state mark is the working dot while a turn runs; an ask the send-back left open keeps it on Needs you. Whatever a later record or ask makes of the workstream is read by the same rule."
  {:about [activity/of work/coordinator-work surface/status-heading surface/state-mark] :evidence "round"})

(Claim send-back-regression-tested
  "A test under test/, which bb nido:test discovers and runs, builds ws-20261120-d22b85's shape — a workstream placed :release-candidate with an open operator ask about landing — sends a rework word through tell's path (person-says!), which answers that ask, has the conductor's send_back tool take the word up, and asserts that the core's awaiting-person lists no ask on the workstream, the position is off the landing rungs and view-activity answers no :needs-you; the same test fails on the code before this change, which has no send_back and leaves the position :release-candidate."
  {:about [nido-workstream/nido-workstream activity/of] :evidence "round"})
