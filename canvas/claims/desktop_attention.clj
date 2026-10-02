(ns canvas.claims.desktop-attention
  "The claims the desktop-attention design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.attention :refer [boot-attention]]
            [canvas.boot.core :refer [boot-core tick!]]
            [canvas.coordinator.daemon.brakes :refer [auto-tripped-triggers]]
            [canvas.coordinator.work :refer [all-gates]]
            [canvas.platform.desktop :refer [platform-desktop]]))

(Claim attention-is-pushed
  "With the notifier armed, the daemon makes every gate, halt or auto-tripped breaker known on the desktop within one attention interval of first observing it, without a person asking: either in a notification of its own, or counted in an aggregate notification. Two cases aggregate: the first look after a start posts one count of everything then present instead of one notification per item, and a look whose arrivals exceed the burst limit posts the first few individually and folds the rest into one count."
  {:about [boot-core boot-attention] :evidence "round"})

(Claim announced-from-the-inbox
  "What the daemon announces is read from all-gates, the halt info and auto-tripped-triggers alone; it derives no reading of its own of what waits on a person."
  {:about [boot-attention all-gates auto-tripped-triggers] :evidence "round"})

(Claim announced-on-arrival
  "An item is announced when its key appears and again only after it has left the set, and the first look after a start posts at most one notification, a count."
  {:about [boot-attention] :evidence "round"})

(Claim armed-only-by-run
  "tick! posts nothing unless run! armed the notifier, so a test driving tick! posts nothing; and a failed look or post never throws out of tick! or makes it wait on the sender."
  {:about [boot-core tick!] :evidence "round"})

(Claim desktop-is-generic
  "The desktop module posts a title, subtitle, message and sound and knows nothing of gates, halts or breakers; it posts through the nido notifier app when that is installed and through osascript otherwise."
  {:about [platform-desktop] :evidence "round"})
