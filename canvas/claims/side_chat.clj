(ns canvas.claims.side-chat
  "The claims the side-chat design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.chat :refer [session-chat]]
            [canvas.ui.chat-host :refer [ui-chat-host]]
            [canvas.ui.side-chat :refer [ui-side-chat]]
            [canvas.ui.catalog-chat :refer [ui-catalog-chat]]
            [canvas.ui.surface :refer [ui-catalog ui-components-chat ui-server ui-views]]))

(Claim one-toggle-rides-the-edge
  "A side panel is opened and closed by one toggle, the first thing in its bar, at the panel's top-left. Closed, the panel shows that toggle and nothing else, so the toggle sits at the panel's left edge open or closed and moves with it. No other on-page control opens or closes it."
  {:about [ui-components-chat ui-catalog ui-views ui-side-chat] :evidence "round"})

(Claim one-way-to-serve-a-panel
  "Keeping a chat panel live — its stream, a send, a stop, a permission answer — is the chat host's alone. The catalog's chat and the dashboard's chat each name a host for their conversation and answer nothing of those four themselves."
  {:about [ui-chat-host ui-catalog-chat ui-side-chat] :evidence "round"})

(Claim chat-on-every-shell-page
  "Every page the server draws through the shell with the dashboard's context, other than the component catalog — Needs, Workstreams with or without a workstream selected, Operations and its subpages, Development, and the Domain, Functionality and Structure slices — carries the dashboard chat's side panel, which the server puts into that context. The catalog carries its own chat and not the dashboard's."
  {:about [ui-views ui-server ui-side-chat] :evidence "round"})

(Claim message-names-its-page
  "Every message the dashboard chat sends carries a reference naming the title and path of the page it was sent from, which the agent reads with the message."
  {:about [ui-side-chat] :evidence "round"})

(Claim open-follows-the-person
  "Whether the dashboard chat is open is written to a cookie whenever it changes, and the server renders the next page open or closed from that cookie, so the panel is already in that state when the page loads."
  {:about [ui-side-chat ui-server] :evidence "round"})

(Claim operator-not-editor
  "The dashboard chat's agent is told it operates nido through nido's own bb verbs: it reads freely, takes an action that changes something when the person asked for that action, proposes one of its own and waits rather than taking it, and does not edit nido's code. Like every chat, it is denied pushing and landing."
  {:about [ui-side-chat session-chat] :evidence "round"})

(Claim one-design-conversation
  "The catalog talks to one conversation, in nido's catalog-design session, whose agent is told to edit nido's dashboard code and that a finished turn refreshes the preview it is looking at."
  {:about [ui-catalog-chat session-chat] :evidence "round"})
