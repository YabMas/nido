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
  [s last-lines]
  (let [lines (inc (count (re-seq #"\n" s)))]
    (print (str (when (pos? last-lines) (cursor-up last-lines))
                clear-down s "\n"))
    (flush)
    lines))

(defn- animate!
  "Spin until `running?` is false, repainting `(frame-fn now)` at ~8 fps.
   Returns the final line count so the caller can erase what it left on screen.

   Takes a frame FN rather than a report, because what is being watched is not
   always a review: the spinner and the elapsed clock have to keep moving while
   one stage blocks for minutes, and that is true of any pipeline. The frame is
   the only part that knows which one."
  [frame-fn running? clock]
  (loop [last-lines 0]
    (let [n (repaint! (frame-fn (clock)) last-lines)]
      (if @running?
        (do (Thread/sleep 120) (recur n))
        n))))

(defn with-live-frame
  "Animate `frame-fn` while `(f)` runs, then erase the live block and give the
   cursor back. Returns whatever `(f)` returned.

   The erase is why this owns the ANSI rather than leaving it to callers: the
   last painted frame is still on screen when the loop stops, and every caller
   wants to print something over it. Doing that means knowing how many rows the
   last frame occupied, which only the render loop knows.

   Plain mode animates nothing and erases nothing — there is no cursor to move
   and no frame to erase — so `(f)` simply runs. Pass :plain? to force a mode;
   omit it to auto-detect."
  [{:keys [frame-fn clock] :as opts :or {clock #(Instant/now)}} f]
  (let [plain (if (contains? opts :plain?) (:plain? opts) (plain?))]
    (if plain
      (f)
      (let [running? (atom true)
            thread   (future (animate! frame-fn running? clock))]
        (print hide-cursor) (flush)
        (try
          (f)
          (finally
            (reset! running? false)
            (let [last-lines (try @thread (catch Exception _ 0))]
              (print (str (when (pos? (long last-lines)) (cursor-up last-lines))
                          clear-down show-cursor))
              (flush))))))))

(defn with-live-display
  "Run (f emit) under the review report's live frame, then print the final
   summary over it. Pass :plain? to force a mode; omit it to auto-detect via the
   `plain?` fn. (Local is named `plain` so it never shadows the `plain?`
   namespace fn.)

   `final` re-renders the live frame as its own static head, which is exactly
   why with-live-frame erases the live one first — otherwise the round block
   prints twice.

   The summary prints from a `finally`, so a run that throws still leaves the
   report on screen: what a review found before it died is the most useful thing
   there is at that moment."
  [{:keys [report-atom report-path clock] :as opts
    :or   {clock #(Instant/now)}} f]
  (let [plain (if (contains? opts :plain?) (:plain? opts) (plain?))
        emit  (emit-fn report-atom report-path clock plain)]
    (try
      (with-live-frame {:frame-fn #(render/frame @report-atom %)
                        :clock clock :plain? plain}
        #(f emit))
      (finally
        (println (render/final @report-atom))))))
