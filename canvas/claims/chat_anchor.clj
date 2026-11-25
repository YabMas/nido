(ns canvas.claims.chat-anchor
  "The claims the chat-anchor design commits nido to: a side chat belongs to the place it was
   started from unless the person unlinks it, and the panel shows the chats visible where the
   person is.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.chat :refer [session-chat]]
            [canvas.ui.place :refer [ui-place]]
            [canvas.ui.side-chat :refer [ui-side-chat AnchorEntry]]
            [canvas.ui.surface :refer [ui-views ui-components-chat ui-components-row]]))

(Claim anchor-log-is-the-record
  "Where a side chat belongs is read only by folding its anchor log, which is appended to and never rewritten: its first entry is where it was started and never changes, its last says where it is now. The log is kept apart from the transcript, so a restart or a reset of the conversation leaves it whole."
  {:about [ui-side-chat AnchorEntry] :evidence "test"})

(Claim anchor-store-is-parsed-once
  "A stored chat becomes a trusted anchor log only through ui-side-chat's one parse, which admits it when every entry is an AnchorEntry and the log is legal — started first and only first, nothing after closed — and otherwise refuses it naming the chat and why; a refused chat is shown in no panel and its stored record is never rewritten. An append that fails to persist fails the action that asked for it and leaves the stored log as it was."
  {:about [ui-side-chat AnchorEntry] :evidence "round"})

(Claim pinned-by-default
  "A side chat's log begins with a started entry naming the anchor of the place it was begun from — by New chat, or by a first message written where no chat was visible — so a new chat is pinned there; one begun from an unknown place names no anchor and follows the person."
  {:about [ui-side-chat AnchorEntry] :evidence "round"})

(Claim anchor-is-the-places-subject
  "A place's anchor is its workstream by project and id when it has one open, else its page's path without the query, and is read from ui-place's place alone: a chat pinned to a workstream is visible wherever that workstream is the place's subject, and the entry or gate open on it is no part of the anchor."
  {:about [ui-side-chat ui-place] :evidence "test"})

(Claim visible-at-a-place
  "The chats a panel shows at a place are exactly those whose last anchor entry pins them to that place's anchor and those that follow the person, a closed chat nowhere; each is its own conversation, so a turn in one never appears in another's transcript."
  {:about [ui-side-chat session-chat] :evidence "round"})

(Claim a-chat-outlives-its-panel
  "A side chat's turn runs to its end whether or not a panel showing it is open, so a chat left mid-turn holds the reply when its place is next shown."
  {:about [ui-side-chat session-chat] :evidence "round"})

(Claim panel-follows-the-location
  "A page draws its panel with the chats visible at its own location's place, and every later change of the location — by the pane, by any other script, or by a step back or forward — is followed by the panel showing the chats visible at the new location's place as the server reads it from the request, the same set a page drawn at that location would show."
  {:about [ui-side-chat ui-views] :evidence "round"})

(Claim message-names-its-anchor
  "Every message a side chat sends carries, beside the place of its send request, the chat's anchor at the moment of the send: pinned to a named anchor, or following the person."
  {:about [ui-side-chat] :evidence "round"})

(Claim accordion-of-chats
  "The panel lists the chats visible at a place as full-width rows stacked one under another, those that follow the person first, then those pinned there, each newest first; exactly one is open — the one the person last had open — showing its transcript and composer beneath its row, and each row carries its mark and a pin control drawn in the panel's icon form: a pin on a pinned chat, which unlinks it, and a struck pin on one that follows, which pins it to the place its request is sent from, or is refused where that place is unknown. With none visible, the panel offers a composer whose first message starts a chat, as pinned-by-default says."
  {:about [ui-components-chat ui-side-chat] :evidence "round"})

(Claim away-mark-on-the-board
  "Every row listing a workstream, on the board and on Needs you, shows a mark when a chat pinned to that workstream ended a turn, or began waiting on a permission, after the person last had it open, and only then. A chat is had open while the panel is expanded on it as its open row with its stream live: the panel tells ui-side-chat when it expands or collapses onto its open chat, and ui-side-chat records when each turn ended and each permission request began."
  {:about [ui-views ui-components-row ui-side-chat] :evidence "round"})

(Claim no-side-chat-lands
  "Every side chat is a session-chat conversation, and session-chat starts every conversation with its agent denied the commands that push, move main or record a landing, whatever its permission mode, so no turn of a side chat does any of them."
  {:about [ui-side-chat session-chat] :evidence "round"})
