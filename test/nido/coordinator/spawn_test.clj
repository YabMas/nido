(ns nido.coordinator.spawn-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.session :as session]
   [nido.coordinator.spawn :as spawn]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.notion.views :as views]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest external-ref-from-notion-payload
  (is (= {:adapter :notion :id "BR-1" :title "T" :url "u" :page-id "pg"}
         (spawn/external-ref {:id "BR-1" :title "T" :url "u" :page-id "pg"})))
  (is (nil? (spawn/external-ref {})))
  (is (nil? (spawn/external-ref {:id "  "}))))

(deftest ensure-workstream-dedups-on-ref
  (with-tmp
    (fn [_]
      (let [a (spawn/ensure-workstream! :brian {:id "BR-9" :title "Nine"} :triaging)
            b (spawn/ensure-workstream! :brian {:id "BR-9" :title "Nine"} :triaging)]
        (is (= (:id a) (:id b)))
        (is (= 1 (count (ws/list-ids :brian))))
        (is (= :triaging (:stage a))))
      (let [c (spawn/ensure-workstream! :brian {} :intake)]
        (is (= 2 (count (ws/list-ids :brian))))
        (is (empty? (:external-refs c)))))))

(deftest spawn-records-creates-workstream-session-and-linked-run
  (with-tmp
    (fn [_]
      (let [routed {:project :brian
                    :trigger {:name :triage-bug :skill :triage-bug :agent :claude
                              :payload "Triage {{event/title}}" :limits {:budget "30m"}
                              :source {:type :notion-view}}
                    :payload {:id "BR-7" :title "Seven"}
                    :priority 0 :session-profile :lite :uncapped? false}
            run    (spawn/spawn-records! routed {:fired-at "2026-06-08T00:00:00Z" :fired-by "t"})
            ws-id  (:workstream-id run)
            sess   (session/read-session :brian ws-id (:session-name run))]
        (is (some? ws-id))
        (is (= "BR-7" (-> (ws/find-by-ref :brian :notion "BR-7") :external-refs first :id)))
        (is (= :light (:weight sess)))
        (is (= :queued (get-in sess [:autonomy :phase])))
        (is (= :triage-bug (get-in sess [:autonomy :trigger])))
        (is (= :live (:substrate sess)))))))

(deftest spawn-records-cleans-up-orphan-on-create-failure
  (with-tmp
    (fn [_]
      ;; A routed fire whose page yields a fresh workstream, but
      ;; create-session-for-run! will throw — simulating a post-run-create failure.
      (let [routed {:project :brian
                    :trigger {:name :triage-bug :skill :triage-bug :agent :claude
                              :payload "Triage {{event/title}}" :limits {} :source {:type :notion-view}}
                    :payload {:id "BR-ORPH" :title "Orphan"}
                    :priority 0 :session-profile :lite :uncapped? false}]
        (with-redefs [spawn/create-session-for-run!
                      (fn [& _] (throw (ex-info "boom" {})))]
          (is (thrown? clojure.lang.ExceptionInfo (spawn/spawn-records! routed {:fired-at "t" :fired-by "x"}))))
        ;; the minted workstream must have been cleaned up — no orphan left
        (is (nil? (ws/find-by-ref :brian :notion "BR-ORPH")))
        (is (empty? (ws/list-ids :brian)))))))

(deftest spawn-records-keeps-preexisting-workstream-on-failure
  (with-tmp
    (fn [_]
      ;; pre-create the workstream so spawn dedups onto it; a later failure must NOT delete it
      (let [pre (ws/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-KEEP"}]})
            routed {:project :brian
                    :trigger {:name :triage-bug :skill :triage-bug :agent :claude
                              :payload "Triage {{event/title}}" :limits {} :source {:type :notion-view}}
                    :payload {:id "BR-KEEP" :title "Keep"}
                    :priority 0 :session-profile :lite :uncapped? false}]
        (with-redefs [spawn/create-session-for-run!
                      (fn [& _] (throw (ex-info "boom" {})))]
          (is (thrown? clojure.lang.ExceptionInfo (spawn/spawn-records! routed {:fired-at "t" :fired-by "x"}))))
        ;; pre-existing workstream survives
        (is (= (:id pre) (:id (ws/find-by-ref :brian :notion "BR-KEEP"))))))))

(deftest external-ref-defaults-to-notion-when-no-adapter
  ;; Regression pin: existing Notion payloads (no :adapter) stay :notion.
  (is (= :notion (:adapter (spawn/external-ref {:id "BR-1" :title "T"})))))

(deftest external-ref-honors-slack-adapter
  (let [p {:adapter :slack-message :id "slack-C1-1.0" :title "broken" :url "u"}]
    (is (= {:adapter :slack-message :id "slack-C1-1.0" :title "broken" :url "u"}
           (spawn/external-ref p)))))

(deftest ensure-workstream-dedups-slack-on-its-own-adapter
  (with-tmp
    (fn [_]
      (let [p {:adapter :slack-message :id "slack-C1-1.0" :title "broken"}
            a (spawn/ensure-workstream! :brian p :triaging)
            b (spawn/ensure-workstream! :brian p :triaging)]
        (is (= (:id a) (:id b)) "same Slack message must not mint two workstreams")
        (is (= 1 (count (ws/list-ids :brian))))
        (is (= :slack-message (-> (ws/read-ws :brian (:id a)) :external-refs first :adapter)))))))

(deftest spawn-records-creates-slack-workstream-ledger-and-session
  (with-tmp
    (fn [_]
      (let [routed {:project :brian
                    :trigger {:name :triage-slack-bugs :skill :triage-bug :agent :claude
                              :payload "Triage {{event/title}}" :limits {:budget "15m"}
                              :source {:type :slack-channel}}
                    :payload {:adapter :slack-message :id "slack-C1-9.0" :title "Nine" :text "boom" :url "u"}
                    :priority 0 :session-profile :lite :uncapped? false}
            run    (spawn/spawn-records! routed {:fired-at "2026-06-16T00:00:00Z" :fired-by "t"})
            ws-id  (:workstream-id run)]
        (is (some? ws-id))
        (is (= "slack-C1-9.0"
               (-> (ws/find-by-ref :brian :slack-message "slack-C1-9.0") :external-refs first :id)))))))

(deftest ensure-workstream-stamps-facets-from-payload
  (with-tmp
    (fn [_]
      (with-redefs [views/facet-properties (constantly ["App Domain" "Type"])]
        (let [payload {:adapter :notion :id "BR-1" :page-id "p"
                       :app-domain ["Teacher"] :type "bug"}
              w (spawn/ensure-workstream! :brian payload :triaging)]
          (is (= {:app-domain ["Teacher"] :type "bug"} (:facets w))))))))
