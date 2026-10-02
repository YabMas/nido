(ns canvas.boot.attention
  "Self-spec: `nido.boot.attention` — what wants a person now, and which of it is new."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]))

(Module boot-attention
  "The daemon's desktop notifications, as a pure diff.

   In Boot because its first input is the gate inbox, which is WorkPlane's, and its others are
   Daemon's brakes — only the composition root reaches both. Edge-triggered: an item is announced
   when its key appears, and again only after it has left the set, so a gate sitting for a day is
   one banner, and a session that resumes and parks again is a new one."
  (Operation items
    "Every gate, halt and auto-tripped breaker as {key notification}."
    {:signature [:=> [:catn [:inputs :map]] [:map-of :any :map]]})
  (Operation arrivals
    "The notifications for keys absent last time. The first look posts one summary instead, so a
     daemon restart never replays every open gate."
    {:signature [:=> [:catn [:seen [:maybe [:set :any]]] [:current :map]] [:vector :map]]})
  (Operation coalesce
    "At most a few notifications, the overflow folded into one count."
    {:signature [:=> [:catn [:ns [:vector :map]]] [:vector :map]]}))
