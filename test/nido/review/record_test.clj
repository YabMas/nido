(ns nido.review.record-test
  "The pure half of a round over a record: whether a baseline round is worth
   running, what the prompt puts in front of the judge, and what an answer has to
   look like to be recorded. The codex call itself is a seam and is not exercised
   here."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.report :as report]
   [nido.review.codex :as codex]
   [nido.review.record :as record]
   [nido.review.stages :as stages]))

(def ^:private baseline
  {:format       :baseline
   :seq          3
   :area         "order totalling"
   :bounded-by   "everything that reads or writes a money amount on an order"
   :shape        "The aggregate is the only thing that sums lines."
   :modules      [{:id "mod-the-order-aggregate" :module "the order aggregate"
                   :hides "the order in which lines are summed"
                   :interface "an order's total"}
                  {:id "mod-the-invoice-reader" :module "the invoice reader"
                   :hides "the invoice document's layout"
                   :interface "renders a total it is handed"}]
   :composition  "Only the aggregate can see the lines, so only it can sum them;
                  the invoice reader consumes the total it produces."
   :load-bearing [{:id "c1" :property "the aggregate is the only summing path"
                   :falsified-by "a caller outside the aggregate that reads lines and sums them"
                   :readings [{:lens :parnas/dependency :verdict :on-interface
                               :because "callers take the total, never the lines"}]
                   :evidence ["src/order/aggregate.clj:12"]}]
   :health       [{:id "invoice-resums" :axis :design
                   :observation "two summing paths where the design claims one"
                   :evidence ["src/order/invoice.clj:88"]}]
   :read         ["src/order/aggregate.clj"]
   :unknowns     ["whether the CSV importer bypasses the aggregate"]})

(def ^:private design
  {:format     :design
   :seq        4
   :summary    "Rounding moves to a single point on the order total."
   :shape      "One rounding boundary at the order aggregate."
   :invariants ["a total is rounded exactly once"]
   :standing   {:relation :conforms}
   :baseline   {:seq 3 :relation :within}
   :effort     :M})

;; ── Worth running ───────────────────────────────────────────────────────────

(deftest a-baseline-with-checkable-claims-is-worth-verifying
  (is (record/baseline-round-worth-running? baseline))
  (is (record/baseline-round-worth-running?
       (assoc baseline :load-bearing [] :health (:health baseline)))
      "health alone is checkable — every observation carries evidence"))

(deftest a-baseline-with-nothing-checkable-is-not
  (is (not (record/baseline-round-worth-running? nil)))
  (is (not (record/baseline-round-worth-running?
            (dissoc (assoc baseline :load-bearing []) :health)))
      "nothing to refute means a round could only produce prose"))

;; ── What the judge is shown ─────────────────────────────────────────────────

(deftest the-baseline-prompt-asks-about-the-decomposition
  (let [p (record/baseline-prompt {:baseline baseline})]
    (testing "the modules and what each hides"
      (is (str/includes? p "MODULES — the decomposition claimed"))
      (is (str/includes? p "hides:     the order in which lines are summed"))
      (is (str/includes? p "interface: an order's total")))
    (testing "and how they are claimed to produce the behaviour"
      (is (str/includes? p "COMPOSITION — how those are claimed"))
      (is (str/includes? p "Only the aggregate can see the lines")))
    (testing "each claim arrives with what would refute it, and how it was read"
      (is (str/includes? p "the aggregate is the only summing path"))
      (is (re-find #"\[c\d+\] the aggregate" p) "and the claim carries its id")
      (is (str/includes? p "refuted by: a caller outside the aggregate"))
      (is (str/includes? p "read as parnas/dependency = on-interface")))
    (testing "and the perspectives themselves, so both sides read a verdict alike"
      (is (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH"))
      (is (str/includes? p "from Out of the Tar Pit"))
      (is (str/includes? p "A READING IS A CLAIM TOO")))
    (testing "the refs are still handed over, as where to look"
      (is (str/includes? p "src/order/aggregate.clj:12")))
    (is (str/includes? p "SUFFICIENT IS THE EXPECTED OUTCOME"))
    (is (str/includes? p "MUST cite"))
    (is (str/includes? p "INSUFFICIENT"))
    (is (str/includes? p "whether the CSV importer bypasses the aggregate")
        "a declared unknown is honesty already recorded, not a finding to make")))

(deftest the-baseline-judge-states-what-a-narrowing-excludes-not-its-wording
  ;; Watched: a judge's :needs dictated the replacement statement, the amender adopted it verbatim,
  ;; and the next round refuted exactly that statement.
  (let [p (record/baseline-prompt {:baseline baseline})]
    (is (str/includes? p "narrowing must EXCLUDE")
        "wording a judge supplies is written in unread, and is the next reading's finding")
    (is (not (str/includes? p "REFUTED READING AFTER READING"))
        "a record with no refutation run tells the judge nothing about one"))
  (is (str/includes? (get-in (json/parse-string (#'record/baseline-review-schema #{}) true)
                             [:properties :findings :items :properties :needs :description])
                     "never replacement wording")
      "the schema's field description is what the judge reads as it writes :needs")
  (let [p (record/baseline-prompt {:baseline baseline
                                   :running {"c1" 2 "invoice-resums" 3 "mod-the-order-aggregate" 4}})]
    (is (str/includes? p "[c1] refuted 2 readings running"))
    (is (str/includes? p "narrows it to the paths you read")
        "a claim reworded and refuted again is narrowed or withdrawn, never extended")
    (is (str/includes? p "Never ask it to cover more"))
    (is (not (str/includes? p "[invoice-resums] refuted"))
        "an observation is offered no withdrawal, so the judge is not told to propose one")
    (is (not (str/includes? p "[mod-the-order-aggregate] refuted"))
        "an element's enumeration has its own repair, restatement by category"))
  (testing "one refutation is an ordinary reading"
    (is (not (str/includes? (record/baseline-prompt {:baseline baseline :running {"c1" 1}})
                            "REFUTED READING AFTER READING"))))
  (testing "a settled claim is no check, and nothing is asked of its :needs"
    (is (not (str/includes? (record/baseline-prompt {:baseline baseline :running {"c1" 2}
                                                     :settled {"c1" {:seq 4}}})
                            "REFUTED READING AFTER READING")))))

(deftest the-baseline-judge-is-shown-every-counterexample-a-read-subject-was-refuted-with
  ;; Watched: a clause refuted twice in one run was rewritten, confirmed by two judges at the same
  ;; text and tree, and refuted a third time on the very counterexample the amender had been shown.
  (let [refuted {"c1"             [{:seq 5 :claim "billing/totals.clj sums lines itself"
                                    :cites ["src/billing/totals.clj:40"]}
                                   {:seq 7 :claim "the CSV importer sums lines" :cites []}]
                 "invoice-resums" [{:seq 7 :claim "the invoice takes the aggregate's total" :cites []}]}
        p       (record/baseline-prompt {:baseline baseline :refuted-with refuted})]
    (is (str/includes? p "REFUTED BEFORE IN THIS RECORD'S LINEAGE")
        "a judge reading a rewrite cold re-confirms what an earlier judge already refuted")
    (is (str/includes? p "billing/totals.clj sums lines itself")
        "every counterexample, the oldest included: a confirmation since does not make one false")
    (is (str/includes? p "citing: src/billing/totals.clj:40")
        "the citation is what lets the judge go and look rather than take the claim on trust")
    (is (str/includes? p "answers EVERY counterexample listed under it")
        "a confirmation that does not answer a known counterexample is the sample that missed it")
    (testing "a counterexample FOUND AGAINST BEFORE already shows is not shown twice"
      (let [p (record/baseline-prompt {:baseline baseline :refuted-with refuted
                                       :prior {"c1" {:seq 7 :finding {:claim "the CSV importer sums lines"}}}})]
        (is (= 1 (count (re-seq #"the CSV importer sums lines" p))))
        (is (str/includes? p "billing/totals.clj sums lines itself"))))
    (testing "a settled subject is no check, and its refutations are not put to the judge"
      (let [p (record/baseline-prompt {:baseline baseline :refuted-with refuted
                                       :settled {"c1" {:seq 4}}})]
        (is (not (str/includes? p "billing/totals.clj sums lines itself")))
        (is (str/includes? p "the invoice takes the aggregate's total")))))
  (is (not (str/includes? (record/baseline-prompt {:baseline baseline})
                          "REFUTED BEFORE IN THIS RECORD'S LINEAGE"))
      "a lineage that refuted nothing tells the judge nothing"))

(deftest the-baseline-prompt-refuses-the-plane-that-never-converges
  ;; The failure this is aimed at: asked to check a baseline against a large
  ;; subsystem, a judge finds true things about it forever. An implementation
  ;; has no fixed point; a decomposition does.
  (let [p (record/baseline-prompt {:baseline baseline})]
    (is (str/includes? p "not reviewing the code for\ndefects"))
    (is (str/includes? p "belongs to code\nreview"))
    (is (str/includes? p "does that specific counterexample exist"))
    (is (str/includes? p "Neither is a finding that reports a bug in code the baseline"))))

(deftest a-baseline-from-before-the-move-still-makes-a-prompt
  ;; Legacy records are readable, so a round over one has to be too — it simply
  ;; has no decomposition to ask about.
  (let [p (record/baseline-prompt
           {:baseline (dissoc baseline :modules :composition)})]
    (is (not (str/includes? p "MODULES —")))
    (is (not (str/includes? p "COMPOSITION — how those are claimed")))
    (is (str/includes? p "the aggregate is the only summing path"))))

;; ── the shared model, as a judge reads it ────────────────────────────────────

(def ^:private model-baseline
  (-> baseline
      (dissoc :modules :composition :load-bearing)
      (assoc :model {:elements [{:id "canvas.order/aggregate" :sort :module
                                 :hides "the order in which lines are summed"
                                 :interface "an order's total"}]
                     :claims   [{:id "one-summing-path" :about ["canvas.order/aggregate"]
                                 :statement "the aggregate is the only summing path"
                                 :falsified-by "a caller outside the aggregate that reads lines and sums them"
                                 :evidence {:by :test :tests ["order.aggregate-test/one-path"]}
                                 :read-at ["src/order/aggregate.clj:12"]}]})))

(deftest a-model-baseline-shows-its-elements-and-claims-by-id
  (let [p (record/baseline-prompt {:baseline model-baseline})]
    (is (str/includes? p "ELEMENTS —"))
    (is (str/includes? p "[canvas.order/aggregate] (module)"))
    (is (str/includes? p "[one-summing-path] the aggregate is the only summing path"))
    (is (str/includes? p "about:      canvas.order/aggregate")
        "a claim says which elements it is about, by the ids the judge can cite back")
    (is (str/includes? p "checked by: tests order.aggregate-test/one-path"))
    (is (not (str/includes? p "LOAD-BEARING —")) "the survey section has nothing to show for a model")
    (is (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH")
        "a model carries readings as the survey did, so the lenses are shown")))

(deftest a-model-baseline-is-worth-verifying-on-its-claims-alone
  (is (record/baseline-round-worth-running? (dissoc model-baseline :health))))

(deftest a-model-design-is-judged-claim-by-claim
  (let [d (-> design
              (dissoc :invariants)
              (assoc :model {:elements [{:id "canvas.order/aggregate" :sort :module}]
                             :claims   [{:id "rounded-once" :about ["canvas.order/aggregate"]
                                         :statement "a total is rounded exactly once"
                                         :falsified-by "two rounding calls reached for one total"
                                         :evidence {:by :round}}]}))
        p (record/design-prompt {:design d})]
    (is (str/includes? p "[rounded-once] a total is rounded exactly once"))
    (is (not (str/includes? p "Invariants:")))
    (is (str/includes? p "Populate confirmed with the claims you checked"))
    (is (str/includes? p "RULE ON EVERY CLAIM YOU ARE ASKED TO CHECK")
        "a claim left without a ruling is what stops a proceed")))

(def ^:private any-era
  "Every check of either era, for an answer whose era is not what the test is about."
  (into #{} (concat report/derivations report/strata-derivations)))

(deftest a-decision-records-the-claims-its-judge-confirmed
  (let [r (record/parse-design-decision
           (json/generate-string {:recommend "proceed" :reason "r" :asks "worth it?"
                                  :checks [{:check "relation_honest" :status "held" :note "n"}]
                                  :findings [] :confirmed ["[rounded-once]" "lines-exact" ""]})
           4 any-era)]
    (is (= ["rounded-once" "lines-exact"] (:confirmed r)) "ids, brackets and blanks taken off")
    (is (report/validate-event :design-decision r) "and the ledger takes it")))

(deftest a-commitment-the-code-does-not-meet-yet-is-owed-not-confirmed
  ;; A pre-build round confirms claims as sound commitments, and settlement read them as true of the
  ;; tree the judge read — so a later round at that tree skipped claims the code refutes.
  (let [r (record/parse-design-decision
           (json/generate-string {:recommend "proceed" :reason "r" :asks "worth it?"
                                  :checks [{:check "relation_honest" :status "held" :note "n"}]
                                  :findings [] :unchecked []
                                  :confirmed [{:id "rounded-once" :evidence ["src/a.clj:3"] :at_this_tree "holds"}
                                              {:id "lines-exact" :evidence ["src/a.clj:9"] :at_this_tree "owed"}]})
           4 any-era)]
    (is (= ["rounded-once"] (:confirmed r)))
    (is (= {"rounded-once" ["src/a.clj:3"]} (:checked-at r)))
    (is (= ["lines-exact"] (:owed r)))
    (is (report/validate-event :design-decision r))))

(deftest a-finding-keyed-to-no-claim-is-paired-with-the-one-its-prose-names
  ;; agentic-rag R5: the finding was about retrieval-composition and named no claim id, so the claim
  ;; stayed :unruled and the figures counted the refutation against "the record".
  (let [answer (fn [& findings]
                 (json/generate-string {:recommend "amend" :reason "r" :asks "a"
                                        :checks [{:check "goal_served" :status "held" :note "n"}]
                                        :findings (vec findings)}))
        parse  #(record/parse-design-decision % 4 any-era #{"h1"} nil
                                              #{"retrieval-composition" "window-clips"})
        one    (first (:findings (parse (answer {:claim "the retrieval composition reads one source"
                                                 :cites ["record: retrieval-composition"]}))))]
    (is (= "retrieval-composition" (:claim-id one)))
    (is (true? (:claim-paired one)) "the ledger has to say the judge did not name it")
    (is (report/validate-event :design-decision (parse (answer {:claim "retrieval composition" :cites ["c"]}))))
    (is (nil? (:claim-id (first (:findings (parse (answer {:claim "retrieval composition and window clips"
                                                            :cites ["c"]}))))))
        "which of two claims a finding refutes is the judge's to say")
    (is (= "window-clips" (:claim-id (first (:findings (parse (answer {:claim-id "window-clips"
                                                                       :claim "retrieval composition"
                                                                       :cites ["c"]}))))))
        "a claim the judge named is never re-paired")
    (is (nil? (:claim-id (first (:findings (parse (answer {:claim "retrieval composition" :cites ["h1"]}))))))
        "a route cited beside the claim takes the finding off it, as it does a claim the judge named")))

;; Watched: a design listing modal-footer under :baseline :breaks, and a judge confirming modal-footer
;; holds at the pre-change tree — which cleared its refutation run and counted it confirmed, while a
;; :breaks id that was no element of the design was dropped.
(deftest a-holds-of-what-the-design-breaks-is-owed-not-confirmed
  (let [result {:confirmed  ["modal-footer" "rounded-once"]
                :checked-at {"modal-footer" ["src/modals.clj:270"] "rounded-once" ["src/a.clj:3"]}}
        checks #{"modal-footer" "rounded-once"}
        r      (#'record/rule result checks checks ["[modal-footer]"])]
    (is (= ["rounded-once"] (:confirmed r))
        "a truth about the code the design says it breaks is not a reading of the design")
    (is (= ["modal-footer"] (:owed r)) "it is still ruled on, so it does not stop the round as :unruled")
    (is (not (contains? (:checked-at r) "modal-footer")))
    (is (= {"modal-footer" 1}
           (record/refuted-running {:model {:claims [{:id "modal-footer"}]} :baseline {:breaks ["[modal-footer]"]}}
                                   [{:findings [{:claim-id "modal-footer"}]} r]))
        "and the subject's refutation run survives it")
    (is (= ["modal-footer" "rounded-once"] (:confirmed (#'record/rule result checks checks)))
        "a round with no :breaks confirms as before")))

(deftest an-owed-ruling-keeps-what-the-judge-read
  ;; Owed rulings reversed earlier refutations with file:line evidence in the judge's answer, and the
  ;; ledger kept a bare id list: neither the reversal nor what it rested on could be read back.
  (let [r (record/parse-design-decision
           (json/generate-string {:recommend "proceed" :reason "r" :asks "worth it?"
                                  :checks [{:check "relation_honest" :status "held" :note "n"}]
                                  :findings [] :unchecked []
                                  :confirmed [{:id "lines-exact" :evidence ["src/a.clj:9"] :at_this_tree "owed"}]})
           4 any-era)]
    (is (= {"lines-exact" ["src/a.clj:9"]} (:owed-at r)))
    (is (report/validate-event :design-decision r) "and the ledger takes it"))
  (let [result {:confirmed  ["modal-footer"] :checked-at {"modal-footer" ["src/modals.clj:270"]}}]
    (is (= {"modal-footer" ["src/modals.clj:270"]}
           (:owed-at (#'record/rule result #{"modal-footer"} #{"modal-footer"} ["modal-footer"])))
        "a holds turned owed by :breaks keeps what was read, under the ruling it now is")))

(deftest an-owed-ruling-against-an-earlier-finding-is-an-overturn
  (let [r (#'record/with-readings {:format :design-decision :owed ["writers-state-order"]}
                                  (constantly true)
                                  {:prior {"writers-state-order" {:ws-id "ws-1" :seq 55 :restated? false}}
                                   :asked ["writers-state-order"]})]
    (is (= [{:id "writers-state-order" :seq 55 :ws-id "ws-1" :restated? false}] (:overturns r))
        "holding a refuted claim sound reverses the refutation, and the ledger has to say so")
    (is (= ["writers-state-order"] (:read-once r)))))

;; Watched (9e0979): artifact-read-whole was reworded and confirmed, refuted, reworded and confirmed,
;; refuted — four refutations across eight rounds, each confirmation the first reading of new words in
;; a round with findings elsewhere. None was :read-once, since that marks a round that would hold, so
;; each ended the run, the count never passed 1 and the cap ended :max-iters instead of :unfixable.
(deftest a-first-reading-ends-no-refutation-run-whatever-the-verdict
  (let [confirm (#'record/with-readings {:format :baseline-review :confirmed ["artifact-read-whole"]
                                         :checked-at {"artifact-read-whole" ["a.clj:1"]}
                                         :findings [{:claim-id "other" :cites ["b.clj:2"] :claim "no"}]}
                                        (constantly false)
                                        {:asked ["artifact-read-whole" "other"]})
        refute  {:format :baseline-review :findings [{:claim-id "artifact-read-whole" :cites ["c.clj:3"] :claim "no"}]}]
    (is (nil? (:read-once confirm)) "a round that does not hold owes no second reading of itself")
    (is (= ["artifact-read-whole"] (:first-reading confirm))
        "but its confirmation of new words is still one reading of them, and the record has to say so")
    (is (report/validate-event :baseline-review (assoc confirm :verdict :falsified :reason "r" :baseline-seq 1))
        "and the ledger takes it")
    (is (= 2 (get (record/refuted-running nil [refute confirm refute]) "artifact-read-whole"))
        "a claim alternating confirmed and refuted is spent by the refutations, not reset by each first reading")
    (is (nil? (get (record/refuted-running nil [refute confirm (assoc confirm :first-reading [])])
                   "artifact-read-whole"))
        "a second confirmation is the pair, and ends the run")))

(deftest an-overturn-says-whether-the-claim-was-restated-since
  ;; Watched: R1 of a run :overturns'd two claims whose earlier wording an earlier run had refuted
  ;; and a hand amendment had restated — and the figures counted the confirmed repairs as the
  ;; judges reversing themselves, beside eight real reversals of unchanged text.
  (let [r (#'record/with-readings {:format :baseline-review :confirmed ["build-job" "sets"]
                                   :checked-at {"build-job" ["a.clj:1"] "sets" ["a.clj:2"]}}
                                  (constantly false)
                                  {:prior {"build-job" {:ws-id "ws-1" :seq 9 :restated? true}
                                           "sets"      {:ws-id "ws-1" :seq 9 :restated? false}}})]
    (is (= [{:id "build-job" :seq 9 :ws-id "ws-1" :restated? true}
            {:id "sets" :seq 9 :ws-id "ws-1" :restated? false}]
           (:overturns r))
        "a confirmation of new words is a repair confirmed, of the same words a reversal, and only the row can say which")
    (is (report/validate-event :baseline-review (assoc r :verdict :sufficient :reason "r" :baseline-seq 1))
        "and the ledger takes it")))

(deftest a-clause-ruled-false-after-the-run-held-it-is-a-contradicted-confirmation
  ;; Watched: R1 held phase-1#2, R2 ruled the same promise false; the phase was false both rounds on
  ;; different clauses, so its readings read F,F and nothing showed R1's holding withdrawn.
  (let [held (#'record/held-clauses
              [{:seq 31 :clause-readings {"phase-1#2" {:ruling :held :text "one stored profile."}
                                          "phase-1#3" {:ruling :false :text "no re-read."}
                                          "c#1"       {:ruling :held :text "old words."}}}
               {:seq 33 :clause-readings {"phase-1#3" {:ruling :held :text "no re-read."}}}])
        r    (#'record/with-readings {:format :baseline-review
                                      :clause-readings {"phase-1#2" {:ruling :false :text "one stored profile."}
                                                        "phase-1#3" {:ruling :false :text "no re-read."}
                                                        "c#1"       {:ruling :false :text "new words."}}}
                                     (constantly false)
                                     {:held held})]
    (is (= [{:clause "phase-1#2" :seq 31} {:clause "phase-1#3" :seq 33}] (:contradicts-held-clause r))
        "the holding withdrawn is the run's latest of that clause, named by the entry that held it")
    (is (not-any? #(= "c#1" (:clause %)) (:contradicts-held-clause r))
        "a key is a position: the same key over new words is another clause, and ruling it false denies nothing")
    (is (report/validate-event :baseline-review (assoc r :verdict :sufficient :reason "r" :baseline-seq 1))
        "and the ledger takes it"))
  (is (= {} (#'record/held-clauses [{:seq 1 :clause-readings {"c#1" {:ruling :held :text "x"}}}
                                    {:seq 2 :clause-readings {"c#1" {:ruling :false :text "x"}}}]))
      "a clause the run last ruled false is held by nobody, so ruling it false again withdraws nothing"))

(deftest a-judgement-keeps-what-each-clause-was-ruled-on
  (let [owed {"c" [{:key "c#1" :text "It reads once." :field :statement :search? false}
                   {:key "c#2" :text "Nothing else writes it." :field :statement :search? true}
                   {:key "c#3" :text "It caches." :field :statement :search? false}]}
        r    (#'record/held-to-clauses
              {:findings [{:claim-id "c" :cites ["x"] :claim "no"}]
               :clause-rulings {"c#1" {:ruling :held :evidence ["a.clj:1"] :search ""}
                                "c#2" {:ruling :held :evidence ["a.clj:2"] :search ""}
                                "c#3" {:ruling :false :evidence [] :search ""}}}
              owed)]
    (is (= {"c#1" {:ruling :held :text "It reads once."}
            "c#3" {:ruling :false :text "It caches."}}
           (:clause-readings r))
        "the words are kept with the ruling, since a later amendment can put other words at the key")
    (is (not (contains? (:clause-readings r) "c#2"))
        "a held whole-tree clause naming no search is no holding, and nothing may later be said to deny it")
    (is (not (contains? r :clause-rulings)) "the answer's own rulings stay in its out.json")))

(def ^:private codex {:reviewer :codex})
(def ^:private claude {:reviewer :claude})

;; Watched (da6d82): m-facade, its words unchanged, was confirmed by codex in R1 and by claude in
;; R2, but a sibling's amendment had knocked the R1 confirmation off standing, so R2 recorded it as
;; another :first-reading and its run stayed at 3 — named :unfixable on a claim nobody touched.
(deftest an-independent-confirmation-pairs-with-the-reading-a-knock-on-reopened
  (let [read      (fn [judge knock-on]
                    (#'record/with-readings {:format :baseline-review :code-identity "t"
                                             :confirmed ["m-facade"] :checked-at {"m-facade" ["a.clj:1"]}
                                             :findings [{:claim-id "other" :cites ["b.clj:2"] :claim "no"}]
                                             :judged-by judge}
                                            (constantly false)
                                            {:knock-ons {"m-facade" knock-on}
                                             :asked ["m-facade" "other"]}))
        knocked   {:ws-id "ws-1" :seq 17 :moved ["text-less"] :judged-by codex}
        refute    {:format :baseline-review
                   :findings [{:claim-id "m-facade" :cites ["c.clj:3"] :claim "no"}]}
        paired    (read claude knocked)]
    (is (nil? (:first-reading paired))
        "another judge confirming the same words beside an amended sibling is the second reading")
    (is (nil? (get (record/refuted-running nil [refute refute refute paired]) "m-facade"))
        "so the run on the untouched claim ends")
    (is (nil? (:splits paired)) "a knock-on pairs; it is no reading the confirmation is set against")
    (is (= ["m-facade"] (:first-reading (read codex knocked)))
        "the judge that confirmed it before agreeing with itself is still one sample")
    (is (= ["m-facade"] (:first-reading (read claude (assoc knocked :elements-moved? true))))
        "an element entry the claim is about moved, so the earlier judge read something else")
    (is (= ["m-facade"] (:first-reading (#'record/with-readings
                                          {:format :baseline-review :confirmed ["m-facade"]
                                           :checked-at {"m-facade" ["a.clj:1"]} :judged-by claude}
                                          (constantly false)
                                          {:knock-ons {"m-facade" knocked} :asked ["m-facade"]})))
        "a record that read no single tree pairs with nothing a knock-on names")))

(deftest a-holds-reading-then-an-owed-one-is-kept-as-unpaired
  ;; Watched: no-cutoff held on round 1 and owed on round 2 at one tree; the run kept the owed
  ;; ruling, and neither the report nor the figures showed there had been two.
  (let [r (#'record/with-readings {:format :design-decision :owed ["no-cutoff"] :code-identity "t"
                                   :judged-by codex}
                                  (constantly true)
                                  {:standing {"no-cutoff" {:ws-id "ws-1" :seq 3 :judged-by codex}}
                                   :asked ["no-cutoff"]})]
    (is (= [{:id "no-cutoff" :was :holds :now :owed}] (:unpaired r)))
    (is (= ["no-cutoff"] (:read-once r)) "one judge reading a claim twice is one sample, so it is read again")
    (is (report/validate-event :design-decision (assoc r :reason "r" :asks "a" :recommend :proceed
                                                       :design-seq 4
                                                       :checks [{:check :relation-honest :status :held :note "n"}]))
        "and the ledger takes it"))
  (let [r (#'record/with-readings {:format :design-decision :owed ["no-cutoff"] :code-identity "t"
                                   :judged-by claude}
                                  (constantly true)
                                  {:standing {"no-cutoff" {:ws-id "ws-1" :seq 3 :judged-by codex}}
                                   :asked ["no-cutoff"]})]
    (is (= [{:id "no-cutoff" :was :holds :now :owed}] (:unpaired r)) "the flip is still recorded")
    (is (nil? (:read-once r))
        "watched: a 'carries no X' claim alternated holds and owed for two rounds — from two judges both say sound, and pair")))

(deftest only-a-reading-by-another-judge-pairs
  ;; Watched: three codex readings of one record at one tree agreed and the fourth refuted a claim
  ;; with a race in unchanged code — a pair from one judge is that judge sampled twice.
  (is (= ["no-cutoff"] (:read-once (#'record/with-readings {:format :design-decision :owed ["no-cutoff"]
                                                            :judged-by codex}
                                                           (constantly true)
                                                           {:carried {"no-cutoff" {:ruling :owed :judged-by codex}}
                                                            :asked ["no-cutoff"]})))
      "the same judge agreeing with itself settles nothing")
  (is (nil? (:read-once (#'record/with-readings {:format :design-decision :owed ["no-cutoff"]
                                                 :judged-by claude}
                                                (constantly true)
                                                {:carried {"no-cutoff" {:ruling :owed :judged-by codex}}
                                                 :asked ["no-cutoff"]})))
      "two judges agreeing pair")
  (is (= ["no-cutoff"] (:read-once (#'record/with-readings {:format :design-decision :owed ["no-cutoff"]}
                                                           (constantly true)
                                                           {:carried {"no-cutoff" {:ruling :owed :judged-by codex}}
                                                            :asked ["no-cutoff"]})))
      "a reading naming no judge cannot be told from the first, so it pairs nothing"))

(deftest a-reading-pairs-with-one-comparison
  ;; Watched: an id was reported as completing a ledger pairing and as owing a second reading at
  ;; once, forcing a round the ledger then treated as settled.
  (let [r (#'record/with-readings {:format :design-decision :confirmed ["a"] :checked-at {"a" ["x:1"]}
                                   :code-identity "t" :judged-by claude}
                                  (constantly true)
                                  {:standing {"a" {:ws-id "ws-1" :seq 3 :judged-by codex}}
                                   :single #{"a"}
                                   :carried {"a" {:ruling :owed :judged-by claude}}
                                   :asked ["a"]})]
    (is (= ["a"] (map :id (:paired-with r))))
    (is (nil? (:read-once r)) "an id it pairs is never also read once")))

(deftest a-reading-refuting-what-the-one-before-held-is-a-split
  ;; Watched: enrolment-deleter was confirmed on round 1 and refuted on round 2 at one tree, and the
  ;; ledger kept two entries nothing related.
  (let [found [{:claim-id "enrolment-deleter" :cites ["c"] :claim "x"}
               {:claim-id "settled-one" :cites ["c"] :claim "y"}]
        r     (#'record/with-readings {:format :baseline-review :findings found}
                                      (constantly false)
                                      {:carried {"enrolment-deleter" {:ruling :holds :judged-by codex}
                                                 "settled-one"       {:ruling :holds :judged-by codex}}
                                       :settled {"settled-one" {:ws-id "ws-1" :seq 2}}})]
    (is (= [{:id "enrolment-deleter" :was :holds}] (:splits r)))
    (is (= ["settled-one"] (map :id (:overrides-settled r)))
        "a settled confirmation refuted is an override, said once and not twice")
    (is (report/validate-event :baseline-review (assoc r :verdict :falsified :reason "r" :baseline-seq 1))
        "and the ledger takes it")))

(deftest a-carried-reading-the-round-left-unchecked-stays-read-once
  (let [r (#'record/with-readings {:format :baseline-review
                                   :unchecked [{:id "structure-not-exposed" :reason "needs response data"}]}
                                  (constantly true)
                                  {:carried {"structure-not-exposed" {:ruling :holds :judged-by codex}}
                                   :asked ["structure-not-exposed"]})]
    (is (= ["structure-not-exposed"] (:read-once r))
        "confirmed once and then not read is one reading, and a run must not stop on it"))
  (let [r (#'record/with-readings {:format :baseline-review
                                   :unchecked [{:id "structure-not-exposed" :reason "sandbox denied the eval"
                                                :tool-failure true}]}
                                  (constantly true)
                                  {:carried {"structure-not-exposed" {:ruling :holds :judged-by codex}}
                                   :asked ["structure-not-exposed"]})]
    (is (nil? (:read-once r))
        "watched: a sandbox-blocked eval forced a second reading that could only fail the same way")))

(deftest figures-measure-how-far-a-confirmation-can-be-trusted
  ;; Watched: claims confirmed two to five times on identical text at an identical tree were then
  ;; refuted on a fact present all along, and nothing in the figures said so — per-subject totals
  ;; cannot tell a judge disagreeing with itself from an amendment repairing a claim.
  (let [r     (fn [n & {:as more}] (merge {:format :baseline-review :seq n :baseline-seq 1
                                           :code-identity "t" :verdict :sufficient} more))
        found (fn [id] {:claim-id id :cites ["x"] :claim "no"})
        es    [(r 1 :confirmed ["a" "b"])
               (r 2 :confirmed ["a"] :findings [(found "b")] :verdict :falsified)
               (r 3 :findings [(found "a")] :verdict :falsified :unchecked [{:id "c" :reason "r"}])]
        f     (record/run-figures es nil (constantly {"a" [:a-text] "b" [:b-text] "c" [:c-text]}))]
    (is (= {"a" "C,C,F" "b" "C,F" "c" "U"} (:readings f)) "each subject's readings, in order")
    (is (= {"a" [2] "b" [1]} (:refuted-after f))
        "how many sound readings of the same text at the same tree each refutation followed")
    (is (= {"a" 1 "b" 1} (:overturned f)) "a refutation of text the run had confirmed")
    (is (= {"a" 1 "b" 1} (:text-splits f)))
    (is (= {:flips 2 :pairs 3} (:flip-rate f)) "of three readings with one before them, two disagreed")
    (is (nil? (:clause-flip-rate f)) "no clause was ruled, so no clause flipped")
    (is (nil? (:flip-rate (record/run-figures es nil (fn [j] (when (= 1 (:seq j)) {"a" [:a-text]})))))
        "a judgement whose record cannot be read back is compared with nothing")
    (is (nil? (:refuted-after (record/run-figures (mapv #(assoc % :code-identity (str "t" (:seq %))) es)
                                                  nil (constantly {"a" [:a-text] "b" [:b-text]}))))
        "readings at different trees are not the same reading repeated")
    (is (= {"a" 1 "b" 1} (:overturned (record/run-figures (mapv #(assoc % :code-identity (str "t" (:seq %))) es)
                                                          nil (constantly {"a" [:a-text] "b" [:b-text]}))))
        "an overturned confirmation is the text's, whatever the tree")))

(deftest a-tool-failure-is-kept-on-the-unchecked-reading
  (is (= [{:id "a" :reason "sandbox denied the eval" :tool-failure true}
          {:id "b" :reason "production data"}]
         (#'record/parse-unchecked [{:id "a" :reason "sandbox denied the eval" :cause "tool-failure"}
                                    {:id "[b]" :reason "production data" :cause "out-of-code"}]))))

(deftest figures-count-each-claim-held-owed
  ;; A claim judged owed every round was in no figure at all, so an owed->holds flip could only be
  ;; found by reading each round's raw decision output.
  (let [d (fn [n & {:as more}] (merge {:format :design-decision :seq n :design-seq 37 :checks []} more))
        f (record/run-figures [(d 1 :owed ["a" "b"]) (d 2 :owed ["a"] :confirmed ["b"])
                               {:format :baseline-review :seq 3 :baseline-seq 1 :owed ["z"]}])]
    (is (= {"a" 2 "b" 1} (:owed f)) "counted per claim, over the run's decisions — a review holds nothing owed")
    (is (nil? (:owed (record/run-figures [(d 1 :confirmed ["a"])]))))))

(deftest figures-count-holds-and-owed-flips-at-one-tree
  (let [d (fn [n ruled & {:as more}]
            (merge {:format :design-decision :seq n :design-seq 37 :code-identity "e76cd2"
                    :recommend :proceed :checks []}
                   (case ruled
                     :holds {:confirmed ["c"] :checked-at {"c" ["src/a.clj:1"]}}
                     :owed  {:owed ["c"]})
                   more))
        f (record/run-figures [(d 1 :holds) (d 2 :owed) (d 3 :owed) (d 4 :holds)
                               (d 5 :owed :code-identity "other")
                               (d 6 :holds :design-seq 38 :code-identity "other")])]
    (is (= {"c" {:holds->owed 1 :owed->holds 1}} (:reading-flips f))
        "only readings of one record at one tree are compared: a moved tree or a new record is no flip"))
  (is (= {"c" 1} (:splits (record/run-figures [{:format :baseline-review :seq 1 :baseline-seq 1
                                                :splits [{:id "c" :was :holds}]}])))))

;; Watched: a judge confirmed chat-door with evidence and filed a stratified gap naming it, and the
;; confirmation was discarded — though a gap refutes nothing.
(deftest a-gap-naming-a-claim-leaves-its-confirmation-standing
  (let [result {:confirmed  ["chat-door"] :checked-at {"chat-door" ["src/chat.clj:12"]}
                :findings   [{:blocks :stratified :claim-id "chat-door" :cites ["c"] :claim "x" :needs "y"}]}
        r      (#'record/rule result #{"chat-door"} #{"chat-door"})]
    (is (= ["chat-door"] (:confirmed r)) "what a gap asks for is more record, not a doubt about the claim")
    (is (nil? (:overrides-settled
               (#'record/with-readings (assoc r :format :baseline-review) (constantly false)
                                       {:settled {"chat-door" {:ws-id "ws-1" :seq 3}}})))
        "nor does it override the settlement it sits beside"))
  (is (empty? (:confirmed (#'record/rule {:confirmed ["chat-door"] :checked-at {"chat-door" ["src/chat.clj:12"]}
                                          :findings [{:claim-id "chat-door" :cites ["c"] :claim "x"}]}
                                         #{"chat-door"} #{"chat-door"})))
      "a refutation naming it still wins over the confirmation"))

(deftest a-decision-leaving-a-claim-unruled-does-not-proceed
  (let [d {:format :design-decision :recommend :proceed :design-seq 4 :reason "r" :asks "a"
           :checks [{:check :relation-honest :status :held :note "n"}]}]
    (is (report/proceeds? d))
    (is (not (report/proceeds? (assoc d :unruled ["lines-exact"])))
        "a proceed that never ruled on a claim it was handed has not derived that nothing blocks it")
    (is (report/validate-event :design-decision (assoc d :unruled ["lines-exact"])))))

(deftest the-decision-prompt-carries-the-four-derivations-and-the-answer-key
  (let [p (record/design-prompt
           {:design (assoc design
                           :rejected [{:alternative "round at render time"
                                       :why-not "moves money math into the view"}]
                           :layers [{:claim "extract the aggregate" :mode :judgment}])
            :baseline baseline
            :stance "two registers of data"
            :intent {:goal "stop the checkout being off by a cent"
                     :done-when ["a multi-line order's total equals its invoice"]}})]
    (is (str/includes? p "relation-honest"))
    (is (str/includes? p "goal-served"))
    (is (str/includes? p "decomposable"))
    (is (str/includes? p "routing-coherent"))
    (is (str/includes? p "ALREADY REJECTED")
        "without the answer key the round re-proposes what was already rejected")
    (is (str/includes? p "round at render time"))
    (is (str/includes? p "stop the checkout being off by a cent"))
    (is (str/includes? p "Done when:"))
    (is (str/includes? p "You do not make the decision"))
    (is (str/includes? p "Never answer it yourself"))))

(deftest the-decision-prompt-holds-each-done-when-line-to-the-clause-that-discharges-it
  (let [with-goal (record/design-prompt
                   {:design design
                    :intent {:goal "bound the reclaims"
                             :done-when ["a crash-exhausted job is not returned by any claim"]}})
        no-lines  (record/design-prompt {:design design :intent {:goal "bound the reclaims"}})]
    (is (str/includes? with-goal "GOAL COVERAGE")
        "a judge ruling claim by claim reads each clause against the code, never against the goal,
         and affirmed a clause contradicting a Done-when line word for word as owed")
    (is (str/includes? with-goal "never owed and never holds")
        "owed promises the code will come to agree with the clause; it cannot also agree with a
         goal line the clause denies")
    (is (not (str/includes? no-lines "GOAL COVERAGE"))
        "with no Done-when lines there is nothing to cover, and the pass would ask for a mapping
         from nothing")))

(deftest the-decision-prompt-reads-a-mixed-claim-as-owed
  (is (str/includes? (record/design-prompt {:design design})
                     "`owed` when ANY clause depends on code not yet built")
      "a claim mixing negative clauses true today with clauses about unbuilt code read owed under
       one judge and holds under the next, at an unchanged tree"))

(deftest the-decision-prompt-shows-a-spin-outs-follow-up-ref
  (let [p (record/design-prompt
           {:design (assoc design :routes [{:health-id "two-content-verdicts" :to :spin-out
                                            :why "revealed, not created" :ref "FU-470"}])})]
    (is (str/includes? p "two-content-verdicts → spin-out (FU-470) — revealed, not created")
        "routing-coherent checks that a spin-out names its follow-up; a judge shown no ref rules
         the routing broken and asks a person for a ref the record already holds")))

(deftest the-decision-prompt-shows-each-seam-and-what-closes-it
  (let [p (record/design-prompt
           {:design (assoc design :seams [{:what "inline and MCP keep two text vocabularies"
                                           :visible-how "both paths still read their own field"
                                           :closed-by :spun-out :ref "FU-470"}
                                          {:what "legacy rows stay unupgraded"
                                           :visible-how "a version column below the recipe's"}])})]
    (is (str/includes? p (str "inline and MCP keep two text vocabularies — visible as: both paths"
                              " still read their own field; spun out as FU-470"))
        "a seam's closing ref is where a spin-out's follow-up is written down; a judge that cannot
         see it rules routing-coherent broken and asks a person for a ref the record already holds")
    (is (str/includes? p "legacy rows stay unupgraded — visible as: a version column below the recipe's\n")
        "a legacy seam names no closure, and saying nothing is more honest than inventing one")))

(deftest the-decision-prompt-asks-a-person-in-their-terms-and-all-at-once
  ;; Each clause answers a run where the person was asked badly: three mechanism-worded questions
  ;; in one, one they said they did not follow; an openly stated deferral left for a second run and
  ;; a second grant; fifteen kept cross-band edges and their cycles granted as "accounted for".
  (let [p (record/design-prompt {:design design})]
    (is (str/includes? p "ONE question per decision they must make")
        "bundled questions are answered as one, or not at all")
    (is (str/includes? p "never as a claim id or the\nmechanism")
        "a person answers what changes for users or the operator, not the record's vocabulary")
    (is (str/includes? p "Gather EVERY goal or scope gap")
        "a gap left out of this ask is a second interruption on the next run")
    (is (str/includes? p "a deferral\nit states openly included")
        "a deferral the record states is scope the person grants, not settled by being written")
    (is (str/includes? p "names how many dependencies it keeps\nand every cycle they form")
        "a recorded violation is granted at its size, never as \"accounted for\"")))

(deftest the-decision-prompt-shows-the-baseline-it-derives-against
  ;; The defect this is aimed at: the prompt named the baseline's modules,
  ;; extension points and health observations in its instructions and printed
  ;; none of them. A judge cannot tell "the baseline does not say" from "I was not
  ;; shown it", the only recommendation that fits the first is :resurvey, and
  ;; that is what one workstream got seven times running without ever reaching a
  ;; decision.
  (let [p (record/design-prompt
           {:design (assoc design :routes [{:health-id "invoice-resums" :to :fix-here}])
            :baseline (assoc baseline
                             :extension-points
                             [{:at "the aggregate's line reducer"
                               :how "a new line kind registers a contribution"}])})]
    (testing "relation-honest and decomposable read the decomposition"
      (is (str/includes? p "MODULES — the decomposition claimed"))
      (is (str/includes? p "hides:     the order in which lines are summed"))
      (is (str/includes? p "COMPOSITION — how those are claimed"))
      (is (str/includes? p "SHAPE: The aggregate is the only thing that sums lines.")))
    (testing "the declared relation has its yardstick"
      (is (str/includes? p "EXTENSION POINTS"))
      (is (str/includes? p "a new line kind registers a contribution")))
    (testing "routing-coherent can resolve the ids the design routes by"
      (is (str/includes? p "- invoice-resums [design]"))
      (is (str/includes? p "two summing paths where the design claims one")))
    (testing "a claim keeps what would refute it, not just where to look"
      (is (str/includes? p "refuted by: a caller outside the aggregate"))
      (is (str/includes? p "src/order/aggregate.clj:12")))
    (testing "and what the baseline already declared it did not determine"
      (is (str/includes? p "DECLARED NOT DETERMINED"))
      (is (str/includes? p "whether the CSV importer bypasses the aggregate")))))

(deftest a-baseline-from-before-the-move-still-makes-a-decision-prompt
  (let [p (record/design-prompt
           {:design design
            :baseline (dissoc baseline :modules :composition :health
                              :unknowns :extension-points)})]
    (is (not (str/includes? p "MODULES —")))
    (is (not (str/includes? p "EXTENSION POINTS")))
    (is (str/includes? p "the aggregate is the only summing path")
        "what the legacy baseline does carry is still shown")))

(deftest the-decision-prompt-survives-a-design-with-no-baseline-or-stance
  (is (string? (record/design-prompt {:design design}))
      "framing is optional everywhere else in this system; it is here too"))

(def ^:private legacy-design
  "A :design from before the baseline event: no :baseline, and still readable."
  {:format :design :summary "s" :shape "sh" :invariants ["i"]
   :standing {:relation :conforms} :effort :M})

(deftest a-judge-round-keeps-the-prompt-it-sent
  ;; document-structure: the decision prompt was not kept, unlike the amend prompt, so what a judge
  ;; was told about grants and earlier asks was re-derived from the ledger by hand.
  (let [tmp  (str (fs/create-temp-dir))
        sent (atom nil)]
    (with-redefs [cstate/run-dir (fn [_] tmp)
                  codex/run-reviewer! (fn [{:keys [prompt out-path log-path]}]
                                        (reset! sent prompt)
                                        (spit out-path "{}")
                                        {:exit 0 :log-path log-path :judged-by {:reviewer :codex}})]
      (#'record/run-round! {:cwd "/w" :run-id "r" :kind :design-decision
                            :label "design-decision-round-2" :prompt "rule on the claims"})
      (is (str/ends-with? @sent "rule on the claims"))
      (is (= @sent (slurp (str (fs/path tmp "design-decision-round-2-prompt.txt"))))
          "the prompt beside the answer, as the judge received it"))))

(deftest a-legacy-design-does-not-crash-the-prompt
  (let [p (record/design-prompt {:design legacy-design})]
    (is (string? p))
    (is (str/includes? p "Declared against the baseline: NOTHING")
        "the degrade this area takes everywhere else — say the yardstick is
         absent rather than invent one")
    (is (str/includes? p "not itself a finding")))
  ;; The regression this pins: the prompt is built as an ARGUMENT to run-round!,
  ;; so it is evaluated outside that function's catch. A throw here does not
  ;; degrade to "no answer recorded" — it takes the whole task down. :baseline is
  ;; the only field a ledger-legal design can be missing that the prompt calls
  ;; `name` on; :standing is required by both the current and the legacy schema.
  (is (string? (record/design-prompt {:design legacy-design
                                      :baseline nil :stance nil :goals nil}))
      "every other framing input is already optional and stays so"))

;; ── What counts as an answer ────────────────────────────────────────────────

(defn- baseline-json [m] (json/generate-string m))

(deftest a-sufficient-baseline-review-parses-and-needs-no-findings
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "sufficient" :reason "both claims held"
                           :confirmed ["the aggregate is the only summing path"]
                           :findings []})
           3)]
    (is (= :sufficient (:verdict r)))
    (is (= 3 (:baseline-seq r)))
    (is (= ["the aggregate is the only summing path"] (:confirmed r)))
    (is (nil? (:findings r)))
    (is (= r (report/validate-event :baseline-review r))
        "whatever the parser emits has to satisfy the ledger write contract")))

(deftest a-falsified-baseline-review-carries-its-citations
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "falsified" :reason "invoice re-sums"
                           :confirmed []
                           :findings [{:cites ["the aggregate is the only summing path"]
                                       :claim "invoice.clj sums lines directly"
                                       :evidence ["src/order/invoice.clj:88"]}]})
           3)]
    (is (= :falsified (:verdict r)))
    (is (= 1 (count (:findings r))))
    (is (= r (report/validate-event :baseline-review r)))))

(deftest a-finding-that-cites-nothing-is-dropped
  (is (nil? (record/parse-baseline-review
             (baseline-json {:verdict "falsified" :reason "vibes"
                             :findings [{:cites [] :claim "feels shaky"
                                         :evidence []}]})
             3))
      "a non-accurate verdict whose findings all cite nothing is the theatre
       this round exists to prevent — read it as no answer, not as a verdict"))

(deftest an-unknown-verdict-is-a-non-answer
  (is (nil? (record/parse-baseline-review
             (baseline-json {:verdict "looks-fine" :reason "" :findings []}) 3)))
  (is (nil? (record/parse-baseline-review "not json at all" 3))
      "nil is a non-answer; the caller records nothing rather than inventing
       trust it did not earn"))

(deftest a-proceed-decision-parses-with-its-derivations
  (let [r (record/parse-design-decision
           (json/generate-string
            {:recommend "proceed" :reason "nothing derivable blocks it"
             :checks [{:check "relation_honest" :status "held" :note "within holds"}
                      {:check "goal_served" :status "held" :note "no smaller design"}
                      {:check "decomposable" :status "held" :note "two layers state cleanly"}
                      {:check "routing_coherent" :status "held" :note "one story"}]
             :findings []
             :asks "worth doing now, at M, given the invoice work queued behind it?"})
           4 any-era)]
    (is (= :proceed (:recommend r)))
    (is (= 4 (count (:checks r))))
    (is (every? #(= :held (:status %)) (:checks r)))
    (is (str/includes? (:asks r) "worth doing now"))
    (is (= r (report/validate-event :design-decision r)))))

(deftest an-ask-without-a-question-is-refused
  (is (= :asks-blank
         (:refused (record/parse-design-decision
                    (json/generate-string
                     {:recommend "ask" :reason "scope"
                      :checks [{:check "relation_honest" :status "held" :note "ok"}]
                      :findings [] :asks "  "})
                    4 any-era)))
      "an ask stops for a person, and the question is the whole of what it hands them"))

(deftest a-blank-asks-on-anything-but-an-ask-is-nothing-left-for-a-person
  ;; Seen live: sixteen design runs whose grants covered every decision answered asks "" as the
  ;; prompt told them, and every one was discarded whole as :unusable-answer.
  (doseq [[recommend findings] [["proceed" []]
                                ["amend" [{:claim-id "c1" :cites ["a.clj:1"] :claim "c1 misses it"}]]]]
    (let [r (record/parse-design-decision
             (json/generate-string
              {:recommend recommend :reason "the grant covers it"
               :checks [{:check "relation_honest" :status "held" :note "ok"}]
               :findings findings :asks ""})
             4 any-era)]
      (is (= (keyword recommend) (:recommend r)) "a judgement with nothing left to ask is still a judgement")
      (is (not (contains? r :asks))
          "stored blank, it reads to every later round as a question somebody still owes")
      (is (= r (report/validate-event :design-decision r)) "and the ledger takes it"))))

(deftest the-ledger-takes-no-blank-asks
  (let [d {:format :design-decision :recommend :proceed :design-seq 4 :reason "r"
           :checks [{:check :relation-honest :status :held :note "n"}]}]
    (is (report/validate-event :design-decision d))
    (is (thrown? Exception (report/validate-event :design-decision (assoc d :asks " ")))
        "a blank asks is a question nobody can answer, read as one somebody owes")
    (is (thrown? Exception (report/validate-event :design-decision (assoc d :recommend :ask)))
        "an ask with no question stops a person for nothing")))

(deftest a-decision-that-derived-nothing-is-a-non-answer
  (is (= :no-asked-checks
         (:refused (record/parse-design-decision
                    (json/generate-string
                     {:recommend "proceed" :reason "looks good to me"
                      :checks [] :findings [] :asks "ship it?"})
                    4 any-era)))
      "handing a human an unreduced question is the rubber stamp with garnish"))

(deftest a-non-proceed-recommendation-must-carry-findings
  (let [r (record/parse-design-decision
           (json/generate-string
            {:recommend "recut" :reason "feels wrong"
             :checks [{:check "decomposable" :status "broken" :note "cannot state layers"}]
             :findings [] :asks "recut?"})
           4 any-era)]
    (is (= :amend-without-findings (:refused r))
        "saying the design is wrong without citing anything is the same theatre")
    (is (nil? (:format r)) "and nothing in it is a record the ledger could take")))

(deftest a-refusal-names-the-rule-and-keeps-what-was-said
  ;; Seen live: every refusal read "the answer did not satisfy what a round must return", and the
  ;; refused judgement's findings were readable only in the raw answer file.
  (let [parse  #(record/parse-design-decision % 4 any-era)
        result (#'record/judged {:ok (json/generate-string
                                       {:recommend "ask" :reason "scope" :asks ""
                                        :checks [{:check "goal_served" :status "broken" :note "n"}]
                                        :findings [{:claim-id "c1" :cites ["a.clj:1"] :claim "c1 misses it"}]})}
                                parse)]
    (is (= :unusable-answer (:outcome result)))
    (is (= :asks-blank (:refused result)))
    (is (str/includes? (:detail result) "asks-blank") "the report says which rule, not that one was broken")
    (is (= ["c1"] (mapv :claim-id (get-in result [:answer :findings])))
        "a refused judgement's findings are kept for the report, which is the only place they survive")
    (is (= :bad-recommend (:refused (parse (json/generate-string
                                            {:recommend "maybe" :reason "r" :asks "a"
                                             :checks [{:check "goal_served" :status "held" :note "n"}]})))))
    (is (= :parse-error (:refused (#'record/judged {:ok "not json"} parse))))))

(deftest a-design-with-no-cited-intent-says-so-and-asks-for-underivable
  (let [p (record/design-prompt {:design design})]
    (is (str/includes? p "NO STATED INTENT"))
    (is (str/includes? p "UNDERIVABLE"))
    (is (str/includes? p "do NOT infer the goal from the design")
        "an inferred goal is the one the design serves, so the check could
         never fail")))

(deftest a-host-finding-cites-an-observation-made-on-the-host
  ;; Watched: a judge broke goal-served on `sha256sum` being absent on macOS, cited from a
  ;; Homebrew formula page; `command -v sha256sum` on the host answered /sbin/sha256sum.
  (let [p (record/design-prompt {:design design})]
    (is (str/includes? p "MISSING on this host")
        "a host-environment finding is settled by the host, never by reading about it")
    (is (str/includes? p "the output of running it here")
        "documentation describes some host, so only an observation made here can break a check")))

(deftest only-an-intent-is-a-goal
  ;; The append boundary refuses a design citing anything but an :intent, so a citation that
  ;; resolves to another kind was never written through it and projects no goal.
  (let [cited (fn [entry]
                (with-redefs [ws/entry-at-seq (fn [_ _ _] entry)]
                  (record/discover-intent [:nido "ws-1"] {:intent {:seq 1}})))]
    (is (= {:goal "Checkout off by a cent" :done-when ["one rounding point"]}
           (cited {:format :intent :goal "Checkout off by a cent"
                   :done-when ["one rounding point"]})))
    (is (nil? (cited {:format :triage-report :title "Checkout off by a cent"
                      :summary "Rounding applied per line."}))
        "a triage report is not a goal, whatever it summarises")))

(deftest an-underivable-check-parses-and-is-not-a-failure
  (let [r (record/parse-design-decision
           (json/generate-string
            {:recommend "proceed" :reason "nothing derivable blocks it"
             :checks [{:check "goal_served" :status "underivable"
                       :note "this design cites no intent record"}]
             :findings [] :asks "worth doing without a stated goal?"})
           4 any-era)]
    (is (= :underivable (:status (first (:checks r)))))
    (is (= r (report/validate-event :design-decision r)))))

(deftest a-judge-answering-in-the-old-shape-is-still-answering
  (let [r (record/parse-design-decision
           (json/generate-string
            {:recommend "proceed" :reason "r"
             :checks [{:check "goal_served" :held true :note "n"}]
             :findings [] :asks "a?"})
           4 any-era)]
    (is (= :held (:status (first (:checks r))))
        "the schema moved; an answer in the previous shape is degraded rather
         than discarded")))

;; ── A round that could not run is not a round that found nothing ───────────
;; The one confusion a judgment surface cannot afford. Silence from a judge is
;; evidence; silence from a missing binary is not, and one nil for both invites
;; the second to be read as the first.

(deftest a-round-outside-a-session-says-so
  (let [r (record/baseline-review! {:cwd "/definitely/not/a/session" :run-id "x"})]
    (is (= :no-workstream (:outcome r)))
    (is (string? (:detail r)))
    (is (nil? (:format r)) "an outcome is never mistaken for a record")))

(deftest an-outcome-is-never-appended-as-a-record
  (is (nil? (record/append! "/definitely/not/a/session"
                            {:outcome :codex-failed :detail "exit 127"}))
      "append! writes records, and an outcome is not one — appending it would
       put 'the judge did not run' into the ledger as a judgment"))

(deftest each-remedy-parses-distinctly
  (doseq [[in out] {"amend" :amend "recut" :recut "resurvey" :resurvey}]
    (let [r (record/parse-design-decision
             (json/generate-string
              {:recommend in :reason "…"
               ;; routing-coherent broken too: a recut no decomposition check supports is an amend
               :checks [{:check "goal_served" :status "broken" :note "a smaller design does"}
                        {:check "routing_coherent" :status "broken" :note "two stories"}]
               :findings [{:cites ["a total is rounded exactly once"]
                           :claim "the smaller design already satisfies it"
                           :evidence ["src/order/aggregate.clj:12"]}]
               :asks "which way?"})
             4 any-era)]
      (is (= out (:recommend r))
          "redesign, recut and re-survey are different instructions; collapsing
           them is worse than saying nothing")
      (is (= r (report/validate-event :design-decision r))))))

(deftest a-prompt-never-promises-what-the-record-does-not-carry
  ;; The failure this catches is the one the whole arc is against: a header
  ;; asserting every claim names its counterexample, over claims that name none
  ;; because they were written before the rule existed.
  (let [legacy (-> baseline
                   (dissoc :modules :composition)
                   (update :load-bearing
                           (fn [lb] (mapv #(dissoc % :falsified-by :readings) lb))))
        p (record/baseline-prompt {:baseline legacy})]
    (is (not (str/includes? p "refuted by:"))
        "no empty label where the author committed to nothing")
    (is (str/includes? p "predates the rule"))
    (is (str/includes? p "treat\na claim you cannot see any way to refute as a finding")
        "and the gap becomes something to report rather than something to ignore")
    (is (not (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH"))
        "a vocabulary nothing is read through is noise in the prompt")
    (is (not (str/includes? p "A READING IS A CLAIM TOO")))))

(deftest a-decomposition-with-no-analysis-is-told-to-report-itself
  ;; The gap this closes was watched, not imagined: the first baseline authored in
  ;; this shape carried five modules and zero readings. Gating the vocabulary on
  ;; readings BEING there hid it from exactly the baseline that needed it.
  (let [no-readings (update baseline :load-bearing
                            (fn [lb] (mapv #(dissoc % :readings) lb)))
        p (record/baseline-prompt {:baseline no-readings})]
    (is (str/includes? p "READS NOTHING THROUGH ANY PERSPECTIVE"))
    (is (str/includes? p "Report that as INSUFFICIENT, blocking relation-honest")
        "in a verdict the answer can carry, naming a derivation it blocks")
    (is (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH")
        "the vocabulary is shown, because this record could carry it")
    (is (not (str/includes? p "A READING IS A CLAIM TOO"))
        "and it is not told to check readings it does not have")))

(deftest a-baseline-that-cannot-carry-readings-is-not-nagged-about-them
  (let [legacy (dissoc baseline :modules :composition)
        p (record/baseline-prompt {:baseline legacy})]
    (is (not (str/includes? p "READS NOTHING THROUGH ANY PERSPECTIVE")))
    (is (not (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH")))))

(deftest a-current-baseline-still-gets-the-full-apparatus
  (let [p (record/baseline-prompt {:baseline baseline})]
    (is (str/includes? p "each with the\ncounterexample that would refute it"))
    (is (str/includes? p "refuted by: a caller outside the aggregate"))
    (is (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH"))))

;; ── Insufficiency has to name what it blocks ────────────────────────────────

(deftest an-insufficient-finding-names-the-derivation-and-what-is-missing
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "insufficient" :reason "the cut cannot be stated"
                           :confirmed []
                           :findings [{:blocks "decomposable"
                                       :cites ["the aggregate is the only summing path"]
                                       :claim "the baseline never says where rounding is decided"
                                       :needs "which module owns the rounding decision"
                                       :evidence ["src/order/calc.clj:41"]}]})
           3)]
    (is (= :insufficient (:verdict r)))
    (is (= :decomposable (:blocks (first (:findings r)))))
    (is (= "which module owns the rounding decision" (:needs (first (:findings r)))))
    (is (= r (report/validate-event :baseline-review r)))))

(deftest a-gap-that-blocks-nothing-is-not-a-finding
  ;; The bound, and the whole reason this verdict replaced :underscoped. Measured
  ;; against a real area a judge finds true things to add forever — five rounds
  ;; of exactly that produced twenty-four findings without one repeating. There
  ;; are four derivations, so there are four ways to be insufficient.
  (is (not-any? :blocks (:findings (record/parse-baseline-review
                                     (baseline-json {:verdict "insufficient" :reason "could say more"
                                                     :findings [{:blocks "none" :cites ["a claim"]
                                                                 :claim "the cache is not mentioned"
                                                                 :needs "mention the cache" :evidence []}]})
                                     3)))
      "'none' is what a falsified finding carries; it cannot make a gap")
  (is (nil? (record/parse-baseline-review
             (baseline-json {:verdict "insufficient" :reason "could say more"
                             :findings [{:blocks "thoroughness" :cites ["a claim"]
                                         :claim "x" :needs "y" :evidence []}]})
             3))
      "a derivation nobody derives"))

(deftest each-finding-keeps-its-own-fields-whatever-the-verdict
  ;; Seen in seven runs: a falsified answer lost every :needs and :blocks, so a gap read as a
  ;; refutation and the amender never saw what the judge asked it to say.
  (let [refute {:claim-id "c1" :blocks "none" :cites ["a claim"] :claim "the store opens no transaction"
                :needs "name the callers that open the serializable transaction"
                :evidence ["src/store.clj:12"]}
        gap    {:claim-id "c2" :blocks "relation-honest" :cites ["another"] :claim "the door is unnamed"
                :needs "name the door that owns admission" :evidence []}
        r      (record/parse-baseline-review
                (baseline-json {:verdict "falsified" :reason "r" :confirmed [] :findings [refute gap]}) 3)
        [f g]  (:findings r)]
    (is (= :falsified (:verdict r)))
    (is (= "name the callers that open the serializable transaction" (:needs f))
        "the refutation's repair reaches the amender and the retreat detector")
    (is (nil? (:blocks f)) "'none' makes a refutation, never a gap")
    (is (= [:relation-honest "name the door that owns admission" "c2"]
           ((juxt :blocks :needs :claim-id) g))
        "a gap under a falsified verdict is still a gap: it blocks a derivation and refutes nothing")
    (is (= r (report/validate-event :baseline-review r))
        "a falsified review carrying a gap is one the ledger accepts"))
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "insufficient" :reason "r" :confirmed []
                           :findings [{:claim-id "c1" :blocks "none" :cites ["a claim"]
                                       :claim "x is false" :needs "" :evidence []}
                                      {:claim-id "c2" :blocks "goal-served" :cites ["b"]
                                       :claim "y" :needs "say z" :evidence []}]})
           3)]
    (is (= :falsified (:verdict r))
        "a refutation under an insufficient verdict is kept, and makes the record falsified")
    (is (= 2 (count (:findings r))))
    (is (= r (report/validate-event :baseline-review r))))
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "falsified" :reason "r" :confirmed []
                           :findings [{:claim-id "c2" :blocks "stratified" :cites ["b"]
                                       :claim "y" :needs "say z" :evidence []}]})
           3)]
    (is (= :insufficient (:verdict r))
        "an answer left with only gaps found the record true and short, whatever verdict it named")
    (is (= r (report/validate-event :baseline-review r)))))

(deftest an-insufficient-finding-must-say-what-would-fix-it
  (is (nil? (record/parse-baseline-review
             (baseline-json {:verdict "insufficient" :reason "vague"
                             :findings [{:blocks "goal-served" :cites ["a claim"]
                                         :claim "not enough here" :needs "" :evidence []}]})
             3))
      "'more detail' is not a repair anyone can make"))

(deftest a-round-from-before-sufficiency-still-reads
  (let [old {:format :baseline-review :verdict :accurate :baseline-seq 3
             :reason "held" :confirmed ["a claim"]}]
    (is (thrown? clojure.lang.ExceptionInfo (report/validate-event :baseline-review old))
        "not writable any more")
    (is (= old (report/parse-event :baseline-review old)) "but still readable")))

(deftest a-cited-id-is-normalised-to-the-id-itself
  ;; The prompt renders `[engine-names-no-stage] the engine names no stage`, and
  ;; a judge told to cite the id "exactly as shown" cites the brackets with it.
  ;; An id sometimes bracketed and sometimes not is no identity at all, which is
  ;; the one thing it exists to be.
  (doseq [cited ["[engine-names-no-stage]" "engine-names-no-stage" "  [engine-names-no-stage] "]]
    (let [r (record/parse-baseline-review
             (baseline-json {:verdict "falsified" :reason "r" :confirmed []
                             :findings [{:claim-id cited :blocks "none" :cites ["c"]
                                         :claim "x" :needs "" :evidence []}]})
             3)]
      (is (= "engine-names-no-stage" (:claim-id (first (:findings r))))
          (str "from " (pr-str cited))))))

(deftest a-finding-about-no-single-claim-carries-no-id
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "falsified" :reason "r" :confirmed []
                           :findings [{:claim-id "" :blocks "none" :cites ["c"]
                                       :claim "x" :needs "" :evidence []}]})
           3)]
    (is (nil? (:claim-id (first (:findings r)))))))

(deftest a-confirmation-identifies-a-claim-rather-than-describing-it
  ;; Measured on a live run: five rounds produced 29 DISTINCT confirmation
  ;; strings for a baseline of 13 claims and modules. Each round reworded them, so
  ;; the list carried forward could not be matched to anything and the judge
  ;; re-examined what it had already settled.
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "falsified" :reason "r"
                           :confirmed ["[engine-names-no-stage]" "judge-never-writes"
                                       "  [loop-engine] " "engine-names-no-stage" ""]
                           :findings [{:claim-id "x" :blocks "none" :cites ["c"]
                                       :claim "y" :needs "" :evidence []}]})
           3)]
    (is (= ["engine-names-no-stage" "judge-never-writes" "loop-engine"] (:confirmed r))
        "normalised, deduplicated, and blanks dropped — so two rounds naming the
         same claim produce the same entry")))

(deftest the-judge-is-asked-for-ids-not-sentences
  (let [p (record/baseline-prompt {:baseline {:format :baseline}})]
    (is (str/includes? p "with the file:line references you read"))
    (is (str/includes? p "cannot be\nmatched to the claim"))
    (is (str/includes? p "CONFIRMED MEANS EVERY SENTENCE HELD")
        "a false clause the counterexample does not name is still a finding, not a note in reason")
    (is (str/includes? p "never\nfor a subject outside this round's checks")
        "the every-caller rule refutes only what the round checks")))

(deftest a-record-judge-rules-every-clause-of-a-compound-subject
  ;; Watched: a judge reported one counterexample to an amended composition claim and missed a
  ;; self-contradiction already in its text; the next round found it on text unchanged since.
  (doseq [[kind p] [[:baseline (record/baseline-prompt {:baseline {:format :baseline}})]
                    [:design (record/design-prompt {:design design})]]]
    (is (str/includes? p "RULED CLAUSE BY CLAUSE")
        (str kind ": a clause the judge read false and did not report costs a round to find again"))
    (is (str/includes? p "naming EVERY clause you found false")
        (str kind ": one finding per subject — a second finding on the same id is the same identity"))
    (is (str/includes? p "one the record itself already\nsatisfies is a contradiction")
        (str kind ": a refuter the record satisfies is a false clause, not a claim"))
    (is (str/includes? p "names the production write that produces it")
        (str kind ": a row only a test fixture writes refuted a claim and cost an amend to withdraw"))))

(deftest a-baseline-judge-does-not-confirm-on-the-records-own-figures
  ;; Watched: two judges called a cost claim unverifiable, a third confirmed it on the baseline's
  ;; own measurements, and a pair then settled it with nothing from the tree behind it.
  (let [p (record/baseline-prompt {:baseline {:format :baseline}})]
    (is (str/includes? p "THE RECORD IS NOT ITS OWN EVIDENCE")
        "a confirmation that repeats the record's number back to it checks nothing, yet settles the claim")
    (is (str/includes? p "unchecked, cause out-of-code, not confirmed")
        "the claim is put where an unverifiable one goes, so it is not settled on its own say-so")))

(deftest a-subject-is-cut-into-the-clauses-a-judge-rules
  (let [record {:model {:claims [{:id "c" :about ["e"] :statement "It sums once. Nothing else sums."
                                  :falsified-by "a caller summing lines, or a second summer"}
                                 {:id "plain" :about ["e"] :statement "It sums."}]}
                :phases [{:claim "p" :habitable "Old readers work. New writers wait."}
                         {:claim "q" :habitable "Done."}]}
        owed   (#'record/clauses-owed record ["c" "plain"])]
    (is (= ["c#1" "c#2" "c#3" "c#4"] (map :key (owed "c")))
        "each sentence and each counterexample is a clause of its own")
    (is (= [false true true true] (map :search? (owed "c")))
        "a counterexample, and an exclusivity, hold only on a search of the whole tree")
    (is (not (contains? owed "plain")) "a subject saying one plain thing is ruled by its id")
    (is (= ["phase-1#1" "phase-1#2"] (map :key (owed "phase-1")))
        "a phase's habitable state is ruled sentence by sentence too")
    (is (= ["phase-2#1"] (map :key (owed "phase-2"))))))

(deftest a-phase-and-an-open-note-are-ruled-against-every-claim
  ;; Watched: a phase admitting a weaker live state than an always-claim, and an open note stating
  ;; the exception a claim covered unconditionally, were each ruled held by two clause-by-clause
  ;; readings of identical text and found false only by a third.
  (let [record {:model {:claims [{:id "c" :about ["e"] :statement "It sums once."}]}
                :open  ["A large page decodes unpermitted. Nothing else is left open."]}
        owed   (#'record/clauses-owed record [])]
    (is (= ["open-1#1" "open-1#2"] (map :key (owed "open-1")))
        "an open note is owed a ruling sentence by sentence, so the exception it states cannot go unread")
    (is (every? false? (map :search? (owed "open-1")))
        "a note is held against the record's claims, not searched for in the tree")
    (let [r (#'record/held-to-clauses {:clause-rulings {"open-1#1" {:ruling :held :evidence [] :search ""}}}
                                      owed)]
      (is (= {"open-1" ["open-1#1" "open-1#2"]} (:incomplete-clauses r))
          "a note held naming no claim it was held against is not ruled")
      (is (empty? (:unruled r)) "a note is no subject, so leaving it open unrules none")))
  (let [d (record/design-prompt {:design (assoc design
                                                :open ["A large page decodes unpermitted."]
                                                :phases [{:claim "p" :habitable "Old readers work."
                                                          :exit {:criterion "x"}}
                                                         {:claim "q" :habitable "Done." :exit {:criterion "y"}}])})]
    (is (str/includes? d "A PHASE, AN OPEN NOTE, THE SHAPE OR AN INTERFACE IS RULED AGAINST THE CLAIMS")
        "the cross ruling is a rule the judge is given, not a reader's chance")
    (is (str/includes? d "NOT marked holds ON\nCOMPLETION")
        "a phase is held against the claims that hold at every boundary, not the on-completion ones")
    (is (str/includes? d "covers unconditionally")
        "an open note is held against every claim covering the path it excepts")
    (is (str/includes? d "open-1#1  open: A large page decodes unpermitted.")
        "each note sentence is listed under the key its ruling names"))
  (is (not (str/includes? (record/design-prompt {:design design}) "RULED AGAINST THE CLAIMS"))
      "a design with no phase and no open note owes no cross ruling"))

(deftest a-shape-and-an-interface-are-ruled-against-every-claim
  ;; Watched: a claim that two projections are separate was ruled held by three readings while
  ;; the shape and an element's interface, settled and never listed, said they were one — a fourth
  ;; reading found the pair, an amend cycle and two judge rounds late.
  (let [record {:shape "One dashboard serves both. It reads the series."
                :model {:elements [{:id "mobile" :sort :module :interface "the dashboard in each entry"}
                                   {:id "bare" :sort :kind}]
                        :claims   [{:id "separate" :about ["mobile"] :statement "Projections are separate."}]}}
        owed   (#'record/clauses-owed record [])]
    (is (= ["across-shape#1" "across-shape#2"] (map :key (owed "across-shape")))
        "the shape is owed a ruling against the claims even when it is not asked, since the claim it contradicts may be")
    (is (= ["across-mobile#1"] (map :key (owed "across-mobile")))
        "an element's interface is owed a ruling against the claims sentence by sentence")
    (is (not (contains? owed "across-bare")) "an element offering nothing has nothing to hold against a claim")
    (is (every? (comp false? :search?) (concat (owed "across-shape") (owed "across-mobile")))
        "held against the record's claims, not the tree — the subject's own listing owes the search")
    (let [r (#'record/held-to-clauses {:clause-rulings {"across-shape#1" {:ruling :held :evidence [] :search ""}}}
                                      owed)]
      (is (= ["across-shape#1" "across-shape#2"] (get-in r [:incomplete-clauses "across-shape"]))
          "a shape held naming no claim it was held against is not ruled")
      (is (empty? (:unruled r)) "a cross listing is no subject, so leaving it open unrules none"))
    (let [asked (#'record/clauses-owed record ["shape"])]
      (is (and (contains? asked "shape") (contains? asked "across-shape"))
          "an asked shape is ruled against the code under its own id and against the claims besides"))
    (is (empty? (#'record/clauses-owed (dissoc record :model) []))
        "a record with no claims has nothing to hold its shape against")
    (let [d (record/design-prompt {:design (merge design record)})]
      (is (str/includes? d "IS RULED AGAINST THE CLAIMS")
          "the cross ruling is a rule the judge is given, not a reader's chance")
      (is (str/includes? d "joins what a claim keeps separate")
          "a shape or interface is false where it joins what a claim keeps apart")
      (is (str/includes? d "across-mobile#1  interface: the dashboard in each entry")
          "each interface sentence is listed under the key its ruling names"))))

(deftest every-judge-prompt-lists-the-clauses-it-owes
  (let [b (record/baseline-prompt {:baseline (assoc baseline :shape "Only the aggregate sums. It hides order.")})
        d (record/design-prompt {:design design})]
    (is (str/includes? b "shape#1  text: Only the aggregate sums.  (search owed)")
        "a clause is shown with the key its ruling names, and whether it owes a search")
    (is (str/includes? b "shape#2  text: It hides order.\n"))
    (doseq [[kind p] [[:baseline b] [:design d]]]
      (is (str/includes? p "THE CLAUSES") (str kind ": the list the clause rulings are drawn from"))
      (is (str/includes? p "A CONFIRMATION MAY NOT DENY WHAT YOUR OWN FINDINGS CITE")
          (str kind ": a confirmation contradicting the round's own finding cost a round")))
    (is (str/includes? d "RULE EVERY SUBJECT AGAINST EVERY CHECK")
        "a decision reported one defect per check per round, so each one already there cost an amendment")
    (is (not (str/includes? b "RULE EVERY SUBJECT AGAINST EVERY CHECK"))
        "a baseline round derives no check per subject")))

(deftest a-round-schema-admits-exactly-the-clauses-owed
  (let [owed {"c" [{:key "c#1"} {:key "c#2"}]}
        bs   (json/parse-string (#'record/baseline-review-schema owed) true)
        ds   (json/parse-string (#'record/design-decision-schema {} [] owed) true)]
    (doseq [s [bs ds]]
      (is (= ["c#1" "c#2"] (get-in s [:properties :clause_rulings :items :properties :clause :enum])))
      (is (= 2 (get-in s [:properties :clause_rulings :minItems]))
          "as many rulings as clauses makes a skipped clause the unlikely answer, not the free one")
      (is (some #{"clause_rulings"} (:required s))))))

;; ── a record that names its strata ───────────────────────────────────────────
;; The era a record was written in is read off :strata, and it picks the yardstick: the stratified
;; check and derivation for a record that names its strata, decomposable as before for one that
;; does not.

(def ^:private a-stratum
  {:id "canvas.order.strata/totals" :sort :stratum
   :interface "an order's total, summed from exact lines"
   :readings [{:lens :stratified/level :verdict :sound :because "one vocabulary"}]})

(def ^:private strata-baseline
  (-> model-baseline
      (update-in [:model :elements] conj a-stratum)
      (assoc :strata ["canvas.order.strata/totals"])))

(def ^:private strata-design
  (-> design
      (dissoc :invariants)
      (assoc :model {:elements [{:id "canvas.order/aggregate" :sort :module}
                                {:id "canvas.order.strata/totals" :sort :stratum}]
                     :claims   [{:id "rounded-once" :about ["canvas.order/aggregate"]
                                 :statement "a total is rounded exactly once"
                                 :falsified-by "two rounding calls reached for one total"
                                 :evidence {:by :round}}]}
             :strata ["canvas.order.strata/totals"])))

(deftest a-baseline-naming-its-strata-is-asked-for-the-stratified-derivation
  (let [p (record/baseline-prompt {:baseline strata-baseline})]
    (is (str/includes? p "  stratified        does each part of the change sit in the level"))
    (is (not (str/includes? p "  decomposable      can its cut be stated")))
    (is (str/includes? p "STRATA — the levels this area declares"))
    (is (str/includes? p "a health observation names that stratum"))
    (is (str/includes? p "reads: a STRATUM (never a module or a claim)")))
  (testing "one written before names the cut it was judged by then"
    (is (str/includes? (record/baseline-prompt {:baseline model-baseline})
                       "  decomposable      can its cut be stated"))))

(deftest the-strata-list-ends-before-the-prose-that-reads-it
  ;; Glued on, the last stratum read as the first word of the next sentence; and "above" pointed a
  ;; judge whose every element was settled into the block it is told not to check.
  (let [p (record/baseline-prompt {:baseline strata-baseline})]
    (is (str/includes? p "- canvas.order.strata/totals\nEach is an element listed in this prompt")
        "the list ends its own line, and the prose names where the elements are without a direction")))

;; ── relation-honest, ruled per baseline id ──────────────────────────────────
;; Watched: one design's :breaks grew six, eight, twelve over three rounds, each round's judge and
;; amender sweeping "each claim and each module" and each finding ids the last had missed — strata
;; among them, which the instruction never named. Enumerated, every id is ruled on in one round.

(deftest relation-honest-is-ruled-on-every-baseline-id-by-name
  (let [p (record/design-prompt {:design design :baseline strata-baseline})]
    (is (str/includes? p "PER BASELINE ID, never as one reading of")
        "one judgement over the relation note lets a self-declared move pass on a lenient reading")
    (is (str/includes? p "claims:\n                          [one-summing-path]")
        "the claims are listed by id")
    (is (str/includes? p "modules:\n                          [canvas.order/aggregate]")
        "the modules are listed by id")
    (is (str/includes? p "strata:\n                          [canvas.order.strata/totals]")
        "the strata are listed too — the instruction that named only claims and modules missed them")
    (is (str/includes? p "relation_rulings") "the answer has a field to rule in")))

(deftest a-design-declaring-no-baseline-relation-is-ruled-on-no-ids
  ;; A design from before the baseline event has no :breaks to hold to any list.
  (let [p (record/design-prompt {:design (dissoc design :baseline) :baseline strata-baseline})]
    (is (not (str/includes? p "PER BASELINE ID")))
    (is (str/includes? p "relation_rulings stays empty"))))

(deftest the-legacy-baseline-shape-names-its-ids-too
  (is (= [[:claim "c1"] [:module "mod-the-order-aggregate"] [:module "mod-the-invoice-reader"]]
         (#'record/relation-subjects baseline))
      "a survey-shaped baseline is ruled on by the same ids its review settles")
  (is (= [] (#'record/relation-subjects nil))))

(defn- decided
  "A parsed proceed whose relation rulings are `rulings`."
  [rulings]
  (record/parse-design-decision
   (json/generate-string {:recommend "proceed" :reason "r" :asks "worth it?"
                          :checks [{:check "relation_honest" :status "held" :note "n"}]
                          :findings [] :confirmed [] :unchecked []
                          :relation_rulings rulings})
   4 any-era))

(def ^:private ids (#'record/relation-subjects strata-baseline))

(defn- held [decision breaks] (#'record/relation-held decision ids breaks))

(defn- every-id-stands []
  (mapv (fn [[_ id]] {:id id :ruling "stands" :reason "untouched"}) ids))

(deftest a-decision-keeps-its-relation-rulings
  (let [r (decided [{:id "[one-summing-path]" :ruling "breaks" :reason "moves"}
                    {:id "one-summing-path" :ruling "stands" :reason "a second word on it"}
                    {:id "canvas.order/aggregate" :ruling "maybe" :reason "not a ruling"}])]
    (is (= [{:id "one-summing-path" :ruling :breaks :reason "moves"}] (:relation-rulings r))
        "brackets off, one ruling per id, and only the two rulings there are")
    (is (report/validate-event :design-decision r) "and the ledger takes it")))

(deftest rulings-that-match-breaks-leave-the-decision-alone
  (let [r (decided (every-id-stands))]
    (is (= r (held r [])))))

(deftest an-id-left-without-a-ruling-stops-the-proceed
  (let [r (held (decided (rest (every-id-stands))) [])]
    (is (= ["one-summing-path"] (:relation-unruled r))
        "a sweep that skipped an id has not derived the relation, so the decision does not proceed")
    (is (not (report/proceeds? r)))
    (is (report/validate-event :design-decision r) "and the ledger takes it")))

;; Watched: one id listed as both 'Not ruled on' and 'owed', and as both :confirmed and :unruled —
;; confirmed as the design element it also is, unruled as the baseline module it was — with nothing
;; saying which list answered which question.
(deftest an-element-reading-and-a-missing-relation-ruling-stay-apart
  (let [r  (held (assoc (decided (rest (every-id-stands)))
                        :confirmed ["one-summing-path"] :unruled ["rounded-once"])
                 [])
        md (report/report->markdown r)]
    (is (= ["rounded-once"] (:unruled r))
        ":unruled stays the claims the element reading left open, untouched by the relation")
    (is (= ["one-summing-path"] (:confirmed r)) "the element reading still holds what it confirmed")
    (is (= ["one-summing-path"] (:relation-unruled r))
        "the relation question an id is open on has its own key, so it is not read as the element's")
    (is (str/includes? md "## Relation not ruled on — whether this design stops what the baseline records\n- one-summing-path")
        "the render names the question the id is open on")
    (is (not (re-find #"(?s)## Not ruled on[^#]*one-summing-path" md))
        "and does not list it as an element nobody ruled on")))

(deftest an-id-ruled-broken-that-breaks-omits-is-relation-honest-broken
  (let [rulings (assoc (every-id-stands) 2 {:id "canvas.order.strata/totals" :ruling "breaks"
                                            :reason "the level's vocabulary moves"})
        r       (held (decided rulings) [])]
    (is (= :broken (:status (first (filter #(= :relation-honest (:check %)) (:checks r)))))
        "the ruling and the check answer one question; the per-id one is what the check is held to")
    (is (= :amend (:recommend r)) "a proceed over an omitted break would hand the omission to nobody")
    (is (= ["canvas.order.strata/totals"] (:cites (last (:findings r)))))
    (is (= ["canvas.order.strata/totals: the level's vocabulary moves"] (:evidence (last (:findings r))))
        "the amender gets the judge's reason with the id")
    (is (report/validate-event :design-decision r) "and the ledger takes the record it becomes")))

(deftest a-breaks-naming-an-id-ruled-standing-claims-too-much
  (let [r (held (decided (every-id-stands)) ["one-summing-path"])]
    (is (str/includes? (:note (first (:checks r))) "under :breaks, ruled standing: one-summing-path"))
    (is (= :amend (:recommend r)))))

(deftest an-ask-stays-an-ask-over-a-mismatch
  (let [r (held (assoc (decided (every-id-stands)) :recommend :ask) ["one-summing-path"])]
    (is (= :ask (:recommend r)) "the question for a person still stops the run")))

(deftest a-relation-honest-with-no-yardstick-is-not-held-to-rulings
  (let [r (-> (decided [])
              (assoc :checks [{:check :relation-honest :status :underivable :note "no stance"}]))]
    (is (= r (held r ["one-summing-path"]))
        "an underivable check stays underivable, rather than ending the run :unruled")))

;; ── Every listed id ruled on, by construction ───────────────────────────────
;; Watched: told to rule on seventeen ids, a judge returned the six claims and skipped every module
;; and stratum, in five rounds of one run and in two runs running — and an unruled id stops the
;; proceed, so that lineage could never proceed.

(defn- rulings-schema [schema-json]
  (get-in (json/parse-string schema-json true) [:properties :relation_rulings]))

(deftest the-decision-schema-admits-exactly-the-listed-ids
  (let [s (rulings-schema (#'record/design-decision-schema {} ids nil))]
    (is (= (mapv second ids) (get-in s [:items :properties :id :enum]))
        "an id outside the list is no ruling the record can be held to")
    (is (= [(count ids) (count ids)] [(:minItems s) (:maxItems s)])
        "a claims-only answer is not one the schema admits")
    (is (= (set (map name (keys (get-in s [:items :properties])))) (set (get-in s [:items :required])))
        "the provider rejects a schema whose required is not every property"))
  (testing "a design ruled on no ids may rule on none"
    (let [s (rulings-schema (#'record/design-decision-schema {} [] nil))]
      (is (= [0 0] [(:minItems s) (:maxItems s)]))
      (is (nil? (get-in s [:items :properties :id :enum])) "an empty enum is no schema a provider takes"))))

(defn- reasked
  "`decision` through the in-round re-ask, the judge answering `answer` (or failing, for nil).
   Returns [decision' every run-round! call]."
  [decision answer]
  (let [calls (atom [])]
    [(with-redefs [record/run-round! (fn [opts] (swap! calls conj opts)
                                       (if answer {:ok (json/generate-string answer)}
                                           {:outcome :no-output :detail "stub"}))]
       (#'record/rule-unruled-relations! decision ids {:run-id "r" :kind :design-decision :label "l"}
                                         {:design design :baseline strata-baseline}))
     @calls]))

(deftest the-ids-a-decision-skipped-are-asked-again-within-the-round
  (let [claims-only     (decided [{:id "one-summing-path" :ruling "stands" :reason "untouched"}])
        skipped         (mapv second (rest ids))
        [r [call :as cs]] (reasked claims-only
                                   {:relation_rulings
                                    (conj (mapv #(hash-map :id % :ruling "stands" :reason "kept") skipped)
                                          {:id "one-summing-path" :ruling "breaks" :reason "a second word"})})]
    (is (= 1 (count cs)) "one more ask, not a loop")
    (is (= skipped (get-in (rulings-schema (:schema call)) [:items :properties :id :enum]))
        "only the ids still owed are asked")
    (is (str/includes? (:prompt call) "Still to rule on:\n  modules:\n    [canvas.order/aggregate]"))
    (is (str/includes? (:prompt call) "[one-summing-path] stands — untouched")
        "the rulings already made are shown, not asked again")
    (is (= "l-unruled-relations" (:label call)) "its artifacts do not overwrite the decision's")
    (is (= {:id "one-summing-path" :ruling :stands :reason "untouched"}
           (first (:relation-rulings r)))
        "the re-ask cannot overturn a ruling the decision made")
    (is (empty? (:relation-unruled (held r []))) "every id ruled, so nothing stops the proceed")
    (is (report/proceeds? (held r [])))
    (is (report/validate-event :design-decision (held r [])) "and the ledger takes it")))

(deftest a-decision-that-ruled-every-id-is-not-asked-again
  (let [r (decided (every-id-stands))]
    (is (= [r []] (reasked r {:relation_rulings []})))))

(deftest an-underivable-relation-honest-is-not-asked-for-rulings
  (let [r (-> (decided []) (assoc :checks [{:check :relation-honest :status :underivable :note "n"}]))]
    (is (= [] (second (reasked r {:relation_rulings []})))
        "relation-held holds it to no ruling, so asking for one buys nothing")))

(deftest a-failed-re-ask-leaves-the-ids-unruled
  (let [[r] (reasked (decided []) nil)]
    (is (= (mapv second ids) (:relation-unruled (held r []))) "what two asks left open is still named")
    (is (not (report/proceeds? (held r []))))))

;; ── A ruling is a reading of a record, held to the last one ─────────────────
;; Watched: rulings re-derived cold each round, while relation-held enforced each round's reading
;; against :breaks. One id ruled breaks, stands, breaks across nine rounds on text no amendment
;; touched, and the amender re-added and removed it each time — rounds that undid one another.

(def ^:private breaks-design
  (assoc strata-design :baseline {:seq 3 :relation :revisit :breaks ["one-summing-path"]
                                  :note "the summing path moves"}))

(deftest an-id-the-record-did-not-move-is-unmoved
  (let [prior (#'record/relation-prior [{:id "one-summing-path" :ruling :breaks :reason "moves"}
                                        {:id "canvas.order/aggregate" :ruling :stands :reason "kept"}]
                                       [breaks-design strata-baseline]
                                       [(assoc breaks-design :seq 9 :summary "Reworded, about nothing listed.")
                                        strata-baseline])]
    (is (= {:ruling :breaks :reason "moves" :moved? false} (prior "one-summing-path"))
        "a rewrite naming none of the ids leaves every ruling standing on the same text")
    (is (false? (:moved? (prior "canvas.order/aggregate"))))))

(deftest an-id-moves-with-its-baseline-statement-or-the-design-text-naming-it
  (let [ruled    [{:id "one-summing-path" :ruling :breaks :reason "moves"}
                  {:id "canvas.order/aggregate" :ruling :stands :reason "kept"}]
        reworded (update-in strata-baseline [:model :claims 0] assoc :statement "totals sum in two places")
        renamed  (assoc-in breaks-design [:model :claims 0 :statement] "a total is rounded at most once")]
    (is (:moved? ((#'record/relation-prior ruled [breaks-design strata-baseline] [breaks-design reworded])
                  "one-summing-path"))
        "a re-survey that restates the id is a new thing to rule on")
    (is (:moved? ((#'record/relation-prior ruled [breaks-design strata-baseline] [renamed strata-baseline])
                  "canvas.order/aggregate"))
        "a claim about the module changed, so the module's ruling may change with it")
    (is (not (:moved? ((#'record/relation-prior ruled [breaks-design strata-baseline] [renamed strata-baseline])
                       "one-summing-path")))
        "the same rewrite says nothing new about an id it does not name")))

(deftest breaks-moving-toward-a-ruling-is-no-ground-to-reverse-it
  (let [ruled  [{:id "canvas.order/aggregate" :ruling :breaks :reason "its interface moves"}]
        before (assoc-in breaks-design [:baseline :breaks] [])
        obeyed (assoc-in breaks-design [:baseline :breaks] ["canvas.order/aggregate"])]
    (is (false? (:moved? ((#'record/relation-prior ruled [before strata-baseline] [obeyed strata-baseline])
                          "canvas.order/aggregate")))
        "the amender listing what was ruled broken is the ruling obeyed; counted as a move, every obeyed ruling could be undone next round")
    (is (true? (:moved? ((#'record/relation-prior ruled [obeyed strata-baseline] [before strata-baseline])
                         "canvas.order/aggregate")))
        "a :breaks moved against the ruling — an amender that disputed it — is a move the judge may answer")))

(defn- prior-of [& {:as by-id}]
  (update-vals by-id (fn [[ruling moved?]] {:ruling ruling :reason "last time" :moved? moved?})))

(deftest an-unexplained-reversal-on-an-unmoved-id-is-not-taken
  (let [flipped (assoc (every-id-stands) 0 {:id "one-summing-path" :ruling "breaks" :reason "moves"})
        r       (#'record/held-to-prior (decided flipped) (prior-of "one-summing-path" [:stands false]))]
    (is (= {:id "one-summing-path" :ruling :stands :reason "last time"} (first (:relation-rulings r)))
        "the earlier reading of the same record stands")
    (is (= [{:id "one-summing-path" :ruling :breaks :reason "moves"}] (:relation-reversals r))
        "the reversal is the judge's inconsistency, and a reader of the run is owed it")
    (is (report/proceeds? (held r [])) "judge variance is not turned into an amend round")
    (is (report/validate-event :design-decision (held r [])) "and the ledger takes it")))

(deftest a-reversal-with-a-cause-or-on-a-moved-id-is-taken
  (let [flipped (assoc (every-id-stands) 0 {:id "one-summing-path" :ruling "breaks" :reason "moves"
                                            :cause "the aggregate now sums twice"})
        caused  (#'record/held-to-prior (decided flipped) (prior-of "one-summing-path" [:stands false]))
        moved   (#'record/held-to-prior (decided (update flipped 0 dissoc :cause))
                                        (prior-of "one-summing-path" [:stands true]))]
    (is (= :breaks (:ruling (first (:relation-rulings caused)))) "a stated cause is a reading, not variance")
    (is (= "the aggregate now sums twice" (:cause (first (:relation-rulings caused)))))
    (is (nil? (:relation-reversals caused)))
    (is (= :breaks (:ruling (first (:relation-rulings moved)))) "a moved record is a new thing to read")
    (is (= :amend (:recommend (held caused []))) "and :breaks is held to it as before")))

(deftest the-judge-is-shown-its-last-rulings-and-what-moved
  (let [p (record/design-prompt {:design design :baseline strata-baseline
                                 :relation-prior (prior-of "one-summing-path" [:breaks false]
                                                           "canvas.order/aggregate" [:stands true])})]
    (is (str/includes? p "THE LAST DECISION RULED THEM SO"))
    (is (str/includes? p "[one-summing-path] breaks — (unmoved) last time"))
    (is (str/includes? p "[canvas.order/aggregate] stands — (moved since) last time"))
    (is (str/includes? p "reversal with no cause is not taken")
        "the judge learns what an uncaused reversal costs before it makes one"))
  (is (not (str/includes? (record/design-prompt {:design design :baseline strata-baseline})
                          "THE LAST DECISION RULED"))
      "a first decision has nothing to be held to"))

(defn- reconciled
  "`decision` through the contradiction re-ask over :breaks `breaks`, the judge answering `answer`.
   Returns [decision' every run-round! call]."
  [decision breaks answer]
  (let [calls (atom [])]
    [(with-redefs [record/run-round! (fn [opts] (swap! calls conj opts)
                                       (if answer {:ok (json/generate-string answer)}
                                           {:outcome :no-output :detail "stub"}))]
       (#'record/reconcile-relation-reading! decision ids breaks
                                             {:run-id "r" :kind :design-decision :label "l"}
                                             {:design design :baseline strata-baseline} nil))
     @calls]))

(deftest a-held-relation-honest-over-mismatched-rulings-is-asked-again
  (let [contradicted   (decided (assoc (every-id-stands) 0 {:id "one-summing-path" :ruling "breaks"
                                                            :reason "moves"}))
        [r [call :as cs]] (reconciled contradicted []
                                      {:relation_rulings [{:id "one-summing-path" :ruling "stands"
                                                           :reason "the held reading was right" :cause ""}]})]
    (is (= 1 (count cs)) "one more ask, not a loop")
    (is (= ["one-summing-path"] (get-in (rulings-schema (:schema call)) [:items :properties :id :enum]))
        "only the contradicted ids are asked")
    (is (str/includes? (:prompt call) "it contradicts itself: it called relation-honest HELD"))
    (is (= "l-contradicted-relations" (:label call)) "its artifacts do not overwrite the decision's")
    (is (= ["one-summing-path"] (:relation-contradicted r)) "the inconsistency is recorded where it was made")
    (is (= :stands (:ruling (first (:relation-rulings r)))) "the judge's second answer is the ruling")
    (is (report/proceeds? (held r [])) "resolved toward the check, nothing forces an amend")
    (is (report/validate-event :design-decision (held r [])) "and the ledger takes it"))
  (testing "a broken relation-honest is no contradiction of a mismatch"
    (let [broken (-> (decided (assoc (every-id-stands) 0 {:id "one-summing-path" :ruling "breaks" :reason "m"}))
                     (assoc :checks [{:check :relation-honest :status :broken :note "n"}]))]
      (is (= [broken []] (reconciled broken [] {:relation_rulings []}))))))

(deftest an-omission-claimed-of-an-id-breaks-already-lists-is-flagged-for-the-amender
  (let [r (-> (decided (assoc (every-id-stands) 0 {:id "one-summing-path" :ruling "breaks" :reason "moves"}))
              (assoc :recommend :amend
                     :checks [{:check :relation-honest :status :broken
                               :note "one-summing-path is absent from :breaks."}]
                     :findings [{:check :relation-honest :cites ["x"] :claim "the omission"}])
              (held ["one-summing-path"]))]
    (is (= ["one-summing-path"] (:relation-misread r)))
    (is (str/includes? (:note (first (:checks r))) ":baseline :breaks already lists one-summing-path")
        "the amender is told the record lists it, so it disputes the line instead of rewording around it")
    (is (= :broken (:status (first (:checks r)))) "what else the judge said stays its own")
    (is (report/validate-event :design-decision r) "and the ledger takes it")))

(deftest figures-count-ruling-flips-and-the-reversals-not-taken
  (let [d (fn [rulings & {:as more}]
            (merge {:format :design-decision :recommend :proceed :checks [] :relation-rulings
                    (mapv (fn [[id r]] {:id id :ruling r :reason ""}) rulings)}
                   more))
        f (record/run-figures [(d {"a" :breaks "b" :stands})
                               (d {"a" :stands "b" :stands})
                               (d {"a" :stands "b" :stands}
                                  :relation-reversals [{:id "b" :ruling :breaks :reason "r"}])])]
    (is (= {"a" {:breaks->stands 1} "b" {:stands->breaks 1}} (:relation-flips f))
        "a reversal not taken is still the judge's variance, and the figure measures the judge")
    (is (= {"b" 1} (:relation-reversals f)))))

(deftest the-design-amender-is-handed-the-same-ids-with-the-judges-rulings
  (let [p (record/design-amend-prompt
           {:design design :baseline strata-baseline :recommend :amend :reason "r"
            :raised [{:check :relation-honest :status :broken :note "n"}]
            :rulings [{:id "canvas.order.strata/totals" :ruling :breaks :reason "the level moves"}]
            :out-path "/run/a.edn"})]
    (is (str/includes? p "THE BASELINE'S IDS, which relation-honest is ruled on one by one, as the judge ruled them"))
    (is (str/includes? p "  [canvas.order.strata/totals] breaks — the level moves"))
    (is (str/includes? p "  [one-summing-path]\n") "an id the judge did not rule is still listed")
    (is (str/includes? p "Go through the whole list")
        "a :breaks repaired one omission at a time takes a round per omission")))

(deftest a-design-claim-no-rewording-settled-is-handed-over-as-spent
  (let [amend (fn [running & {:as more}]
                (record/design-amend-prompt
                 (merge {:design strata-design :baseline strata-baseline :recommend :amend :reason "r"
                         :raised [{:claim-ids ["rounded-once"] :claim "a refund rounds again"}]
                         :refuted-running running :out-path "/run/a.edn"}
                        more)))
        p (amend {"rounded-once" 6})]
    (is (str/includes? p "A CLAIM NO REWORDING HAS SETTLED. [rounded-once] has been refuted 6 readings running")
        "a claim reworded through six counterexamples is reworded again unless the amender is told")
    (is (str/includes? p "Restating it at the same strength is off the\ntable"))
    (doseq [repair ["WEAKEN" "STATE THE RESIDUAL AS ONE CLASS" "ASK"]]
      (is (str/includes? p repair) (str "the spent claim is offered " repair)))
    (is (not (str/includes? p ":withdrawn"))
        "a design has no withdrawal to offer; dropping its claim is a retreat")
    (is (str/includes? p "1. rounded-once — a refund rounds again\n   running: [rounded-once] refuted 6 readings in a row")
        "the numbered line says it is spent, where the amender answers it")
    (testing "one refutation short of withdrawable-after is still the amender's to reword"
      (is (not (str/includes? (amend {"rounded-once" 1}) "NO REWORDING HAS SETTLED"))))
    (testing "a spent claim this round did not refute is not the amender's to touch"
      (is (not (str/includes? (amend {"rounded-once" 4} :raised [{:check :goal-served :status :broken :note "n"}])
                              "NO REWORDING HAS SETTLED"))))
    (testing "a question for a person is not the amender's to answer, spent or not"
      (is (not (str/includes? (amend {"rounded-once" 4}
                                     :raised [{:claim-ids ["rounded-once"] :claim "c" :for-person true}])
                              "NO REWORDING HAS SETTLED"))))))

(deftest a-design-interface-refuted-member-by-member-is-restated-by-category
  ;; The element is restated by id alone; its :interface is the baseline's, and so is the enumeration.
  (let [p (record/design-amend-prompt
           {:design strata-design :baseline model-baseline :recommend :amend :reason "r"
            :raised [{:claim-ids ["canvas.order/aggregate"] :claim "it also publishes a rounding helper"}]
            :refuted-running {"canvas.order/aggregate" 3} :out-path "/run/a.edn"})]
    (is (str/includes? p "AN INTERFACE NO ENUMERATION HAS SETTLED. [canvas.order/aggregate] has been refuted 3 readings running"))
    (is (str/includes? p "Do NOT add the member this round found")
        "each member added is the next round's finding; the enumeration is what is refuted")
    (is (str/includes? p "Restate the :interface by category"))
    (is (not (str/includes? p "A CLAIM NO REWORDING HAS SETTLED"))
        "an interface is not weakened like a claim; it is restated as a kind")
    (is (str/includes? (record/design-amend-prompt
                        {:design strata-design :baseline model-baseline :recommend :amend :reason "r"
                         :raised [{:claim-ids ["canvas.order/aggregate"] :claim "c"}]
                         :refuted-running {"canvas.order/aggregate" 2} :out-path "/run/a.edn" :by-hand 7})
                       "entry 7 was written by hand")
        "a design amender is told, as a baseline's is, that the enumeration it may not extend was a person's")))

(deftest a-design-naming-its-strata-is-judged-on-its-levels-not-its-cut
  (let [p (record/design-prompt {:design strata-design :baseline strata-baseline})]
    (is (str/includes? p "STRATA THIS CHANGE TOUCHES, floor first"))
    (is (str/includes? p "  stratified        — does the change sit where its levels say"))
    (doseq [q ["PLACEMENT" "BARRIER" "FIT"]]
      (is (str/includes? p q) (str "the stratified check asks " q)))
    (is (str/includes? p "This check blocks like the others."))
    (is (not (str/includes? p "AND IT NEVER BLOCKS")) "the cut's advisory rule is not this check's")
    (is (not (str/includes? p "CLAIMED DECOMPOSITION, VERTICAL"))))
  (testing "a design written with a cut is judged by decomposable, as it was"
    (let [layered (assoc design :layers [{:claim "extract the aggregate" :mode :judgment}])
          p       (record/design-prompt {:design layered :baseline baseline})]
      (is (str/includes? p "CLAIMED DECOMPOSITION, VERTICAL"))
      (is (str/includes? p "AND IT NEVER BLOCKS"))
      (is (not (str/includes? p "  stratified        —"))))))

(deftest a-stratified-check-is-read-back-and-holds-a-design
  (let [r (record/parse-design-decision
           (json/generate-string {:recommend "amend" :reason "r" :asks "a"
                                  :checks [{:check "stratified" :status "broken" :note "misplaced"}
                                           {:check "goal_served" :status "held" :note "n"}]
                                  :findings [{:cites ["x"] :claim "y" :check "stratified" :claim_id ""}]})
           12 (set report/strata-derivations))]
    (is (= [:stratified :goal-served] (mapv :check (:checks r))))
    (is (= :stratified (:check (first (:findings r)))))
    (is (false? (report/proceeds? r))
        "unlike the cut it replaced, a broken stratified check alone holds the design")))

;; Watched in eight runs: a strata-era judge, shown only `stratified`, answered `decomposable` too
;; because the schema offered it — and once filed a real habitability defect under it, which
;; `proceeds?` then waived as the advisory cut.

(defn- check-enums [schema-json]
  (let [s (json/parse-string schema-json true)]
    [(get-in s [:properties :checks :items :properties :check :enum])
     (get-in s [:properties :findings :items :properties :check :enum])]))

(deftest the-decision-schema-offers-only-the-checks-the-prompt-defines
  (is (= [["relation_honest" "goal_served" "stratified" "routing_coherent"]
          ["relation_honest" "goal_served" "stratified" "routing_coherent" ""]]
         (check-enums (#'record/design-decision-schema {:strata ["totals"]} [] nil)))
      "a strata-era judge offered decomposable answers a check its prompt never asked")
  (is (= [["relation_honest" "goal_served" "decomposable" "routing_coherent"]
          ["relation_honest" "goal_served" "decomposable" "routing_coherent" ""]]
         (check-enums (#'record/design-decision-schema {} [] nil)))
      "a design from before strata is still judged by decomposable, as its prompt says"))

(deftest a-check-the-round-never-asked-is-dropped-and-named
  (let [r (record/parse-design-decision
           (json/generate-string
            {:recommend "recut" :reason "the first landing is not habitable" :asks "a"
             :checks [{:check "decomposable" :status "broken" :note "phase 1 not habitable"}
                      {:check "stratified" :status "held" :note "n"}]
             :findings [{:claim-id "deploy-order" :check "decomposable" :cites ["x"]
                         :claim "old writers run beside the new trigger" :evidence []
                         :for_person false}]})
           12 (set report/strata-derivations))]
    (is (= [:stratified] (mapv :check (:checks r)))
        "a check the prompt never defined is not a ruling the ledger or the figures may count")
    (is (= [:decomposable] (:unasked-checks r)) "the drop is recorded, not silent")
    (is (= [nil] (mapv :check (:findings r)))
        "the defect may be real whatever it was filed under, so the finding stays, check-less")
    (is (false? (report/proceeds? r))
        "a blocking defect filed under a phantom check must not be waived as the advisory cut")
    (is (= r (report/validate-event :design-decision r)) "the ledger takes the note"))
  (testing "a decision whose every check was unasked derived nothing"
    (is (= :no-asked-checks
           (:refused (record/parse-design-decision
                      (json/generate-string {:recommend "proceed" :reason "r" :asks "a"
                                             :checks [{:check "decomposable" :status "held" :note "n"}]
                                             :findings []})
                      12 (set report/strata-derivations)))))))

(deftest the-strata-a-record-names-resolve-against-the-declaration
  (let [listing {:status :listed
                 :elements [{:id "canvas.order/aggregate" :sort :fukan.common.vocab.code.module/Module}
                            {:id "canvas.order.strata/totals" :sort :fukan.common.vocab.code.stratum/Stratum}]}]
    (is (= [] (record/unresolved-subjects strata-design listing)))
    (is (= ["canvas.order.strata/totals"]
           (record/unresolved-subjects strata-design (update listing :elements pop)))
        "a named stratum the declaration does not hold is undeclared, whether or not a claim is about it")))

;; ── Who reads asks, and what is already answered ────────────────────────────

(deftest an-ask-needs-no-finding-and-the-ledger-takes-it
  ;; A doubt the build must not start without is a person's question whether or not it breaks a
  ;; check — the done-when caveat two judges in a row parked in the asks of a self-clearing design.
  (let [r (record/parse-design-decision
           (json/generate-string
            {:recommend "ask" :reason "the intent can be read two ways"
             :checks [{:check "goal_served" :status "held" :note "on one reading"}]
             :findings [] :asks "does a dead session count as working?"})
           4 any-era)]
    (is (= :ask (:recommend r)))
    (is (not (contains? r :findings)))
    (is (= r (report/validate-event :design-decision r)))))

(deftest an-ask-marks-which-of-its-findings-the-question-covers
  ;; The mark is what lets the round repair the rest before asking; on any other recommendation
  ;; there is no question for a finding to belong to, and every finding is the amender's.
  (let [answer (fn [recommend]
                 (record/parse-design-decision
                  (json/generate-string
                   {:recommend recommend :reason "r" :asks "is the pool in scope?"
                    :checks [{:check "goal_served" :status "broken" :note "n"}]
                    :findings [{:claim-id "" :check "goal_served" :cites ["c"] :claim "over-serves"
                                :evidence [] :for_person true}
                               {:claim-id "" :check "" :cites ["c"] :claim "breaks omitted"
                                :evidence [] :for_person false}]})
                  4 any-era))
        ask    (answer "ask")
        amend  (answer "amend")]
    (is (= [true nil] (mapv :for-person (:findings ask))))
    (is (= ask (report/validate-event :design-decision ask)) "the ledger takes the mark")
    (is (not-any? :for-person (:findings amend)))))

(deftest an-ask-marks-the-findings-only-a-person-can-repair-where-the-judge-gave-no-mark
  ;; Twice a judge asked the person a question and handed the amender, as derivable, the finding
  ;; the question was about; once it left unmarked a finding quoting the task's done-when, whose
  ;; only repair was editing the intent. Each cost a round to reach the ask it already held.
  (let [intent {:goal "Merge duplicate courses into one row per content"
                :done-when ["after the migration there are zero groups larger than one"]}
        answer (fn [recommend]
                 (record/parse-design-decision
                  (json/generate-string
                   {:recommend recommend :reason "r" :asks "should quiz import keep its own extractor?"
                    :asks_about ["[tika-arm]"]
                    :checks [{:check "goal_served" :status "broken" :note "n"}]
                    :findings [{:claim-id "tika-arm" :check "goal_served" :cites ["src/q.clj:4"]
                                :claim "quiz import extracts locally" :evidence []}
                               {:claim-id "one-row" :check "goal_served"
                                :cites ["Task done condition: after the migration ... there are zero groups larger than one"]
                                :claim "reconcile runs later" :evidence []}
                               {:claim-id "breaks" :check "" :cites ["src/a.clj:1"]
                                :claim "breaks omitted" :evidence []}]})
                  4 any-era nil intent))
        ask    (answer "ask")]
    (is (= [true true nil] (mapv :for-person (:findings ask)))
        "a claim the asks decides, and a cite quoting the intent, are the person's; the rest is not")
    (is (= ask (report/validate-event :design-decision ask)))
    (is (not-any? :for-person (:findings (answer "amend")))
        "only an ask has a question for a finding to belong to")))

(deftest a-judges-explicit-no-to-the-person-stands-and-is-kept
  ;; A goal-served finding cites the goal by construction, and a claim carries a person's question
  ;; and a derivable defect at once: the overlay handed every such finding to the person, so a
  ;; defect the judge called derivable — "the record can independently correct the wording" — was
  ;; asked three rounds running and never amended, and the report read it as the person's.
  (let [intent {:goal "block a rejected push before it reaches the remote"}
        ask    (record/parse-design-decision
                (json/generate-string
                 {:recommend "ask" :reason "the push range omission is derivable" :asks "keep Gemini?"
                  :asks_about ["rejected-push-is-blocked"]
                  :checks [{:check "goal_served" :status "broken" :note "n"}]
                  :findings [{:claim-id "rejected-push-is-blocked" :check "goal_served"
                              :cites ["The goal requires: block a rejected push before it reaches the remote"]
                              :claim "the push range omits remotes" :evidence [] :for_person false}
                             {:claim-id "other" :check "" :cites ["src/a.clj:1"]
                              :claim "unrelated" :evidence [] :for_person false}]})
                4 any-era nil intent)
        [named plain] (:findings ask)]
    (is (nil? (:for-person named)) "the judge said derivable, so the amender repairs it")
    (is (false? (:judge-marked named))
        "where asks_about or a goal quote would have taken it, the judge's no is kept on the record")
    (is (not (contains? plain :judge-marked)) "a no nothing disagreed with is not recorded")
    (is (= ask (report/validate-event :design-decision ask)) "the ledger takes the kept mark")
    (is (not-any? #(contains? % :judge-marked)
                  (:findings (record/parse-design-decision
                              (json/generate-string
                               {:recommend "amend" :reason "r"
                                :checks [{:check "goal_served" :status "broken" :note "n"}]
                                :findings [{:claim-id "x" :check "goal_served" :cites ["c"]
                                            :claim "c" :evidence [] :for_person false}]})
                              4 any-era)))
        "off an ask there is no question to disagree about")))

(deftest a-persons-finding-no-question-reaches-is-owed
  ;; On an ask about heading priority, a finding that the read code does not yet return the
  ;; committed shape was filed for the person: the figures read the claim as asked at the end, and
  ;; the person was handed a code-owed defect as their question.
  (let [ask (record/parse-design-decision
             (json/generate-string
              {:recommend "ask" :reason "r" :asks "should headings outrank passages?"
               :asks_about ["section-titles-keyword-hits"]
               :checks [{:check "goal_served" :status "broken" :note "n"}]
               :findings [{:claim-id "tools-do-not-overlap" :check "" :cites ["src/read.clj:40"]
                           :claim "the read response still carries scores"
                           :evidence ["src/read.clj:44"] :for_person true}
                          {:claim-id "section-titles-keyword-hits" :check "goal_served" :cites ["c"]
                           :claim "heading priority is undecided" :evidence [] :for_person true}]})
             4 any-era nil {:goal "reading returns passage text and nothing else"})]
    (is (= ["section-titles-keyword-hits"] (mapv :claim-id (:findings ask)))
        "the asked claim stays the person's question")
    (is (= ["tools-do-not-overlap"] (:owed ask)) "a commitment the code does not meet yet is the build's")
    (is (= {"tools-do-not-overlap" ["src/read.clj:44"]} (:owed-at ask)) "with the evidence it was found on")
    (is (= ask (report/validate-event :design-decision ask)))))

(deftest an-ask-keeps-the-claims-its-question-is-about
  ;; The decision kept the asks prose and dropped asks_about once it had marked the findings, so the
  ;; amender run beside the ask could not be told which claims were the person's — and its stale
  ;; sweep restated one, answering the question before the person was asked it.
  (let [answer (fn [recommend]
                 (record/parse-design-decision
                  (json/generate-string
                   {:recommend recommend :reason "r" :asks "accept the 512px fallback?"
                    :asks_about ["[resolution-keyed-on-medium]"]
                    :checks [{:check "goal_served" :status "broken" :note "n"}]
                    :findings [{:claim-id "x" :check "goal_served" :cites ["src/a.clj:1"]
                                :claim "c" :evidence []}]})
                  4 any-era nil nil))
        ask    (answer "ask")]
    (is (= ["resolution-keyed-on-medium"] (:asks-about ask))
        "the claims the question is about outlive the marking, for the amender to be held to")
    (is (= ask (report/validate-event :design-decision ask)) "and the ledger takes them")
    (is (not (contains? (answer "amend") :asks-about)) "off an ask there is no question to be about")))

(deftest a-question-for-a-person-is-no-ruling-on-a-claim
  ;; A for-person question whose cite named a confirmed claim was paired to it, counted as the
  ;; claim refuted after its settlement, and re-asked the judge as a self-contradiction.
  (is (= "" (str (:claim-id (#'record/cite-paired ["settled-rule-unchanged"]
                                                  {:for-person true :claim "q"
                                                   :cites ["The design leaves the settled rule unchanged"]})))))
  (is (= [] (#'record/self-contradicted-ids {:confirmed ["retry-op"]
                                             :findings [{:claim-id "retry-op" :for-person true}]}))))

(deftest the-judge-is-told-when-nobody-will-read-its-ask
  ;; A design declaring :conforms/:within clears on a proceed, and its required ask reached no
  ;; report, gate or view. Said before the judge answers, so a question the build must not start
  ;; without is recommended `ask` instead of parked where nobody looks.
  (let [modest (record/design-prompt {:design design})
        owing  (record/design-prompt {:design (assoc design :standing {:relation :challenges :note "n"})})]
    (is (str/includes? modest "WHO READS asks: NOBODY, on a proceed"))
    (is (str/includes? owing "WHO READS asks: a person"))
    (is (str/includes? modest "  ask      — stop for a person"))))

(deftest the-judge-is-shown-what-is-already-decided
  ;; Shown neither the record's :open nor its grant, the judge re-posed a product decision the
  ;; record stated as made, and asked the worth-executing question a person had already granted.
  (let [p (record/design-prompt
           {:design  (assoc design :open ["The name-order flip is confirmed as a product decision."])
            :answers {:grant {:seq 16 :self? false :note "approved in session"
                              :delta {:added ["drafts-not-scored"] :changed ["pipeline-boundary"]
                                      :dropped []}}
                      :asked {:seq 18 :of 16 :asks "is the compression worth it now?"}}})]
    (is (str/includes? p "- The name-order flip is confirmed as a product decision."))
    (is (str/includes? p "asks does not pose it again"))
    (is (str/includes? p "A person approved entry 16, which this design replaces"))
    (is (str/includes? p "added: drafts-not-scored"))
    (is (str/includes? p "changed: pipeline-boundary"))
    (is (str/includes? p "The grant is not evidence for any structural check")
        "the grant narrows the ask and nothing else; every check is still derived whole")
    (is (str/includes? p "ASKED BEFORE, AND NO GRANT WRITTEN SINCE — at entry 18, of entry 16:\n  is the compression worth it now?"))))

(deftest a-clean-round-on-an-owing-design-proceeds-over-a-question-already-asked
  ;; Two zero-finding rounds over the same open questions recorded :ask once and :proceed once:
  ;; the ASKED BEFORE block ended "recommend ask" and said nothing for a round that found nothing.
  (let [asked   {:asked {:seq 18 :asks "is the compression worth it now?"}}
        owing   (record/design-prompt {:design  (assoc design :standing {:relation :challenges :note "n"})
                                      :answers asked})
        clear   (record/design-prompt {:design design :answers asked})]
    (is (str/includes? owing "If you find nothing, recommend proceed")
        "a proceed already stops for the person, so a clean round has no reason to record :ask")
    (is (not (str/includes? clear "If you find nothing, recommend proceed"))
        "on a design that owes nobody a proceed starts the build, so the asked question must not be waved through")))

(deftest what-is-answered-is-read-off-the-ledger
  (let [claims  (fn [& cs] {:claims (mapv (fn [[id st]] {:id id :statement st :about ["m"]}) cs)})
        granted (assoc design :seq 16 :model (claims ["a" "one"] ["b" "two"]))
        current (assoc design :seq 20 :supersedes {:seq 16 :why "w"}
                       :model (claims ["a" "one"] ["b" "TWO"] ["c" "three"]))
        ledger  (fn [approvals decisions]
                  (with-redefs [ws/amended-seqs (constantly #{20})
                                ws/entry-at-seq (fn [_ _ n] ({16 granted 20 current} n))
                                ws/entries-of (fn [_ _ k] (case k
                                                            :design-approved approvals
                                                            :design-decision decisions
                                                            []))]
                    (record/answered :nido "ws-1" current)))]
    (testing "the nearest grant up the supersedes chain, with the claims changed since it"
      (let [a (ledger [{:seq 17 :design {:seq 16} :at-seq 16 :note "ok"}] [])]
        (is (= {:seq 16 :self? false :note "ok"
                :delta {:added ["c"] :changed ["b"] :dropped []
                        :clauses {"b" {:granted ["two"] :now ["TWO"]}}}}
               (:grant a)))))
    (testing "a question put to a person stays open until a grant is written after it"
      (is (= {:seq 18 :of 16 :asks "worth it?"}
             (:asked (ledger [] [{:seq 18 :design-seq 16 :asks "worth it?"}]))))
      (is (nil? (:asked (ledger [{:seq 19 :design {:seq 16} :at-seq 18}]
                                [{:seq 18 :asks "worth it?"}])))))
    (is (= {} (ledger [] [])) "nothing granted and nothing asked")))

;; ── An answer a person wrote into the record ──────────────────────────────

(defn- answered-over
  "`record/answered` of the entry at `judged` on a ledger holding `entries` by :seq, `amended` the
   seqs the loop's amender wrote."
  [entries judged & {:keys [amended] :or {amended #{}}}]
  (let [of-kind (fn [k] (->> (vals entries) (filter #(= k (:format %))) (sort-by :seq) vec))]
    (with-redefs [ws/amended-seqs (constantly amended)
                  ws/entry-at-seq (fn [_ _ n] (get entries n))
                  ws/entries-of   (fn [_ _ k] (of-kind k))]
      (record/answered :nido "ws-1" (get entries judged)))))

(def ^:private asked-ledger
  {24 (assoc design :seq 24)
   26 {:format :design-decision :seq 26 :design-seq 24 :recommend :ask
       :asks "Does the source gate cover only slices this workstream adds?"}
   27 (assoc design :seq 27 :supersedes {:seq 24 :why "the person narrowed the source gate to slices this workstream adds"})})

(deftest a-reply-written-as-a-superseding-design-answers-the-ask
  ;; Seven analyses watched the judge be told "ASKED BEFORE, AND NO GRANT WRITTEN SINCE" over a
  ;; question the person had answered by rewriting the record, and re-ask it word for word.
  (let [a (answered-over asked-ledger 27)
        p (record/design-prompt {:design (get asked-ledger 27) :answers a})]
    (is (nil? (:asked a)) "a question answered in the record is no longer open")
    (is (= {:seq 26 :asks "Does the source gate cover only slices this workstream adds?"
            :by [{:seq 27 :kind :design :why "the person narrowed the source gate to slices this workstream adds"}]}
           (:answered-since a)))
    (is (str/includes? p "ANSWERED SINCE"))
    (is (str/includes? p "entry 27 (design): the person narrowed the source gate")
        "the judge reads the answer itself, not only that one exists")
    (is (str/includes? p "amend, not ask")
        "a record left inconsistent with the answer is the amender's to repair, not the person's to re-answer")
    (is (not (str/includes? p "ASKED BEFORE")))))

(deftest an-amenders-rewrite-answers-nothing
  ;; An amender handed an asked question settles it by guessing; its rewrite must not close it.
  (is (= "Does the source gate cover only slices this workstream adds?"
         (:asks (:asked (answered-over asked-ledger 27 :amended #{27}))))
      "stamped as the amender's on the index")
  (is (some? (:asked (answered-over (assoc-in asked-ledger [27 :supersedes :why]
                                              "corrected against the code after round 2 of run r")
                                    27)))
      "an amender's record from before the stamp is still known by the :why the loop wrote"))

(deftest a-reply-written-as-a-superseding-intent-answers-the-ask
  ;; The person's answer went into the goal: intent 96 superseded 77, and the design cites 96.
  (let [ledger {77 {:format :intent :seq 77 :goal "g" :done-when ["d"]}
                90 (assoc design :seq 90 :intent {:seq 77})
                95 {:format :design-decision :seq 95 :design-seq 90 :recommend :ask :asks "shared blobs?"}
                96 {:format :intent :seq 96 :goal "g" :done-when ["views are per content"]
                    :supersedes {:seq 77 :why "States the user's answer on shared content"}}
                97 (assoc design :seq 97 :intent {:seq 96}
                          :supersedes {:seq 90 :why "corrected against the code after round 1 of run r"})}
        a      (answered-over ledger 97 :amended #{97})]
    (is (nil? (:asked a)))
    (is (= [{:seq 96 :kind :intent :why "States the user's answer on shared content"}]
           (:by (:answered-since a))))))

(deftest an-amendment-carries-what-a-person-already-granted
  ;; A grant given in chat had nowhere to live but a :design-approved written after the round
  ;; asked, so every round on a :revisit design re-asked cost and revisit.
  (let [ledger {32 (assoc design :seq 32)
                33 (assoc design :seq 33 :supersedes {:seq 32 :why "keeps :rows on the user's challenge"
                                                      :granted "XL effort is fine; revisit the ingest boundary"})}
        a      (answered-over ledger 33)]
    (is (= {:seq 33 :self? true :note "XL effort is fine; revisit the ingest boundary" :carried? true}
           (:grant a)))
    (is (str/includes? (record/design-prompt {:design (get ledger 33) :answers a})
                       "carrying what they had already granted — \"XL effort is fine"))
    (is (nil? (:grant (answered-over ledger 33 :amended #{33})))
        "an amender may not grant anything, so a :granted it wrote is not read")
    (is (m/validate report/Supersedes (get-in ledger [33 :supersedes]))
        "the ledger takes the carried grant")))

(deftest a-grant-answers-the-scope-question-it-decided
  ;; Told "the grant is not evidence for any check", the judge had to break the rule to answer a
  ;; finding whose whole content was that the person had not chosen this scope.
  (let [p (record/design-prompt {:design design :answers {:grant {:seq 4 :self? true}}})]
    (is (str/includes? p "not evidence for any structural check"))
    (is (str/includes? p "a person has not chosen this scope"))))

(deftest a-finding-is-classified-by-its-repair-not-its-claim
  ;; A judge filed both notes on an asked claim for_person, each carrying a derivable defect it
  ;; said the amender should repair; person-marked kept them the person's, and the run ended asked
  ;; with that repair inside the question.
  (let [p (record/design-prompt {:design design})]
    (is (str/includes? p "Classify a finding by its REPAIR, never by its claim")
        "for_person is a property of what repairs a finding, not of which claim it is about")
    (is (str/includes? p "its own finding with\n             for_person false written out")
        "only an explicit false keeps a finding on an asks_about claim off the person's question")))

(deftest a-claim-disagreeing-with-its-own-refuted-by-is-the-amenders
  ;; A run ended asked over which of a claim's two readings held — its statement's or its
  ;; :falsified-by's — and the person was handed the record's own inconsistency to settle.
  (let [p (record/design-prompt {:design design})]
    (is (str/includes? p "its statement says one and its own `refuted by` the other, is not\n             the person's")
        "a record disagreeing with itself is repaired by the amender, not decided by a person")
    (is (str/includes? p "Ask the person only what the amended record still leaves\n             open")
        "the person is asked only once the amendment has made the claim say one thing")))

(deftest a-grant-bounds-what-a-derivable-repair-may-settle
  ;; A judge filed a re-keying of stored summaries as derivable because "the grants already cover
  ;; the scope and cost decisions", and the amender widened what the person had granted unasked.
  (let [p (record/design-prompt {:design design :answers {:grant {:seq 4 :self? true}}})]
    (is (str/includes? p "its scope, its cost, what it\nstores and how that is keyed")
        "the judge sets a repair against the grant before calling it no new human decision")
    (is (str/includes? p "changes what the\ngrant covers, the repair is a choice the grant never made — for_person")
        "a repair that widens a grant is the person's to choose")
    (is (not (str/includes? (record/design-prompt {:design design}) "A grant also bounds"))
        "with no grant there is nothing a repair can widen")))

(deftest the-judge-reads-why-a-person-last-rewrote-the-record
  ;; A mid-build amendment said "noticed while building", and the judge, shown none of it, asked
  ;; whether the whole change was worth executing at XL.
  (let [ledger {27 (assoc design :seq 27)
                28 {:format :design-approved :seq 28 :design {:seq 27} :at-seq 27}
                30 (assoc design :seq 30 :supersedes {:seq 27 :why "noticed while building: the cache needs a key"})
                31 (assoc design :seq 31 :supersedes {:seq 30 :why "corrected against the code after round 1 of run r"})}
        a      (answered-over ledger 31 :amended #{31})
        p      (record/design-prompt {:design (get ledger 31) :answers a})]
    (is (= {:seq 30 :why "noticed while building: the cache needs a key" :supersedes 27 :approved? true}
           (:rewritten a))
        "the nearest record a person wrote, past the amender's rewrite of it")
    (is (str/includes? p "noticed while building"))
    (is (str/includes? p "not the worth of the whole change"))))

(deftest what-a-person-asked-for-reaches-the-round
  ;; A user audit filed as :findings widened the scope, and the round read the widening as scope
  ;; creep against the intent.
  (let [ledger {10 {:format :findings :seq 10 :round 1 :items [{:id "old" :summary "s" :severity :tweak}]}
                16 (assoc design :seq 16)
                17 {:format :design-approved :seq 17 :design {:seq 16} :at-seq 16}
                18 {:format :findings :seq 18 :round 2 :note "user audit"
                    :items [{:id "f4" :summary "Supersede with a P2 claim" :severity :blocker}]}
                20 (assoc design :seq 20 :supersedes {:seq 16 :why "corrected against the code after round 1 of run r"})}
        a      (answered-over ledger 20)
        p      (record/design-prompt {:design (get ledger 20) :answers a})]
    (is (= [18] (map :seq (:asked-for a))) "only what was filed after the design they last granted")
    (is (str/includes? p "WHAT A PERSON HAS ASKED FOR SINCE"))
    (is (str/includes? p "- [f4] Supersede with a P2 claim"))))

;; ── What was asked and granted, against the record as it stands ────────────

(deftest an-ask-of-a-superseded-record-is-posed-against-the-current-one
  ;; A proceed copied the previous round's ask word for word, precondition included, over the
  ;; amendment that had met it — and the person answered the same question twice.
  (let [a (answered-over (assoc asked-ledger 27 (assoc design :seq 27 :supersedes {:seq 24 :why "w"}))
                         27 :amended #{27})
        p (record/design-prompt {:design (assoc design :seq 27) :answers a})]
    (is (= {:seq 26 :of 24 :asks "Does the source gate cover only slices this workstream adds?"}
           (:asked a)))
    (is (str/includes? p "at entry 26, of entry 24")
        "the ask is labelled with the record it was asked of, so the judge can tell it is not this one")
    (is (str/includes? p "this round judges entry 27, not entry 24"))
    (is (str/includes? p "drop a precondition it already\nmeets and any clause it retracted")
        "an ask over a record that changed under it is re-posed against what is left open, not copied")
    (is (str/includes? p "THE DESIGN — entry 27:")
        "the judged entry is named, so a citation into an older entry reads as one")
    (is (not (str/includes? (record/design-prompt {:design (assoc design :seq 24) :answers a})
                            "The record has changed since"))
        "an ask of the very record being judged still stands as asked")))

(deftest a-proceed-asks-unconditionally-and-corrections-are-findings
  ;; A proceed carried "After the reads are assigned to door calls, do you grant…" over the record
  ;; that had assigned them; a round asked a person to correct a summary the amender then fixed.
  (let [p (record/design-prompt {:design design})]
    (is (str/includes? p "On a proceed it is unconditional"))
    (is (str/includes? p "A correction the record needs is a\nfinding for the amender, never asks"))))

(deftest a-repair-decisions-asks-poses-the-grant-apart-from-the-repair
  ;; Ten decisions re-asked the same scope grant, each behind a new "after correcting…" prefix, so the
  ;; person waited on a record that kept changing for a grant that never did.
  (let [p (record/design-prompt {:design design})]
    (is (str/includes? p "On amend, recut or resurvey, too, asks carries no \"after the corrections…\""))
    (is (str/includes? p "so it stands whatever the repair\nwrites — a person can grant that now"))))

(deftest a-changed-claim-is-shown-clause-by-clause-against-its-grant
  ;; Shown only "changed: <id>", the judge asked a person to re-grant a clause that read the same
  ;; in the granted record, and called a consequential rewording a "revised" commitment.
  (let [claims  (fn [st] {:claims [{:id "doors" :statement st :about ["m"]}]})
        ledger  {37 (assoc design :seq 37 :model (claims "Every read goes through a door. LTI context is in scope."))
                 41 {:format :design-approved :seq 41 :design {:seq 37} :note "supersede to exclude LTI context"}
                 42 (assoc design :seq 42 :supersedes {:seq 37 :why "excludes LTI context, as ordered"}
                           :model (claims "Every read goes through a door. LTI context is excluded."))}
        a       (answered-over ledger 42)
        p       (record/design-prompt {:design (get ledger 42) :answers a})]
    (is (= {"doors" {:granted ["LTI context is in scope."] :now ["LTI context is excluded."]}}
           (get-in a [:grant :delta :clauses])))
    (is (str/includes? p "granted: LTI context is in scope.\n      now:     LTI context is excluded."))
    (is (not (str/includes? p "granted: Every read goes through a door."))
        "a clause the grant already covered is not put back in front of the judge as new")
    (is (not (str/includes? p "is the one your note ordered"))
        "no sentence crediting the person with the change is handed to the judge to repeat")
    (is (str/includes? p "only by quoting the words of the\nnote that order it")
        "a change the note ordered is said to be that only on the note's own words")))

(deftest every-grant-up-the-chain-reaches-the-judge
  ;; A newer, narrower grant hid the older one that settled the scope, and four rounds re-asked the
  ;; question the older grant had answered.
  (let [ledger {70 (assoc design :seq 70)
                72 {:format :design-approved :seq 72 :design {:seq 70} :note "per-enrolment stats are Engagement"}
                95 (assoc design :seq 95 :supersedes {:seq 70 :why "w"})
                97 {:format :design-approved :seq 97 :design {:seq 95} :note "phase 2 only"}
                99 (assoc design :seq 99 :supersedes {:seq 95 :why "w"})}
        a      (answered-over ledger 99 :amended #{99})
        p      (record/design-prompt {:design (get ledger 99) :answers a})]
    (is (= 95 (get-in a [:grant :seq])))
    (is (= [{:seq 70 :note "per-enrolment stats are Engagement"}] (get-in a [:grant :earlier])))
    (is (str/includes? p "entry 70 — \"per-enrolment stats are Engagement\"")
        "the scope an older grant decided is still decided under a newer one that does not mention it")))

;; ── What a grant answered, and against which premise ───────────────────────

(deftest a-grant-carries-the-questions-it-answered
  ;; A grant given with no note left the next run's judge only "approved", and it re-posed the
  ;; re-analysis cost the person had been asked about and had granted.
  (let [ledger {30 (assoc design :seq 30)
                31 {:format :design-decision :seq 31 :design-seq 30 :recommend :proceed
                    :asks "Do you accept a paid re-analysis of each old document?"}
                32 {:format :design-decision :seq 32 :design-seq 30 :recommend :proceed
                    :asks "Do you accept a paid re-analysis of each old document?"}
                33 {:format :design-approved :seq 33 :design {:seq 30} :at-seq 32}
                35 {:format :design-decision :seq 35 :design-seq 30 :recommend :proceed :asks "later"}
                36 (assoc design :seq 36 :supersedes {:seq 30 :why "corrected against the code after round 1 of run r"})}
        a      (answered-over ledger 36 :amended #{36})
        p      (record/design-prompt {:design (get ledger 36) :answers a})]
    (is (= [{:seq 31 :asks "Do you accept a paid re-analysis of each old document?"}]
           (get-in a [:grant :answered]))
        "one entry per question asked before the approval, and nothing asked after it")
    (is (str/includes? p "The grant answered what was asked before it:\n  entry 31: Do you accept a paid re-analysis"))))

(deftest a-grants-delta-names-moved-fields-and-relation
  ;; A supersession changing only :shape and the baseline relation was described to the judge as
  ;; "none of those claims has changed", and the move from :extends to :revisit was never posed.
  (let [granted (assoc design :seq 52 :shape "one path" :baseline {:seq 49 :relation :extends :at "x"})
        current (assoc design :seq 54 :shape "fast model first, frontier retry"
                       :baseline {:seq 49 :relation :revisit :breaks ["pages-are-the-unit"]}
                       :supersedes {:seq 52 :why "corrected against the code after round 1 of run r"})
        ledger  {52 granted
                 53 {:format :design-approved :seq 53 :design {:seq 52} :at-seq 52}
                 54 current}
        a       (answered-over ledger 54 :amended #{54})
        p       (record/design-prompt {:design current :answers a})]
    (is (= [:shape] (get-in a [:grant :delta :fields])))
    (is (= {:granted {:relation :extends :at "x"} :now {:relation :revisit :breaks ["pages-are-the-unit"]}}
           (get-in a [:grant :delta :relation])))
    (is (str/includes? p "fields changed: shape"))
    (is (str/includes? p "relation to the baseline, now:     revisit, breaking pages-are-the-unit"))
    (is (not (str/includes? p "what changed since it: none")))))

(deftest a-grant-does-not-carry-across-a-changed-premise
  ;; A revisit granted for a change against intent 1 and baseline 8 was read as granting the next
  ;; change, against intent 42 and baseline 49, which breaks two properties no grant ever named.
  (let [ledger {30 (assoc design :seq 30 :intent {:seq 1} :baseline {:seq 8 :relation :revisit :breaks ["a"]})
                31 {:format :design-approved :seq 31 :design {:seq 30} :at-seq 30 :note "revisit granted"}
                52 (assoc design :seq 52 :intent {:seq 42} :baseline {:seq 49 :relation :revisit :breaks ["a" "b"]}
                          :supersedes {:seq 30 :why "corrected against the code after round 1 of run r"})}]
    (is (nil? (:grant (answered-over ledger 52 :amended #{52})))
        "a grant decided one change against one premise")
    (is (nil? (:grant (answered-over (assoc-in ledger [52 :intent :seq] 1) 52 :amended #{52})))
        "a changed baseline alone ends the chain")
    (is (= 30 (get-in (answered-over (-> ledger (assoc-in [52 :intent :seq] 1) (assoc-in [52 :baseline :seq] 8))
                                     52 :amended #{52})
                      [:grant :seq]))
        "on the same intent and baseline the grant still reaches the amendment")))

(deftest a-reply-stays-an-answer-under-a-later-decision
  ;; Decision 107's asks read "No further decision remains…", which made it the newest ask and
  ;; emptied ANSWERED SINCE, and three rounds re-asked what the person's rewrite at 106 had chosen.
  (let [ledger (assoc asked-ledger
                      28 {:format :design-decision :seq 28 :design-seq 27 :recommend :proceed
                          :asks "No further decision remains."})
        a      (answered-over ledger 27)]
    (is (= 26 (get-in a [:answered-since :seq])) "the reply answers the ask before it")
    (is (= [27] (map :seq (get-in a [:answered-since :by]))))
    (is (str/includes? (record/design-prompt {:design (get ledger 27) :answers a})
                       "names the entry above and the words of the answer it\ncontradicts"))))

(deftest a-runs-own-first-reading-is-no-standing-ask
  ;; Round 2 was told "ASKED BEFORE, AND NO GRANT WRITTEN SINCE — at entry 34" of the ask its own
  ;; round 1 had appended on a first reading, and cited it as an unanswered question.
  (let [ledger {33 (assoc design :seq 33)
                34 {:format :design-decision :seq 34 :design-seq 33 :recommend :ask :run-id "r1"
                    :read-once ["bars-as-on-main"] :asks "Which ticket?"
                    :findings [{:claim-id "bars-as-on-main" :for-person true}]}}
        of     (fn [run-id]
                 (with-redefs [ws/amended-seqs (constantly #{})
                               ws/entry-at-seq (fn [_ _ n] (get ledger n))
                               ws/entries-of   (fn [_ _ k] (filterv #(= k (:format %)) (vals ledger)))
                               record/lineage-of (fn [& _] [(get ledger 34)])]
                   (record/answered :nido "ws-1" (get ledger 33) run-id)))]
    (is (nil? (:asked (of "r1"))) "the reading under test is not a question put to anyone")
    (is (nil? (:asked-of (of "r1"))))
    (is (= 34 (get-in (of "r2") [:asked :seq])) "another run's ask still stands")))

(deftest a-reask-of-the-standing-question-names-its-first-entry
  ;; Three rounds posed one retention question about one unchanged claim, and the ledger and the
  ;; gate counted three open questions.
  (let [claims   {:claims [{:id "seed" :statement "s" :about ["m"]}]}
        ledger   {26 (assoc design :seq 26 :model claims)
                  27 {:format :design-decision :seq 27 :design-seq 26 :recommend :ask :asks "Keep both?"
                      :findings [{:claim-id "seed" :for-person true}]}
                  29 {:format :design-decision :seq 29 :design-seq 26 :recommend :ask :asks "Keep both, now?"
                      :reasks 27 :findings [{:claim-id "seed" :for-person true}]}
                  28 (assoc design :seq 28 :model claims)}
        reasked  (fn [asked decision]
                   (with-redefs [ws/entry-at-seq (fn [_ _ n] (get ledger n))]
                     (#'record/reasked [:nido "ws-1"] (get ledger 28) decision asked "r2")))
        decision {:recommend :ask :asks "Do you want both kept?" :findings [{:claim-id "seed" :for-person true}]}]
    (is (= 27 (reasked {:seq 27} decision)) "same claims at the same text are the same question")
    (is (= 27 (reasked {:seq 29} decision)) "a re-ask of a re-ask names the first")
    (is (= 27 (reasked {:seq 27} {:recommend :ask :asks "Keep both?"})) "same words are the same question")
    (is (nil? (reasked {:seq 27} (assoc decision :findings [{:claim-id "other" :for-person true}]))))
    (is (nil? (reasked {:seq 27} (assoc decision :recommend :amend))))
    (is (= "Design decision: ask — as asked at entry 27"
           (report/report-title {:format :design-decision :recommend :ask :reasks 27})))))

(deftest an-ask-over-the-standing-ask-of-its-own-run-is-a-reask
  ;; Watched: one retention question asked in three rounds of one run, each in new words and filed
  ;; under a different set of claims (one round under none), and none of the three named the first —
  ;; the gate counted three open questions where a person owed one answer.
  (let [ledger   {26 (assoc design :seq 26 :model {:claims [{:id "seed" :statement "s" :about ["m"]}]})
                  27 {:format :design-decision :seq 27 :design-seq 26 :recommend :ask :run-id "r1"
                      :asks "Entry 25 granted summaries kept per content — still?"
                      :findings [{:claim-id "seed" :for-person true}]}
                  28 {:format :design-decision :seq 28 :design-seq 26 :recommend :proceed :run-id "r1"
                      :asks "Anything else?"}}
        reasked  (fn [asked decision run-id]
                   (with-redefs [ws/entry-at-seq (fn [_ _ n] (get ledger n))]
                     (#'record/reasked [:nido "ws-1"] (get ledger 26) decision asked run-id)))
        decision {:recommend :ask :asks "You approved keeping summaries; is reuse across contents wanted?"
                  :findings []}]
    (is (= 27 (reasked {:seq 27} decision "r1"))
        "no amendment answers a person, so within the run the standing ask is the question asked again")
    (is (nil? (reasked {:seq 27} decision "r3"))
        "another run's ask is one question only on the evidence of its words or its claims")
    (is (nil? (reasked {:seq 28} decision "r1"))
        "a proceed's asks stopped nobody, so an :ask after it is a question put for the first time")))

(deftest a-carried-self-grant-is-written-rather-than-parked
  ;; A person's "ok" written into the design as :supersedes :granted parked the proceed for a
  ;; second approval, which the person gave four seconds later in the same words.
  (let [d        (assoc design :seq 61 :standing {:relation :challenges :note "n"}
                        :supersedes {:seq 58 :why "narrow depth" :granted "ok (keep the relaxation)"})
        ledger   {58 (assoc design :seq 58) 61 d}
        written  (atom [])
        status   (fn [design-entry]
                   (with-redefs [ws/amended-seqs      (constantly #{})
                                 ws/entry-at-seq      (fn [_ _ n] (get (assoc ledger 61 design-entry) n))
                                 ws/entries-of        (fn [_ _ _] [])
                                 ws/append-entry!     (fn [_ _ _ body] (swap! written conj (read-string body)) "0063.edn")
                                 nido.coordinator.record.standing/of-design (constantly {:decidable? true})]
                     (#'record/proceeding-status [:nido "ws-1"] {:design-seq 61} {:seq 62})))]
    (is (= :cleared (status d)))
    (is (= [{:format :design-approved :design {:seq 61} :at-seq 62 :note "ok (keep the relaxation)" :carried true}]
           @written))
    (is (= (first @written) (report/validate-event :design-approved (first @written))))
    (reset! written [])
    (is (= :proceed (status (update d :supersedes dissoc :granted))) "no grant carried, the person is asked")
    (is (empty? @written))))

;; ── A claim spent across runs ───────────────────────────────────────────────

(deftest a-subject-is-spent-once-a-rewording-was-itself-refuted
  (is (= {"writers-order" 2 "readers" 11}
         (record/spent {"writers-order" 2 "readers" 11 "fresh" 1} (constantly true)))
      "one refutation is a defect to repair; a second after the rewording is the circle")
  (is (= {} (record/spent nil (constantly true)))))

(deftest only-a-subject-an-amender-is-offered-a-repair-for-is-spent
  ;; Watched: runs ended :unfixable on `shape` and on `:goal-served`, and the headline told a person
  ;; to weaken or withdraw them, though no amender had been offered either — a check cannot be
  ;; weakened, and `shape` pools four different repaired defects under one id.
  (is (= {"writers-order" 3}
         (record/spent {"writers-order" 3 "shape" 4 ":goal-served" 2} #{"writers-order"}))
      "a subject outside the offer is never spent, however often it was refuted"))

(deftest a-cap-ends-on-a-spent-subject-only-while-a-repair-is-owed
  (is (= ["writers-order"]
         (record/spent-at-cap {:findings [{:claim-id "writers-order"}]
                               :spent {"writers-order" 3}})))
  (is (nil? (record/spent-at-cap {:findings [] :spent {"writers-order" 3}}))
      "a round owing no amendment has nothing a cap would leave half-done")
  (is (nil? (record/spent-at-cap {:findings [{:claim-id "readers" :for-person true}]
                                  :spent {"readers" 3} :spent-awaiting ["readers"]}))
      "a subject held only as a person's question is owed an answer, not an amendment — the cap
       ends on that as an ask, never as a rewording that failed"))

(deftest the-figures-name-what-the-run-ended-spent
  ;; The figures printed {:broken 1 :alone 0 :at-end true} for a claim refuted for the eleventh
  ;; time, exactly as for a new defect.
  (let [d (fn [n spent] (cond-> {:format :design-decision :seq n :recommend :amend
                                 :checks [{:check :stratified :status :held}]
                                 :findings [{:claim-id "writers-order"}]}
                          spent (assoc :spent spent)))]
    (is (= {"writers-order" 11} (:spent (record/run-figures [(d 40 {"writers-order" 10}) (d 41 {"writers-order" 11})]))))
    (is (nil? (:spent (record/run-figures [(d 40 {"writers-order" 10}) (d 41 nil)])))
        "only the run's END says what is still spent; a claim the last round let go is not")))

(deftest a-claim-already-asked-about-is-shown-with-its-grants
  ;; Three asks on one claim each granted one more exception of the same at-least-once class,
  ;; spending the person three times on one decision.
  (let [current (assoc design :seq 20 :supersedes {:seq 16 :why "w"}
                       :model {:claims [{:id "one-per-content" :statement "s" :about ["m"]}]})
        ask     (fn [n] {:seq n :design-seq 16 :recommend :ask :asks (str "grant case " n "?")
                         :findings [{:claim-id "one-per-content" :for-person true}
                                    {:claim-id "not-carried"}]})
        a       (with-redefs [ws/amended-seqs (constantly #{20})
                              ws/entry-at-seq (fn [_ _ n] ({20 current 16 (assoc design :seq 16)} n))
                              ws/entries-of (fn [_ _ k] (case k
                                                          :design-approved [{:seq 77 :design {:seq 16} :note "lease lapse ok"}]
                                                          :design-decision [(ask 76) (ask 90)]
                                                          []))]
                  (record/answered :nido "ws-1" current))]
    (is (= {"one-per-content" [{:seq 76 :asks "grant case 76?" :granted {:seq 77 :note "lease lapse ok"}}
                               {:seq 90 :asks "grant case 90?"}]}
           (:asked-of a))
        "each earlier ask on a claim the design still carries, with the grant that answered it")
    (let [p (record/design-prompt {:design current :answers a})]
      (is (str/includes? p "[one-per-content] entry 76: grant case 76?"))
      (is (str/includes? p "granted at entry 77 — \"lease lapse ok\""))
      (is (str/includes? p "CLASS level")
          "the next ask on the claim is framed so one answer covers the next instance"))))

;; ── Who judged, and at which revision ───────────────────────────────────────

(def ^:private stand-in {:reviewer :claude :instead-of :codex :because "You've hit your usage limit"})

(deftest a-round-a-stand-in-answered-says-so
  (let [dir (str (fs/create-temp-dir))]
    (try
      (with-redefs [cstate/run-dir       (constantly dir)
                    codex/run-reviewer!  (fn [{:keys [out-path]}]
                                           (spit out-path "{}")
                                           {:exit 0 :log-path "l" :judged-by stand-in})]
        (is (= stand-in (:judged-by (#'record/run-round! {:run-id "r" :kind :baseline-review
                                                          :prompt "p"})))
            "a successful round dropped who answered, so every stand-in's verdict read as codex's"))
      (finally (fs/delete-tree dir)))))

(deftest every-judge-is-told-to-read-jj-without-snapshotting
  (let [dir  (str (fs/create-temp-dir))
        sent (atom nil)]
    (try
      (with-redefs [cstate/run-dir      (constantly dir)
                    codex/run-reviewer! (fn [{:keys [prompt]}]
                                          (reset! sent prompt)
                                          {:exit 1 :log-path "l" :judged-by {:reviewer :codex}})]
        (#'record/run-round! {:run-id "r" :kind :baseline-review :prompt "THE CHECKS"})
        (is (str/includes? @sent "jj --ignore-working-copy")
            "a judge's bare `jj log` is denied the lock write under the read-only sandbox, and the claim it read for goes unchecked")
        (is (str/ends-with? @sent "THE CHECKS")
            "the prompts end on the list the judge's answer is drawn from, and that stays last"))
      (finally (fs/delete-tree dir)))))

(deftest an-amender-is-told-the-jj-spelling-its-shell-admits
  (is (str/includes? @#'record/amender-reading "--ignore-working-copy")
      "the amender's shell admits jj only with the flag, so an amender not told so spends a turn per refused read"))

(deftest a-judge-its-vendor-would-not-run-ends-reviewer-unavailable-quoting-the-vendor
  (let [dir  (str (fs/create-temp-dir))
        line "ERROR: Selected model is at capacity. Please try a different model."]
    (try
      (with-redefs [cstate/run-dir      (constantly dir)
                    codex/run-reviewer! (fn [_] {:exit 1 :log-path "l" :judged-by {:reviewer :codex}
                                                 :unavailable {:signal :capacity :message line}})]
        (let [r (#'record/run-round! {:run-id "r" :kind :baseline-review :prompt "p"})]
          (is (= :reviewer-unavailable (:outcome r))
              ":codex-failed sends a reader to a judge that broke, when the vendor refused to run one")
          (is (str/includes? (:detail r) line)
              "the vendor's line is the only place the wait it calls for is stated")
          (is (not (contains? @#'record/judge-outcomes (name (:outcome r))))
              "the refused judge read nothing, so a run of only such rounds is not analysed")))
      (finally (fs/delete-tree dir)))))

(deftest a-judgement-keeps-who-made-it
  (let [json (baseline-json {:verdict "sufficient" :reason "r" :confirmed [] :findings []})
        r    (#'record/judged {:ok json :judged-by stand-in} #(record/parse-baseline-review % 3))]
    (is (= stand-in (:judged-by r))
        "a stand-in's confirmations settle subjects for later rounds exactly as codex's would")
    (is (= r (report/validate-event :baseline-review r))
        "the ledger refuses a judgement whole, so what is kept must be what it admits"))
  (is (= {:reviewer :claude}
         (:judged-by (#'record/judged {:ok (baseline-json {:verdict "sufficient" :reason "r"
                                                          :confirmed [] :findings []})
                                       :judged-by {:reviewer :claude :because nil}}
                                      #(record/parse-baseline-review % 3))))
      "a field the schema would refuse is left out rather than losing the entry")
  (is (nil? (:judged-by (#'record/judged {:ok (json/generate-string {:verdict "fits" :reason "r"
                                                                    :cites []})
                                         :judged-by stand-in}
                                        #(record/parse-stratum-reading % "s"))))
      "a level reading is evidence inside a decision, not a judgement the ledger holds alone"))

(deftest a-judgement-names-the-revision-its-judge-read
  (let [r (#'record/stamp-run {:format :baseline-review :baseline-seq 3 :reason "r" :verdict :sufficient}
                              {:run-id "baseline-loop-1" :judged-tree {:rev "abc123" :ahead 2}})]
    (is (= {:rev "abc123" :ahead 2} (:tree r))
        "a confirmation made at the base and one made at the tip must be told apart without a transcript")
    (is (= r (report/validate-event :baseline-review r))))
  (is (not (contains? (#'record/stamp-run {:outcome :codex-failed} {:judged-tree {:rev "a"}}) :tree))
      "an outcome is not a judgement and names no tree"))

;; ── A decision that contradicts itself ──────────────────────────────────────
;;
;; Each shape below is one a judge returned: a :recut with only goal-served broken, a finding filed
;; under goal_served beside goal_served held, and a route's defect filed against the claim it runs
;; through while that claim was confirmed.

(defn- decision-json [recommend checks findings]
  (json/generate-string {:recommend recommend :reason "r" :asks "a"
                         :checks (mapv (fn [[c s]] {:check c :status s :note "n"}) checks)
                         :findings findings}))

(def ^:private strata (set report/strata-derivations))

(deftest a-recut-no-decomposition-check-supports-is-an-amend
  (let [r (record/parse-design-decision
           (decision-json "recut" [["goal_served" "broken"] ["stratified" "held"] ["routing_coherent" "held"]]
                          [{:check "goal_served" :cites ["x"] :claim "scores drift after re-chunking"}])
           3 strata)]
    (is (= :amend (:recommend r))
        "an amender told to recut redraws the strata to repair what only the goal got wrong")
    (is (= :recut (:judge-recommended r)) "what the judge said is kept beside what nido read")
    (is (= r (report/validate-event :design-decision r)) "the ledger takes the note"))
  (doseq [c ["stratified" "routing_coherent"]]
    (let [r (record/parse-design-decision
             (decision-json "recut" [["goal_served" "held"] [c "broken"]]
                            [{:check c :cites ["x"] :claim "the cut does not hold"}])
             3 strata)]
      (is (= :recut (:recommend r)) (str "a broken " c " is the decomposition failing, so the recut stands"))
      (is (nil? (:judge-recommended r))))))

(deftest a-finding-under-a-check-ruled-held-is-check-less
  (let [r (record/parse-design-decision
           (decision-json "amend" [["goal_served" "held"] ["relation_honest" "broken"]]
                          [{:claim-id "writers-state-order" :check "goal_served" :cites ["x"]
                            :claim "source answers can share an order"}])
           3 strata)
        [f] (:findings r)]
    (is (nil? (:check f)) "tied to a held check, the report reads goal-served as both held and broken")
    (is (= :goal-served (:filed-under f)) "what it was filed under stays readable")
    (is (= "writers-state-order" (:claim-id f)) "the defect is still a refutation of its claim")
    (is (= r (report/validate-event :design-decision r)))))

(deftest a-finding-citing-a-health-observation-is-routing-s
  (let [health  #{"cleanup-skips-minimal-evaluation"}
        finding {:claim-id "attempt-writes-keep-their-commits" :check ""
                 :cites ["Design claim [attempt-writes-keep-their-commits]"
                         "Health observation [cleanup-skips-minimal-evaluation], marked invisibly incomplete"]
                 :claim "cleanup leaves the branch with its marked observation unrealised"}
        parse   #(record/parse-design-decision
                  (decision-json "amend" [["routing_coherent" %1] ["goal_served" "held"]] [%2])
                  3 strata health)]
    (let [[f] (:findings (parse "broken" finding))]
      (is (= :routing-coherent (:check f)) "a route's defect is a routing defect")
      (is (nil? (:claim-id f))
          "a co-cited claim is not refuted by its route — counted, the figures report it broken at end"))
    (let [r   (parse "held" finding)
          [f] (:findings r)]
      (is (and (nil? (:check f)) (nil? (:claim-id f)) (= :routing-coherent (:filed-under f)))
          "with routing ruled held, the finding is the routing defect the judge would not call a break")
      (is (= r (report/validate-event :design-decision r))))
    (let [[f] (:findings (parse "held" (assoc finding :check "goal_served")))]
      (is (= :goal-served (:filed-under f)) "a check the judge named is its answer, not nido's to move")
      (is (= "attempt-writes-keep-their-commits" (:claim-id f))))))

(deftest a-second-reading-names-the-single-confirmation-it-pairs-with
  ;; Watched: a run confirmed six ids that an earlier, dead run had confirmed once, stopped
  ;; sufficient after one round, and nothing on the entry or in the report said why it was allowed
  ;; to — the pairing lived in the earlier entry's :read-once and a docstring.
  (let [r (#'record/with-readings {:format :baseline-review :code-identity "t"
                                   :confirmed ["course-store" "transactions-join" "settled-one"]
                                   :checked-at {"course-store" ["src/a.clj:1"]
                                                "transactions-join" ["src/b.clj:2"]
                                                "settled-one" ["src/c.clj:3"]}
                                   :judged-by claude}
                                  (constantly true)
                                  {:standing {"course-store" {:ws-id "ws-1" :seq 34 :run-id "baseline-loop-dead"
                                                              :judged-by codex}
                                              "transactions-join" {:ws-id "ws-1" :seq 34 :run-id "baseline-loop-dead"
                                                                   :judged-by codex}
                                              "settled-one" {:ws-id "ws-1" :seq 20}}
                                   :single #{"course-store" "transactions-join"}
                                   :settled {"settled-one" {:ws-id "ws-1" :seq 20}}
                                   :asked ["course-store" "transactions-join"]})]
    (is (= [{:id "course-store" :seq 34 :ws-id "ws-1" :run-id "baseline-loop-dead"}
            {:id "transactions-join" :seq 34 :ws-id "ws-1" :run-id "baseline-loop-dead"}]
           (:paired-with r))
        "the second reading is what lets the run stop, so it names the first and the run that made it")
    (is (nil? (:read-once r)) "and a paired id is not read once")
    (is (report/validate-event :baseline-review (assoc r :verdict :sufficient :reason "r" :baseline-seq 1))
        "and the ledger takes it"))
  (is (nil? (:paired-with (#'record/with-readings {:format :baseline-review :confirmed ["c"] :checked-at {"c" ["src/a.clj:1"]}}
                                                  (constantly true)
                                                  {:standing {"c" {:ws-id "ws-1" :seq 3}} :single #{"c"} :asked ["c"]})))
      "a record that read no single tree pairs with nothing on the ledger")
  (is (nil? (:paired-with (#'record/with-readings {:format :baseline-review :code-identity "t" :confirmed ["c"]
                                                   :checked-at {"c" ["src/a.clj:1"]} :judged-by codex}
                                                  (constantly true)
                                                  {:standing {"c" {:ws-id "ws-1" :seq 3 :judged-by codex}}
                                                   :single #{"c"} :asked ["c"]})))
      "the judge that confirmed it once confirming it again completes nothing"))

(deftest a-refutation-of-a-read-once-id-names-the-confirmation-it-denies
  ;; Watched: #154 confirmed shape on a first reading; #156 refuted it at the same tree and carried
  ;; nothing joining the two — :overturns runs the other way only.
  (let [r (#'record/with-readings {:format :baseline-review :code-identity "t"
                                   :findings [{:claim-id "shape" :cites ["c"] :claim "x"}]}
                                  (constantly false)
                                  {:standing {"shape" {:ws-id "ws-1" :seq 154 :run-id "baseline-loop-first"}
                                              "settled-one" {:ws-id "ws-1" :seq 20}}
                                   :single #{"shape"}
                                   :settled {"settled-one" {:ws-id "ws-1" :seq 20}}})]
    (is (= [{:id "shape" :seq 154 :ws-id "ws-1"}] (:contradicts-read-once r))
        "one reading each way at one tree is the whole meaning of the run, and the entry has to say it")
    (is (= [{:id "shape" :was :holds}] (:splits r)) "beside the split, which names no entry")
    (is (report/validate-event :baseline-review (assoc r :verdict :falsified :reason "r" :baseline-seq 1))
        "and the ledger takes it")))

(deftest round-1-names-the-runs-whose-judgements-it-read
  ;; Watched: a run restarted 19 s after its predecessor died in amend-round-2 inherited its
  ;; refutation count and its settled readings, and the report named the predecessor nowhere.
  (with-redefs [record/run-status (fn [id] ({"baseline-loop-dead" "interrupted"} id))]
    (let [lineage [{:seq 6 :run-id "baseline-loop-dead" :findings [{:claim-id "citation-helpers"}]}
                   {:seq 4 :run-id "baseline-loop-older" :findings [{:claim-id "withdrawn"}]}
                   {:seq 5 :run-id "baseline-loop-me" :findings [{:claim-id "citation-helpers"}]}]
          standing {"course-store" {:ws-id "ws-1" :seq 6 :run-id "baseline-loop-dead"}
                    "by-hand" {:ws-id "ws-1" :seq 2}}]
      (is (= [{:run-id "baseline-loop-dead" :status "interrupted"}]
             (#'record/continues "baseline-loop-me" 1 standing {"citation-helpers" 2} lineage))
          "the run behind a standing confirmation or a counted refutation, with how it ended — never this run, nor one whose refutation no longer counts")
      (is (= [{:run-id "baseline-loop-gone" :status "unknown"}]
             (#'record/continues "me" 1 {"c" {:ws-id "ws-1" :seq 1 :run-id "baseline-loop-gone"}} {} []))
          "a run whose report is gone is still named")
      (is (nil? (#'record/continues "baseline-loop-me" 2 standing {"citation-helpers" 2} lineage))
          "past round 1 the run reads its own amendments, which no earlier run judged"))))

(deftest round-1-names-a-record-written-by-hand-after-the-run-it-continues
  ;; Seen live (110ff6): the predecessor's last round judged entry 11 and had no amend phase; entry
  ;; 12 was appended by hand a minute later and dropped a falsifier R1 then re-found, and nothing
  ;; measured what it gave up.
  (let [judged  {:format :baseline :seq 11
                 :load-bearing [{:id "c1" :property "a blank text view is never served as content"
                                 :falsified-by "serving a blank text view as content, or a null page"}]}
        by-hand (assoc-in judged [:load-bearing 0 :falsified-by] "a null page")
        by-hand (assoc by-hand :seq 12)
        lineage [{:seq 20 :baseline-seq 11 :run-id "baseline-loop-prev" :findings []}]
        rows    (atom [{:seq 11} {:seq 12}])]
    (with-redefs [ws/read-ws      (fn [_ _] {:entries @rows})
                  ws/entry-at-seq (fn [_ _ n] (when (= 11 n) judged))]
      (let [h (#'record/by-hand [:nido "ws-1"] :baseline :baseline-seq by-hand
                                [{:run-id "baseline-loop-prev" :status "max-iters"}] lineage)]
        (is (= :hand (:record-by h)) "no run's amender stamped the entry")
        (is (= [12 11 "baseline-loop-prev"] ((juxt :seq :after :run-id) h)))
        (is (seq (:retreats h)) "the dropped falsifier is measured as an amend phase would have")
        (is (every? :by-hand (:retreats h))))
      (reset! rows [{:seq 11} {:seq 12 :amended-by "baseline-loop-prev"}])
      (is (nil? (#'record/by-hand [:nido "ws-1"] :baseline :baseline-seq by-hand
                                  [{:run-id "baseline-loop-prev"}] lineage))
          "an entry the continued run's own amender wrote was measured by its amend phase")
      (is (nil? (#'record/by-hand [:nido "ws-1"] :baseline :baseline-seq (assoc judged :seq 11)
                                  [{:run-id "baseline-loop-prev"}] lineage))
          "the record that run last judged is no amendment at all"))))

(deftest a-round-names-the-subjects-it-asks-again-only-for-their-context
  ;; Watched: a claim confirmed and then refuted at one tree on the same words read as a bare
  ;; refutation, with nothing naming the sibling amendment that reopened it.
  (let [knock-ons {"strata-placement" {:ws-id "ws-1" :seq 19 :moved ["parse-artifacts"]}
                   "settled-anyway"   {:ws-id "ws-1" :seq 19 :moved ["parse-artifacts"]}}]
    (is (= [{:id "strata-placement" :seq 19 :ws-id "ws-1" :moved ["parse-artifacts"]}]
           (#'record/knock-on-rows knock-ons #{"strata-placement" "parse-artifacts"}))
        "only what the round asks: a knock-on it did not put to the judge was read by nobody")
    (is (= [] (#'record/knock-on-rows {} #{"strata-placement"})))))

(deftest a-report-says-what-the-run-continued
  (let [report {:rounds [{:round 1 :phases [{:phase "judge"
                                             :paired-with [{:id "a" :seq 34 :ws-id "w"} {:id "b" :seq 34 :ws-id "w"}]
                                             :continues [{:run-id "baseline-loop-dead" :status "interrupted"}]}
                                            {:phase "amend"}]}
                         {:round 2 :phases [{:phase "judge"
                                             :paired-with [{:id "c" :seq 50 :ws-id "w"}]
                                             :contradicts-read-once [{:id "shape" :seq 154 :ws-id "w"}]}]}]}]
    (is (= {:paired {34 ["a" "b"] 50 ["c"]}
            :contradicted [{:id "shape" :seq 154}]
            :continues [{:run-id "baseline-loop-dead" :status "interrupted"}]}
           (record/continued report))
        "read off the report, so a finished run's headline says it as the run did"))
  (is (= {} (record/continued {:rounds [{:round 1 :phases [{:phase "judge"}]}]}))))

(deftest the-subject-at-start-says-what-the-run-was-pointed-at-and-what-stood-beside-it
  ;; Watched: a run named entry 4 while entry 6, from a run interrupted a minute earlier,
  ;; already superseded it; the report's :target held {cwd, base nil, files []}, and the
  ;; forked lineage — 6 left with no review naming it — was readable only from entry mtimes.
  (let [entries {4 {:format :baseline :seq 4}
                 6 {:format :baseline :seq 6 :supersedes {:seq 4 :why "reworded"}}}
        latest  (fn [kind] (fn [_ _ k] (when (= kind k) (get entries 6))))]
    (with-redefs [ws/latest-entry  (latest :baseline)
                  ws/entry-at-seq  (fn [_ _ n] (get entries n))]
      (is (= {:seq 4 :named? true :newest-at-start 6
              :skipped {:seq 6 :supersedes-subject? true}}
             (record/subject-at-start [:nido "ws-1"] :baseline (get entries 4)))
          "a named run pointed past a superseding entry forks the lineage, and the report says so")
      (is (= {:seq 6 :named? false :newest-at-start 6}
             (record/subject-at-start [:nido "ws-1"] :baseline nil))
          "an unnamed run starts on the newest, and skips nothing")))
  (let [entries {4 {:format :baseline :seq 4}
                 6 {:format :baseline :seq 6}}]
    (with-redefs [ws/latest-entry (fn [_ _ k] (when (= :baseline k) (get entries 6)))
                  ws/entry-at-seq (fn [_ _ n] (get entries n))]
      (is (= {:seq 6 :supersedes-subject? false}
             (:skipped (record/subject-at-start [:nido "ws-1"] :baseline (get entries 4))))
          "a newer baseline of another area is a neighbour, not a skipped amendment")))
  (is (nil? (record/subject-at-start nil :baseline nil)) "no ledger, nothing to say")
  (with-redefs [ws/latest-entry (constantly nil)]
    (is (nil? (record/subject-at-start [:nido "ws-1"] :design nil)) "no entry of the kind")))

(deftest the-subject-at-start-says-when-the-standing-design-cites-an-older-baseline
  ;; Watched: design entry 28 cited baseline 21; the run judged 29, which 24, 26 and 29 had
  ;; superseded 21 to reach, and neither the report nor the headline said the premise had moved.
  (let [latest (fn [baseline design] (fn [_ _ k] (case k :baseline baseline :design design nil)))]
    (with-redefs [ws/latest-entry (latest {:format :baseline :seq 29}
                                          {:format :design :seq 28 :baseline {:seq 21 :relation :revisit}})]
      (is (= {:design-seq 28 :cites 21 :moved? true}
             (:premise (record/subject-at-start [:nido "ws-1"] :baseline nil)))
          "the design was decided on a reading this run no longer judges — its re-citation is owed"))
    (with-redefs [ws/latest-entry (latest {:format :baseline :seq 21}
                                          {:format :design :seq 28 :baseline {:seq 21}})]
      (is (= {:design-seq 28 :cites 21}
             (:premise (record/subject-at-start [:nido "ws-1"] :baseline nil)))
          "a design citing the judged baseline has not moved, and says so without a flag"))
    (with-redefs [ws/latest-entry (latest {:format :baseline :seq 29} nil)]
      (is (nil? (:premise (record/subject-at-start [:nido "ws-1"] :baseline nil)))
          "no design stands, so no premise to have moved"))
    (with-redefs [ws/latest-entry (latest {:format :baseline :seq 29}
                                          {:format :design :seq 28 :baseline {:seq 21}})]
      (is (nil? (:premise (record/subject-at-start [:nido "ws-1"] :design nil)))
          "a design run's subject is the design; the premise question is the baseline run's"))))

(deftest a-later-round-that-read-an-identity-is-named
  (let [rep {:rounds [{:round 1 :phases [{:phase "judge" :identity-unreadable "stale"}]}
                      {:round 2 :phases [{:phase "judge" :code-identity "d54d8bc5"}]}]}]
    (is (= 2 (record/readable-from rep)))
    (is (nil? (record/readable-from (update rep :rounds pop))) "none read after it")
    (is (nil? (record/readable-from {:rounds [{:round 1 :phases [{:phase "judge" :code-identity "x"}]}]})))))

(deftest a-dispute-ends-re-reported-unchecked-or-withdrawn
  ;; structure-deadline: the report said a dispute was raised and never how the next round
  ;; settled it — withdrawal was only a finding's absence.
  (let [ended (fn [c] (#'record/dispute-endings :key
                       {:iter 2 :history [{:iter 1 :disputes [{:key "a" :claim "a"} {:key "b" :claim "b"}
                                                               {:key "c" :claim "c"}]}]}
                       c))]
    (is (= [{:claim "a" :ended "re-reported"} {:claim "b" :ended "unchecked"} {:claim "c" :ended "withdrawn"}]
           (ended {:findings [{:key "a"}] :record {:unchecked [{:id "b"}]}})))
    (is (nil? (#'record/dispute-endings :key {:iter 2 :history [{:iter 1 :disputes []}]} {})))))

(deftest a-judge-answers-only-the-disputes-it-can-still-end
  ;; Watched: an R1 dispute on a claim R2 ended, and R3 and R4 then settled, was put to every judge
  ;; through R6, which withdrew it again each time because the claim was no longer its subject.
  (let [d       (fn [k] {:key k :claim (pr-str k) :because "no"})
        history [{:iter 1 :disputes [(d [:claim-id "old"])]}
                 {:iter 2 :disputes [(d [:claim-id "a"]) (d [:claim-id "settled"]) (d [:blocks :goal-served])
                                     (d [:check :decomposable :claims ["a" "settled"]])
                                     (d [:check :goal-served :claims ["settled"]])]}
                 {:iter 2 :disputes [(d [:claim-id "quiet"])] :quiet true}]
        keys-of #(mapv :key (record/disputes-for-judge history 3 %))]
    (is (= [[:claim-id "a"] [:blocks :goal-served] [:check :decomposable :claims ["a" "settled"]]]
           (keys-of #{"a"}))
        "a dispute the round before raised reaches the judge while any claim it names is asked; one
         under no claim always does — an earlier round's was ended already, and one about a claim
         the judge may not rule on is one it can neither withdraw nor re-report")
    (is (= 5 (count (keys-of nil))) "a round that names no subjects retires nothing")
    (is (= [] (record/disputes-for-judge [(first history) {:iter 2 :disputes [] :quiet true}] 3 nil))
        "the round after a quiet one is put none: the quiet round's judge already answered the
         disputes before it, and a quiet round raises none")))

(deftest figures-separate-repairs-from-reversals-and-count-clause-flips
  ;; Watched: a run's flip-rate read 0/23 while R1 held phase-1#2 and R2 ruled it false, and its
  ;; :overturns counted confirmed repairs of restated claims with the judges reversing themselves.
  (let [r  (fn [n & {:as more}] (merge {:format :design-decision :seq n :design-seq 1
                                        :code-identity "t" :recommend :amend} more))
        es [(r 1 :overturns [{:id "a" :seq 9 :ws-id "w" :restated? false}
                             {:id "b" :seq 9 :ws-id "w" :restated? true}
                             {:id "c" :seq 9 :ws-id "w"}]
               :clause-readings {"p#2" {:ruling :held :text "one profile."}
                                 "p#3" {:ruling :false :text "no re-read."}})
            (r 2 :clause-readings {"p#2" {:ruling :false :text "one profile."}
                                   "p#3" {:ruling :false :text "no re-read."}}
               :contradicts-held-clause [{:clause "p#2" :seq 1}])
            (r 3 :clause-readings {"p#2" {:ruling :held :text "two profiles."}})]
        f  (record/run-figures es)]
    (is (= {"a" 1} (:reversals f)) "only a confirmation of the same words is the judges disagreeing")
    (is (= {"b" 1} (:repairs f)) "a confirmation of restated words is a repair confirmed")
    (is (not-any? #(contains? (% f) "c") [:reversals :repairs])
        "a row from before :restated? was carried says neither, and is counted as neither")
    (is (= {"p#2" 1} (:contradicted-clauses f)))
    (is (= {:flips 1 :pairs 2} (:clause-flip-rate f))
        "p#2 held then false flips, p#3 false twice does not, and p#2 over new words pairs with nothing")
    (is (nil? (:clause-flip-rate (record/run-figures (mapv #(assoc % :code-identity (str "t" (:seq %))) es))))
        "rulings at different trees are not the same reading repeated")))

(deftest a-tool-failure-stands-only-on-a-failed-call-in-the-judges-stream
  ;; Watched: a judge marked digest-is-gnu-listing unchecked for a tool failure with no shasum call
  ;; anywhere in its transcript — a refusal it predicted — and the cause took the subject out of
  ;; pairing and away from its amender.
  (let [dir    (fs/create-temp-dir)
        quiet  (str (fs/path dir "quiet.log"))
        failed (str (fs/path dir "failed.log"))
        json   (baseline-json {:verdict "sufficient" :reason "r" :confirmed [] :findings []
                               :unchecked [{:id "digest" :reason "the shell runs only jj and grep"
                                            :cause "tool-failure"}]})
        parse  #(record/parse-baseline-review % 3)]
    (try
      (spit quiet "exec\n/bin/zsh -lc 'rg x' in /t\n succeeded in 0ms:\n")
      (spit failed "exec\n/bin/zsh -lc 'shasum -a 256 f' in /t\n exited 127 in 3ms:\n")
      (is (= [{:id "digest" :reason "the shell runs only jj and grep"}]
             (:unchecked (#'record/judged {:ok json :log-path quiet :judged-by codex} parse)))
          "a failure the stream does not show is the judge's prediction, read as plain unchecked")
      (is (= [{:id "digest" :reason "the shell runs only jj and grep" :tool-failure true}]
             (:unchecked (#'record/judged {:ok json :log-path failed :judged-by codex} parse)))
          "a call the judge made and saw fail is what the cause is for")
      (finally (fs/delete-tree dir)))))

(deftest a-tool-failure-does-not-clear-a-confirmation-read-once
  ;; Watched: claude confirmed pdfbox-render once; codex, whose sandbox refused clj-nrepl-eval, left
  ;; it unchecked for a tool failure, which cleared the second reading it was owed. Only another
  ;; claim's read-once kept the run going to the round where claude refuted it by grep.
  (let [failed {:format :baseline-review
                :unchecked [{:id "pdfbox-render" :reason "clj-nrepl-eval failed" :tool-failure true}]}]
    (is (= ["pdfbox-render"]
           (:read-once (#'record/with-readings failed (constantly true)
                                               {:carried {"pdfbox-render" {:ruling :holds :judged-by {:reviewer :claude}
                                                                           :first-reading true}}
                                                :asked ["pdfbox-render"]})))
        "a carried first reading is still owed its second: the other judge does not share the tools that failed")
    (is (= ["pdfbox-render"]
           (:read-once (#'record/with-readings (assoc failed :code-identity "t" :judged-by codex)
                                               (constantly true)
                                               {:standing {"pdfbox-render" {:seq 8 :ws-id "w" :judged-by {:reviewer :claude}}}
                                                :single ["pdfbox-render"]
                                                :asked ["pdfbox-render"]})))
        "nor is a confirmation standing on the ledger on one reading")))

(deftest an-unchecked-claim-whose-every-clause-held-is-no-ruling
  ;; Watched: codex marked pdfbox-render unchecked for a tool failure while ruling all three of its
  ;; clauses held, with evidence and searches — and the claim-level word was taken.
  (let [owed {"p" [{:key "p#1" :text "It renders." :field :statement :search? false}
                   {:key "p#2" :text "It crops." :field :statement :search? false}]
              "q" [{:key "q#1" :text "It costs." :field :statement :search? false}
                   {:key "q#2" :text "It scales." :field :statement :search? false}]}
        r    (#'record/held-to-clauses
              {:unchecked [{:id "p" :reason "eval failed" :tool-failure true}
                           {:id "q" :reason "needs a measurement"}]
               :clause-rulings {"p#1" {:ruling :held :evidence ["a.clj:1"] :search ""}
                                "p#2" {:ruling :held :evidence ["a.clj:2"] :search ""}
                                "q#1" {:ruling :held :evidence ["a.clj:3"] :search ""}}}
              owed)]
    (is (= ["p"] (:unruled r)) "read true and unreadable at once is neither, and the round is asked again")
    (is (= ["p"] (:unchecked-over-held r)) "and the reader is told why it is unruled")
    (is (= [{:id "q" :reason "needs a measurement"}] (:unchecked r))
        "a claim with a clause it could not hold is an ordinary unchecked")
    (is (report/validate-event :baseline-review (assoc r :format :baseline-review :verdict :sufficient
                                                       :reason "r" :baseline-seq 1))
        "and the ledger takes it")))

(deftest a-reader-that-cannot-execute-is-marked-when-it-withdraws-an-observation
  ;; Watched: codex observed exit 127 by sourcing ci/lib.sh; claude, whose shell runs only jj and
  ;; grep, later refuted the same clause by reasoning from the record, and only an amender's dispute
  ;; restored it.
  (let [held (#'record/held-clauses
              [{:seq 3 :judged-by {:reviewer :codex :executes? true}
                :clause-readings {"swallowed#2" {:ruling :held :text "errors are swallowed."}}}
               {:seq 4 :judged-by {:reviewer :claude :executes? false}
                :clause-readings {"lib#1" {:ruling :held :text "it calls sha256sum."}}}])
        read (fn [judge] (#'record/with-readings
                          {:format :baseline-review :judged-by judge
                           :clause-readings {"swallowed#2" {:ruling :false :text "errors are swallowed."}
                                             "lib#1"       {:ruling :false :text "it calls sha256sum."}}}
                          (constantly false) {:held held}))
        r    (read {:reviewer :claude :executes? false})]
    (is (= [{:clause "lib#1" :seq 4} {:clause "swallowed#2" :seq 3 :by-inference true}]
           (:contradicts-held-clause r))
        "an inference against an observation is marked; one against another inference is not")
    (is (not-any? :by-inference (:contradicts-held-clause (read {:reviewer :codex :executes? true})))
        "a reader that can run the command has observed as much as the one it contradicts")
    (is (report/validate-event :baseline-review (assoc r :verdict :sufficient :reason "r" :baseline-seq 1))
        "and the ledger takes it")))

(deftest a-reader-that-cannot-execute-is-handed-what-an-executing-one-observed
  (let [ctx  {:carry {:quiet {:seq 7 :trace "/runs/r/baseline-review-round-3.log"
                              :read {"lib" {:ruling :holds :judged-by {:reviewer :codex :executes? true}
                                            :at ["ci/lib.sh:12" "PATH=/usr/bin:/bin sourcing exits 127"]}
                                     "tag" {:ruling :holds :judged-by {:reviewer :claude :executes? false}
                                            :at ["ci/tag.sh:3"]}}}}}
        got  (#'record/executed-readings ctx {:seq 7} :claude)
        text (#'record/executed-block got)]
    (is (= {:trace "/runs/r/baseline-review-round-3.log"
            :read {"lib" {:ruling :holds :at ["ci/lib.sh:12" "PATH=/usr/bin:/bin sourcing exits 127"]}}}
           got)
        "only what a judge that could run commands read, with the transcript it ran them in")
    (is (nil? (#'record/executed-readings ctx {:seq 7} :codex))
        "a reader that can run commands observes for itself, and stays cold")
    (is (nil? (#'record/executed-readings ctx {:seq 8} :claude))
        "readings of another record are not this one's")
    (is (str/includes? text "/runs/r/baseline-review-round-3.log") "the transcript is named, to be read")
    (is (str/includes? text "- [lib] holds: ci/lib.sh:12; PATH=/usr/bin:/bin sourcing exits 127"))
    (is (nil? (#'record/executed-block nil)))))

(deftest a-second-reading-carries-what-the-first-was-and-where-it-read
  (let [record {:format :baseline-review :judged-by {:reviewer :codex :executes? true}
                :confirmed ["lib"] :checked-at {"lib" ["ci/lib.sh:12"]} :first-reading ["lib"]}
        ctx    (#'record/second-reading {:iter 3 :config {:run-id "run-1"}} {:seq 7} record)
        q      (get-in ctx [:carry :quiet])]
    (is (= {:ruling :holds :judged-by {:reviewer :codex :executes? true}
            :first-reading true :at ["ci/lib.sh:12"]}
           (get-in q [:read "lib"]))
        "the next round needs to know it was a first reading, and a non-executing reader where it was read")
    (is (str/ends-with? (:trace q) "run-1/baseline-review-round-3.log")
        "the transcript is the round's own log")))
