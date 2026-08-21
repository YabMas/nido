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
                               :layers [{:label "drop-legacy" :index 1
                                         :status "reviewed" :findings 2}
                                        {:label "widen" :index 2 :status "skipped"}
                                        {:label "stack" :status "reviewed" :findings 0
                                         :stack? true}]}]}]}
        s (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z"))]
    (is (str/includes? s "2 layers") "the header says how wide the stack is")
    (is (str/includes? s "Layer 1 · drop-legacy"))
    (is (str/includes? s "Layer 2 · widen"))
    (is (str/includes? s "converged"))
    (is (str/includes? s "1 layer converged"))
    (is (str/includes? s "composition") "the whole-stack pass is named for what it is")))

(deftest a-converged-layer-keeps-its-place-in-the-stack
  ;; Rows used to arrive reviewed-first, skipped-after, so a layer appeared to
  ;; drop out of the stack in the round it stopped changing. Its number and its
  ;; position both say it is still there.
  (let [r {:target {:cwd "/x/feat/thing" :base "main" :layers 3 :files ["a"]}
           :rounds [{:round 2
                     :phases [{:phase "review" :status "running"
                               :started-at "2026-01-01T00:00:00Z"
                               :layers [{:label "one" :index 1 :status "skipped"}
                                        {:label "two" :index 2 :status "running"}
                                        {:label "three" :index 3 :status "skipped"}
                                        {:label "stack" :stack? true :status "running"}]}]}]}
        rows (->> (str/split-lines (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z")))
                  (keep #(second (re-find #"(Layer \d · \w+|All \d layers at once)" %))))]
    (is (= ["Layer 1 · one" "Layer 2 · two" "Layer 3 · three" "All 3 layers at once"]
           rows)
        "converged layers sit between the layers they sit between")))

(deftest the-composition-pass-is-set-apart-from-the-layers-it-spans
  (let [r {:target {:cwd "/x/feat/thing" :base "main" :layers 6 :files ["a"]}
           :rounds [{:round 1
                     :phases [{:phase "review" :status "running"
                               :started-at "2026-01-01T00:00:00Z"
                               :layers [{:label "one" :index 1 :status "running"}
                                        {:label "two" :index 2 :status "running"}
                                        {:label "stack" :stack? true :status "running"}]}]}]}
        lines (str/split-lines (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z")))
        rule  (first (keep-indexed (fn [i l] (when (str/includes? l "───") i)) lines))]
    (is rule "a rule separates it from the layers")
    (is (str/includes? (nth lines rule) "composition")
        "the rule says what the block below it is")
    (is (str/includes? (nth lines (inc rule)) "All 6 layers at once")
        "and it is named for the whole stack, not for the rows in this round")
    (is (not (str/includes? (nth lines (inc rule)) "Layer "))
        "the composition pass carries no layer number — it is not a layer")))

(deftest a-branch-with-no-layers-is-a-branch-and-not-a-composition
  ;; Below two layers there is nothing to compose, so the single target is the
  ;; whole branch — and a rule under nothing separates nothing.
  (let [r {:target {:cwd "/x/feat/thing" :base "main" :layers 0 :files ["a"]}
           :rounds [{:round 1
                     :phases [{:phase "review" :status "running"
                               :started-at "2026-01-01T00:00:00Z"
                               :layers [{:label "stack" :stack? true :status "running"}]}]}]}
        s (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z"))]
    (is (str/includes? s "Whole branch"))
    (is (not (str/includes? s "composition")))
    (is (not (str/includes? s "───")))))

(deftest the-name-column-cannot-be-widened-without-bound
  ;; repaint! counts the frame's newlines to know how far to move the cursor
  ;; back up, so a line that wraps is a row it does not know about and the frame
  ;; walks down the screen. One long slug must therefore not be able to drag
  ;; every other row across the terminal with it.
  (let [row      (fn [i l] {:label l :index i :status "reviewed" :findings 0})
        frame-of (fn [n]
                   (render/frame
                    {:target {:cwd "/x" :base "main" :layers 2 :files ["a"]}
                     :rounds [{:round 1
                               :phases [{:phase "review" :status "running"
                                         :started-at "2026-01-01T00:00:00Z"
                                         :layers [(row 1 "a")
                                                  (row 2 (apply str (repeat n "x")))]}]}]}
                    (java.time.Instant/parse "2026-01-01T00:00:10Z")))
        short-of (fn [n] (->> (str/split-lines (frame-of n))
                              (filter #(str/includes? % "Layer 1 ·"))
                              first))]
    (is (= (count (short-of 60)) (count (short-of 200)))
        "past the cap the neighbour's length stops mattering")
    (is (str/includes? (frame-of 200) "0 findings")
        "and the long row still says what it found")))

(deftest a-report-written-before-layers-were-numbered-still-renders
  ;; Rows with no :index, and a warden block whose by-layer is still the
  ;; label->count map. Neither may throw, and neither may invent a number.
  (let [r {:target {:cwd "/x/feat/thing" :base "main" :files ["a"]}
           :rounds [{:round 1
                     :phases [{:phase "review" :status "ok"
                               :started-at "2026-01-01T00:00:00Z" :findings []
                               :layers [{:label "lower" :status "reviewed" :findings 1}
                                        {:label "upper" :status "reviewed" :findings 0}]}
                              {:phase "warden" :status "ok"
                               :started-at "2026-01-01T00:00:00Z"
                               :dispositions [{}]
                               :by-layer {"lower" 1}}]}]}
        s (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z"))]
    (is (str/includes? s "lower"))
    (is (str/includes? s "upper"))
    (is (str/includes? s "1 to rule on") "the old by-layer map still renders")
    (is (not (str/includes? s "Layer ")) "nothing invents a number it does not have")))

(deftest the-warden-block-says-where-the-compositions-findings-went
  ;; They belong to no single layer by construction, so no warden rules on them
  ;; and they go straight to the arbiter. A row that merely appeared here would
  ;; read as a layer nobody ruled on.
  (let [r {:target {:cwd "/x/feat/thing" :base "main" :layers 2 :files ["a"]}
           :rounds [{:round 1
                     :phases [{:phase "warden" :status "ok"
                               :started-at "2026-01-01T00:00:00Z"
                               :dispositions [{}]
                               :by-layer [{:label "one" :index 1 :stack? false :count 1}
                                          {:label "stack" :stack? true :count 2}]}]}]}
        s (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z"))]
    (is (str/includes? s "Layer 1 · one        1 to rule on"))
    (is (str/includes? s "All 2 layers at once 2 to the arbiter"))))

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

(deftest final-says-what-kind-of-composition-defect-a-parked-finding-is
  ;; `park` alone cannot tell a reader whether an invariant is in question or
  ;; the cut is — and those ask very different things of them.
  (let [r {:target {:cwd "/x/feat/thing" :base "main"}
           :status "converged" :started-at "2026-01-01T00:00:00Z"
           :ended-at "2026-01-01T00:01:00Z"
           :rounds [{:round 1
                     :phases [{:phase "review" :status "ok" :started-at "2026-01-01T00:00:00Z"
                               :findings [{:priority 2 :title "t" :file "a.clj" :line-start 1
                                           :line-end 2 :disposition :park
                                           :kind :misplaced-seam
                                           :layers ["series" "banner"]
                                           :from-layer "stack" :owner-layer "banner"}]}]}]}
        s (render/final r)]
    (is (str/includes? s "misplaced-seam across series + banner"))
    (is (str/includes? s "→ park"))))

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
                       :layers [{:label "helpers" :index 1 :stack? false :status "pending"}
                                {:label "basis"   :index 2 :stack? false :status "skipped"}
                                {:label "stack"   :stack? true  :status "pending"}]}]}]
   :summary nil})

(deftest header-names-the-stack-before-any-review-finishes
  (let [s (render/frame mid-review-report now)]
    (is (str/includes? s "2 layers")
        "the layer count comes from the resolved target, not from a finished review")
    (is (str/includes? s "3 files"))))

(deftest a-running-review-lists-its-targets
  (let [s (render/frame mid-review-report now)]
    (is (str/includes? s "Layer 1 · helpers"))
    (is (str/includes? s "queued"))
    (is (str/includes? s "All 2 layers at once"))
    (is (str/includes? s "converged")
        "a target skipped by the cache says so from the start")))

(deftest a-completed-review-renders-exactly-as-before
  ;; The running-state display must not be bought with a regression in the
  ;; finished-state one — the rows a finished phase carries are its own.
  (let [done (assoc-in mid-review-report [:rounds 0 :phases 0]
                       {:phase "review" :status "ok"
                        :started-at "2026-06-30T14:00:00Z"
                        :ended-at "2026-06-30T14:00:30Z"
                        :findings []
                        :layers [{:label "helpers" :index 1 :stack? false
                                  :status "reviewed" :findings 2}]})
        s (render/frame done now)]
    (is (str/includes? s "2 findings"))
    (is (not (str/includes? s "queued")))))

(deftest a-running-target-spins-and-a-finished-one-reports
  (let [r (assoc-in mid-review-report [:rounds 0 :phases 0 :layers]
                    [{:label "helpers" :index 1 :stack? false :status "reviewed" :findings 2}
                     {:label "basis"   :index 2 :stack? false :status "running"}
                     {:label "stack"   :stack? true  :status "pending"}])
        s (render/frame r now)]
    (is (str/includes? s "reviewing …"))
    (is (str/includes? s "2 findings"))
    (is (str/includes? s "queued"))))

(deftest plain-mode-narrates-each-target-as-it-lands
  (is (= "round 1 · specs ✓ 5 findings"
         (render/plain-line {} {:event :target-moved :iter 1
                                :label "specs" :status "reviewed" :findings 5})))
  (is (nil? (render/plain-line {} {:event :target-moved :iter 1
                                   :label "specs" :status "running"}))
      "a start is not worth a log line"))
