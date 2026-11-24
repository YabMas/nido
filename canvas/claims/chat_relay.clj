(ns canvas.claims.chat-relay
  "The claims the chat-relay design commits nido to: a workstream's side chat is an ACP agent
   outside the core, and only what it relays — with the person's images — reaches the conductor.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [coordinator-work person-says! chat-dir]]
            [canvas.core.buffer :refer [core-buffer]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.core.executor-claude :refer [executor-claude]]
            [canvas.core.images :refer [core-images]]
            [canvas.core.turn :refer [core-turn]]
            [canvas.session.chat :refer [session-chat]]
            [canvas.tasks.nido-workstream :refer [tell*]]
            [canvas.ui.side-chat :refer [ui-side-chat]]
            [canvas.ui.surface :refer [ui-components-chat]]))

(Claim one-side-chat-machine
  "There is one side chat. The operator's and a workstream's share one machinery — a session-chat conversation served through a chat host, whose send carries the page's place and names each image to the agent by digest — and differ only in what their context supplies: the agent's instructions, where it runs, and the target it relays to with its view of that target. A workstream's target is its conductor, viewed as [relayed-images-render-from-the-core] says; the operator relays to nothing and shows nothing beside its conversation."
  {:about [ui-side-chat session-chat] :evidence "round"})

(Claim side-chat-is-an-acp-agent
  "A workstream's side chat is a session-chat conversation the dashboard holds, as it holds the operator's, carrying chat-dir as its :dir: its agent runs in the directory chat-dir names when that agent starts, is told to answer the person about the workstream from its ledger and code and to change nothing, and receives each message's words, the page's place in its <nido-context> block, and its images as image blocks. When chat-dir names no directory, that start fails as an error on the conversation, and no agent starts and no session is brought up."
  {:about [ui-side-chat session-chat chat-dir] :evidence "round"})

(Claim only-relays-reach-the-core
  "Sending a message in a workstream's side chat sends no command to the core and writes nothing the core reads: the message goes only to the chat's agent. Its words reach the core only through a command that agent runs, and the agent is told to pass the person's word on only by tell, which sends it through person-says! with the grants the person gave and the images it names [a-relay-carries-images-by-digest]. So a message its agent passes on by no command enters neither the core's events nor any conductor turn."
  {:about [ui-side-chat tell* person-says!] :evidence "round"})

(Claim a-relay-carries-images-by-digest
  "A relay names images by the digests its message's references gave the agent. tell copies each from the side chat's image store into the core's before it sends anything, and refuses, sending nothing, when the side chat keeps no image by a digest or the relay names images with no words. person-says! posts a relay's images to the conductor's chat on a message carrying its words, also when those words answer the ask awaiting the person. That chat message names at most four images, each by its digest alone: no event carries image bytes, and the core's image store holds only images a relay passed on, each kept only when its bytes hash to its digest."
  {:about [tell* coordinator-work core-domain core-images] :evidence "round"})

(Claim the-conductor-sees-relayed-images
  "A conductor turn that hears a chat message carrying images is given their digests in its spec, each once in order, and its first run is prompted with the brief followed by one image block per image, read from the core's image store at launch into one of the run's files, which the run's own command line redirects to its stdin; the runner launches it as any other run. A turn hearing no images is prompted as before."
  {:about [core-buffer executor-claude core-turn] :evidence "round"})

(Claim relayed-images-render-from-the-core
  "A workstream's side-chat transcript shows, beside its conversation, every message the conductor received in the workstream's chat with its images, and every conductor reply. Its image route answers a digest the panel has staged, the conversation holds, or that workstream's core chat names, the last from the core's image store; any other digest is not found."
  {:about [ui-side-chat ui-components-chat coordinator-work core-images] :evidence "round"})

(Claim history-folds-unchanged
  "A :chat-posted carrying no images folds exactly as it did before images, adding no key to the chat entry or the inbox item, so the upgrade's rebuild leaves every recorded workstream's state as it was."
  {:about [core-domain] :evidence "round"})

