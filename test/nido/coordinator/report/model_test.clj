(ns nido.coordinator.report.model-test
  "A record of any era answers with elements and claims. These pin that each earlier shape is
   read faithfully — nothing it said is lost, and nothing it could not say is invented."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.report.model :as model]))

(def ^:private model-baseline
  {:format :baseline :strata []
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

(deftest a-record-says-what-it-was-written-before
  (is (nil? (model/predates model-baseline)))
  (is (= :shared-model (model/predates survey-baseline)))
  (is (= :shared-model (model/predates nil)) "a record that could not be read holds no model either")
  (let [with-role #(update-in model-baseline [:model :elements] conj (merge {:id "r" :sort :role} %))]
    (is (= :role-players (model/predates (with-role {}))) "a role naming no players was written before they were")
    (is (nil? (model/predates (with-role {:plays ["canvas.order/total"]}))))))

(deftest a-design-describes-the-modules-it-adds
  (let [baseline {:elements [{:id "agg" :sort :module :hides "summing order" :interface "a total"}
                             {:id "total" :sort :operation}]
                  :claims   []}]
    (is (= [] (model/undescribed-modules
               baseline {:elements [{:id "agg" :sort :module}
                                    {:id "writer" :sort :module :hides "storage" :interface "store a total"}]
                         :claims   []}))
        "a module the baseline describes is named by id alone, and an added one that says both passes")
    (is (= ["writer" "total"]
           (model/undescribed-modules
            baseline {:elements [{:id "writer" :sort :module :hides "storage"} {:id "total" :sort :module}]
                      :claims   []}))
        "an added module, and an operation restated as one, say what they hide and what they expose")))

(deftest a-design-describes-the-strata-it-adds
  (let [level    [{:lens :stratified/level :verdict :sound :because "one vocabulary"}]
        baseline {:elements [{:id "records" :sort :stratum :interface "typed records" :readings level}]
                  :claims   []}]
    (is (= [] (model/undescribed-strata
               baseline {:elements [{:id "records" :sort :stratum}
                                    {:id "ledger" :sort :stratum :interface "entries" :readings level}]
                         :claims   []}))
        "a stratum the baseline describes is named by id alone, and an added one that says both passes")
    (is (= ["ledger" "views"]
           (model/undescribed-strata
            baseline {:elements [{:id "ledger" :sort :stratum :interface "entries"}
                                 {:id "views" :sort :stratum :readings level}]
                      :claims   []}))
        "an added stratum says what it provides and reads its level")))

(deftest the-strata-of-a-model-are-its-stratum-elements-in-order
  (is (= ["records" "ledger"]
         (model/strata-of {:elements [{:id "records" :sort :stratum} {:id "agg" :sort :module}
                                      {:id "ledger" :sort :stratum}]
                           :claims   []})))
  (is (= [] (model/strata-of nil))))

(deftest an-element-whose-sort-a-design-changes-carries-nothing-of-what-it-was
  (let [claim    {:id "c1" :about ["r"] :statement "s" :falsified-by "f" :evidence {:by :round}}
        baseline {:elements [{:id "agg" :sort :module :hides "summing order" :interface "a total"}
                             {:id "r" :sort :role :plays ["agg"]}]
                  :claims   [claim]}
        laid     (model/overlay baseline {:elements [{:id "agg" :sort :operation} {:id "r" :sort :kind}]
                                          :claims   [claim]})]
    (is (= [{:id "agg" :sort :operation} {:id "r" :sort :kind}] (:elements laid))
        "a module restated as an operation hides nothing, and a role restated as a kind plays nobody")))

(deftest a-design-laid-over-its-baseline-restates-nothing-it-keeps
  (let [c        (fn [id about statement] {:id id :about about :statement statement
                                           :falsified-by "f" :evidence {:by :round}})
        baseline {:elements [{:id "agg" :sort :module :hides "summing order" :interface "a total"}
                             {:id "total" :sort :operation}]
                  :claims   [(c "c1" ["agg"] "one summing path") (c "c2" ["total"] "rounded once")]}
        design   {:elements [{:id "agg" :sort :module}
                             {:id "writer" :sort :module :hides "storage" :interface "store"}]
                  :claims   [(c "c1" ["agg"] "one summing path, per line") (c "c3" ["writer"] "stored once")]
                  :removed  {:claims ["c2"]}}
        laid     (model/overlay baseline design)]
    (testing "an element the design only names keeps what the baseline said of it"
      (is (= {:id "agg" :sort :module :hides "summing order" :interface "a total"}
             (first (:elements laid)))))
    (testing "a claim the design states is the claim"
      (is (= "one summing path, per line" (:statement (first (:claims laid))))))
    (testing "a removed id is dropped, an unstated one carried, and new ids follow the baseline's"
      (is (= ["c1" "c3"] (mapv :id (:claims laid))))
      (is (= ["agg" "total" "writer"] (mapv :id (:elements laid)))))
    (testing "an id missing from a design is never a removal"
      (is (= (:claims baseline)
             (:claims (model/overlay baseline {:elements [{:id "agg" :sort :module}]
                                               :claims   [(first (:claims baseline))]})))))
    (is (not (contains? laid :removed)) "an effective model is laid over nothing, so removes nothing")))

(deftest three-models-combine-by-id-against-the-base
  (let [c    (fn [id s] {:id id :about ["agg"] :statement s :falsified-by "f" :evidence {:by :round}})
        agg  {:id "agg" :sort :module}
        base {:elements [agg] :claims [(c "c1" "one") (c "c2" "two")]}]
    (testing "a side that alone changed an id wins, and the same change on both sides is one change"
      (is (= {:model {:elements [agg] :claims [(c "c1" "one, amended") (c "c2" "two, amended")]}
              :conflicts []}
             (model/combine base
                            {:elements [agg] :claims [(c "c1" "one, amended") (c "c2" "two, amended")]}
                            {:elements [agg] :claims [(c "c1" "one") (c "c2" "two, amended")]}))))
    (testing "an id both sides changed differently is a conflict, and left out of the combination"
      (let [{:keys [model conflicts]}
            (model/combine base
                           {:elements [agg] :claims [(c "c1" "the parent's") (c "c2" "two")]}
                           {:elements [agg] :claims [(c "c1" "the child's") (c "c2" "two")]})]
        (is (= [{:kind :claim-diverged :names ["c1"]}] conflicts))
        (is (= ["c2"] (mapv :id (:claims model))))))))

(deftest a-claim-about-a-role-relies-on-its-players-as-any-side-names-them
  ;; The child narrows the role to play only b and amends the claim about it, while the parent alone
  ;; changes a. The claim was stated against a role a played at the fork, so a is a subject the
  ;; other side restated — whichever side's own reading of the role still lists it.
  (let [a     {:id "a" :sort :module}
        b     {:id "b" :sort :module}
        role  {:id "r" :sort :role :plays ["a" "b"]}
        claim {:id "c" :about ["r"] :statement "each player sums once" :falsified-by "f" :evidence {:by :round}}
        base  {:elements [a b role] :claims [claim]}]
    (is (= [{:kind :subject-restated :names ["c" "a"]}]
           (:conflicts (model/combine base
                                      {:elements [(assoc a :interface "moved") b role] :claims [claim]}
                                      {:elements [a b (assoc role :plays ["b"])]
                                       :claims   [(assoc claim :statement "each remaining player sums once")]}))))))

(deftest a-claim-about-a-role-relies-on-a-player-the-other-side-removed
  ;; Both sides narrow the role to b; only the parent deletes a, and the child amends the claim. The
  ;; combined role no longer plays a, but the claim was stated against a role a played at the fork.
  (let [a      {:id "a" :sort :module}
        b      {:id "b" :sort :module}
        role   {:id "r" :sort :role :plays ["a" "b"]}
        narrow (assoc role :plays ["b"])
        claim  {:id "c" :about ["r"] :statement "each player sums once" :falsified-by "f" :evidence {:by :round}}
        base   {:elements [a b role] :claims [claim]}]
    (is (= [{:kind :subject-removed :names ["c" "a"]}]
           (:conflicts (model/combine base
                                      {:elements [b narrow] :claims [claim]}
                                      {:elements [a b narrow]
                                       :claims   [(assoc claim :statement "each remaining player sums once")]}))))
    (is (= [] (:conflicts (model/combine base
                                         {:elements [b narrow]
                                          :claims   [(assoc claim :statement "each remaining player sums once")]}
                                         {:elements [a b role] :claims [claim]})))
        "a side that removed the player itself and amended the claim leaves nothing for the other to rely on")))
