(ns canvas.claims.stop-hand-back
  "The claims the stop-hand-back design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [activate!]]
            [canvas.tasks.nido-session :refer [spawn]]))

(Claim spawned-session-is-activated-at-birth
  "bb nido:session:spawn activates the child's workstream before it carries or drives the child on its brief, so the child's first turn end is its conductor's to read. Only a session a person starts by hand stays plain until they activate it."
  {:about [spawn activate!] :evidence "round"})
