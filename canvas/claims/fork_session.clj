(ns canvas.claims.fork-session
  "The claims the fork-session design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [run-once!]]
            [canvas.coordinator.lane.work :refer [birth!]]
            [canvas.coordinator.record.session :refer [create! record-session workstream-id-for write!]]
            [canvas.coordinator.work :refer [adopt-orphans!]]
            [canvas.review.passes :refer [project+ws-from-cwd]]
            [canvas.tasks.nido-session :refer [up]]))

(Claim sessions-born-onto-own-workstream
  "A manual session start that names a workstream puts the session on that workstream and mints none; one that names none keeps the workstream already holding the name, else mints a :scratch one-off, as before. No path moves a session between workstreams."
  {:about [birth!] :evidence "round"})

(Claim one-workstream-per-session-name
  "record-session refuses any write that brings a session record into existence — create!, or write! of a record whose path holds none — when another workstream of the project already holds the name, and makes that check and the write one step under a per-project lock; so no writer, the three lanes and the migration's run-once! included, can make a second holder."
  {:about [create! write! run-once!] :evidence "round"})

(Claim unjoinable-start-refused-first
  "A start naming a workstream that does not exist, is closed, or whose session name another workstream holds is refused, saying which, before any worktree or service is made."
  {:about [up birth!] :evidence "round"})

(Claim child-worked-through-its-session
  "A session started on a fork child resolves to the child through project+ws-from-cwd unchanged, so the review rounds, attach, owed and land act on the child; a session that was on the parent still resolves to the parent."
  {:about [project+ws-from-cwd record-session] :evidence "round"})

(Claim name-held-twice-never-answered
  "For every session name of a project, workstream-id-for has exactly one answer — the one workstream holding it — since no name is held twice when the change lands and no write can make one so. It never answers one of several: a name found held twice is refused naming its holders, and the orphan sweep reports every such name it cannot yield by removing a bare scratch holder, naming both holders."
  {:about [workstream-id-for adopt-orphans!] :evidence "round"})
