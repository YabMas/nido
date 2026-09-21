(ns canvas.claims.headless-launch
  "The claims the headless-launch design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.agent :refer [launch! parse-budget-ms]]
            [canvas.session.resume-shim :refer [session-resume-shim]]))

(Claim headless-agents-run-in-the-foreground
  "Every claude that launch! starts runs with CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1 and CLAUDE_CODE_DISABLE_CRON=1 in its environment whatever the caller passes, and with --disallowedTools ScheduleWakeup on its command, so its agent is offered no run_in_background parameter, no Cron tool and no ScheduleWakeup."
  {:about [launch!] :evidence "round"})

(Claim budget-is-the-only-clock
  "Every claude launch! starts runs with CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0, so claude sets no ceiling of its own on waiting for work its agent is still running, and the run's budget timer is the only limit on how long the process runs."
  {:about [launch! parse-budget-ms] :evidence "round"})

(Claim interactive-sessions-keep-claudes-defaults
  "A session a person drives never passes through launch! and is started with none of the headless settings: the resume shim execs claude --resume in the person's terminal with their own environment, so they are offered claude's defaults."
  {:about [launch! session-resume-shim] :evidence "round"})
