(ns canvas.ui.side-chat
  "Self-spec: `nido.ui.side-chat` — the dashboard's chat, on every page the server draws through the shell
   with the dashboard's context, other than the catalog, chosen by the place the page shows."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.work :as work]
            [canvas.session.chat :as chat]
            [canvas.ui.chat-host :as host]
            [canvas.ui.surface :refer [side signals]]
            [fukan.common.typing.malli]))

(Module ui-side-chat
  "The dashboard's chat: a conversation with an agent in a side panel on every page the server
   draws through the shell with the dashboard's context, other than the catalog, which keeps its
   own chat — chosen by the place the page shows.

   HIDES which conversation each place has (at an open workstream that workstream's own chat, in a
   session on it that holds no seat; anywhere else nido's operator session, made on the first
   send), what each agent is told — operate nido, or talk about one workstream and pass word to
   its conductor, through nido's own verbs and changing no code — the paths each conversation is
   served under, how a message says which page it was sent from, and that open or closed is kept
   in a cookie so the next page renders it the same."
  (Operation open? "Whether a request's page renders the chat open: what the person last left it as."
    {:signature [:=> [:catn [:req :map]] :boolean]})
  (Operation panel
    "The chat's side panel for a dashboard page showing a place — a workstream, or none — open or
     closed."
    {:signature [:=> [:catn [:open? :boolean] [:place [:maybe :map]]] :any]
     :delegates [side signals chat/transcript work/chat-place]})
  (Operation handle!
    "One request under /_chat, of the conversation its path names, or nil for any other path and
     for a workstream's chat path that names no open workstream."
    {:signature [:=> [:catn [:req :map]] [:maybe :map]]
     :delegates [host/host host/handle! work/chat-place work/open-chat!]}))
