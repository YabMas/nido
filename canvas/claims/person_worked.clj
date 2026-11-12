(ns canvas.claims.person-worked
  "The claims the person-worked design (ws-20261105-4f661c) commits nido to, declared where they
   live on: a workstream is worked only while a person types in it, a restore needs a person,
   and clearing a breaker keeps a person's pause.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.control :refer [clear-breaker! resume-trigger!]]
            [canvas.coordinator.lane.conductor :refer [conductors! prompted]]
            [canvas.coordinator.lane.restore :refer [continuation]]
            [canvas.coordinator.record.session :refer [person-prompt? prompted-sessions]]
            [canvas.ui.surface :refer [ui-views ui-tui]]))

(Claim delivered-prompts-are-no-persons
  "person-prompt? is false for a prompt whose text carries a nido send token ([nido-send …]) or a harness delivery wrapper (<cross-session-message, <agent-message, <task-notification, [Cross-session delivery notice]), and true for every other recorded prompt."
  {:about [person-prompt?] :evidence "round"})

(Claim a-checkout-is-no-sessions
  "prompted-sessions attributes a prompt to the session whose home or real worktree is the deepest directory equal to or containing its cwd, and to no session when that directory is a registered project's own checkout or more than one session owns it."
  {:about [prompted-sessions] :evidence "round"})

(Claim unworked-conductor-is-retired
  "On a pass whose conductor/prompted reading answered a set, conductors! retires a running conductor whose workstream is closed, settled in Notion, or not worked by that reading. A retire that throws is logged and leaves the conductor to a later pass."
  {:about [conductors!] :evidence "round"})

(Claim one-reading-per-sweep
  "One conductor pass reads the prompt log once, through conductor/prompted, which answers the set of prompted sessions or :unread when the log could not be read. On a set, every conductors! and reseat! call in that pass judges worked by it; on :unread, the pass makes no conductor decision."
  {:about [prompted conductors!] :evidence "round"})

(Claim restore-needs-a-person
  "continuation withdraws, naming :why :no-person, a failure whose origin is a person's (or absent) when no person-prompt? prompt was attributed to its session in the 24 hours before the failure; a person-origin failure with such a prompt is restored as before. When the prompt log cannot be read, continuation answers :unread and restore-one! leaves the failure :not-restored, still owed — never withdrawn as :no-person, and without stopping the restore of the cause's other failures."
  {:about [continuation] :evidence "round"})

(Claim clear-keeps-a-pause
  "clear-breaker! clears a trigger's automatic trip and failure count and leaves :disabled-by-user? and its note as they were. A person's pause is lifted only by resume-trigger!, which the dashboard and the TUI call, or by bb nido:trigger:enable, the CLI's explicit resume, which is kept as it is."
  {:about [clear-breaker! resume-trigger!] :evidence "round"})

(Claim a-pause-shows-its-resume
  "The dashboard's ops panel shows each open breaker's reason, offers clear only for a tripped one and resume only for a paused one. The TUI's ops overlay offers the same two levers: its clear picker (c) lists only tripped triggers and its resume picker (r) only paused ones, each with its reason — for a pause, the note its person left."
  {:about [ui-views ui-tui] :evidence "round"})

(Claim the-worked-incident-is-reproduced
  "The change lands with tests that fail on the code before it: a workstream whose only activity is older than the window, or nido-sent, or delivered from another session, gets no conductor, and its running conductor is retired; a person-origin failed start in a session nobody typed into is withdrawn as :no-person; clearing a paused breaker leaves it paused. It lands only once bb nido:test is green and bb nido:land:check prints no REFUSED line, is pushed to nido main, and bb nido:land:record succeeds."
  {:about [conductors! continuation clear-breaker!] :evidence "round"})
