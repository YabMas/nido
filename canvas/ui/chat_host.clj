(ns canvas.ui.chat-host
  "Self-spec: `nido.ui.chat-host` — one conversation served to the chat panels showing it."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.integration.acp :as acp]
            [canvas.session.chat :as chat]
            [canvas.ui.surface :refer [chat-item chat-log log-id turn-status signals
                                       patch-elements-event patch-signals-event]]
            [fukan.common.typing.malli]))

(Module ui-chat-host
  "One conversation served to the chat panels showing it.

   HIDES how a panel is kept live — the whole log, status row and busy signal once, then one patch
   per transcript item that changed, appended or morphed by its id, and the whole log again when
   the transcript shrank — which streams are open, and
   how a request's path and body are read. A host names its conversation, its panel's routes, the
   path its routes live under and what a sent message carries besides its words. Its caller may also
   name whether a send is accepted (`:holds?`, true while none may be), what an accepted send's
   response patches besides clearing the draft (`:on-sent`), and what a new stream gets after the
   log, status row and busy signal (`:opening`); the page around the panel and any request beyond
   the five it answers are its caller's."
  (Operation host "A host for one conversation, its panel's routes and the path they live under; its caller assocs :holds?, :on-sent and :opening onto it when it needs them."
    {:signature [:=> [:catn [:chat :map] [:routes :map] [:prefix [:sequential :string]] [:refs :any]] :map]})
  (Operation html "Hiccup as markup text."
    {:signature [:=> [:catn [:hiccup :any]] :string]})
  (Operation broadcast! "One event down every stream the host has open."
    {:signature [:=> [:catn [:host :map] [:event :string]] :any]})
  (Operation sse "A finished server-sent-events response carrying some events."
    {:signature [:=> [:catn [:events [:* :string]]] :map]})
  (Operation body-json "A request's JSON body, string-keyed; empty when absent or unparseable."
    {:signature [:=> [:catn [:req :map]] :map]})
  (Operation changed-events
    "The patches that take a page from one transcript to the next: new items appended, changed
     ones morphed by id, the status row and the busy signal only when they change — and the whole
     log, status row and busy signal when the new transcript is shorter."
    {:signature [:=> [:catn [:routes :map] [:old acp/Conversation] [:new acp/Conversation]] :any]
     :delegates [chat-item chat-log log-id turn-status signals patch-elements-event patch-signals-event]})
  (Operation handle!
    "One request under the host's path — the stream, a send, a stop, a reset, a permission
     answer — or nil for any other, which is its caller's."
    {:signature [:=> [:catn [:host :map] [:req :map]] [:maybe :map]]
     :delegates [changed-events chat-log chat/transcript chat/send! chat/cancel! chat/reset! chat/answer-permission!
                 chat/subscribe!]}))
