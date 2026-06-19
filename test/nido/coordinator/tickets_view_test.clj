(ns nido.coordinator.tickets-view-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.tickets-view :as tv]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest read-all-tickets-empty-when-none
  (with-tmp (fn [_] (is (= [] (tv/read-all-tickets))))))

(deftest read-all-tickets-reads-and-tags-project
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-1" {:notion-page-id "p" :url "u" :title "T1"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (let [all (tv/read-all-tickets)]
        (is (= 1 (count all)))
        (is (= :brian (:project (first all))))
        (is (= "BR-1" (:br-id (first all))))))))

(deftest classify-by-status
  (is (= :ready       (tv/classify {:status :triaged})))
  (is (= :in-progress (tv/classify {:status :planning})))
  (is (= :in-progress (tv/classify {:status :implementing})))
  (is (= :in-progress (tv/classify {:status :awaiting-input})))
  (is (= :dismissed   (tv/classify {:status :dismissed})))
  (is (= :other       (tv/classify {:status nil}))))

(deftest grouped-tickets-buckets-and-orders
  (let [mk (fn [br status at] {:br-id br :status status :triaged-at at})
        all [(mk "BR-1" :triaged "2026-06-04T10:00:00Z")
             (mk "BR-2" :triaged "2026-06-05T10:00:00Z")
             (mk "BR-3" :planning "2026-06-05T09:00:00Z")
             (mk "BR-4" :dismissed "2026-06-01T10:00:00Z")]
        g (tv/grouped-tickets all)]
    ;; ready group, newest activity first
    (is (= ["BR-2" "BR-1"] (mapv :br-id (:ready g))))
    (is (= ["BR-3"] (mapv :br-id (:in-progress g))))
    (is (= ["BR-4"] (mapv :br-id (:dismissed g))))))

(deftest format-row-shows-br-title-status
  (is (= "BR-4659   · firefox persistent loading animations  [triaged]"
         (tv/format-row {:br-id "BR-4659" :status :triaged
                         :title "firefox persistent loading animations"})))
  ;; long titles truncate with an ellipsis; status still appended
  (let [row (tv/format-row {:br-id "BR-7" :status :planning
                            :title (apply str (repeat 60 \x))})]
    (is (str/includes? row "…"))
    (is (str/ends-with? row "  [planning]"))))
