(ns canvas.claims.catalog-chat
  "The claims the catalog-chat design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.strata :refer [ui-blocks]]
            [canvas.integration.acp :refer [acp-client acp-transcript]]
            [canvas.session.chat :refer [session-chat session-preview]]
            [canvas.ui.surface :refer [ui-catalog ui-catalog-chat ui-catalog-scenes ui-components-chat ui-server]]))

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
  "The chat panel and its items render from a transcript value and the URLs their controls post to, and read nothing else. Every state the brief names (empty, the person's message, a streaming reply, a finished reply, a tool call collapsed and expanded, a permission request, an error) is a scene over a literal transcript."
  {:about [ui-components-chat ui-blocks] :evidence "round"})

(Claim chat-leads-the-catalog
  "The chat panel's group is the first in the catalog's registry, so the scene page's sidebar lists it first, and the catalog test covers the chat block's namespace, so it renders every one of the panel's scenes."
  {:about [ui-catalog-scenes ui-components-chat] :evidence "round"})

(Claim pick-is-stable
  "A picked element is named by its block, its scene and a path of child steps from the scene's frame. The same element gets the same path on every render of the same code, so a reference in the transcript still names it after a reload."
  {:about [ui-catalog] :evidence "round"})

(Claim select-outlines-and-attaches
  "In select mode the scene page outlines the element under the pointer, and selecting it attaches its reference (pick-is-stable) to the composer; the prompt the agent receives for the next message carries that reference with the person's words."
  {:about [ui-catalog ui-catalog-chat] :evidence "round"})

(Claim preview-is-the-sessions-code
  "Once the catalog has a design session, its frame shows that session's runtime checkout, served by a process started from it. Each agent turn ends with a sync of that checkout and a restart of the process, and then every open frame reloads from it. When the sync refuses or the process does not start, the frame stays on what it showed and every open panel says, under the turn, that the preview was not refreshed and why. The design session is brought up under the project's default profile, which asks for services, so the runtime checkout merges its working copy (runtime-checkout-merges-service-members). The daemon's own dashboard never loads a session's code."
  {:about [session-preview ui-catalog-chat] :evidence "round"})

(Claim streams-are-scoped-to-a-panel
  "The dashboard's only open response is a chat panel's stream. It patches the transcript items that changed, unsubscribes when the browser goes away, and every other response stays a finished body."
  {:about [ui-server ui-catalog-chat session-chat] :evidence "round"})

(Claim lands-checked
  "The change lands only on a tip where bb nido:test is green and bb nido:design:check reports ok."
  {:about [acp-client acp-transcript session-chat session-preview ui-components-chat ui-catalog-chat] :evidence "round"})
