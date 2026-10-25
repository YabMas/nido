(ns nido.session.revive-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.session.revive :as revive]))

(def ^:private gb (* 1024 1024 1024))

(defn- facts [id & {:keys [last-at worktree conflicts registered]
                    :or {worktree :present conflicts :none registered "2026-10-24T12:00:00Z"}}]
  {:casualty     {:boot-id "b" :instance-id id :project "brian" :worktree (str "/w/" id)
                  :registered-at registered}
   :worktree     worktree
   :conflicts    conflicts
   :conversation (when last-at {:id (str "c-" id) :cwd (str "/w/" id) :last-at-ms last-at})})

(def ^:private roomy {:physical (* 100 gb) :committed (* 10 gb) :typical {"brian" (* 4 gb)}})

(defn- plan [fs & {:as more}]
  (mapv (juxt #(get-in % [:facts :casualty :instance-id]) :row)
        (revive/plan (merge {:facts fs :machine roomy :all? false} more))))

(deftest ranked-by-last-activity-then-registration
  (is (= [["new" :revive] ["old" :revive] ["unseen-late" :revive] ["unseen-early" :revive]]
         (plan [(facts "old" :last-at 1)
                (facts "unseen-early" :registered "2026-10-24T08:00:00Z")
                (facts "new" :last-at 9)
                (facts "unseen-late" :registered "2026-10-24T20:00:00Z")]))))

(deftest admitted-in-rank-order-while-each-still-fits
  ;; 70% of 20 GB is 14; 5 committed + 4 + 4 = 13 fits, a third 4 GB would not — but one after it
  ;; that still fits is admitted: no casualty that fits is left resumable.
  (let [machine {:physical (* 20 gb) :committed (* 5 gb) :typical {"brian" (* 4 gb) "tiny" 1}}
        tiny    (assoc-in (facts "tiny" :last-at 1) [:casualty :project] "tiny")]
    (is (= [["a" :revive] ["b" :revive] ["c" :resumable] ["tiny" :revive]]
           (plan [(facts "a" :last-at 4) (facts "b" :last-at 3) (facts "c" :last-at 2) tiny]
                 :machine machine)))
    (testing ":all admits past the budget"
      (is (every? #{:revive} (map second (plan [(facts "a" :last-at 4) (facts "b" :last-at 3)
                                                (facts "c" :last-at 2)]
                                               :machine machine :all? true)))))))

(deftest unknown-memory-admits-nothing-without-all
  (let [rows (revive/plan {:facts [(facts "a" :last-at 1)] :machine {:physical nil :committed 1 :typical {}}
                           :all? false})]
    (is (= [{:row :resumable :why :memory-unknown}] (map #(select-keys % [:row :why]) rows)))))

(deftest gone-and-conflicted-are-never-started
  (is (= [["c" :conversation-only] ["u" :conversation-only] ["g" :gone] ["ok" :revive]]
         (plan [(facts "c" :last-at 9 :conflicts :conflicted)
                (facts "u" :last-at 8 :conflicts :unknown)
                (facts "g" :last-at 7 :worktree :gone)
                (facts "ok" :last-at 6)])))
  (testing "a conflicted casualty costs nothing"
    (let [machine {:physical (* 10 gb) :committed (* 3 gb) :typical {"brian" (* 4 gb)}}]
      (is (= [["c" :conversation-only] ["ok" :revive]]
             (plan [(facts "c" :last-at 9 :conflicts :conflicted) (facts "ok" :last-at 6)]
                   :machine machine))))))

(deftest only-restricts-and-the-plan-is-pure
  (let [fs [(facts "a" :last-at 2) (facts "b" :last-at 1)]]
    (is (= [["b" :revive]] (plan fs :only #{"b"})))
    (is (= (revive/plan {:facts fs :machine roomy :all? false})
           (revive/plan {:facts fs :machine roomy :all? false})))))

(deftest estimate-falls-back-when-the-fleet-cannot-say
  (is (= (* 4 gb) (revive/estimated-bytes {:project "brian"} roomy)))
  (is (pos? (revive/estimated-bytes {:project "unknown"} roomy))))
