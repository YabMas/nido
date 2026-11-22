(ns canvas.claims.session-substrate
  "The claims the session-substrate design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.agent :refer [launch!]]
            [canvas.coordinator.executor :refer [coordinator-executor]]
            [canvas.coordinator.record.session :refer [record-session]]
            [canvas.coordinator.record.session-events :refer [record-session-events]]
            [canvas.tasks.nido-session-hook :refer [nido-session-hook]]
            [canvas.boot.attention :refer [boot-attention]]
            [canvas.coordinator.view.workstreams :refer [session-rows]]
            [canvas.session.briefing :refer [session-launcher]]))

(Claim one-shot-calls-stay-calls
  "A one-shot agent call is a claude process that code in nido.review starts and waits on for a value its loop consumes — the review reviewers, warden, fixer and amender, the design-verdict judge among them — or that the board-title generator in nido.coordinator.lane.titles starts and waits on for a title. It runs as that process, never as a session dispatched to the substrate; only the loop that started it may continue it, as a review run resumes its fixer across its repair rounds, and conduct never drives it. Its command line limits the settings it loads to the user and project sources, so no settings.local.json — and with it neither nido's session event hook nor the supervisor briefing — reaches it, whatever directory it runs in."
  {:about [launch!] :evidence "round"})

(Claim hooks-record-never-decide
  "nido's session event hooks — the hook command that appends to a session's event log, not the commit gate the launch baseline installs beside them — only record what the session reported, add the supervisor briefing as context at session start, and always let the session proceed, including when recording fails; they make no decision about a session."
  {:about [nido-session-hook record-session-events] :evidence "round"})

(Claim session-list-shows-substrate-state
  "nido's session list — a workstream's session rows — shows each session the substrate lists whose SessionStart nido's session event hook recorded from that workstream's session home or worktree and whose latest recorded event shows a permission mode that asks nobody, as one-launch-baseline states, in the state the listing reports for it, and who drives it in one vocabulary whoever started it: the driver its :claude facet records, and nobody for a session without one."
  {:about [session-rows] :evidence "round"})
