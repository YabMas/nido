(ns canvas.claims.activity-model
  "The claims the activity-model design commits nido to, declared where they live on.
   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [status]]
            [canvas.coordinator.view.activity :refer [Activity of]]
            [canvas.coordinator.work :refer [coordinator-work core-resolve! gates read-gates]]
            [canvas.tasks.nido-workstream :refer [list*]]
            [canvas.ui.surface :refer [needs-words position-chip state-mark status-heading ui-components-state ui-tui]]))

(Claim owed-by-the-person-is-needs-you
  "An open workstream's activity answers :needs-you exactly when it names, as :needs, one of the categories the person is needed in, chosen in this order: :system-error while a failure only the person clears stands — the core holding its conductor stopped and asking them, or a unit's session that failed to start with the core idle and no pause standing; else :ask while the core holds an operator ask open; else :landing while the position the row is placed at, on an activated, unpaused workstream, is one the person's Land lands — :release-candidate or :published with no :merge grant the core holds unspent, which the observation's :landing? says; else :ask while it owes them any other :human move; else :system-error while it is stalled, as [stall-needs-you-as-error] says. The observation's :owed-by and :landing? are both read off that placed position, so a candidate whose Land the core already holds, placed :land-granted, is the seat's move, owed by the agent and no landing. Exactly one category is named, so a release candidate whose conductor the core stopped needs the person as a :system-error, and one the core asks about as an :ask. Its :owed-by is :person exactly when :needs-you holds — with no exception, a release candidate's landing included — and a position's move for the person that nothing makes theirs, on a workstream not activated or paused, reads :nobody. status says whether the workstream is activated."
  {:about [of status coordinator-work] :evidence "test"})

(Claim needs-you-list-is-the-activity
  "Needs you is one classification followed by card construction: `of` classifies each row, and gates and read-gates card exactly the rows whose activity answers :needs-you, one card per row, each its own workstream's (a slice's as any other's, never folded into its drain's), built for that activity's :needs and carrying it — the core's ask where it holds one, a stopped conductor's before any other; else for a :landing a card offering Land, for a :system-error a card offering Retry, which core-resolve! sends to the core as its :retry-provision of the failed unit, where a unit's session failed to start, and otherwise, for a stall, a card offering Wake, and for an :ask the move the row's position owes. read-gates cards every stall without depending on the rows its process holds. No other list holds release candidates. read-gates' declaration is the one account of where Needs you's gates come from: a contract on a caller of read-gates or all-gates says it by naming them, never by an account of its own. The pane's arc says the person is awaited, titled with the category, exactly while that same activity answers :needs-you — on the spine stage the work is in, or, where the position names none (a move past the implementation, a landing among them), past the spine — and marks a stage :running only while it answers :live."
  {:about [of gates read-gates coordinator-work core-resolve!] :evidence "test"})

(Claim landing-is-a-card-not-a-question
  "A release candidate on an activated, unpaused workstream whose Land the core does not already hold as an unspent :merge grant, on which no failure only the person clears stands and the core holds no ask, reads :owed-by :person, :needs-you true in the :landing category, never :stalled and, with the core idle, not :live; its Needs you card offers Land — the grant core-resolve! gives over a :release-candidate or :published position — and carries no question and no core ask: nothing is raised in the core until the person lands. Where a failure or a core ask does stand, the earlier category is the one the person is needed in and its card is that category's."
  {:about [of gates coordinator-work] :evidence "test"})

(Claim system-error-is-a-failure-the-person-clears
  "A failure only the person clears needs them as a :system-error: a conductor the core stopped after it failed, whose stop is the operator ask its card offers; or a unit whose session failed to start, which nobody holds, the core does nothing on and no pause holds — carded with Retry, and reading needing the person, never :stalled. While the conductor is still owed the failure the core is deciding, and the person is not needed for it. A stall is the other :system-error: no failure stands, yet nothing will make the move an agent or the machine owes, and its card offers Wake."
  {:about [of gates coordinator-work] :evidence "test"})

(Claim merge-queued-is-underway-not-live
  "A workstream in the :shipping band the core does nothing on is underway as a merge waiting its turn — :doing {:source :merge :phase :queued} — and not :live; the board row's mark, the mark in the pane's status heading and the arc — past its spine, where :shipping lies, carried as the arc's :past :doing and rendered as that same mark — all read it `merging · queued`, as intent Done-when 2 names each of them, while the heading's words stay the position, and no surface lights the working dot or marks an arc stage running for it."
  {:about [of state-mark status-heading coordinator-work] :evidence "test"})

(Claim one-mark
  "state-mark is the one mark a workstream's activity is said in, on the board row and in the pane's status heading alike, read off the activity alone: the needs-you dot while :needs-you, titled with its category, and the merge badge while a merge is underway — both, side by side, when both hold; where neither holds, the pause, else the working dot while :live, else nothing. A stall needs the person as a :system-error, so it carries that category's dot, its title saying it is stalled. The position chip lights for the person on that same :needs-you, never on its position's mode, and is titled with the category; the pane's status heading says the person is needed, with the category, whenever that :needs-you holds, and says what a merge is doing in the merge badge's words; and the mark, the chip, the heading, the arc's awaiting mark and the Needs you card all word the category through needs-words. The rows say no state in words of their own: a needs-you card's standing (working…, parked on a session, or its category) and a winding-down row's (closed and how, stopping…) are worded by ui-components-state."
  {:about [state-mark position-chip status-heading needs-words ui-components-state] :evidence "round"})

(Claim facets-cannot-disagree
  "Over every combination of closure, activation, pause, owed-by, whether the core holds it, core activity, band, open unit, deliberate wait, landing, failure and claim, `of`'s answer is an Activity — a shape that admits only consistent facets, one closed variant per engagement — and keeps the model's invariants: underway exactly when live, except a merge waiting its turn or a pause; a merge is underway only while the core does nothing; owed-by :person exactly when needs-you exactly when :needs names a category, with no exception; a landing is said only of a :land move and a system error only of a failure or a stall; waiting on the person with the core idle lights no working dot; a stall runs nothing, has nothing underway, is unpaused, rests on no deliberate wait and is an agent's or the machine's move, and needs the person as a :system-error; nothing not activated needs the person but the core — an ask, a failed start or a stall of an open unit nobody holds; settled is settled; engagement is read off the rest. So a blocker at an idle workstream reads owed by the person, not live and needing the person as an ask, and is awaited in the arc and carded on Needs you as [needs-you-list-is-the-activity] says of every row that needs the person."
  {:about [of coordinator-work Activity] :evidence "test"})

(Claim brian-blockers-read-as-asks
  "A ledger cut at an unanswered :blocker — placed :blocked, its next move :human — on an activated, unpaused workstream the core does nothing on and holds no ask or failure about answers :needs-you in the :ask category and shows no working dot; its card is the one [needs-you-list-is-the-activity] builds for every row that needs the person. This is the shape the held brian snapshot's two blocked workstreams are read in; whether each is such a ledger is under :open."
  {:about [of gates] :evidence "test"})

(Claim the-person-lands-it
  "This change is accepted on a working copy where bb nido:test and bb nido:design:check both pass, and it reaches main only by the person's Land — the grant core-resolve! records — never by its own agent running bb nido:land or pushing main."
  {:about [core-resolve!] :evidence "round"})

(Claim terminal-rows-say-the-category
  "The CLI board's line for a row and the TUI board's status for it say, beside who owes the next move, the category the row's activity names as :needs while its :needs-you holds — read off the row, re-derived from nothing — and name none while it does not."
  {:about [list* ui-tui] :evidence "round"})
