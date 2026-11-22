(ns canvas.claims.blocker-question
  "The claims the multi-question asking design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :as drive]
            [canvas.coordinator.report :as report]
            [canvas.coordinator.report.ask :as ask]
            [canvas.coordinator.work :as work]
            [canvas.ui.surface :as surface]
            [canvas.tasks.nido-workstream :as nido-workstream]))

(Claim one-question-per-blocker
  "A :blocker asks either one question, in :needs with its top-level :options, or two or more, in :questions — each {:id \"qN\" :question :context? :recommendation? :options?}, ids q1, q2… in order — and never both. validate-event holds every question on write to one trimmed line of at most 240 characters ending in '?', refusing the blocker with the first question that fails named, and holds each question's :options to 2..cap branches, none of them approving a design (:approves-design)."
  {:about [report/coordinator-report report/validate-event] :evidence "test"})

(Claim blocker-questions-one-reading
  "report/blocker-questions is the one reading of the questions a :blocker or a conductor :escalate body asks, in either form and era, as a vector of parts {:id :question :context :recommendation? :options} — a :needs one as one question through blocker-parts. Every other function that shows, compares or matches such a question, in report or outside it, reads it there; only the write contract (validate-event) and the conductor's copy of an escalation's question form into its halt take :needs or :questions off the record themselves."
  {:about [report/blocker-questions] :evidence "round"})

(Claim blocker-asks-one-question
  "The :answer-blocker kind yields an Ask of one question per question of the owed blocker (blocker-questions). A one-question blocker's Ask is as before, its options carrying their own gate actions. A multi-question blocker's options carry no action, and its Ask's :submit is the gate's :answer-questions action, which gate-actions offers exactly when the owed blocker holds :questions; on that gate gate-actions offers no :reply action, Reply or Answer, so an answer given on the card is recorded per question."
  {:about [ask/ask work/gate-actions] :evidence "test"})

(Claim card-answers-each-question
  "In submit mode the card offers each question its options as toggles and, for a question without options, a words field, each held under the card's signal by question index, and its submit posts every index in order, a blank for a question left unanswered."
  {:about [surface/ui-components-ask] :evidence "test"})

(Claim resume-names-each-answer
  "A session parked on a multi-question blocker is resumed, after the answer is recorded, with every question in order and its answer — the picked branch in full, the person's words, or that it was left open."
  {:about [work/resolve-gate!] :evidence "test"})

(Claim authors-told-one-question
  "The asking briefing (resources/asking/ask.md, through guidance) tells its reader to ask each thing the person must decide as its own question — one in :needs, several in :questions, each in its parts — and never to pack several into one line; the conductor's escalation brief carries it."
  {:about [ask/guidance] :evidence "round"})
