(ns nido.review.frontend
  "Terminal driver for the review loop. Holds the report in an atom; `emit`
   folds each engine event into it and persists report.json. In a TTY it runs
   an ~8 fps render thread that owns stdout (spinner/elapsed animate
   independently of events — so a long stage never looks stuck). In a non-TTY
   it degrades to one narrated line per event."
  (:require
   [babashka.process :as process]
   [clojure.string :as str]
   [nido.review.report :as report]
   [nido.review.render :as render])
  (:import
   [java.time Instant]))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  plain?
  "True when output is not an interactive terminal, or forced via env."
  []
  (or (= "1" (System/getenv "NIDO_REVIEW_PLAIN"))
      (nil? (System/console))))

(defn ^{:malli/schema [:=> [:cat :any :Path :any :boolean] :any]}
  emit-fn
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

;; Terminal geometry -------------------------------------------------------
;;
;; The live block is repainted by moving the cursor UP over the rows the last
;; frame occupied, so the frontend has to know how many rows that was — which
;; means knowing how wide and how tall the terminal is. Every fact about the
;; viewport lives in this section; `render` stays a pure projection to text and
;; knows nothing about the window it lands in.

(defn ^{:malli/schema [:=> [:cat] :map]}
  tty-size
  "The terminal's `{:cols n :rows n}`, or nil when there is no tty to ask.

   `stty` rather than JLine, which babashka does bundle: building a JLine system
   terminal writes its own capability probes (`ESC[6n` and friends) to the very
   stdout the live block owns, and they surface as garbage mid-frame. `stty`
   reads the kernel's window size and writes nothing.

   Reads `/dev/tty` rather than stdin, so a piped stdin still measures the
   terminal the block is being painted on."
  []
  (try
    (let [{:keys [exit out]} (process/shell {:out :string :err :string :continue true}
                                            "sh" "-c" "stty size < /dev/tty")]
      (when (zero? exit)
        (let [[rows cols] (keep parse-long (str/split (str/trim out) #"\s+"))]
          (when (and rows cols (pos? rows) (pos? cols))
            {:cols cols :rows rows}))))
    (catch Exception _ nil)))

(defn- truncate
  "`line` cut to fit a `cols`-wide terminal, marked where it was cut.

   Stops one column short of the edge on purpose. A line that fills the last
   column leaves the cursor there with a wrap pending rather than on the next
   row, and terminals differ on whether the newline that follows consumes that
   pending wrap or spends a fresh row. A column of clearance makes one printed
   line exactly one terminal row everywhere, which is the equality this whole
   section exists to hold."
  [line cols]
  (let [width (max 1 (dec cols))]
    (if (<= (count line) width)
      line
      (str (subs line 0 (max 0 (dec width))) "…"))))

(defn ^{:malli/schema [:=> [:cat :string :map] :string]}
  fit
  "`s` bounded to a `cols` x `rows` viewport, so that its line count is exactly
   the number of terminal rows it will occupy.

   That equality is the contract `repaint!` rests on, and nothing else enforces
   it: the repaint moves the cursor up by the line count of the frame it painted
   last, so a frame with a line that WRAPPED leaves a row it cannot reach, and a
   frame TALLER than the window leaves rows the cursor cannot climb back to at
   all (`ESC[nA` stops at the top of the screen). Either way the cursor lands
   inside the old block, the clear below it spares that block's head, and the
   next frame prints under its own remains — eight times a second, for the rest
   of the run.

   What survives a frame that is too tall: the first line, because it names the
   run, and the newest lines, because they are the ones still moving. What goes
   is the middle. The cut prefers a blank line, so a round is dropped whole
   rather than beheaded, and `unit` names what was dropped when it lands on one.

   Tabs are spent as spaces before anything is measured — a phase renders its
   error verbatim, tool output carries tabs, and a tab is one character of a
   count and up to eight columns of a row.

   `cols`/`rows` absent — no tty to measure — returns `s` untouched: with no
   viewport there is no bound to apply, and the unbounded frame is what this
   printed before."
  [s {:keys [cols rows unit] :or {unit "line"}}]
  (if-not (and cols rows)
    s
    (let [lines  (mapv #(truncate % cols)
                       (str/split-lines (str/replace s "\t" "    ")))
          ;; The block AND the row its trailing newline leaves the cursor on
          ;; both have to be on screen, or the next cursor-up starts from a row
          ;; that has already scrolled away.
          budget (max 1 (dec rows))]
      (cond
        (<= (count lines) budget)
        (str/join "\n" lines)

        ;; Too small to hold a head, a marker and anything worth reading: the
        ;; newest rows alone say more than a header over an elision notice.
        (< budget 4)
        (str/join "\n" (take-last budget lines))

        :else
        (let [floor   (- (count lines) (- budget 2))
              ;; The first blank line at or after the floor, so the kept tail
              ;; starts on a block boundary. None there means an unbroken run of
              ;; lines, and the floor stands.
              cut     (or (first (filter #(str/blank? (nth lines (dec %)))
                                         (range floor (count lines))))
                          floor)
              dropped (subvec lines 1 cut)
              blocks  (count (filter str/blank? dropped))
              [n u]   (if (pos? blocks) [blocks unit] [(count dropped) "line"])]
          (str/join "\n" (into [(first lines)
                                (truncate (str "  … " n " earlier " u
                                               (when (not= 1 n) "s"))
                                          cols)]
                               (subvec lines cut))))))))

;; ANSI helpers ------------------------------------------------------------
(def ^:private esc "")
(def ^:private hide-cursor (str esc "[?25l"))
(def ^:private show-cursor (str esc "[?25h"))
(defn- cursor-up [n] (str esc "[" n "A"))
(def ^:private clear-down (str esc "[0J"))

(defn- repaint!
  "Reprint the live block in place: move the cursor up the lines printed last
   frame, clear downward, print the new frame. Returns the new line count.

   The line count is a count of ROWS only because `fit` has already bounded `s`
   to the viewport. Print an unfitted frame through here and every line that
   wraps is a row this undercounts."
  [s last-lines]
  (let [lines (inc (count (re-seq #"\n" s)))]
    (print (str (when (pos? last-lines) (cursor-up last-lines))
                clear-down s "\n"))
    (flush)
    lines))

(def ^:private geom-poll-ms
  "The floor under `watch-resizes!` — how long a resize can go unnoticed if the
   signal never arrives. Slow, because SIGWINCH is what normally catches one and
   this only has to stop a missed signal from lasting the whole run."
  2000)

(defn- watch-resizes!
  "Set `flag` whenever the terminal is resized. Returns a fn that puts the
   previous handler back.

   Worth a signal rather than a poll alone: every frame painted at a width the
   terminal no longer has is a frame whose lines wrap and whose repaint
   therefore misses, so a poll interval is not a delay in noticing a resize, it
   is a run of broken frames — and dragging a window edge produces a resize
   every few milliseconds. SIGWINCH costs one measurement per actual resize and
   makes the window at most one frame wide.

   Not every platform delivers it and nothing here is load-bearing, so a failure
   to install is silent and the caller's poll stands as the floor."
  [flag]
  (try
    (let [sig  (sun.misc.Signal. "WINCH")
          prev (sun.misc.Signal/handle
                sig (reify sun.misc.SignalHandler
                      (handle [_ _] (reset! flag true))))]
      #(try (sun.misc.Signal/handle sig prev) (catch Throwable _ nil)))
    (catch Throwable _ (fn []))))

(defn- animate!
  "Spin until `running?` is false, repainting `(frame-fn now)` at ~8 fps.
   Returns the final line count so the caller can erase what it left on screen.

   Takes a frame FN rather than a report, because what is being watched is not
   always a review: the spinner and the elapsed clock have to keep moving while
   one stage blocks for minutes, and that is true of any pipeline. The frame is
   the only part that knows which one.

   Re-measures the terminal when `resized` says so and periodically regardless,
   because the window can be resized under a run that lasts an hour. A new
   geometry also RETIRES the row count: those rows were laid out at the old
   width and the terminal has reflowed them since, so no number reaches the old
   block's top any more. The frame therefore starts a new block below the old
   one — one stale copy per resize, which is the price of not writing over rows
   that can no longer be located."
  [frame-fn running? clock geom-fn unit resized]
  (loop [last-lines 0 geom nil measured-at 0]
    (let [now    (System/currentTimeMillis)
          due?   (or (compare-and-set! resized true false)
                     (>= (- now measured-at) geom-poll-ms))
          geom'  (if due? (geom-fn) geom)
          moved? (and (some? geom) (not= geom' geom))
          n      (repaint! (fit (frame-fn (clock)) (assoc geom' :unit unit))
                           (if moved? 0 last-lines))]
      (if @running?
        (do (Thread/sleep 120)
            (recur n geom' (if due? now measured-at)))
        n))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  with-live-frame
  "Animate `frame-fn` while `(f)` runs, then erase the live block and give the
   cursor back. Returns whatever `(f)` returned.

   The erase is why this owns the ANSI rather than leaving it to callers: the
   last painted frame is still on screen when the loop stops, and every caller
   wants to print something over it. Doing that means knowing how many rows the
   last frame occupied, which only the render loop knows.

   Plain mode animates nothing and erases nothing — there is no cursor to move
   and no frame to erase — so `(f)` simply runs. Pass :plain? to force a mode;
   omit it to auto-detect.

   `:elided-unit` names what one blank-line-separated block of the frame is, for
   the notice `fit` leaves when the frame outgrows the window. `:geom-fn` is the
   seam over `tty-size`, injected the way `:clock` is so a test can pin a
   viewport."
  [{:keys [frame-fn clock geom-fn elided-unit] :as opts
    :or   {clock #(Instant/now) geom-fn tty-size elided-unit "line"}} f]
  (let [plain (if (contains? opts :plain?) (:plain? opts) (plain?))]
    (if plain
      (f)
      (let [running? (atom true)
            resized  (atom false)
            unwatch  (watch-resizes! resized)
            thread   (future (animate! frame-fn running? clock geom-fn
                                       elided-unit resized))]
        (print hide-cursor) (flush)
        (try
          (f)
          (finally
            (reset! running? false)
            ;; The handler is process-global, so it goes back before anything
            ;; else — nothing after this run should still be hearing about
            ;; resizes on our behalf.
            (unwatch)
            (let [last-lines (try @thread (catch Exception _ 0))]
              (print (str (when (pos? (long last-lines)) (cursor-up last-lines))
                          clear-down show-cursor))
              (flush))))))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  with-live-display
  "Run (f emit) under the review report's live frame, then print the final
   summary over it. Pass :plain? to force a mode; omit it to auto-detect via the
   `plain?` fn. (Local is named `plain` so it never shadows the `plain?`
   namespace fn.)

   `final` re-renders the live frame as its own static head, which is exactly
   why with-live-frame erases the live one first — otherwise the round block
   prints twice.

   The summary prints from a `finally`, so a run that throws still leaves the
   report on screen: what a review found before it died is the most useful thing
   there is at that moment.

   `final` is printed WHOLE, never fitted. It is printed once over an erased
   screen and never repainted, so nothing depends on its height, and a review
   that ran fourteen rounds must still be able to report all fourteen.

   `:geom-fn` is passed through for tests; live it defaults to `tty-size`."
  [{:keys [report-atom report-path clock geom-fn] :as opts
    :or   {clock #(Instant/now) geom-fn tty-size}} f]
  (let [plain (if (contains? opts :plain?) (:plain? opts) (plain?))
        emit  (emit-fn report-atom report-path clock plain)]
    (try
      (with-live-frame {:frame-fn    #(render/frame @report-atom %)
                        :clock       clock
                        :plain?      plain
                        :geom-fn     geom-fn
                        ;; The review frame separates rounds with a blank line,
                        ;; so a dropped block is a dropped round.
                        :elided-unit "round"}
        #(f emit))
      (finally
        (println (render/final @report-atom))))))
