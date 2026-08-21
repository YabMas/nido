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
                     :rulings [{:id "aa11" :disposition :fix}
                               {:id "bb22" :disposition :closed}]})
        s (render/frame r now)]
    (is (str/includes? s "continue"))
    (is (str/includes? s "fix 1") "only the findings actually handed to a fixer")))

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

(deftest frame-lists-every-layer-including-the-ones-it-skipped
  ;; A reader who cannot see that a layer was passed over has to take on trust
  ;; that passing over it was safe. Silent truncation reads as coverage.
  (let [r {:target {:cwd "/x/feat/thing" :base "main" :layers 2 :files ["a"]}
           :rounds [{:round 1
                     :phases [{:phase "review" :status "ok" :started-at "2026-01-01T00:00:00Z"
                               :findings [{} {}]
                               :layers [{:label "drop-legacy" :status "reviewed" :findings 2}
                                        {:label "widen" :status "skipped"}
                                        {:label "stack" :status "reviewed" :findings 0
                                         :stack? true}]}]}]}
        s (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z"))]
    (is (str/includes? s "2 layers") "the header says how wide the stack is")
    (is (str/includes? s "drop-legacy"))
    (is (str/includes? s "widen"))
    (is (str/includes? s "unchanged since it converged"))
    (is (str/includes? s "1 skipped"))
    (is (str/includes? s "composition") "the whole-stack pass is named for what it is")))

(deftest final-says-what-was-decided-about-each-finding
  (let [r {:target {:cwd "/x/feat/thing" :base "main"}
           :status "converged" :started-at "2026-01-01T00:00:00Z"
           :ended-at "2026-01-01T00:01:00Z"
           :rounds [{:round 1
                     :phases [{:phase "review" :status "ok" :started-at "2026-01-01T00:00:00Z"
                               :findings [{:priority 1 :title "t" :file "a.clj" :line-start 1
                                           :line-end 2 :disposition :closed :authority "out-of-scope"
                                           :from-layer "widen" :owner-layer "drop-legacy"}]}]}]}
        s (render/final r)]
    (is (str/includes? s "→ closed"))
    (is (str/includes? s "out-of-scope"))
    (is (str/includes? s "reported by widen") "attribution is visible when it moved")))

;; ---- what a run that has not finished reviewing yet shows ----------------

(def mid-review-report
  "The state the display is in for most of a real round: targets resolved, six
   agents in flight, nothing finished. Before targets-resolved this rendered as
   one bare `reviewing …` line for the whole multi-minute phase."
  {:schema 1 :run-id "review-1" :status "running"
   :target {:cwd "/w/feat/progress-ratio" :base "main" :base-rev "B"
            :files ["src/a.clj" "src/b.clj" "src/c.clj"] :layers 2}
   :started-at "2026-06-30T14:00:00Z" :ended-at nil
   :rounds [{:round 1 :status "running" :started-at "2026-06-30T14:00:00Z"
             :phases [{:phase "review" :status "running"
                       :started-at "2026-06-30T14:00:00Z" :ended-at nil
                       :layers [{:label "helpers" :stack? false :status "pending"}
                                {:label "basis"   :stack? false :status "skipped"}
                                {:label "stack"   :stack? true  :status "pending"}]}]}]
   :summary nil})

(deftest header-names-the-stack-before-any-review-finishes
  (let [s (render/frame mid-review-report now)]
    (is (str/includes? s "2 layers")
        "the layer count comes from the resolved target, not from a finished review")
    (is (str/includes? s "3 files"))))

(deftest a-running-review-lists-its-targets
  (let [s (render/frame mid-review-report now)]
    (is (str/includes? s "helpers"))
    (is (str/includes? s "queued"))
    (is (str/includes? s "stack (composition)"))
    (is (str/includes? s "unchanged since it converged")
        "a target skipped by the cache says so from the start")))

(deftest a-completed-review-renders-exactly-as-before
  ;; The running-state display must not be bought with a regression in the
  ;; finished-state one — the rows a finished phase carries are its own.
  (let [done (assoc-in mid-review-report [:rounds 0 :phases 0]
                       {:phase "review" :status "ok"
                        :started-at "2026-06-30T14:00:00Z"
                        :ended-at "2026-06-30T14:00:30Z"
                        :findings []
                        :layers [{:label "helpers" :stack? false
                                  :status "reviewed" :findings 2}]})
        s (render/frame done now)]
    (is (str/includes? s "2 findings"))
    (is (not (str/includes? s "queued")))))
