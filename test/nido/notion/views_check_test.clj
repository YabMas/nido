(ns nido.notion.views-check-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.io :as io]
   [nido.notion.client :as client]
   [nido.notion.views-check :as check]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "projects" "brian")))
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- mock-ds!
  "Stub resolve-data-source-id + retrieve-data-source for the duration of `f`.
   `properties` is the data source's :properties map (cheshire keywordises
   JSON keys, so property names arrive as keywords like :Status)."
  [properties f]
  (with-redefs [client/resolve-data-source-id (fn [_db _token] "ds-1")
                client/retrieve-data-source   (fn [_ds _token] {:properties properties})]
    (f)))

(deftest check-registry-passes-when-properties-exist
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db-1"
                      :views {:v {:filter {:property "Status"
                                           :status {:equals "Needs verification"}}}}})
      (mock-ds! {:Status {:type "status"
                          :status {:options [{:name "Needs verification"}]}}}
        (fn []
          (let [result (check/check-registry :brian "fake-token")]
            (is (= :ok (:status result)))))))))

(deftest check-registry-passes-when-properties-keyed-as-strings
  ;; Defensive: in case cheshire is reconfigured to keep string keys, the
  ;; lookup helpers should still match.
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db-1"
                      :views {:v {:filter {:property "Status"
                                           :status {:equals "Needs verification"}}}}})
      (mock-ds! {"Status" {:type "status"
                           :status {:options [{:name "Needs verification"}]}}}
        (fn []
          (let [result (check/check-registry :brian "fake-token")]
            (is (= :ok (:status result)))))))))

(deftest check-registry-fails-on-missing-property
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db-1"
                      :views {:v {:filter {:property "Bogus"
                                           :status {:equals "x"}}}}})
      (mock-ds! {:Status {:type "status" :status {:options []}}}
        (fn []
          (let [result (check/check-registry :brian "fake-token")]
            (is (= :error (:status result)))
            (is (some #(re-find #"Bogus" (:message %)) (:errors result)))))))))

(deftest check-registry-fails-on-missing-select-option
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db-1"
                      :views {:v {:filter {:property "Type"
                                           :select {:equals "bug"}}}}})
      (mock-ds! {:Type {:type "select" :select {:options [{:name "feature"}]}}}
        (fn []
          (let [result (check/check-registry :brian "fake-token")]
            (is (= :error (:status result)))
            (is (some #(re-find #"bug" (:message %)) (:errors result)))))))))

(deftest check-registry-walks-and-filters
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db-1"
                      :views {:v {:filter {:and [{:property "Type"
                                                  :select {:equals "bug"}}
                                                 {:property "Status"
                                                  :status {:does_not_equal "Done"}}]}}}})
      (mock-ds! {:Type   {:type "select" :select {:options [{:name "bug"}]}}
                 :Status {:type "status" :status {:options [{:name "Done"}]}}}
        (fn []
          (let [result (check/check-registry :brian "fake-token")]
            (is (= :ok (:status result)))))))))
