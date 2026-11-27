(ns canvas.claims.no-pr-landing-arc
  "The claims the no-PR landing arc design commits nido to: whether a change still owes a pull
   request is the landing's question, so on a project whose landing names no PR a recorded
   implementation review makes the working copy landable and the draft-PR step drops out of the
   arc, while PR-landing projects are placed as before.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [of]]
            [canvas.coordinator.lane.landing :refer [names-pr?]]
            [canvas.core.buffer :refer [brief]]
            [canvas.ui.surface :refer [ui-components-state]]))

(Claim landable-is-the-landings-question
  "of reads a project's landing only as names-pr?'s answer, a refusal read as PR-landing. A change is landable when a :pr-opened stands in the trail, or — where the landing names no PR — when a :review stands in the trail. For a workstream class-position places on the standard arc (no drain or slice), with closure, blockers, findings, retraction, re-entry and QA not deciding: landable under an unspent :merge grant is :land-granted; landable with the implementation review passed is :release-candidate; landable otherwise is :published; a :review in the trail that is not landable — only where the landing names a PR — is :reviewed."
  {:about [of names-pr?] :evidence "test"})

(Claim no-pr-step-without-a-pr-landing
  "Where a project's landing names no PR, no answer of of names :publish-draft-pr as the next stage, so no conductor brief directs /prepare-draft-pr. Everything that already outranks the landing still does — closure, an unanswered blocker, open findings, a drain's or a slice's own recipe, a retraction, and QA that holds — and only where none of them decides, a :review standing in the trail places the workstream at a position whose next stage is :land (:release-candidate, :published, or :land-granted under the grant), whose granted landing runs the declared procedure through pr:land."
  {:about [of brief] :evidence "test"})

(Claim published-placed-by-what-lands
  "The record that places :published — what a gate's Land is offered from and a note over it is bound by — is the newest :pr-opened standing in the trail, else, where the landing names no PR, the newest :review standing in it."
  {:about [of] :evidence "round"})

(Claim published-worded-without-a-procedure
  "position-chip and status-heading word a position from ui-components-state's one position-label table, keyed by position alone, and that table's words for :published name no pull request: they say the change awaits the person's Land. The TUI's board row prints the position keyword and is left as it is: :published names no PR."
  {:about [ui-components-state] :evidence "round"})
