(ns nido.coordinator.view.drains-test
  "The drains page reads each open drain off its records — phase, recipe version, newest census and
   its trend, slices by state — and renders the person's acts on it; a slice is never a card of
   its own in the gate inbox."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.view.drains :as view]
   [nido.platform.core :as core]
   [nido.review.stages :as stages]
   [nido.ui.views :as views]
   [tasks.nido-review :as review]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))] (f))
         (finally (fs/delete-tree tmp)))))

(def ^:private p :brian)

(def ^:private a-recipe
  {:steps ["s"] :ready-gates ["g"] :standing-orders [] :counter {:command "bb countdown"}})

(deftest a-drain-reads-off-its-records
  (with-tmp
    (fn []
      (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "wrap-conn deleted"})]
        (is (= :chartered (:phase (view/drain p d))))
        (let [prop (drain/propose-recipe! p d {:reason "first cut" :recipe a-recipe})]
          (is (= :recipe-proposed (:phase (view/drain p d))))
          (is (= [prop] (map :seq (:proposals (view/drain p d)))))
          (let [v  (drain/approve-recipe! p d prop)
                _  (drain/record-census! p d {:recipe v :remaining 5 :groups []})
                c  (drain/record-census! p d {:recipe v :remaining 4 :groups []})
                pl (drain/plan-slice! p d {:sites ["src/a.clj"] :rests-on [] :census c})
                s  (drain/start-slice! p d pl :nido)
                r  (view/drain p d)]
            (is (= :draining (:phase r)))
            (is (= v (:version r)))
            (is (= 4 (get-in r [:census :remaining])))
            (is (= [5 4] (:trend r)) "the readings under the current recipe, oldest first")
            (is (= [{:ws-id s :state :started :sites ["src/a.clj"]}]
                   (map #(select-keys % [:ws-id :state :sites]) (:slices r))))
            (drain/clear-slice! p d s [])
            (is (= {:cleared 1} (:by-state (view/drain p d))))
            ;; The record refuses a slice's landing before its review under its version passed.
            (with-redefs [stages/project+ws-from-cwd (fn [_] [p s])]
              (review/append-review-entry! "/wt" {:status :converged :findings []
                                                  :design (stages/discover-design-record "/wt")}
                                           {:summary {:rounds 1 :fix-attempts 0}
                                            :target {:base "main" :base-rev "abc"}}
                                           "/runs/r/report.json"))
            (ws/append-entry! p s {:kind :closed} (pr-str {:format :closed :outcome :done :note "landed"}))
            (is (= {:landed 1} (:by-state (view/drain p d))))
            (testing "a slice is no drain"
              (is (nil? (view/drain p s)))
              (is (= [d] (map :ws-id (view/drains p)))))))))))

(deftest the-page-offers-the-persons-acts
  (with-tmp
    (fn []
      (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "wrap-conn deleted"})
            prop (drain/propose-recipe! p d {:reason "first cut" :recipe a-recipe})
            html (views/drains-fragment [(view/drain p d)])]
        (is (str/includes? html "wrap-conn deleted"))
        (is (str/includes? html (str "/drains/brian/" d "/approve/" prop)) "a pending proposal is the person's to approve")
        (is (str/includes? (views/drains-fragment [] "nope") "nope") "a refusal is named")))))

(deftest every-act-posts-under-the-pages-scope
  (with-tmp
    (fn []
      (let [d    (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "wrap-conn deleted"})
            prop (drain/propose-recipe! p d {:reason "first cut" :recipe a-recipe})]
        (is (str/includes? (views/drains-fragment [(view/drain p d)] nil "brian")
                           (str "/drains/brian/" d "/approve/" prop "?scope=brian")))
        (is (not (str/includes? (views/drains-fragment [(view/drain p d)] nil "all") "?scope="))
            "the unscoped page carries no scope")))))
