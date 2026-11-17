(ns canvas.claims.chat-reset
  "The claims the chat-reset design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:refer-clojure :exclude [reset!])
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.chat :refer [session-chat reset! send!]]
            [canvas.ui.chat-host :refer [ui-chat-host changed-events handle!]]
            [canvas.ui.side-chat :refer [ui-side-chat]]
            [canvas.ui.catalog-chat :refer [ui-catalog-chat]]
            [canvas.ui.surface :refer [side]]))

(Claim reset-forgets-the-conversation
  "An accepted reset leaves the chat as one never started: no agent process held, no id file, an empty transcript that every subscriber is told of, and started? false. The next send starts a new ACP session and writes its id; no agent is started for the chat before that send. Notes and errors nido put in the transcript go with it, so every panel shows an empty log."
  {:about [reset!] :evidence "round"})

(Claim reset-refused-while-working
  "A reset is refused — nil, nothing changes — while a turn runs or while a send accepted before it has not yet started its turn; a reset and an agent start or load for the same chat never interleave."
  {:about [reset! send!] :evidence "round"})

(Claim old-process-cannot-touch-new
  "A conversation's agent process ending — closed by a reset, reaped, or exiting on its own — changes the chat's state only while that process is still the chat's current one, so the end of an old process never drops or errors a new conversation's connection or turn."
  {:about [session-chat] :evidence "round"})

(Claim every-panel-answers-reset
  "A POST reset under a host's prefix is answered by the host for every conversation it serves, through session-chat's reset; side-chat and catalog-chat answer none of the panel's five requests — stream, send, cancel, reset, permission — themselves."
  {:about [handle! ui-side-chat ui-catalog-chat] :evidence "round"})

(Claim stream-follows-a-shrinking-transcript
  "When a transcript has fewer items than the one a stream last sent, the stream re-renders the whole log, status row and busy signal; otherwise it patches only what changed at each position. So every open panel on a reset conversation shows an empty log without reloading."
  {:about [changed-events] :evidence "round"})

(Claim new-conversation-is-confirmed-and-withheld-while-busy
  "A side panel whose routes carry :reset-url shows a 'New conversation' control in its bar; choosing it asks in place, saying the conversation cannot be brought back, before anything posts, and the control and its confirm are hidden while the panel's busy signal is set. A panel without :reset-url shows none, and the block still reads nothing but its arguments."
  {:about [side] :evidence "round"})

(Claim checks-pass-when-landed
  "The change is on main only with bb nido:test, bb nido:design:check and bb nido:land:check passing at the commit that put it there."
  {:about [session-chat ui-chat-host side] :evidence "round"})
