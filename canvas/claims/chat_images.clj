(ns canvas.claims.chat-images
  "The claims the chat-images design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.integration.acp :refer [acp-transcript Item prompt-blocks prompt! accepts-images?]]
            [canvas.session.chat :refer [send!]]
            [canvas.ui.chat-host :refer [ui-chat-host parse-image]]
            [canvas.ui.side-chat :refer [ui-side-chat]]
            [canvas.ui.catalog-chat :refer [ui-catalog-chat]]
            [canvas.ui.surface :refer [ui-components-chat]]))

(Claim one-door-for-image-bytes
  "parse-image is the only place bytes a browser uploads become an Image: it decides the media type from the bytes' signature, accepts only PNG, JPEG, GIF and WebP up to 5 MB decoded, and answers either an Image or a refusal naming why. The images a host hands send! are Images parse-image made."
  {:about [parse-image ui-chat-host] :evidence "round"})

(Claim staged-images-belong-to-the-conversation
  "A host's staged images are held per path prefix beside its streams, not in any page: every panel showing the conversation shows the same staged images, a stage or unstage reaches all of them, and a send takes and clears exactly the images staged when it was accepted."
  {:about [ui-chat-host ui-side-chat] :evidence "round"})

(Claim image-reaches-agent-as-image
  "send! prompts with the blocks prompt-blocks builds: the one text block for the message's words and references, then one ACP image block — base64 data and media type — per image, in order, in that one session/prompt; for a message without images that is the one text block alone."
  {:about [send! prompt-blocks prompt!] :evidence "round"})

(Claim images-only-to-an-agent-that-sees
  "send! sends images only to an agent whose initialize answer advertised promptCapabilities.image; to any other it ends the turn as an error item saying the agent does not accept images, and sends nothing."
  {:about [accepts-images? send!] :evidence "round"})

(Claim transcript-holds-a-message-s-images
  "A :user item carries :images, a vector of Images in order, empty when it has none. Folding the local :user event sets it to the images the message was sent with. Folding a replayed user_message_chunk whose content is of type image appends an Image of that chunk's data and media type to the user item a text chunk with the same messageId would join, starting a user item when there is none; that image does not pass through parse-image. The transcript writes no image to disk."
  {:about [acp-transcript Item] :evidence "round"})

(Claim log-stays-text-sized
  "The panel never inlines image bytes in markup: a transcript image and a staged image's chip are each an <img> whose src is the host's image route by digest, and the host answers that route from the transcript or its staged images with the image's media type and an immutable cache header. A stream's opening log therefore grows with the number of images, not their size."
  {:about [ui-components-chat ui-chat-host] :evidence "round"})

(Claim attaching-is-opt-in-per-panel
  "The composer offers attaching — a file picker, paste into the text box, drop onto the panel — and shows staged images as removable chips only when its routes carry :attach-url; the side chat's routes do, the catalog's do not, and a panel without it renders as before."
  {:about [ui-components-chat ui-side-chat ui-catalog-chat] :evidence "round"})

(Claim a-message-without-images-is-unchanged
  "A message sent without an image reaches the agent and renders in the transcript as it did before images: the same words and references, and no image markup; the nido.ui, nido.acp and nido.session.chat tests pass."
  {:about [send! acp-transcript ui-components-chat] :evidence "round"})

(Claim refusal-keeps-the-draft
  "A refused image — wrong type, too large, one too many — is said in the composer, beside the chips, and changes neither the draft's words nor the images already staged. The browser refuses by type and size before it uploads; the host refuses again on the bytes."
  {:about [ui-components-chat ui-chat-host] :evidence "round"})
