(ns canvas.claims.work-session
  "The claims the work-session design commits nido to: which of a workstream's session records do
   its work is one predicate, and the readings that choose a working session — the environment,
   open-target, the improvement sweep and attach's suggestion — never choose its chat's or its
   conductor's record. The gate's Land grants :merge and ships nothing itself
   (canvas.claims.project-landing/land-gate-grants-never-ships).

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.session :refer [work-session?]]
            [canvas.coordinator.work :refer [coordinator-work environment open-target]]
            [canvas.tasks.nido-attach :refer [nido-attach]]))

(Claim work-session-predicate
  "work-session? is true of a session record exactly when it is marked neither :conductor nor :chat."
  {:about [work-session?] :evidence "round"})

(Claim env-is-latest-live-work-heavy
  "A workstream's environment is, of its records work-session? admits, the latest-created one that is :heavy and live; nil when there is none. A chat record, however new and heavy, is never the environment."
  {:about [environment work-session?] :evidence "round"})

(Claim choosers-use-the-predicate
  "open-target, the improvement sweep's reading of an attempt's sessions, and attach's suggested session to enter choose or count only records work-session? admits."
  {:about [open-target coordinator-work nido-attach] :evidence "round"})

