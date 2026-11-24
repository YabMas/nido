(ns canvas.claims.chat-relay
  "The claims the chat-relay design commits nido to: the side chat lives outside the core, and only
   what its agent relays — with the person's images — reaches a conductor.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [coordinator-work person-says!]]
            [canvas.core.buffer :refer [core-buffer]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.core.executor-claude :refer [executor-claude]]
            [canvas.core.images :refer [core-images]]
            [canvas.core.turn :refer [core-turn]]
            [canvas.tasks.nido-workstream :refer [tell*]]))

(Claim a-relay-carries-images-by-digest
  "A relay names images by the digests its message's references gave the agent. tell copies each from the side chat's image store into the core's before it sends anything, and refuses, sending nothing, when the side chat keeps no image by a digest or the relay names images with no words. person-says! posts a relay's images to the conductor's chat on a message carrying its words, also when those words answer the ask awaiting the person. That chat message names at most four images, each by its digest alone: no event carries image bytes, and the core's image store holds only images a relay passed on, each kept only when its bytes hash to its digest."
  {:about [tell* coordinator-work core-domain core-images] :evidence "round"})

(Claim the-conductor-sees-relayed-images
  "A conductor turn that hears a chat message carrying images is given their digests in its spec, each once in order, and its first run is prompted with the brief followed by one image block per image, read from the core's image store at launch into one of the run's files, which the run's own command line redirects to its stdin; the runner launches it as any other run. A turn hearing no images is prompted as before."
  {:about [core-buffer executor-claude core-turn] :evidence "round"})

(Claim history-folds-unchanged
  "A :chat-posted carrying no images folds exactly as it did before images, adding no key to the chat entry or the inbox item, so the upgrade's rebuild leaves every recorded workstream's state as it was."
  {:about [core-domain] :evidence "round"})
