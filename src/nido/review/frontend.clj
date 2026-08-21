(ns nido.review.frontend
  "Terminal driver for the review loop. Holds the report in an atom; `emit`
   folds each engine event into it and persists report.json. In a TTY it runs
   an ~8 fps render thread that owns stdout (spinner/elapsed animate
   independently of events — so a long stage never looks stuck). In a non-TTY
   it degrades to one narrated line per event."
  (:require
   [nido.review.report :as report]
   [nido.review.render :as render])
  (:import
   [java.time Instant]))

(defn plain?
  "True when output is not an interactive terminal, or forced via env."
  []
  (or (= "1" (System/getenv "NIDO_REVIEW_PLAIN"))
      (nil? (System/console))))

(defn emit-fn
  "Build the emit fn: fold event → atom, persist, and in plain mode print a
   narration line.

   Serialized, because emit is no longer called only from the engine's thread:
   the review stage fans out and each target reports as it finishes. `swap!`
   would survive that on its own, but `persist!` stages through one fixed
   `<path>.tmp` before its atomic rename, so two threads writing at once can
   interleave their bytes and rename a corrupt file over a good one. The lock
   also keeps plain-mode narration from tearing across lines.

   Folds the value `swap!` returned rather than re-dereferencing: the atom may
   already hold a newer report, and an event's own line should describe the
   report that event produced."
  [report-atom report-path clock plain?]
  (let [lock (Object.)]
    (fn [event]
      (locking lock
        (let [r (swap! report-atom report/apply-event event clock)]
          (report/persist! r report-path)
          (when plain?
            (when-let [line (render/plain-line r event)]
              (println line))))))))

;; ANSI helpers ------------------------------------------------------------
(def ^:private esc "")
(def ^:private hide-cursor (str esc "[?25l"))
(def ^:private show-cursor (str esc "[?25h"))
(defn- cursor-up [n] (str esc "[" n "A"))
(def ^:private clear-down (str esc "[0J"))

(defn- repaint!
  "Reprint the live block in place: move the cursor up the lines printed last
   frame, clear downward, print the new frame. Returns the new line count."
  [report ^Instant now last-lines]
  (let [s     (render/frame report now)
        lines (inc (count (re-seq #"\n" s)))]
    (print (str (when (pos? last-lines) (cursor-up last-lines))
                clear-down s "\n"))
    (flush)
    lines))

(defn- render-loop
  "Spin until `running?` is false, repainting at ~8 fps. Returns the final
   line count so the caller can position the final summary."
  [report-atom running? clock]
  (loop [last-lines 0]
    (let [n (repaint! @report-atom (clock) last-lines)]
      (if @running?
        (do (Thread/sleep 120) (recur n))
        n))))

(defn with-live-display
  "Run (f emit). In plain mode just provides emit (which narrates lines). In a
   TTY, spawns the render thread, hides the cursor, runs f, then stops the
   thread, repaints once more, shows the cursor, and prints the final summary.
   Always restores the cursor (finally). Pass :plain? to force a mode; omit it
   to auto-detect via the `plain?` fn. (Local is named `plain` so it never
   shadows the `plain?` namespace fn.)"
  [{:keys [report-atom report-path clock] :as opts
    :or   {clock #(Instant/now)}} f]
  (let [plain (if (contains? opts :plain?) (:plain? opts) (plain?))
        emit  (emit-fn report-atom report-path clock plain)]
    (if plain
      (let [v (f emit)]
        (println (render/final @report-atom))
        v)
      (let [running? (atom true)
            thread   (future (render-loop report-atom running? clock))]
        (print hide-cursor) (flush)
        (try
          (f emit)
          (finally
            (reset! running? false)
            ;; The render loop's last paint leaves the live frame on screen and
            ;; returns its line count; `final` re-renders that frame as its
            ;; static head, so cursor up over the live frame and clear it first
            ;; — otherwise the round block prints twice.
            (let [last-lines (try @thread (catch Exception _ 0))]
              (print (str (when (pos? (long last-lines)) (cursor-up last-lines))
                          clear-down show-cursor
                          (render/final @report-atom) "\n"))
              (flush))))))))
