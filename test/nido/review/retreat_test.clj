;; test/nido/review/retreat_test.clj
(ns nido.review.retreat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.review.retreat :as retreat]))

(defn- whats [rs] (set (map :what rs)))

(def modular-baseline
  {:format :baseline
   :area "order totalling" :bounded-by "b" :shape "s"
   :modules [{:id "mod-calc" :module "calc" :hides "how money is represented" :interface "amounts"}
             {:id "mod-aggregate" :module "aggregate" :hides "summing order" :interface "a total"}]
   :composition "only the aggregate sees lines, so only it sums them"
   :load-bearing [{:id "c1" :property "amounts are never rounded in place"
                   :falsified-by "a write of a rounded amount"
                   :readings [{:lens :tarpit/state :verdict :essential :because "cannot be recomputed"}]}
                  {:id "c2" :property "the aggregate is the only summing path"
                   :falsified-by "an outside caller that sums lines"
                   :readings [{:lens :parnas/dependency :verdict :on-interface :because "callers take the total"}
                              {:lens :tarpit/control :verdict :required :because "a total cannot precede its lines"}]}
                  {:id "c3" :property "a total is derived, never stored"
                   :falsified-by "a stored total edited independently"
                   :readings [{:lens :tarpit/state :verdict :derived :because "computable by summation"}]}]
   :read ["src/order/aggregate.clj"]})

(def base-baseline
  {:format :baseline
   :load-bearing [{:id "c4" :property "one" :evidence ["src/a.clj:1"]}
                  {:id "c5" :property "two" :evidence ["src/b.clj:2"]}]
   :health [{:id "h1" :axis :design :observation "o1" :evidence ["src/a.clj:9"]
             :invisibly-incomplete? true}
            {:id "h2" :axis :implementation :observation "o2" :evidence ["src/b.clj:9"]}]
   :read ["src/a.clj" "src/b.clj"]})

(deftest an-unchanged-baseline-retreats-nothing
  (is (= [] (retreat/baseline-retreats base-baseline base-baseline))))

(def model-baseline
  {:format :baseline :strata [] :area "a" :bounded-by "b" :shape "s"
   :model {:elements [{:id "canvas.x/m" :sort :module :hides "h"}]
           :claims   [{:id "k1" :about ["canvas.x/m"] :statement "one" :falsified-by "f1"
                       :evidence {:by :round} :read-at ["src/a.clj:1"]}
                      {:id "k2" :about ["canvas.x/m"] :statement "two" :falsified-by "f2"
                       :evidence {:by :round} :read-at ["src/b.clj:2"]}]}
   :read ["src/a.clj"]})

(deftest a-model-baseline-is-measured-by-its-claim-ids
  (is (= [] (retreat/baseline-retreats model-baseline model-baseline)))
  (let [rs (retreat/baseline-retreats model-baseline (update-in model-baseline [:model :claims] pop))]
    (is (some #(= "claim k2 is no longer made" (:detail %)) rs) "a dropped claim is named")
    (is (contains? (whats rs) :evidence-dropped) "and the place only it cited is reported"))
  (is (= [] (retreat/baseline-retreats
             model-baseline (assoc-in model-baseline [:model :claims 0 :statement] "one, reworded")))
      "a claim reworded under its id is not a loss"))

(deftest a-model-design-that-drops-a-claim-names-it
  (let [d  {:format :design :strata [] :effort :M :standing {:relation :conforms}
            :baseline {:seq 1 :relation :within}
            :model {:elements [{:id "canvas.x/m" :sort :module}]
                    :claims   [{:id "k1" :about ["canvas.x/m"] :statement "one" :falsified-by "f"
                                :evidence {:by :round}}
                               {:id "k2" :about ["canvas.x/m"] :statement "two" :falsified-by "f"
                                :evidence {:by :round}}]}}
        rs (retreat/design-retreats d (update-in d [:model :claims] pop))]
    (is (= #{:claim-dropped} (whats rs))
        "the dropped id is the whole fall, so the count would report the same loss twice")
    (is (some #(= "claim k2 is no longer made" (:detail %)) rs))))

(deftest a-dropped-property-is-a-retreat-and-names-the-evidence-nothing-cites
  (let [curr (update base-baseline :load-bearing pop)
        rs   (retreat/baseline-retreats base-baseline curr)]
    (is (= #{:evidence-dropped :claim-dropped} (whats rs))
        "named by id, and its orphaned evidence named too — not counted again as a fall")
    (is (some #(= {:claim-id "c5" :places ["src/b.clj:2"]} (select-keys % [:claim-id :places])) rs))))

(deftest a-claim-withdrawn-with-a-reason-is-reported-once-with-that-reason
  ;; The removal was the repair — a claim reworded and refuted again that nothing rested on. Told
  ;; as load-bearing-fewer, claim-dropped and evidence-dropped it reads as three unexplained
  ;; weakenings, which is exactly what sent a person to make the same removal by hand.
  (let [curr (update base-baseline :load-bearing pop)
        rs   (retreat/baseline-retreats base-baseline curr {"c5" "refuted each round; nothing rests on it"})]
    (is (= [{:what :claim-withdrawn
             :detail "claim c5 was removed: refuted each round; nothing rests on it"}]
           rs)
        "one withdrawal carrying its reason, and nothing it took with it reported again")))

(deftest a-reason-withdraws-only-what-the-record-actually-removed
  (testing "a reason for a claim still made changes nothing"
    (is (= [] (retreat/baseline-retreats base-baseline base-baseline {"c5" "gone"}))))
  (testing "a second claim dropped beside a withdrawn one is still a plain drop"
    (let [curr (assoc base-baseline :load-bearing [])
          rs   (retreat/baseline-retreats base-baseline curr {"c5" "gone"})]
      (is (= #{:claim-withdrawn :claim-dropped :evidence-dropped} (whats rs)))
      (is (some #(= "claim c4 is no longer made" (:detail %)) rs)
          "the reason given for one removal does not excuse another")
      (is (some #(= {:claim-id "c4" :places ["src/a.clj:1"]} (select-keys % [:claim-id :places])) rs))
      (is (not-any? #(= "src/b.clj:2 is cited by no load-bearing property any more" (:detail %)) rs)))))

(deftest a-model-claim-can-be-withdrawn
  (let [rs (retreat/baseline-retreats model-baseline (update-in model-baseline [:model :claims] pop)
                                      {"k2" "no derivation reads it"})]
    (is (= #{:claim-withdrawn} (whats rs)))
    (is (= #{"k1" "k2"} (retreat/claim-ids model-baseline)))))

(deftest rewording-a-property-is-not-a-retreat
  ;; The whole point of comparing evidence rather than prose: a baseline that
  ;; corrects how it states a property, while still pointing at the same code,
  ;; has repaired the record — not weakened it.
  (let [curr (assoc-in base-baseline [:load-bearing 0 :property]
                       "one, stated correctly this time")]
    (is (= [] (retreat/baseline-retreats base-baseline curr)))))

(deftest a-dropped-health-observation-is-named
  (let [curr (update base-baseline :health (comp vec rest))
        rs   (retreat/baseline-retreats base-baseline curr)]
    (is (contains? (whats rs) :health-dropped))
    (is (some #(re-find #"h1" (:detail %)) rs))))

(deftest clearing-the-spin-out-veto-is-its-own-retreat
  ;; The flag is the only thing standing between an observation and a deferral,
  ;; so losing it silently is the highest-value edit an amender could make.
  (let [curr (assoc-in base-baseline [:health 0 :invisibly-incomplete?] false)
        rs   (retreat/baseline-retreats base-baseline curr)]
    (is (= #{:veto-lifted} (whats rs))))
  (testing "dropping the key entirely counts the same as setting it false"
    (let [curr (update-in base-baseline [:health 0] dissoc :invisibly-incomplete?)]
      (is (= #{:veto-lifted} (whats (retreat/baseline-retreats base-baseline curr)))))))

(deftest adding-to-a-baseline-is-never-a-retreat
  (let [curr (-> base-baseline
                 (update :load-bearing conj {:id "c6" :property "three" :evidence ["src/c.clj:3"]})
                 (update :health conj {:id "h3" :axis :design :observation "o3"
                                       :evidence ["src/c.clj:9"]}))]
    (is (= [] (retreat/baseline-retreats base-baseline curr)))))

(def base-design
  {:format :design
   :effort :L
   :standing {:relation :challenges :note "n"}
   :baseline {:seq 1 :relation :revisit :breaks ["p"] :note "n"}
   :invariants [{:invariant "i1" :holds :always}
                {:invariant "i2" :holds :always}]
   :rejected [{:alternative "a" :why-not "w"}]
   :phases [{:claim "p1"} {:claim "p2"}]
   :routes [{:health-id "h1" :to :fix-here}
            {:health-id "h2" :to :spin-out :why "w" :ref "FU-1"}]})

(deftest an-unchanged-design-retreats-nothing
  (is (= [] (retreat/design-retreats base-design base-design))))

(deftest softening-any-of-the-three-ordinals-is-a-retreat
  (is (= #{:effort-lowered}
         (whats (retreat/design-retreats base-design (assoc base-design :effort :M)))))
  (is (= #{:baseline-relation-softened}
         (whats (retreat/design-retreats
                 base-design (assoc base-design :baseline {:seq 1 :relation :within})))))
  (is (= #{:standing-softened}
         (whats (retreat/design-retreats
                 base-design (assoc base-design :standing {:relation :conforms}))))))

(deftest raising-an-ordinal-is-not-a-retreat
  (is (= [] (retreat/design-retreats (assoc base-design :effort :M) base-design))))

(deftest an-unknown-relation-is-not-reported-as-a-retreat
  ;; A vocabulary this namespace does not know is not evidence of anything, and
  ;; guessing would put a false alarm in front of a human on every schema change.
  (is (= [] (retreat/design-retreats
             base-design (assoc base-design :standing {:relation :something-new})))))

(deftest dropping-the-phase-plan-is-a-retreat
  (is (contains? (whats (retreat/design-retreats base-design (dissoc base-design :phases)))
                 :phases-dropped)))

(deftest deferring-work-you-said-you-would-do-is-a-retreat
  (let [curr (assoc base-design :routes [{:health-id "h1" :to :declined :why "w"}
                                         {:health-id "h2" :to :spin-out :why "w" :ref "FU-1"}])]
    (is (= #{:route-deferred} (whats (retreat/design-retreats base-design curr))))))

(deftest promising-more-work-is-not-a-retreat
  ;; :fix-here is the conservative destination. Moving TO it quiets
  ;; design-round-worth-running?, which is the caller's problem to catch — but
  ;; calling it a retreat would misstate which way the doctrine points.
  (let [curr (assoc base-design :routes [{:health-id "h1" :to :fix-here}
                                         {:health-id "h2" :to :fix-here}])]
    (is (= [] (retreat/design-retreats base-design curr)))))

(deftest summary-renders-nothing-for-nothing
  (is (nil? (retreat/summary [])))
  (is (re-find #"! effort-lowered — :L → :M"
               (retreat/summary (retreat/design-retreats
                                 base-design (assoc base-design :effort :M))))))

;; ── Evidence is compared by place, not by text ──────────────────────────────

(defn- with-evidence [& refs]
  (assoc base-baseline :load-bearing [{:id "c7" :property "p" :evidence (vec refs)}]))

(deftest annotating-a-citation-is-not-losing-it
  ;; Seen live: one round enriched eight references and the detector called every
  ;; one a weakening. A human reading eight non-events is a human who misses the
  ;; ninth.
  (let [prev (with-evidence "src/a/time_series.clj:669")
        curr (with-evidence "src/a/time_series.clj:669 (period_dialogue_time groups on dcs.created_at)")]
    (is (= [] (retreat/baseline-retreats prev curr)))))

(deftest a-label-in-front-of-a-citation-is-not-losing-it
  (let [prev (with-evidence "src/a/learner.clj:25")
        curr (with-evidence "denominator: src/a/learner.clj:25 progress-denominator, which delegates")]
    (is (= [] (retreat/baseline-retreats prev curr)))))

(deftest widening-a-line-into-the-range-around-it-is-not-losing-it
  (let [prev (with-evidence "src/a/discussion.clj:202")
        curr (with-evidence "src/a/discussion.clj:198-205")]
    (is (= [] (retreat/baseline-retreats prev curr)))))

(deftest a-bare-line-inside-an-annotation-still-counts-as-a-citation
  ;; `foo.clj:732 ... joined to :746 at :760-765` points at three places in
  ;; foo.clj, and reading only the first calls the other two lost.
  (let [prev (with-evidence "src/a/t.clj:746" "src/a/t.clj:762")
        curr (with-evidence "src/a/t.clj:732 (ladder inlined) joined to :746 at :760-765")]
    (is (= [] (retreat/baseline-retreats prev curr)))))

(deftest a-place-nothing-points-at-any-more-is-still-reported
  (let [prev (with-evidence "src/a/t.clj:729" "src/a/t.clj:800")
        curr (with-evidence "src/a/t.clj:800 (still here)")
        rs   (retreat/baseline-retreats prev curr)]
    (is (= [:evidence-dropped] (map :what rs)))
    (is (= "claim c7 no longer cites src/a/t.clj:729, and no finding at it this round asked it to"
           (:detail (first rs))))))

(deftest a-citation-re-pointed-within-its-file-is-not-a-loss
  ;; Seen live: a cite moved seven lines onto the judge's own site while the claim grew from three
  ;; cites to five, and it was the run's only counted weakening. A claim still citing every file it
  ;; cited, as many times, has claimed nothing less.
  (let [prev (with-evidence "src/a/progress.clj:386")
        curr (with-evidence "src/a/progress.clj:390")]
    (is (= [] (retreat/baseline-retreats prev curr))))
  (let [prev (with-evidence "src/c.clj:410" "src/c.clj:417" "src/c.clj:430")
        curr (with-evidence "src/c.clj:410" "src/c.clj:424" "src/c.clj:430" "src/c.clj:440" "src/c.clj:450")]
    (is (= [] (retreat/baseline-retreats prev curr)))))

(deftest fewer-cites-is-a-loss-even-when-the-rest-moved
  (let [prev (with-evidence "src/a/t.clj:386" "src/a/t.clj:500")
        curr (with-evidence "src/a/t.clj:390")
        rs   (retreat/baseline-retreats prev curr)]
    (is (= ["claim c7 no longer cites src/a/t.clj:386, and no finding at it this round asked it to"
            "claim c7 no longer cites src/a/t.clj:500, and no finding at it this round asked it to"]
           (map :detail rs))
        "nothing tells which of the two the new line replaced, so both are named")))

(deftest a-file-the-claim-stops-citing-is-a-loss-whatever-the-count
  (let [prev (with-evidence "src/a.clj:10" "src/b.clj:20")
        curr (with-evidence "src/a.clj:10" "src/a.clj:15")]
    (is (= ["claim c7 no longer cites src/b.clj:20, and no finding at it this round asked it to"]
           (map :detail (retreat/baseline-retreats prev curr))))))

(deftest a-cite-moved-onto-the-judges-site-answers-for-the-one-it-replaced
  ;; The count fell, so something was given up — but the move onto the site the judge named is the
  ;; repair the finding asked for, and only the place nothing replaced is a loss.
  (let [prev   (with-evidence "reporting.clj:166" "reporting.clj:216" "reporting.clj:300")
        curr   (with-evidence "reporting.clj:159" "reporting.clj:216")
        found  [{:claim-id "c7" :evidence ["reporting.clj:159 (the post happens before prep)"]}]]
    (is (= [["reporting.clj:300"]]
           (map :places (retreat/baseline-retreats prev curr nil found))))
    (is (= [["reporting.clj:166"] ["reporting.clj:300"]]
           (map :places (retreat/baseline-retreats prev curr nil [{:claim-id "other"
                                                                    :evidence ["reporting.clj:159"]}])))
        "the judge's evidence against a different claim excuses nothing here")))

;; ── Which claim gave a citation up, and whether anyone asked it to ──────────

(deftest one-citation-naming-three-places-is-one-retreat
  ;; Seen live: one grep line on one claim named three call sites, and the headline counted three
  ;; weakenings with no claim id between them — one decision the amender made, reported as three
  ;; it did not.
  (let [prev (with-evidence "src/a.clj:1" "content/ingest.clj:105, :161 and :178 call the blob API")
        curr (with-evidence "src/a.clj:1")
        rs   (retreat/baseline-retreats prev curr)]
    (is (= [:evidence-dropped] (map :what rs)))
    (is (= "c7" (:claim-id (first rs))) "the claim that gave it up is named")
    (is (= ["content/ingest.clj:105" "content/ingest.clj:161" "content/ingest.clj:178"]
           (:places (first rs))))))

(deftest a-narrowing-the-judge-asked-for-says-so
  ;; A requested narrowing and an over-narrowing read the same unless the retreat carries the
  ;; request; a reader then has to re-derive from the run which of the two each one was.
  (let [prev  (with-evidence "src/a.clj:1" "src/out.clj:40")
        curr  (with-evidence "src/a.clj:1")
        asked [{:claim-id "c7" :evidence ["src/a.clj:1"]
                :needs "drop the outside-caller clause; it is a client conformance claim"}]
        [r]   (retreat/baseline-retreats prev curr nil asked)]
    (is (= ["drop the outside-caller clause; it is a client conformance claim"] (:answering r)))
    (is (re-find #"narrowed at its finding's request" (:detail r)))
    (is (not (:relocation-dropped? r)) "it was asked to go, not to move")
    (is (nil? (:answering (first (retreat/baseline-retreats prev curr nil [{:claim-id "c4"
                                                                            :needs "elsewhere"}]))))
        "a finding at another claim requested nothing of this one")))

(deftest a-drop-at-a-claim-a-finding-objected-to-is-not-called-unasked
  ;; Seen live: every drop at a refuted claim read "no finding at it this round asked it to", and
  ;; the headline's weakenings were narrowings the judge had asked for.
  (let [prev (with-evidence "src/a.clj:1" "src/out.clj:40")
        curr (with-evidence "src/a.clj:1")
        [r]  (retreat/baseline-retreats prev curr nil [{:claim-id "c7" :evidence ["src/a.clj:1"]}])]
    (is (= "claim c7 no longer cites src/out.clj:40, and a finding at it this round objected to the claim"
           (:detail r)))
    (is (nil? (:answering r)) "it answers an objection, not a stated request")))

(deftest evidence-the-judge-asked-to-move-and-the-amender-deleted-is-flagged
  ;; Seen live: the finding asked for three surfaces to be restated as bounded evidence or health,
  ;; and the amended record mentioned none of them anywhere — dropped, not relocated.
  (let [prev  (with-evidence "src/a.clj:1" "src/sse_adapter.clj:293")
        asked [{:claim-id "c7" :needs "restate the surfaces as bounded evidence or health"}]
        moved (update (with-evidence "src/a.clj:1") :health conj
                      {:id "h9" :axis :design :observation "o" :evidence ["src/sse_adapter.clj:293"]})]
    (is (:relocation-dropped? (first (retreat/baseline-retreats
                                      prev (with-evidence "src/a.clj:1") nil asked))))
    (let [[r] (filter (comp #{:evidence-dropped} :what)
                      (retreat/baseline-retreats prev moved nil asked))]
      (is (not (:relocation-dropped? r)) "a citation the record still makes in its health was moved")
      (is (re-find #"still cites it outside the load-bearing claims" (:detail r))))))

(deftest evidence-that-names-no-file-is-not-a-place
  (is (= [] (retreat/baseline-retreats (with-evidence "the schema comment")
                                       (with-evidence "the schema comment, reworded")))))

;; ── Giving up the decomposition ─────────────────────────────────────────────

(deftest dropping-a-module-is-a-retreat-and-names-it
  (let [curr (update modular-baseline :modules pop)
        rs   (retreat/baseline-retreats modular-baseline curr)]
    (is (contains? (whats rs) :module-dropped))
    (is (some #(re-find #"mod-aggregate" (:detail %)) rs)
        "named by id, which a rename cannot move")))

(deftest renaming-a-module-while-the-decomposition-grows-is-not-a-loss
  ;; Watched live: "codex — the judge launch" became "codex — the read-only judge
  ;; launch" while two modules were ADDED, and comparing the name strings called
  ;; that a module dropped. A module's identity is its own description, which the
  ;; amender rewrites like everything else; the count is what survives a
  ;; rewording.
  (let [curr (-> modular-baseline
                 (assoc-in [:modules 0 :module] "calc — the money representation")
                 (update :modules conj
                         {:id "mod-invoice" :module "invoice" :hides "layout" :interface "renders a total"}))]
    (is (= [] (retreat/baseline-retreats modular-baseline curr)))))

(deftest a-rename-that-hides-a-real-drop-is-still-caught-by-id
  ;; Seen live: one dropped module reported as module-dropped AND modules-fewer 13 → 12, one
  ;; decision counted as two weakenings.
  (let [curr (-> modular-baseline
                 (update :modules pop)
                 (assoc-in [:modules 0 :module] "calc — reworded"))
        rs   (retreat/baseline-retreats modular-baseline curr)]
    (is (= #{:module-dropped} (whats rs))
        "the id names the drop whatever the rename did, and the count adds nothing to it")))

(deftest dropping-a-reading-is-dropping-analysis
  ;; A reading is where the analysis lives, so losing one loses analysis whatever
  ;; the prose still says. No id on a claim would track a reading through a
  ;; rewrite; the count and the set of perspectives survive one.
  (let [curr (update-in modular-baseline [:load-bearing 1] dissoc :readings)
        rs   (retreat/baseline-retreats modular-baseline curr)]
    (is (contains? (whats rs) :readings-fewer))))

(deftest abandoning-a-perspective-entirely-is-named
  (let [curr (update modular-baseline :load-bearing
                     (fn [lb] (mapv #(update % :readings
                                             (fn [rs] (vec (remove (comp #{:tarpit/control} :lens) rs))))
                                    lb)))
        rs   (retreat/baseline-retreats modular-baseline curr)]
    (is (contains? (whats rs) :lens-abandoned))
    (is (some #(re-find #"tarpit/control" (:detail %)) rs))))

(deftest changing-a-verdict-is-a-re-judgement-not-a-retreat
  ;; Reading something as accidental that was read as essential is what a baseline
  ;; SHOULD do when it finds the derivation. The reading is still there.
  (let [curr (assoc-in modular-baseline [:load-bearing 0 :readings 0 :verdict] :accidental)]
    (is (= [] (retreat/baseline-retreats modular-baseline curr)))))

(deftest rewording-every-claim-while-keeping-the-readings-is-not-a-retreat
  ;; What survives an amender rewriting every word is the count and the set of
  ;; perspectives, which is why those are what is counted.
  (let [curr (update modular-baseline :load-bearing
                     (fn [lb] (mapv #(assoc % :property (str (:property %) ", restated")
                                            :falsified-by (str (:falsified-by %) ", restated"))
                                    lb)))]
    (is (= [] (retreat/baseline-retreats modular-baseline curr)))))

(deftest adding-a-reading-is-not-a-retreat
  (let [curr (update-in modular-baseline [:load-bearing 0 :readings] conj
                        {:lens :parnas/dependency :verdict :on-interface :because "nothing reaches past calc"})]
    (is (= [] (retreat/baseline-retreats modular-baseline curr)))))

(deftest adding-a-module-is-not-a-retreat
  (let [curr (update modular-baseline :modules conj
                     {:id "mod-invoice" :module "invoice" :hides "layout" :interface "renders a total"})]
    (is (= [] (retreat/baseline-retreats modular-baseline curr)))))

(deftest a-claim-rewritten-beyond-recognition-is-still-the-same-claim
  ;; The whole reason ids exist. Before them, an amendment rewrote property text
  ;; AND evidence, so a claim that had been corrected and was still wrong looked
  ;; like a claim nobody had ever seen.
  (let [curr (update modular-baseline :load-bearing
                     (fn [lb] (mapv #(assoc % :property "completely different words"
                                            :falsified-by "and a different counterexample"
                                            :evidence ["src/somewhere/else.clj:1"])
                                    lb)))]
    (is (empty? (filter (comp #{:claim-dropped} :what)
                        (retreat/baseline-retreats modular-baseline curr)))
        "same ids, so no claim was dropped however much the wording moved")))

;; ── Prose that stops saying anything ────────────────────────────────────────

(def ^:private full-baseline
  {:format :baseline :area "orders" :bounded-by "money on an order"
   :shape "one rounding boundary" :composition "only the aggregate sees the lines"
   :read ["src/a.clj"]
   :modules [{:id "m1" :module "the aggregate" :hides "the summing order"
              :interface "an order's total"}]
   :load-bearing [{:id "c1" :property "the aggregate is the only summing path"
                   :falsified-by "a second path that sums lines"
                   :evidence ["src/a.clj:1"]}]})

(deftest a-field-that-stops-saying-anything-is-a-retreat
  ;; Every other detector counts something. Prose has no cardinality, the schema
  ;; types these `string?` so "" validates, and the ledger accepts it — so
  ;; blanking a field claimed less in the one way nothing measured, and the next
  ;; round had nothing left to refute.
  (let [curr (-> full-baseline
                 (assoc :composition "")
                 (assoc-in [:modules 0 :hides] "")
                 (assoc-in [:load-bearing 0 :falsified-by] ""))
        details (map :detail (filter #(= :emptied (:what %))
                                     (retreat/baseline-retreats full-baseline curr)))]
    (is (= ["the baseline's composition now says nothing"
            "module m1's hides now says nothing"
            "claim c1's falsified-by now says nothing"]
           details))))

(deftest shortening-prose-is-not-a-retreat
  ;; An amender tightening a claim is doing the job. Only blank-from-non-blank
  ;; is unambiguous; a length threshold would report the good edit too.
  (let [curr (assoc full-baseline :composition "only the aggregate sums")]
    (is (empty? (filter #(= :emptied (:what %))
                        (retreat/baseline-retreats full-baseline curr))))))

(deftest a-dropped-module-is-not-also-reported-as-emptied
  ;; It is already named as dropped; saying it twice reads as two losses.
  (let [curr (assoc full-baseline :modules [])
        whats (set (map :what (retreat/baseline-retreats full-baseline curr)))]
    (is (contains? whats :module-dropped))
    (is (not (contains? whats :emptied)))))

(deftest a-design-that-stops-saying-anything-is-a-retreat-too
  ;; Same hole, same fields typed `string?`. It does not even quiet the round —
  ;; the worth-running gate reads the relations — so it is pure loss.
  (let [prev {:format :design :summary "rounding moves to one point"
              :shape "one rounding boundary" :effort :M
              :standing {:relation :extends :note "n"}
              :baseline {:seq 1 :relation :within :note "m"}}
        curr (-> prev (assoc :shape "") (assoc-in [:baseline :note] ""))
        details (map :detail (filter #(= :emptied (:what %))
                                     (retreat/design-retreats prev curr)))]
    (is (= ["the design's shape now says nothing"
            "the design's baseline relation note now says nothing"]
           details))
    (is (empty? (filter #(= :emptied (:what %))
                        (retreat/design-retreats prev prev))))))

;; ── A multi-file citation is several files ──────────────────────────────────

(deftest every-file-in-a-citation-anchors-its-own-lines
  ;; Seen live: `google.clj:2534, login_code.clj:219,664,786, …` reported as google.clj:219,
  ;; google.clj:616 … — places nobody cited — while the ones really dropped went unreported.
  (let [cite "google.clj:2534, login_code.clj:219,664,786, email.clj:616,638, result_boundary.clj:163"
        prev (with-evidence "src/a.clj:1" cite)
        curr (with-evidence "src/a.clj:1")
        [r]  (retreat/baseline-retreats prev curr)]
    (is (= ["google.clj:2534" "login_code.clj:219" "login_code.clj:664" "login_code.clj:786"
            "email.clj:616" "email.clj:638" "result_boundary.clj:163"]
           (:places r))
        "each bare line belongs to the nearest file before it")))

(deftest a-citation-widened-across-files-is-not-a-drop
  ;; Seen live: `(content/ingest.clj:105, 178-179)` widened to cite execute.clj as well was read as
  ;; one file, execute.clj, and both ingest.clj places reported dropped.
  (let [prev (with-evidence "(content/ingest.clj:105, 178-179)" "content/ingest.clj:161")
        curr (with-evidence "the job (execute.clj:1426-1427), and content/ingest.clj (content/ingest.clj:103-113, 160-226)")]
    (is (= [] (retreat/baseline-retreats prev curr))
        "every place the old citation named is inside a range the new one names")))

(deftest a-judge-citing-the-full-path-re-points-the-short-one
  (let [prev  (with-evidence "content/ingest.clj:166" "content/ingest.clj:300")
        curr  (with-evidence "content/ingest.clj:159")
        found [{:claim-id "c7" :evidence ["src/main/brian/content/ingest.clj:159"]}]]
    (is (= [["content/ingest.clj:300"]]
           (map :places (retreat/baseline-retreats prev curr nil found)))
        "the judge's path and the record's name one file, so the move answers for :166")))

;; ── Narrowing in place, by id ────────────────────────────────────────────────

(def ^:private model-design
  {:format :design :strata [] :effort :M :standing {:relation :conforms}
   :baseline {:seq 1 :relation :extends}
   :shape "Every passage is read whole. Page breaks are marked inline."
   :model {:elements [{:id "canvas.x/store" :sort :module :hides "how rows are keyed and stored"}]
           :claims   [{:id "one-analysis" :about ["canvas.x/store"] :evidence {:by :round}
                       :statement "at most one analysis of a blob runs at a time"
                       :falsified-by "two analyses of one blob in flight at once"}
                      {:id "no-furniture" :about ["canvas.x/store"] :evidence {:by :round}
                       :statement "no page furniture reaches a passage"
                       :falsified-by "furniture text in a block or in a passage"}]}})

(defn- narrowed [rs] (filter (comp #{:narrowed} :what) rs))

(deftest an-exception-added-to-a-kept-claim-is-a-narrowing
  ;; Seen live, run after run: a claim kept its id while its :falsified-by gained 'other than after
  ;; the first job's lease lapsed', and the headline said 0 weakenings.
  (let [curr (assoc-in model-design [:model :claims 0 :falsified-by]
                       "two analyses of one blob in flight at once other than after the first job's lease lapsed")
        [r]  (narrowed (retreat/design-retreats model-design curr))]
    (is (= "one-analysis" (:id r)))
    (is (= [:exception-added] (:signals r)))
    (is (re-find #"claim one-analysis's falsified-by gained an exception" (:detail r)))
    (is (= [r] (retreat/unasked [r])) "nothing asked for it, so it is an unasked weakening")))

(deftest a-lost-disjunct-is-a-narrowing
  (let [curr (assoc-in model-design [:model :claims 1 :falsified-by] "furniture text in a passage")]
    (is (= [[:disjunct-lost]] (map :signals (narrowed (retreat/design-retreats model-design curr)))))))

(deftest a-narrowing-the-round-asked-for-is-linked-to-its-finding
  ;; The narrowing is still reported — the record claims less — but a reader can see the judge
  ;; asked for it, and the unasked count, which is the one to act on, leaves it out.
  (let [curr     (assoc-in model-design [:model :claims 1 :falsified-by]
                           "furniture text outside table and figure spans")
        findings [{:claim-ids ["no-furniture"] :check :goal-served :cites ["x"]
                   :claim "the guarantee is too broad: tables legitimately carry headers"}]
        [r]      (narrowed (retreat/design-retreats model-design curr findings))]
    (is (:ordered r))
    (is (= ["the guarantee is too broad: tables legitimately carry headers"] (:finding r)))
    (is (= [] (retreat/unasked [r])))
    (is (re-find #"\[asked for\]$" (retreat/line r)))))

(deftest a-rewording-that-claims-no-less-is-not-a-narrowing
  (let [curr (assoc-in model-design [:model :claims 0 :statement]
                       "no two analyses of one blob ever run at the same time")]
    (is (= [] (retreat/design-retreats model-design curr)))))

(deftest a-hides-that-concedes-a-decision-is-a-narrowing
  (let [curr (assoc-in model-design [:model :elements 0 :hides]
                       "how rows are keyed; the storage is not hidden here")]
    (is (= [["element" :hides]]
           (map (juxt #(re-find #"^element" (:detail %)) :field)
                (narrowed (retreat/design-retreats model-design curr)))))))

(deftest a-shape-commitment-moved-into-open-is-relocated-not-lost
  ;; Seen live: a sentence dropped from a record was restated as something it does not know.
  ;; Declared, not dropped — and indistinguishable from deleted unless the detector looks.
  (let [moved   (-> model-design
                    (assoc :shape "Every passage is read whole.")
                    (assoc :open ["whether page breaks are marked inline in every passage"]))
        deleted (assoc model-design :shape "Every passage is read whole.")
        rm      (first (narrowed (retreat/design-retreats model-design moved)))
        rd      (first (narrowed (retreat/design-retreats model-design deleted)))]
    (is (= [:clause-lost] (:signals rm) (:signals rd)))
    (is (:relocated rm) "the clause reappears in :open")
    (is (not (:relocated rd)) "the clause is nowhere")
    (is (= [rd] (retreat/unasked [rm rd])))))

(deftest a-baseline-claim-narrowed-in-place-is-reported
  ;; Seen live: a refuted claim's :falsified-by dropped 'reads' from what it forbade, and the
  ;; headline said 0 weakenings.
  (let [curr (assoc-in model-baseline [:model :claims 0 :falsified-by] "f1 except where locked")
        rs   (retreat/baseline-retreats model-baseline curr nil [{:claim-id "k1" :claim "k1 is too broad"
                                                                   :needs "say which writes"}])
        [r]  (narrowed rs)]
    (is (= "k1" (:id r)))
    (is (:ordered r))
    (is (= ["say which writes"] (:answering r)))))

;; ── What the round asked for ─────────────────────────────────────────────────

(deftest a-relation-the-broken-check-corrected-is-ordered
  ;; Seen live: relation-honest broke and said the relation needed correcting; the amender did, and
  ;; the headline counted it as the run's one weakening.
  (let [prev (assoc-in base-design [:baseline :relation] :extends)
        curr (assoc-in base-design [:baseline :relation] :within)
        rs   (retreat/design-retreats prev curr [{:check :relation-honest :cites ["x"]
                                                  :claim "the relation label needs correction"}])]
    (is (= [:baseline-relation-softened] (map :what rs)))
    (is (= [:relation-honest] (:checks (first rs))))
    (is (= [] (retreat/unasked rs)))
    (testing "a broken check that judges something else orders nothing"
      (is (= 1 (count (retreat/unasked (retreat/design-retreats prev curr [{:check :routing-coherent
                                                                             :cites ["x"] :claim "c"}]))))))))

(deftest a-deferred-route-carries-its-reason-and-ref
  (let [curr (assoc base-design :routes [{:health-id "h1" :to :spin-out :why "a different story" :ref "FU-353"}
                                         {:health-id "h2" :to :spin-out :why "w" :ref "FU-1"}])
        [r]  (retreat/design-retreats base-design curr)]
    (is (= "FU-353" (:ref r)))
    (is (re-find #"FU-353.*a different story" (:detail r)))))

(deftest a-health-observation-the-judge-refuted-is-dropped-at-its-request
  (let [curr (update base-baseline :health (comp vec rest))
        [r]  (retreat/baseline-retreats base-baseline curr {"h1" "the function is absent"}
                                        [{:claim-id "h1" :claim "h1 is false"}])]
    (is (= :health-dropped (:what r)))
    (is (re-find #"the function is absent" (:detail r)) "the amender's reason travels with it")
    (is (:ordered r))))

(deftest a-citation-still-made-elsewhere-is-relocated
  (let [prev  (with-evidence "src/a.clj:1" "src/sse_adapter.clj:293")
        moved (update (with-evidence "src/a.clj:1") :health conj
                      {:id "h9" :axis :design :observation "o" :evidence ["src/sse_adapter.clj:293"]})
        rs    (filter (comp #{:evidence-dropped} :what) (retreat/baseline-retreats prev moved))]
    (is (every? :relocated rs))
    (is (= [] (retreat/unasked rs)))))

(deftest a-drop-at-a-refuted-claim-is-ordered
  (let [prev (with-evidence "src/a.clj:1" "src/out.clj:40")
        curr (with-evidence "src/a.clj:1")
        rs   (retreat/baseline-retreats prev curr nil [{:claim-id "c7" :claim "the writer list is wrong"}])]
    (is (:ordered (first rs)))
    (is (= ["the writer list is wrong"] (:finding (first rs))))
    (is (= [] (retreat/unasked rs)))))

;; ── Growth, by id ────────────────────────────────────────────────────────────

(deftest a-claim-that-grew-past-checking-is-named
  ;; Seen live: a claim's statement went from 171 characters to 959 across four rounds while the
  ;; record-wide fields stayed put, and nothing reported growth.
  (let [long (apply str (repeat 60 "and also this "))
        curr (assoc-in model-design [:model :claims 0 :statement] long)
        g    (retreat/growth model-design curr)]
    (is (= [{:field :statement :id "one-analysis" :from 45 :to (count long)}] g))
    (is (re-find #"one-analysis's statement" (retreat/growth-summary g)))))
