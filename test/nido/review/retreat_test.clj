;; test/nido/review/retreat_test.clj
(ns nido.review.retreat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.review.retreat :as retreat]))

(defn- whats [rs] (set (map :what rs)))

(def modular-baseline
  {:format :baseline
   :area "order totalling" :bounded-by "b" :shape "s"
   :modules [{:module "calc" :hides "how money is represented" :interface "amounts"}
             {:module "aggregate" :hides "summing order" :interface "a total"}]
   :composition "only the aggregate sees lines, so only it sums them"
   :load-bearing [{:property "amounts are never rounded in place"
                   :falsified-by "a write of a rounded amount"
                   :readings [{:lens :tarpit/state :verdict :essential :because "cannot be recomputed"}]}
                  {:property "the aggregate is the only summing path"
                   :falsified-by "an outside caller that sums lines"
                   :readings [{:lens :parnas/dependency :verdict :on-interface :because "callers take the total"}
                              {:lens :tarpit/control :verdict :required :because "a total cannot precede its lines"}]}
                  {:property "a total is derived, never stored"
                   :falsified-by "a stored total edited independently"
                   :readings [{:lens :tarpit/state :verdict :derived :because "computable by summation"}]}]
   :read ["src/order/aggregate.clj"]})

(def base-baseline
  {:format :baseline
   :load-bearing [{:property "one" :evidence ["src/a.clj:1"]}
                  {:property "two" :evidence ["src/b.clj:2"]}]
   :health [{:id "h1" :axis :design :observation "o1" :evidence ["src/a.clj:9"]
             :invisibly-incomplete? true}
            {:id "h2" :axis :implementation :observation "o2" :evidence ["src/b.clj:9"]}]
   :read ["src/a.clj" "src/b.clj"]})

(deftest an-unchanged-baseline-retreats-nothing
  (is (= [] (retreat/baseline-retreats base-baseline base-baseline))))

(deftest a-dropped-property-is-a-retreat-and-names-the-evidence-nothing-cites
  (let [curr (update base-baseline :load-bearing pop)
        rs   (retreat/baseline-retreats base-baseline curr)]
    (is (= #{:load-bearing-fewer :evidence-dropped} (whats rs)))
    (is (some #(= "src/b.clj:2 is cited by no load-bearing property any more" (:detail %)) rs))))

(deftest rewording-a-property-is-not-a-retreat
  ;; The whole point of comparing evidence rather than prose: a survey that
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
                 (update :load-bearing conj {:property "three" :evidence ["src/c.clj:3"]})
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
  (assoc base-baseline :load-bearing [{:property "p" :evidence (vec refs)}]))

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
    (is (= "src/a/t.clj:729 is cited by no load-bearing property any more"
           (:detail (first rs))))))

(deftest a-shifted-line-is-reported-because-nothing-can-tell-it-from-a-loss
  ;; :386 → :390 is either a corrected anchor or a dropped one, and the record
  ;; carries nothing that distinguishes them. Reporting is the honest answer.
  (let [prev (with-evidence "src/a/progress.clj:386")
        curr (with-evidence "src/a/progress.clj:390")]
    (is (= [:evidence-dropped] (map :what (retreat/baseline-retreats prev curr))))))

(deftest evidence-that-names-no-file-is-not-a-place
  (is (= [] (retreat/baseline-retreats (with-evidence "the schema comment")
                                       (with-evidence "the schema comment, reworded")))))

;; ── Giving up the decomposition ─────────────────────────────────────────────

(deftest dropping-a-module-is-a-retreat-and-names-it
  (let [curr (update modular-baseline :modules pop)
        rs   (retreat/baseline-retreats modular-baseline curr)]
    (is (contains? (whats rs) :module-dropped))
    (is (some #(re-find #"module aggregate" (:detail %)) rs))))

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
  ;; Reading something as accidental that was read as essential is what a survey
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
                     {:module "invoice" :hides "layout" :interface "renders a total"})]
    (is (= [] (retreat/baseline-retreats modular-baseline curr)))))
