;; test/nido/review/design_pipeline_test.clj
(ns nido.review.design-pipeline-test
  "The decision round driven as a loop. Its terminal state is an ESCALATION, not
   a convergence — everything derivable is derived so that what reaches a human
   is only the judgement that cannot be. Both agents are seams."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.review.loop :as rloop]
   [nido.review.record :as record]
   [nido.review.stages :as stages]))

(defn- with-tmp-nido-root
  "Every stage here writes where the real stage writes — run dirs, answer files
   — so without this the suite scatters run directories through the user's live
   ~/.nido/runs/. Redirecting the root is the fix rather than stubbing the calls
   that bite, because the hazard is structural: the next side effect a stage
   grows would land there too."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(use-fixtures :each with-tmp-nido-root)

(def ^:private a-design
  {:format :design
   :summary "rounding moves to one point"
   :shape "one rounding boundary"
   :standing {:relation :challenges :note "n"}
   :baseline {:seq 1 :relation :revisit :breaks ["p"] :note "n"}
   :intent {:seq 0}
   :invariants ["one rounding boundary"]
   :effort :L})

(defn- check [c status] {:check c :status status :note (str (name c) " note")})

;; ── The premise the round stands on ─────────────────────────────────────────
;;
;; Three of the four derivations are made AGAINST the survey, so a survey nobody
;; verified makes them worthless in a way the round cannot see from inside: its
;; only honest exit is :resurvey. The first workstream to run this loop took that
;; exit seven times out of seven and never reached a decision, paying for a full
;; derivation each time to rediscover something already legible in the ledger.

(deftest a-survey-nobody-verified-is-not-something-to-decide-against
  (with-redefs [ws/entries-of (constantly [])]
    (let [out (record/unverified-premise :nido "ws-1" a-design)]
      (is (= :premise-unverified (:outcome out)))
      (is (str/includes? (:detail out) "entry 1")
          "which survey went unverified is the whole of what the reader has to act on"))))

(deftest a-review-of-a-DIFFERENT-survey-does-not-verify-this-one
  ;; A workstream can hold several surveys and several reviews. The one that
  ;; counts is the one naming the survey the design stands on — reading `the
  ;; latest review` would let a survey of another area vouch for this one.
  (with-redefs [ws/entries-of (constantly [{:format :baseline-review
                                            :verdict :sufficient :baseline-seq 7}])]
    (is (some? (record/unverified-premise :nido "ws-1" a-design))))
  (with-redefs [ws/entries-of (constantly [{:format :baseline-review
                                            :verdict :falsified :baseline-seq 1}])]
    (is (some? (record/unverified-premise :nido "ws-1" a-design))
        "checked and found wrong is not checked and found sound")))

(deftest a-verified-survey-clears-the-gate-under-either-question
  (doseq [v [:sufficient :accurate]]
    (with-redefs [ws/entries-of (constantly [{:format :baseline-review
                                              :verdict v :baseline-seq 1}])]
      (is (nil? (record/unverified-premise :nido "ws-1" a-design))
          "a survey checked under the older question was still checked"))))

(deftest a-design-citing-no-survey-is-not-gated
  (with-redefs [ws/entries-of (constantly [])]
    (is (nil? (record/unverified-premise :nido "ws-1" (dissoc a-design :baseline)))
        "there is no premise to verify, and the prompt says so")))

(deftest the-gate-is-read-before-a-judge-is-spent
  (let [launched (atom 0)
        run (fn [entries]
              (reset! launched 0)
              (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                            ws/latest-entry (fn [_ _ k] (when (= :design k) a-design))
                            ws/entries-of (constantly entries)
                            stages/read-stance (constantly nil)
                            record/discover-intent (constantly nil)
                            record/run-round! (fn [_] (swap! launched inc)
                                                {:outcome :no-output :detail "stub"})]
                (record/design-decision! {:cwd "/w" :run-id "r1" :label "l"})))]
    (is (= :premise-unverified (:outcome (run []))))
    (is (zero? @launched)
        "the answer is in nido's own ledger; paying an agent to rediscover it is the defect")
    ;; The control, and it is what makes the assertion above mean anything: with
    ;; the survey verified the same call DOES reach the judge, so a zero count is
    ;; the gate refusing rather than the seam failing to bite.
    (is (= :no-output (:outcome (run [{:format :baseline-review
                                       :verdict :sufficient :baseline-seq 1}]))))
    (is (= 1 @launched))))


(defn- decision [recommend & {:keys [checks findings]}]
  (cond-> {:format :design-decision :design-seq 4 :recommend recommend
           :reason "r" :asks "is this worth doing now?"
           :checks (or checks [(check :relation-honest :broken)])}
    findings (assoc :findings findings)))

(defn- ctx [& {:as over}]
  (merge {:config {:cwd "/w" :run-id "r1"} :iter 1 :control :continue} over))

(defn- run [stage c] ((:run stage) c))

;; ── What identifies a design finding ────────────────────────────────────────

(deftest a-design-finding-is-the-check-not-the-prose
  ;; The prose quotes the record, and every amendment rewrites the record. The
  ;; check vocabulary is four closed values no amendment can move.
  (is (= (record/design-finding-base-key (check :relation-honest :broken))
         (record/design-finding-base-key
          (assoc (check :relation-honest :broken) :note "completely different words"))))
  (is (not= (record/design-finding-base-key (check :relation-honest :broken))
            (record/design-finding-base-key (check :goal-served :broken)))))

;; ── The judge stage ─────────────────────────────────────────────────────────

(deftest proceed-escalates-because-the-ask-is-the-point
  (with-redefs [record/design-decision! (fn [_] (decision :proceed))
                record/append! (fn [_ _] nil)]
    (let [out (run record/design-judge-stage (ctx))]
      (is (= :escalate (:control out)))
      (is (= :proceed (:status out))))))

(deftest only-broken-checks-become-findings
  (with-redefs [record/design-decision!
                (fn [_] (decision :amend :checks [(check :relation-honest :broken)
                                                  (check :goal-served :held)
                                                  (check :decomposable :underivable)]
                                  :findings [{:cites ["c"] :claim "x"}]))
                record/append! (fn [_ _] nil)]
    (let [out (run record/design-judge-stage (ctx))]
      (is (= [:relation-honest] (mapv :check (:findings out))))
      (is (= [:decomposable] (mapv :check (:underivable out)))))))

(deftest a-check-with-no-yardstick-is-never-sent-to-an-amender
  ;; nido's own work has no stance document, so relation-honest has nothing to
  ;; check against. An amender told to fix that would amend a true record until
  ;; the complaint went away.
  (with-redefs [record/design-decision!
                (fn [_] (decision :amend :checks [(check :relation-honest :underivable)
                                                  (check :goal-served :held)]
                                  :findings [{:cites ["c"] :claim "x"}]))
                record/append! (fn [_ _] nil)]
    (let [out (run record/design-judge-stage (ctx))]
      (is (= :underivable (:status out)))
      (is (= :escalate (:control out)))
      (is (= [] (:findings out))))))

(deftest a-check-restated-after-two-objections-goes-to-a-human
  (let [k (record/design-finding-base-key (check :relation-honest :broken))]
    (with-redefs [record/design-decision! (fn [_] (decision :amend))
                  record/append! (fn [_ _] nil)]
      (is (nil? (:status (run record/design-judge-stage
                              (ctx :history [{:disputes [{:key k :claim "c" :because "b"}]}])))))
      (is (= :disputed
             (:status (run record/design-judge-stage
                           (ctx :history (vec (repeat 2 {:disputes [{:key k :claim "c" :because "b"}]}))))))))))

(deftest a-round-that-could-not-run-keeps-its-own-name
  (doseq [outcome [:codex-failed :no-record :not-worth-running :unusable-answer]]
    (with-redefs [record/design-decision! (fn [_] {:outcome outcome :detail "d"})
                  record/append! (fn [_ _] nil)]
      (is (= outcome (:status (run record/design-judge-stage (ctx))))))))

(deftest an-escalated-decision-carries-how-it-was-arrived-at
  ;; The whole reason the trajectory field exists: the gate shows the LATEST
  ;; ledger entry, so this is the only place a weakening can reach the human it
  ;; was escalated to.
  (let [appended (atom nil)]
    (with-redefs [record/design-decision! (fn [_] (decision :proceed))
                  record/append! (fn [_ r] (reset! appended r))]
      (run record/design-judge-stage
           (ctx :history [{:findings [(check :relation-honest :broken)]
                           :retreats [{:what :effort-lowered :detail ":L → :M"}]
                           :disputes [] :amended? true}]))
      (is (= [{:round 1 :found ["relation-honest"] :amended true
               :weakened ["effort-lowered — :L → :M"]}]
             (:trajectory @appended))))))

(deftest a-first-round-decision-carries-no-trajectory
  (let [appended (atom nil)]
    (with-redefs [record/design-decision! (fn [_] (decision :proceed))
                  record/append! (fn [_ r] (reset! appended r))]
      (run record/design-judge-stage (ctx))
      (is (not (contains? @appended :trajectory))
          "a round with nothing behind it must not write an empty one"))))

;; ── The amend stage ─────────────────────────────────────────────────────────

(defn- with-amend
  [{:keys [prev writes recommend append-throws?] :or {prev a-design recommend :amend}} c]
  (let [appended (atom nil)]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] prev)
                  stages/discover-baseline (fn [_ _] nil)
                  stages/working-copy-state (fn [_] "")
                  ws/append-entry! (fn [_ _ _ payload]
                                     (when append-throws? (throw (ex-info "schema said no" {})))
                                     (reset! appended payload)
                                     "/ws/entries/0005-design.edn")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (when writes
                                    (writes (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))))
                                  {:num-turns 3})]
      [(run record/design-amend-stage
            (assoc-in c [:record :recommend] recommend))
       @appended])))

(deftest a-superseding-design-is-appended-and-the-loop-continues
  (let [fixed (assoc a-design :invariants ["one rounding boundary" "totals never re-round"])
        [out appended] (with-amend {:writes (fn [p] (spit p (pr-str {:record fixed})))}
                                   (ctx :findings [(check :relation-honest :broken)]
                                        :record (decision :amend)))]
    (is (nil? (:status out)))
    (is (= fixed (read-string appended)))
    (is (= [] (:retreats out)))))

(deftest a-design-amended-below-its-own-threshold-is-a-retreat
  ;; Declare :within on the baseline, :conforms on the stance, a modest effort
  ;; and everything routed :fix-here, and the design round refuses to run at all
  ;; — which reads as success from every angle except this one.
  (let [gutted (assoc a-design :baseline {:seq 1 :relation :within}
                      :standing {:relation :conforms} :effort :M)
        [out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record gutted})))}
                            (ctx :findings [(check :relation-honest :broken)]
                                 :record (decision :amend)))]
    (is (= :retreated (:status out)))
    (is (contains? (set (map :what (:retreats out))) :effort-lowered))))

(deftest recut-and-amend-are-given-different-jobs
  (let [prompts (atom [])]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-design)
                  stages/discover-baseline (fn [_ _] nil)
                  stages/working-copy-state (fn [_] "")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (swap! prompts conj first-message) {:num-turns 1})]
      (doseq [r [:amend :recut]]
        (run record/design-amend-stage
             (assoc (ctx :findings [(check :decomposable :broken)])
                    :record (decision r))))
      (let [[amend recut] @prompts]
        (is (str/includes? amend "RECORD has a derivable defect"))
        (is (str/includes? recut "DECOMPOSITION does not hold"))
        (is (str/includes? recut "restating the claims will not fix it"))))))

;; ── Resurvey: the loop that calls the other loop ────────────────────────────

(def ^:private corrected-baseline
  {:format :baseline :seq 11 :area "a" :bounded-by "b" :shape "s"
   :load-bearing [{:property "p" :evidence ["src/x.clj:1"]}] :read ["src/x.clj"]})

(defn- with-resurvey
  "Stub every seam the two-step re-survey touches. `amends` stands in for what
   the amender wrote after the nested loop came back."
  [{:keys [nested amends]} c]
  (let [prompt (atom nil) appended (atom nil)]
    (with-redefs [rloop/run-loop (fn [_] {:status nested :under-repair corrected-baseline})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ kind]
                                    (if (= :baseline kind) corrected-baseline a-design))
                  stages/discover-baseline (fn [_ _] {:format :baseline :seq 8})
                  stages/working-copy-state (fn [_] "")
                  ws/append-entry! (fn [_ _ _ payload] (reset! appended payload) "/e")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (reset! prompt first-message)
                                  (when amends
                                    (amends (second (re-find #"Write EDN to:\n\n  (\S+)"
                                                             first-message))))
                                  {:num-turns 3})]
      [(run record/design-amend-stage c) @prompt @appended])))

(deftest a-resurvey-is-only-half-the-repair
  ;; The failure this catches: discover-baseline resolves the CITED baseline, so
  ;; repairing the latest one changes nothing the next round can see. The design
  ;; would be judged against the same stale survey, reach the same verdict, and
  ;; re-survey until the cap.
  (let [repointed (assoc a-design :baseline {:seq 11 :relation :extends :note "n"})
        [out prompt appended]
        (with-resurvey {:nested :sufficient
                        :amends (fn [p] (spit p (pr-str {:record repointed})))}
                       (assoc (ctx :findings [(check :relation-honest :broken)])
                              :record (decision :resurvey)))]
    (is (nil? (:status out)) "the round continues once the design is re-stated")
    (is (some? appended) "and the design is what gets superseded")
    (is (= 11 (get-in (read-string appended) [:baseline :seq])))
    (testing "the amender sees the CORRECTED survey, not the one that was wrong"
      (is (str/includes? prompt "PREMISE was wrong"))
      ;; By its content, not by its :seq — the stamp is the reader's and is
      ;; stripped before the record is shown, precisely so it can be written back.
      (is (str/includes? prompt "src/x.clj:1"))
      (is (not (str/includes? prompt ":seq 11"))))
    (testing "and is told a bare re-citation is not the job"
      (is (str/includes? prompt "not a re-citation"))
      (is (str/includes? prompt "nobody has re-checked it")))
    (testing "the two halves are recorded as one round"
      (is (= 1 (count (:history out))))
      (is (= :sufficient (:resurveyed (first (:history out))))))))

(deftest the-nested-loop-does-not-emit-into-this-run
  ;; Its rounds are not this run's rounds; folding them in would renumber both.
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :sufficient})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-design)
                  stages/discover-baseline (fn [_ _] {:format :baseline :seq 8})
                  stages/working-copy-state (fn [_] "")
                  agent/launch! (fn [_] {:num-turns 0})]
      (run record/design-amend-stage
           (assoc (ctx :findings []) :record (decision :resurvey)))
      (is (= {:format :baseline :seq 8} (:baseline @seen))
          "the nested loop repairs the survey the design CITES, not the newest one")
      (is (= record/baseline-pipeline (:pipeline @seen)))
      (is (= record/baseline-finding-key (:finding-key @seen)))
      (is (fn? (:emit @seen)))
      (is (nil? ((:emit @seen) {:event :phase-started}))))))

(deftest a-resurvey-that-did-not-hold-is-terminal-here
  ;; A design round cannot proceed on a survey the baseline loop could not make
  ;; true; re-judging against it would build a decision on the failed premise.
  (doseq [s [:retreated :no-progress :amend-noop]]
    (with-redefs [rloop/run-loop (fn [_] {:status s})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-design)
                  stages/discover-baseline (fn [_ _] {:format :baseline :seq 8})]
      (let [out (run record/design-amend-stage
                     (assoc (ctx :findings []) :record (decision :resurvey)))]
        (is (= (keyword (str "resurvey-" (name s))) (:status out)))
        (is (= :stop (:control out)))))))

(deftest re-surveying-is-not-capped
  ;; A re-survey is only half a repair: the design is re-stated against the
  ;; corrected survey afterwards, so every cycle changes the record the next
  ;; round judges. A count would stop the loop while it was still making
  ;; progress, which is the one thing a convergence loop must not do — the
  ;; engine's stall detector is what ends a run that has stopped getting
  ;; anywhere.
  (let [nested (atom 0)]
    (with-redefs [rloop/run-loop (fn [_] (swap! nested inc) {:status :sufficient})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-design)
                  stages/discover-baseline (fn [_ _] nil)
                  stages/working-copy-state (fn [_] "")
                  agent/launch! (fn [_] {:num-turns 0})]
      (doseq [prior (range 5)]
        (let [hist (vec (repeat prior {:resurveyed :sufficient}))
              out  (run record/design-amend-stage
                        (assoc (ctx :findings [] :history hist) :record (decision :resurvey)))]
          (is (not= :resurvey-exhausted (:status out))
              (str "descent " (inc prior) " must still be allowed to run"))))
      (is (= 5 @nested) "every one of them reached the baseline loop"))))

;; ── Driven by the engine ────────────────────────────────────────────────────

(deftest the-pipeline-ends-at-a-human-once-nothing-derivable-is-left
  (let [round (atom 0)]
    (with-redefs [record/design-decision!
                  (fn [_] (if (= 1 (swap! round inc))
                            (decision :amend :findings [{:cites ["c"] :claim "x"}])
                            (decision :proceed)))
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-design)
                  stages/discover-baseline (fn [_ _] nil)
                  stages/working-copy-state (fn [_] "")
                  ws/append-entry! (fn [_ _ _ _] "/ws/entries/0005-design.edn")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                        (pr-str {:record a-design}))
                                  {:num-turns 3})]
      (let [out (rloop/run-loop {:run-id "r-design" :cwd "/w"
                                 :pipeline record/design-pipeline
                                 :finding-key record/design-finding-key})]
        (is (= :proceed (:status out)))
        (is (= 2 (:iter out)))))))

(deftest an-amender-that-changes-nothing-stalls-instead-of-spinning
  (with-redefs [record/design-decision! (fn [_] (decision :amend))
                record/append! (fn [_ _] nil)
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (fn [_ _ _] a-design)
                stages/discover-baseline (fn [_ _] nil)
                stages/working-copy-state (fn [_] "")
                ws/append-entry! (fn [_ _ _ _] "/ws/entries/0005-design.edn")
                agent/launch! (fn [{:keys [first-message]}]
                                (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                      (pr-str {:record a-design}))
                                {:num-turns 3})]
    (let [out (rloop/run-loop {:run-id "r-stall-design" :cwd "/w"
                               :pipeline record/design-pipeline
                               :finding-key record/design-finding-key})]
      (is (= :no-progress (:status out))))))

;; ── The prompt ──────────────────────────────────────────────────────────────

(deftest the-design-amend-prompt-names-the-cheap-wrong-answer-in-its-own-vocabulary
  (let [p (record/design-amend-prompt
           {:design a-design :recommend :amend :reason "r"
            :checks [(check :relation-honest :broken)]
            :findings [{:cites ["c"] :claim "x"}]
            :out-path "/run/a.edn"})]
    (is (str/includes? p "make the record TRUE"))
    (is (str/includes? p "It is NOT to make the\nchecks pass"))
    (is (str/includes? p "softening :revisit to :within"))
    (is (str/includes? p "1. relation-honest"))
    (is (str/includes? p "IF A CHECK IS WRONGLY MARKED BROKEN"))))

(deftest a-failed-resurvey-carries-its-reason-out-of-the-nested-loop
  ;; Seen live: the terminal said :resurvey-amend-invalid and stopped. A reader
  ;; cannot act on a refusal whose reason stayed inside a loop they never saw.
  (with-redefs [rloop/run-loop (fn [_] {:status :amend-invalid
                                        :amend-error "{:at [\"disallowed key\"]}"})
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (fn [_ _ _] a-design)
                stages/discover-baseline (fn [_ _] {:format :baseline :seq 8})]
    (let [out (run record/design-amend-stage
                   (assoc (ctx :findings []) :record (decision :resurvey)))]
      (is (= :resurvey-amend-invalid (:status out)))
      (is (= "{:at [\"disallowed key\"]}" (:amend-error out))))))
