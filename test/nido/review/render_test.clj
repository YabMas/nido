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
                      {:phase "warden" :status "running"
                       :started-at "2026-06-30T14:00:30Z"}]}]
   :summary nil})

(deftest frame-shows-round-and-phase-status
  (let [s (render/frame running-report now)]
    (is (str/includes? s "Round 1"))
    (is (str/includes? s "reviewing"))
    (is (str/includes? s "2 files"))
    (is (str/includes? s "1 finding"))      ; review done summary
    (is (str/includes? s "ruling"))
    (is (str/includes? s "00:12"))          ; warden elapsed: 14:00:42 - 14:00:30
    (is (str/includes? s "✓"))))            ; review ok glyph

(deftest frame-shows-warden-decision-when-done
  (let [r (assoc-in running-report [:rounds 0 :phases 1]
                    {:phase "warden" :status "ok" :decision "continue"
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
    (is (str/includes? s "Composition") "the whole-stack pass is named for what it is")))

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
                  (keep #(second (re-find #"(Layer \d · \w+|Composition)" %))))]
    (is (= ["Layer 1 · one" "Layer 2 · two" "Layer 3 · three" "Composition"]
           rows)
        "converged layers sit between the layers they sit between")))

(deftest the-composition-pass-is-the-last-row-and-carries-no-layer-number
  ;; It sat in the list looking like a layer whose name happened to be `stack`.
  ;; The name is what sets it apart now — a captioned rule over a single row was
  ;; more furniture than the distinction needed.
  (let [r {:target {:cwd "/x/feat/thing" :base "main" :layers 6 :files ["a"]}
           :rounds [{:round 1
                     :phases [{:phase "review" :status "running"
                               :started-at "2026-01-01T00:00:00Z"
                               :layers [{:label "one" :index 1 :status "running"}
                                        {:label "two" :index 2 :status "running"}
                                        {:label "stack" :stack? true :status "running"}]}]}]}
        lines (str/split-lines (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z")))
        comp  (first (filter #(str/includes? % "Composition") lines))]
    (is comp)
    (is (= comp (last lines)) "it comes after every layer, and nothing after it")
    (is (not (str/includes? comp "Layer "))
        "the composition pass carries no layer number — it is not a layer")
    (is (not-any? #(str/includes? % "───") lines) "no rule, no section heading")))

(deftest a-branch-with-no-layers-is-a-branch-and-not-a-composition
  ;; Below two layers there is nothing to compose, so the single target is the
  ;; whole branch.
  (let [r {:target {:cwd "/x/feat/thing" :base "main" :layers 0 :files ["a"]}
           :rounds [{:round 1
                     :phases [{:phase "review" :status "running"
                               :started-at "2026-01-01T00:00:00Z"
                               :layers [{:label "stack" :stack? true :status "running"}]}]}]}
        s (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z"))]
    (is (str/includes? s "Whole branch"))
    (is (not (str/includes? s "Composition")))))

(deftest the-name-column-cannot-be-widened-without-bound
  ;; `frontend/fit` cuts every line to the terminal's width, so width overspent
  ;; on the name column is text lost off the right of the row. One long slug
  ;; must not be able to drag every other row across the terminal with it and
  ;; spend that budget for all of them.
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
  ;; Rows with no :index. It may not throw, and it may not invent a number.
  (let [r {:target {:cwd "/x/feat/thing" :base "main" :files ["a"]}
           :rounds [{:round 1
                     :phases [{:phase "review" :status "ok"
                               :started-at "2026-01-01T00:00:00Z" :findings []
                               :layers [{:label "lower" :status "reviewed" :findings 1}
                                        {:label "upper" :status "reviewed" :findings 0}]}]}]}
        s (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z"))]
    (is (str/includes? s "lower"))
    (is (str/includes? s "upper"))
    (is (not (str/includes? s "Layer ")) "nothing invents a number it does not have")))

(deftest a-report-carrying-the-removed-per-layer-warden-phase-still-renders
  ;; The stage is gone, but reports written while it existed are still on disk —
  ;; and "warden" now names the decision phase that replaced it. A legacy row
  ;; lands on that branch, finds no :decision, and renders as a bare line rather
  ;; than throwing or inventing a summary for a pass that no longer runs.
  (let [r {:target {:cwd "/x/feat/thing" :base "main" :layers 2 :files ["a"]}
           :rounds [{:round 1
                     :phases [{:phase "warden" :status "ok"
                               :started-at "2026-01-01T00:00:00Z"
                               :dispositions [{}]
                               :by-layer [{:label "one" :index 1 :stack? false :count 1}]}]}]}
        s (render/frame r (java.time.Instant/parse "2026-01-01T00:00:10Z"))]
    (is (str/includes? s "ruling"))
    (is (not (str/includes? s "to rule on")) "no per-layer rows survive")
    (is (not (str/includes? s "disposition")) "and no summary is invented for it")))

(defn- report-of
  "A finished report in the shape the event fold actually produces: a review
   phase holding the findings as the REVIEW stage left them — no dispositions,
   because the warden has not run when that phase folds — and a warden phase
   holding what was decided, separately, as rulings.

   Every test below builds through this. One that embeds a disposition in a
   review finding is testing a shape the fold never emits, which is how a
   closing block that showed no fate at all passed a test asserting it did."
  [findings rulings]
  {:target {:cwd "/x/feat/thing" :base "main"}
   :status "converged" :started-at "2026-01-01T00:00:00Z"
   :ended-at "2026-01-01T00:01:00Z"
   :rounds [{:round 1
             :phases [{:phase "review" :status "ok" :started-at "2026-01-01T00:00:00Z"
                       :findings findings}
                      {:phase "warden" :status "ok" :started-at "2026-01-01T00:00:30Z"
                       :decision "stop" :rulings rulings}]}]})

(deftest final-says-what-was-decided-about-each-finding
  (let [s (render/final
           (report-of [{:id "aa11" :priority 1 :title "t" :file "a.clj"
                        :line-start 1 :line-end 2 :from-layer "widen"}]
                      [{:id "aa11" :disposition :closed :authority "out-of-scope"
                        :owner-layer "drop-legacy"}]))]
    (is (str/includes? s "→ closed"))
    (is (str/includes? s "out-of-scope"))
    (is (str/includes? s "reported by widen") "attribution is visible when it moved")))

(deftest final-says-what-kind-of-composition-defect-a-parked-finding-is
  ;; `park` alone cannot tell a reader whether an invariant is in question or
  ;; the cut is — and those ask very different things of them.
  (let [s (render/final
           (report-of [{:id "bb22" :priority 2 :title "t" :file "a.clj"
                        :line-start 1 :line-end 2 :kind :misplaced-seam
                        :layers ["series" "banner"] :from-layer "stack"}]
                      [{:id "bb22" :disposition :park :owner-layer "banner"}]))]
    (is (str/includes? s "misplaced-seam across series + banner"))
    (is (str/includes? s "→ park"))))

(deftest final-tallies-what-became-of-the-findings
  ;; The status says how the run ended; this says what it did. Holding one
  ;; finding out of thirty and holding thirty read identically without it.
  (let [s (render/final
           (report-of [{:id "a" :priority 1 :title "one" :file "a.clj" :line-start 1}
                       {:id "b" :priority 1 :title "two" :file "b.clj" :line-start 1}
                       {:id "c" :priority 2 :title "three" :file "c.clj" :line-start 1}]
                      [{:id "a" :disposition :fix}
                       {:id "b" :disposition :fix}
                       {:id "c" :disposition :park}]))]
    (is (str/includes? s "2 fix, 1 park") "commonest first")))

(deftest final-shows-no-fate-when-no-warden-ruled
  ;; A run that died before its first warden has nothing to say about fates, and
  ;; must not invent one.
  (let [s (render/final
           (report-of [{:id "a" :priority 1 :title "one" :file "a.clj" :line-start 1}] []))]
    (is (str/includes? s "one"))
    (is (not (str/includes? s "\n      → ")) "no fate line without a ruling")))

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
    (is (str/includes? s "Composition"))
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

;; ── A loop over a ledger record ─────────────────────────────────────────────

(def ^:private now-10s (java.time.Instant/parse "2026-01-01T00:00:10Z"))

(def ^:private record-report
  {:status "running"
   :started-at "2026-01-01T00:00:00Z"
   :rounds [{:round 1 :status "ended" :started-at "2026-01-01T00:00:00Z"
             :phases [{:phase "judge" :status "ok" :started-at "2026-01-01T00:00:00Z"
                       :ended-at "2026-01-01T00:00:04Z"
                       :verdict "falsified"
                       :findings [{:cites ["a"] :claim "x"} {:cites ["b"] :claim "y"}]}
                      {:phase "amend" :status "running"
                       :started-at "2026-01-01T00:00:04Z"}]}]})

(deftest a-running-record-phase-shows-that-it-is-working
  (let [s (render/record-frame record-report now-10s {:title "baseline loop · ws-1"})]
    (is (str/includes? s "baseline loop · ws-1"))
    (is (re-find #"amend\s+00:06" s) "the elapsed clock is what says it is not stuck")
    (is (re-find #"[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏] amend" s))))

(deftest a-finished-judge-shows-its-verdict-and-how-much-it-found
  (let [s (render/record-frame record-report now-10s {:title "t"})]
    (is (re-find #"✓ judge\s+falsified · 2 findings" s))))

(deftest an-amendment-says-where-it-gave-something-up
  (let [r (assoc-in record-report [:rounds 0 :phases 1]
                    {:phase "amend" :status "ok" :started-at "2026-01-01T00:00:04Z"
                     :ended-at "2026-01-01T00:00:08Z" :amended? true
                     :retreats [{:what :health-dropped :detail "h1 is no longer recorded"}]})
        s (render/record-frame r now-10s {:title "t"})]
    (is (re-find #"✓ amend\s+amended · 1 weakening" s))))

(deftest a-round-that-only-re-surveyed-does-not-claim-to-have-amended
  ;; The same class of lie as a ✓ on a judge that never ran. Seen live: a design
  ;; round whose re-survey failed outright still reported "amended".
  (let [r (assoc-in record-report [:rounds 0 :phases 1]
                    {:phase "amend" :status "ok" :started-at "2026-01-01T00:00:04Z"
                     :ended-at "2026-01-01T00:00:08Z" :resurveyed "amend-invalid"})
        s (render/record-frame r now-10s {:title "t"})]
    (is (re-find #"✓ amend\s+re-surveyed amend-invalid" s))
    (is (not (str/includes? s "amended")))))

(deftest a-round-that-did-nothing-at-all-says-so
  (let [r (assoc-in record-report [:rounds 0 :phases 1]
                    {:phase "amend" :status "ok" :started-at "2026-01-01T00:00:04Z"
                     :ended-at "2026-01-01T00:00:08Z"})
        s (render/record-frame r now-10s {:title "t"})]
    (is (re-find #"✓ amend\s+nothing to amend" s))))

(deftest a-judge-that-could-not-run-never-renders-like-a-clean-one
  ;; A ✓ with nothing after it reads as "judged, found nothing". For a round that
  ;; never reached a judgment that is the one wrong reading available.
  (let [r (assoc-in record-report [:rounds 0 :phases 0]
                    {:phase "judge" :status "ok" :started-at "2026-01-01T00:00:00Z"
                     :ended-at "2026-01-01T00:00:02Z" :outcome "codex-failed"})
        s (render/record-frame r now-10s {:title "t"})]
    (is (re-find #"judge\s+codex-failed — no judgment" s))))

(deftest a-run-that-never-amended-does-not-claim-it-gave-nothing-up
  ;; Same distinction the section exists for, applied to itself: not reaching an
  ;; amendment is not the same as declining to weaken the record.
  (let [r {:status "no-workstream" :started-at "2026-01-01T00:00:00Z"
           :ended-at "2026-01-01T00:00:01Z"
           :rounds [{:round 1 :status "ended" :started-at "2026-01-01T00:00:00Z"
                     :phases [{:phase "judge" :status "ok"
                               :started-at "2026-01-01T00:00:00Z"
                               :outcome "no-workstream"}]}]}
        s (render/record-final r {:title "t"})]
    (is (str/includes? s "(no amendment ran — nothing here was even attempted)"))
    (is (not (str/includes? s "claims everything it claimed at the start")))))

(deftest no-frame-line-carries-trailing-whitespace
  (let [s (render/record-frame record-report now-10s {:title "t"})]
    (is (every? #(= % (str/trimr %)) (str/split-lines s)))))

(deftest the-final-block-never-leaves-nothing-given-up-implied
  ;; The distinction a vanishing section destroys: "nothing was weakened" and
  ;; "nobody looked" have to read differently.
  (let [r (-> record-report (assoc :status "accurate" :ended-at "2026-01-01T00:00:10Z"))
        s (render/record-final r {:title "t"})]
    (is (str/includes? s "Weakened:"))
    (is (str/includes? s "(nothing — the record claims everything it claimed at the start)"))
    (is (str/includes? s "Status: accurate"))))

(deftest the-final-block-lists-every-weakening-across-every-round
  (let [r (-> record-report
              (assoc :status "retreated" :ended-at "2026-01-01T00:00:10Z")
              (assoc-in [:rounds 0 :phases 1]
                        {:phase "amend" :status "ok" :started-at "2026-01-01T00:00:04Z"
                         :retreats [{:what :health-dropped :detail "h1 gone"}
                                    {:what :veto-lifted :detail "h2 unmarked"}]}))
        s (render/record-final r {:title "t"})]
    (is (str/includes? s "! health-dropped — h1 gone"))
    (is (str/includes? s "! veto-lifted — h2 unmarked"))))

(deftest a-long-title-is-capped-here-not-cut-by-the-terminal
  ;; `frontend/fit` would cut it at the window's edge, and a workstream id
  ;; ending in `…` names nothing. Capping it here spends the width deliberately.
  (let [s (render/record-frame record-report now-10s
                               {:title (apply str (repeat 200 "x"))})]
    (is (every? #(<= (count %) 80) (str/split-lines s)))))
