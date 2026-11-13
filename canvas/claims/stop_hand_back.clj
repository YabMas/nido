(ns canvas.claims.stop-hand-back
  "The claims the stop-hand-back design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.core :refer [tick!]]
            [canvas.coordinator.agent.substrate :refer [dispatch! wake! pending-dialog]]
            [canvas.coordinator.lane.conductor :refer [stops!]]
            [canvas.coordinator.lane.drive :refer [status WorkstreamStatus]]
            [canvas.coordinator.work :refer [activate!]]
            [canvas.tasks.nido-session :refer [spawn]]))

(Claim spawned-session-is-activated-at-birth
  "bb nido:session:spawn activates the child's workstream before it carries or drives the child on its brief, so the child's first turn end is its conductor's to read. Only a session a person starts by hand stays plain until they activate it."
  {:about [spawn activate!] :evidence "round"})

(Claim driven-sessions-ask-no-dialogs
  "Every background session the substrate starts (dispatch!) or wakes (wake!), and every headless agent run, runs without AskUserQuestion; a respawn keeps its launch's list. A session a person starts by hand and the chat panel's sessions keep it."
  {:about [dispatch! wake!] :evidence "round"})

(Claim persons-turn-is-not-a-stall
  "On a workstream not activated — a scratch session a person started by hand, since a session nido starts on a brief is activated at birth — when nothing is live and the latest turn end of its faceted sessions ended no instruction nido gave, status answers :stalled false, with owed-by unchanged: the conversation is the person's and resting, not a fault."
  {:about [status] :evidence "round"})

(Claim stall-carries-why
  "When status reads :stalled it also answers :why: the faceted session whose turn ended last, the instant that turn ended, the move the position owes, and the turn's last message, or that no session of the workstream has recorded a turn end. Its :turn-end tag, :recorded or :none, says which of the two it is. When not stalled it carries no :why."
  {:about [status WorkstreamStatus] :evidence "round"})

(Claim stall-is-handed-back
  "On an activated workstream not paused, when status reads :stalled with a :why naming a recorded turn end at least ten minutes old, no session of it stands on a dialog, and its conductor owes no hand-back unanswered, stops! posts one hand-back to the conductor, keyed on that session and turn end, carrying the session, when its turn ended, the move owed and what it last said. The conductor answers it through nido:seats:decide like any hand-back. stops! sends nothing to the session and writes no blocker for a stall."
  {:about [stops! status] :evidence "round"})

(Claim dialog-reaches-the-person
  "When the listing has shown a session nido drives 'blocked' with status 'waiting' for at least two minutes, stops! appends one :blocker carrying :stop {:kind :dialog :session :since} on its workstream. Its :needs is the dialog's first question, or for a permission prompt the tool it waits to run, when pending-dialog can read it and it fits a blocker's question, and otherwise asks the person to answer the session's dialog; its :summary carries what the dialog asks whole; its :after says the answer is given in the session itself. No second blocker is written while that dialog stands. Once the listing no longer shows the session waiting, stops! answers that blocker {:by :nido}, unless something already answered it."
  {:about [stops! pending-dialog] :evidence "round"})

(Claim stop-blockers-have-one-writer
  "A blocker carrying :stop is written only by stops!, and only stops! answers one {:by :nido}; the daemon's tick runs stops! after each conduct pass's hand-backs. Neither status nor conduct! writes a ledger entry in response to a listing state or a stall."
  {:about [stops! tick!] :evidence "round"})

(Claim catalog-chat-replay
  "Replayed against the catalog-chat session as it stood at 15:00 on 2026-11-13 — listed state 'blocked', status 'waiting', on an AskUserQuestion its transcript shows unanswered — status answers :live false, and once stops! has passed over it two minutes on, status answers owed-by :person, at the dialog's blocker stops! wrote."
  {:about [status stops!] :evidence "round"})

(Claim landed-tip-is-checked
  "On the tip this change lands, bb nido:test is green, the catalog-chat replay among its tests, and bb nido:design:check reports ok."
  {:about [stops! status] :evidence "round"})
