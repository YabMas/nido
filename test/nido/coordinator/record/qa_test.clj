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
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.lane.pipeline :as p]
   [nido.platform.project :as project]
   [nido.ui.components.state]
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

;; ── the declaration ────────────────────────────────────────────────────────

(deftest a-project-takes-on-qa-only-by-declaring-it
  (with-redefs [project/get-project (fn [n] (get {"brian" {:qa :browser} "nido" {} "typo" {:qa :browsr}} n))]
    (is (= :browser (project/qa :brian)))
    (is (nil? (project/qa :nido)) "no declaration: QA is off")
    (is (nil? (project/qa :elsewhere)))
    (is (= {:error :undeclared-qa :value :browsr} (project/qa "typo")) "an unknown value is refused by name")
    (testing "a refused declaration is never read as off"
      (is (true? (#'p/qa-declared? :typo)))
      (is (false? (#'p/qa-declared? :nido))))))

;; ── the one reading ────────────────────────────────────────────────────────

(defn- build! [add! d] (add! :layer-completed {:format :layer-completed :design {:seq d} :layer 1 :of 1}))

(deftest the-qa-reading-has-one-state-from-the-plans-walks-and-builds
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d  (designed! add!)
            r  #(standing/qa :brian id true)]
        (testing "a project that declares no :qa reads :off, plan or none"
          (is (= :off (:state (standing/qa :brian id false)))))
        (testing "no plan citing the governing design: owed, and no plan named"
          (is (= {:state :owed :walks []} (r))))
        (build! add! d)
        (let [p1 (add! :qa-plan (product-plan d))]
          (is (= {:state :owed :plan p1 :surface :product :walks []} (r)))
          (is (= p1 (:plan (standing/qa :brian id false))) "even :off stands on the newest plan")
          (let [w1 (add! :qa-walk (walk p1 [["open" :pass] ["check" :fail]]))]
            (is (= {:state :failed :plan p1 :surface :product :walks [w1]} (r)))
            (testing "a later walk with no build between does not outweigh a failed one"
              (add! :qa-walk (walk p1 [["open" :pass] ["check" :pass]]))
              (is (= :failed (:state (r))))))
          (testing "a build since makes every earlier walk count for nothing"
            (build! add! d)
            (is (= {:state :owed :plan p1 :surface :product :walks []} (r))))
          (let [w (add! :qa-walk (walk p1 [["open" :pass] ["check" :pass]]))]
            (is (= {:state :passed :plan p1 :surface :product :walks [w]} (r))))
          (testing ":blocked is not passed"
            (add! :qa-walk (walk p1 [["open" :pass] ["check" :blocked]]))
            (is (= :failed (:state (r)))))
          (testing "the newest plan is :none: not applicable, whatever was walked before"
            (let [n (add! :qa-plan (none-plan d))]
              (is (= {:state :not-applicable :plan n :surface :none :walks []} (r)))))
          (testing "the newest plan is :product again: its own walks alone count"
            (let [p2 (add! :qa-plan (product-plan d ["one"]))]
              (is (= {:state :owed :plan p2 :surface :product :walks []} (r))))))))))

(deftest implementation-review-passed-reads-the-newest-build-from-standing
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d (designed! add!)
            w #(ws/read-ws :brian id)]
        (build! add! d)
        (is (= :layer-completed (:kind (standing/newest-build (w)))))
        (add! :review {:format :review-report :status :converged :base "main" :base-rev nil :rounds 1
                       :fix-attempts 0 :defects-settled 0 :findings-remaining 0 :report-path "r.edn"
                       :design {:seq d}})
        (is (true? (p/implementation-review-passed? (w))))
        (build! add! d)
        (is (false? (p/implementation-review-passed? (w))))))))

;; ── show ───────────────────────────────────────────────────────────────────

(deftest show-prints-the-qa-reading-and-the-plan-whatever-is-newest
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d   (designed! add!)
            out #(with-out-str (tw/show* {:project "brian" :ws-id id}))]
        (with-redefs [project/qa (constantly :browser)]
          (is (str/includes? (out) "qa: owed · product-facing: unclassified"))
          (let [p1 (add! :qa-plan (product-plan d))]
            (add! :note {:format :markdown :markdown "a later entry"})
            (let [o (out)]
              (is (str/includes? o (str "qa: owed · product-facing: :product · plan " p1)))
              (is (str/includes? o "Do open. Expected: open happens.") "the plan's steps, though a later entry is newest")
              (is (str/includes? o "## What changed")))))
        (testing "a project with no :qa still prints its product-facing line"
          (with-redefs [project/qa (constantly nil)]
            (is (str/includes? (out) "qa: off · product-facing: :product"))))))))

;; ── the gate before the candidate ──────────────────────────────────────────

(def ^:private place #'p/place)

(deftest qa-that-holds-keeps-the-workstream-off-every-landing-position
  (let [base {:ks #{:intent :review :implementation-completed} :review-passed? true :lands-pr? false}]
    (testing "under :off, :not-applicable or :passed the fold places what it places today"
      (doseq [st [nil :off :not-applicable :passed]]
        (is (= :release-candidate (place (assoc base :qa-state st))))))
    (testing "owed with the review passed: walk the plan, or write it while no :product plan is newest"
      (is (= :qa-owed (place (assoc base :qa-state :owed :qa-product? true))))
      (is (= :qa-unplanned (place (assoc base :qa-state :owed :qa-product? false)))))
    (testing "failed: back to the build, whatever the review says"
      (is (= :qa-failed (place (assoc base :qa-state :failed))))
      (is (= :qa-failed (place (assoc base :qa-state :failed :review-passed? false)))))
    (testing "a merge grant does not place a held workstream at :land-granted"
      (is (= :qa-owed (place (assoc base :qa-state :owed :qa-product? true :merge-granted? true))))
      (is (= :qa-failed (place (assoc base :qa-state :failed :merge-granted? true)))))
    (testing "an open PR does not publish a held workstream; a review no longer passed is owed again"
      (let [pr (assoc base :ks #{:intent :review :pr-opened} :lands-pr? true :review-passed? false)]
        (is (= :published (place pr)))
        (is (= :implemented (place (assoc pr :qa-state :owed))))
        (is (= :implemented (place (assoc base :review-passed? false :qa-state :owed))))))
    (testing "a halt still outranks QA"
      (is (= :blocked (place (assoc base :qa-state :failed :blocker-seq 9)))))))

(deftest each-qa-next-action-has-its-spine-stage
  (is (= {:stage :walk-qa :mode :authoring} (p/next-action :qa-owed :pickup)))
  (is (= {:stage :plan-qa :mode :authoring} (p/next-action :qa-unplanned :pickup)))
  (is (= {:stage :address-qa :mode :working-copy} (p/next-action :qa-failed :pickup)))
  (let [stage-of-next @#'p/stage-of-next]
    (is (= :qa (stage-of-next :walk-qa)))
    (is (= :qa (stage-of-next :plan-qa)))
    (is (= :implementation (stage-of-next :address-qa)))))

(deftest a-declared-project-walks-before-it-lands-and-a-failed-walk-returns-through-the-build
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d   (designed! add!)
            at  #(:at (p/of :brian id))
            review! #(add! :review {:format :review-report :status :converged :base "main" :base-rev nil
                                    :rounds 1 :fix-attempts 0 :defects-settled 0 :findings-remaining 0
                                    :report-path "r.edn" :design {:seq d}})]
        (with-redefs [project/qa (constantly :browser)
                      project/landing (constantly :push-main)]
          (add! :implementation-completed {:format :implementation-completed :summary "s" :artifacts []
                                           :design {:seq d}})
          (review!)
          (is (= :qa-unplanned (at)) "the review passed and no plan is recorded")
          (is (= :qa (:stage (p/of :brian id))))
          (let [pl (add! :qa-plan (product-plan d))]
            (is (= :qa-owed (at)))
            (is (= :walk-qa (get-in (p/of :brian id) [:next :stage])))
            (add! :qa-walk (walk pl [["open" :pass] ["check" :fail]]))
            (is (= :qa-failed (at)))
            (is (= :implementation (:stage (p/of :brian id))))
            (add! :qa-walk (walk pl [["open" :pass] ["check" :pass]]))
            (is (= :qa-failed (at)) "a later walk with no build between does not outweigh the failure")
            (add! :layer-completed {:format :layer-completed :design {:seq d} :layer 1 :of 1})
            (is (= :implemented (at)) "the fix is built: the review is owed again")
            (review!)
            (is (= :qa-owed (at)))
            (add! :qa-walk (walk pl [["open" :pass] ["check" :pass]]))
            (is (= :release-candidate (at)) "QA passed: a candidate")
            (is (= :passed (get-in (p/of :brian id) [:qa :state])))))
        (testing "the same ledger in a project that declares no :qa"
          (with-redefs [project/qa (constantly nil) project/landing (constantly :push-main)]
            (is (= :release-candidate (at)))
            (is (= :off (get-in (p/of :brian id) [:qa :state])))))))))

(deftest a-none-plan-settles-qa-whatever-was-walked-before
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d (designed! add!)]
        (with-redefs [project/qa (constantly :browser) project/landing (constantly :push-main)]
          (add! :implementation-completed {:format :implementation-completed :summary "s" :artifacts []
                                           :design {:seq d}})
          (add! :review {:format :review-report :status :converged :base "main" :base-rev nil :rounds 1
                         :fix-attempts 0 :defects-settled 0 :findings-remaining 0 :report-path "r.edn"
                         :design {:seq d}})
          (let [pl (add! :qa-plan (product-plan d))]
            (add! :qa-walk (walk pl [["open" :fail] ["check" :pass]]))
            (is (= :qa-failed (:at (p/of :brian id))))
            (add! :qa-plan (none-plan d))
            (is (= :release-candidate (:at (p/of :brian id))) "go by the newest plan")))))))

;; ── the spine ──────────────────────────────────────────────────────────────

(deftest the-spine-ends-at-qa-and-draws-it-off-where-qa-does-not-apply
  (is (= [:intent :baseline :design :implementation :qa] p/unit-stages))
  (testing "a plan belongs to the design, a walk to QA"
    (is (= :design (p/stage-of :qa-plan)))
    (is (= :qa (p/stage-of :qa-walk))))
  (let [es    [{:kind :intent :seq 1} {:kind :qa-plan :seq 2} {:kind :qa-walk :seq 3}]
        state (fn [opts] (->> (:stages (p/arc es opts)) (filter #(= :qa (:stage %))) first :state))
        held  (fn [opts st] (->> (:stages (p/arc es opts)) (filter #(= st (:stage %))) first :entries))]
    (is (= 1 (held {} :design)))
    (is (= 1 (held {} :qa)))
    (is (= :off (state {:at :implementation :qa-off? true})) "never :skipped or :ahead")
    (is (= :off (state {:at nil :qa-off? true})))
    (is (= :stale (state {:at :implementation})) "otherwise the arc's own rule")
    (is (= :current (state {:at :qa})))))

(deftest the-off-mark-is-the-marks-own
  (let [m (nido.ui.components.state/arc-mark :off nil)]
    (is (= :span.arc-off (first m)))
    (is (= "QA" (nido.ui.components.state/arc-name :qa)))))

;; ── the brief ──────────────────────────────────────────────────────────────

(deftest the-brief-is-the-plan-rendered-or-nothing
  (let [pl (assoc (product-plan 4) :also-check ["The class list still loads."]
                  :not-in-change ["Exports."])]
    (testing ":passed renders the plan's sections and steps in qa-brief.md's shape"
      (let [b (report/qa-brief {:reading {:state :passed} :plan pl})]
        (is (str/starts-with? b "QA instructions"))
        (doseq [h ["What changed" "Before you start" "Steps" "Also check" "Not in this change"]]
          (is (str/includes? b h)))
        (is (str/includes? b "1. Do open. Expected: open happens."))
        (is (str/includes? b "2. Do check. Expected: check happens."))))
    (testing ":not-applicable is the one line from the plan's reason"
      (is (= "QA instructions\n\nNothing a user sees changes: a background job only"
             (report/qa-brief {:reading {:state :not-applicable} :plan (none-plan 4)}))))
    (testing "owed, failed, or off: no brief"
      (doseq [st [:owed :failed :off]]
        (is (nil? (report/qa-brief {:reading {:state st} :plan pl})))))))

(deftest show-prints-the-landing-brief-or-says-there-is-none
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d   (designed! add!)
            out #(with-out-str (tw/show* {:project "brian" :ws-id id}))]
        (with-redefs [project/qa (constantly :browser)]
          (is (str/includes? (out) "none — QA reads owed, and no QA plan cites the governing design"))
          (add! :qa-plan (none-plan d))
          (is (str/includes? (out) "── landing QA brief ──\nQA instructions\n\nNothing a user sees changes")))))))
