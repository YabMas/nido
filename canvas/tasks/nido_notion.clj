(ns canvas.tasks.nido-notion
  "Self-spec: `tasks.nido-notion` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-notion
  "Bb task entry points for Notion auth (Stage 5). Recommended token type is"
  (Operation auth-set
    "bb nido:notion:auth:set — read a token from stdin, store in macOS Keychain."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation auth-check
    "bb nido:notion:auth:check — print whether the keychain has a token."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
