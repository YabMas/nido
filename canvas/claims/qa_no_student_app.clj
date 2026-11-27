(ns canvas.claims.qa-no-student-app
  "The claims the qa-no-student-app design (ws-20261127-89115b, design at ledger entry 13) commits
   nido to: a QA plan an agent records holds only what its walk can carry out in the session's
   running web app, the walk re-plans away a step that needs the Student App instead of recording
   it :blocked, and the :plan-qa direction names the QA brief page by a path any worktree resolves.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three things the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.core.buffer :refer [brief]]))

(Claim plan-steps-walkable
  "docs/reference/qa-brief.md and the :plan-qa direction each say that every step of a recorded QA plan, and everything its Before you start asks for, must be something the walk can do in the session's own running web app; that no Student App runs against a session's local backend, so no step or precondition may need the Student App; and that a check which would is left out of the plan. Naming a visual the web app draws after the Student App is not needing it."
  {:about [brief coordinator-report] :evidence "round"})

(Claim walk-replans-unwalkable
  "The :walk-qa direction says that a step of the plan which needs the Student App is not walked and recorded :blocked: the unit first records a newer :qa-plan citing the same design without that step, and walks that plan."
  {:about [brief] :evidence "round"})

(Claim plan-page-resolvable
  "The :plan-qa direction names the QA brief page by a path that resolves from any project's worktree — nido's own tree, ~/Code/nido/docs/reference/qa-brief.md, as the direction already names nido's verbs by ~/Code/nido/bb.edn."
  {:about [brief] :evidence "round"})

(Claim direction-wording-tested
  "A test in test/nido/core/buffer_test.clj asserts that the brief at :plan-qa carries the rule of [plan-steps-walkable], naming the Student App, and that the brief at :walk-qa carries the re-planning of [walk-replans-unwalkable]; bb nido:test passes with it."
  {:about [brief] :evidence "round"})
