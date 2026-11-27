(ns tasks.nido-bench-census-test
  "The census reports every page the dashboard lists, each by the median of its loads, and
   never drops one whose loads missed a deadline or that the budget cut."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.ui.server :as server]
   [tasks.nido-bench :as bench]))

(def ^:private pages
  {:pages [{:path "/a"} {:path "/b"} {:path "/p/:id" :instance (constantly "/p/1")}
           {:path "/q/:id" :instance (constantly nil)}]
   :left-out []})

(deftest the-median-is-the-middle-load
  (is (= 20 (bench/median [30 10 20])))
  (is (= 15.0 (bench/median [10 20])))
  (is (nil? (bench/median []))))

(deftest every-page-is-reported
  (with-redefs [server/page-routes (constantly pages)]
    (let [loads (atom [])
          rows  (bench/census* {:base "http://h" :loads 3 :project "nido"
                                :deadline-ms 1000 :budget-ms 60000}
                               (fn [url ms]
                                 (swap! loads conj [url ms])
                                 (if (= url "http://h/b")
                                   (if (= 1 (count (filter #(= url (first %)) @loads)))
                                     {:error :timed-out}
                                     {:ms 50})
                                   {:ms (count @loads)})))
          by    (into {} (map (juxt :path identity)) rows)]
      (is (= ["/a" "/b" "/p/:id" "/q/:id"] (mapv :path rows)) "every listed page, none dropped")
      (is (every? #(<= (second %) 1000) @loads) "every load has the deadline")
      (testing "each measured page has its loads and their median"
        (is (= 3 (count (:samples (by "/a")))))
        (is (= (bench/median (:samples (by "/a"))) (:median (by "/a"))))
        (is (= :ok (:status (by "/a"))))
        (is (= "http://h/p/1" (:url (by "/p/:id")))))
      (testing "a load that missed its deadline is reported, with the samples it has"
        (is (= :timed-out (:status (by "/b"))))
        (is (= [50 50] (:samples (by "/b")))))
      (testing "a parameterised page with no instance is reported, not dropped"
        (is (= :no-instance (:status (by "/q/:id"))))))))

(deftest a-spent-budget-reports-what-it-did-not-measure
  (with-redefs [server/page-routes (constantly pages)]
    (let [rows (bench/census* {:base "http://h" :loads 3 :project "nido"
                               :deadline-ms 1000 :budget-ms 0}
                              (fn [_ _] (throw (ex-info "no load may start" {}))))]
      (is (= 4 (count rows)))
      (is (= #{:not-measured :no-instance} (set (map :status rows)))))))
