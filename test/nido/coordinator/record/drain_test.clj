(ns nido.coordinator.record.drain-test
  "A drain's records are read off its own ledger, appended only through record-drain, and every
   append refuses what its citations do not support."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private p :nido)

(def ^:private a-recipe
  {:steps ["move each call site behind the owning door"]
   :ready-gates ["bb test green" "review loop converged"]
   :standing-orders ["run the test-suite review prompt"]
   :counter {:command "bb countdown"}})

(defn- refused? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= :drain (:refused (ex-data e))))))

(defn- chartered []
  (drain/start-drain! p {:established-by {:ws-id "ws-pattern"} :exit "wrap-conn deleted"}))

(defn- with-recipe-and-census [d]
  (let [prop (drain/propose-recipe! p d {:reason "first cut" :recipe a-recipe})
        v    (drain/approve-recipe! p d prop)
        c    (drain/record-census! p d {:recipe v :remaining 10 :groups [{:group "quiz" :n 4}]})]
    {:proposal prop :version v :census c}))

(deftest a-drain-is-read-from-its-birth-entry
  (with-tmp
    (fn [_]
      (let [d (chartered)
            w (ws/read-ws p d)]
        (is (drain/drain? w))
        (is (not (drain/slice? w)))
        (is (= "wrap-conn deleted" (:exit (drain/charter w))))
        (is (nil? (drain/version w)) "no recipe until the person approves one")))))

(deftest drain-kinds-refused-by-the-generic-append
  (with-tmp
    (fn [_]
      (let [d (chartered)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/append-entry! p d {:kind :census}
                                       (pr-str {:format :census :recipe 2 :remaining 0 :groups [] :by :nido}))))))))

(deftest a-recipe-is-only-an-approved-proposal
  (with-tmp
    (fn [_]
      (let [d (chartered)
            prop (drain/propose-recipe! p d {:reason "first cut" :recipe a-recipe})]
        (is (nil? (drain/version (ws/read-ws p d))) "a proposal alone is no recipe")
        (is (= [prop] (map :seq (drain/pending-proposals (ws/read-ws p d)))))
        (let [v (drain/approve-recipe! p d prop)
              w (ws/read-ws p d)]
          (is (= v (drain/version w)))
          (is (empty? (drain/pending-proposals w)))
          (is (refused? #(drain/approve-recipe! p d prop)) "approved twice"))))))

(deftest a-census-cites-the-current-recipe
  (with-tmp
    (fn [_]
      (let [d (chartered)
            {:keys [version census]} (with-recipe-and-census d)]
        (is (= census (:seq (drain/newest-census (ws/read-ws p d)))))
        (is (refused? #(drain/record-census! p d {:recipe (dec version) :remaining 3 :groups []})))))))

(deftest no-slice-without-recipe-and-census
  (with-tmp
    (fn [_]
      (let [d (chartered)]
        (testing "no recipe"
          (is (= "the drain has no recipe" (drain/startable (ws/read-ws p d) 99))))
        (let [prop (drain/propose-recipe! p d {:reason "r" :recipe a-recipe})
              v    (drain/approve-recipe! p d prop)]
          (testing "a recipe but no census under it"
            (is (= "the drain has no census under its current recipe" (drain/startable (ws/read-ws p d) 99)))
            (let [before (count (ws/list-ids p))]
              (is (refused? #(drain/start-slice! p d 99 :nido)))
              (is (= before (count (ws/list-ids p))) "refused before any workstream is made")))
          (let [c (drain/record-census! p d {:recipe v :remaining 5 :groups []})
                plan (drain/plan-slice! p d {:sites ["quiz"] :rests-on [] :census c})
                s (drain/start-slice! p d plan :nido)
                sw (ws/read-ws p s)
                dw (ws/read-ws p d)]
            (is (drain/slice? sw))
            (is (= d (drain/drain-of sw)))
            (is (= v (drain/slice-version sw)))
            (is (= [s] (map :ws-id (drain/slices dw))) "the drain lists its slice from its own ledger")
            (is (empty? (drain/open-plans dw)) "the start consumed the plan")
            (is (refused? #(drain/start-slice! p d plan :nido)) "a consumed plan starts nothing")))))))

(deftest plans-are-made-from-the-newest-census
  (with-tmp
    (fn [_]
      (let [d (chartered)
            {:keys [version census]} (with-recipe-and-census d)
            newer (drain/record-census! p d {:recipe version :remaining 9 :groups []})]
        (is (refused? #(drain/plan-slice! p d {:sites ["a"] :rests-on [] :census census})))
        (let [a (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census newer})
              b (drain/plan-slice! p d {:sites ["b"] :rests-on [a] :start :on-top :census newer})
              b2 (drain/plan-slice! p d {:sites ["b" "b2"] :rests-on [a] :census newer :replaces b})]
          (is (= {a :open b :replaced b2 :open}
                 (into {} (map (juxt :seq :state)) (drain/plans (ws/read-ws p d)))))
          (drain/withdraw-plan! p d b2 "folded into a")
          (is (= [a] (map :seq (drain/open-plans (ws/read-ws p d)))))
          (is (refused? #(drain/withdraw-plan! p d b2 "again"))))))))

(deftest misfits-clearances-and-residue
  (with-tmp
    (fn [_]
      (let [d (chartered)
            {:keys [census]} (with-recipe-and-census d)
            a (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census census})
            s (drain/start-slice! p d a :nido)
            m (drain/record-misfit! p d {:slice s :site "src/a.clj:12" :note "job shape"})]
        (is (= [m] (map :seq (drain/open-misfits (ws/read-ws p d)))))
        (is (refused? #(drain/record-misfit! p d {:slice "ws-not-a-slice" :site "x" :note "y"})))
        (drain/answer-misfit! p d m {:as :carved :site "src/a.clj:12" :disposition {:to :closing}})
        (let [w (ws/read-ws p d)]
          (is (empty? (drain/open-misfits w)))
          (is (= [["src/a.clj:12" {:to :closing}]] (map (juxt :site :disposition) (drain/residue w)))))
        (drain/clear-slice! p d s [{:site "src/b.clj:3" :disposition {:to :closing}}])
        (let [w (ws/read-ws p d)
              [_ item] (drain/residue w)]
          (is (drain/cleared? w s))
          (is (= 2 (count (drain/residue w))))
          (let [f (drain/plan-slice! p d {:sites ["src/b.clj:3"] :rests-on [] :census census})]
            (drain/dispose-residue! p d (:seq item) {:to :follow-up :plan f})
            (is (= {:to :follow-up :plan f} (:disposition (second (drain/residue (ws/read-ws p d))))))))))))

(deftest the-exit-needs-zero-and-covered-residue
  (with-tmp
    (fn [_]
      (let [d (chartered)
            {:keys [version census]} (with-recipe-and-census d)
            a (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census census})
            s (drain/start-slice! p d a :nido)]
        (drain/clear-slice! p d s [{:site "left" :disposition {:to :closing}}])
        (is (refused? #(drain/observe-exit! p d (constantly true))) "census still reads 10")
        (let [z (drain/record-census! p d {:recipe version :remaining 0 :groups []})]
          (is (refused? #(drain/observe-exit! p d (constantly true))) "residue 'left' is covered by no slice")
          (let [r (drain/plan-slice! p d {:sites ["left"] :rests-on [] :census z})
                rs (drain/start-slice! p d r :nido)]
            (is (refused? #(drain/observe-exit! p d #{s})) "the residue slice has not landed")
            (drain/observe-exit! p d #{s rs})
            (is (some? (drain/exit-observed (ws/read-ws p d))))))))))

(deftest a-refused-verb-leaves-none-of-its-entries
  (with-tmp
    (fn [_]
      (let [d (chartered)
            {:keys [census]} (with-recipe-and-census d)
            a (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census census})
            s (drain/start-slice! p d a :nido)
            m (drain/record-misfit! p d {:slice s :site "src/a.clj:12" :note "job shape"})
            entries #(count (:entries (ws/read-ws p d)))
            before  (entries)]
        (testing "a clearance whose residue cites no plan"
          (is (refused? #(drain/clear-slice! p d s [{:site "x" :disposition {:to :closing}}
                                                    {:site "y" :disposition {:to :follow-up :plan 999999}}])))
          (is (not (drain/cleared? (ws/read-ws p d) s)))
          (is (= before (entries))))
        (testing "a clearance whose residue is malformed"
          (is (thrown? clojure.lang.ExceptionInfo
                       (drain/clear-slice! p d s [{:site "x" :disposition {:to :nowhere}}])))
          (is (not (drain/cleared? (ws/read-ws p d) s)))
          (is (= before (entries))))
        (testing "a carved answer whose disposition cites no plan"
          (is (refused? #(drain/answer-misfit! p d m {:as :carved :site "src/a.clj:12"
                                                      :disposition {:to :follow-up :plan 999999}})))
          (is (= [m] (map :seq (drain/open-misfits (ws/read-ws p d)))))
          (is (= before (entries))))
        (testing "a slice start the drain's entry would refuse makes no workstream"
          (let [b  (drain/plan-slice! p d {:sites ["b"] :rests-on [] :census census})
                n  (count (ws/list-ids p))
                at (entries)]
            (is (thrown? clojure.lang.ExceptionInfo (drain/start-slice! p d b :someone)))
            (is (= n (count (ws/list-ids p))))
            (is (= at (entries)))
            (is (some #(= b (:seq %)) (drain/open-plans (ws/read-ws p d))))))
        (testing "a drain whose birth is malformed makes no workstream"
          (let [n (count (ws/list-ids p))]
            (is (thrown? clojure.lang.ExceptionInfo
                         (drain/start-drain! p {:established-by {:ws-id "ws-pattern"} :exit ""})))
            (is (= n (count (ws/list-ids p))))))))))

(deftest the-slice-that-left-residue-never-covers-it
  (with-tmp
    (fn [_]
      (let [d (chartered)
            {:keys [version census]} (with-recipe-and-census d)
            a (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census census})
            s (drain/start-slice! p d a :nido)]
        (testing "a follow-up is never the leaving slice's own plan"
          (is (refused? #(drain/clear-slice! p d s [{:site "a" :disposition {:to :follow-up :plan a}}]))))
        (drain/clear-slice! p d s [{:site "a" :disposition {:to :closing}}])
        (testing "nor is a re-disposition"
          (let [item (first (drain/residue (ws/read-ws p d)))]
            (is (refused? #(drain/dispose-residue! p d (:seq item) {:to :follow-up :plan a})))))
        (let [z (drain/record-census! p d {:recipe version :remaining 0 :groups []})]
          (is (= ["a"] (map :site (drain/uncovered-residue (ws/read-ws p d) #{s})))
              "the slice that left 'a' has landed, and covers nothing it left")
          (is (refused? #(drain/observe-exit! p d #{s})))
          (let [r  (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census z})
                rs (drain/start-slice! p d r :nido)]
            (is (empty? (drain/uncovered-residue (ws/read-ws p d) #{s rs}))
                "a closing slice planned after the residue covers it once it lands")))))))

(deftest a-follow-up-covers-only-the-site-its-plan-names
  (with-tmp
    (fn [_]
      (let [d (chartered)
            {:keys [version census]} (with-recipe-and-census d)
            a (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census census})
            s (drain/start-slice! p d a :nido)
            b (drain/plan-slice! p d {:sites ["b"] :rests-on [] :census census})]
        (testing "a follow-up citing a plan that does not name the site is refused"
          (is (refused? #(drain/clear-slice! p d s [{:site "a2" :disposition {:to :follow-up :plan b}}]))))
        (drain/clear-slice! p d s [{:site "a2" :disposition {:to :closing}}])
        (let [item (first (drain/residue (ws/read-ws p d)))]
          (testing "and so is a re-disposition to one"
            (is (refused? #(drain/dispose-residue! p d (:seq item) {:to :follow-up :plan b}))))
          (let [bs (drain/start-slice! p d b :nido)]
            (drain/record-census! p d {:recipe version :remaining 0 :groups []})
            (is (= ["a2"] (map :site (drain/uncovered-residue (ws/read-ws p d) #{s bs})))
                "a landed slice planned for 'b' covers nothing at 'a2'")
            (is (refused? #(drain/observe-exit! p d #{s bs})))))))))

(deftest a-closing-residue-a-started-plan-covers-is-not-disposed-again
  (with-tmp
    (fn [_]
      (let [d (chartered)
            {:keys [census]} (with-recipe-and-census d)
            a (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census census})
            s (drain/start-slice! p d a :nido)]
        (drain/clear-slice! p d s [{:site "a2" :disposition {:to :closing}}])
        (let [item (first (drain/residue (ws/read-ws p d)))
              r    (drain/plan-slice! p d {:sites ["a2"] :rests-on [] :census census})
              b    (drain/plan-slice! p d {:sites ["a2"] :rests-on [] :census census})]
          (is (not (refused? #(drain/dispose-residue! p d (:seq item) {:to :closing})))
              "a plan covering it that has not started leaves it open to a new disposition")
          (drain/start-slice! p d r :nido)
          (is (refused? #(drain/dispose-residue! p d (:seq item) {:to :follow-up :plan b}))
              "a closing plan made after it and started covers it, as the closure reads it"))))))
