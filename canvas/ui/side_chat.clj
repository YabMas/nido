(ns canvas.ui.side-chat
  "Self-spec: `nido.ui.side-chat` — the dashboard's chat: nido's operator, one conversation on every
   page the server draws through the shell with the dashboard's context, other than the catalog."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.session.chat :as chat]
            [canvas.ui.chat-host :as host]
            [canvas.ui.place :as place]
            [canvas.libraries.ui :refer [render signal]]
            [fukan.common.typing.malli]))

(Module ui-side-chat
  "The dashboard's chat: nido's operator, in a side panel on every page the server draws through
   the shell with the dashboard's context, other than the catalog, which keeps its own chat. One
   conversation, the same on every page.

   HIDES the operator — what its agent is told, where it runs, and that it reaches a workstream's
   conductor only through nido's tell verb, by its own judgment — the path it is served under,
   that each send carries the place of the send request and names each image to the agent by
   digest, keeping it for a relay, and that open or closed is kept in a cookie so the next page
   renders it the same."
  (Operation open? "Whether a request's page renders the chat open: what the person last left it as."
    {:signature [:=> [:catn [:req :map]] :boolean]})
  (Operation panel
    "The chat's side panel, open or closed: the operator's conversation, whatever the page shows."
    {:signature [:=> [:catn [:open? :boolean]] :any]
     :delegates [render signal host/router chat/transcript]})
  (Operation handle!
    "One request under /_chat, of the operator's conversation, or nil for any other path."
    {:signature [:=> [:catn [:req :map]] [:maybe :map]]
     :delegates [host/host host/handle! place/place place/references]}))
