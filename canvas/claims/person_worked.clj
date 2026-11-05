(ns canvas.claims.person-worked
  "The claims the person-worked design (ws-20261105-4f661c) commits nido to, declared where they
   live on: a workstream is worked only while a person types in it, a restore needs a person,
   and clearing a breaker keeps a person's pause.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [conductors! prompted]]
            [canvas.coordinator.lane.restore :refer [continuation]]
            [canvas.coordinator.record.session :refer [person-prompt? prompted-sessions]]))

(Claim worked-is-a-recent-person-prompt
  "conductors! and reseat! treat an open workstream as worked exactly when one of its sessions is in the set conductor/prompted answers: the sessions a person-prompt? prompt recorded within the last 24 hours was attributed to. Nothing else — a session's creation, its origin, its seat, a hand-back — makes a workstream worked."
  {:about [conductors! prompted] :evidence "round"})

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
