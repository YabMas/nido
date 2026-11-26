(ns canvas.claims.run-scopes
  "The claims the resource-crash-dive design (ws-20261125-16863c) commits nido to: each run the
   core starts on Linux is capped in a scope of its own, a run the host refused resources to is
   :resource-exhausted and a conductor refused so backs off rather than stopping, and the project
   code nido runs never inherits a caller's shell-startup file.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.core.buffer :as buffer]
            [canvas.core.domain :as domain]
            [canvas.core.executor-claude :as executor-claude]
            [canvas.core.turn :as turn]
            [canvas.platform.process :as process]
            [canvas.review.passes :as passes]
            [canvas.session.provisioning :as provisioning]
            [canvas.session.service :as service]
            [canvas.session.support :as support]))

(Claim a-run-in-a-scope-of-its-own
  "When the runner has a scope, each run starts in its own transient systemd user scope with TasksMax=run-tasks-max (8000). The scope's unit is named nido-run-<hex digest of the run directory's path>.scope, so the name holds nothing of the turn id and differs for every run. The run's processes count against that scope, not against nido-core.service. That cap and its value are the person's decision, recorded at ledger seq 4: \"Every run the core starts gets its own cgroup (a systemd transient scope on Linux) with its own process cap (around 8,000).\" It is the proposal intent 11's done-when 4 asks for, made to the person and decided by them."
  {:about [turn/core-turn turn/start turn/scope-argv] :evidence "test"})

(Claim no-manager-no-scope
  "The runner has a scope only on Linux, and only if at runner start a probe scope running `true` exits 0 within 10s. Otherwise, including when systemd-run is not on PATH and on macOS, runs start as before, in the core's cgroup. The runner logs one line at start naming which mode it is in."
  {:about [turn/probe-scope turn/start] :evidence "test"})

(Claim a-runs-handle-is-unchanged
  "Scoped or not, a run is identified by its wrapper's pid, lock and directory, and is watched, cancelled and taken back through those. In scoped mode the wrapper forks the run, waits for it, writes its scope's pids.events max count to the run's pid-refusals file, and exits with the run's exit status. A cancel kills the wrapper and the processes descended from it at that moment, not the scope, so a service the run detached with nohup, and which has left the wrapper's tree, survives the cancel, as it did before."
  {:about [turn/core-turn turn/take-back!] :evidence "test"})

(Claim a-refused-run-is-resource-exhausted
  "A run that ended :failed, whether it was watched to its end, taken back by a later daemon or its launch threw, is reported as :failed {:reason :resource-exhausted}, with its original detail under :was, when any of three kinds of evidence, each read over the run's whole life from just before its launch to its end, shows the host refused it: (a) its text matches the refusal signature (EAGAIN, Resource temporarily unavailable, errno 11, thread limit, pthread_create, unable to create native thread, fork: retry, Cannot fork), its text being its stderr, anywhere in it, or, for a launch that threw, the exception's message; (b) its own scope refused it a fork, as its wrapper records in its run directory (a launch that threw has no scope); (c) the forks refused at the core's cgroup and at each of its ancestors rose between a count taken just before its launch, kept in its run directory, and a count taken at its end, when its .start threw or when its watch saw it end; a run that ended while no daemon held it has no count at its end, so (c) does not apply to it. Each refusal in that count is counted once, at the level whose limit refused it: where the kernel has pids.events.local, pids.events is hierarchical, so the local count is read at each level; without it, pids.events. A refusal at a sibling's own cap (another run's scope) is not in it. Where there is no cgroup v2 (macOS), only (a) applies. A run that failed with none of this evidence keeps its reason. Bun's abort at start (`panic(main thread): abort() called`) carries no refusal text, so it is :resource-exhausted only when (b) or (c) shows a refusal; on macOS it stays an ordinary failure. The person decided on 2026-11-25, answering round 1 of design-loop-12430750, that a run is reclassified only on evidence the host refused it: a startup abort with no such evidence stays an ordinary failure. A run that completed, asked or was cancelled is never reclassified."
  {:about [turn/classify-exhaustion turn/pid-refusals-above] :evidence "test"})

(Claim a-refused-start-is-an-outcome
  "A launch whose ProcessBuilder.start throws an IOException immediately becomes that run's outcome: :failed {:reason :launch-failed} carrying the exception's message, which [a-refused-run-is-resource-exhausted] then classifies, so a fork the host refused (error=11, Resource temporarily unavailable) is reported :resource-exhausted. The turn does not stay :running."
  {:about [turn/core-turn turn/classify-exhaustion] :evidence "test"})

(Claim an-exhausted-conductor-backs-off
  "A conductor turn that ends :resource-exhausted puts its heard items back in the inbox and does not count toward :conductor-failures, so it never raises the stop ask. It increments :conductor-exhaustions and sets :conductor-retry-at to the detail's :at plus a backoff of 30s, doubling with each consecutive exhaustion and capped at 15m. A completed or asked conductor turn, or an answered stop ask, clears all three counters."
  {:about [domain/evolve domain/exhausted? domain/exhaustion-backoff-ms] :evidence "test"})

(Claim nothing-wakes-a-backing-off-conductor
  "While the time is before a workstream's :conductor-retry-at, no wake is issued for it, not even for a person's input. While any backoff is pending, the rules thread waits on its queue no longer than until the earliest retry time, then decides the wakes again over the states it holds."
  {:about [buffer/core-buffer buffer/wakes buffer/next-retry-in] :evidence "test"})

(Claim an-exhausted-unit-turn-goes-to-its-conductor
  "A unit's turn (a seat) that ends :resource-exhausted is handled like any failed unit turn. The conductor hears a :turn-ended item with :outcome :failed, and the :resource-exhausted detail stays on the turn. No backoff applies, and no rule re-runs the turn."
  {:about [domain/evolve] :evidence "round"})

(Claim judges-share-their-callers-scope
  "Review judges are not runs the core starts and get no cgroup placement of their own: they start as children of the process running the review loop, so they are in whatever cgroup that process is in. When the loop runs in a seat and the runner has a scope, that is the seat's scope, and the judges count against its cap of 8000. Without a scope, a seat and its judges are in the core's cgroup with no cap of their own."
  {:about [passes/review-claude] :evidence "round"})

(Claim project-shells-run-without-a-callers-startup-file
  "A project command (nido:run), a :process service and a :shell setup step run as `bash -lc` with the calling process's environment minus BASH_ENV and ENV, plus the variables the call site sets for that command. A BASH_ENV or ENV in one of these shells is therefore one those variables set, never one inherited from the caller."
  {:about [process/platform-process support/run-command! service/start-service! provisioning/session-engine] :evidence "test"})

(Claim a-seats-agent-keeps-its-projects-env
  "nido does not clear BASH_ENV or ENV for a seat's agent: no setting nido composes, no executor :env entry and no launch step sets or removes either, so the project's .claude/settings.json env stays among the seat's agent's settings sources unchanged (in a brian seat it includes BASH_ENV=env.sh). How the claude CLI applies those settings to its tool shells is not nido's and is not claimed here (see :open)."
  {:about [executor-claude/executor-claude] :evidence "round"})
