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
   [nido.coordinator.record.entry :as entry]
   [nido.coordinator.record.history :as history]
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

(def teacher {:id "teacher" :kind "teacher" :needs "owns a course with one published quiz"})

(defn product-plan [d & [ids]]
  {:format :qa-plan :design {:seq d} :surface :product
   :what-changed "A teacher sees the total."
   :before-you-start "Staging."
   :roles [teacher]
   :steps (vec (for [i (or ids ["open" "check"])]
                 {:id i :role "teacher" :action (str "Do " i ".") :expected (str i " happens.")}))})

(defn pre-roles-plan
  "A :product plan as one was written before plans declared their roles."
  [d & [ids]]
  (-> (product-plan d ids) (dissoc :roles) (update :steps #(mapv (fn [s] (dissoc s :role)) %))))

(defn none-plan [d] {:format :qa-plan :design {:seq d} :surface :none :reason "a background job only"})

(def digest (apply str (repeat 64 "a")))

(def a-cast [{:role "teacher" :account "anna@school.test"}])

(defn walk
  "A walk of plan `plan-seq` with one result per [step outcome], casting `cast` — the teacher by
   default, nobody given nil."
  ([plan-seq outcomes] (walk plan-seq outcomes a-cast))
  ([plan-seq outcomes cast]
   (cond-> {:format :qa-walk :plan {:seq plan-seq}
            :results (vec (for [[s o] outcomes] {:step s :outcome o :screenshots [digest]}))}
     (seq cast) (assoc :cast (vec cast)))))

;; ── the records' own shapes ────────────────────────────────────────────────

(defn- build! [add! d] (add! :layer-completed {:format :layer-completed :design {:seq d} :layer 1 :of 1}))

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

(deftest a-plan-declares-the-roles-its-steps-are-performed-as
  (let [refusal (fn [plan] (try (report/validate-event :qa-plan plan) nil
                                (catch Exception e (ex-message e))))]
    (testing "a plan with roles, every step naming one, is taken"
      (is (nil? (refusal (product-plan 1))))
      (is (m/validate report/QaPlan (product-plan 1))))
    (testing "a :product plan declaring no roles is refused"
      (is (re-find #"declares at least one role" (refusal (dissoc (product-plan 1) :roles))))
      (is (not (m/validate report/QaPlan (dissoc (product-plan 1) :roles)))))
    (testing "a step naming no role is refused, naming the step"
      (is (re-find #"step `check` names no :role"
                   (refusal (update-in (product-plan 1) [:steps 1] dissoc :role)))))
    (testing "a step naming an undeclared role is refused, naming the step and the role"
      (is (re-find #"step `open` names role `admin`, which the plan does not declare"
                   (refusal (assoc-in (product-plan 1) [:steps 0 :role] "admin")))))
    (testing "a role declared twice is refused, naming the role"
      (is (re-find #"role `teacher` is declared 2 times"
                   (refusal (update (product-plan 1) :roles conj (assoc teacher :needs "any"))))))
    (testing "a role is the project's name and the state its account needs"
      (is (not (m/validate report/QaPlan (update-in (product-plan 1) [:roles 0] dissoc :needs)))))
    (testing "a :none plan declares no roles"
      (is (nil? (refusal (none-plan 1))))
      (is (re-find #"a :none plan declares no :roles" (refusal (assoc (none-plan 1) :roles [teacher])))))))

(deftest a-plan-and-a-walk-from-before-roles-still-read
  (do
    (testing "an earlier plan is refused as a write, and read as it was written"
      (is (thrown? Exception (report/validate-event :qa-plan (pre-roles-plan 1))))
      (is (= (:steps (pre-roles-plan 1)) (:steps (report/parse-event :qa-plan (pre-roles-plan 1))))))
    (testing "an earlier walk, which casts nobody, reads and validates"
      (is (m/validate report/QaWalk (walk 5 [["open" :pass]] nil)))
      (is (some? (report/parse-event :qa-walk (walk 5 [["open" :pass]] nil))))))
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d  (designed! add!)
            r  #(standing/qa :brian id true)]
        (build! add! d)
        (let [p (history/append-past-the-refusal! :brian id :qa-plan (pre-roles-plan d))]
          (testing "its QA reading is what it was: owed, standing on that plan"
            (is (= {:state :owed :plan p :surface :product :walks []} (r))))
          (testing "a walk of it casts nobody, and is taken"
            (let [w (add! :qa-walk (walk p [["open" :pass] ["check" :pass]] nil))]
              (is (= {:state :passed :plan p :surface :product :walks [w]} (r)))))
          (testing "a walk of it casting a role the plan never declared is refused"
            (is (thrown-with-msg? Exception #"not a role of the plan: teacher"
                                  (add! :qa-walk (walk p [["open" :pass] ["check" :pass]])))))
          (testing "show prints its brief as it always did, with no role and no cast"
            (with-redefs [project/qa (constantly :browser)]
              (let [o (with-out-str (tw/show* {:project "brian" :ws-id id}))]
                (is (str/includes? o "1. Do open. Expected: open happens."))
                (is (not (str/includes? o "In nido's walk")))))))))))

;; ── what the append refuses ────────────────────────────────────────────────

(deftest the-append-holds-a-walk-to-cast-every-role-its-plan-uses
  (with-tmp
    (fn [_]
      (let [[_ add!] (ledger)
            d   (designed! add!)
            two (-> (product-plan d)
                    (assoc :roles [teacher {:id "pupil" :kind "student" :needs "enrolled in the course"}])
                    (assoc-in [:steps 1 :role] "pupil"))
            p   (add! :qa-plan two)
            all [["open" :pass] ["check" :pass]]
            both [{:role "teacher" :account "anna@school.test"} {:role "pupil" :account "ben@school.test"}]]
        (testing "a walk missing a role a step uses is refused, naming the role"
          (is (thrown-with-msg? Exception #"not cast: pupil" (add! :qa-walk (walk p all)))))
        (testing "a walk casting nobody is refused, naming every role"
          (is (thrown-with-msg? Exception #"not cast: teacher, pupil" (add! :qa-walk (walk p all nil)))))
        (testing "a walk naming a role the plan does not declare is refused, naming it"
          (is (thrown-with-msg? Exception #"not a role of the plan: admin"
                                (add! :qa-walk (walk p all (conj both {:role "admin" :account "root"}))))))
        (testing "a walk casting a role twice is refused, naming it"
          (is (thrown-with-msg? Exception #"cast twice: teacher"
                                (add! :qa-walk (walk p all (conj both {:role "teacher" :account "carl@school.test"}))))))
        (testing "a walk casting every role once is taken"
          (is (pos? (add! :qa-walk (walk p all both)))))))))


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

(deftest the-conditional-append-checks-a-walk-as-the-plain-append-does
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d   (designed! add!)
            p   (add! :qa-plan (product-plan d))
            at! #(ws/append-entry-at! :brian id (count (:entries (ws/read-ws :brian id)))
                                      {:kind :qa-walk} (pr-str %))]
        (is (thrown-with-msg? Exception #"missing: check" (at! (walk p [["open" :pass]]))))
        (is (thrown-with-msg? Exception #"not in the plan: zap"
                              (at! (walk p [["open" :pass] ["check" :pass] ["zap" :pass]]))))
        (is (int? (at! (walk p [["open" :pass] ["check" :pass]]))))))))

(deftest the-qa-reading-fails-closed-on-a-record-it-cannot-read
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d     (designed! add!)
            r     #(standing/qa :brian id true)
            lose  (fn [lost f]
                    (let [orig ws/entries-of]
                      (with-redefs [ws/entries-of (fn [& args]
                                                    (remove #(contains? lost (:seq %)) (apply orig args)))]
                        (f))))]
        (build! add! d)
        (let [p1 (add! :qa-plan (product-plan d))
              w1 (add! :qa-walk (walk p1 [["open" :pass] ["check" :fail]]))
              _  (add! :qa-walk (walk p1 [["open" :pass] ["check" :pass]]))]
          (testing "an unreadable failed walk does not let a later one pass"
            (lose #{w1} #(let [q (r)]
                           (is (= :owed (:state q)))
                           (is (= p1 (:plan q)))
                           (is (= :unreadable-ledger (get-in q [:blocked :reason])))))))
        (let [n  (add! :qa-plan (none-plan d))
              p2 (add! :qa-plan (product-plan d ["one"]))]
          (testing "an unreadable newest plan does not fall back to an earlier :none plan"
            (lose #{p2} #(let [q (r)]
                           (is (= :owed (:state q)))
                           (is (nil? (:plan q)))
                           (is (:indeterminate? q)))))
          (is (some? n))
          (testing "undeclared, it reads :off all the same"
            (lose #{p2} #(is (= :off (:state (standing/qa :brian id false)))))))))))

(deftest the-qa-reading-fails-closed-on-an-entry-with-no-content
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d     (designed! add!)
            r     #(standing/qa :brian id true)
            blank (fn [lost f]
                    (let [orig entry/text]
                      (reset! @#'entry/parsed-cache {})
                      (reset! @#'entry/texts {})
                      (try
                        (with-redefs [entry/text (fn [w n] (when-not (contains? lost n) (orig w n)))]
                          (f))
                        (finally (reset! @#'entry/parsed-cache {})))))]
        (build! add! d)
        (let [p1 (add! :qa-plan (product-plan d))
              w1 (add! :qa-walk (walk p1 [["open" :pass] ["check" :fail]]))
              _  (add! :qa-walk (walk p1 [["open" :pass] ["check" :pass]]))]
          (testing "a failed walk with no content does not let a later one pass"
            (blank #{w1} #(let [q (r)]
                            (is (= :owed (:state q)))
                            (is (= :unreadable-ledger (get-in q [:blocked :reason])))))))
        (add! :qa-plan (none-plan d))
        (let [p2 (add! :qa-plan (product-plan d ["one"]))]
          (testing "a newest plan with no content does not fall back to an earlier :none plan"
            (blank #{p2} #(let [q (r)]
                            (is (= :owed (:state q)))
                            (is (nil? (:plan q)))))))))))

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
        (is (false? (p/implementation-review-passed? (w))))
        (testing "a review taken from a trail that keeps no layer builds is still judged after them"
          (let [trail (remove #(= :layer-completed (:kind %)) (:entries (w)))]
            (is (false? (p/implementation-review-passed? (w) trail)))))))))

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
      (is (= :blocked (place (assoc base :qa-state :failed :blocker-seq 9)))))
    (testing "a passed review the clamped trail no longer holds — a superseding design since — advances nothing"
      (let [d2 (assoc base :ks #{:intent :design :review :implementation-completed}
                      :trail-records [{:kind :intent} {:kind :design}])]
        (is (= :designed (place (assoc d2 :qa-state :owed :qa-product? false))))
        (is (= :designed (place (assoc d2 :qa-state :owed :qa-product? true))))
        (is (= :designed (place d2)) "nor makes a working-copy lander a candidate")))))

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
        (is (str/includes? b "1. As teacher: Do open. Expected: open happens."))
        (is (str/includes? b "2. As teacher: Do check. Expected: check happens."))))
    (testing "Before you start lists each role and the state its account needs, before the plan's prose"
      (let [b (report/qa-brief {:reading {:state :passed} :plan pl})]
        (is (str/includes? b "Before you start\n- As teacher: owns a course with one published quiz\nStaging."))
        (is (not (str/includes? b "In nido's walk")) "no walk given, no cast named")))
    (testing "given the counted walk, the brief names the account that played each role"
      (let [b (report/qa-brief {:reading {:state :passed} :plan pl :walk (walk 5 [["open" :pass]])})]
        (is (str/includes? b "In nido's walk: teacher was anna@school.test."))))
    (testing "two roles of one kind are told apart by the plan's id"
      (let [two (assoc pl :roles [teacher {:id "colleague" :kind "teacher" :needs "shares the course"}])
            b   (report/qa-brief {:reading {:state :passed} :plan (assoc-in two [:steps 1 :role] "colleague")})]
        (is (str/includes? b "- As teacher (colleague): shares the course"))
        (is (str/includes? b "2. As teacher (colleague): Do check."))))
    (testing "a plan from before roles renders as it always did"
      (let [b (report/qa-brief {:reading {:state :passed} :plan (pre-roles-plan 4)})]
        (is (str/includes? b "Before you start\nStaging.\n"))
        (is (str/includes? b "1. Do open. Expected: open happens."))))
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

(deftest show-hands-the-brief-the-walk-the-passing-reading-counts
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)
            d   (designed! add!)
            out #(with-out-str (tw/show* {:project "brian" :ws-id id}))]
        (with-redefs [project/qa (constantly :browser)]
          (build! add! d)
          (let [p (add! :qa-plan (product-plan d))]
            (add! :qa-walk (walk p [["open" :pass] ["check" :fail]] [{:role "teacher" :account "first@school.test"}]))
            (is (str/includes? (out) "none — QA reads failed") "no brief while the walk failed")
            (build! add! d)
            (add! :qa-walk (walk p [["open" :pass] ["check" :pass]] [{:role "teacher" :account "second@school.test"}]))
            (let [o (out)]
              (is (str/includes? o "── landing QA brief ──\nQA instructions"))
              (is (str/includes? o "- As teacher: owns a course with one published quiz"))
              (is (str/includes? o "1. As teacher: Do open."))
              (is (str/includes? o "In nido's walk: teacher was second@school.test."))
              (is (not (str/includes? (subs o (str/index-of o "── landing QA brief ──")) "first@school.test"))
                  "the brief names the counted walk's cast, not an earlier one's"))))))))
