(ns canvas.ui.side-chat
  "Self-spec: `nido.ui.side-chat` — the dashboard's side chats: each one nido's operator in a
   conversation of its own, pinned to the place it was started from unless the person unlinks it,
   shown on every page the server draws through the shell with the dashboard's context, other than
   the catalog."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.session.chat :as chat]
            [canvas.ui.chat-host :as host]
            [canvas.ui.place :as place]
            [canvas.libraries.ui :refer [render signal]]
            [fukan.common.typing.malli]))

(Kind AnchorEntry
  "One entry of a side chat's anchor log, which only grows: `started` where the chat was begun,
   `unlinked` at the anchor the person took it from, `pinned` at the anchor they pinned it to, or
   `closed` where they ended it — each with when. An anchor is a workstream by project and id when
   the place has one open, else the page's path. A `pinned` and an `unlinked` entry always name
   one; a `started` entry names one — pinned there — or none, following the person, as a start
   from an unknown place does; a `closed` entry names none when closed from an unknown place. The
   first entry is where the chat came from; the last says where it is: `started` or `pinned` at
   an anchor pins it there, `started` at none or `unlinked` has it follow the person, and `closed`
   shows it nowhere. A log is legal when it starts once, first, and goes on after no close.")

(Module ui-side-chat
  "The dashboard's side chats: each one nido's operator in a conversation of its own, in a side
   panel on every page the server draws through the shell with the dashboard's context, other than
   the catalog, which keeps its own chat. A panel shows the chats visible where the person is: those
   pinned to that place's anchor and those that follow the person.

   HIDES the operator — what its agent is told, where it runs, and that it reaches a workstream's
   conductor only through nido's tell verb, by its own judgment — each chat's anchor log, where it
   is kept and the one parse through which a stored chat becomes a trusted log or is refused, when
   the person last had each chat open and when each of its permission requests began, the paths a
   chat's actions are served under,
   that each send carries the place of the send request and the chat's anchor and names each image
   to the agent by digest, keeping it for a relay, and that open or closed is kept in a cookie so
   the next page renders it the same. A page draws its panel from its own location, and every
   later change of the location has the panel ask again for the chats visible at the place the
   request states."
  {:child [AnchorEntry]}
  (Operation open? "Whether a request's page renders the chat open: what the person last left it as."
    {:signature [:=> [:catn [:req :map]] :boolean]})
  (Operation panel
    "The side panel for the page a request draws, open or closed: the chats visible at that page's
     place, one of them open — the one the person last had open."
    {:signature [:=> [:catn [:req :map] [:open? :boolean]] :any]
     :delegates [render signal host/router chat/transcript place/page-place]})
  (Operation unseen
    "The anchors with a chat pinned to them whose turn ended, or which waits on a permission, since
     the person last had it open."
    {:signature [:=> [:catn] [:set :map]]
     :delegates [chat/transcript]})
  (Operation handle!
    "One request under /_chat: the chats visible at the place a request states, or one request of
     one chat — its stream, a send, a stop, a permission answer, an image, opening, unlinking or
     pinning it, the panel expanding or collapsing onto it, starting a new one, closing one — or
     nil for any other path."
    {:signature [:=> [:catn [:req :map]] [:maybe :map]]
     :delegates [host/host host/handle! place/place place/references]}))
