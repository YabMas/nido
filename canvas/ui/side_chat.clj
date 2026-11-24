(ns canvas.ui.side-chat
  "Self-spec: `nido.ui.side-chat` — the dashboard's chat, on every page the server draws through the shell
   with the dashboard's context, other than the catalog, chosen by the place the page shows."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.work :as work]
            [canvas.session.chat :as chat]
            [canvas.ui.chat-host :as host]
            [canvas.ui.place :as place]
            [canvas.libraries.ui :refer [render signal]]
            [fukan.common.typing.malli]))

(Module ui-side-chat
  "The dashboard's chat: a conversation with an agent in a side panel on every page the server
   draws through the shell with the dashboard's context, other than the catalog, which keeps its
   own chat — chosen by the place the page shows.

   HIDES the one side chat and the contexts it runs in. Every context shares the machinery — a
   session-chat conversation served through a chat host, whose sends carry the page's place and
   name each image to the agent by digest — and supplies only its own instructions, where its agent
   runs, and the target it relays to: at a workstream the core holds, that workstream, run where
   the work plane says, relaying to its conductor through nido's tell verb and showing what reached
   it; anywhere else nido's operator session, relaying to nothing. Also the paths each conversation
   is served under, and that open or closed is kept in a cookie so the next page renders it the
   same."
  (Operation open? "Whether a request's page renders the chat open: what the person last left it as."
    {:signature [:=> [:catn [:req :map]] :boolean]})
  (Operation panel
    "The chat's side panel for a dashboard page showing a place — a workstream, or none — open or
     closed."
    {:signature [:=> [:catn [:open? :boolean] [:place [:maybe :map]]] :any]
     :delegates [render signal host/router chat/transcript work/chat-dir]})
  (Operation handle!
    "One request under /_chat, of the conversation its path names, or nil for any other path and
     for a workstream's chat path that names no open workstream."
    {:signature [:=> [:catn [:req :map]] [:maybe :map]]
     :delegates [host/host host/handle! place/place place/references]}))
