(ns canvas.claims.coordinating-chat
  "The claims the coordinating-chat design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor]]
            [canvas.coordinator.lane.seats :refer [lane-seats]]
            [canvas.coordinator.work :refer [list-workstreams]]
            [canvas.session.chat :refer [session-chat]]
            [canvas.tasks.nido-session :refer [start up]]
            [canvas.tasks.nido-workstream :refer [list-cmd]]))

(Claim overview-is-the-boards-reading
  "The list command prints, for each workstream it shows, the band, who owes the next move and what is underway that the work plane's listing answers — the reading the dashboard's board draws — and derives none of them itself."
  {:about [list-cmd list-workstreams] :evidence "round"})

(Claim goal-start-is-a-persons-start
  "start reaches a session only through the path up takes, so the session is up when the command returns and nothing is queued for the daemon; it needs no session to run in, activates the workstream it minted before the session is carried, and gives that session as its first instruction to record the brief's goal as the workstream's intent, followed by the brief verbatim."
  {:about [start up] :evidence "round"})

(Claim workstream-chat-holds-no-seat
  "A workstream's chat is a conversation in a session of its own on that workstream which occupies no seat, so a record its agent stamps is stamped as that session and never as a seat, and it can send no seat message. The chat is started with instructions naming its project and workstream id, so its agent reads that workstream's goal and newest design from its ledger without being told which workstream it is in. An act of the person's that its agent carries out at their word — closing, deactivating, answering a blocker — is recorded as the person's, as it is from any session."
  {:about [session-chat lane-seats] :evidence "round"})

(Claim chat-word-is-a-hand-back
  "What a workstream's chat tells the conductor is posted once to the conductor as a hand-back naming the chat's session, which the conductor answers with one decision like any other; a seat message is still accepted only from a seat's occupant."
  {:about [lane-conductor lane-seats] :evidence "round"})
