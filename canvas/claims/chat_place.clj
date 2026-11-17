(ns canvas.claims.chat-place
  "The claims the chat-place design commits nido to: where the person is, as every dashboard chat's
   agent is told it, derived on the server when a message is sent.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.ui.place :refer [ui-place]]
            [canvas.ui.chat-host :refer [ui-chat-host]]
            [canvas.ui.side-chat :refer [ui-side-chat]]
            [canvas.ui.surface :refer [ui-view-state ui-views ui-server]]))

(Claim one-place-declaration
  "ui-place's table has exactly one entry for every surface a ViewState can name, saying what that surface's subject is or that it has none, and a place is read only through that table."
  {:about [ui-place ui-view-state] :evidence "round"})

(Claim place-is-derived-at-send
  "The place a side-chat message carries is derived on the server while the send is handled, from the location of the page the send came from as the request states it and from the work plane at that moment. That location, with the pane's writes to it, is the one thing held in the browser the place is read from; no posted signal and no value computed when the page was rendered enters it."
  {:about [ui-place ui-side-chat] :evidence "round"})

(Claim workstream-subject-named
  "On the Workstreams surface with a workstream selected, the place names that workstream by project and id with its title and stage, the gate open on it with its question when one is, and the ledger entry open in its pane when one is."
  {:about [ui-place] :evidence "round"})

(Claim deep-link-parses-to-selection
  "ViewState's parse reads a /workstreams/:project/:ws-id path as the Workstreams surface with that workstream selected, as it reads ?sel=, so a page reached by its deep link parses to the workstream it shows."
  {:about [ui-view-state] :evidence "round"})

(Claim pane-position-in-location
  "Whenever the workstream pane's reading position changes — an entry opened or closed, a stage, a round fold, the history — the page's location is replaced with the URL of that position, so the location parses through ViewState to what the pane has open."
  {:about [ui-views] :evidence "round"})

(Claim host-refs-read-the-request
  "A host computes a sent message's references by calling its :refs function with the send request, its parsed body attached, so a caller can read what the request itself states as well as what the client posted."
  {:about [ui-chat-host] :evidence "round"})

(Claim place-never-silent
  "Every message the side chat sends carries a place reference. A place is either known — a page, with its subject or none — or unknown, when the request states no location that can be read into a path and query; an unknown place's reference says the place is unknown, worded apart from a known page with no subject, instead of being left out."
  {:about [ui-side-chat ui-place] :evidence "round"})

(Claim operator-resolves-from-place
  "The operator is told that each message's <nido-context> block names the page and the subject open on it, that 'this', 'here' and 'the open one' mean that subject, and that when the block names no subject, or one that does not fit what was asked, it says what it cannot resolve instead of choosing one."
  {:about [ui-side-chat] :evidence "round"})

(Claim chat-pages-enumerated
  "A test lists every dashboard route whose page carries the side chat — each route, so pages that share one ViewState surface, such as the Operations pages, are listed one by one — and fails for any whose location parses to the catch-all surface ViewState gives a path it does not name, or to a surface ui-place's table does not declare."
  {:about [ui-place ui-server] :evidence "round"})

(Claim place-is-the-one-reading
  "ui-place is the dashboard's one reading of where the person is for a chat: a chat that tells its agent, or chooses its conversation by, where the person is reads it from ui-place, and no other module derives a page's subject for a chat."
  {:about [ui-place] :evidence "round"})
