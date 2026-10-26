(ns canvas.claims.session-substrate
  "The claims the session-substrate design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.agent :refer [launch!]]
            [canvas.coordinator.agent.substrate :refer [agent-substrate send! wake! dispatch!
                                                        ensure-trusted! listing]]
            [canvas.coordinator.executor :refer [coordinator-executor]]
            [canvas.coordinator.lane.conduct :refer [lane-conduct conduct! recover! adopt!
                                                     hand-back!]]
            [canvas.coordinator.record.session :refer [record-session]]
            [canvas.coordinator.record.session-events :refer [record-session-events]]
            [canvas.tasks.nido-session-hook :refer [nido-session-hook]]
            [canvas.boot.attention :refer [boot-attention]]
            [canvas.coordinator.view.workstreams :refer [session-rows]]
            [canvas.session.briefing :refer [session-launcher]]))

(Claim one-door-to-the-substrate
  "Only the substrate adapter starts a background session, lists sessions, or attaches to, messages, wakes, stops, respawns or resumes a Claude Code session, and only it assembles the command line that continues a recorded conversation: no lane, task, surface, boot or session namespace does any of these itself. A one-shot agent call is not such a session, and neither the foreground claude nido work starts in a person's terminal nor the terminal tab revive opens for a person, running the command line the adapter gives it, is any of these."
  {:about [agent-substrate] :evidence "round"})

(Claim conversations-start-in-the-background
  "Every conversation nido starts is a Claude Code background session started through dispatch!. A one-shot agent call, as one-shot-calls-stay-calls defines it, is the one kind of claude process nido runs that is not a conversation it starts. A claude a person starts — by hand, through nido work, through a spawn kickoff in their terminal, or through the tab revive opens for them — is that person's own session."
  {:about [dispatch! lane-conduct] :evidence "round"})

(Claim one-shot-calls-stay-calls
  "A one-shot agent call is a claude process that code in nido.review or nido.vsdd starts and waits on for a value its loop consumes — the review reviewers, warden, fixer and amender, the design-verdict judge and vsdd's agents among them — or that the board-title generator in nido.coordinator.lane.titles starts and waits on for a title. It runs as that process, never as a session dispatched to the substrate; only the loop that started it may continue it, as a review run resumes its fixer across its repair rounds, and conduct never drives it. Its command line limits the settings it loads to the user and project sources, so no settings.local.json — and with it neither nido's session event hook nor the supervisor briefing — reaches it, whatever directory it runs in."
  {:about [launch!] :evidence "round"})

(Claim driver-is-recorded-not-inferred
  "A session record's :claude facet states who drives the session now — nido, a person, or nobody — and whether a hold stands, independently of who started it: a person from the turn that sets a hold until hand-back! clears it and makes nido the driver again, and adopt! refuses a session whose hold stands, naming the hold. Origin is recorded there and no decision about a session with that facet reads it."
  {:about [record-session lane-conduct] :evidence "round"})

(Claim send-is-a-message
  "nido delivers an instruction to a session with a :claude facet only as a message on its inbox while it has a live process, and otherwise only by waking it in the background under its own id; it never stops, restarts or copies such a session to deliver input."
  {:about [send! wake! conduct!] :evidence "round"})

(Claim sends-only-when-driving-and-done
  "nido delivers an instruction to a session with a :claude facet only when the facet says nido drives it, no hold stands, and no earlier instruction to it is outstanding — given, and not yet followed by that session's Stop. It sends a message only to one with a live process that the substrate reports done, or blocked with its Stop as its latest recorded event, with no work of its own still running, and wakes only one the listing shows without a live process whose latest recorded event is its Stop. A session's latest recorded event, here and in every claim that names it, is its latest event other than a notification: Claude Code sends one to a session sitting idle after its Stop, and it reports neither a permission mode nor a turn."
  {:about [conduct!] :evidence "round"})

(Claim a-person-speaking-takes-over
  "Every instruction nido gives a session with a :claude facet — its first prompt, every send and every wake — carries a token recorded as pending before it is given. In such a session nido drives, a turn nido did not give, recorded or listed since nido last began driving it — by dispatch, adoption or hand-back — sets a hold at conduct's first decision about that session after the turn was recorded or listed; such a turn is a recorded prompt without the pending, unconsumed token, or a turn that starts after a Stop with no work pending and no instruction given since. Only an explicit hand-back clears the hold."
  {:about [conduct! hand-back! record-session-events] :evidence "round"})

(Claim turn-end-is-observed
  "nido learns that a turn of a session with a :claude facet ended from the session's own Stop event and the substrate's state, never from a process exiting; such a session's process ending is a fact about liveness, not an outcome."
  {:about [conduct! record-session-events] :evidence "round"})

(Claim hooks-record-never-decide
  "nido's session event hooks — the hook command that appends to a session's event log, not the commit gate the launch baseline installs beside them — only record what the session reported, add the supervisor briefing as context at session start, and always let the session proceed, including when recording fails; they make no decision about a session."
  {:about [nido-session-hook record-session-events] :evidence "round"})

(Claim one-launch-baseline
  "nido's launch baseline — permission mode auto and nido's hooks, which are its session event hooks and the commit gate, with the supervisor briefing nido's SessionStart hook gives as context — is held in the settings.local.json of the nido session home or worktree a session runs in, a workstream's own checkout included when it runs there through a linked worktree; it reaches the sessions started there and no one-shot agent call, as one-shot-calls-stay-calls states. A session a person starts through nido work, the kickoff nido session spawn stages included, runs with permissions skipped, which Claude Code takes only from its command line: nido work passes --dangerously-skip-permissions and accepts no permission mode from its caller, and the command line the adapter gives revive for a person to continue a recorded conversation passes the same. No other command line nido assembles for a claude session in such a directory carries a permission mode or any other part of the baseline: dispatch!, wake! and respawn! carry none. A session's MCP config, add-dirs, name and model are not part of it; nido dispatches only into such a directory. What nido verifies of a session's baseline is what the session's own recorded events show: that nido's session event hook recorded its latest SessionStart from such a directory, and that its latest recorded event shows a permission mode that asks nobody — auto or bypassPermissions. nido adopts a session, and sends to or wakes a session with a :claude facet, only when both show, by the same check whoever started the session; an adoption that fails it is refused, naming which does not show."
  {:about [dispatch! adopt! conduct! session-launcher] :evidence "round"})

(Claim needs-a-person-is-blocked
  "A session with a :claude facet that needs a person is one the substrate reports blocked, and nido sends it nothing while it is blocked mid-turn — while its latest recorded event is not its Stop. Blocked after its Stop, it is waiting on its next instruction, and nido gives it one queued for it as sends-only-when-driving-and-done states. nido learns an entry into blocked from the needs-a-person notification event the session's hooks record, or from the listing showing it blocked when no such event was recorded; it marks the entry announced in the session's :claude facet until the session leaves blocked, so each entry raises at most one notification of its own — none if the post fails or nido stops between marking and posting — never folded into an overflow banner. Every auto-mode denial the session's hooks record while nido drives it is shown in the gate inbox."
  {:about [conduct! boot-attention record-session-events] :evidence "round"})

(Claim liveness-from-the-substrate
  "Whether a session is alive and what it is doing are read from the substrate's listing; nido keeps no lock, pid or output log of its own for a session."
  {:about [listing lane-conduct] :evidence "round"})

(Claim recovery-once-per-boot
  "At nido's first start after a host boot, recover! restarts, once, each session with a :claude facet whose policy is auto-respawn and that has no live process, whatever stopped it, and restarts no other session; later starts in the same boot restart nothing. Every other session with a :claude facet and no live process stays in the substrate's listing, where attaching to it — one action — resumes it from its saved conversation, and appears as resumable in nido's session list when it is within that list's scope."
  {:about [recover! session-rows] :evidence "round"})

(Claim a-crash-is-the-supervisors
  "nido leaves a background session whose process dies while the host stays up to Claude Code's supervisor: no nido code wakes or respawns a session for the death of its process, other than the restart recover! makes at nido's first start after a host boot, as recovery-once-per-boot states."
  {:about [agent-substrate] :evidence "round"})

(Claim trust-follows-the-source
  "nido accepts Claude Code's workspace trust only for a worktree or session home it created from a source repository already trusted; a dispatch anywhere else that is untrusted is refused naming the directory."
  {:about [ensure-trusted! dispatch!] :evidence "round"})

(Claim slot-is-a-working-session
  "A session with a :claude facet that nido drives occupies a concurrency slot while the substrate reports it working, under the same caps its unit was admitted under: it counts against the global cap unless its unit is uncapped, and against its trigger's in-flight limit either way, beside the executor's bodies in flight as the executor counts them. A session a person drives occupies none."
  {:about [coordinator-executor lane-conduct] :evidence "round"})

(Claim session-list-shows-substrate-state
  "nido's session list — a workstream's session rows — shows each session the substrate lists whose SessionStart nido's session event hook recorded from that workstream's session home or worktree and whose latest recorded event shows a permission mode that asks nobody, as one-launch-baseline states, in the state the listing reports for it, and who drives it in one vocabulary whoever started it: the driver its :claude facet records, and nobody for a session without one."
  {:about [session-rows] :evidence "round"})
