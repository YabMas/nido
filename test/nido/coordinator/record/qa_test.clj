(ns nido.coordinator.record.qa-test
  "The QA plan and the QA walk as ledger records: what the append refuses of each, and the
   screenshot verb that keeps a walk's evidence outside the ledger."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [nido.platform.core :as core]
   [nido.coordinator.report :as report]
   [nido.coordinator.record.workstream :as ws]
   [tasks.nido-workstream :as tw]))

(defn with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def a-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order"
   :shape "one summing path"
   :model {:elements [{:id "agg" :sort :module :hides "the summing order"
                       :interface "an order's total"}]
           :claims [{:id "c1" :about ["agg"]
                     :statement "the aggregate is the only summing path"
                     :falsified-by "a second path that sums lines"
                     :evidence {:by :round} :read-at ["src/a.clj:1"]}]}
   :read ["src/a.clj"]})

(defn a-design [baseline-seq]
  {:format :design :strata [] :summary "s" :shape "sh"
   :model {:elements [{:id "agg" :sort :module}]
           :claims [{:id "one-summing-path" :about ["agg"]
                     :statement "one summing path"
                     :falsified-by "a second path that sums lines"
                     :evidence {:by :round}}]}
   :standing {:relation :conforms}
   :baseline {:seq baseline-seq :relation :within}
   :intent {:seq 1} :effort :S})

(defn ledger
  "An open workstream, and an add! appending one record and answering its seq."
  []
  (let [id (:id (ws/create! :brian {:external-refs []}))]
    [id (fn [kind record]
          (if (= :design-cleared kind)
            (ws/append-entry-at! :brian id (count (:entries (ws/read-ws :brian id)))
                                 {:kind kind} (pr-str record))
            (if (= :design-decision kind)
              (ws/append-decision! :brian id (pr-str record))
              (ws/append-entry! :brian id {:kind kind} (pr-str record))))
          (count (:entries (ws/read-ws :brian id))))]))

(defn designed!
  "Intent, a verified baseline, a design decided and cleared: answers the design's seq."
  [add!]
  (add! :intent {:format :intent :goal "g" :done-when ["d"]})
  (let [b (add! :baseline a-baseline)]
    (add! :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq b :reason "it holds"})
    (let [d (add! :design (a-design b))]
      (add! :design-decision {:format :design-decision :recommend :proceed :design-seq d :reason "r"
                              :checks [{:check :relation-honest :status :held :note "n"}]
                              :asks "is it worth it?"})
      (add! :design-cleared {:format :design-cleared :design {:seq d}})
      d)))

(defn product-plan [d & [ids]]
  {:format :qa-plan :design {:seq d} :surface :product
   :what-changed "A teacher sees the total."
   :before-you-start "Staging, as a teacher."
   :steps (vec (for [i (or ids ["open" "check"])]
                 {:id i :action (str "Do " i ".") :expected (str i " happens.")}))})

(defn none-plan [d] {:format :qa-plan :design {:seq d} :surface :none :reason "a background job only"})

(def digest (apply str (repeat 64 "a")))

(defn walk [plan-seq outcomes]
  {:format :qa-walk :plan {:seq plan-seq}
   :results (vec (for [[s o] outcomes] {:step s :outcome o :screenshots [digest]}))})

;; ── the records' own shapes ────────────────────────────────────────────────

(deftest a-plan-is-parsed-at-the-append
  (testing "a :product plan carries at least one step"
    (is (not (m/validate report/QaPlan (assoc (product-plan 1) :steps [])))))
  (testing "a :none plan carries its reason"
    (is (not (m/validate report/QaPlan (dissoc (none-plan 1) :reason))))
    (is (m/validate report/QaPlan (none-plan 1))))
  (testing "step ids are unique in a plan"
    (is (not (m/validate report/QaPlan (product-plan 1 ["a" "a"]))))
    (is (m/validate report/QaPlan (product-plan 1 ["a" "b"]))))
  (testing "a plan cites the design it plans"
    (is (not (m/validate report/QaPlan (dissoc (product-plan 1) :design))))))

(deftest a-walk-result-names-a-screenshot-and-an-outcome
  (is (m/validate report/QaWalk (walk 3 [["a" :pass] ["b" :blocked]])))
  (is (not (m/validate report/QaWalk (assoc-in (walk 3 [["a" :pass]]) [:results 0 :screenshots] []))))
  (is (not (m/validate report/QaWalk (walk 3 [["a" :skipped]]))))
  (testing ":blocked is not passed"
    (is (false? (report/walk-passed? (walk 3 [["a" :pass] ["b" :blocked]]))))
    (is (true? (report/walk-passed? (walk 3 [["a" :pass]]))))))

;; ── what the append refuses ────────────────────────────────────────────────

(deftest the-append-checks-what-a-plan-and-a-walk-cite
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d (designed! add!)]
        (testing "a plan citing an entry that is no :design is refused"
          (is (thrown-with-msg? Exception #"QA plan :design cites entry 1"
                                (add! :qa-plan (product-plan 1)))))
        (let [p (add! :qa-plan (product-plan d))
              n (add! :qa-plan (none-plan d))]
          (testing "a walk of a :none plan is refused"
            (is (thrown-with-msg? Exception #"only a :product plan is walked"
                                  (add! :qa-walk (walk n [["open" :pass]])))))
          (testing "a walk missing a step, naming one twice, or one the plan lacks is refused"
            (is (thrown-with-msg? Exception #"missing: check" (add! :qa-walk (walk p [["open" :pass]]))))
            (is (thrown-with-msg? Exception #"given twice: open"
                                  (add! :qa-walk (walk p [["open" :pass] ["open" :fail] ["check" :pass]]))))
            (is (thrown-with-msg? Exception #"not in the plan: zap"
                                  (add! :qa-walk (walk p [["open" :pass] ["check" :pass] ["zap" :pass]])))))
          (testing "a walk citing what is no plan is refused"
            (is (thrown-with-msg? Exception #"QA walk :plan cites entry" (add! :qa-walk (walk d [["open" :pass]])))))
          (testing "a walk of every step of a :product plan is taken"
            (is (pos? (add! :qa-walk (walk p [["open" :pass] ["check" :fail]]))))))))))

;; ── the screenshot verb ────────────────────────────────────────────────────

(deftest a-screenshot-is-kept-by-digest-and-a-walk-may-name-only-kept-ones
  (with-tmp
    (fn [tmp]
      (let [png (str (fs/path tmp "shot.png"))
            _   (spit png "not really a png, but bytes")
            dg  (tw/screenshot-add* {:file png})]
        (is (re-matches #"[0-9a-f]{64}" dg))
        (is (= (str (core/nido-home) "/qa/screenshots") (#'tw/qa-screenshot-root)))
        (is (fs/exists? (fs/path (#'tw/qa-screenshot-root) dg)) "kept under the QA screenshot root")
        (is (= dg (tw/screenshot-add* {:file png})) "keeping it twice keeps it once")
        (testing "a missing file or one that is no image is refused"
          (is (thrown? Exception (tw/screenshot-add* {:file (str (fs/path tmp "nope.png"))})))
          (let [txt (str (fs/path tmp "a.txt"))] (spit txt "x")
               (is (thrown-with-msg? Exception #"Not a screenshot" (tw/screenshot-add* {:file txt})))))
        (testing "entry:add refuses a walk naming a digest nothing is kept for, before it appends"
          (is (thrown-with-msg? Exception #"screenshots nothing is kept for"
                                (#'tw/check-walk-screenshots! :qa-walk (pr-str (walk 9 [["a" :pass]])))))
          (is (nil? (#'tw/check-walk-screenshots!
                     :qa-walk (pr-str (assoc-in (walk 9 [["a" :pass]]) [:results 0 :screenshots] [dg]))))))))))
