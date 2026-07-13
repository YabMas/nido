(ns nido.coordinator.findings-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.findings :as findings]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(defn- shipped-ticket-ws []
  (tickets/open! :brian "BR-7" {:title "T" :url "u"})
  (let [w (ws/create! :brian {:stage :in-progress
                              :external-refs [{:adapter :notion :id "BR-7"}]})]
    (ws/close! :brian (:id w) :done)
    w))

(deftest file-appends-event-to-ticket-ledger-seeds-tracker-reopens-enqueues
  (with-tmp
    (fn []
      (let [enq (atom nil)]
        (with-redefs [queue/enqueue! (fn [e] (reset! enq e) "/q/x.edn")]
          (let [w   (shipped-ticket-ws)
                res (findings/file! :brian (:id w)
                                    {:items [{:summary "A" :severity :blocker}
                                             {:summary "B" :severity :tweak :area "Login"}]
                                     :staging-ref "s://build"
                                     :session "sess-1"})]
            (is (= 1 (:round res)))
            (let [m (tickets/read-meta :brian "BR-7")]
              (is (= :findings (-> m :entries last :kind))))
            (let [w2 (ws/read-ws :brian (:id w))]
              (is (= #{"f1" "f2"} (-> w2 :findings :open)))
              (is (= 1 (-> w2 :findings :round)))
              (is (nil? (:closed w2)))
              (is (= :in-progress (:stage w2))))
            (is (= :plan-bug (-> @enq :target :trigger)))
            (is (= "BR-7" (-> @enq :payload :id)))))))))

(deftest file-refuses-on-non-settled-workstream
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :in-progress})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (findings/file! :brian (:id w)
                                     {:items [{:summary "A" :severity :tweak}]})))))))

(deftest resolve-moves-open-to-resolved
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (fn [_] "/q/x.edn")]
        (let [w (shipped-ticket-ws)]
          (findings/file! :brian (:id w)
                          {:items [{:summary "A" :severity :blocker}
                                   {:summary "B" :severity :tweak}]})
          (findings/resolve! :brian (:id w) ["f1" "unknown"] "brian#812")
          (let [t (:findings (ws/read-ws :brian (:id w)))]
            (is (= #{"f2"} (:open t)))
            (is (= "brian#812" (get-in t [:resolved "f1" :by])))
            (is (= 1 (findings/open-count (ws/read-ws :brian (:id w)))))))))))

(deftest round-increments-on-second-filing
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (fn [_] "/q/x.edn")]
        (let [w (shipped-ticket-ws)]
          (findings/file! :brian (:id w) {:items [{:summary "A" :severity :tweak}]})
          (ws/close! :brian (:id w) :done)
          (let [res2 (findings/file! :brian (:id w) {:items [{:summary "C" :severity :blocker}]})]
            (is (= 2 (:round res2)))
            (is (= #{"f1"} (-> (ws/read-ws :brian (:id w)) :findings :open)))))))))

(deftest file-on-non-notion-workstream-skips-provisioning
  (with-tmp
    (fn []
      (let [enq (atom 0)]
        (with-redefs [queue/enqueue! (fn [_] (swap! enq inc) "/q/x.edn")]
          (let [w (ws/create! :brian {:stage :in-progress})]   ; ref-less, non-Notion
            (ws/close! :brian (:id w) :done)
            (let [res (findings/file! :brian (:id w) {:items [{:summary "A" :severity :tweak}]})]
              ;; no provisioning envelope for a non-Notion ws …
              (is (nil? (:queued res)))
              (is (zero? @enq))
              ;; … but reopen + tracker + event still applied
              (is (= #{"f1"} (-> (ws/read-ws :brian (:id w)) :findings :open)))
              (is (= :in-progress (:stage (ws/read-ws :brian (:id w))))))))))))

(deftest file-appends-to-own-entries-when-workstream-has-nonempty-entries
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (fn [_] "/q/x.edn")]
        (tickets/open! :brian "BR-8" {:title "T" :url "u"})
        (let [w (ws/create! :brian {:stage :in-progress
                                    :external-refs [{:adapter :notion :id "BR-8"}]})]
          ;; a prior own-entry (mirrors notion-sync / review appending to the ws)
          (ws/append-entry! :brian (:id w) {:kind :note} "prior note")
          (ws/close! :brian (:id w) :done)
          (findings/file! :brian (:id w) {:items [{:summary "A" :severity :tweak}]})
          ;; active-ledger reads OWN entries when non-empty → findings must land there
          (is (= :findings (-> (ws/read-ws :brian (:id w)) :entries last :kind)))
          ;; and NOT in the ticket ledger
          (is (not-any? #(= :findings (:kind %))
                        (:entries (tickets/read-meta :brian "BR-8")))))))))
