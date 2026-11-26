(ns canvas.claims.ask-guidance
  "The claims the ask-guidance design (workstream ws-20261126-e8839b) commits nido to, declared
   where they live on: every question the core puts to the person is briefed from
   resources/asking/ and its question form is checked where it enters the core. Each Claim's name
   is the claim's id, its docstring the statement, and its :about the elements it is about."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.report :refer [validate-event]]
            [canvas.coordinator.report.ask :refer [guidance]]
            [canvas.core.ask-tool :refer [core-ask-tool]]
            [canvas.core.buffer :refer [core-buffer brief]]
            [canvas.core.domain :refer [decide ask-problems]]
            [canvas.core.executor-claude :refer [hook-decision]]))

(Claim one-asking-source
  "The ask tool's description, raise_operator_ask's description and the conductor brief's asking
   lines carry the asking rules as read from resources/asking/ — ask.md, then the core addendum —
   with no second hand-written copy of that briefing in src/nido/core; a field's description and a
   refusal's instruction state only that field's own form. report.ask/guidance reads the same
   ask.md for the ledger path."
  {:about [core-ask-tool brief guidance] :evidence "round"})

(Claim ask-md-edit-seen
  "A test that changes nothing but ask.md's text sees the change in the ask tool's description, in
   raise_operator_ask's description and in the conductor brief."
  {:about [core-ask-tool brief] :evidence "round"})

(Claim ask-md-serves-both-paths
  "ask.md names, for each part of a question (the question, its context, a recommendation, what
   follows, the options, several questions), the field it is written in on a ledger record and on a
   core ask, so the same text is correct for both authors; an addendum adds only what its path
   alone has."
  {:about [guidance core-ask-tool] :evidence "round"})

(Claim recommendation-optional
  "Neither the ask tool's schema nor raise_operator_ask's requires recommendation, and decide
   admits an operator ask or a conductor stop without one; explored stays required."
  {:about [core-ask-tool core-buffer decide] :evidence "round"})

(Claim explored-is-context-only
  "The schemas describe explored as only the context the answer depends on, and say it never tells
   how the question reached the person — no review-loop history, round counts, or who escalated or
   stopped."
  {:about [core-ask-tool] :evidence "round"})

(Claim core-ask-form-checked
  "ask-problems yields, for an ask's content, one instruction per question-form violation, each
   naming its field: the headline question or an entry of questions that is not one line of at most
   240 characters ending in '?'. decide ends a turn reporting an ask with any such problem :failed,
   with them in its detail; and it refuses :raise-operator-ask and :raise-conductor-stop whose
   content has any such problem, or is missing explored, or has neither options nor questions,
   naming each under :problems."
  {:about [ask-problems decide] :evidence "round"})

(Claim hook-denies-malformed
  "hook-decision answers an unanswered ask call whose content has problems under ask-problems with
   a deny whose reason is those instructions, and defers one with none; the resolved call is
   allowed as before."
  {:about [hook-decision ask-problems] :evidence "round"})

(Claim conductor-tightens-raised-asks
  "The brief, through the core addendum, tells the conductor that raising a unit's ask means
   rewriting it to ask.md's rules — which is what drops its provenance and filler — rather than
   carrying its text over, while keeping each decision as its own question with its options; it
   points at ask.md for those rules rather than restating them, and no longer says to carry the ask
   over as the unit wrote it."
  {:about [brief] :evidence "round"})

(Claim one-question-cap
  "The question cap the ledger's write contract enforces and the one ask-problems enforces are the
   cap ask.md states; a test reads it from ask.md and holds both checks to it."
  {:about [validate-event ask-problems] :evidence "round"})

(Claim suite-green
  "bb nido:test is green on the change, with the tests and fixtures that carry asks brought to the
   new schemas and form."
  {:about [core-ask-tool decide hook-decision brief] :evidence "round"})
