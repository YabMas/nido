;; test/nido/review/design_pipeline_test.clj
(ns nido.review.design-pipeline-test
  "The decision round driven as a loop. Its terminal state is an ESCALATION, not
   a convergence — everything derivable is derived so that what reaches a human
   is only the judgement that cannot be. Both agents are seams."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as jio]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [nido.platform.core :as core]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.report :as report]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as ws]
   [nido.design.check :as design-check]
   [nido.review.loop :as rloop]
   [nido.review.record :as record]
   [nido.review.settled :as settled]
   [nido.review.stages :as stages]
   [nido.review.tree :as tree]))

(defn- with-tmp-nido-root
  "Every stage here writes where the real stage writes — run dirs, answer files
   — so without this the suite scatters run directories through the user's live
   ~/.nido/runs/. Redirecting the root is the fix rather than stubbing the calls
   that bite, because the hazard is structural: the next side effect a stage
   grows would land there too."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
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
;; Three of the four derivations are made AGAINST the baseline, so a baseline nobody
;; verified makes them worthless in a way the round cannot see from inside: its
;; only honest exit is :resurvey. The first workstream to run this loop took that
;; exit seven times out of seven and never reached a decision, paying for a full
;; derivation each time to rediscover something already legible in the ledger.


;; The join itself now lives in `standing`, which asks two questions where this
;; asked one — verified, and not retracted since — and is tested there against a
;; real ledger. What is left here is the adaptation: the gate asks, and reports
;; what it is told in the shape a round's outcome takes.

(defn- gate-says [st]
  (with-redefs [standing/of-design (constantly st)]
    (record/unverified-premise :nido "ws-1" a-design)))

(deftest an-undecidable-premise-becomes-the-round-s-outcome
  (let [out (gate-says {:decidable? false
                        :blocked {:reason :premise-unverified :seq 1
                                  :detail "the design cites the baseline at entry 1, and no round has found that baseline sufficient"}})]
    (is (= :premise-unverified (:outcome out)))
    (is (str/includes? (:detail out) "entry 1")
        "which baseline went unverified is the whole of what the reader has to act on")))

(deftest a-retracted-premise-keeps-its-own-name
  ;; Not collapsed to :premise-unverified. A baseline nobody checked and a baseline
  ;; somebody found false want different things from a reader, and the remedy
  ;; line is chosen by this keyword.
  (let [out (gate-says {:decidable? false
                        :blocked {:reason :premise-retracted :seq 9
                                  :detail "the baseline at entry 1 was retracted by entry 9"}})]
    (is (= :premise-retracted (:outcome out)))
    (is (str/includes? (:detail out) "entry 9"))))

(deftest a-decidable-premise-does-not-gate
  (is (nil? (gate-says {:decidable? true})))
  (is (nil? (gate-says {:decidable? true :approved-by nil}))
      "an unapproved design is decidable — approval comes after this round, and a
       gate that wanted it first would make the round unreachable"))

(deftest a-design-citing-no-baseline-is-not-gated
  (with-redefs [standing/of-design (fn [& _] (throw (ex-info "must not be asked" {})))]
    (is (nil? (record/unverified-premise :nido "ws-1" (dissoc a-design :baseline)))
        "there is no premise to verify, and the prompt says so")))

(deftest the-gate-is-read-before-a-judge-is-spent
  (let [launched (atom 0)
        run (fn [entries]
              (reset! launched 0)
              (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                            ws/latest-entry (fn [_ _ k] (when (= :design k) a-design))
                            standing/of-design (constantly {:decidable? (boolean (seq entries))
                                                            :blocked {:reason :premise-unverified
                                                                      :detail "stub"}})
                            stages/read-stance (constantly nil)
                            record/discover-intent (constantly nil)
                            record/run-round! (fn [_] (swap! launched inc)
                                                {:outcome :no-output :detail "stub"})]
                (record/design-decision! {:cwd "/w" :run-id "r1" :label "l"})))]
    (is (= :premise-unverified (:outcome (run []))))
    (is (zero? @launched)
        "the answer is in nido's own ledger; paying an agent to rediscover it is the defect")
    ;; The control, and it is what makes the assertion above mean anything: with
    ;; the baseline verified the same call DOES reach the judge, so a zero count is
    ;; the gate refusing rather than the seam failing to bite.
    (is (= :no-output (:outcome (run [{:format :baseline-review
                                       :verdict :sufficient :baseline-seq 1}]))))
    (is (= 1 @launched))))

(def ^:private a-model-design
  (-> a-design
      (dissoc :invariants)
      (assoc :model {:elements [{:id "canvas.order/aggregate" :sort :module}]
                     :claims   [{:id "rounded-once" :about ["canvas.order/aggregate"]
                                 :statement "a total is rounded exactly once"
                                 :falsified-by "a total rounded twice"
                                 :evidence {:by :round}}]})))

(deftest a-design-claim-about-nothing-declared-spends-no-judge
  (let [launched (atom 0)
        seen     (atom nil)
        run (fn [rows]
              (reset! launched 0)
              (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                            ws/latest-entry (fn [_ _ k] (when (= :design k) a-model-design))
                            standing/of-design (constantly {:decidable? true})
                            stages/read-stance (constantly nil)
                            stages/discover-baseline (constantly nil)
                            record/discover-intent (constantly nil)
                            design-check/elements (fn [_ worktree]
                                                    (reset! seen worktree)
                                                    {:status :listed :elements rows})
                            record/run-round! (fn [_] (swap! launched inc)
                                                {:outcome :no-output :detail "stub"})]
                (record/design-decision! {:cwd "/w" :code-cwd "/tree" :run-id "r1" :label "l"})))]
    (let [out (run [])]
      (is (= :subjects-undeclared (:outcome out)))
      (is (str/includes? (:detail out) "canvas.order/aggregate"))
      (is (= "/tree" @seen) "resolved at the tree carrying the design's own declaration")
      (is (zero? @launched)))
    (is (= :no-output (:outcome (run [{:id "canvas.order/aggregate"
                                       :sort :fukan.common.vocab.code.module/Module}]))))
    (is (= 1 @launched) "the control: every subject declared, and the judge is reached")))

(deftest a-design-declaring-it-moves-nothing-still-reaches-the-round
  ;; The declarations decide whether a person's grant is additionally owed,
  ;; never whether the round runs — and only a proceeding round writes the
  ;; clearance such a design needs. Skipping the round left exactly those designs
  ;; with nothing that could ever clear them.
  (let [launched (atom 0)
        modest   (assoc a-design :standing {:relation :conforms}
                        :baseline {:seq 1 :relation :within} :effort :S)]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ k] (when (= :design k) modest))
                  standing/of-design (constantly {:decidable? true})
                  stages/discover-baseline (fn [_ _] nil)
                  stages/read-stance (constantly nil)
                  record/discover-intent (constantly nil)
                  record/run-round! (fn [_] (swap! launched inc)
                                      {:outcome :no-output :detail "stub"})]
      (is (= :no-output (:outcome (record/design-decision!
                                   {:cwd "/w" :run-id "r1" :label "l"}))))
      (is (= 1 @launched)))))

(deftest a-decision-round-asks-for-every-relation-id-before-it-is-recorded
  ;; Seen live: a judge shown claims, modules and strata returned rulings on the claims alone, two
  ;; runs running, and the lineage could never proceed. The schema now names every id, and what a
  ;; judge still skips is asked again inside the round rather than recorded :unruled.
  (let [calls    (atom [])
        baseline {:format :baseline :seq 1
                  :load-bearing [{:id "p" :property "one rounding boundary"}]
                  :modules [{:id "m-rounding" :module "rounding" :hides "h" :interface "i"}]}
        answers  [{:recommend "proceed" :reason "r" :asks "worth it?"
                   :checks [{:check "relation_honest" :status "held" :note "n"}]
                   :findings [] :confirmed [] :unchecked []
                   :relation_rulings [{:id "p" :ruling "breaks" :reason "moves"}]}
                  {:relation_rulings [{:id "m-rounding" :ruling "stands" :reason "kept"}]}]
        out      (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                               ws/latest-entry (fn [_ _ k] (when (= :design k) a-design))
                               standing/of-design (constantly {:decidable? true})
                               stages/discover-baseline (fn [_ _] baseline)
                               stages/read-stance (constantly nil)
                               record/discover-intent (constantly nil)
                               record/run-round! (fn [opts]
                                                   (let [n (count (swap! calls conj opts))]
                                                     {:ok (json/generate-string (answers (dec n)))}))]
                   (record/design-decision! {:cwd "/w" :run-id "r1" :label "l"}))
        enum-of  #(get-in (json/parse-string (:schema %) true)
                          [:properties :relation_rulings :items :properties :id :enum])]
    (is (= [["p" "m-rounding"] ["m-rounding"]] (mapv enum-of @calls))
        "the decision is shaped to rule on every id, and the re-ask on only the one it skipped")
    (is (= ["p" "m-rounding"] (mapv :id (:relation-rulings out))))
    (is (empty? (:unruled out)) "an id ruled on the second ask does not stop the proceed")))

(deftest a-round-holds-a-reversal-on-an-unmoved-record-to-the-last-ruling
  ;; Seen live: rulings re-derived cold each round flipped on text no amendment touched, and each
  ;; flip was enforced against :breaks as an amend round the next flip undid.
  (let [calls    (atom [])
        baseline {:format :baseline :seq 1
                  :load-bearing [{:id "p" :property "one rounding boundary"}]
                  :modules [{:id "m-rounding" :module "rounding" :hides "h" :interface "i"}]}
        design   (assoc a-design :seq 5)
        last     {:format :design-decision :seq 6 :design-seq 5 :recommend :proceed
                  :relation-rulings [{:id "p" :ruling :breaks :reason "moves"}
                                     {:id "m-rounding" :ruling :stands :reason "kept"}]}
        answer   {:recommend "proceed" :reason "r" :asks "worth it?"
                  :checks [{:check "relation_honest" :status "held" :note "n"}]
                  :findings [] :confirmed [] :unchecked []
                  :relation_rulings [{:id "p" :ruling "breaks" :reason "moves" :cause ""}
                                     {:id "m-rounding" :ruling "breaks" :reason "its interface moves"
                                      :cause ""}]}
        out      (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                               ws/latest-entry (fn [_ _ k] (case k :design design :design-decision last nil))
                               ws/entry-at-seq (fn [_ _ n] (when (= 5 n) design))
                               standing/of-design (constantly {:decidable? true})
                               stages/discover-baseline (fn [_ _] baseline)
                               stages/read-stance (constantly nil)
                               record/discover-intent (constantly nil)
                               record/run-round! (fn [opts]
                                                   (swap! calls conj opts)
                                                   {:ok (json/generate-string answer)})]
                   (record/design-decision! {:cwd "/w" :run-id "r1" :label "l"}))]
    (is (str/includes? (:prompt (first @calls)) "[m-rounding] stands — (unmoved) kept")
        "the judge was shown the ruling it reversed")
    (is (= [{:id "m-rounding" :ruling :breaks :reason "its interface moves"}] (:relation-reversals out)))
    (is (= :stands (:ruling (second (:relation-rulings out)))))
    (is (= :proceed (:recommend out)) "the judge's variance did not become an amend round")
    (is (= 1 (count @calls)) "the held check now agrees with the rulings, so nothing is re-asked")
    (is (report/validate-event :design-decision out) "and the ledger takes the decision")))

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

(deftest a-design-finding-against-a-claim-is-told-apart-by-the-claims-id
  (let [against #(assoc (check :goal-served :broken) :claim-ids %)]
    (testing "one check broken against another claim is another finding, not a stall"
      (is (not= (record/design-finding-base-key (against ["merge-combines-by-id"]))
                (record/design-finding-base-key (against ["fork-writes-nothing-on-parent"])))))
    (testing "the same claims in another order, or named twice, are the same finding"
      (is (= (record/design-finding-base-key (against ["a" "b"]))
             (record/design-finding-base-key (against ["b" "a" "a"]))))))
  (testing "the judge stage hands a broken check only the claim ids of the findings naming it"
    (with-redefs [record/design-decision! (fn [_] (decision :amend
                                                            :findings [{:cites ["c"] :claim "x" :check :relation-honest
                                                                        :claim-id "merge-combines-by-id"}
                                                                       {:cites ["d"] :claim "y" :check :goal-served
                                                                        :claim-id "fork-writes-nothing-on-parent"}]))
                  record/append! (fn [_ _] nil)]
      (let [out (run record/design-judge-stage (ctx))]
        (is (= [["merge-combines-by-id"]]
               (mapv :claim-ids (filter :check (:findings out))))
            "a claim found against another check moves this finding's identity not at all")))))

(deftest a-finding-that-breaks-no-check-is-told-apart-by-its-claim
  ;; :amend is "a derivable defect in the record itself" and :resurvey is "the premise is
  ;; wrong" — neither has a check a finding could name — so the claim is the only handle
  ;; such a finding has, and without one it could be neither disputed nor called stalled.
  (let [about #(hash-map :claim-ids %)]
    (is (not= (record/design-finding-base-key (about ["consistency-reported"]))
              (record/design-finding-base-key (about ["reconnect-invisible"]))))
    (is (not= (record/design-finding-base-key (about ["consistency-reported"]))
              (record/design-finding-base-key
               (assoc (check :goal-served :broken) :claim-ids ["consistency-reported"])))
        "the same claim under a broken check is a different finding: one names a derivation"))
  (testing "the judge stage keys a check-less finding on the claim it is about"
    (with-redefs [record/design-decision!
                  (fn [_] (decision :amend
                                    :checks [(check :goal-served :held)]
                                    :findings [{:cites ["c"] :claim "z" :claim-id "consistency-reported"}]))
                  record/append! (fn [_ _] nil)]
      (let [out (run record/design-judge-stage (ctx))]
        (is (= [[:check nil :claims ["consistency-reported"]]]
               (mapv record/design-finding-base-key (:findings out))))))))

(deftest a-finding-about-no-claim-is-told-apart-by-the-code-it-cites
  ;; A goal the record does not serve names no claim, so on the check alone every such finding
  ;; shared one handle: unrelated goal gaps pooled their disputes and their give-up count, and two
  ;; rounds holding different ones read as a stall.
  (let [gap #(assoc (check :goal-served :broken) :evidence %)]
    (is (not= (record/design-finding-base-key (gap ["src/queue.clj:40"]))
              (record/design-finding-base-key (gap ["src/view.clj:12"]))))
    (is (= (record/design-finding-base-key (gap ["b:2" "a:1"]))
           (record/design-finding-base-key (gap ["a:1" "b:2" "a:1"]))))
    (is (= (record/design-finding-base-key (assoc (gap ["src/queue.clj:40"]) :claim-ids ["c"]))
           (record/design-finding-base-key (assoc (gap ["src/view.clj:12"]) :claim-ids ["c"])))
        "a claim is still the whole identity of a finding that names one"))
  (testing "the judge stage hands a broken check the evidence of the findings filed under it"
    (with-redefs [record/design-decision!
                  (fn [_] (decision :amend :checks [(check :goal-served :broken)]
                                    :findings [{:cites ["c"] :claim "x" :check :goal-served
                                                :evidence ["src/queue.clj:40"]}
                                               {:cites ["d"] :claim "y" :check :goal-served
                                                :evidence ["src/view.clj:12"]}]))
                  record/append! (fn [_ _] nil)]
      (is (= [[:check :goal-served :claims [] :evidence ["src/queue.clj:40" "src/view.clj:12"]]]
             (mapv record/design-finding-base-key (:findings (run record/design-judge-stage (ctx)))))))))

(deftest a-design-claim-refuted-again-elsewhere-after-an-amend-is-movement
  (let [against (fn [ev] (assoc (check :goal-served :broken) :claim-ids ["one-rendering"] :evidence ev))
        prior   [{:iter 1 :amended? true :findings [(against ["standing.clj:425"])]}]]
    (is (true? (record/design-round-changed? {:iter 2 :findings [(against ["pipeline.clj:544"])]} prior)))
    (is (false? (record/design-round-changed? {:iter 2 :findings [(against ["standing.clj:425"])]} prior)))))

;; ── The judge stage ─────────────────────────────────────────────────────────

(deftest proceed-escalates-because-the-ask-is-the-point
  (with-redefs [record/design-decision! (fn [_] (decision :proceed))
                record/append! (fn [_ _] nil)]
    (let [out (run record/design-judge-stage (ctx))]
      (is (= :escalate (:control out)))
      (is (= :proceed (:status out))))))

(deftest a-proceed-over-unruled-claims-is-asked-again-then-refused
  ;; A proceed reached the person with claims the judge was handed and never ruled on, and nothing
  ;; recorded that they were never checked. Asked once more; then the run ends :unruled, and the
  ;; decision does not proceed on any reader's reading of it.
  (let [appended (atom [])]
    (with-redefs [record/design-decision! (fn [_] (assoc (decision :proceed :checks [(check :relation-honest :held)])
                                                        :unruled ["lines-exact"]))
                  record/append! (fn [_ r] {:seq (count (swap! appended conj r))})]
      (let [first (run record/design-judge-stage (ctx))]
        (is (= :next-round (:control first)))
        (is (nil? (:status first)))
        (is (= 1 (count @appended)) "the round is on the ledger all the same")
        (let [second (run record/design-judge-stage (ctx :carry (:carry first)))]
          (is (= :unruled (:status second)))
          (is (= :escalate (:control second))))))))

(deftest a-judged-round-names-the-entry-its-decision-became
  ;; The seq is read off the append that wrote it and nowhere else: the newest entry once the lock
  ;; is released may be another writer's.
  (with-redefs [record/design-decision! (fn [_] (decision :amend
                                                          :findings [{:cites ["c"] :claim "x"
                                                                      :check :relation-honest}]))
                record/append! (fn [_ _] {:seq 17})]
    (is (= 17 (:appended-seq (run record/design-judge-stage (ctx))))))
  (with-redefs [record/design-decision! (fn [_] (decision :amend
                                                          :findings [{:cites ["c"] :claim "x"
                                                                      :check :relation-honest}]))
                record/append! (fn [_ _] nil)]
    (is (not (contains? (run record/design-judge-stage (ctx)) :appended-seq))
        "a round whose append was lost names no entry rather than a wrong one")))

(deftest a-clearance-the-ledger-kept-refusing-is-not-an-ask
  ;; Contention is not a grant being owed. Escalating as :proceed would park a
  ;; design whose declarations owe nobody on interference alone.
  (with-redefs [record/design-decision! (fn [_] (decision :proceed))
                record/append! (fn [_ _] {:contended true})]
    (let [out (run record/design-judge-stage (ctx))]
      (is (= :clearance-contended (:status out)))
      (is (not= :escalate (:control out))))))

(deftest a-clearance-still-owed-is-never-a-person-s-gate
  ;; Contention is one way a clearance goes unwritten; a write that threw inside
  ;; `append!` is another, and it answers nil like a design owing a person. Both
  ;; are a write the clearance stage makes, and a design that no longer stands
  ;; goes where standing says — neither parks as the round's ask.
  (let [modest   (assoc a-design :standing {:relation :conforms}
                        :baseline {:seq 1 :relation :within})
        run-with (fn [design st]
                   (with-redefs [record/design-decision! (fn [_] (decision :proceed))
                                 record/append! (fn [_ _] nil)
                                 stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                                 ws/entries-of (constantly [])
                                 ws/entry-at-seq (constantly design)
                                 standing/of-design (constantly st)]
                     (run record/design-judge-stage (ctx))))]
    (testing "owing nobody and still standing: the clearance stage takes it"
      (let [out (run-with modest {:decidable? true})]
        (is (= :clearance-contended (:status out)))
        (is (not= :escalate (:control out)))))
    (testing "owing nobody and no longer standing: standing's own reason"
      (let [out (run-with modest {:decidable? false
                                  :blocked {:reason :premise-retracted :detail "d"}})]
        (is (= :premise-retracted (:status out)))
        (is (not= :escalate (:control out)))))
    (testing "and a design that owes a person is still the round's ask"
      (let [out (run-with a-design {:decidable? true})]
        (is (= :proceed (:status out)))
        (is (= :escalate (:control out)))))))

(defn- clearance-ledger
  "A real workstream on disk, built up to a design with the given `standing`,
   plus a thunk appending `decision` (default: a clean :proceed) over it.

   Every entry goes through `ws/append-entry!`, so the append boundary's own
   checks run here as they run in production — which is the whole reason to pay
   for a ledger rather than stub `record/append!`. The boundary is a reader of
   the clearance answer, and a stub is how one that disagreed with
   `report/proceeds?` sat behind a green suite."
  ([standing] (clearance-ledger standing nil))
  ([standing decision]
   (let [id   (:id (ws/create! :brian {:stage :in-progress :external-refs []}))
         add! (fn [kind record]
                (ws/append-entry! :brian id {:kind kind} (pr-str record))
                (count (:entries (ws/read-ws :brian id))))
         _    (add! :intent {:format :intent :goal "g" :done-when ["d"]})
         b    (add! :baseline
                    {:format :baseline :strata [] :intent {:seq 1}
                     :area "a" :bounded-by "b" :shape "s"
                     :model {:elements [{:id "m" :sort :module :hides "h" :interface "i"}]
                             :claims [{:id "c1" :about ["m"] :statement "p" :falsified-by "f"
                                       :evidence {:by :round} :read-at ["src/a.clj:1"]}]}
                     :read ["src/a.clj"]})
         _    (add! :baseline-review {:format :baseline-review :verdict :sufficient
                                      :baseline-seq b :reason "holds"})
         d    (add! :design {:format :design :strata [] :summary "s" :shape "sh"
                             :model {:elements [{:id "m" :sort :module}]
                                     :claims [{:id "one-path" :about ["m"] :statement "one path"
                                               :falsified-by "a second path"
                                               :evidence {:by :round}}]}
                             :standing standing
                             :baseline {:seq b :relation :within}
                             :intent {:seq 1} :effort :S})]
     [id #(add! :design-decision
                (assoc (or decision
                           {:recommend :proceed
                            :checks [{:check :relation-honest :status :held :note "n"}]})
                       :format :design-decision :design-seq d
                       :reason "r" :asks "worth it?"))])))

(defn- clearances [id] (count (ws/entries-of :brian id :design-cleared)))

(deftest the-clearance-stage-writes-the-clearance-and-runs-no-round
  ;; The round that ended :clearance-contended already appended its decision, so
  ;; what is left is one write, made as often as it takes. Nothing here may
  ;; re-run the round, turn the write into a grant, or clear a design that no
  ;; proceeding decision names.
  (with-redefs [record/run-round! (fn [_] (throw (ex-info "no round may run" {})))]
    (testing "a design owing nobody"
      (let [[id decide!] (clearance-ledger {:relation :conforms})]
        (with-redefs [stages/project+ws-from-cwd (fn [_] [:brian id])]
          (is (= :nothing-to-clear (record/clear! "/w")) "no decision, nothing to clear")
          (decide!)
          (is (= :cleared (record/clear! "/w")))
          (is (= 1 (clearances id)))
          (is (= :cleared (record/clear! "/w")) "asked again, it answers")
          (is (= 1 (clearances id)) "without writing a second clearance"))))
    (testing "a design owing a person is not the clearance stage's to clear"
      (let [[id decide!] (clearance-ledger {:relation :challenges :note "n"})]
        (with-redefs [stages/project+ws-from-cwd (fn [_] [:brian id])]
          (decide!)
          (is (= :nothing-to-clear (record/clear! "/w")))
          (is (zero? (clearances id))))))))

(deftest an-advisory-only-decision-clears-through-the-real-append-boundary
  ;; The append boundary is a reader of the clearance answer too, and the one a
  ;; stubbed `record/append!` never reaches. Asking for a literal :proceed there
  ;; left a conforming design whose round complained only about the cut with no
  ;; way out at all: the boundary refused the clearance, and the gate offers no
  ;; grant to a design owing nobody.
  (doseq [r [:amend :recut]]
    (let [[id decide!] (clearance-ledger
                        {:relation :conforms}
                        {:recommend r
                         :checks [{:check :decomposable :status :broken :note "n"}]
                         :findings [{:cites ["src/a.clj:1"] :claim "the cut is wrong"}]})]
      (with-redefs [record/run-round! (fn [_] (throw (ex-info "no round may run" {})))
                    stages/project+ws-from-cwd (fn [_] [:brian id])]
        (decide!)
        (is (= :cleared (record/clear! "/w")) (str "recommended " r))
        (is (= 1 (clearances id)) "and the clearance is on the ledger")))))

(deftest clearance-gives-up-with-an-answer-rather-than-silence
  (let [writes (atom 0)]
    (with-redefs [ws/read-ws (constantly {:entries []})
                  ws/entry-at-seq (constantly (assoc a-design
                                                     :standing {:relation :conforms}
                                                     :baseline {:seq 1 :relation :within}))
                  standing/of-design (constantly {:decidable? true})
                  ws/append-entry-at! (fn [& _] (swap! writes inc) {:refused :stale})]
      (binding [*err* (java.io.StringWriter.)]
        (is (= :contended (@#'record/clear-if-owed-nobody! :nido "ws-1" 4))))
      (is (< 100 @writes) "every refusal was asked again before it gave up"))))

(deftest a-layering-complaint-alone-does-not-hold-a-design-round
  ;; Layers do not survive: the stack is collapsed into one commit before it
  ;; lands, so a bad cut costs the attention of the reviewers reading it now and
  ;; nothing afterwards. `decomposable` was 143 of 357 findings and the sole
  ;; complaint in 41 of 193 finding-bearing rounds before this guard existed.
  (testing "whatever the judge recommended, a decomposable-only break proceeds"
    (doseq [r [:amend :recut]]
      (with-redefs [record/design-decision!
                    (fn [_] (decision r :checks [(check :decomposable :broken)
                                                 (check :goal-served :held)]))
                    record/append! (fn [_ _] nil)]
        (let [out (run record/design-judge-stage (ctx))]
          (is (= :proceed (:status out)) (str "recommended " r))
          (is (= :escalate (:control out)))
          (is (empty? (:findings out))
              "nothing is handed to an amender")))))
  (testing "and the complaint still reaches the human on the record"
    (with-redefs [record/design-decision!
                  (fn [_] (decision :amend :checks [(check :decomposable :broken)]))
                  record/append! (fn [_ _] nil)]
      (let [out (run record/design-judge-stage (ctx))]
        (is (= [:broken] (mapv :status (filter #(= :decomposable (:check %))
                                               (:checks (:record out)))))
            "the broken check is not quietly flipped to :held")))))

(deftest a-commitment-complaint-still-blocks-even-beside-a-layering-one
  ;; The guard is for the ONE check about packaging. As soon as a check about
  ;; what the change commits to breaks, the round routes to the amender as
  ;; before and the layering rides along with it.
  (with-redefs [record/design-decision!
                (fn [_] (decision :amend :checks [(check :decomposable :broken)
                                                  (check :goal-served :broken)]))
                record/append! (fn [_ _] nil)]
    (let [out (run record/design-judge-stage (ctx))]
      (is (not= :proceed (:status out)))
      (is (= [:decomposable :goal-served] (sort (mapv :check (:findings out))))))))

(deftest a-clean-round-is-not-the-advisory-case
  ;; `every?` over an empty sequence is true, so a round with nothing broken
  ;; would otherwise take the guard's branch rather than its own recommendation.
  (is (false? (report/proceeds? (decision :amend :checks [(check :goal-served :held)]))))
  (is (true?  (report/proceeds? (decision :amend :checks [(check :decomposable :broken)]))))
  (is (false? (report/proceeds? (decision :amend :checks [(check :decomposable :broken)
                                                          (check :goal-served :broken)])))))

(deftest an-advisory-only-decision-on-a-design-owing-nobody-is-cleared-not-parked
  ;; The judge treats it as proceeding, so the clearance path has to as well —
  ;; testing the recorded :recommend instead parked it for a person whose grant
  ;; `grantable?`, reading the same :amend, would then refuse.
  (let [modest (assoc a-design :standing {:relation :conforms}
                      :baseline {:seq 1 :relation :within})]
    (with-redefs [record/design-decision!
                  (fn [_] (decision :amend :checks [(check :decomposable :broken)]))
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/entries-of (constantly [])
                  ws/entry-at-seq (constantly modest)
                  standing/of-design (constantly {:decidable? true})]
      (let [out (run record/design-judge-stage (ctx))]
        (is (= :clearance-contended (:status out))
            "owed nobody, so what is owed is the clearance write")
        (is (not= :escalate (:control out)))))))

(deftest a-held-check-is-never-what-a-round-hands-over
  ;; A check that held and a check with no yardstick are both things an amender must not
  ;; be sent to repair. What it IS sent is every finding the round made.
  (with-redefs [record/design-decision!
                (fn [_] (decision :amend :checks [(check :relation-honest :broken)
                                                  (check :goal-served :held)
                                                  (check :decomposable :underivable)]
                                  :findings [{:cites ["c"] :claim "x" :check :relation-honest}]))
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
                                  :findings [{:cites ["c"] :claim "x" :check :relation-honest}]))
                record/append! (fn [_ _] {:seq 1})]
    (let [out (run record/design-judge-stage (ctx))]
      (is (= :underivable (:status out)))
      (is (= :escalate (:control out)))
      (is (= [] (:findings out))))))

(deftest a-defect-under-no-check-reaches-the-amender-rather-than-a-human
  ;; Six design runs ended :underivable holding a real, repairable record defect and no
  ;; underivable check. Reading "nothing broke" as "nothing to repair" is what did it: an
  ;; amend or a resurvey has no check its findings could name, so those findings named
  ;; none — and one of the six forced a hand-written design that no amender and no retreat
  ;; check ever saw.
  (doseq [r [:amend :recut :resurvey]]
    (with-redefs [record/design-decision!
                  (fn [_] (decision r :checks [(check :goal-served :held)
                                               (check :routing-coherent :held)]
                                    :findings [{:cites ["canvas/claims.clj:47"] :claim "the record says both"
                                                :claim-id "lens-can-say-no-level"}]))
                  record/append! (fn [_ _] nil)]
      (let [out (run record/design-judge-stage (ctx))]
        (is (nil? (:status out)) (str "recommended " r ": another round, not a terminal"))
        (is (= ["lens-can-say-no-level"] (mapcat :claim-ids (:findings out)))
            "and the finding is what the amender is handed")))))

(deftest a-round-with-nothing-to-repair-and-no-missing-yardstick-says-so
  ;; :underivable is a claim about the CHECKS — one of them had no yardstick — so a round
  ;; with none of those may not end there. What is left when a round will not proceed,
  ;; breaks nothing, and makes no finding an amender could act on is the judge
  ;; contradicting itself, and a person is told that rather than told a yardstick is
  ;; missing.
  (with-redefs [record/design-decision!
                (fn [_] (decision :amend :checks [(check :goal-served :held)]))
                record/append! (fn [_ _] {:seq 1})]
    (let [out (run record/design-judge-stage (ctx))]
      (is (= :nothing-to-amend (:status out)))
      (is (= :escalate (:control out)))
      (is (= [] (:findings out))))))

(deftest a-finding-filed-under-a-check-the-round-held-is-carried-under-its-claim
  ;; The amender is handed the judge's raw findings and repairs this one whatever the round
  ;; carries, so a round that dropped it hid a repair from the report, the figures and every
  ;; stall and dispute identity — and a round whose only finding was one ended
  ;; :nothing-to-amend over a defect it had named.
  (with-redefs [record/design-decision!
                (fn [_] (decision :amend :checks [(check :goal-served :broken)
                                                  (check :stratified :held)]
                                  :findings [{:cites ["a"] :claim "x" :check :goal-served
                                              :claim-id "goal"}
                                             {:cites ["b"] :claim "y" :check :stratified
                                              :claim-id "stale-page-untouched"}]))
                record/append! (fn [_ _] nil)]
    (let [out  (run record/design-judge-stage (ctx))
          held (last (:findings out))]
      (is (nil? (:status out)) "there is something to repair, so the round goes on")
      (is (= [:goal-served nil] (mapv :check (:findings out)))
          "a held check is not a broken one: the amender is never told stratified failed")
      (is (= ["stale-page-untouched"] (:claim-ids held)))
      (is (= :stratified (:filed-under held)) "what the judge named still reaches the report")
      (is (= (record/design-finding-base-key {:claim-ids ["stale-page-untouched"]})
             (record/design-finding-base-key held))
          "its identity is its claim's, the same as a check-less finding about it")))
  (with-redefs [record/design-decision!
                (fn [_] (decision :amend :checks [(check :goal-served :held)]
                                  :findings [{:cites ["c"] :claim "x" :check :routing-coherent
                                              :claim-id "r"}]))
                record/append! (fn [_ _] nil)]
    (let [out (run record/design-judge-stage (ctx))]
      (is (nil? (:status out))
          "a round whose only finding names a held check has named a defect, not nothing")
      (is (= [["r"]] (mapv :claim-ids (:findings out)))))))

(deftest a-check-less-finding-is-handed-over-even-beside-a-missing-yardstick
  ;; The two are independent: a yardstick nobody could reach does not make the defect the
  ;; round DID find unrepairable, and the amender is handed the finding without being
  ;; asked to do anything about the check.
  (with-redefs [record/design-decision!
                (fn [_] (decision :amend :checks [(check :relation-honest :underivable)
                                                  (check :goal-served :held)]
                                  :findings [{:cites ["c"] :claim "x" :claim-id "benchmark-writes-only-its-own-state"}]))
                record/append! (fn [_ _] nil)]
    (let [out (run record/design-judge-stage (ctx))]
      (is (nil? (:status out)) "there is something to repair, so the round goes on")
      (is (= ["benchmark-writes-only-its-own-state"] (mapcat :claim-ids (:findings out))))
      (is (= [:relation-honest] (mapv :check (:underivable out)))
          "the missing yardstick still travels to the human the run ends at"))))

(deftest a-check-less-finding-restated-after-two-objections-goes-to-a-human
  ;; The appeal channel is what makes a finding answerable rather than merely visible, and
  ;; it runs on the identity key. A finding that had none could be disputed forever.
  (let [k (record/design-finding-base-key {:claim-ids ["consistency-reported"]})
        objection {:key k :claim "the record says both" :because "b"}]
    (with-redefs [record/design-decision!
                  (fn [_] (decision :amend :checks [(check :goal-served :held)]
                                    :findings [{:cites ["c"] :claim "the record says both"
                                                :claim-id "consistency-reported"}]))
                  record/append! (fn [_ _] {:seq 1})]
      (is (nil? (:status (run record/design-judge-stage
                              (ctx :history [{:disputes [objection]}])))))
      (is (= :disputed
             (:status (run record/design-judge-stage
                           (ctx :history (vec (repeat 2 {:disputes [objection]}))))))))))

(deftest a-check-restated-after-two-objections-goes-to-a-human
  (let [k (record/design-finding-base-key (check :relation-honest :broken))]
    (with-redefs [record/design-decision! (fn [_] (decision :amend))
                  record/append! (fn [_ _] {:seq 1})]
      (is (nil? (:status (run record/design-judge-stage
                              (ctx :history [{:disputes [{:key k :claim "c" :because "b"}]}])))))
      (is (= :disputed
             (:status (run record/design-judge-stage
                           (ctx :history (vec (repeat 2 {:disputes [{:key k :claim "c" :because "b"}]}))))))))))

(deftest a-round-that-could-not-run-keeps-its-own-name
  (doseq [outcome [:codex-failed :no-record :premise-unverified :unusable-answer]]
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
  "Run the design amend stage with every seam stubbed. `writes` stands in for the
   answer the amender left at the out-path; `moves` for the paths it wrote in the
   tree, each named in its transcript by an Edit; `declared` for the project's
   design configuration."
  [{:keys [prev writes recommend append-throws? refusals prompts moves declared]
    :or {prev a-design recommend :amend refusals 0}} c]
  (let [appended (atom nil)
        refused  (atom 0)
        state    (atom {:identity "t0" :entries {}})]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] prev)
                  stages/discover-baseline (fn [_ _] nil)
                  design-check/design-of (fn [_ _] declared)
                  stages/working-copy-state (fn [_] @state)
                  ws/append-entry! (fn [_ _ _ payload]
                                     (when (or append-throws? (< @refused refusals))
                                       (swap! refused inc)
                                       (throw (ex-info "schema said no" {})))
                                     (reset! appended payload)
                                     "/ws/entries/0005-design.edn")
                  agent/launch! (fn [{:keys [first-message out-file]}]
                                  (some-> prompts (swap! conj first-message))
                                  (when writes
                                    (writes (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))))
                                  (when (seq moves)
                                    (reset! state {:identity "t1" :entries (zipmap moves (repeat "new"))})
                                    (spit out-file
                                          (str/join "\n"
                                                    (for [m moves]
                                                      (json/generate-string
                                                       {:type "assistant"
                                                        :message {:content [{:type "tool_use" :name "Edit"
                                                                             :input {:file_path (str "/w/" m)}}]}})))))
                                  {:num-turns 3})]
      [(run record/design-amend-stage
            (assoc-in c [:record :recommend] recommend))
       @appended])))

(deftest the-canvas-edit-a-declared-design-requires-is-part-of-the-amendment
  ;; The element-id rule makes a moved element's id valid only once the canvas
  ;; lists it, so a faithful amendment in a modelled project edits canvas/ — and
  ;; the guard used to discard every one of them, leaving the canvas declaring an
  ;; amendment the ledger never received.
  (let [fixed (assoc a-design :effort :L)
        [out appended] (with-amend {:declared {:spec-dirs ["canvas"]}
                                    :moves ["canvas/review/core.clj"]
                                    :writes (fn [p] (spit p (pr-str {:record fixed})))}
                                   (ctx :findings [(check :relation-honest :broken)]
                                        :record (decision :amend)))]
    (is (nil? (:status out)) "the round goes on, and the next judge reads the canvas edit")
    (is (= fixed (read-string appended)))
    (is (= ["canvas/review/core.clj"] (get-in out [:amend-tree :permitted])))))

(deftest a-design-amender-writing-outside-the-declaration-is-still-caught
  (let [[out appended] (with-amend {:declared {:spec-dirs ["canvas"]}
                                    :moves ["canvas/review/core.clj" "src/nido/review/record.clj"]
                                    :writes (fn [p] (spit p (pr-str {:record a-design})))}
                                   (ctx :findings [(check :relation-honest :broken)]
                                        :record (decision :amend)))]
    (is (= :amend-touched-code (:status out)))
    (is (= ["src/nido/review/record.clj"] (get-in out [:amend-tree :attributed])))
    (is (nil? appended))))

(deftest a-project-declaring-no-design-permits-no-edit-at-all
  (let [[out _] (with-amend {:moves ["canvas/review/core.clj"]
                             :writes (fn [p] (spit p (pr-str {:record a-design})))}
                            (ctx :findings [(check :relation-honest :broken)]
                                 :record (decision :amend)))]
    (is (= :amend-touched-code (:status out)))))

(deftest the-design-amender-is-told-the-canvas-edit-is-its-to-make
  (let [p (record/design-amend-prompt {:design a-design :recommend :amend :raised []
                                       :out-path "/x" :declared? true})]
    (is (str/includes? p "change its declaration\nunder canvas/ to match")))
  (is (not (str/includes? (record/design-amend-prompt {:design a-design :recommend :amend
                                                       :raised [] :out-path "/x"})
                          "under canvas/"))
      "a project with no declaration is not told to edit one"))

(deftest a-superseding-design-is-appended-and-the-loop-continues
  (let [fixed (assoc a-design :invariants ["one rounding boundary" "totals never re-round"])
        [out appended] (with-amend {:writes (fn [p] (spit p (pr-str {:record fixed})))}
                                   (ctx :findings [(check :relation-honest :broken)]
                                        :record (decision :amend)))]
    (is (nil? (:status out)))
    (is (= fixed (read-string appended)))
    (is (= [] (:retreats out)))))

(deftest a-refused-design-amendment-is-handed-back-and-the-repair-appended
  ;; Seen live: an amender put :removed at the design's top level instead of under
  ;; :model, and the round lost its whole amendment to one disallowed key.
  (let [prompts (atom [])
        fixed   (assoc a-design :effort :L)
        [out appended] (with-amend {:refusals 1 :prompts prompts
                                    :writes (fn [p] (spit p (pr-str {:record fixed})))}
                                   (ctx :findings [(check :relation-honest :broken)]
                                        :record (decision :amend)))]
    (is (nil? (:status out)))
    (is (= 2 (count @prompts)))
    (is (str/includes? (second @prompts) "The ledger refused the design record"))
    (is (= (first @prompts)
           (slurp (str (fs/path (cstate/run-dir "r1") "design-amend-round-1-prompt.txt"))))
        "what the design amender was told is readable from the run, since its transcript omits it")
    (is (= fixed (read-string appended)))
    (is (= ["schema said no"] (:amend-refusals out)))))

(deftest a-design-the-ledger-keeps-refusing-ends-amend-invalid
  (let [[out appended] (with-amend {:append-throws? true
                                    :writes (fn [p] (spit p (pr-str {:record a-design})))}
                                   (ctx :findings [(check :relation-honest :broken)]
                                        :record (decision :amend)))]
    (is (= :amend-invalid (:status out)))
    (is (= "schema said no" (:amend-error out)))
    (is (nil? appended))))

;; ── What a design amendment cites ───────────────────────────────────────────

(deftest a-design-amendment-cites-the-design-it-corrects
  ;; The amender is shown the design unstamped, so it guessed this field — the
  ;; round's decision one run, the design's own stale citation the next — and the
  ;; ledger refused the first guess and forked the lineage on the second.
  (let [[_ appended] (with-amend {:prev (assoc a-design :seq 21)
                                  :writes (fn [p] (spit p (pr-str {:record a-design})))}
                                 (ctx :findings [(check :relation-honest :broken)]
                                      :record (decision :amend)))]
    (is (= 21 (get-in (read-string appended) [:supersedes :seq])))
    (is (str/includes? (get-in (read-string appended) [:supersedes :why]) "round 1"))))

(deftest a-citation-copied-off-the-design-is-replaced-and-its-reason-carried
  ;; Seen live: every amendment of one run cited the grandparent and repeated its
  ;; :why, so the ledger held no reason for any of them — and the author's :why
  ;; was the only record of the base revision the chain was surveyed at.
  (let [prev    (assoc a-design :seq 21 :supersedes {:seq 19 :why "surveyed at d045c958"})
        [_ app] (with-amend {:prev prev
                             :writes (fn [p] (spit p (pr-str {:record (dissoc prev :seq)})))}
                            (ctx :findings [(check :relation-honest :broken)]
                                 :record (decision :amend)))
        cited   (:supersedes (read-string app))]
    (is (= 21 (:seq cited)) "the design corrected, not its predecessor")
    (is (str/includes? (:why cited) "round 1"))
    (is (str/includes? (:why cited) "surveyed at d045c958")
        "the author's reason travels down the chain instead of being overwritten")))

(deftest a-reason-carried-twice-is-carried-once
  ;; A carried reason that nested another per amendment would grow without bound
  ;; across a run, which is the growth the amend prompts exist to stop.
  (let [at    {:iter 3 :run-id "r" :resolve (constantly nil)}
        once  (record/cite-corrected :design {:seq 5 :supersedes {:seq 4 :why "the pin"}} {} at)
        twice (record/cite-corrected :design (assoc once :seq 6) {} at)]
    (is (= (get-in once [:supersedes :why]) (get-in twice [:supersedes :why])))
    (is (= 6 (get-in twice [:supersedes :seq])))))

(deftest a-citation-of-no-design-is-replaced-and-the-authors-reason-kept
  (let [[_ appended]
        (with-redefs [ws/entry-at-seq (fn [_ _ n] (when (= 20 n) {:format :design-decision :seq 20}))]
          (with-amend {:prev (assoc a-design :seq 21)
                       :writes (fn [p] (spit p (pr-str {:record (assoc a-design :supersedes
                                                                         {:seq 20 :why "goal-served narrowed"})})))}
                      (ctx :findings [(check :relation-honest :broken)]
                           :record (decision :amend))))]
    (is (= {:seq 21 :why "goal-served narrowed"} (:supersedes (read-string appended))))))

(deftest a-refused-design-amendment-is-named-and-its-objections-kept
  ;; A round that ended on a refusal read `continued`, named no file, and dropped
  ;; the amender's disputes — so the one complete answer to the round, and the
  ;; objections the next reader most needs, were visible only in the run dir.
  (let [[out _] (with-amend {:append-throws? true
                             :writes (fn [p] (spit p (pr-str {:record a-design
                                                             :disputes [{:finding 1 :because "the code routes it"
                                                                         :evidence ["src/x.clj:4"]}]})))}
                            (ctx :findings [(check :relation-honest :broken)]
                                 :record (decision :amend)))]
    (is (= :amend-invalid (:status out)))
    (is (str/ends-with? (:amend-unappended out) "design-amend-round-1-reask-2.edn")
        "the last record the ledger refused, which is the one worth recovering by hand")
    (is (= 1 (count (:disputes out))))))

(deftest the-design-amender-is-told-the-laws-it-is-held-to
  (let [p (record/design-amend-prompt {:design (assoc a-design :seq 21 :model {:elements [] :claims []})
                                       :recommend :amend :raised [] :out-path "/x"
                                       :check-cmd "bb nido:review:amend:check :file /x"})]
    (is (str/includes? p ":revisit — :seq int?, :breaks [:vector {:min 1} string?], :note string?")
        "the relation shapes as the write schema states them, so a :revisit carries :breaks")
    (is (str/includes? p "for a moved module boundary, that module's\ninterface")
        "and what a moved boundary names there, which is what the judge calls :revisit")
    (is (str/includes? p "carries a :stratified/level reading"))
    (is (str/includes? p "bb nido:review:amend:check :file /x") "the check it can run first")
    (is (str/includes? p "LEAVE :supersedes :seq TO THE LOOP. It sets it to entry 21"))
    (is (str/includes? p "a number you guess names someone else's")
        "a follow-up ref it cannot file, it must not mint")))

(deftest the-design-amender-is-handed-a-check-of-its-own-answer
  (let [prompts (atom [])]
    (with-amend {:prompts prompts} (ctx :findings [(check :relation-honest :broken)]
                                        :record (decision :amend)))
    (is (re-find #"nido:review:amend:check :project nido :ws-id ws-1 :kind design :file \S+design-amend-round-1\.edn"
                 (first @prompts)))))

(deftest the-judge-says-what-a-moved-boundary-breaks
  ;; The judge called a moved boundary :revisit and the schema requires :revisit to
  ;; name what it breaks, so an amender following the judge wrote a record the
  ;; ledger refused, or softened it to :within to get it taken.
  (is (str/includes? (record/design-prompt {:design a-design}) "names under :breaks")))

(deftest the-design-amender-is-told-where-removals-go
  (let [p (record/design-amend-prompt {:design (assoc a-design :model {:elements [] :claims []})
                                       :recommend :amend :raised [] :out-path "/x"})]
    (is (str/includes? p "under :model :removed"))
    (is (str/includes? p "never at the record's top level")))
  (testing "and so is the repair of a refused one"
    (is (str/includes? (record/refusal-prompt {:kind :design
                                               :record (assoc a-design :model {:elements [] :claims []})
                                               :refusal "no" :out-path "/x"})
                       "under :model :removed"))))

(deftest a-design-amended-to-claim-it-moves-nothing-is-still-judged
  ;; Declaring :within, :conforms and a modest effort used to stop the loop here,
  ;; because the round would then have refused to run. The round is never
  ;; skipped now, so the amended design goes back to the judge — and the
  ;; weakening still reaches the human, on the retreats and the trajectory.
  (let [gutted (assoc a-design :baseline {:seq 1 :relation :within}
                      :standing {:relation :conforms} :effort :M)
        [out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record gutted})))}
                            (ctx :findings [(check :relation-honest :broken)]
                                 :record (decision :amend)))]
    (is (nil? (:status out)) "the loop continues to another round")
    (is (true? (:amended? out)))
    (is (contains? (set (map :what (:retreats out))) :effort-lowered))))

(deftest an-amendment-extending-breaks-shows-in-its-delta
  ;; Extending :breaks is how an amendment repairs a broken relation-honest. A delta that
  ;; omitted the citation reported that round as having moved nothing, so the report hid
  ;; the one repair the round was for.
  (let [extended (assoc-in a-design [:baseline :breaks] ["p" "q"])
        [out _]  (with-amend {:writes (fn [p] (spit p (pr-str {:record extended})))}
                             (ctx :findings [(check :relation-honest :broken)]
                                  :record (decision :amend)))]
    (is (= {:changed ["baseline"]} (:amend-delta out))
        "the citation's change is named, under the key the report reads"))
  (testing "and an amendment that leaves the citation alone does not name it"
    (let [reworded (assoc a-design :shape "one rounding boundary, at the invoice")
          [out _]  (with-amend {:writes (fn [p] (spit p (pr-str {:record reworded})))}
                               (ctx :findings [(check :relation-honest :broken)]
                                    :record (decision :amend)))]
      (is (= {:changed ["shape"]} (:amend-delta out))))))

(deftest recut-and-amend-are-given-different-jobs
  (let [prompts (atom [])]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-design)
                  stages/discover-baseline (fn [_ _] nil)
                  stages/working-copy-state (fn [_] {:identity "t" :entries {}})
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

(deftest the-round-s-refutation-count-reaches-the-design-amender
  ;; The judge stage counts it onto ctx; an amender that never sees it rewords a spent claim again.
  (let [prompt (atom nil)]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-design)
                  stages/discover-baseline (fn [_ _] nil)
                  stages/working-copy-state (fn [_] {:identity "t" :entries {}})
                  agent/launch! (fn [{:keys [first-message]}]
                                  (reset! prompt first-message) {:num-turns 1})]
      (run record/design-amend-stage
           (ctx :findings [{:claim-ids ["c1"] :claim "a second writer reorders"}]
                :record (decision :amend)
                :refuted-running {"c1" 3}))
      (is (str/includes? @prompt "A CLAIM NO REWORDING HAS SETTLED. [c1] has been refuted 3 readings running")))))

;; ── Resurvey: the loop that calls the other loop ────────────────────────────

(def ^:private corrected-baseline
  {:format :baseline :seq 11 :area "a" :bounded-by "b" :shape "s"
   :load-bearing [{:id "c1" :property "p" :evidence ["src/x.clj:1"]}] :read ["src/x.clj"]})

(defn- with-resurvey
  "Stub every seam the two-step re-survey touches. `amends` stands in for what
   the amender wrote after the nested loop came back."
  [{:keys [nested amends corrected out] :or {corrected corrected-baseline}} c]
  (let [prompt (atom nil) appended (atom nil)]
    ;; In :carry, where the engine actually leaves it. Stubbing the old
    ;; top-level shape here is why this test passed against an engine that
    ;; dropped the key every round: the stub asserted a contract nothing kept.
    (with-redefs [rloop/run-loop (fn [_] (or out {:status nested
                                                  :carry {:under-repair corrected}}))
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ kind]
                                    (if (= :baseline kind) corrected-baseline a-design))
                  stages/discover-baseline (fn [_ _] {:format :baseline :seq 8})
                  stages/working-copy-state (fn [_] {:identity "t" :entries {}})
                  ws/append-entry! (fn [_ _ _ payload] (reset! appended payload) "/e")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (reset! prompt first-message)
                                  (when amends
                                    (amends (second (re-find #"Write EDN to:\n\n  (\S+)"
                                                             first-message))))
                                  {:num-turns 3})]
      [(run record/design-amend-stage c) @prompt @appended])))

(deftest the-amender-is-told-the-number-it-is-asked-to-cite
  ;; The record below the instruction is printed unstamped — a :seq is the
  ;; ledger's to give and a record carrying one is refused on write — so an
  ;; amender told to cite the corrected baseline by :seq and shown no :seq
  ;; anywhere was being asked to guess the one field the ledger checks.
  (let [[_ prompt _]
        (with-resurvey {:nested :sufficient}
                       (assoc (ctx :findings [(check :relation-honest :broken)])
                              :record (decision :resurvey)))]
    (is (str/includes? prompt "Set :baseline :seq to 11"))))

(deftest an-unstamped-corrected-baseline-does-not-invent-a-number
  ;; Degraded read-back: better to say "the corrected baseline" than to name an
  ;; entry that is not the one on disk.
  (let [[_ prompt _]
        (with-resurvey {:nested :sufficient
                        :corrected (dissoc corrected-baseline :seq)}
                       (assoc (ctx :findings [(check :relation-honest :broken)])
                              :record (decision :resurvey)))]
    (is (not (str/includes? prompt "Set :baseline :seq to")))
    (is (str/includes? prompt "Point :baseline :seq at the corrected baseline"))))

(deftest the-design-amender-is-told-not-to-restate-what-held
  ;; Same churn guard the baseline amender has, and for a sharper reason: every
  ;; check is re-derived over the whole record, so a rewritten neighbour can
  ;; break a derivation that was passing.
  (let [[_ prompt _]
        (with-resurvey {:nested :sufficient}
                       (assoc (ctx :findings [(check :relation-honest :broken)])
                              :record (decision :amend)))]
    (is (str/includes? prompt "CHANGE ONLY WHAT WAS REFUTED"))))

(deftest a-resurvey-reads-what-a-baseline-round-would
  ;; Not the design round's tree: that carries the design's declaration over the
  ;; base, and a baseline describes the area before the change. A tree the caller
  ;; named travels as :survey-cwd and is read as given.
  (let [nested (atom []) asked (atom [])]
    (with-redefs [rloop/run-loop (fn [cfg] (swap! nested conj (:code-cwd cfg))
                                   {:status :sufficient :carry {:under-repair corrected-baseline}})
                  tree/reading   (fn [kind _ _] (swap! asked conj kind) {:rev "abc" :overlay []})
                  tree/with-reading! (fn [_ _ _ _ f] (f "/base-tree"))
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ kind] (if (= :baseline kind) corrected-baseline a-design))
                  stages/discover-baseline (fn [_ _] {:format :baseline :seq 8})
                  stages/working-copy-state (fn [_] {:identity "t" :entries {}})
                  agent/launch! (fn [_] {:num-turns 1})]
      (doseq [config [{:cwd "/w" :run-id "r1" :code-cwd "/design-tree"}
                      {:cwd "/w" :run-id "r1" :code-cwd "/named" :survey-cwd "/named"}]]
        (run record/design-amend-stage
             (assoc (ctx :findings [(check :relation-honest :broken)] :record (decision :resurvey))
                    :config config))))
    (is (= ["/base-tree" "/named"] @nested))
    (is (= [:baseline] @asked) "a named tree asks for no reading")))

(deftest a-resurvey-is-only-half-the-repair
  ;; The failure this catches: discover-baseline resolves the CITED baseline, so
  ;; repairing the latest one changes nothing the next round can see. The design
  ;; would be judged against the same stale baseline, reach the same verdict, and
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
    (testing "the amender sees the CORRECTED baseline, not the one that was wrong"
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
                  stages/working-copy-state (fn [_] {:identity "t" :entries {}})
                  agent/launch! (fn [_] {:num-turns 0})]
      (run record/design-amend-stage
           (assoc (ctx :findings []) :record (decision :resurvey)))
      (is (= {:format :baseline :seq 8} (:baseline @seen))
          "the nested loop repairs the baseline the design CITES, not the newest one")
      (is (= record/baseline-pipeline (:pipeline @seen)))
      (is (= record/baseline-finding-key (:finding-key @seen)))
      (is (fn? (:emit @seen)))
      (is (nil? ((:emit @seen) {:event :phase-started}))))))

(deftest a-resurvey-that-did-not-hold-is-terminal-here
  ;; A design round cannot proceed on a baseline the baseline loop could not make
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
  ;; corrected baseline afterwards, so every cycle changes the record the next
  ;; round judges. A count would stop the loop while it was still making
  ;; progress, which is the one thing a convergence loop must not do — the
  ;; engine's stall detector is what ends a run that has stopped getting
  ;; anywhere.
  (let [nested (atom 0)]
    (with-redefs [rloop/run-loop (fn [_] (swap! nested inc) {:status :sufficient})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-design)
                  stages/discover-baseline (fn [_ _] nil)
                  stages/working-copy-state (fn [_] {:identity "t" :entries {}})
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
                  stages/working-copy-state (fn [_] {:identity "t" :entries {}})
                  ws/append-entry! (fn [_ _ _ _] "/ws/entries/0005-design.edn")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                        (pr-str {:record a-design}))
                                  {:num-turns 3})]
      (let [out (rloop/run-loop {:run-id "r-design" :cwd "/w"
                                 :pipeline record/design-pipeline
                                 :finding-key record/design-finding-key})]
        (is (= :proceed (:status out)))
        (is (= 3 (:iter out)) "the amendment's proceed is read a second time before it stands")))))

(deftest an-amender-that-changes-nothing-stalls-instead-of-spinning
  (with-redefs [record/design-decision! (fn [_] (decision :amend))
                record/append! (fn [_ _] nil)
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (fn [_ _ _] a-design)
                stages/discover-baseline (fn [_ _] nil)
                stages/working-copy-state (fn [_] {:identity "t" :entries {}})
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
            :raised [(check :relation-honest :broken)]
            :findings [{:cites ["c"] :claim "x"}]
            :out-path "/run/a.edn"})]
    (is (str/includes? p "make the record TRUE"))
    (is (str/includes? p "It is NOT to make the\nchecks pass"))
    (is (str/includes? p "softening :revisit to :within"))
    (is (str/includes? p "1. relation-honest"))
    (is (str/includes? p "IF A NUMBERED LINE IS WRONG ABOUT THE CODE"))))

(deftest the-design-amender-numbers-a-defect-that-broke-no-check
  ;; Disputes are made BY NUMBER against the lines as they were listed, so a finding left
  ;; out of the numbering is one the amender can neither answer nor object to — and the
  ;; line has to say which claim it is about, since that is the finding's whole identity.
  (let [p (record/design-amend-prompt
           {:design a-design :recommend :amend :reason "r"
            :raised [(check :relation-honest :broken)
                     {:claim-ids ["consistency-reported"]
                      :claim "the record reports a consistency it also says it cannot reach"}]
            :out-path "/run/a.edn"})]
    (is (str/includes? p "1. relation-honest"))
    (is (str/includes? p "2. consistency-reported — the record reports a consistency"))
    (is (str/includes? p "breaks none of them")
        "the amender is told which kind of line it is reading")))

(deftest the-design-amender-is-given-the-baseline-amenders-rule-for-an-elements-id
  (doseq [declared? [true false]]
    (is (str/includes? (record/design-amend-prompt
                        {:design a-design :recommend :amend :reason "r"
                         :raised [(check :relation-honest :broken)]
                         :out-path "/run/a.edn" :declared? declared?})
                       (#'record/element-id-rule declared?))
        (str "declared? " declared?))))

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

(deftest the-judge-prompt-and-its-schema-agree-about-a-finding-with-no-check
  ;; Two documents the same judge reads in one call, and they disagreed: the schema's
  ;; check enum admits "" for a finding bearing on none of the four, while the prompt said
  ;; every finding names the check it shows broken. The judge produced check-less findings
  ;; anyway — the settled-claims block asks for exactly that shape — so what the
  ;; contradiction bought was a prompt nobody could follow.
  (let [enum (->> (json/parse-string
                   (slurp (jio/resource "review/design_decision_schema.json")) true)
                  :properties :findings :items :properties :check :enum)
        p    (record/design-prompt {:design a-design})]
    (is (contains? (set enum) "") "the schema is the half that was right")
    (is (not (str/includes? p "every\nfinding names the check it shows broken")))
    (is (str/includes? p "leaves check empty")
        "and the prompt now says what the schema admits")))

;; ── What a declared relation actually says ──────────────────────────────────

(deftest a-declared-relation-is-shown-with-what-makes-it-checkable
  ;; relation-honest is entirely about these two declarations, and the prompt
  ;; rendered each as a bare keyword. Watched: a design declared :revisit and
  ;; named the two properties it breaks — in the field the closed schema
  ;; requires them in, so it could not have been appended without them — and the
  ;; round reported it as failing to name them, three rounds running, sending
  ;; the amender to rewrite the field it already occupied.
  (let [d (assoc a-design
                 :standing {:relation :challenges :note "the stance is wrong here"
                            :principles ["one" "two"]}
                 :baseline {:seq 3 :relation :revisit
                            :breaks ["aggregate-is-the-only-summing-path"]
                            :note "the boundary has to move"})
        p (record/design-prompt {:design d})]
    (testing "the baseline declaration"
      (is (str/includes? p "Declared against the baseline: revisit"))
      (is (str/includes? p "breaks: aggregate-is-the-only-summing-path"))
      (is (str/includes? p "because: the boundary has to move")))
    (testing "the stance declaration"
      (is (str/includes? p "Declared against the stance: challenges"))
      (is (str/includes? p "principles: one; two"))
      (is (str/includes? p "because: the stance is wrong here")))))

(deftest a-judging-round-is-shown-no-provenance
  ;; The rule the two judging prompts kept by accident. Both transcribed a record
  ;; field by field and never reached for :supersedes, so nothing said the
  ;; omission was intended — and an amendment is the first thing on this arc that
  ;; carries a delta at all. A round told it is looking at an amendment judges the
  ;; delta: it reads a small change as small and stops asking whether the whole
  ;; record still serves the goal that moved.
  (let [d (assoc a-design :supersedes {:seq 41 :why "the goal moved"})
        b {:format :baseline :area "the arc" :bounded-by "the vocabulary"
           :supersedes {:seq 40 :why "corrected against the code"}}]
    (testing "the decision prompt"
      (let [p (record/design-prompt {:design d :baseline b})]
        (is (not (str/includes? p "41")))
        (is (not (str/includes? p "the goal moved")))
        (is (not (str/includes? p "corrected against the code")))
        (is (str/includes? p "rounding moves to one point")
            "and the record itself is still shown — this withholds provenance,
             not the subject")))
    (testing "the verification prompt"
      (let [p (record/baseline-prompt {:baseline b})]
        (is (not (str/includes? p "corrected against the code")))
        (is (str/includes? p "the arc")
            "same: the area is shown, that it replaced an earlier survey is not")))))

(deftest an-extends-declaration-says-where-it-lands
  (let [d (assoc a-design :baseline {:seq 3 :relation :extends
                                     :at "the lens registry" :note "a new lens"})
        p (record/design-prompt {:design d})]
    (is (str/includes? p "at: the lens registry"))))

(deftest a-declaration-with-nothing-to-qualify-it-renders-bare
  ;; :within needs no note and usually carries none; a label with nothing after
  ;; it would read as a field the author left empty.
  (let [d (assoc a-design :baseline {:seq 3 :relation :within})
        p (record/design-prompt {:design d})]
    ;; The line ends right after the relation. Checked that way rather than by
    ;; the absence of "because:", which the stance declaration also uses.
    (is (str/includes? p "Declared against the baseline: within\n"))
    (is (not (str/includes? p "breaks:")))
    (is (not (str/includes? p "at:")))))

;; ── A round names the run that appended it ───────────────────────────────────

(deftest a-decision-names-the-run-that-appended-it
  (let [appended (atom [])]
    (with-redefs [record/design-decision! (fn [_] (decision :amend :checks [(check :goal-served :broken)]
                                                            :findings [{:cites ["c"] :claim "x" :check :goal-served}]))
                  record/append! (fn [_ r] (swap! appended conj r) nil)]
      (run record/design-judge-stage (ctx))
      (is (= ["r1"] (mapv :run-id @appended)))
      (is (not-any? :within-run @appended) "a design run is nested in nothing"))))

(deftest a-rounds-outcome-is-left-unnamed
  ;; An outcome is why there is no record; it is not appended as one, and stamping it would make it
  ;; look like a decision.
  (let [appended (atom [])]
    (with-redefs [record/design-decision! (fn [_] {:outcome :codex-failed :detail "d"})
                  record/append! (fn [_ r] (swap! appended conj r) nil)]
      (run record/design-judge-stage (ctx))
      (is (every? #(not (contains? % :run-id)) @appended)))))

(deftest a-resurvey-is-told-the-design-run-it-is-nested-in
  (let [seen (atom nil)]
    (with-redefs [stages/project+ws-from-cwd (constantly [:nido "ws-1"])
                  ws/latest-entry            (constantly a-design)
                  stages/discover-baseline   (constantly {:format :baseline})
                  rloop/run-loop             (fn [cfg] (reset! seen cfg) {:status :no-progress})]
      (#'record/resurvey! (ctx :config {:cwd "/w" :run-id "design-loop-7"}))
      (is (= "design-loop-7" (:within-run @seen)))
      (is (str/starts-with? (:run-id @seen) "design-loop-7-resurvey-")))))

;; ── A re-survey that cannot pass by carrying ────────────────────────────────
;;
;; Three runs on one workstream refuted a baseline claim, re-surveyed, and had the nested loop find
;; every subject settled and carry an old review with no judge. The amender was then told the
;; baseline "now holds", and the next round re-found the refutation verbatim.

(def ^:private a-finding-against-c1
  {:claim-ids ["c1"] :claim "the unlocked read answers nil while a fenced leg still holds the row"
   :evidence ["src/x.clj:9"]})

(deftest a-resurvey-hands-the-nested-loop-the-baseline-claims-it-refuted
  (let [seen (atom nil)]
    (with-redefs [stages/project+ws-from-cwd (constantly [:nido "ws-1"])
                  ws/latest-entry            (constantly a-design)
                  stages/discover-baseline   (constantly corrected-baseline)
                  rloop/run-loop             (fn [cfg] (reset! seen cfg) {:status :no-progress})]
      (#'record/resurvey! (ctx :config {:cwd "/w" :run-id "design-loop-7"}
                               :appended-seq 14
                               :findings [a-finding-against-c1
                                          {:claim-ids ["design-own"] :claim "the design's own claim"}]))
      (is (= #{"c1"} (set (keys (:refuted @seen))))
          "only the cited baseline's claims: a finding about the design is not the baseline's to answer")
      (is (= {:ws-id "ws-1" :seq 14} (select-keys (get-in @seen [:refuted "c1"]) [:ws-id :seq]))
          "named as the decision that found it, so a judge confirming past it records an overturn")
      (is (= ["src/x.clj:9"] (get-in @seen [:refuted "c1" :finding :evidence])))))
  (testing "and nothing when the round named no claim of the baseline"
    (let [seen (atom nil)]
      (with-redefs [stages/project+ws-from-cwd (constantly [:nido "ws-1"])
                    ws/latest-entry            (constantly a-design)
                    stages/discover-baseline   (constantly corrected-baseline)
                    rloop/run-loop             (fn [cfg] (reset! seen cfg) {:status :no-progress})]
        (#'record/resurvey! (ctx :config {:cwd "/w" :run-id "design-loop-7"} :findings []))
        (is (nil? (:refuted @seen)))))))

(deftest the-amender-is-told-what-the-re-survey-actually-did
  (let [prompt-for (fn [out]
                     (second (with-resurvey {:out out}
                                            (assoc (ctx :findings [a-finding-against-c1])
                                                   :record (decision :resurvey)))))]
    (testing "a baseline the nested loop appended is the corrected one"
      (let [p (prompt-for {:status :sufficient :carry {:under-repair corrected-baseline}})]
        (is (str/includes? p "re-surveyed and corrected"))
        (is (str/includes? p "Set :baseline :seq to 11"))))
    (testing "a judge that upheld the cited baseline corrected nothing, and is not said to have"
      (let [p (prompt-for {:status :sufficient
                           :record {:format :baseline-review :verdict :sufficient}})]
        (is (not (str/includes? p "corrected baseline")))
        (is (not (str/includes? p "now holds")))
        (is (str/includes? p "keep :baseline :seq where it is"))))
    (testing "a carried review is no reading at all"
      (let [p (prompt-for {:status :sufficient
                           :record {:format :baseline-review :verdict :sufficient :carried-from 52}})]
        (is (str/includes? p "re-survey launched NO judge"))
        (is (str/includes? p "re-surveyed by hand"))
        (is (not (str/includes? p "now holds")))))))

(deftest a-carried-resurvey-answered-only-by-disputes-ends-the-run-at-once
  ;; Seen live: the next round read the same design, against the same baseline, at the same tree,
  ;; and re-found the same finding — a full judge round spent before :no-progress could fire.
  (let [dispute (fn [p] (spit p (pr-str {:disputes [{:finding 1 :because "the baseline is false here; re-survey it by hand"}]})))
        round   (fn [out]
                  (first (with-resurvey {:out out :amends dispute}
                                        (assoc (ctx :findings [a-finding-against-c1])
                                               :record (decision :resurvey)))))
        carried (round {:status :sufficient
                        :record {:format :baseline-review :verdict :sufficient :carried-from 52}})]
    (is (= :no-progress (:status carried)))
    (is (= :stop (:control carried)))
    (is (seq (:unfixable carried)) "naming what it ended holding, as the engine's :no-progress does")
    (is (= [false] (map :amended? (:history carried))) "the round is still recorded")
    (testing "a re-survey a judge read goes on to be judged with the dispute in front of it"
      (is (nil? (:status (round {:status :sufficient
                                 :record {:format :baseline-review :verdict :sufficient}})))))))

;; ── What a run's rounds did ──────────────────────────────────────────────────

(deftest the-figures-are-read-off-the-decisions-a-run-appended
  (let [d  (fn [& checks] {:format :design-decision :checks (vec checks)})
        es [(d (check :relation-honest :broken) (check :stratified :broken))
            ;; a round that proceeds: the report drops this, the decision keeps it
            (d (check :decomposable :broken) (check :goal-served :held))
            (d (check :stratified :broken))]
        f  (record/run-figures es)]
    (is (= 3 (:decisions f)))
    (is (= {:derived 2 :held 0 :underivable 0 :broken 2 :alone 1 :at-end true}
           (get-in f [:checks :stratified])))
    (is (= {:derived 1 :held 0 :underivable 0 :broken 1 :alone 1 :at-end false}
           (get-in f [:checks :decomposable]))
        "a check a proceeding round broke is counted")
    (is (= {:derived 1 :held 0 :underivable 0 :broken 1 :alone 0 :at-end false}
           (get-in f [:checks :relation-honest])))
    (is (= {:derived 1 :held 1 :underivable 0 :broken 0 :alone 0 :at-end false}
           (get-in f [:checks :goal-served]))
        "a check that only ever held still has a row, or a clean run prints what a run that derived nothing prints")))

(deftest a-clean-runs-figures-say-what-it-answered
  (let [d (fn [& checks] {:format :design-decision :checks (vec checks)})
        f (record/run-figures [(d (check :stratified :held) (check :relation-honest :underivable))])]
    (is (seq (:checks f)) "four held checks and none derived must not print the same empty map")
    (is (= 1 (get-in f [:checks :stratified :held])))
    (is (= 1 (get-in f [:checks :relation-honest :underivable]))
        "an underivable check is the status most worth watching across runs")))

(deftest a-claim-refuted-with-no-check-broken-is-counted
  (let [d (fn [findings & checks] {:format :design-decision :checks (vec checks) :findings findings})
        f (record/run-figures [(d [{:check :stratified :claim-id "c1"} {:claim-id "c2"}]
                                  (check :stratified :broken))
                               (d [{:claim-id "c2"}] (check :stratified :held))])]
    (is (= {"c2" {:broken 2 :alone 1 :at-end true}} (:claims f))
        "a run whose only defect was a check-less refutation read as a clean one")
    (is (= 0 (get-in f [:checks :stratified :alone]))
        "a check is not the only defect of a round that also refuted a claim")))

(deftest a-claim-refuted-under-a-check-the-decision-held-is-counted
  (let [d (fn [findings & checks] {:format :design-decision :checks (vec checks) :findings findings})
        f (record/run-figures [(d [{:check :stratified :claim-id "c1"}]
                                  (check :stratified :held) (check :goal-served :broken))
                               (d [{:check :relation-honest :claim-id "c2"}]
                                  (check :relation-honest :underivable))])]
    (is (= {"c1" {:broken 1 :alone 0 :at-end false}} (:claims f))
        "a refutation the amender repaired must not vanish from the figures for naming a held check")
    (is (= 0 (get-in f [:checks :stratified :broken])) "and the check it named stays held")))

(deftest a-runs-figures-count-confirmations-and-who-judged
  (let [f (record/run-figures [{:format :baseline-review :verdict :sufficient :confirmed ["c1" "c2"]
                                :judged-by {:reviewer :claude :instead-of :codex}}
                               {:format :baseline-review :verdict :sufficient :confirmed ["c1"]
                                :judged-by {:reviewer :codex}}
                               {:format :baseline-review :verdict :sufficient}])]
    (is (= {"c1" 2 "c2" 1} (:confirmed f))
        "a clean baseline run says which subjects were checked, not only that it ended")
    (is (= {"claude for codex" 1 "codex" 1} (:judged-by f))
        "a comparison across runs has to be able to hold the instrument fixed")))

(deftest a-baseline-runs-figures-count-gaps-and-falsified-claims
  (let [es [{:format :baseline-review :verdict :insufficient
             :findings [{:blocks :relation-honest :cites ["a"] :claim "c" :needs "n"}]}
            {:format :baseline-review :verdict :falsified
             :findings [{:cites ["a"] :claim "c" :claim-id "one-gate"}]}
            {:format :baseline-review :verdict :sufficient}]
        f  (record/run-figures es)]
    (is (= 3 (:reviews f)))
    (is (= {:broken 1 :alone 1 :at-end false} (get-in f [:derivations :relation-honest])))
    (is (= {"one-gate" 1} (:falsified f)))))

(deftest a-gap-is-counted-under-the-derivation-it-blocks-whatever-the-verdict
  (let [f (record/run-figures
           [{:format :baseline-review :verdict :falsified
             :findings [{:blocks :relation-honest :cites ["a"] :claim "c" :needs "n" :claim-id "shape"}
                        {:blocks :goal-served :cites ["a"] :claim "c" :needs "n" :claim-id "shape"}
                        {:cites ["a"] :claim "c" :claim-id "one-gate"}]}])]
    (is (= #{:relation-honest :goal-served} (set (keys (:derivations f))))
        "two gaps blocking different derivations are two derivations, not one claim")
    (is (= {"one-gate" 1} (:falsified f)) "only a finding blocking no derivation is a falsified claim")))

(deftest a-run-that-launched-no-judge-counts-none
  (let [round (fn [outcome] {:phases [{:phase :judge :outcome outcome} {:phase :amend}]})]
    (is (= 2 (record/judges-launched {:rounds [(round nil) (round "codex-failed")]}))
        "a verdict and a failed judge were both launched")
    (is (= 1 (record/judges-launched {:rounds [(round nil) (round "reviewer-unavailable")]}))
        "a judge its vendor refused read nothing, so a run that only ever met the refusal is not
         sent to an analysis — while one that judged before the refusal still is")
    (is (= 1 (record/judges-launched {:rounds [(round nil) (round "premise-unverified")]})))
    (is (= 0 (record/judges-launched {:rounds [(round "subjects-undeclared")]})))
    (is (= 0 (record/judges-launched {:rounds [(round "goal-superseded") (round "premise-retracted")]}))
        "a premise `standing` refused launched no judge, whatever it is called —
         a reason nobody listed is counted as not judged, never as judged")
    (is (= 1 (record/judges-launched {:rounds [{:phases [{:phase "judge" :verdict "sufficient"}]}]})))
    (is (= 0 (record/judges-launched nil)))))

;; ── Each named stratum read by a judge of its own ────────────────────────────

(def ^:private a-listing
  {:status :listed
   :elements [{:id "canvas.coordinator.report/coordinator-report" :sort :fukan.common.vocab.code.module/Module}
              {:id "canvas.strata/record-vocabulary" :sort :fukan.common.vocab.code.stratum/Stratum
               :doc "Typed ledger records: what each kind may carry."
               :refs {:provided-by ["canvas.coordinator.report/coordinator-report"]}}
              {:id "canvas.strata/record-model" :sort :fukan.common.vocab.code.stratum/Stratum
               :doc "Any record read as one model."
               :refs {:provided-by ["canvas.coordinator.report.model/report-model"]
                      :rests-on ["canvas.strata/record-vocabulary"]}}]})

(def ^:private a-stratified-design
  (-> a-design
      (dissoc :invariants)
      (assoc :model {:elements [{:id "canvas.coordinator.report/coordinator-report" :sort :module}
                                {:id "canvas.review.record/review-record" :sort :module}
                                {:id "canvas.strata/record-vocabulary" :sort :stratum}]
                     :claims [{:id "decision-records-levels" :about ["canvas.coordinator.report/coordinator-report"]
                               :statement "a decision records each level's reading" :falsified-by "f"
                               :evidence {:by :round}}
                              {:id "elsewhere" :about ["canvas.review.record/review-record"]
                               :statement "a claim about another module" :falsified-by "f"
                               :evidence {:by :round}}]}
             :strata ["canvas.strata/record-vocabulary"])))

(def ^:private a-proceeding-answer
  (json/generate-string {:recommend "proceed" :reason "r" :asks "worth it?" :findings [] :confirmed []
                         :checks (for [c ["relation_honest" "goal_served" "stratified" "routing_coherent"]]
                                   {:check c :status "held" :note "n"})}))

(defn- decide [design stratum-answer]
  (let [calls (atom [])]
    (with-redefs [stages/project+ws-from-cwd  (fn [_] [:nido "ws-1"])
                  ws/latest-entry             (fn [_ _ k] (when (= :design k) design))
                  standing/of-design          (constantly {:decidable? true})
                  record/undeclared-subjects  (constantly nil)
                  settled/code-identity       (constantly "tree")
                  stages/read-stance          (constantly nil)
                  stages/discover-baseline    (constantly nil)
                  record/discover-intent      (constantly nil)
                  record/run-round!           (fn [{:keys [kind prompt]}]
                                                (swap! calls conj {:kind kind :prompt prompt})
                                                (if (= :stratum-reading kind)
                                                  stratum-answer
                                                  {:ok a-proceeding-answer}))]
      {:decision (record/design-decision! {:cwd "/w" :run-id "r1" :label "l" :listing a-listing})
       :calls    @calls})))

(deftest each-named-stratum-is-read-before-the-deciding-judge
  (let [{:keys [decision calls]}
        (decide a-stratified-design
                {:ok (json/generate-string {:verdict "widens" :reason "no primitive for a judge's evidence"
                                            :cites []})})]
    (is (= [:stratum-reading :design-decision] (mapv :kind calls)) "one level judge, then the one that decides")
    (is (str/includes? (:prompt (second calls)) "WHAT EACH LEVEL SAID"))
    (is (str/includes? (:prompt (second calls))
                       "canvas.strata/record-vocabulary — provides: Typed ledger records: what each kind may carry.")
        "the deciding judge sees the level's declared vocabulary")
    (is (str/includes? (:prompt (second calls)) "its judge: widens — no primitive for a judge's evidence"))
    (is (= [{:stratum "canvas.strata/record-vocabulary" :verdict :widens
             :reason "no primitive for a judge's evidence"}]
           (:strata-read decision)))
    (is (= :proceed (:recommend decision)) "the recommendation is the deciding judge's alone")))

(deftest a-level-judge-sees-only-its-level
  (let [{:keys [calls]} (decide a-stratified-design {:outcome :no-output :detail "d"})
        p (:prompt (first calls))]
    (is (str/includes? p "THE LEVEL: canvas.strata/record-vocabulary"))
    (is (str/includes? p "Its modules: canvas.coordinator.report/coordinator-report"))
    (is (str/includes? p "Resting on it: canvas.strata/record-model"))
    (is (str/includes? p "[decision-records-levels]") "a claim about one of its modules")
    (is (not (str/includes? p "[elsewhere]")) "not a claim about a module outside it")
    (is (not (str/includes? p "Any record read as one model")) "nor another level's vocabulary")))

(deftest a-failed-level-judge-leaves-the-round-to-decide-without-it
  (let [{:keys [decision calls]} (decide a-stratified-design {:outcome :codex-failed :detail "exit 1"})]
    (is (= :proceed (:recommend decision)))
    (is (= [{:stratum "canvas.strata/record-vocabulary" :outcome :codex-failed :detail "exit 1"}]
           (:strata-read decision)))
    (is (str/includes? (:prompt (second calls)) "its judge did not answer (codex-failed)"))))

(deftest a-design-naming-no-stratum-reads-no-level
  (let [{:keys [decision calls]} (decide (assoc a-stratified-design :strata []) {:ok "{}"})]
    (is (= [:design-decision] (mapv :kind calls)))
    (is (not (contains? decision :strata-read)))))

(deftest a-level-judges-answer-is-closed
  (is (= {:stratum "s" :verdict :fits :reason "r"}
         (record/parse-stratum-reading (json/generate-string {:verdict "fits" :reason "r" :cites []}) "s")))
  (is (nil? (record/parse-stratum-reading (json/generate-string {:verdict "proceed" :reason "r"}) "s")))
  (is (nil? (record/parse-stratum-reading (json/generate-string {:verdict "fits" :reason " "}) "s")))
  (is (nil? (record/parse-stratum-reading "not json" "s"))))

(deftest a-runs-figures-count-the-checks-left-without-a-ruling
  (let [f (record/run-figures [{:format :baseline-review :verdict :sufficient :unruled ["h1"]}
                               {:format :design-decision :checks [] :unruled ["h1" "c2"]}])]
    (is (= {"c2" 1 "h1" 2} (:unruled f))))
  (is (nil? (:unruled (record/run-figures [{:format :baseline-review :verdict :sufficient}])))))

(deftest a-runs-figures-name-what-it-ended-without-anyone-ruling-on
  ;; A run ended sufficient with one claim unchecked in all four of its judgements, and its figures
  ;; printed confirmed and falsified only — the one subject nobody ruled on was nowhere.
  (let [u (fn [& ids] {:format :baseline-review :verdict :sufficient
                       :unchecked (mapv #(hash-map :id % :reason "needs production history") ids)})
        f (record/run-figures [(u "tools-unused" "rows-span")
                               (assoc (u "tools-unused") :confirmed ["rows-span"])
                               (u "tools-unused")])]
    (is (= {"rows-span" 1 "tools-unused" 3} (:unchecked f)))
    (is (= ["tools-unused"] (:still-unchecked f))
        "a subject confirmed after it was unchecked was ruled on, and is not still owed"))
  (is (nil? (:still-unchecked (record/run-figures [{:format :baseline-review :verdict :sufficient}])))))

(deftest a-runs-figures-count-relation-ids-apart-from-unruled-checks
  (let [f (record/run-figures [{:format :design-decision :checks [] :unruled ["c1"]
                                :relation-unruled ["c1" "m1"]}
                               {:format :design-decision :checks [] :relation-unruled ["m1"]}])]
    (is (= {"c1" 1} (:unruled f)) "a claim left unruled is not counted again for its relation")
    (is (= {"c1" 1 "m1" 2} (:relation-unruled f))
        "a judge skipping module rulings round after round shows as its own tally")))

(deftest a-runs-level-figures-are-read-off-its-decisions
  (let [f (record/run-figures [{:format :design-decision :checks []
                                :strata-read [{:stratum "s" :verdict :widens :reason "r"}]}
                               {:format :design-decision :checks []
                                :strata-read [{:stratum "s" :outcome :codex-failed}]}])]
    (is (= {"s" {:read 2 :fits 0 :widens 1 :misplaced 0 :not-a-level 0 :failed 1}} (:strata f)))))

(deftest a-level-judge-asks-first-whether-its-stratum-is-a-level
  (let [{:keys [calls]} (decide a-stratified-design {:outcome :no-output :detail "d"})
        p (:prompt (first calls))]
    (is (str/includes? p "0. Is this a level at all?"))
    (is (str/includes? p "Verdict — the FIRST that holds: not-a-level"))))

(deftest a-level-judge-can-say-its-stratum-is-no-level
  (is (= {:stratum "s" :verdict :not-a-level :reason "nothing rests on it; it is an output sink"}
         (record/parse-stratum-reading
          (json/generate-string {:verdict "not-a-level" :reason "nothing rests on it; it is an output sink"
                                 :cites []})
          "s")))
  (is (= {"s" {:read 1 :fits 0 :widens 0 :misplaced 0 :not-a-level 1 :failed 0}}
         (:strata (record/run-figures [{:format :design-decision :checks []
                                        :strata-read [{:stratum "s" :verdict :not-a-level :reason "r"}]}])))))

(deftest the-stratified-check-asks-whether-declared-strata-are-levels
  (let [{:keys [calls]} (decide a-stratified-design {:outcome :no-output :detail "d"})
        p (:prompt (second calls))]
    (is (str/includes? p "LEVEL — every stratum the design declares or restates is"))
    (is (str/includes? p "Four\n                      questions of the COMMITMENT"))))

(defn- judging-a-model-design
  "`f` called with the design stage reading `a-model-design` at seq 4 over an empty ledger, its
   judge answering `answer` and every append landing in `appended`."
  [answer appended f]
  (with-redefs [record/design-decision! (fn [_] answer)
                record/append! (fn [_ r] (swap! appended conj r) nil)
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (fn [_ _ k] (when (= :design k) (assoc a-model-design :seq 4)))
                stages/discover-baseline (constantly nil)
                settled/code-identity (constantly "tree")
                settled/ledgers (constantly [])
                design-check/elements (constantly {:status :listed :elements []})]
    (f)))

(deftest a-proceed-on-a-first-reading-is-read-again-before-anyone-is-asked
  ;; A design loop proceeded on one clean reading, and the next run broke the same claims on text
  ;; that had not changed. The first reading is kept, proceeds on no reader's reading of it, and so
  ;; clears nothing; the second is the one that proceeds.
  (let [appended (atom [])
        read     (assoc (decision :proceed :checks [(check :relation-honest :held)])
                        :confirmed ["rounded-once"] :checked-at {"rounded-once" ["src/a.clj:1"]})]
    (judging-a-model-design
     read appended
     (fn []
       (let [r1 (run record/design-judge-stage (ctx))]
         (is (= :next-round (:control r1)))
         (is (= ["rounded-once"] (:read-once (first @appended))))
         (is (false? (report/proceeds? (first @appended))) "so appending it writes no clearance")
         (let [r2 (run record/design-judge-stage (ctx :carry (:carry r1)))]
           (is (= :proceed (:status r2)))
           (is (= :escalate (:control r2)))))))))

(deftest only-a-subject-the-judge-was-asked-to-confirm-is-read-once
  ;; A decision round asks for its claims by id and shows its elements as what they are about, so a
  ;; judge that does not confirm an element again has not left it :unruled. Held for a second
  ;; reading, three of four such subjects were cleared by a later judge saying nothing about them,
  ;; and the run proceeded on a second reading nobody made.
  (let [appended (atom [])
        read     (assoc (decision :proceed :checks [(check :relation-honest :held)])
                        :confirmed ["rounded-once" "canvas.order/aggregate"]
                        :checked-at {"rounded-once" ["src/a.clj:1"] "canvas.order/aggregate" ["src/a.clj:2"]})]
    (judging-a-model-design
     read appended
     (fn []
       (run record/design-judge-stage (ctx))
       (is (= ["rounded-once"] (:read-once (first @appended)))
           "the claim, whose silence the next round counts as :unruled — never the element")))))

(deftest a-proceed-on-a-design-this-run-amended-is-read-again-though-it-confirms-nothing
  ;; An amendment that rewrote only the summary left every claim settled, so the judge after it had
  ;; nothing to confirm and its one clean reading ended the run — where a second reading of the
  ;; record before it had broken a check on text no claim carries.
  (let [appended (atom [])
        amended  [{:iter 1 :amended? true}]]
    (judging-a-model-design
     (decision :proceed :checks [(check :relation-honest :held)]) appended
     (fn []
       (let [r1 (run record/design-judge-stage (ctx :iter 2 :history amended))]
         (is (= :next-round (:control r1)) "read again before anyone is asked")
         (is (true? (:amendment-read-once (first @appended))))
         (is (false? (report/proceeds? (first @appended))) "so appending it writes no clearance")
         (let [r2 (run record/design-judge-stage (ctx :iter 3 :history amended :carry (:carry r1)))]
           (is (= :proceed (:status r2)) "the second reading of the amendment proceeds")
           (is (nil? (:amendment-read-once (last @appended))))))))))

;; Watched twice on one workstream: a design not yet built had every claim held :owed, so nothing
;; was read once and the run proceeded on one judge — the second time clearing three claims the
;; round before had refuted. An owed ruling is a reading like a confirmation, and is paired like one.
(deftest a-proceed-holding-every-claim-owed-is-read-again
  (let [appended (atom [])
        read     (assoc (decision :proceed :checks [(check :relation-honest :held)])
                        :owed ["rounded-once"])]
    (judging-a-model-design
     read appended
     (fn []
       (let [r1 (run record/design-judge-stage (ctx))]
         (is (= :next-round (:control r1)) "one judge's word that a claim is sound is a sample of it")
         (is (= ["rounded-once"] (:read-once (first @appended))))
         (let [r2 (run record/design-judge-stage (ctx :carry (:carry r1)))]
           (is (= :proceed (:status r2)) "a second owed ruling pairs the first, so the run proceeds")
           (is (nil? (:read-once (last @appended))))))))))

;; Watched: a round asked the person with no derivable finding, and the round after it broke three
;; checks on text no amendment had touched — the person would have been asked over an unrepaired
;; record. An ask with nothing derivable ends the run as a proceed does, so it is read twice too.
(deftest an-ask-over-nothing-derivable-is-read-again-before-the-person-is-asked
  (let [appended (atom [])
        read     (assoc (decision :ask :checks [(check :goal-served :broken)]
                                  :findings [{:cites ["c"] :claim "over-serves the goal"
                                              :check :goal-served :claim-id "pool-in-scope"
                                              :for-person true}])
                        :confirmed ["rounded-once"] :checked-at {"rounded-once" ["src/a.clj:1"]})]
    (judging-a-model-design
     read appended
     (fn []
       (with-redefs [record/append! (fn [_ r] {:seq (count (swap! appended conj r))})]
         (let [r1 (run record/design-judge-stage (ctx))]
           (is (= :next-round (:control r1)) "a single reading must not be what a person is asked over")
           (is (= ["rounded-once"] (:read-once (first @appended))))
           (let [r2 (run record/design-judge-stage (ctx :carry (:carry r1)))]
             (is (= :asked (:status r2)) "the second reading agreeing is what stops for the person")
             (is (= :escalate (:control r2))))))))))

;; Watched: a claim the judge held owed, then holds, on one design at one tree was paired by the
;; holds and settled — though the owed reading was the true one. Two readings that disagree are not
;; a pair, and the disagreement is kept.
(deftest an-owed-reading-then-a-holds-one-is-read-a-third-time
  (let [appended (atom [])
        owed     (assoc (decision :proceed :checks [(check :relation-honest :held)])
                        :owed ["rounded-once"])
        holds    (assoc (decision :proceed :checks [(check :relation-honest :held)])
                        :confirmed ["rounded-once"] :checked-at {"rounded-once" ["src/a.clj:1"]})
        answers  (atom [owed holds holds])]
    (judging-a-model-design
     owed appended
     (fn []
       (with-redefs [record/design-decision! (fn [_] (let [a (first @answers)] (swap! answers rest) a))]
         (let [r1 (run record/design-judge-stage (ctx))
               r2 (run record/design-judge-stage (ctx :carry (:carry r1)))]
           (is (= :next-round (:control r2)) "a judge that said owed then holds has not said one thing twice")
           (is (= [{:id "rounded-once" :was :owed :now :holds}] (:unpaired (second @appended)))
               "the disagreement is on the ledger, not overwritten by the later ruling")
           (is (= ["rounded-once"] (:read-once (second @appended))))
           (let [r3 (run record/design-judge-stage (ctx :carry (:carry r2)))]
             (is (= :proceed (:status r3)) "the third reading agreeing with the second pairs it")
             (is (nil? (:unpaired (last @appended)))))))))))

;; Watched: a claim confirmed on round 1 and left unchecked on round 2, at one tree, ended the run
;; :sufficient — confirmed once, never paired, and unchecked once is below the amender's bar.
(deftest a-confirmation-the-next-round-leaves-unchecked-is-still-read-once
  (let [appended (atom [])
        holds    (assoc (decision :proceed :checks [(check :relation-honest :held)])
                        :confirmed ["rounded-once"] :checked-at {"rounded-once" ["src/a.clj:1"]})
        unchecked (assoc (decision :proceed :checks [(check :relation-honest :held)])
                         :unchecked [{:id "rounded-once" :reason "needs response data"}])
        answers  (atom [holds unchecked])]
    (judging-a-model-design
     holds appended
     (fn []
       (with-redefs [record/design-decision! (fn [_] (let [a (first @answers)] (swap! answers rest) a))]
         (let [r1 (run record/design-judge-stage (ctx))
               r2 (run record/design-judge-stage (ctx :carry (:carry r1)))]
           (is (= :next-round (:control r2)) "one confirmation and one shrug are not two readings")
           (is (= ["rounded-once"] (:read-once (second @appended))))
           (is (not (contains? (get-in r2 [:carry :quiet :read]) "rounded-once"))
               "the confirmation before the shrug pairs with nothing after it")))))))

;; ── A decision that is a person's to make ───────────────────────────────────

(deftest a-scope-decision-stops-for-a-person-instead-of-reaching-the-amender
  ;; Handed a finding whose only repair was the scope question in :asks, amenders dropped a
  ;; 309-line module the branch was building and reversed a decision the record's :open stated.
  ;; An :ask whose every finding is the person's ends the run there, and nothing is handed to an
  ;; amender.
  (let [appended (atom [])]
    (with-redefs [record/design-decision!
                  (fn [_] (decision :ask :checks [(check :goal-served :broken)]
                                    :findings [{:cites ["c"] :claim "over-serves the goal"
                                                :check :goal-served :claim-id "pool-in-scope"
                                                :for-person true}]))
                  record/append! (fn [_ r] {:seq (count (swap! appended conj r))})]
      (let [out (run record/design-judge-stage (ctx))]
        (is (= :asked (:status out)))
        (is (= :escalate (:control out)))
        (is (= [:goal-served] (mapv :check (:findings out)))
            "the round stops holding what it found, so the report's broken check has a case behind it")
        (is (= [:ask] (mapv :recommend @appended)) "the decision is on the ledger for the person")))))

(deftest an-ask-the-ledger-never-took-ends-unrecorded
  ;; An :ask decision reached no ledger and the run still ended :asked: the person was parked on a
  ;; question no grant could answer, the next round never read it, and the figures disagreed with
  ;; the headline. The status a run ends on has to be one the ledger backs.
  (with-redefs [record/design-decision! (fn [_] (decision :ask :checks [(check :goal-served :held)]))
                record/append! (fn [_ _] nil)]
    (let [out (run record/design-judge-stage (ctx :config {:cwd "/w" :run-id "r1" :ledger [:nido "ws-1"]}))]
      (is (= :unrecorded (:status out)) "a terminal decision that wrote nothing must not read as recorded")
      (is (= :escalate (:control out)) "the decision exists only in the report, so a person has to read it there")
      (is (= {:would-have-ended :asked :ledger "nido/ws-1"} (:unrecorded out))
          "the report has to say what the run decided and where it failed to write it")
      (is (not (contains? out :appended-seq))))))

(deftest a-round-appends-to-the-ledger-resolved-when-the-run-started
  ;; Resolving the session from cwd again at the append answered nil mid-run, and the judged
  ;; decision went nowhere. The ledger the run started on is the one every round writes to.
  (let [wrote-to (atom nil)]
    (with-redefs [record/design-decision! (fn [_] (decision :ask :checks [(check :goal-served :held)]))
                  stages/project+ws-from-cwd (fn [_] nil)
                  record/append! (fn [ledger _] (reset! wrote-to ledger) {:seq 9})]
      (let [out (run record/design-judge-stage (ctx :config {:cwd "/w" :run-id "r1" :ledger [:nido "ws-1"]}))]
        (is (= [:nido "ws-1"] @wrote-to) "a cwd that resolves nothing now must not lose the decision")
        (is (= :asked (:status out)))
        (is (= 9 (:appended-seq out)))))))

(deftest an-ask-that-also-found-a-derivable-defect-repairs-it-before-asking
  ;; One round asked a scope question and said in its reason that two derivable defects also needed
  ;; repair — then escalated with no findings at all, so the run resuming after the answer had to
  ;; find both again and spend a round amending them.
  (let [scope   {:cites ["c"] :claim "over-serves the goal" :check :goal-served
                 :claim-id "pool-in-scope" :for-person true}
        breaks  {:cites ["[tutor-context]"] :claim "ruled breaks, missing from :breaks"
                 :check :relation-honest}
        record  (decision :ask :checks [(check :goal-served :broken) (check :relation-honest :broken)]
                          :findings [scope breaks])
        seen    (atom nil)]
    (with-redefs [record/design-decision! (fn [_] record)
                  record/append! (fn [_ _] nil)]
      (let [out (run record/design-judge-stage (ctx))]
        (is (nil? (:status out)) "the round goes on to the amender rather than stopping for the person")
        (is (= [:relation-honest] (mapv :check (:findings out)))
            "only the defect the question does not cover is the amender's")))
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ k] (when (= :design k) a-design))
                  stages/discover-baseline (fn [_ _] nil)
                  design-check/design-of (constantly nil)
                  record/launch-amender! (fn [_ {:keys [first-message]}] (reset! seen first-message) {})]
      (run record/design-amend-stage (ctx :record record :findings [(check :relation-honest :broken)]))
      (is (str/includes? @seen "ruled breaks, missing from :breaks"))
      (is (not (str/includes? @seen "over-serves the goal"))
          "a finding only the question repairs is never put to the amender, which would answer it")
      (is (str/includes? @seen "is this worth doing now?") "the question is shown as not its to answer"))))

(deftest an-ask-whose-derivable-finding-was-objected-to-twice-stops-for-the-person
  ;; Objected to twice, a derivable finding is a disagreement only a person settles — and the ask
  ;; is already stopping for one.
  (with-redefs [record/design-decision!
                (fn [_] (decision :ask :checks [(check :relation-honest :broken)]
                                  :findings [{:cites ["c"] :claim "x" :check :relation-honest}]))
                record/append! (fn [_ _] {:seq 1})
                record/disputed-n (constantly 2)]
    (let [out (run record/design-judge-stage (ctx))]
      (is (= :asked (:status out)))
      (is (= [:relation-honest] (mapv :check (:findings out)))))))

(deftest an-ask-parks-even-where-a-proceed-would-clear
  ;; A design declaring :conforms/:within owes nobody a grant, so a proceed clears it and nobody
  ;; reads its ask. The judge's way to reach a person there is :ask, and nothing may read an :ask
  ;; as proceeding — not even when the only check it broke is the advisory one.
  (is (false? (report/proceeds? (decision :ask :checks [(check :decomposable :broken)]))))
  (let [modest (assoc a-design :standing {:relation :conforms}
                      :baseline {:seq 1 :relation :within})]
    (with-redefs [record/design-decision!
                  (fn [_] (decision :ask :checks [(check :goal-served :held)]))
                  record/append! (fn [_ _] {:seq 1})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/entries-of (constantly [])
                  ws/entry-at-seq (constantly modest)
                  standing/of-design (constantly {:decidable? true})]
      (is (= :asked (:status (run record/design-judge-stage (ctx))))))))

(deftest the-amender-is-shown-the-question-it-may-not-answer
  ;; It was handed :reason and never :asks, and its only way to decline was a finding wrong about
  ;; the code — so a finding right about the code whose repair was the ask got answered by it.
  (let [p (record/design-amend-prompt {:design a-design :recommend :amend :reason "r"
                                       :asks "is the candidate pool in scope?"
                                       :raised [(check :goal-served :broken)] :out-path "/o"})]
    (is (str/includes? p "is the candidate pool in scope?"))
    (is (str/includes? p ":out-of-reach [{:finding 2")
        "declining to answer it has a channel of its own, which goes to the person")))

;; ── A person's question never reaches the amender as a repair order ─────────

(deftest a-broken-check-with-nothing-filed-under-it-goes-to-the-amender
  ;; routing-coherent broke with a concrete note and no finding filed under it. `every?` over no
  ;; filings called it the person's, so on an :ask it reached neither the amender nor the asks, and
  ;; vanished from the round's findings while the figures still counted it broken.
  (with-redefs [record/design-decision!
                (fn [_] (decision :ask :checks [(check :goal-served :broken) (check :routing-coherent :broken)]
                                  :findings [{:cites ["c"] :claim "over-serves" :check :goal-served
                                              :claim-id "pool-in-scope" :for-person true}]))
                record/append! (fn [_ _] {:seq 1})]
    (let [out (run record/design-judge-stage (ctx))]
      (is (nil? (:status out)) "a check broken on its note alone is a defect an amender can repair")
      (is (= [:routing-coherent] (mapv :check (:findings out))))
      (is (= [:goal-served] (mapv :check (:asked-findings out)))
          "the person's half is kept for the round's history rather than dropped"))))

(deftest a-check-mixing-a-person-claim-with-a-derivable-one-hands-the-amender-the-derivable-claim
  ;; Handed the whole check, the amender repaired the derivable claim and disputed the line for the
  ;; person's half — and the dispute was keyed on the claim it had repaired.
  (with-redefs [record/design-decision!
                (fn [_] (decision :ask :checks [(check :goal-served :broken)]
                                  :findings [{:cites ["c"] :claim "no tool internals on the wire?"
                                              :check :goal-served :claim-id "no-tool-internals"
                                              :for-person true}
                                             {:cites ["src/a.clj:3"] :claim "the event is split"
                                              :check :goal-served :claim-id "event-is-whole"}]))
                record/append! (fn [_ _] {:seq 1})]
    (let [out (run record/design-judge-stage (ctx))]
      (is (= [["event-is-whole"]] (mapv :claim-ids (:findings out)))
          "the amender's handle, and so any dispute it files, names only what it can repair")
      (is (= [:check :goal-served :claims ["event-is-whole"]]
             (record/design-finding-base-key (first (:findings out)))))
      (is (= [["no-tool-internals"]] (mapv :claim-ids (:asked-findings out)))))))

(deftest the-amender-s-numbered-line-reads-as-the-claims-filed-under-it
  ;; The line showed the check's note, which carried the person's question, while the claim filed
  ;; under it was a derivable overstatement. The amender fixed the claim and then answered the
  ;; note as a dispute — a person's question had reached it as a repair order.
  (let [p (record/design-amend-prompt
           {:design a-design :recommend :amend :reason "r" :out-path "/o"
            :raised [(assoc (check :goal-served :broken)
                            :note "does the extractor still need a request connection?"
                            :claim-ids ["stats-reach-rows"])]
            :findings [{:check :goal-served :claim-id "stats-reach-rows" :cites ["c"]
                        :claim "stats reach rows through the domain is overbroad"}]})]
    (is (str/includes? p "1. goal-served [stats-reach-rows] — stats reach rows through the domain is overbroad"))
    (is (not (str/includes? p "does the extractor still need a request connection?"))))
  (is (str/includes? (record/design-amend-prompt
                      {:design a-design :recommend :amend :reason "r" :out-path "/o"
                       :raised [(check :routing-coherent :broken)]})
                     "1. routing-coherent — routing-coherent note")
      "a check with nothing filed is still told by its note"))

(deftest a-line-the-amender-cannot-reach-stops-for-the-person-with-the-line-attached
  ;; The amender said the remainder was in the intent, which it cannot edit. The prose reached
  ;; nobody, and a whole judge round re-derived it before anyone asked the person.
  (let [line     (assoc (check :goal-served :broken) :claim-ids ["one-row-per-content"])
        [out _]  (with-amend {:writes (fn [p] (spit p (pr-str {:record a-design
                                                                :out-of-reach [{:finding 1 :because "the done-when says zero duplicates"}]})))}
                             (ctx :findings [line] :record (decision :ask)))]
    (is (= :asked (:status out)))
    (is (= :escalate (:control out)))
    (is (= [{:finding line :key (record/design-finding-base-key line) :claim "goal-served"
             :because "the done-when says zero duplicates"}]
           (:out-of-reach out))
        "the person is handed the line itself, not only the amender's words about it")
    (is (true? (:amended? out)) "what the amender could repair beside it is still appended"))
  (testing "and with nothing amended beside it"
    (let [[out _] (with-amend {:writes (fn [p] (spit p (pr-str {:out-of-reach [{:finding 1 :because "a ref to file"}]})))}
                              (ctx :findings [(check :goal-served :broken)] :record (decision :ask)))]
      (is (= :asked (:status out)) "an answer naming only out-of-reach lines is not a no-op"))))

(deftest the-trajectory-names-what-a-round-left-for-the-person
  (is (= [{:round 1 :found ["relation-honest"] :asked ["goal-served"] :amended true}]
         (record/trajectory [{:findings [(check :relation-honest :broken)]
                              :asked [(check :goal-served :broken)] :amended? true}]))))

(deftest the-amend-stage-passes-the-decision-s-ask-through
  (let [seen (atom nil)]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ k] (when (= :design k) a-design))
                  stages/discover-baseline (fn [_ _] nil)
                  design-check/design-of (constantly nil)
                  record/launch-amender! (fn [_ {:keys [first-message]}] (reset! seen first-message) {})]
      (run record/design-amend-stage
           (ctx :record (decision :amend :findings [{:cites ["c"] :claim "x"}])
                :findings [(check :relation-honest :broken)]))
      (is (str/includes? @seen "is this worth doing now?")))))

(deftest a-design-amender-is-shown-the-baseline-properties-its-claims-rest-on
  ;; An amender widened a claim to `every child the run spawns` across a baseline property saying
  ;; one path runs outside the run. The baseline was in the prompt; what was missing was which of
  ;; its properties bear on the claim being rewritten.
  (let [baseline {:format :baseline :strata [] :seq 6
                  :model {:elements [{:id "canvas.order/aggregate" :sort :module}]
                          :claims [{:id "band-runs-outside" :about ["canvas.order/aggregate"]
                                    :statement "a band run takes no lock and keeps no record"
                                    :falsified-by "a band run holding the lock"
                                    :evidence {:by :round}}
                                   {:id "unrelated" :about ["canvas.other/x"]
                                    :statement "something else"
                                    :falsified-by "x" :evidence {:by :round}}]}}
        p (record/design-amend-prompt
           {:design a-model-design :baseline baseline :recommend :amend :reason "r"
            :raised [{:claim-ids ["rounded-once"] :claim "a band run rounds twice"}]
            :findings [{:claim-id "rounded-once" :cites ["[rounded-once]"] :claim "twice"}]
            :out-path "/run/a.edn"})]
    (is (str/includes? p "- [band-runs-outside] (the baseline's, load-bearing) a band run takes no lock"))
    (is (not (str/includes? p "- [unrelated]")))
    (is (str/includes? p "a quantifier you widen must not\ncross a property the baseline holds"))
    (is (str/includes? p "REPAIR THE CLASS, NOT THE INSTANCE"))))

(deftest a-decision-confirming-what-it-refutes-is-sent-back-once
  ;; Seen live: a judge confirmed a claim with four citations and filed its only finding against the
  ;; same claim. `rule` let the finding win silently, so the claim read refuted at end.
  (let [calls    (atom [])
        finding  {:claim-id "c1" :check "goal_served" :cites ["x"] :claim "c1 misses the batch path"}
        answer-1 {:recommend "amend" :reason "r" :asks "a"
                  :checks [{:check "goal_served" :status "broken" :note "n"}]
                  :confirmed [{:id "c1" :evidence ["a.clj:1"] :at_this_tree "holds"}]
                  :findings [finding] :unchecked [] :relation_rulings []}
        answer-2 (assoc answer-1 :confirmed [])
        run      (fn [answers]
                   (reset! calls [])
                   (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                                 ws/latest-entry (fn [_ _ k] (when (= :design k) (assoc a-design :seq 5)))
                                 standing/of-design (constantly {:decidable? true})
                                 stages/discover-baseline (fn [_ _] {:format :baseline :seq 1})
                                 stages/read-stance (constantly nil)
                                 record/discover-intent (constantly nil)
                                 record/run-round! (fn [opts]
                                                     (let [n (count (swap! calls conj opts))]
                                                       (answers (dec n))))]
                     (record/design-decision! {:cwd "/w" :run-id "r1" :label "l"})))
        out      (run [{:ok (json/generate-string answer-1)} {:ok (json/generate-string answer-2)}])]
    (is (= 2 (count @calls)) "sent back once, not resolved by nido")
    (is (str/includes? (:prompt (second @calls)) "[c1] found: c1 misses the batch path")
        "the judge is shown what it said both ways")
    (is (= (:schema (first @calls)) (:schema (second @calls))) "and answers the same decision again")
    (is (= ["c1"] (:self-contradicted out)) "the inconsistency is recorded where it was made")
    (is (report/validate-event :design-decision out) "and the ledger takes the decision")
    (let [out (run [{:ok (json/generate-string answer-1)} {:outcome :codex-failed :detail "x"}])]
      (is (= :amend (:recommend out)) "a re-ask that fails leaves the first answer standing")
      (is (= ["c1"] (:self-contradicted out))))))
