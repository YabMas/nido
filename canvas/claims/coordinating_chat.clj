(ns canvas.claims.coordinating-chat
  "The claims the coordinating-chat design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor]]
            [canvas.coordinator.lane.drive :refer [status]]
            [canvas.coordinator.lane.seats :refer [lane-seats]]
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

(Claim workstream-chat-holds-no-seat
  "A workstream's chat is a conversation in a session of its own on that workstream: one record marked the chat's, which can carry neither a seat nor the conductor's mark, made ready by lane-seats on the workstream's runtime with no services, through the bring-up the chat's caller names to session-chat. So a record its agent stamps is stamped as that session and never as a seat, and it can send no seat message. The chat is started with instructions naming its project and workstream id and directing its agent to read that workstream's goal and newest design from its ledger before it answers about them. An act of the person's that its agent carries out at their word — closing, deactivating, answering a blocker — is recorded as the person's, as it is from any session."
  {:about [session-chat record-session lane-seats] :evidence "round"})

(Claim chat-turns-are-not-handed-back
  "A turn end in a workstream's chat session is posted to no one. On an activated workstream, being the conductor's or the chat's is what exempts a session's turn end; a turn end of any other session that ended no instruction recorded for a Run is still a hand-back."
  {:about [lane-conductor record-session] :evidence "round"})

(Claim chat-session-is-not-work
  "A workstream's status passes over a session record marked the chat's when it reads whether the workstream is live, as it passes over the conductor's: a chat's session being up never makes its workstream live."
  {:about [status record-session] :evidence "round"})

(Claim chat-word-is-a-hand-back
  "What a workstream's chat tells the conductor is posted once to the conductor as a hand-back naming the chat's session, which the conductor answers with one decision like any other; it is not marked a person's turn, so an escalation decided on it is answered as any blocker is and by no later chat message. Only the chat's session may tell, and only on an open, activated workstream: tell! reads the telling session from the directory it runs in, never from its caller, and answers the hand-back's id or a refusal naming which of those failed, or that the text was blank. A seat message is still accepted only from a seat's occupant."
  {:about [lane-conductor lane-seats] :evidence "round"})

(Claim conversation-chosen-by-place
  "Which conversation the dashboard's panel shows is chosen by the place its page shows. A page drawing a workstream with a chat to offer — open, or closed with its chat's session record still standing — gets that workstream's chat, served under a path naming its project and workstream; every other page, and a workstream with no chat to offer, gets nido's operator, under /_chat. The server passes the place; side-chat alone maps a place to a conversation, asks the work plane whether the place has a chat and to make its session ready, creates no session record itself, and serves each conversation through a chat host made for that conversation and path. A request under a workstream's chat path that names no workstream with a chat to offer is answered by no conversation."
  {:about [ui-side-chat ui-chat-host ui-server coordinator-work] :evidence "round"})
