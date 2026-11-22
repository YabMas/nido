(ns canvas.claims.desktop-attention
  "The claims the desktop-attention design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.attention :refer [boot-attention]]
            [canvas.coordinator.daemon.brakes :refer [auto-tripped-triggers]]
            [canvas.coordinator.work :refer [all-gates owed-revivals]]
            [canvas.platform.desktop :refer [platform-desktop]]))

(Claim announced-from-the-inbox
  "Every condition the daemon announces is one a source reports: a gate from all-gates, a halt from the halt info, a tripped breaker from auto-tripped-triggers, a permission denial, a blocked session or a turn end on a workstream not activated from conduct's report, and a boot's casualties from owed-revivals. The daemon formats what a source reports and decides no condition of its own."
  {:about [boot-attention all-gates auto-tripped-triggers owed-revivals] :evidence "round"})

(Claim announced-on-arrival
  "An item is announced when its key appears and again only after it has left the set, and the first look after a start posts at most one notification, a count."
  {:about [boot-attention] :evidence "round"})

(Claim desktop-is-generic
  "The desktop module posts a title, subtitle, message and sound and knows nothing of gates, halts or breakers; it posts through the nido notifier app when that is installed and through osascript otherwise."
  {:about [platform-desktop] :evidence "round"})
