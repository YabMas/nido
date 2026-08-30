(ns canvas.tasks.nido-slack
  "Self-spec: `tasks.nido-slack` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-slack
  "Bb task entry points for Slack auth + write actions. The token is an"
  (Operation auth-set
    "bb nido:slack:auth:set — read a bot token from stdin, store in macOS Keychain."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation auth-check
    "bb nido:slack:auth:check — print whether the keychain has a token."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation react
    "bb nido:slack:react :channel <C> :ts <ts> [:name eyes]"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation reply
    "bb nido:slack:reply :channel <C> :thread-ts <ts> :text '...'"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
