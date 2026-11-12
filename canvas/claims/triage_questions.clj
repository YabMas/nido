(ns canvas.claims.triage-questions
  "The claims the triage-questions design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [decide!]]
            [canvas.coordinator.lane.drive :refer [halt-for]]
            [canvas.coordinator.record.tickets :refer [complete!]]
            [canvas.coordinator.report :refer [accepted-answers blocker-parts coordinator-report parse-event triage-questions validate-event]]
            [canvas.coordinator.report.ask :refer [Ask ask report-ask]]
            [canvas.coordinator.work :refer [apply! gate-actions resolve-gate!]]
            [canvas.tasks.nido-review :refer [parked-blocker]]
            [canvas.tasks.nido-seats :refer [decide*]]
            [canvas.ui.surface :refer [gate-pane question-card ui-server workstream-pane]]))

(Claim questions-write-shape
  "The write contract for :triage accepts :questions, each a :question, a :context, an :after, one to authoring-branch-cap :directions and an optional :recommendation, which a triager gives only when it genuinely holds a view on which fix is right. It refuses a new report carrying :directions; a report with no questions writes :questions []."
  {:about [validate-event] :evidence "test"})

(Claim legacy-reads-as-one-question
  "report/triage-questions returns a report's questions in either era. A stored report with non-empty :directions is one question with no words of its own and those directions; one with none is no question. The Ask, the gate's actions, the accept, the report's markdown and the gate pane's triage record card read a report's questions only through it."
  {:about [triage-questions parse-event] :evidence "test"})

(Claim blocker-parts-required
  "The write contract refuses a new :blocker whose :needs is not one line of at most 240 characters ending in '?', whose :summary is blank or the escalation headline, or which has no :after; :recommendation is optional. The refusal's message names the missing or malformed parts and says where each belongs, as the prose-options refusal does, and carries no schema dump."
  {:about [validate-event] :evidence "test"})

(Claim legacy-blocker-is-body-text
  "report/blocker-parts reads a blocker in either era as {:question :context :recommendation? :after?}. A stored blocker with :after gives its :needs as the question. One without is legacy: its question is the fixed line 'This work is waiting on your answer.' and its :needs leads the context. A :summary that only says where the blocker came from — the escalation headline, or one starting 'Raised by the workstream's conductor' — is dropped from the context."
  {:about [blocker-parts ask] :evidence "test"})

(Claim escalation-carries-parts
  "A conductor :escalate decision that parks the workstream carries :needs as the one-sentence question, plus :summary and :after and, when the conductor genuinely holds one, :recommendation, and the blocker it parks copies them. `bb nido:seats:decide`, the conductor's one way to write a decision, carries :summary, :after and an optional :recommendation onto the :escalate body, and the conductor's brief names all three. One decided while the next move is already a person's parks nothing and is a note on that move's card, not held to the parts. The conductor never writes a provenance sentence as the summary: where a blocker came from is not context, and no :raised-by field records it."
  {:about [decide! decide* validate-event] :evidence "test"})

(Claim writers-emit-parts
  "Every blocker nido's own code builds — each halt-for caller (the drive lane's halts and the conductor's seat and no-decision escalations) and the review loop's parked-blocker — carries a one-sentence question, a context and an :after, and a recommendation only where the writer genuinely holds a view, and passes the write contract. The asking guidance (resources/asking) and the skills that tell an agent to raise a blocker state the parts, and that a recommendation is given only when it is a genuine one."
  {:about [halt-for parked-blocker decide!] :evidence "test"})

(Claim ask-is-typed
  "An Ask is {:kind :question :questions :answer-does} plus optional :submit, :goal, :holding and :note. :question is its one-line headline. Each question is {:question :context [strings] :options} with an optional :recommendation and :after. Each option is letter, label, summary, an optional consequence and recommended?, and an optional :action. An Ask answers in one mode: without :submit, every option carries an :action and answers by itself; with :submit, no option carries one, there is at least one option, and :submit names the gate action that sends the choices. `ask` constructs no other Ask, and none whose :action or :submit names an action the gate does not offer."
  {:about [Ask ask] :evidence "test"})

(Claim answer-is-its-button
  "On a card that is not pending, each Ask option carrying an :action is the one button that gives it, labelled as the option, beside its summary and consequence. When the Ask has no :submit, the recommended option is the primary button. Every other action the gate offers is a row of its control beside the Ask's :does for it, so the reply says whom it reaches and the evidence what it opens. No option action is rendered as a button anywhere else. A pending card renders no control for any action: it shows its options without buttons and the working message in place of every other row."
  {:about [question-card Ask] :evidence "test"})

(Claim option-does-said-once
  "What giving a lettered option does is worded without its letter, so every option action on one gate has the same :does. The card states that sentence once, under the options, and never once per option."
  {:about [report-ask question-card] :evidence "test"})

(Claim card-leads-with-the-question
  "A needs-you row previews its Ask :question. The gate pane renders, top to bottom: the title and links; the orientation band; the permission denials recorded while nido drives the session, when there are any; the question card; and the kind's own record card last, folded. A failed action's error line, when there is one, sits at the top of the card, above its first question."
  {:about [gate-pane Ask] :evidence "test"})

(Claim ask-carries-questions
  "The accept-triage Ask is read from the newer of a triage report and a Slack proposal. Over a triage report it carries one question per question of the report, in order; a report with no questions is asked as its own acceptance — one question, the headline's, over its determination and summary, with no options. Each has its words ('Which fix should be built?' where a legacy question has none), its context, recommendation and after, and its directions as options with no :action, label, summary and the effort as consequence. :submit is :apply when the report asks a question; a report with none has no :submit, its Apply answering as its own button. The headline asks to accept the triage. Over a Slack proposal it asks to file it as a ticket, as one option-less question over the proposal's sections, answered by Apply with no :submit. An answer-blocker Ask carries one question from report/blocker-parts, whose options carry their :option-<letter> actions. Every other kind yields one question from its own record."
  {:about [report-ask] :evidence "test"})

(Claim no-letter-actions
  "A triage gate with at least one question offers Accept (:apply, the one primary), Defer decision (:defer) and Dismiss, plus Reply when parked. With none it offers Apply and Dismiss. No gate offers a :direction-<letter> action, and resolve-gate! knows none."
  {:about [gate-actions resolve-gate!] :evidence "test"})

(Claim accept-once-checked
  "A gate Accept carries the :seq it was rendered at and one letter or blank per question. apply! writes nothing and answers :triage-stale unless the :seq names the report the gate answers, no acceptance of that report already stands, the answer count equals that report's question count, and each letter names a direction of its question. Defer accepts with every question left open. A choice made before Accept stays page state and reaches no record."
  {:about [apply! resolve-gate! ui-server] :evidence "test"})

(Claim acceptance-per-question
  "Accepting a report with questions appends one :triage-accepted whose :answers holds one entry per question, in report order. Each entry has the question's words when it has any, and either the chosen direction's letter, label, shape and effort (:squirrel included) or no direction for a question left open. The append still comes only after the Notion write lands, and only while the report is not yet accepted. Every other writer of an acceptance that cites a report — `bb nido:ticket:complete` (tickets/complete!) and the ledger backfill — records one open answer per question of that report (report/open-answers), so a reader tells a question left open from one never asked. report/accepted-answers reads a stored single :direction as one answer."
  {:about [coordinator-report accepted-answers apply! complete!] :evidence "test"})

(Claim effort-is-largest
  "The Notion Effort an accept writes is the largest concrete effort (XS < S < M < L < XL) among the chosen directions. When every chosen direction defers its size, no Effort is written and the deferred-size note is added. When none is chosen, the report's own effort is used. When the chosen efforts differ, the callout says the effort is the largest of the chosen fixes and lists their sizes, naming no branch."
  {:about [apply!] :evidence "test"})

(Claim card-is-self-contained
  "nido.ui.views/question-card is (question-card project ws-id ask actions opts), with opts {:route :pending? :error-msg :session}. It renders every control the Ask's gate offers — the questions with their choices, Previous/Next, and each action the gate offers beside the Ask's :does for it — and reads nothing but its arguments: no orientation band, no :holding, no position or doing line. Every POST it renders is built by opts' :route."
  {:about [question-card] :evidence "test"})

(Claim stepper-card
  "The card shows one question at a time. With several questions it shows a 'Question i of n' line, one marker per question saying whether it is answered and which is shown, and Previous/Next controls. Each option is a button whose first line is its label in bold, then its summary, then its consequence; no letter appears on screen. An option with an :action posts that action. One without marks itself chosen, and choosing it again leaves its question open. The :submit action's row is the one primary button and posts the chosen letters. A pending card shows its questions with no button."
  {:about [question-card] :evidence "test"})

(Claim question-line-calm
  "A question's line is set at --fs-lg, the largest step of the scale, at weight 500 — lighter than the bold title and than the 600 a section heading carries. Its context, recommendation and after-the-answer text follow as body text at --fs-md, the recommendation and the after each led by a short label. No Ask text is set larger than a title."
  {:about [question-card] :evidence "test"})

(Claim panes-use-the-card
  "The gate pane renders its Ask only through question-card, below its title, links and orientation, with the folded record after it. The workstream pane, which renders a gate's actions without its Ask, renders every action that accepts a verdict with questions — an Accept that submits them, and Defer, which accepts with every one left open — as a link to that gate on Needs you instead of a button, so no surface accepts a multi-question verdict without showing its questions."
  {:about [gate-pane workstream-pane] :evidence "test"})

(Claim skill-splits-defects
  "The triage-bug skill's report template writes :questions. It tells the agent to write one question per independent defect, each with its question, context and after and a recommendation only when it genuinely holds one; to list within a question only alternatives that exclude one another; and that a question may carry a single direction."
  {:about [validate-event] :evidence "round"})

(Claim continue-ticket-reads-answers
  "The continue-ticket skill's stage table has a :triage-accepted row. It tells the implementing session to read the acceptance's answers and the report it cites, to proceed on every chosen direction as a decision already made, and to settle each question left open in its own design."
  {:about [accepted-answers] :evidence "round"})

(Claim principles-say-questions
  "docs/reference/ui-principles.md §1 makes the question line the focal point by place and weight rather than by being the largest text. §2 states the type scale as four steps, the question at the titles' step. §4 says an Ask of several questions is answered in one card, a question at a time, each answer a choice within its question, with the one submitting action as the primary button."
  {:about [question-card] :evidence "round"})
