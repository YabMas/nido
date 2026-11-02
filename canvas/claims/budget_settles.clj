(ns canvas.claims.budget-settles
  "The claims the budget-settles-recorded-ends design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.core :refer [tick!]]
            [canvas.coordinator.daemon.lifecycle :refer [reconcile!]]
            [canvas.coordinator.lane.conduct :refer [conduct! outstanding]]
            [canvas.coordinator.lane.conductor :refer [conductors!]]
            [canvas.coordinator.record.status-file :refer [record-status-file]]))

(Claim budget-never-outruns-a-recorded-stop
  "enforce-budgets! stops and settles a Run only when conduct/outstanding, asked at the brake's check, answers its instruction outstanding: a Run whose session had recorded, by that check, the Stop that ends it, folded or not, is left :running by the brake, for a later pass's fold to settle through settle-turn-ends!."
  {:about [outstanding tick!] :evidence "round"})

(Claim outstanding-is-what-the-fold-leaves
  "conduct/outstanding answers exactly the :pending that conduct!'s fold of the facet's unread events would leave, by the same fold; outside lane-conduct nothing reads a session's event log to decide whether an instruction has ended. :pending is written only inside lane-conduct."
  {:about [outstanding conduct!] :evidence "round"})

(Claim overrun-honours-a-reported-end
  "A Run that is not a :merge Run, stopped by enforce-budgets! past its budget, is settled by settle-run! as it settles a clean turn end when its status file reports a phase that status-file/phase->state maps to a state; when it reports none it is settled as an overrun — failed as :timeout, or, for a provision-only Run, parked :awaiting-review. A :merge Run's overrun is settle-drive-home!'s, outside this record's bound."
  {:about [tick! record-status-file] :evidence "round"})

(Claim status-file-decides-only-at-settle
  "The skill-written phase is read for a substrate Run only when the tick settles it — at its turn end, or at its budget overrun; while the Run is :running within its budget it triggers nothing."
  {:about [record-status-file tick!] :evidence "round"})

(Claim one-fold-per-pass
  "One conduct pass folds each session's events once, at conduct!'s start, and the conductor sweep runs between settle-turn-ends! and enforce-budgets!; enforce-budgets! reads what is outstanding at its own check, through conduct/outstanding, not from that fold."
  {:about [tick! conductors!] :evidence "round"})

(Claim the-incident-is-reproduced
  "The change lands with a regression test of the 2026-10-30 shape — run.edn :running past its budget, _run-status.edn {:phase :complete}, a background-session facet whose instruction is pending, and that session's Stop appended to its event log during the tick's conductor sweep, after conduct! has read the log — that fails before the change, the Run failing as :timeout, and passes after it, the Run settling to a non-:failed terminal state; and with a test presenting reconcile!, as at a daemon start, with run.edn :running past its budget, _run-status.edn {:phase :complete} and the session's Stop in its event log past the facet's persisted cursor, after which the next tick settles the Run to a non-:failed terminal state. It lands only once bb nido:test is green and bb nido:land:check prints no REFUSED line, is pushed to nido main, and bb nido:land:record succeeds."
  {:about [tick! reconcile!] :evidence "round"})
