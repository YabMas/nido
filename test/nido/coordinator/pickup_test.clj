(ns nido.coordinator.pickup-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.pickup :as pickup]
   [nido.coordinator.queue :as queue]
   [nido.notion.client :as client]))

(deftest extract-page-id-from-urls-and-ids
  (let [pid "2a1b3c4d5e6f7081a2b3c4d5e6f70810"]
    (is (= "2a1b3c4d-5e6f-7081-a2b3-c4d5e6f70810"
           (pickup/extract-page-id (str "https://www.notion.so/Some-Title-" pid))))
    (is (= "2a1b3c4d-5e6f-7081-a2b3-c4d5e6f70810"
           (pickup/extract-page-id (str "https://app.notion.com/p/x-" pid "?pvs=4"))))
    (is (= "2a1b3c4d-5e6f-7081-a2b3-c4d5e6f70810"
           (pickup/extract-page-id "2a1b3c4d-5e6f-7081-a2b3-c4d5e6f70810")) "bare dashed uuid")
    (is (nil? (pickup/extract-page-id "BR-4826")) "a BR id is not a page-id")
    (is (nil? (pickup/extract-page-id "nonsense")))))

(deftest resolve-ref-fetches-a-url
  (with-redefs [client/retrieve-page
                (fn [_pid _tok]
                  {:id "pg-1" :url "u" :properties
                   {"ID" {:type "unique_id" :unique_id {:prefix "BR" :number 4826}}
                    "Task result" {:type "title" :title [{:plain_text "Logo bug"}]}}})]
    (let [r (pickup/resolve-ref :brian "https://www.notion.so/Logo-2a1b3c4d5e6f7081a2b3c4d5e6f70810" "tok")]
      (is (= "BR-4826" (:id r)))
      (is (= "pg-1" (:page-id r)))
      (is (= "Logo bug" (:title r))))))

(deftest resolve-ref-fetches-a-br-id
  (with-redefs [nido.notion.views/load-registry
                (fn [_project] {:database "db-1"})
                client/resolve-data-source-id
                (fn [database-id _tok]
                  (is (= "db-1" database-id))
                  "ds-1")
                client/data-source-query
                (fn [ds-id _tok {:keys [filter]}]
                  (is (= "ds-1" ds-id))
                  (is (= {:property "ID" :unique_id {:equals 4826}} filter))
                  {:status 200
                   :results [{:id "pg-1" :url "u" :properties
                              {"ID" {:type "unique_id" :unique_id {:prefix "BR" :number 4826}}
                               "Task result" {:type "title" :title [{:plain_text "Logo bug"}]}}}]
                   :has_more false})]
    (let [r (pickup/resolve-ref :brian "BR-4826" "tok")]
      (is (= "BR-4826" (:id r)))
      (is (= "pg-1" (:page-id r)))
      (is (= "Logo bug" (:title r))))))

(deftest resolve-ref-not-found
  (with-redefs [nido.notion.views/load-registry
                (fn [_project] {:database "db-1"})
                client/resolve-data-source-id
                (fn [_database-id _tok] "ds-1")
                client/data-source-query
                (fn [_ds-id _tok _opts] {:status 200 :results [] :has_more false})]
    (is (= {:error :not-found} (pickup/resolve-ref :brian "BR-9999" "tok")))))

(deftest resolve-ref-unrecognized-input
  (is (= {:error :unrecognized-input} (pickup/resolve-ref :brian "not a ref" "tok"))))

(deftest resolve-ref-br-id-data-source-resolution-throws
  (with-redefs [nido.notion.views/load-registry
                (fn [_project] {:database "db-1"})
                client/resolve-data-source-id
                (fn [_database-id _tok]
                  (throw (ex-info "Failed to resolve data-source id" {})))]
    (is (= {:error :notion-error} (pickup/resolve-ref :brian "BR-4826" "tok")))))

(deftest resolve-ref-br-id-query-failure-is-not-mistaken-for-not-found
  (with-redefs [nido.notion.views/load-registry
                (fn [_project] {:database "db-1"})
                client/resolve-data-source-id
                (fn [_database-id _tok] "ds-1")
                client/data-source-query
                (fn [_ds-id _tok _opts] {:error :auth :status 401})]
    (is (= {:error :auth} (pickup/resolve-ref :brian "BR-4826" "tok")))))

(deftest pickup-enqueues-plan-bug-for-a-resolved-ref
  (with-redefs [pickup/resolve-ref (fn [_ _ _] {:id "BR-1" :page-id "pg-1" :url "u" :title "t"})]
    (let [captured (atom nil)]
      (with-redefs [queue/enqueue! (fn [env] (reset! captured env) "/q/x.edn")]
        (let [r (pickup/pickup! :brian "https://notion.so/x-<id>" "tok")]
          (is (= :driving (:decision r)))
          (is (= {:project :brian :trigger :plan-bug} (:target @captured)))
          (is (= {:id "BR-1" :notion-page-id "pg-1" :url "u" :title "t"} (:payload @captured))))))))

(deftest pickup-reports-unresolved
  (with-redefs [pickup/resolve-ref (fn [_ _ _] {:error :not-found})]
    (is (= :unresolved (:decision (pickup/pickup! :brian "BR-nope" "tok"))))))
