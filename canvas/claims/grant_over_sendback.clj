(ns canvas.claims.grant-over-sendback
  "The claim that survives the grant-over-a-send-back design: a record loop runs only the round its
   command line asked for.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.platform.task-args :refer [unknown-opts-refusal]]
            [canvas.tasks.nido-review :refer [design-cmd baseline-cmd]]))

(Claim record-loops-run-only-what-was-asked
  "bb nido:review:design and bb nido:review:baseline refuse, before any round runs and exiting non-zero, a command line naming any option the loop does not read, naming each such option with the nearest one it does read; unknown-opts-refusal is what names them."
  {:about [design-cmd baseline-cmd unknown-opts-refusal] :evidence "test"})
