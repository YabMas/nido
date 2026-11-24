(ns canvas.claims.coordinating-chat
  "The claims the coordinating-chat design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [status]]
            [canvas.coordinator.record.session :refer [record-session]]
            [canvas.coordinator.work :refer [coordinator-work list-workstreams]]
            [canvas.session.chat :refer [session-chat]]
            [canvas.tasks.nido-session :refer [start up]]
            [canvas.tasks.nido-workstream :refer [list-cmd]]
            [canvas.ui.chat-host :refer [ui-chat-host]]
            [canvas.ui.side-chat :refer [ui-side-chat]]
            [canvas.ui.surface :refer [ui-server]]))

(Claim overview-is-the-boards-reading
  "The list command prints, for each workstream it shows, the band, who owes the next move and what is underway that the work plane's listing answers — the reading the dashboard's board draws — and derives none of them itself."
  {:about [list-cmd list-workstreams] :evidence "round"})

(Claim goal-start-is-a-persons-start
  "start reaches a session only through the path up takes, so the session is up when the command returns and nothing is queued for the daemon; it needs no session to run in, activates the workstream it minted before the session is carried, and gives that session as its first instruction to record the brief's goal as the workstream's intent, followed by the brief verbatim."
  {:about [start up] :evidence "round"})

(Claim chat-session-is-not-work
  "A workstream's status passes over a session record marked the chat's when it reads whether the workstream is live, as it passes over the conductor's: a chat's session being up never makes its workstream live."
  {:about [status record-session] :evidence "round"})

(Claim conversation-chosen-by-place
  "Which conversation the dashboard's panel shows is chosen by the place its page shows. A page drawing a workstream the core holds gets that workstream's chat, served under a path naming its project and workstream; every other page, and a workstream the core does not hold, gets nido's operator, under /_chat. The server passes the place; side-chat alone maps a place to a conversation, asks the work plane whether the core holds the workstream and where its chat runs, creates no session record itself, and serves each conversation through a chat host made for that conversation and path. A request under a workstream's chat path that names no workstream the core holds is answered by no conversation."
  {:about [ui-side-chat ui-chat-host ui-server coordinator-work] :evidence "round"})
