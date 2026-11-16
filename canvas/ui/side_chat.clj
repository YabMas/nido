(ns canvas.ui.side-chat
  "Self-spec: `nido.ui.side-chat` — the dashboard's chat, on every page the server draws through the shell
   with the dashboard's context, other than the catalog."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.session.chat :as chat]
            [canvas.ui.chat-host :as host]
            [canvas.ui.surface :refer [side signals]]
            [fukan.common.typing.malli]))

(Module ui-side-chat
  "The dashboard's chat: one conversation with an agent that operates nido's work, in a side panel
   on every page the server draws through the shell with the dashboard's context, other than the
   catalog, which keeps its own chat — following the person from page to page.

   HIDES which conversation that is (nido's operator session, made on the first send), what its
   agent is told — operate nido through its own verbs, change none of its code — how a message
   says which page it was sent from, and that open or closed is kept in a cookie so the next page
   renders it the same."
  (Operation open? "Whether a request's page renders the chat open: what the person last left it as."
    {:signature [:=> [:catn [:req :map]] :boolean]})
  (Operation panel "The chat's side panel for a dashboard page, open or closed."
    {:signature [:=> [:catn [:open? :boolean]] :any]
     :delegates [side signals chat/transcript]})
  (Operation handle! "One request under /_chat, or nil for any other path."
    {:signature [:=> [:catn [:req :map]] [:maybe :map]]
     :delegates [host/host host/handle!]}))
