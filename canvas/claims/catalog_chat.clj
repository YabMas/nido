(ns canvas.claims.catalog-chat
  "The claims the catalog-chat design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.integration.acp :refer [acp-transcript]]
            [canvas.session.chat :refer [session-chat session-preview]]
            [canvas.strata :refer [ui-blocks]]
            [canvas.ui.surface :refer [ui-catalog ui-catalog-chat ui-catalog-scenes ui-components-chat ui-server]]))

(Claim closed-is-just-the-catalog
  "With the chat closed, /_catalog shows the scene frame at the full page width and one launcher; no composer, prompt text, status row, picker or mark is on screen, and the scene page inside the frame carries none of them outside the chat block's own scenes, which render the panel as the catalog renders every block. The launcher or ⌘I (from the page or the frame) opens the panel, which slides in from the right and narrows the frame rather than covering it; below 1000px wide it overlays. Closing it removes the panel, ends select mode and removes every mark from the frame. The open state is in the page's URL, so a reload keeps it."
  {:about [ui-catalog ui-catalog-chat] :evidence "round"})

(Claim turn-state-from-send-to-end
  "From the fold of the person's message until the turn ends, the panel shows a status row, derived from the transcript alone, naming the phase: waiting before the agent's first update, thinking while a thought streams, running with the tool's title while a tool call is pending or in progress, writing while a reply streams, and working otherwise; while a permission request made in this turn, after the person's message, is unanswered, the phase is waiting for your answer whatever else holds, and one left from an earlier turn counts for nothing. It shows the time since the turn started, counted in the browser from the time the transcript carries, and it is a polite live region. A reply's text appears as its chunks arrive. Stop is offered for the whole turn and keeps what has arrived. With no turn running there is no status row."
  {:about [ui-components-chat acp-transcript ui-catalog-chat] :evidence "round"})

(Claim progressive-disclosure
  "A thought is shown open while it streams and collapsed once it is finished. A tool call is collapsed while it is pending, running or completed, and open when it has failed. A tool's output scrolls inside a bounded box rather than growing the log without limit."
  {:about [ui-components-chat] :evidence "round"})

(Claim picks-are-removable-context
  "In select mode, which stays on until the person turns it off, presses Esc or closes the chat, clicking an element of a scene toggles it in or out of the picks; the picker's own controls and the scene's frame are never picked. Each pick is a numbered chip in the composer with a control that removes it, and the same element carries the same number as a mark on the frame for as long as it is picked. Removing a chip unmarks its element. A send carries every pick to the agent, with the person's words, and the sent message shows them as numbered chips, including after a replay. A send clears the picks and their marks."
  {:about [ui-catalog ui-components-chat ui-catalog-chat acp-transcript] :evidence "round"})

(Claim one-acp-parse
  "ACP's update shapes are read in the transcript fold alone. Nothing above it names an ACP field, and the panel renders only transcript items."
  {:about [acp-transcript ui-components-chat] :evidence "round"})

(Claim transcript-is-derived
  "The only durable state of a chat is its ACP session id. Its transcript is rebuilt by load-and-replay whenever its process is gone, so a daemon restart costs a conversation nothing but its replay time."
  {:about [session-chat] :evidence "round"})

(Claim chat-never-lands
  "A chat's agent edits only the worktree of the session it was started on. It runs with permissions skipped, and the commands that push, fast-forward main or record a landing are denied it. Nothing the panel sends lands."
  {:about [session-chat] :evidence "round"})

(Claim panel-renders-a-value
  "The chat panel, its items, its status row and its chips render from a transcript or picks value and the URLs their controls post to, and read nothing else. Every state the findings name has a scene over a literal value: the empty panel, the person's message with and without picks, a turn waiting, thinking, running a tool and writing, a finished reply, reasoning open and collapsed, a tool call collapsed, expanded and failed, a permission request, an error, and the composer with picks."
  {:about [ui-components-chat ui-blocks] :evidence "round"})

(Claim chat-leads-the-catalog
  "The chat panel's group is the first in the catalog's registry, and the catalog test covers the chat block's namespace, so it renders every one of the panel's scenes."
  {:about [ui-catalog-scenes ui-components-chat] :evidence "round"})

(Claim pick-is-stable
  "A picked element is named by its block, its scene and a path of child steps from the scene's frame. The same element gets the same path on every render of the same code, so a mark is redrawn on the same element after the frame reloads."
  {:about [ui-catalog] :evidence "round"})

(Claim preview-is-the-sessions-code
  "Once the catalog has a design session, its frame shows that session's runtime checkout, served by a process started from it. Each agent turn ends with a sync of that checkout and a restart of the process, and then every open frame reloads from it. When the sync refuses or the process does not start, the frame stays on what it showed and the conversation says, under the turn, that the preview was not refreshed and why. The daemon's own dashboard never loads a session's code."
  {:about [session-preview ui-catalog-chat] :evidence "round"})

(Claim streams-are-scoped-to-a-panel
  "The dashboard's only open response is a chat panel's stream. It patches the transcript items, the status row and the busy signal that changed, unsubscribes when the browser goes away, and every other response stays a finished body."
  {:about [ui-server ui-catalog-chat session-chat] :evidence "round"})

(Claim survey-grounds-the-choices
  "docs/reference/chat-panel-survey.md records the surveyed patterns and implementations with their sources, and each of its conclusions names the sections and sources it rests on; every choice this design makes about placement, turn state, disclosure and picks is one of those conclusions."
  {:about [ui-components-chat ui-catalog] :evidence "round"})

(Claim lands-checked
  "The change lands only on a tip where bb nido:test is green and bb nido:design:check reports ok."
  {:about [acp-transcript session-chat ui-components-chat ui-catalog ui-catalog-chat] :evidence "round"})
