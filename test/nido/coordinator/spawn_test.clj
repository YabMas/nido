(ns nido.coordinator.spawn-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.session :as session]
   [nido.coordinator.spawn :as spawn]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]))

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
