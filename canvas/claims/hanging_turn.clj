(ns canvas.claims.hanging-turn
  "The claims the hanging-turn design commits nido to: a turn that has given its final answer ends,
   whatever its agent left running, and one that cannot be ended reads stuck rather than working.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.view.activity :refer [view-activity]]
            [canvas.coordinator.work :refer [coordinator-work]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.core.executor-claude :refer [executor-claude]]
            [canvas.core.turn :refer [core-turn]]
            [canvas.ui.surface :refer [ui-components-row ui-components-state]]))

(Claim claude-asks-about-left-running-work
  "Every run of the Claude executor hooks Stop. When the model ends a response while a background task it started is still running, the hook blocks once per such task, naming it, and asks the model to stop it with TaskStop if the response was its final answer, or to end again to wait for it; a task still running when the model ends again over it is recorded as awaited in the run's directory and is not asked about again. Every such run sets CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS to Claude's default."
  {:about [executor-claude] :evidence "test"})

(Claim a-settled-run-is-ended
  "A run whose executor has read it settled — at rest at a final answer, held by no task it awaits — for the runner's settle window is ended by the runner, whatever is written to its output meanwhile: it and its live descendants are killed, and the outcome recorded is the one its output carries, so the conductor hears the turn's end as for any turn that ends. An executor with no settled reading is never ended this way. core-turn names no provider."
  {:about [core-turn executor-claude] :evidence "test"})

(Claim a-run-that-will-not-end-reads-stuck
  "A run the runner ended — settled, past its deadline, or cancelled by the store — that is still alive after the runner's kill grace is reported with :record-stuck, once per run. While a turn's current run is stuck, the core's activity on its workstream is :stuck, outranking working and deciding, or an open ask carrying the stuck run; either way the surface reads it as stalled and stuck, never live, and a board row carries the stuck mark, titled that its process will not exit, beside the needs-you dot when an ask is open. The run's outcome is still recorded when its process ends, a store-cancelled run's included, which clears it."
  {:about [core-turn core-domain view-activity coordinator-work ui-components-state ui-components-row] :evidence "test"})
