(ns canvas.core.feed
  "Self-spec: `nido.core.feed`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-feed
  "The change feed: what each commit changed, in commit order, keyed by commit position,
   delivered to surfaces as pushed messages (SSE to the dashboard, a socket stream to
   the TUI). The asks it delivers as awaiting the person are the open operator
   asks.

   HIDES subscription, fan-out and resume-from-position, so a surface that reconnects continues from the last position
   it rendered."
  (Operation create
    "A feed with no subscribers."
    {:signature [:=> [:catn] :map]})
  (Operation publish!
    "Offer a commit's changes to every subscriber without blocking, cutting off one with no room."
    {:signature [:=> [:catn [:feed :map] [:changes [:sequential :map]]] :nil]})
  (Operation subscribe!
    "Subscribe from a position: what changed since, then each commit live."
    {:signature [:=> [:catn [:feed :map] [:reader :map] [:from :int] [:opts :map]] :map]})
  (Operation take!
    "The next message, or the cut-off with the position to resume from."
    {:signature [:=> [:catn [:sub :map] [:timeout-ms :int]] [:maybe :map]]})
  (Operation unsubscribe!
    "Stop delivering to a subscription."
    {:signature [:=> [:catn [:sub :map]] :nil]})
  (Operation session
    "A read session for one api connection: committed state through a read-only handle of its own, and subscriptions that catch up through it."
    {:signature [:=> [:catn [:feed :map] [:db-path :string]] :map]}))
