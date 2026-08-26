;; test/nido/review/record_pipeline_test.clj
(ns nido.review.record-pipeline-test
  "The baseline round driven as a loop: what each stage does with an answer, and
   which of the ways it can go wrong are terminal. Both agents are seams — the
   codex judge through `baseline-review!`, the amender through `agent/launch!` —
   so nothing here spawns a process."
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

(def ^:private a-baseline
  {:format :baseline
   :modules [{:id "mod-the-order-aggregate" :module "the order aggregate"
              :hides "the order in which lines are summed"
              :interface "an order's total"}]
   :composition "only the aggregate sees the lines, so only it sums them"
   :area "order totalling"
   :bounded-by "money amounts on an order"
   :shape "the aggregate is the only thing that sums lines"
   :load-bearing [{:id "c1" :property "the aggregate is the only summing path"
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

(deftest the-judge-reads-the-code-cwd-and-the-ledger-reads-the-other-one
  ;; A survey describes the area BEFORE a change. Judged against a worktree that
  ;; already carries that change, the round reports the change's own modules as
  ;; things the survey failed to mention, and the amender folds the change into
  ;; the record it was supposed to be judged against. So the revision is its own
  ;; axis: the agents read :code-cwd, the workstream still resolves from :cwd.
  (let [seen (atom nil)]
    (with-redefs [record/run-round! (fn [opts] (reset! seen opts) {:ok "{}"})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)]
      (record/baseline-review! {:cwd "/ledger" :code-cwd "/base" :run-id "r1"})
      (is (= "/base" (:cwd @seen))
          "the judge reads the base revision, not the tree the work is in"))))

(deftest code-cwd-defaults-to-the-ledger-cwd
  (let [seen (atom nil)]
    (with-redefs [record/run-round! (fn [opts] (reset! seen opts) {:ok "{}"})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)]
      (record/baseline-review! {:cwd "/ledger" :run-id "r1"})
      (is (= "/ledger" (:cwd @seen))
          "they are the same tree in the ordinary case, and nothing has to say so"))))

(deftest an-accurate-verdict-stops-the-loop
  (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                 :verdict :sufficient :reason "ok"})
                record/append! (fn [_ _] nil)]
    (let [out (run record/judge-stage (ctx))]
      (is (= :stop (:control out)))
      (is (= :sufficient (:status out)))
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
   and stands in for what the amender did (or did not) leave behind; `tree-before`
   and `tree-after` are what the working copy's diff contained either side of it."
  [{:keys [prev writes tree-before tree-after append-throws?]
    :or {prev a-baseline tree-before "" tree-after nil}} c]
  (let [tree (atom tree-before)
        appended (atom nil)]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] prev)
                  stages/working-copy-state (fn [_] @tree)
                  ws/append-entry! (fn [_ _ _ payload]
                                     (when append-throws?
                                       (throw (ex-info "schema said no" {})))
                                     (reset! appended payload)
                                     "/ws/entries/0002-baseline.edn")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (when writes
                                    (writes (second (re-find #"Write EDN to:\n\n  (\S+)"
                                                             first-message))))
                                  (reset! tree (or tree-after @tree))
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
  (let [[out _] (with-amend {:tree-before "" :tree-after "diff --git a/x b/x"}
                            (ctx :findings [a-finding]))]
    (is (= :amend-touched-code (:status out)))
    (is (= :stop (:control out)))))

(deftest an-already-dirty-worktree-is-not-blamed-on-the-amender
  ;; A session worktree routinely carries a human's uncommitted work, and it is
  ;; still there afterwards. Comparing the diff rather than a dirty flag is what
  ;; lets that pass while an actual edit does not.
  (let [[out appended] (with-amend {:tree-before "a human's work"
                                    :writes (fn [p] (spit p (pr-str a-baseline)))}
                                   (ctx :findings [a-finding]))]
    (is (not= :amend-touched-code (:status out)))
    (is (some? appended))))

(deftest an-amender-that-writes-on-top-of-a-dirty-tree-is-caught
  ;; The hole the boolean left, and the one that mattered: the old guard only
  ;; fired on a clean-to-dirty transition, so on an already-dirty tree — which is
  ;; most real sessions — an amender could write code and nothing noticed. Found
  ;; by a live round judging this very namespace.
  (let [[out appended] (with-amend {:tree-before "a human's work"
                                    :tree-after "a human's work\n+ and the amender's"
                                    :writes (fn [p] (spit p (pr-str a-baseline)))}
                                   (ctx :findings [a-finding]))]
    (is (= :amend-touched-code (:status out)))
    (is (nil? appended) "and the record it returned never reaches the ledger")))

(deftest an-amender-that-wrote-nothing-leaves-the-ledger-alone
  (let [[out appended] (with-amend {} (ctx :findings [a-finding]))]
    (is (= :amend-noop (:status out)))
    (is (nil? appended))))

(deftest a-leftover-answer-is-not-mistaken-for-this-rounds
  ;; "The file is there" is the whole test for whether the amender answered, so
  ;; a leftover from an earlier run under this run-id would be appended to the
  ;; ledger as a superseding baseline nobody wrote this round.
  (let [dir  (cstate/run-dir "r1")
        _    (fs/create-dirs dir)
        _    (spit (str (fs/path dir "amend-round-1.edn"))
                   (pr-str {:record (assoc a-baseline :area "someone else's answer")}))
        [out appended] (with-amend {} (ctx :findings [a-finding]))]
    (is (= :amend-noop (:status out)))
    (is (nil? appended) "the stale record never reached the ledger")))

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
                            {:format :baseline-review :verdict :sufficient :reason "ok"}))
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)
                  stages/working-copy-state (fn [_] "")
                  ws/append-entry! (fn [_ _ _ _] "/ws/entries/0002-baseline.edn")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                        (pr-str a-baseline))
                                  {:num-turns 3})]
      (let [out (rloop/run-loop {:run-id "r-conv" :cwd "/w"
                                 :pipeline record/baseline-pipeline
                                 :finding-key record/baseline-finding-key})]
        (is (= :sufficient (:status out)))
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
                stages/working-copy-state (fn [_] "")
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
                [a-finding] record/baseline-finding-base-key)]
    (is (nil? (:record answer)))
    (is (= [{:key (record/baseline-finding-base-key a-finding)
             :claim (:claim a-finding)
             :because "the renderer calls the aggregate"
             :evidence ["src/order/invoice.clj:90"]}]
           (:disputes answer)))))

(deftest an-objection-that-cannot-be-answered-is-dropped
  (testing "out of range"
    (is (= [] (:disputes (record/parse-amend-answer
                          {:disputes [{:finding 7 :because "no"}]} [a-finding]
                          record/baseline-finding-base-key)))))
  (testing "no reason given — an objection with no reason is not an appeal"
    (is (= [] (:disputes (record/parse-amend-answer
                          {:disputes [{:finding 1 :because "  "}]} [a-finding]
                          record/baseline-finding-base-key))))
    (is (= [] (:disputes (record/parse-amend-answer
                          {:disputes [{:finding 1}]} [a-finding]
                          record/baseline-finding-base-key))))))

(deftest a-bare-record-is-still-a-valid-answer
  ;; The shape from before there was anything to say back. Records already
  ;; written must not stop being readable because the answer grew a wrapper.
  (let [answer (record/parse-amend-answer a-baseline [a-finding]
                                          record/baseline-finding-base-key)]
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
                                                 :verdict :sufficient :reason "ok"})
                record/append! (fn [_ _] nil)]
    (let [out (run record/judge-stage
                   (ctx :history (vec (repeat 2 {:disputes [{:key (record/baseline-finding-base-key a-finding)
                                                             :claim "c" :because "b"}]}))))]
      (is (= :sufficient (:status out)) "withdrawing is the other honest answer"))))

(deftest standing-objections-reach-the-next-judge
  (let [seen (atom nil)]
    (with-redefs [record/baseline-review! (fn [opts] (reset! seen opts)
                                            {:format :baseline-review :verdict :sufficient})
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

;; ── What the reader adds, the writer may not carry ──────────────────────────

(deftest a-record-read-from-the-ledger-can-be-written-back
  ;; The defect this catches took every amendment in both pipelines down, and no
  ;; fixture here had ever seen it: latest-entry stamps :seq and :at on the way
  ;; out, the write schema is closed against both, and the prompt shows the
  ;; amender exactly what it read. A faithful amender echoes them and the ledger
  ;; refuses the result.
  (let [stamped (assoc a-baseline :seq 11 :at "2026-08-24T17:27:18Z")]
    (testing "the amender is shown the record as it will be WRITTEN"
      (let [p (record/amend-prompt {:baseline stamped :findings [a-finding]
                                    :out-path "/run/a.edn"})]
        (is (not (str/includes? p ":seq 11")))
        (is (not (str/includes? p ":at \"2026-08-24")))))
    (testing "and an echoed stamp still does not reach the ledger"
      (let [[out appended]
            (with-amend {:prev stamped
                         :writes (fn [p] (spit p (pr-str {:record stamped})))}
                        (ctx :findings [a-finding]))]
        (is (nil? (:status out)) "not :amend-invalid")
        (is (= a-baseline (read-string appended)))))))

(deftest unstamping-leaves-a-citation-alone
  ;; A design's :baseline :seq is an author's citation, not the reader's stamp,
  ;; and they are the same key one level apart.
  (is (= {:format :design :baseline {:seq 8 :relation :within}}
         (ws/unstamp {:format :design :seq 10 :at "t"
                      :baseline {:seq 8 :relation :within}}))))

;; ── Which record a run is repairing ─────────────────────────────────────────

(deftest a-loop-repairs-the-record-it-was-pointed-at-not-the-newest
  ;; The failure this catches ran for five rounds and could not have converged:
  ;; a workstream held two surveys of DIFFERENT areas — a narrow follow-up
  ;; written beside the broad one — and the loop re-read "the latest" every
  ;; round, so it repaired the follow-up while the design went on citing the
  ;; other. No amount of repair to one answers a design citing the other.
  (let [cited {:format :baseline :area "the one the design cites"
               :load-bearing [{:id "c2" :property "p" :evidence ["src/cited.clj:1"]}]
               :bounded-by "b" :shape "s" :read ["src/cited.clj"]}
        newest (assoc cited :area "a narrow follow-up, appended later")
        seen (atom [])]
    (with-redefs [record/baseline-review!
                  (fn [{:keys [baseline]}]
                    (swap! seen conj (:area baseline))
                    {:format :baseline-review :verdict :sufficient :reason "ok"})
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] newest)]
      (run record/judge-stage (ctx :config {:cwd "/w" :run-id "r1" :baseline cited}))
      (is (= ["the one the design cites"] @seen)))))

(deftest a-loop-follows-its-own-amendment-rather-than-re-reading-the-ledger
  ;; Same hazard one step later: another session appending a baseline mid-run
  ;; must not hijack the repair.
  (let [start {:format :baseline :area "round one" :bounded-by "b" :shape "s"
               :load-bearing [{:id "c3" :property "p" :evidence ["src/a.clj:1"]}] :read ["src/a.clj"]}
        mine  (assoc start :area "what I amended it to")
        other (assoc start :area "what someone else appended")
        seen  (atom [])]
    (with-redefs [record/baseline-review!
                  (fn [{:keys [baseline]}] (swap! seen conj (:area baseline))
                    {:format :baseline-review :verdict :sufficient :reason "ok"})
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] other)]
      (run record/judge-stage (ctx :carry {:under-repair mine}))
      (is (= ["what I amended it to"] @seen)))))

(deftest an-amendment-becomes-the-record-the-next-round-repairs
  (let [corrected (assoc a-baseline :area "corrected")
        [out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record corrected})))}
                            (ctx :findings [a-finding]))]
    ;; In :carry, because that is the only part of the ctx the engine hands to
    ;; the next round.
    (is (= corrected (:under-repair (:carry out))))))

(deftest the-record-carried-on-is-the-one-the-ledger-stamped
  ;; The amender cannot write a :seq and must not invent one, but everything
  ;; downstream identifies the record by exactly that number — the verdict is
  ;; labelled with it and a design cites its survey by it. Carrying the record
  ;; as written leaves all of that pointing at nothing.
  (let [corrected (assoc a-baseline :area "corrected")]
    (with-redefs [ws/entry-at-seq (fn [_ _ n] (assoc corrected :seq n :at "t"))]
      (let [[out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record corrected})))}
                                (ctx :findings [a-finding]))]
        (is (= 2 (:seq (:under-repair (:carry out))))
            "the seq append-entry! answered with, read back off the path")))))

(deftest a-stamp-that-cannot-be-read-back-does-not-cost-the-round
  ;; Degrading beats throwing: the amendment is already committed to the ledger
  ;; by the time this runs, so a failed read-back must not turn a good round
  ;; into a terminal failure.
  (let [corrected (assoc a-baseline :area "corrected")]
    (with-redefs [ws/entry-at-seq (fn [_ _ _] nil)]
      (let [[out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record corrected})))}
                                (ctx :findings [a-finding]))]
        (is (nil? (:status out)))
        (is (= corrected (:under-repair (:carry out))))))))

(deftest the-amender-is-told-what-a-reading-may-say
  ;; It is the pass that WRITES readings and was the only one never shown the
  ;; vocabulary. Watched live: it invented a :tarpit/control verdict and a lens
  ;; outside the registry, the ledger refused the record, and a nineteen-reading
  ;; amendment was lost whole.
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding] :out-path "/x"})]
    (is (str/includes? p "THE PERSPECTIVES THIS SURVEY IS READ THROUGH"))
    (is (str/includes? p "imposed") "the verdicts themselves, not just the lens names")
    (is (str/includes? p "the whole record\nis lost with it")
        "and what it costs to guess"))
  (testing "a survey that cannot carry readings is not handed a vocabulary it cannot use"
    (let [p (record/amend-prompt {:baseline (dissoc a-baseline :modules)
                                  :findings [a-finding] :out-path "/x"})]
      (is (not (str/includes? p "THE PERSPECTIVES THIS SURVEY IS READ THROUGH"))))))

(deftest the-vocabulary-says-which-subject-each-lens-reads
  ;; Watched live: an amender attached a claim lens to a module. Only
  ;; :ousterhout/depth reads a module, the registry has always known it, and the
  ;; prompt never said so — and the cost of guessing is not a bad field but an
  ;; invalid dispatch, which refuses the whole record.
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding] :out-path "/x"})]
    (is (str/includes? p "a MODULE (never a claim)"))
    (is (str/includes? p "a LOAD-BEARING CLAIM (never a module)"))))

(deftest a-refused-record-says-which-field-was-wrong
  ;; "Invalid event report" names nothing an amender could fix, and the explain
  ;; data was on the exception all along.
  (let [[out _] (with-amend {:append-throws? true
                             :writes (fn [p] (spit p (pr-str a-baseline)))}
                            (ctx :findings [a-finding]))]
    (is (= :amend-invalid (:status out)))
    (is (str/includes? (str (:amend-error out)) "schema said no"))))

(deftest the-amender-is-told-to-leave-unchallenged-claims-alone
  ;; The arms race this ends, watched across five rounds: each amendment wrote
  ;; sharper claims to satisfy the last finding, and a sharper claim is a bigger
  ;; target. Findings went 7, 4, 1, 1, 3 — never to zero, because every round
  ;; created new refutable surface nobody had asked for.
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding] :out-path "/x"})]
    (is (str/includes? p "CHANGE ONLY WHAT WAS REFUTED"))
    (is (str/includes? p "must come\nback unchanged"))
    (is (str/includes? p "A sharper claim is a bigger target")
        "the reason, because a rule without one gets reasoned around")
    (is (str/includes? p "stays true as the code moves")
        "and what to prefer when a claim does have to change")))

;; ── What an earlier round already settled ───────────────────────────────────

(deftest confirmations-travel-to-the-next-judge
  ;; Measured on a frozen run: three of five findings in one round were against
  ;; records byte-identical to what the previous round had CONFIRMED. Improving
  ;; the record could never have fixed that, because the record was already right.
  (let [seen (atom nil)]
    (with-redefs [record/baseline-review! (fn [opts] (reset! seen opts)
                                            {:format :baseline-review :verdict :sufficient :reason "ok"})
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)]
      (run record/judge-stage
           (ctx :history [{:confirmed ["the aggregate is the only summing path"]}
                          {:confirmed ["a total is derived, never stored"
                                       "the aggregate is the only summing path"]}]))
      (is (= ["the aggregate is the only summing path" "a total is derived, never stored"]
             (:confirmed @seen))
          "every distinct confirmation, once"))))

(deftest a-round-records-what-it-confirmed
  ;; By id, and only ids the survey actually contains — a confirmation naming
  ;; nothing confirms nothing, and must not accumulate in the list later rounds
  ;; are shown.
  (let [corrected (assoc a-baseline :area "corrected")
        real-id   (:id (first (:load-bearing a-baseline)))
        [out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record corrected})))}
                            (ctx :findings [a-finding]
                                 :record {:verdict :falsified
                                          :confirmed [real-id "a sentence about nothing"]}))]
    (is (= [real-id] (:confirmed (first (:history out))))
        "or nothing travels and the next round may re-litigate it silently")))

(deftest the-judge-may-still-withdraw-a-confirmation-but-must-say-so
  (let [p (record/baseline-prompt {:baseline {:format :baseline}
                                   :confirmed ["a claim that held"]})]
    (is (str/includes? p "ALREADY CONFIRMED"))
    (is (str/includes? p "You may still refute one"))
    (is (str/includes? p "cannot converge"))
    (is (str/includes? p "Spend your effort on what is NOT in this list"))))

(deftest a-confirmation-naming-nothing-in-the-survey-is-dropped
  ;; Watched live: a judge asked for ids answered with "design" and
  ;; "implementation" — health-observation AXIS values, not ids. Kept, those
  ;; accumulate in the list every later round is shown, and a list that is partly
  ;; ids and partly not is the prose problem creeping back one entry at a time.
  (let [rec {:load-bearing [{:id "real-claim"}] :modules [{:id "real-module"}]
             :health [{:id "real-health"}]}]
    (is (= ["real-claim" "real-module" "real-health"]
           (record/confirmed-in rec ["real-claim" "design" "real-module"
                                     "implementation" "real-health" "real-claim"]))
        "known ids only, deduplicated, order preserved")))
