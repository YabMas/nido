(ns canvas.claims.stop-hand-back
  "The claims the stop-hand-back design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [status]]
            [canvas.coordinator.view.activity :refer [of Activity]]
            [canvas.coordinator.work :refer [activate!]]
            [canvas.tasks.nido-session :refer [spawn]]))

(Claim spawned-session-is-activated-at-birth
  "bb nido:session:spawn activates the child's workstream before it carries or drives the child on its brief, so the child's first turn end is its conductor's to read. Only a session a person starts by hand stays plain until they activate it."
  {:about [spawn activate!] :evidence "round"})

(Claim persons-turn-is-not-a-stall
  "On a workstream not activated — a scratch session a person started by hand, since a session nido starts on a brief is activated at birth — when the latest turn end of its faceted sessions ended no instruction nido gave, status answers :resting? true with owed-by unchanged, and the activity answers :stalled false though nothing is live: the conversation is the person's and resting, not a fault."
  {:about [status of] :evidence "round"})

(Claim stall-carries-why
  "When the activity reads :stalled it also answers :why, as status reads it: the faceted session whose turn ended last, the instant that turn ended, the move the position owes, and the turn's last message, or that no session of the workstream has recorded a turn end. Its :turn-end tag, :recorded or :none, says which of the two it is. When not stalled it carries no :why."
  {:about [status of Activity] :evidence "round"})

