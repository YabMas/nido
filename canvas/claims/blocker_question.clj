(ns canvas.claims.blocker-question
  "The claims the multi-question asking design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :as drive]
            [canvas.coordinator.report :as report]
            [canvas.coordinator.report.ask :as ask]
            [canvas.coordinator.work :as work]
            [canvas.coordinator.lane.conductor :as conductor]
            [canvas.ui.surface :as surface]
            [canvas.tasks.nido-workstream :as nido-workstream]))

(Claim one-question-per-blocker
  "A :blocker asks either one question, in :needs with its top-level :options, or two or more, in :questions — each {:id \"qN\" :question :context? :recommendation? :options?}, ids q1, q2… in order — and never both. validate-event holds every question on write to one trimmed line of at most 240 characters ending in '?', refusing the blocker with the first question that fails named, and holds each question's :options to 2..cap branches, none of them approving a design (:approves-design)."
  {:about [report/coordinator-report report/validate-event] :evidence "test"})

(Claim blocker-questions-one-reading
  "report/blocker-questions is the one reading of the questions a :blocker or a conductor :escalate body asks, in either form and era, as a vector of parts {:id :question :context :recommendation? :options} — a :needs one as one question through blocker-parts. Every other function that shows, compares or matches such a question, in report or outside it, reads it there; only the write contract (validate-event) and the conductor's copy of an escalation's question form into its halt take :needs or :questions off the record themselves."
  {:about [report/blocker-questions] :evidence "round"})

(Claim escalation-is-one-blocker-question
  "A conductor :escalate body asks in a blocker's form — one :needs or two or more :questions, never both — and validate-event holds every question of its :questions, whether the escalation parks or not, to the per-question contract [one-question-per-blocker] (enforce-escalation-parts). record-decision! copies the body's question form whole into the halt park-held! appends as the :blocker, under the same append lock as the decision."
  {:about [conductor/decide! report/validate-event] :evidence "test"})

(Claim blocker-asks-one-question
  "The :answer-blocker kind yields an Ask of one question per question of the owed blocker (blocker-questions). A one-question blocker's Ask is as before, its options carrying their own gate actions. A multi-question blocker's options carry no action, and its Ask's :submit is the gate's :answer-questions action, which gate-actions offers exactly when the owed blocker holds :questions; on that gate gate-actions offers no :reply action, Reply or Answer, so an answer given on the card is recorded per question."
  {:about [ask/ask work/gate-actions] :evidence "test"})

(Claim card-answers-each-question
  "In submit mode the card offers each question its options as toggles and, for a question without options, a words field, each held under the card's signal by question index, and its submit posts every index in order, a blank for a question left unanswered."
  {:about [surface/ui-components-ask] :evidence "test"})

(Claim blocker-answer-is-one-record
  "A person's answer to a blocker is one :blocker-answered naming its :blocker-seq. A one-question blocker's carries the picked branch's letter, label and summary (with :grants) or the person's words as :summary, as before. A multi-question blocker answered through :answer-questions carries :answers in their place — one per question in order, each {:question-id} plus the picked branch's :letter :label :summary, or the person's :summary, or neither when left open — and no top-level :letter, :label or :summary, which a reader renders from :answers rather than reading a stored copy; its :grants is the union of the picked branches'. Each letter is resolved against the owed blocker's question at its index, and the whole answer is refused as stale, recording nothing, when any no longer names a branch. conductor/answer! and the blocker:answer task answer a blocker whole, as :summary; nido's own answers are unchanged."
  {:about [work/resolve-gate! conductor/answer! nido-workstream/blocker-answer*] :evidence "test"})

(Claim resume-names-each-answer
  "A session parked on a multi-question blocker is resumed, after the answer is recorded, with every question in order and its answer — the picked branch in full, the person's words, or that it was left open."
  {:about [work/resolve-gate!] :evidence "test"})

(Claim decision-questions-are-the-judges
  "A :decide-design park whose run appended a decision carrying non-blank :asks asks the newest such decision's own questions as the judge wrote them, trimmed — its :questions texts in order, else the '?'-ending sentences of its :asks — keeping each that passes the question contract: one is asked as :needs, two or more as :questions. A decision's :questions holds its alternatives beside its questions, and an alternative that passes the contract is asked as a question of its own. A question that fails the contract is not rewritten and never relaxes the contract; it reaches the person only in the context's quote of :asks."
  {:about [drive/decision-questions drive/run-stage!] :evidence "test"})

(Claim summary-never-the-question
  "run-stage! passes decision-needs' paragraph as the halt's :summary and never as its :needs. A :decide-design park with no judge's question to ask — the run left no :asks, or none of its questions passes the contract — asks the generic stage question, and the newest decision's :asks reach the person beneath it, verbatim, in that :summary."
  {:about [drive/run-stage!] :evidence "test"})

(Claim only-decide-design-derives-needs
  "run-stage! attaches a question or context derived from a round's records only to a :decide-design halt, from the decisions appended after the stage started; a :verify-baseline or :review halt it parks on a round's outcome carries no :needs, :questions or :summary derived from the round's records, so it asks the generic stage question. The :no-workstream and retries-exhausted parks write fixed wording of their own, for every stage, derived from no round's records."
  {:about [drive/run-stage!] :evidence "test"})

(Claim question-is-callers-or-generic
  "A halt asks its caller's :questions, each trimmed, when the caller gives two or more that all pass the question test; otherwise its caller's :needs, trimmed, when that passes; otherwise the generic \"How should the <stage> stage go on[, now that it stopped at <outcome>]?\", with a non-question :needs moved into :summary. halt-for derives no question from the content of a :needs or :summary."
  {:about [drive/halt-for] :evidence "test"})

(Claim halt-question-composition
  "The questions a person sees on a parked design round travel decision (:questions / :asks) → decision-questions, which chooses them → run-stage!'s halt (:needs or :questions, with decision-needs' paragraph as :summary) → halt-for → :blocker → report/blocker-questions → the Ask → the card; nothing in the drive lane sets or rewrites a blocker's question after halt-for."
  {:about [drive/lane-drive report/coordinator-report] :evidence "round"})

(Claim authors-told-one-question
  "The asking briefing (resources/asking/ask.md, through guidance) tells its reader to ask each thing the person must decide as its own question — one in :needs, several in :questions, each in its parts — and never to pack several into one line; the conductor's escalation brief carries it."
  {:about [ask/guidance] :evidence "round"})
