(ns nido.coordinator.lane.pipeline-generation-test
  "A workstream's position across a re-cut: what the generation before left owed places nothing in
   the new one."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.pipeline :as p]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]))

(def ^:private a-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order" :shape "one summing path"
   :model {:elements [{:id "agg" :sort :module :hides "the summing order" :interface "an order's total"}]
           :claims [{:id "c1" :about ["agg"] :statement "the aggregate is the only summing path"
                     :falsified-by "a second path that sums lines" :evidence {:by :round}
                     :read-at ["src/a.clj:1"]}]}
   :read ["src/a.clj"]})

(def ^:private a-design
  {:format :design :strata [] :summary "s" :shape "sh"
   :model {:elements [{:id "agg" :sort :module :defines-generation true}]
           :claims [{:id "one-summing-path" :about ["agg"] :statement "one summing path"
                     :falsified-by "a second path that sums lines" :evidence {:by :round}}]}
   :standing {:relation :conforms}
   :baseline {:seq 2 :relation :within}
   :intent {:seq 1} :effort :S})

(deftest a-halt-the-generation-before-left-does-not-hold-the-new-one
  ;; An answer may not cite across the boundary, so a blocker carried forward would hold the new
  ;; generation at :blocked with no answer the ledger would take.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [id   (:id (ws/create! :brian {:external-refs []}))
              add! (fn [kind record] (ws/append-entry! :brian id {:kind kind} (pr-str record))
                     (count (:entries (ws/read-ws :brian id))))]
          (add! :intent {:format :intent :goal "g" :done-when ["d"]})
          (add! :baseline a-baseline)
          (add! :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq 2 :reason "holds"})
          (let [d1 (add! :design a-design)]
            (add! :design-approved {:format :design-approved :design {:seq d1} :at-seq d1})
            (add! :blocker {:format :blocker :summary "which way" :needs "a decision"})
            (is (= :blocked (:at (p/of :brian id))))
            (ws/append-stamped! :brian id {:format :recut :by {:as :person} :ids ["agg"] :why "two secrets"})
            (ws/open-generation! :brian id {:format :generation :recut {:seq (count (:entries (ws/read-ws :brian id)))}
                                            :words "split it" :ids ["agg"] :base-rev "abc"})
            (let [r (p/of :brian id)]
              (is (= :baseline-verified (:at r)))
              (is (= :design (:stage (:next r))))))))
      (finally (fs/delete-tree tmp)))))

(deftest an-amended-goal-owes-nothing-over-the-generation-befores-design
  ;; The amendment reading of a goal is read over the current span, as the fold places the arc:
  ;; a design the re-cut replaced is no record the new generation owes a stage over.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [id   (:id (ws/create! :brian {:external-refs []}))
              add! (fn [kind record] (ws/append-entry! :brian id {:kind kind} (pr-str record))
                     (count (:entries (ws/read-ws :brian id))))]
          (add! :intent {:format :intent :goal "g" :done-when ["d"]})
          (add! :baseline a-baseline)
          (add! :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq 2 :reason "holds"})
          (let [d1 (add! :design a-design)]
            (add! :design-approved {:format :design-approved :design {:seq d1} :at-seq d1})
            (ws/append-stamped! :brian id {:format :recut :by {:as :person} :ids ["agg"] :why "two secrets"})
            (ws/open-generation! :brian id {:format :generation :recut {:seq (count (:entries (ws/read-ws :brian id)))}
                                            :words "split it" :ids ["agg"] :base-rev "abc"})
            (add! :intent {:format :intent :goal "g2" :done-when ["d"] :supersedes {:seq 1 :why "moved"}})
            (let [owed (p/amendment-owed :brian id 1)]
              (is (seq owed) "the survey still stands on the replaced goal")
              (is (not-any? #(or (= d1 (:supersedes %)) (= d1 (:design %))) owed)
                  "and nothing is owed over the design of the generation before")))))
      (finally (fs/delete-tree tmp)))))
