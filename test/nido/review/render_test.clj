(ns nido.review.render-test
  (:require
   [clojure.test :refer [deftest is]]
   [clojure.string :as str]
   [nido.review.render :as render]))

(def now (java.time.Instant/parse "2026-06-30T14:00:42Z"))

(def running-report
  {:schema 1 :run-id "review-1" :status "running"
   :target {:cwd "/w/feat/review-tui" :base "main" :base-rev "B"
            :files ["src/a.clj" "src/b.clj"]}
   :started-at "2026-06-30T14:00:00Z" :ended-at nil
   :rounds [{:round 1 :status "running" :started-at "2026-06-30T14:00:00Z"
             :phases [{:phase "review" :status "ok" :started-at "2026-06-30T14:00:00Z"
                       :ended-at "2026-06-30T14:00:30Z"
                       :overall-correctness "incorrect"
                       :findings [{:title "bug" :file "src/a.clj"
                                   :line-start 5 :line-end 6 :priority 1}]}
                      {:phase "arbiter" :status "running"
                       :started-at "2026-06-30T14:00:30Z"}]}]
   :summary nil})

(deftest frame-shows-round-and-phase-status
  (let [s (render/frame running-report now)]
    (is (str/includes? s "Round 1"))
    (is (str/includes? s "reviewing"))
    (is (str/includes? s "2 files"))
    (is (str/includes? s "1 finding"))      ; review done summary
    (is (str/includes? s "arbitrating"))
    (is (str/includes? s "00:12"))          ; arbiter elapsed: 14:00:42 - 14:00:30
    (is (str/includes? s "✓"))))            ; review ok glyph

(deftest frame-shows-arbiter-decision-when-done
  (let [r (assoc-in running-report [:rounds 0 :phases 1]
                    {:phase "arbiter" :status "ok" :decision "continue"
                     :fix-findings [0 2]})
        s (render/frame r now)]
    (is (str/includes? s "continue"))
    (is (str/includes? s "fix 0,2"))))

(deftest final-lists-findings-and-status
  (let [r (-> running-report
              (assoc :status "converged"
                     :summary {:rounds 1 :findings-fixed 1 :final-status "converged"}))
        s (render/final r)]
    (is (str/includes? s "converged"))
    (is (str/includes? s "src/a.clj:5"))
    (is (str/includes? s "bug"))
    (is (str/includes? s "report.json"))))

(deftest plain-line-narrates-transitions
  (is (str/includes?
       (render/plain-line running-report
                          {:event :phase-started :iter 1 :phase :review})
       "review"))
  (is (nil? (render/plain-line running-report {:event :run-started}))))
