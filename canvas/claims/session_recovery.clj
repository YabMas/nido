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
  "Whether a kept failure is owed a recovery is derived on every read and stored on nothing: it is owed until a recovery run settles it — a :session-restored record on the run naming it restored or withdrawn, or the run ending closed or dismissed by a person in the recovery journal having named it — and a failure kept for a start the recovery source itself fired is never owed."
  {:about [source-start-failures] :evidence "round"})

(Claim diagnosis-before-action
  "Neither restoring nor landing proceeds for a recovery run that has recorded no :session-diagnosis, and landing also refuses one whose diagnosis does not name a nido defect."
  {:about [lane-restore nido-recovery] :evidence "round"})

(Claim restore-starts-the-session-itself
  "A restore brings a failed start back up in the process running the restore, never the daemon, and only a person's start in a session someone typed into shortly before it failed; a start kept for a run or a reply's turn it withdraws, since the core already ended that turn. It hands the daemon nothing to continue."
  {:about [lane-restore] :evidence "round"})

(Claim restore-says-what-happened
  "A restore covers every failure of its run's cause still owed when it runs and records on that run one :session-restored giving each exactly one outcome — restored once its start returned, withdrawn, or not-restored saying why — and records nothing when nothing was owed."
  {:about [lane-restore] :evidence "round"})

(Claim nido-fix-lands-through-its-gate
  "The recovery land verb lands only nido's repository, through the landing gate — the worktree's tip on origin's main, bb nido:test and bb nido:land:check passing at that tip, a push that fast-forwards — and then appends a :merged naming the commit to the run unless the run already records one for that commit, so a retry records no second."
  {:about [nido-recovery] :evidence "round"})
