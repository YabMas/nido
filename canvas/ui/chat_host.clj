(ns canvas.ui.chat-host
  "Self-spec: `nido.ui.chat-host` — one conversation served to the chat panels showing it."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.integration.acp :as acp]
            [canvas.session.chat :as chat]
            [canvas.libraries.ui :refer [patch-elements-event patch-signals-event render decode-action
                                         selector signal-root]]
            [fukan.common.typing.malli]))

(Module ui-chat-host
  "One conversation served to the chat panels showing it.

   HIDES how a panel is kept live — the whole log, status row and busy signal once, then one patch
   per transcript item that changed, appended or morphed by its id, and the whole log again when
   the transcript shrank — which streams are open, and
   how a request's path and body are read, which images a conversation has staged, and how an
   untrusted upload becomes an Image. A host names its conversation, its panel's view — the
   panel's instance id, the router its actions are routed through and whether it offers attaching
   — the path those actions live under and what a sent message carries besides its words. Every
   post a panel's action makes is read through the panel's own decoder. Its caller may also
   name what a send's acceptance runs inside (`:guard`, which may make it wait but never refuses it), what an accepted send's
   response patches besides clearing the draft (`:on-sent`), and what a new stream gets after the
   log, status row and busy signal (`:opening`); the page around the panel and any request beyond
   the five it answers are its caller's."
  (Operation router
    "The router for a panel whose actions are served under one path: each action at
     <path>/<action>, its params' values following as path segments in the order of their keys."
    {:signature [:=> [:catn [:base :string]] :any]})
  (Operation part
    "One part of a panel — its log, an item, its status row, its chips or its staged images — as
     the panel renders it, on the panel's instance: what a stream patches in place."
    {:signature [:=> [:catn [:view :map] [:part :keyword] [:props :map]] :any]
     :delegates [render]})
  (Operation signals-patch
    "The JSON a patch-signals event carries to set some of a panel's signals."
    {:signature [:=> [:catn [:id :string] [:signals :map]] :string]
     :delegates [signal-root]})
  (Operation decode
    "A request's body read against one of the panel's actions for one panel: the declared fields,
     or a named failure."
    {:signature [:=> [:catn [:action :keyword] [:id :string] [:req :map]] [:or [:map {:closed true} [:ok :map]] [:map {:closed true} [:failure [:= :unparseable]]] [:map {:closed true} [:failure [:= :missing]] [:key :keyword]] [:map {:closed true} [:failure [:= :invalid]] [:key :keyword] [:why :some]]]]
     :delegates [decode-action]})
  (Operation host "A host for one conversation, its panel's view and the path its actions live under; its caller assocs :guard, :on-sent and :opening onto it when it needs them."
    {:signature [:=> [:catn [:chat :map] [:view :map] [:prefix [:sequential :string]] [:refs :any]] :map]})
  (Operation html "Hiccup as markup text."
    {:signature [:=> [:catn [:hiccup :any]] :string]})
  (Operation broadcast! "One event down every stream the host has open."
    {:signature [:=> [:catn [:host :map] [:event :string]] :any]})
  (Operation sse "A finished server-sent-events response carrying some events."
    {:signature [:=> [:catn [:events [:* :string]]] :map]})
  (Operation changed-events
    "The patches that take a page from one transcript to the next: new items appended, changed
     ones morphed by id, the status row and the busy signal only when they change — and the whole
     log, status row and busy signal when the new transcript is shorter."
    {:signature [:=> [:catn [:view :map] [:old acp/Conversation] [:new acp/Conversation]] :any]
     :delegates [part selector signals-patch patch-elements-event patch-signals-event]})
  (Operation parse-image
    "An uploaded image as an Image — its media type read from its bytes' signature, one of the four
     the agent accepts, at most 5 MB decoded — or a refusal naming why."
    {:signature [:=> [:catn [:upload :map]] :map]})
  (Operation staged?
    "Whether any image is staged on a host for its next message."
    {:signature [:=> [:catn [:host :map]] :boolean]})
  (Operation take-staged!
    "Move the images staged on one host onto another's stage, after what it holds — none staged
     twice, at most four — and re-render both: how a first message written where no conversation
     exists yet carries its images into the conversation it starts."
    {:signature [:=> [:catn [:from :map] [:to :map]] :any]
     :delegates [part html broadcast! patch-elements-event]})
  (Operation handle!
    "One request under the host's path — the stream, a send, a stop, a reset, a permission
     answer, and, for a panel whose view offers attaching, staging an image, unstaging one and
     an image's bytes by digest — or nil for any other, which is its caller's."
    {:signature [:=> [:catn [:host :map] [:req :map]] [:maybe :map]]
     :delegates [changed-events parse-image part decode signals-patch chat/transcript chat/image chat/send! chat/cancel! chat/reset! chat/answer-permission!
                 chat/subscribe!]})
  (Operation via
    "The function `k` of a conversation: its backend's, else `f`."
    {:signature [:=> [:catn [:chat :map] [:k :keyword] [:f fn?]] fn?]}))
