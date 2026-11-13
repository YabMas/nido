(ns nido.coordinator.lane.pipeline-drain-test
  "A drain and a slice are placed on recipes of their own, never the standard arc: a slice owes no
   intent, baseline or design, and both are classified as origins the overview does not draw."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.view.workstreams :as wsv]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private p :nido)

(def ^:private a-recipe
  {:steps ["s"] :ready-gates ["bb test"] :standing-orders [] :counter {:command "bb countdown"}})

(defn- at [ws-id] (:at (pipeline/of p ws-id)))
(defn- next-stage [ws-id] (get-in (pipeline/of p ws-id) [:next :stage]))

(deftest a-drain-follows-the-drain-recipe
  (with-tmp
    (fn [_]
      (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "x"})]
        (is (= :drain-chartered (at d)))
        (is (= :propose-recipe (next-stage d)))
        (let [prop (drain/propose-recipe! p d {:reason "r" :recipe a-recipe})]
          (is (= :recipe-proposed (at d)))
          (is (= :human (get-in (pipeline/of p d) [:next :mode])) "approving a recipe is the person's")
          (let [v (drain/approve-recipe! p d prop)]
            (is (= :awaiting-census (at d)))
            (drain/record-census! p d {:recipe v :remaining 4 :groups []})
            (is (= :draining (at d)))
            (is (= :coordinate (next-stage d)))
            (testing "a pending proposal outranks running"
              (drain/propose-recipe! p d {:reason "amend" :recipe a-recipe})
              (is (= :recipe-proposed (at d))))))))))

(deftest a-drain-at-zero-is-the-persons-to-close
  (with-tmp
    (fn [_]
      (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "x"})
            v (drain/approve-recipe! p d (drain/propose-recipe! p d {:reason "r" :recipe a-recipe}))]
        (drain/record-census! p d {:recipe v :remaining 0 :groups []})
        (is (= :drain-exhausted (at d)))
        (is (= :close-drain (next-stage d)))))))

(deftest a-slice-owes-no-intent-baseline-or-design
  (with-tmp
    (fn [_]
      (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "x"})
            v (drain/approve-recipe! p d (drain/propose-recipe! p d {:reason "r" :recipe a-recipe}))
            c (drain/record-census! p d {:recipe v :remaining 4 :groups []})
            plan (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census c})
            s (drain/start-slice! p d plan :nido)]
        (is (= :slice-started (at s)))
        (is (= :implement (next-stage s)) "straight to implementation")
        (drain/clear-slice! p d s [])
        (is (= :slice-cleared (at s)))
        (is (= :land (next-stage s)))
        (is (= :human (get-in (pipeline/of p s) [:next :mode])) "landing is the person's while supervised")))))

(deftest a-slice-is-never-reviewed-against-no-yardstick
  (with-tmp
    (fn [_]
      (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "x"})
            v (drain/approve-recipe! p d (drain/propose-recipe! p d {:reason "r" :recipe a-recipe}))
            c (drain/record-census! p d {:recipe v :remaining 4 :groups []})
            s (drain/start-slice! p d (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census c}) :nido)]
        (ws/append-entry! p s {:kind :implementation-completed}
                          (pr-str {:format :implementation-completed :summary "done" :artifacts []}))
        (is (= :slice-implemented (at s)))
        (is (= :review-slice (next-stage s)) "never the design-only diff review")
        (testing "a review judged against no yardstick does not review the slice"
          (ws/append-entry! p s {:kind :review}
                            (pr-str {:format :review-report :status :converged :base "main"
                                     :base-rev "a1b2c3d" :rounds 1 :fix-attempts 0
                                     :defects-settled 0 :findings-remaining 0
                                     :targets-reviewed 1 :targets-skipped 0
                                     :report-path nil :yardstick :none}))
          (is (= :slice-implemented (at s)))
          (is (= :review-slice (next-stage s))))))))

(deftest drains-and-slices-are-their-own-origins
  (with-tmp
    (fn [_]
      (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "x"})
            v (drain/approve-recipe! p d (drain/propose-recipe! p d {:reason "r" :recipe a-recipe}))
            c (drain/record-census! p d {:recipe v :remaining 4 :groups []})
            s (drain/start-slice! p d (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census c}) :nido)]
        (is (= :drain (wsv/ws-source (ws/read-ws p d))))
        (is (= :slice (wsv/ws-source (ws/read-ws p s))))))))

(deftest a-standard-workstream-is-placed-as-before
  (with-tmp
    (fn [_]
      (let [w (ws/create! p {})]
        (is (= :intake (at (:id w))))
        (is (= :establish-intent (next-stage (:id w))))))))
