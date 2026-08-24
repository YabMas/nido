;; test/nido/review/record_pipeline_test.clj
(ns nido.review.record-pipeline-test
  "The baseline round driven as a loop: what each stage does with an answer, and
   which of the ways it can go wrong are terminal. Both agents are seams — the
   codex judge through `baseline-review!`, the amender through `agent/launch!` —
   so nothing here spawns a process."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.review.loop :as rloop]
   [nido.review.record :as record]
   [nido.review.stages :as stages]))

(def ^:private a-baseline
  {:format :baseline
   :area "order totalling"
   :bounded-by "money amounts on an order"
   :shape "the aggregate is the only thing that sums lines"
   :load-bearing [{:property "the aggregate is the only summing path"
                   :evidence ["src/order/aggregate.clj:12"]}]
   :health [{:id "invoice-resums" :axis :design :observation "two summing paths"
             :evidence ["src/order/invoice.clj:88"]}]
   :read ["src/order/aggregate.clj"]})

(def ^:private a-finding
  {:cites ["the aggregate is the only summing path"]
   :claim "the invoice renderer sums independently"
   :evidence ["src/order/invoice.clj:88"]})

(defn- ctx [& {:as over}]
  (merge {:config {:cwd "/w" :run-id "r1"} :iter 1 :control :continue} over))

(defn- run [stage c] ((:run stage) c))

;; ── What identifies a baseline finding ──────────────────────────────────────

(deftest a-finding-is-keyed-on-the-code-it-cites-not-the-prose
  ;; The amender rewrites :cites — it quotes the property being refuted — so a
  ;; key over that text would make every round look new and the stall detector
  ;; would never fire.
  (is (= (record/baseline-finding-key a-finding)
         (record/baseline-finding-key
          (assoc a-finding :cites ["the aggregate is the only summing path, restated"]
                 :claim "the invoice renderer sums independently, restated")))))

(deftest two-findings-about-different-code-are-different-findings
  (is (not= (record/baseline-finding-key a-finding)
            (record/baseline-finding-key (assoc a-finding :evidence ["src/order/csv.clj:4"])))))

(deftest a-finding-citing-no-code-falls-back-to-its-text-and-cannot-collide
  (let [no-ev (dissoc a-finding :evidence)]
    (is (not= (record/baseline-finding-key no-ev)
              (record/baseline-finding-key a-finding)))
    (is (= [:cites (:cites no-ev)] (record/baseline-finding-base-key no-ev)))))

;; ── The judge stage ─────────────────────────────────────────────────────────

(deftest an-accurate-verdict-stops-the-loop
  (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                 :verdict :accurate :reason "ok"})
                record/append! (fn [_ _] nil)]
    (let [out (run record/judge-stage (ctx))]
      (is (= :stop (:control out)))
      (is (= :accurate (:status out)))
      (is (= [] (:findings out))))))

(deftest findings-carry-into-the-context-and-the-loop-continues
  (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                 :verdict :falsified
                                                 :findings [a-finding]})
                record/append! (fn [_ _] nil)]
    (let [out (run record/judge-stage (ctx))]
      (is (nil? (:status out)))
      (is (= [(assoc a-finding :disputed-n 0)] (:findings out))
          "each finding carries how many times it has been objected to"))))

(deftest a-round-that-could-not-run-keeps-its-own-name
  ;; The distinction the one-shot round already held, and which matters more in
  ;; a loop: :codex-failed on round three is not convergence.
  (doseq [outcome [:codex-failed :no-output :nothing-to-check :no-record
                   :no-workstream :round-crashed :unusable-answer]]
    (with-redefs [record/baseline-review! (fn [_] {:outcome outcome :detail "d"})
                  record/append! (fn [_ _] nil)]
      (is (= outcome (:status (run record/judge-stage (ctx))))
          (str outcome " must terminate under its own name")))))

;; ── The amend stage ─────────────────────────────────────────────────────────

(defn- with-amend
  "Run amend-stage with every seam stubbed. `writes` is called with the out-path
   and stands in for what the amender did (or did not) leave behind."
  [{:keys [prev writes dirty-before? dirty-after? append-throws?]
    :or {prev a-baseline dirty-before? false dirty-after? false}} c]
  (let [dirty (atom dirty-before?)
        appended (atom nil)]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] prev)
                  stages/working-copy-dirty? (fn [_] @dirty)
                  ws/append-entry! (fn [_ _ _ payload]
                                     (when append-throws?
                                       (throw (ex-info "schema said no" {})))
                                     (reset! appended payload)
                                     "/ws/entries/0002-baseline.edn")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (when writes
                                    (writes (second (re-find #"Write EDN to:\n\n  (\S+)"
                                                             first-message))))
                                  (reset! dirty dirty-after?)
                                  {:num-turns 3})]
      [(run record/amend-stage c) @appended])))

(deftest a-dry-run-never-launches-an-amender
  (let [launched (atom false)]
    (with-redefs [agent/launch! (fn [_] (reset! launched true) {})]
      (let [out (run record/amend-stage (ctx :config {:cwd "/w" :run-id "r1" :dry-run? true}))]
        (is (= :dry-run (:status out)))
        (is (false? @launched))))))

(deftest an-amender-that-wrote-code-is-terminal
  ;; No stage of a record loop may touch the working copy. Whatever it wrote is
  ;; left in place — this halts for a human rather than tidying up after it.
  (let [[out _] (with-amend {:dirty-before? false :dirty-after? true}
                            (ctx :findings [a-finding]))]
    (is (= :amend-touched-code (:status out)))
    (is (= :stop (:control out)))))

(deftest an-already-dirty-worktree-is-not-blamed-on-the-amender
  ;; A session worktree routinely carries a human's uncommitted work.
  (let [[out appended] (with-amend {:dirty-before? true :dirty-after? true
                                    :writes (fn [p] (spit p (pr-str a-baseline)))}
                                   (ctx :findings [a-finding]))]
    (is (not= :amend-touched-code (:status out)))
    (is (some? appended))))

(deftest an-amender-that-wrote-nothing-leaves-the-ledger-alone
  ;; Also the staleness guard: these tests share a run-id, so the out-path this
  ;; round would use still holds an earlier test's record. A round that read it
  ;; would append someone else's answer to the ledger as a superseding baseline.
  (let [[out appended] (with-amend {} (ctx :findings [a-finding]))]
    (is (= :amend-noop (:status out)))
    (is (nil? appended))))

(deftest an-unreadable-answer-is-its-own-outcome
  (let [[out appended] (with-amend {:writes (fn [p] (spit p "{:not edn"))}
                                   (ctx :findings [a-finding]))]
    (is (= :amend-unreadable (:status out)))
    (is (nil? appended))))

(deftest a-record-the-ledger-refuses-is-its-own-outcome
  (let [[out _] (with-amend {:append-throws? true
                             :writes (fn [p] (spit p (pr-str a-baseline)))}
                            (ctx :findings [a-finding]))]
    (is (= :amend-invalid (:status out)))
    (is (= "schema said no" (:amend-error out)))))

(deftest a-corrected-record-is-appended-and-the-loop-continues
  (let [corrected (assoc-in a-baseline [:load-bearing 0 :property]
                            "the invoice renderer sums independently of the aggregate")
        [out appended] (with-amend {:writes (fn [p] (spit p (pr-str corrected)))}
                                   (ctx :findings [a-finding]))]
    (is (nil? (:status out)) "a repair is not terminal")
    (is (= corrected (read-string appended)))
    (is (= [] (:retreats out)) "correcting a property in place gives nothing up")
    (is (= 1 (count (:history out))))))

(deftest a-record-amended-below-its-own-threshold-is-a-retreat-not-a-convergence
  ;; The failure the whole stage exists to catch: strip the record and the next
  ;; judge round would refuse to run, which reads as success from every angle
  ;; except this one.
  (let [gutted (assoc a-baseline :load-bearing [] :health [])
        [out _] (with-amend {:writes (fn [p] (spit p (pr-str gutted)))}
                            (ctx :findings [a-finding]))]
    (is (= :retreated (:status out)))
    (is (= :stop (:control out)))
    (is (seq (:retreats out)) "and it says what was given up")))

(deftest a-weakening-that-stays-above-the-threshold-is-reported-and-continues
  ;; Reported, never forbidden: dropping a property the code genuinely refutes
  ;; IS the honest repair. What must not happen is one passing silently.
  (let [thinner (update a-baseline :health empty)
        [out _] (with-amend {:writes (fn [p] (spit p (pr-str thinner)))}
                            (ctx :findings [a-finding]))]
    (is (nil? (:status out)))
    (is (contains? (set (map :what (:retreats out))) :health-dropped))
    (is (= [{:what :health-dropped
             :detail "observation invoice-resums is no longer recorded"}]
           (:retreats (first (:history out)))))))

;; ── Driven by the engine ────────────────────────────────────────────────────

(deftest the-pipeline-converges-when-the-code-stops-refuting-the-record
  (let [round (atom 0)]
    (with-redefs [record/baseline-review!
                  (fn [_] (if (= 1 (swap! round inc))
                            {:format :baseline-review :verdict :falsified
                             :findings [a-finding]}
                            {:format :baseline-review :verdict :accurate :reason "ok"}))
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)
                  stages/working-copy-dirty? (fn [_] false)
                  ws/append-entry! (fn [_ _ _ _] "/ws/entries/0002-baseline.edn")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                        (pr-str a-baseline))
                                  {:num-turns 3})]
      (let [out (rloop/run-loop {:run-id "r-conv" :cwd "/w"
                                 :pipeline record/baseline-pipeline
                                 :finding-key record/baseline-finding-key})]
        (is (= :accurate (:status out)))
        (is (= 2 (:iter out)))))))

(deftest an-amender-that-changes-nothing-stalls-instead-of-spinning
  ;; Uncapped. The only thing that ends this is the injected identity seeing the
  ;; same finding twice — which is what the diff review's key could never do
  ;; here, every record finding colliding on [nil nil nil].
  (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                 :verdict :falsified
                                                 :findings [a-finding]})
                record/append! (fn [_ _] nil)
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (fn [_ _ _] a-baseline)
                stages/working-copy-dirty? (fn [_] false)
                ws/append-entry! (fn [_ _ _ _] "/ws/entries/0002-baseline.edn")
                agent/launch! (fn [{:keys [first-message]}]
                                (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                      (pr-str a-baseline))
                                {:num-turns 3})]
    (let [out (rloop/run-loop {:run-id "r-stall" :cwd "/w"
                               :pipeline record/baseline-pipeline
                               :finding-key record/baseline-finding-key})]
      (is (= :no-progress (:status out))))))

;; ── The prompt ──────────────────────────────────────────────────────────────

(deftest the-amend-prompt-names-the-job-and-the-cheap-wrong-answer
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding]
                                :out-path "/run/amend-round-1.edn"})]
    (testing "what it is for"
      (is (str/includes? p "make the survey TRUE"))
      (is (str/includes? p "not the same job as making the")))
    (testing "the evidence the judge cited is in front of the amender"
      (is (str/includes? p "src/order/invoice.clj:88")))
    (testing "the weakening moves are named, with their consequence"
      (is (str/includes? p ":invisibly-incomplete?"))
      (is (str/includes? p "measured and reported to a human")))
    (testing "and it may not write code or append the record itself"
      (is (str/includes? p "Do NOT edit any source file"))
      (is (str/includes? p "Do not append it yourself")))))

;; ── The appeal channel ──────────────────────────────────────────────────────

(deftest an-objection-is-made-by-number-so-nothing-has-to-match-text-to-text
  (let [answer (record/parse-amend-answer
                {:disputes [{:finding 1 :because "the renderer calls the aggregate"
                             :evidence ["src/order/invoice.clj:90"]}]}
                [a-finding])]
    (is (nil? (:record answer)))
    (is (= [{:key (record/baseline-finding-base-key a-finding)
             :claim (:claim a-finding)
             :because "the renderer calls the aggregate"
             :evidence ["src/order/invoice.clj:90"]}]
           (:disputes answer)))))

(deftest an-objection-that-cannot-be-answered-is-dropped
  (testing "out of range"
    (is (= [] (:disputes (record/parse-amend-answer
                          {:disputes [{:finding 7 :because "no"}]} [a-finding])))))
  (testing "no reason given — an objection with no reason is not an appeal"
    (is (= [] (:disputes (record/parse-amend-answer
                          {:disputes [{:finding 1 :because "  "}]} [a-finding]))))
    (is (= [] (:disputes (record/parse-amend-answer
                          {:disputes [{:finding 1}]} [a-finding]))))))

(deftest a-bare-record-is-still-a-valid-answer
  ;; The shape from before there was anything to say back. Records already
  ;; written must not stop being readable because the answer grew a wrapper.
  (let [answer (record/parse-amend-answer a-baseline [a-finding])]
    (is (= a-baseline (:record answer)))
    (is (= [] (:disputes answer)))))

(deftest an-objection-round-trip-is-progress-not-a-stall
  ;; A dispute changes no record, so a judge that answers by RESTATING produces
  ;; a set identical to last round's. Without the dispute count in the identity
  ;; that reads as a stalled loop and ends before the channel completes even one
  ;; exchange.
  (let [f0 (assoc a-finding :disputed-n 0)
        f1 (assoc a-finding :disputed-n 1)]
    (is (not= (record/baseline-finding-key f0) (record/baseline-finding-key f1)))))

(deftest an-amender-that-only-objects-leaves-the-ledger-alone-and-keeps-going
  (let [[out appended]
        (with-amend {:writes (fn [p] (spit p (pr-str {:disputes [{:finding 1 :because "wrong"}]})))}
                    (ctx :findings [a-finding]))]
    (is (nil? (:status out)) "objecting is a complete answer, not an empty one")
    (is (nil? appended) "and it does not touch the record")
    (is (= 1 (count (:disputes out))))
    (is (= [] (:retreats out)))))

(deftest objections-and-an-amendment-can-arrive-together
  (let [corrected (assoc a-baseline :read ["src/order/aggregate.clj" "src/order/invoice.clj"])
        [out appended]
        (with-amend {:writes (fn [p] (spit p (pr-str {:record corrected
                                                      :disputes [{:finding 1 :because "wrong"}]})))}
                    (ctx :findings [a-finding]))]
    (is (nil? (:status out)))
    (is (= corrected (read-string appended)))
    (is (= 1 (count (:disputes out))))))

(deftest a-finding-restated-after-two-objections-goes-to-a-human
  ;; Neither side can settle it: the judge cannot be overruled by the pass it is
  ;; judging, and that pass may not amend a record it believes is already true.
  (let [disputed (fn [n] (vec (repeat n {:disputes [{:key (record/baseline-finding-base-key a-finding)
                                                     :claim "c" :because "b"}]})))]
    (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                   :verdict :falsified
                                                   :findings [a-finding]})
                  record/append! (fn [_ _] nil)]
      (testing "once objected to, the judge restating it is the answer we asked for"
        (let [out (run record/judge-stage (ctx :history (disputed 1)))]
          (is (nil? (:status out)))
          (is (= 1 (:disputed-n (first (:findings out)))))))
      (testing "twice objected to and stated again, it is a human's call"
        (let [out (run record/judge-stage (ctx :history (disputed 2)))]
          (is (= :disputed (:status out)))
          (is (= :escalate (:control out))))))))

(deftest a-withdrawn-finding-never-reaches-the-escalation
  (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                 :verdict :accurate :reason "ok"})
                record/append! (fn [_ _] nil)]
    (let [out (run record/judge-stage
                   (ctx :history (vec (repeat 2 {:disputes [{:key (record/baseline-finding-base-key a-finding)
                                                             :claim "c" :because "b"}]}))))]
      (is (= :accurate (:status out)) "withdrawing is the other honest answer"))))

(deftest standing-objections-reach-the-next-judge
  (let [seen (atom nil)]
    (with-redefs [record/baseline-review! (fn [opts] (reset! seen opts)
                                            {:format :baseline-review :verdict :accurate})
                  record/append! (fn [_ _] nil)]
      (run record/judge-stage
           (ctx :history [{:disputes [{:key [:evidence ["x"]] :claim "c" :because "b"}]}]))
      (is (= 1 (count (:disputes @seen)))))))

(deftest the-judge-prompt-tells-the-judge-it-may-be-the-one-that-is-wrong
  (let [p (record/disputes-block [{:claim "the renderer sums independently"
                                   :because "it delegates to the aggregate"
                                   :evidence ["src/order/invoice.clj:90"]}])]
    (is (str/includes? p "may itself be mistaken")
        "the amender is not an authority — the judge is told to go look")
    (is (str/includes? p "withdraw the finding"))
    (is (str/includes? p "report it again with evidence"))
    (is (str/includes? p "src/order/invoice.clj:90"))))

(deftest no-objections-puts-nothing-in-the-prompt
  (is (nil? (record/disputes-block []))))

(deftest the-amend-prompt-offers-the-objection-route-and-names-its-worst-abuse
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding]
                                :out-path "/run/a.edn"})]
    (is (str/includes? p "1. refutes:") "findings are numbered, and answered by number")
    (is (str/includes? p "IF A FINDING IS WRONG ABOUT THE CODE, SAY SO INSTEAD"))
    (is (str/includes? p "You do not settle it"))
    (is (str/includes? p "makes the record false AND ends the argument"))))
