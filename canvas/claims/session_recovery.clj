(ns canvas.claims.session-recovery
  "The claims the session-recovery design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.restore :refer [lane-restore]]
            [canvas.coordinator.record.runs :refer [teardown-session-for-run!]]
            [canvas.coordinator.source.start-failures :refer [source-start-failures]]
            [canvas.session.failure :refer [session-failure]]
            [canvas.session.lifecycle :refer [session-lifecycle]]
            [canvas.tasks.nido-recovery :refer [nido-recovery]]))

(Claim failed-start-kept
  "Every lifecycle verb that starts a session keeps exactly one failure record when the start throws — the error chain, the tails of the instance's service logs, the verb and options attempted, and the origin its caller passed — and rethrows with the original message and ex-data plus the record's id; a record that cannot be kept never replaces the start's exception."
  {:about [session-failure session-lifecycle] :evidence "round"})

(Claim failure-outlives-its-session
  "A kept failure lives apart from the session it describes: destroying, tearing down or reclaiming that session removes no failure record."
  {:about [session-failure teardown-session-for-run!] :evidence "round"})

(Claim owed-is-derived
  "Whether a failure is owed a recovery is derived and never stored on it: it is owed until a recovery workstream for its cause closes having named it — in the payload of a recovery Run fired onto that workstream or in a :session-restored entry on it — so a failure kept while a recovery was closing, and named by neither, stays owed; and a failure whose origin is a Run fired by the recovery source is never owed."
  {:about [session-failure source-start-failures] :evidence "round"})

(Claim diagnosis-before-action
  "Neither restoring nor landing proceeds on a recovery workstream that holds no :session-diagnosis — the record classifying the cause as a nido defect, a project defect or a one-off and citing the failures and evidence it rests on."
  {:about [lane-restore nido-recovery] :evidence "round"})

(Claim restore-starts-the-session-itself
  "A restore brings each failed session up in the process running the restore and hands the daemon only what continues once it is up — the parked Run executed again, a new Run of a failed Run's trigger and payload on its workstream, or the reply's resume turn, and nothing for a person's start — so no bring-up depends on code the daemon loaded before a fix landed."
  {:about [lane-restore] :evidence "round"})

(Claim restore-says-what-happened
  "A restore covers every failure of its cause still owed when it runs that no earlier :session-restored entry on its workstream names restored or withdrawn, and appends one :session-restored entry giving each exactly one outcome — restored once its start returned and its continuation was handed over; withdrawn only when the workstream its origin names closed after the failure; otherwise not-restored, naming the failure its own attempt left — and closes the recovery workstream only when every failure named on it, by a recovery Run fired onto it or a :session-restored entry, has been named restored or withdrawn."
  {:about [lane-restore] :evidence "round"})

(Claim nido-fix-lands-through-its-gate
  "The recovery land verb pushes only nido's repository, and only the worktree's tip on top of origin's main after bb nido:test and bb nido:land:check both pass at that tip and the workstream's diagnosis names a nido defect; the push must fast-forward, and the landing is recorded once as a :merged naming the commit."
  {:about [nido-recovery] :evidence "round"})

