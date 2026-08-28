(ns canvas.platform.charm-patch
  "Self-spec: `nido.platform.charm-patch` — a vendored workaround for a charm.clj resize bug.

   Modelled because it is a real part of the floor's surface, and because a module whose whole
   reason to exist is 'delete me when upstream fixes this' is exactly the one that quietly
   outlives its cause. Declaring it puts the expiry in the design rather than in a comment."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]))

(Module platform-charm-patch
  "Wrap charm's renderer construction so the TUI can clear the alt-screen on resize."
  (Operation install!
    "Idempotently wrap create-renderer to capture each renderer charm builds."
    {:signature [:=> [:catn] :any]})
  (Operation clear-on-resize!
    "Wipe the alt-screen and invalidate JLine's diff cache, so the next render repaints from
     home instead of stranding the previous frame."
    {:signature [:=> [:catn] :any]}))
