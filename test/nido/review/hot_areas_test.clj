(ns nido.review.hot-areas-test
  "Where findings keep landing across a design's rounds and runs: `heat`, `hot-areas` and
   `hot-figures`, pure over ledger entries."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.review.record :as record]))

(def ^:private design
  {:format :design :seq 1
   :model  {:elements [{:id "m/mod" :sort :module}
                       {:id "m/op-a" :sort :operation}
                       {:id "m/op-b" :sort :operation}]
            :claims   [{:id "a1" :about ["m/mod" "m/op-a"]}
                       {:id "a2" :about ["m/op-a"]}
                       {:id "b1" :about ["m/op-b"]}
                       {:id "lone" :about ["m/mod"]}]}})

(defn- decision
  "A design decision on `design` at `seq` refuting `claims`, one finding each."
  [seq claims & {:as more}]
  (merge {:format :design-decision :seq seq :design-seq 1 :at "2026-10-20T00:00:00Z"
          :recommend :amend
          :findings (mapv (fn [c] {:claim-id c :claim "x"}) claims)}
         more))

(deftest an-area-is-a-non-container-element-or-a-lone-claim
  (let [h (record/heat design [design (decision 2 ["a1"]) (decision 3 ["lone"])])]
    (testing "a claim's finding lands on the elements it is about, never the module"
      (is (= 1 (get h "m/op-a")))
      (is (nil? (get h "m/mod"))))
    (testing "a claim about no other element is its own area"
      (is (= 1 (get h "lone"))))))

(deftest one-decision-counts-once-per-area
  (is (= 1 (get (record/heat design [design (decision 2 ["a1" "a2"])]) "m/op-a"))))

(deftest a-confirmation-ends-no-count
  (let [es [design (decision 2 ["a1"]) (decision 3 [] :recommend :proceed :confirmed ["a1"])
            (decision 4 ["a2"])]]
    (is (= 2 (get (record/heat design es) "m/op-a")))))

(deftest only-a-carried-out-step-back-resets
  (let [three [(decision 2 ["a1"]) (decision 3 ["a2"]) (decision 4 ["a1"])]]
    (testing "a recut re-does the whole design"
      (is (empty? (record/heat design (concat [design] three [(decision 5 ["b1"] :recommend :recut)])))))
    (testing "an :ask whose :step-back names the area resets it, and only it"
      (let [h (record/heat design (concat [design (decision 2 ["b1"])] three
                                          [(decision 5 [] :recommend :ask
                                                     :step-back [{:area "m/op-a" :step :ask :classes ["x"]}])]))]
        (is (nil? (get h "m/op-a")))
        (is (= 1 (get h "m/op-b")))))
    (testing "a :restate recorded on a decision that proceeds carries nothing out"
      (is (= 3 (get (record/heat design (concat [design] three
                                                [(decision 5 [] :recommend :proceed
                                                           :step-back [{:area "m/op-a" :step :restate :classes ["x"]}])]))
                    "m/op-a"))))
    (testing "a design adding a :rejected entry under the area resets it"
      (let [next-design (assoc design :seq 5 :rejected [{:alternative "old" :why-not "y" :area "m/op-a"}])]
        (is (nil? (get (record/heat next-design (concat [design] three [next-design])) "m/op-a")))))))

(deftest hot-areas-shows-the-area-whole
  (let [es    [design (decision 2 ["a1"]) (decision 3 ["a2"]) (decision 4 ["a1"]) (decision 5 ["b1"])]
        [hot] (record/hot-areas design es)]
    (is (= 1 (count (record/hot-areas design es))) "op-b at one decision is not hot")
    (is (= "m/op-a" (:area hot)))
    (is (= 3 (:heat hot)))
    (is (= ["a1" "a2"] (:claims hot)) "its claims, not the element's own id")
    (is (= [2 3 4] (mapv :seq (:history hot))))
    (is (every? :since-reset (:history hot)))))

(deftest hot-figures-reads-before-and-after-alike
  (let [hot3 [design (decision 2 ["a1"]) (decision 3 ["a2"]) (decision 4 ["a1"])]
        f    (record/hot-figures [(conj hot3 (decision 5 ["a2"] :recommend :recut))])]
    (testing "the fourth finding lands on an area already hot"
      (is (= 4 (:findings f)))
      (is (= 1 (:on-hot f))))
    (testing "a recorded :recut on a hot area is a step-back, with or without :step-back"
      (is (= 1 (:ruling-on-hot f)))
      (is (= 1 (:stepped-back f)))))
  (testing "a :step-back recorded but not carried out is counted apart"
    (let [f (record/hot-figures [[design (decision 2 ["a1"]) (decision 3 ["a2"]) (decision 4 ["a1"])
                                  (decision 5 ["a2"] :recommend :amend
                                            :step-back [{:area "m/op-a" :step :restate :classes ["x"]}])]])]
      (is (= 0 (:stepped-back f)))
      (is (= 1 (:step-back-not-carried f)))))
  (testing "the window narrows which decisions are counted, not the history they are read against"
    (let [es [design (decision 2 ["a1"]) (decision 3 ["a2"]) (decision 4 ["a1"])
              (decision 5 ["a2"] :at "2026-10-25T00:00:00Z")]]
      (is (= {:findings 1 :on-hot 1}
             (select-keys (record/hot-figures [es] {:from "2026-10-24"}) [:findings :on-hot]))))))
