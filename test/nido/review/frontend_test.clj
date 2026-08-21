(ns nido.review.frontend-test
  (:require
   [clojure.test :refer [deftest is]]
   [cheshire.core :as json]
   [babashka.fs :as fs]
   [nido.review.frontend :as frontend]
   [nido.review.report :as report]))

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
