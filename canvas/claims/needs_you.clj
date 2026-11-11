(ns canvas.claims.needs-you
  "The claims the needs-you design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.report :refer [validate-event]]
            [canvas.coordinator.report.ask :refer [report-ask ask guidance Ask]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor decide! close-unanswered!]]
            [canvas.coordinator.lane.drive :refer [next-action]]
            [canvas.coordinator.work :refer [gates Gate owed-revivals]]
            [canvas.review.record :refer [design-prompt]]
            [canvas.ui.surface :refer [gate-pane needs-fragment ui-server operations-home-cards workstream-pane]]))

(Claim every-gate-carries-an-ask
  "Every gate the work plane lists carries an :ask that report.ask/ask built for the move its position owes, from the record that move's question was written in — the owed blocker for answer-blocker, and for every other move the record the position read when it placed the workstream there, whatever was appended after it — together with the workstream's live intent, the actions the gate offers and any conductor note."
  {:about [gates Gate ask] :evidence "round"})

(Claim a-kind-is-one-entry
  "How a kind's record yields its Ask's question, context and options, and that kind's guidance addendum, are one entry of report.ask's registry plus the addendum's file; the generic ask, the work plane's building of a gate's Ask and the views' rendering of an Ask name no kind. The kind's record card renders the record as written and builds no part of the Ask."
  {:about [report-ask] :evidence "round"})

(Claim card-leads-with-the-question
  "A needs-you row previews its Ask's question; the gate pane renders, after the title and doing line, the Ask — its question as the heading, then its context, its options with their consequences, what each answer does, and the goal with its done-whens numbered from 1 — and the kind's own record card after it, folded."
  {:about [gate-pane Ask] :evidence "round"})

(Claim authors-briefed-from-one-guidance
  "There is one generic guidance on asking a person a question, and each kind's addendum extends it; the conductor's brief carries the guidance for an escalation and the design round's judge prompt the guidance for approve-design, both from report.ask/guidance, and a task prints it for any kind. The conductor's escalation guidance no longer states asking rules of its own."
  {:about [guidance lane-conductor design-prompt] :evidence "round"})

(Claim escalation-does-not-reask
  "An :escalate decision recorded after this change while the position already owes a person a move appends no :blocker and parks nothing new; its :needs is that move's Ask's :note. An escalation recorded when nothing is owed appends a blocker as before, whose :summary is never the text 'the conductor escalated a hand-back'."
  {:about [decide! close-unanswered!] :evidence "round"})

(Claim prose-choices-refused
  "The write contract refuses a blocker without :options whose :needs enumerates two distinct branches as 'option a/b/1/2', '(a)/(b)', '(1)/(2)' or 'a)/b)'; a single such marker is not refused."
  {:about [validate-event] :evidence "round"})

(Claim worked-example-re-asked
  "Once the change lands, brian ws-20261105-275c8d owes one question about where students see interactive elements: blockers 6, 7 and 9 each carry nido's own :blocker-answered saying the question was re-asked, and one new blocker asks the web-only-versus-brian-mobile choice in words readable without the ledger, with that choice as :options; its gate's Ask meets the card claims above."
  {:about [gates] :evidence "round"})

(Claim ask-is-typed
  "An Ask is constructed only by report.ask/ask and satisfies its schema there: a non-blank question; context as strings; each option a label, summary, optional consequence and recommended?, and the id of the gate action that gives it; one :answer-does entry per gate action, naming the action and what giving it records and resumes; and the goal as the intent's goal and done-whens. Every option names an action the gate offers."
  {:about [Ask ask] :evidence "round"})

(Claim a-person-owes-six-moves
  "The position level names the moves only a person can make as :human stages of next-action — accept-triage, approve-design, acknowledge-invalidation, assert-gate, answer-blocker and land — and a workstream is a needs-you gate only when its position owes one of them."
  {:about [next-action gates] :evidence "round"})

(Claim list-is-owed-moves
  "The needs-you list renders the gates and nothing else: no casualty card and no workstream whose only claim on the person is a recorded permission denial."
  {:about [needs-fragment ui-server] :evidence "round"})

(Claim notices-on-operations
  "Operations' home carries a card for the host casualties owed a revive, with the revive command, and a card for the workstreams with a permission denial recorded in a session nido drives, each linking to its workstream; a workstream's pane shows its recorded denials whether or not it is a gate."
  {:about [operations-home-cards workstream-pane owed-revivals] :evidence "round"})
