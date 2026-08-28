(ns nido.review.frontend-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clojure.string :as str]
   [babashka.fs :as fs]
   [nido.review.frontend :as frontend]
   [nido.review.report :as report]
   [nido.review.vterm :as vterm]))

(def clock (constantly (java.time.Instant/parse "2026-06-30T14:00:00Z")))

(deftest emit-fn-folds-and-persists
  (let [dir  (str (fs/create-temp-dir))
        path (str (fs/path dir "report.json"))
        a    (atom nil)
        emit (frontend/emit-fn a path clock false)]
    (emit {:event :run-started :run-id "r" :cwd "/w" :base "main" :at "2026-06-30T14:00:00Z"})
    (emit {:event :phase-started :iter 1 :phase :review :at "2026-06-30T14:00:01Z"})
    (is (= "running" (:status @a)))
    (is (= 1 (count (:rounds @a))))
    (is (fs/exists? path))
    (is (= "r" (:run-id (json/parse-string (slurp path) true))))))

(deftest with-live-display-plain-runs-and-returns
  ;; Plain mode (no render thread): runs f, returns its value, leaves a report.
  (let [dir  (str (fs/create-temp-dir))
        path (str (fs/path dir "report.json"))
        a    (atom nil)
        out  (with-out-str
               (let [v (frontend/with-live-display
                         {:report-atom a :report-path path :clock clock :plain? true}
                         (fn [emit]
                           (emit {:event :run-started :run-id "r" :cwd "/w" :base "main" :at "2026-06-30T14:00:00Z"})
                           (emit {:event :phase-started :iter 1 :phase :review :at "2026-06-30T14:00:01Z"})
                           (emit {:event :phase-finished :iter 1 :phase :review :at "2026-06-30T14:00:02Z"
                                  :ctx {:findings []}})
                           (emit {:event :run-finalized :status :clean :ctx {} :at "2026-06-30T14:00:03Z"})
                           :ok))]
                 (is (= :ok v))))]
    (is (= "clean" (:status @a)))
    (is (re-find #"review" out) "plain mode narrates phases to stdout")))

(deftest with-live-display-tty-overwrites-live-frame-before-final
  ;; Regression: the render loop's last paint leaves the live frame (header +
  ;; round block) on screen, and `render/final` re-renders that frame as its
  ;; static head. So before printing `final` the frontend must cursor up over
  ;; the live frame and clear downward — otherwise the round block prints twice
  ;; on a real terminal. (In captured output the ANSI clear can't erase text, so
  ;; we assert the cursor-up + clear-down + show-cursor sequence directly.)
  (let [dir  (str (fs/create-temp-dir))
        path (str (fs/path dir "report.json"))
        a    (atom nil)
        out  (with-out-str
               (frontend/with-live-display
                 {:report-atom a :report-path path :clock clock :plain? false}
                 (fn [emit]
                   (emit {:event :run-started :run-id "r" :cwd "/w" :base "main" :at "2026-06-30T14:00:00Z"})
                   (emit {:event :phase-started :iter 1 :phase :review :at "2026-06-30T14:00:01Z"})
                   (emit {:event :phase-finished :iter 1 :phase :review :at "2026-06-30T14:00:02Z"
                          :ctx {:findings []}})
                   (emit {:event :run-finalized :status :clean :ctx {} :at "2026-06-30T14:00:03Z"})
                   :ok)))]
    (is (= "clean" (:status @a)))
    ;; cursor-up (ESC[<n>A) → clear-down (ESC[0J) → show-cursor (ESC[?25h),
    ;; emitted contiguously right before the final block.
    (is (re-find #"\x1b\[\d+A\x1b\[0J\x1b\[\?25h" out)
        "final summary overwrites the live frame instead of appending below it")))

(deftest emit-survives-concurrent-callers
  ;; The review stage fans out and each target reports as it finishes, so emit
  ;; is called from several threads at once. persist! stages through one fixed
  ;; <path>.tmp, so an unserialized emit can rename a half-written file.
  (let [d (str (fs/create-temp-dir))]
    (let [path (str (fs/path d "report.json"))
          a    (atom (report/init {:run-id "r" :cwd "/w" :base "main"
                                   :started-at "t0"}))
          emit (frontend/emit-fn a path (constantly "t") false)]
      (emit {:event :phase-started :iter 1 :phase :review :at "t1"})
      (emit {:event :targets-resolved :iter 1 :at "t1a" :base-rev "B" :files []
             :targets (mapv (fn [i] {:label (str "l" i) :stack? false
                                     :status "pending"})
                            (range 24))})
      (->> (range 24)
           (mapv (fn [i]
                   (future (emit {:event :target-moved :iter 1 :at "t2"
                                  :label (str "l" i) :status "reviewed"
                                  :findings i}))))
           (run! deref))
      (let [rows (:layers (first (:phases (first (:rounds @a)))))]
        (is (= 24 (count rows)))
        (is (every? #(= "reviewed" (:status %)) rows)
            "every concurrent report landed"))
      (let [on-disk (json/parse-string (slurp path) true)]
        (is (= "running" (:status on-disk))
            "the persisted file is complete JSON, not a torn write")))))

;; ── The live block against a terminal that has a size ───────────────────────
;;
;; `repaint!` climbs back over the last frame by its LINE count. That number is
;; a count of rows only while every line fits the width and the whole frame fits
;; the height; when it is not, the cursor lands inside the old block, the clear
;; spares its head, and the next frame prints below its own remains — eight
;; times a second. `fit` is what keeps the two numbers equal.

(defn- rows-occupied
  "How many terminal rows `s` really takes at `cols` wide."
  [s cols]
  (reduce + (map #(max 1 (long (Math/ceil (/ (count %) (double cols)))))
                 (str/split-lines s))))

(deftest fit-makes-the-line-count-a-row-count
  ;; The one property everything else rests on, over the shapes that broke it:
  ;; a line wider than the window, a frame taller than it, and both at once.
  (doseq [[label s] [["wide line"  (str "head\n" (apply str (repeat 400 "x")) "\ntail")]
                     ["tall frame" (str/join "\n" (map #(str "line " %) (range 200)))]
                     ["both"       (str/join "\n" (cons (apply str (repeat 900 "y"))
                                                        (map #(str "line " %) (range 200))))]]
          [cols rows] [[80 24] [120 40] [203 55] [40 12]]]
    (let [out (frontend/fit s {:cols cols :rows rows})]
      (testing (str label " at " cols "x" rows)
        (is (= (count (str/split-lines out)) (rows-occupied out cols))
            "every line is one row — nothing wraps")
        (is (<= (count (str/split-lines out)) (dec rows))
            "the block and the row below it both fit the window")))))

(deftest fit-keeps-the-head-and-the-newest-rounds
  (let [frame (str "  review-loop · nido · base main\n"
                   (str/join "\n\n" (for [r (range 1 21)]
                                      (str "  Round " r "\n   ✓ reviewing\n   ✓ ruling"))))
        out   (frontend/fit frame {:cols 100 :rows 16 :unit "round"})
        lines (str/split-lines out)]
    (is (str/includes? (first lines) "review-loop") "the run is still named")
    (is (re-find #"… \d+ earlier rounds" (second lines))
        "what was dropped is counted in rounds, not in lines")
    (is (str/includes? out "Round 20") "the newest round survives")
    (is (not (str/includes? out "Round 1\n")) "the oldest rounds are the ones dropped")
    (is (every? #(not (str/starts-with? % "   ")) (take 2 lines))
        "the cut landed on a round boundary, not inside one")))

(deftest fit-without-a-viewport-changes-nothing
  ;; No tty to measure: there is no bound to apply, and the unfitted frame is
  ;; what this printed before there was one.
  (let [s "  a\n  b\n  c"]
    (is (= s (frontend/fit s {})))
    (is (= s (frontend/fit s {:cols 80})))
    (is (= s (frontend/fit s {:rows 24})))))

(deftest fit-spends-tabs-before-measuring
  ;; A phase renders its error verbatim and tool output carries tabs. A tab is
  ;; one character of a count and up to eight columns of a row.
  (let [out (frontend/fit "a\tb\tc\td\te\tf" {:cols 20 :rows 10})]
    (is (not (str/includes? out "\t")))
    (is (= 1 (count (str/split-lines out))))
    (is (<= (count out) 19))))

(deftest live-block-never-duplicates-itself
  ;; The bug, as the user meets it: a review that has run enough rounds to
  ;; outgrow the window, or a phase whose error is wider than it. Forty frames
  ;; is five seconds of the render loop.
  (doseq [[label frame]
          [["a frame taller than the window"
            (str "  review-loop · nido · base main\n"
                 (str/join "\n\n" (for [r (range 1 15)]
                                    (str "  Round " r "\n   ✓ reviewing\n   ✓ ruling\n   ✓ fixing"))))]
           ["a phase error wider than the window"
            (str "  review-loop · nido · base main\n"
                 "  Round 1\n"
                 "   ✗ fixing  " (apply str (repeat 1764 "e")))]]
          [cols rows] [[80 24] [120 40]]]
    (testing (str label " at " cols "x" rows)
      (let [out (frontend/fit frame {:cols cols :rows rows :unit "round"})
            t   (reduce (fn [t last-lines]
                          (vterm/feed t (with-out-str
                                          (@#'frontend/repaint! out last-lines))))
                        (vterm/make cols rows)
                        ;; after the first frame the count is stable
                        (cons 0 (repeat 39 (count (str/split-lines out)))))]
        (is (= 1 (vterm/occurrences t "review-loop"))
            "one live block on screen, not one per frame")
        (is (empty? (:scrollback t))
            "the block repaints in place instead of scrolling the window away")))))

(deftest live-block-duplicates-without-fit
  ;; The same drive, unfitted — the regression this guards against. Without
  ;; this the tests above could pass on a frame that never needed fitting.
  (let [frame (str "  review-loop · nido · base main\n"
                   (str/join "\n\n" (for [r (range 1 15)]
                                      (str "  Round " r "\n   ✓ reviewing\n   ✓ ruling\n   ✓ fixing"))))
        t     (reduce (fn [t last-lines]
                        (vterm/feed t (with-out-str
                                        (@#'frontend/repaint! frame last-lines))))
                      (vterm/make 120 24)
                      (cons 0 (repeat 39 (count (str/split-lines frame)))))]
    (is (< 1 (vterm/occurrences t "review-loop"))
        "unfitted, the block prints below its own remains — this is the bug")))

(deftest resize-is-heard-and-retires-the-row-count
  ;; The reported trigger. A window that has been narrowed must be re-measured
  ;; before the next frame — a frame fitted to a width the terminal no longer
  ;; has wraps, and a wrapped frame is the whole bug — and the rows already on
  ;; screen must be abandoned rather than climbed over, since the terminal has
  ;; reflowed them and no count reaches their top any more.
  (let [wide   (str/join "\n" (repeat 6 (apply str (repeat 100 "x"))))
        ;; What a line of `wide` looks like once fitted to the narrow window:
        ;; 59 columns of clearance, then the mark where it was cut.
        narrow (str (apply str (repeat 58 "x")) "…")
        sizes  (atom [{:cols 120 :rows 40} {:cols 60 :rows 40}])
        asked  (atom 0)
        geom   (fn []
                 (swap! asked inc)
                 (let [[now & later] @sizes]
                   (when (seq later) (swap! sizes subvec 1))
                   now))
        out    (with-out-str
                 (frontend/with-live-frame
                   {:frame-fn (constantly wide) :clock clock :plain? false
                    :geom-fn geom}
                   (fn []
                     ;; A frame at the first size, a resize, then long enough
                     ;; for the loop to hear it and paint at the second.
                     (Thread/sleep 200)
                     (sun.misc.Signal/raise (sun.misc.Signal. "WINCH"))
                     (loop [waited 0]
                       (when (and (or (< @asked 2)
                                      (not (str/includes? (str *out*) narrow)))
                                  (< waited 800))
                         (Thread/sleep 20)
                         (recur (+ waited 20)))))))]
    (is (<= 2 @asked)
        "the signal reached the render loop — this did not wait out the 2s floor")
    (is (str/includes? out narrow)
        "frames after the resize are fitted to the window the terminal now has")
    ;; The first frame at the new size starts a fresh block: the cursor-up that
    ;; precedes every other frame is absent, because those rows were laid out at
    ;; the old width and the terminal has reflowed them out from under us.
    (let [at     (str/index-of out narrow)
          before (subs out (max 0 (- at 40)) at)]
      (is (not (re-find #"\x1b\[\d+A" before))
          "no cursor-up over rows that can no longer be located"))))
