(ns canvas.claims.coordinating-chat
  "The claims the coordinating-chat design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [status]]
            [canvas.coordinator.record.session :refer [record-session]]
            [canvas.coordinator.work :refer [coordinator-work list-workstreams start-goal!]]
            [canvas.session.chat :refer [session-chat]]
            [canvas.tasks.nido-session :refer [start]]
            [canvas.tasks.nido-workstream :refer [list-cmd]]
            [canvas.ui.chat-host :refer [ui-chat-host]]
            [canvas.ui.side-chat :refer [ui-side-chat]]
            [canvas.ui.surface :refer [ui-server]]))

(Claim overview-is-the-boards-reading
  "The list command prints, for each workstream it shows, the band, who owes the next move and what is underway that the work plane's listing answers — the reading the dashboard's board draws — and derives none of them itself."
  {:about [list-cmd list-workstreams] :evidence "round"})

(Claim goal-start-is-a-persons-start
  "start writes nothing when a session of the name it is given already exists; otherwise it mints a workstream and opens its unit in the core, named for the session, with a first turn that records the brief's goal as the workstream's intent and then works the brief verbatim. It brings no session up itself: the core provisions the unit's session under that name, and no session already up becomes the unit's."
  {:about [start start-goal!] :evidence "round"})

(Claim chat-session-is-not-work
  "A workstream's status passes over a session record marked the chat's when it reads whether the workstream is live, as it passes over the conductor's: a chat's session being up never makes its workstream live."
  {:about [status record-session] :evidence "round"})

