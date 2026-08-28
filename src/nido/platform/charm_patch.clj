(ns nido.platform.charm-patch
  "Vendored workaround for a charm.clj 0.2.71 alt-screen RESIZE bug.

   Symptom: in alt-screen mode, resizing the terminal strands a frozen copy of
   the previous frame above the new one (ghost header/footer/rows). Root cause:
   charm's event loop handles a window-size message by calling
   `render/update-size!` (which only calls JLine `Display.resize`, dropping the
   diff cache) and then `render/render!` — with NO physical screen clear in
   between. The old rows remain in the alt-screen buffer. Startup is clean
   because entering alt-screen clears once; only resize hits the gap.

   Proven in nido.tui-spike-alt: clearing the physical screen and invalidating
   JLine's cache on each resize fixes it completely. charm exposes the exact
   tools (`render/clear-screen!`, `render/repaint!`), but the event loop that
   should call them is internal to `program/run`. So we:

     1. WRAP (not replace) `render/create-renderer` so every renderer charm
        builds is captured into `renderer-ref`. Wrapping the return value — vs
        reimplementing the constructor — means we assume only the stable
        contract 'create-renderer returns the renderer', and survive charm
        changing the renderer's internal shape.
     2. Expose `clear-on-resize!`, which the TUI's own window-size handler calls
        (it runs AFTER charm's update-size! and BEFORE its render! — exactly the
        missing step).

   DELETE THIS NAMESPACE once charm's `program/run` clears the screen on resize
   itself (a clear-screen! + repaint! between update-size! and render!). An
   upstream report is deferred for now; this carries us until then. `install!`
   is idempotent, so the TUI can call it on every run-once without re-wrapping."
  (:require
   [charm.render.core :as render]))

(def ^:private renderer-ref
  "Holds the most recently constructed charm renderer atom, captured by the
   create-renderer wrapper. nil until the first renderer is built."
  (atom nil))

(defonce ^:private installed? (atom false))

(defn install!
  "Idempotently wrap charm.render.core/create-renderer to capture each renderer
   it returns. Safe to call repeatedly (e.g. once per run-once) — only the first
   call wraps."
  []
  (when (compare-and-set! installed? false true)
    (alter-var-root
     #'render/create-renderer
     (fn [orig]
       (fn [& args]
         (let [r (apply orig args)]
           (reset! renderer-ref r)
           r))))))

(defn clear-on-resize!
  "Physically wipe the alt-screen and invalidate JLine's diff cache so the next
   render! repaints the full frame from home instead of stranding the previous
   frame. Call from the TUI's window-size handler. No-op until a renderer has
   been captured (i.e. before the first program/run)."
  []
  (when-let [r @renderer-ref]
    (render/clear-screen! r)
    (render/repaint! r)))
