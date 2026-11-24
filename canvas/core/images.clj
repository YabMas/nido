(ns canvas.core.images
  "Self-spec: `nido.core.images`, images kept by digest outside the core's events. Its claims are in
   canvas.claims.chat-relay."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-images
  "Images kept by digest under a root: the core's, holding the images a message to a conductor
   names — an event names an image by its digest alone, so the event log stays the size of its
   words — and the side chat's own, holding what a person attached until a relay passes it on.

   HIDES where and how the bytes are kept. A digest names its bytes: an image is kept only when its
   bytes hash to its digest, kept once however often, and never rewritten or deleted."
  (Operation put!
    "Keep an image under a root and answer its digest; bytes that do not hash to it are not kept,
     and the answer is {:refused :digest-mismatch :digest}."
    {:signature [:=> [:catn [:root :string] [:image :map]] [:or :string [:map [:refused :keyword] [:digest :any]]]]})
  (Operation fetch
    "The image kept under a root by its digest, with its media type, or nil."
    {:signature [:=> [:catn [:root :string] [:digest :string]] [:maybe :map]]}))
