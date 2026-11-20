(ns canvas.ui.catalog-chat
  "Self-spec: `nido.ui.catalog.chat` — the component catalog's chat."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.control :as control]
            [canvas.session.chat :as chat]
            [canvas.ui.chat-host :as host]
            [canvas.ui.surface :refer [catalog-page chat-page change-bar shell picks signals
                                       patch-signals-event]]
            [fukan.common.typing.malli]))

(Module ui-catalog-chat
  "The catalog's chat: the page around the scene frame, and the routes its panel posts to.

   HIDES which conversation the catalog talks to (one design session, made on the first send),
   how a picked element is worded for the agent and labelled for the person, that the frame's
   requests are forwarded to the design session's preview, that a finished turn refreshes the
   preview, and how the session's unlanded change is published or thrown away. The panel's markup is the chat block's, keeping it live
   is the chat host's; the conversation and the preview are the Session band's."
  (Operation page
    "/_catalog: the scene page alone when it is the frame, else the catalog with its chat —
     closed unless the query string opens it — inside the dashboard's page chrome."
    {:signature [:=> [:catn [:query-string [:maybe :string]] [:chrome [:=> [:catn] :map]]] :string]
     :delegates [catalog-page chat-page shell change-bar chat/transcript chat/started?
                 chat/pending chat/landing control/latest-landing]})
  (Operation handle!
    "One request under /_catalog/chat: the stream, a send, a cancel, a permission answer, the
     picks posted for their chips or one removed, a publish or a revert; or the frame's own
     request, forwarded to the design session's preview once the conversation exists; nil for
     any other."
    {:signature [:=> [:catn [:req :map]] [:maybe :map]]
     :delegates [host/host host/handle! host/broadcast! host/sse host/body-json host/html picks
                 signals patch-signals-event change-bar chat/refresh! chat/note-error! chat/note!
                 chat/forward! chat/pending chat/landing chat/seal! chat/publish! chat/onto-main! chat/revert!
                 control/latest-landing]}))
