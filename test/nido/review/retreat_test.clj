;; test/nido/review/retreat_test.clj
(ns nido.review.retreat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.review.retreat :as retreat]))

(defn- whats [rs] (set (map :what rs)))

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
