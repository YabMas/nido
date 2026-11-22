(ns canvas.core.api
  "Self-spec: `nido.core.api`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-api
  "How anything outside the daemon changes or reads nido: commands over a local socket,
   each carrying its sender's key and answered with the outcome committed under that key;
   reads of committed state.

   HIDES the wire format and the socket. CLIs, surfaces and agents' tools are its clients;
   none of them writes the store."
  (Operation serve!
    "Listen on a Unix socket and serve commands, reads and subscriptions, one virtual thread per connection."
    {:signature [:=> [:catn [:daemon :map]] :map]})
  (Operation close!
    "Stop listening and remove the socket."
    {:signature [:=> [:catn [:server :map]] :nil]})
  (Operation connect
    "Open a client connection to a daemon socket."
    {:signature [:=> [:catn [:socket :string]] :map]})
  (Operation request
    "Send one request and answer its reply."
    {:signature [:=> [:catn [:conn :map] [:req :map]] :any]})
  (Operation subscribe
    "Turn a connection into a feed subscription from a position."
    {:signature [:=> [:catn [:conn :map] [:from :int]] :nil]})
  (Operation next-message
    "The next message of a subscription stream, or nil once it ended."
    {:signature [:=> [:catn [:conn :map]] :any]})
  (Operation disconnect
    "Close a client connection."
    {:signature [:=> [:catn [:conn :map]] :nil]}))
