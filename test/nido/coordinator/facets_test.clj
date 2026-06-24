(ns nido.coordinator.facets-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.facets :as facets]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.notion.client :as notion]
   [nido.notion.views :as views]))

(def ^:private payload
  {:source :notion-view :page-id "p" :title "X"
   :app-domain ["Teacher"] :type "bug" :status "Needs verification"})

(deftest select-facets-pulls-only-configured
  (is (= {:app-domain ["Teacher"] :type "bug"}
         (facets/select-facets ["App Domain" "Type"] payload))))

(deftest select-facets-omits-absent
  (is (= {:type "bug"}
         (facets/select-facets ["App Domain" "Type"] {:type "bug"}))))

(deftest select-facets-omits-empty-vector
  (is (= {} (facets/select-facets ["App Domain"] {:app-domain []}))))

(deftest select-facets-empty-config-is-empty
  (is (= {} (facets/select-facets [] payload))))

;; raw Notion page object (what retrieve-page returns) for BR-1
(def ^:private raw-page
  {:id "p1" :url "u" :created_time "t" :last_edited_time "t"
   :properties {:Type        {:type "select" :select {:name "bug"}}
                (keyword "App Domain") {:type "multi_select"
                                        :multi_select [{:name "Teacher"}]}}})

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))] (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(deftest refresh-ws-rewrites-facets-from-page
  (with-tmp
    (fn []
      (with-redefs [views/facet-properties (constantly ["App Domain" "Type"])
                    notion/retrieve-page   (fn [_pid _tok] raw-page)]
        (let [w (ws/create! :brian {:stage :triaging
                                    :external-refs [{:adapter :notion :id "BR-1" :page-id "p1"}]})]
          (facets/refresh-ws! :brian (:id w) "tok")
          (is (= {:app-domain ["Teacher"] :type "bug"}
                 (:facets (ws/read-ws :brian (:id w))))))))))

(deftest refresh-ws-noop-for-refless
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :scratch :external-refs []})]
        (is (nil? (facets/refresh-ws! :brian (:id w) "tok")))
        (is (nil? (:facets (ws/read-ws :brian (:id w)))))))))

(deftest refresh-ws-noop-on-page-error
  (with-tmp
    (fn []
      (with-redefs [views/facet-properties (constantly ["Type"])
                    notion/retrieve-page   (fn [_ _] {:error :network})]
        (let [w (ws/create! :brian {:stage :triaging
                                    :external-refs [{:adapter :notion :id "BR-1" :page-id "p1"}]
                                    :facets {:type "old"}})]
          (facets/refresh-ws! :brian (:id w) "tok")
          (is (= {:type "old"} (:facets (ws/read-ws :brian (:id w)))) "facets untouched on error"))))))

(deftest refresh-for-ticket-resolves-by-notion-ref
  (with-tmp
    (fn []
      (with-redefs [views/facet-properties (constantly ["Type"])
                    notion/retrieve-page   (fn [_ _] raw-page)
                    notion/keychain-token  (constantly "tok")]
        (let [w (ws/create! :brian {:stage :triaging
                                    :external-refs [{:adapter :notion :id "BR-1" :page-id "p1"}]})]
          (facets/refresh-for-ticket! :brian "BR-1")
          (is (= "bug" (-> (ws/read-ws :brian (:id w)) :facets :type))))))))

(deftest refresh-for-ticket-noop-for-unknown
  (with-tmp
    (fn []
      (is (nil? (facets/refresh-for-ticket! :brian "BR-404"))))))
