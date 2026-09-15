(ns nido.coordinator.report.model-test
  "A record of any era answers with elements and claims. These pin that each earlier shape is
   read faithfully — nothing it said is lost, and nothing it could not say is invented."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.report.model :as model]))

(def ^:private model-baseline
  {:format :baseline
   :model  {:elements [{:id "canvas.order/aggregate" :sort :module :hides "summing order"}
                       {:id "canvas.order/total" :sort :operation}]
            :claims   [{:id "one-summing-path" :about ["canvas.order/aggregate"]
                        :statement "the aggregate is the only summing path"
                        :falsified-by "a caller that sums lines itself"
                        :evidence {:by :round}}]}})

(def ^:private survey-baseline
  "A baseline from before the shared model: modules, load-bearing properties, a composition."
  {:format       :baseline
   :modules      [{:id "mod-calc" :module "calc" :hides "rounding" :interface "exact amounts"}
                  {:module "the invoice reader" :hides "layout" :interface "renders a total"}]
   :composition  "the aggregate is the only reader of lines"
   :load-bearing [{:id "c1" :property "a line amount is never rounded in place"
                   :falsified-by "a write that stores a rounded amount on a line"
                   :evidence ["src/order/calc.clj:41"]
                   :drift "copied, never decided"}]})

(deftest a-model-era-record-answers-with-its-own-model
  (is (= (:model model-baseline) (model/model model-baseline)))
  (is (= ["one-summing-path"] (mapv :id (model/claims model-baseline)))))

(deftest an-older-baseline-reads-as-elements-and-claims
  (testing "modules become elements, by id where they have one and by name before ids existed"
    (is (= [{:id "mod-calc" :sort :module :hides "rounding" :interface "exact amounts"}
            {:id "the invoice reader" :sort :module :hides "layout" :interface "renders a total"}]
           (model/elements survey-baseline))))
  (testing "a load-bearing property becomes a claim about nothing named, and keeps what it said"
    (is (= {:id "c1" :about [] :statement "a line amount is never rounded in place"
            :falsified-by "a write that stores a rounded amount on a line"
            :evidence {:by :round} :read-at ["src/order/calc.clj:41"]
            :drift "copied, never decided"}
           (first (model/claims survey-baseline)))))
  (testing "the composition is a claim about every module, under the id rounds already use"
    (is (= {:id "composition" :about ["mod-calc" "the invoice reader"]
            :statement "the aggregate is the only reader of lines" :evidence {:by :round}}
           (last (model/claims survey-baseline))))))

(deftest an-older-design-reads-its-invariants-as-claims-with-no-id
  (testing "both invariant shapes — a string, and a phased map — keep their text; neither has an
            id, and none is invented"
    (let [d {:format :design
             :invariants ["a total is rounded exactly once"
                          {:invariant "one writer" :holds :on-completion}]}]
      (is (= ["a total is rounded exactly once" "one writer"] (mapv :statement (model/claims d))))
      (is (every? nil? (map :id (model/claims d))))
      (is (= [] (model/elements d))))))

(deftest claims-by-id-holds-only-what-can-be-named
  (is (= #{"c1" "composition"} (set (keys (model/claims-by-id (model/model survey-baseline))))))
  (is (= {} (model/claims-by-id (model/model {:format :design :invariants ["x"]})))
      "an invariant with no id cannot be addressed, so it is not keyed"))

(deftest a-record-is-read-by-what-it-carries-not-by-its-tag
  (is (= ["a total is rounded exactly once"]
         (mapv :statement (model/claims {:invariants ["a total is rounded exactly once"]})))
      "a design held without its :format still states its invariants")
  (is (= ["c1"] (mapv :id (model/claims {:load-bearing [{:id "c1" :property "p"}]})))))

(deftest anything-else-has-no-model
  (is (nil? (model/model {:format :intent})))
  (is (= [] (model/claims nil))))
