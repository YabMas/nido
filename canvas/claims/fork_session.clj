(ns canvas.claims.fork-session
  "The claims the fork-session design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.session :refer [create! write!]]))

(Claim one-workstream-per-session-name
  "record-session refuses any write that brings a session record into existence — create!, or write! of a record whose path holds none — when another workstream of the project already holds the name, and makes that check and the write one step under a per-project lock; so no writer, the three lanes included, can make a second holder."
  {:about [create! write!] :evidence "round"})
