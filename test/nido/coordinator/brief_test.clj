(ns nido.coordinator.brief-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.brief :as brief]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.notion.client :as client]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(def ^:private tref
  {:id "BR-5099" :page-id "34afca9f-403c-8076-a55f-e15171a138fe"
   :url "https://app.notion.com/p/x" :title "Setting Up L1 Correctly"})

(defn- para [s]
  {:block {:type "paragraph" :paragraph {:rich_text [{:plain_text s :type "text"}]}} :depth 0})

(defmacro ^:private with-notion
  "Stub the two Notion reads the brief makes."
  [{:keys [blocks comments]} & body]
  `(with-redefs [client/walk-blocks   (fn [~'_ ~'_ ~'_] ~blocks)
                 client/list-comments (fn [~'_ ~'_] {:status 200 :results ~comments})]
     ~@body))

(deftest brief-carries-body-ref-and-comments
  (with-notion {:blocks [(para "Allow the same L1 as L2.")]
                :comments [{:rich_text [{:plain_text "bumped to must" :type "text"}]
                            :created_time "2026-06-14T10:00:00.000Z"}]}
    (let [out (brief/ticket-brief tref "tok")]
      (is (str/includes? out "Setting Up L1 Correctly"))
      (is (str/includes? out "BR-5099"))
      (is (str/includes? out "https://app.notion.com/p/x"))
      (is (str/includes? out "Allow the same L1 as L2."))
      (is (str/includes? out "bumped to must"))
      (testing "the entry says it is a transcription, not a reading"
        (is (str/includes? out "the ticket is authoritative"))))))

(deftest brief-reads-the-page-id-under-either-key
  (with-notion {:blocks [(para "body")] :comments []}
    (is (some? (brief/ticket-brief {:id "BR-1" :notion-page-id "abc"} "tok"))
        "a payload carrying only :notion-page-id still resolves")
    (is (nil? (brief/ticket-brief {:id "BR-1"} "tok")) "no page id at all → nothing")
    (is (nil? (brief/ticket-brief tref "")) "no token → nothing")))

(deftest brief-survives-an-unreadable-page
  (testing "a ticket nido cannot read is no worse than the empty ledger it replaces"
    (with-redefs [client/walk-blocks   (fn [_ _ _] (throw (ex-info "boom" {})))
                  client/list-comments (fn [_ _] {:status 403 :error :http})]
      (is (nil? (binding [*err* (java.io.PrintWriter. (java.io.StringWriter.))]
                  (brief/ticket-brief tref "tok")))))))

(deftest brief-keeps-a-body-whose-comments-are-forbidden
  (testing "comment reads are a separate Notion capability; losing them loses nothing else"
    (with-redefs [client/walk-blocks   (fn [_ _ _] [(para "the body")])
                  client/list-comments (fn [_ _] {:status 403 :error :http})]
      (let [out (brief/ticket-brief tref "tok")]
        (is (str/includes? out "the body"))
        (is (not (str/includes? out "## Comments")))))))

(deftest ensure-appends-only-onto-an-empty-ledger
  (with-tmp
    (fn []
      (with-notion {:blocks [(para "the ticket body")] :comments []}
        (let [w (ws/create! :brian {:stage :triaging
                                    :external-refs [{:adapter :notion :id "BR-5099"}]})]
          (testing "a pickup's empty ledger gets the ticket"
            (is (some? (brief/ensure-ticket-brief! :brian (:id w) tref "tok")))
            (let [entries (:entries (ws/read-ws :brian (:id w)))]
              (is (= [:ticket] (mapv :kind entries)))
              (is (str/includes? (slurp (str (fs/path (cstate/workstream-dir :brian (:id w))
                                                      (:file (first entries)))))
                                 "the ticket body"))))
          (testing "and never a second time — one telling, not two to reconcile"
            (is (nil? (brief/ensure-ticket-brief! :brian (:id w) tref "tok")))
            (is (= 1 (count (:entries (ws/read-ws :brian (:id w))))))))))))

(deftest ensure-leaves-a-triaged-ledger-alone
  (with-tmp
    (fn []
      (with-notion {:blocks [(para "body")] :comments []}
        (let [w (ws/create! :brian {:stage :triaging
                                    :external-refs [{:adapter :notion :id "BR-1"}]})]
          (ws/append-entry! :brian (:id w) {:kind :note} "a triage already read this")
          (is (nil? (brief/ensure-ticket-brief! :brian (:id w) tref "tok")))
          (is (= 1 (count (:entries (ws/read-ws :brian (:id w)))))))))))

(deftest ensure-never-throws
  (with-tmp
    (fn []
      (binding [*err* (java.io.PrintWriter. (java.io.StringWriter.))]
        (testing "it runs on the provisioning path, where a throw reads as a failed spawn"
          (is (nil? (brief/ensure-ticket-brief! :brian "ws-does-not-exist" tref "tok")))
          (with-redefs [ws/read-ws (fn [_ _] (throw (ex-info "disk on fire" {})))]
            (is (nil? (brief/ensure-ticket-brief! :brian "ws-1" tref "tok")))))))))
