(ns nido.coordinator.lane.findings-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.findings :as findings]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(defn- shipped-ticket-ws []
  (tickets/open! :brian "BR-7" {:title "T" :url "u"})
  (let [w (ws/create! :brian {:stage :in-progress
                              :external-refs [{:adapter :notion :id "BR-7"}]})]
    (ws/close! :brian (:id w) :done)
    w))

(deftest file-appends-event-to-workstream-ledger-reopens-enqueues
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
            (let [w2 (ws/read-ws :brian (:id w))]
              (is (= :findings (-> w2 ws/newest-record :kind)))
              (is (= #{"f1" "f2"} (standing/open-findings :brian (:id w))))
              (is (= 1 (:round (ws/latest-entry :brian (:id w) :findings))))
              (is (nil? (ws/closure w2)))
              (is (= :in-progress (standing/chosen-stage :brian (:id w) w2))))
            (is (= :plan-bug (-> @enq :target :trigger)))
            (is (= "BR-7" (-> @enq :payload :id)))))))))

(deftest file-refuses-on-non-settled-workstream
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :in-progress})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (findings/file! :brian (:id w)
                                     {:items [{:summary "A" :severity :tweak}]})))))))

(deftest resolve-records-the-open-items-it-resolves
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (fn [_] "/q/x.edn")]
        (let [w (shipped-ticket-ws)]
          (findings/file! :brian (:id w)
                          {:items [{:summary "A" :severity :blocker}
                                   {:summary "B" :severity :tweak}]})
          (is (= {:round 1 :open #{"f2"}}
                 (findings/resolve! :brian (:id w) ["f1" "unknown"] "brian#812")))
          (is (= #{"f2"} (standing/open-findings :brian (:id w))))
          (is (= [{:round 1 :items ["f1"] :by "brian#812"}]
                 (map #(select-keys % [:round :items :by])
                      (ws/entries-of :brian (:id w) :findings-resolved)))
              "only the open item it named is recorded — an unknown id records nothing")
          (findings/resolve! :brian (:id w) ["f1"] "again")
          (is (= 1 (count (ws/entries-of :brian (:id w) :findings-resolved)))
              "and an item already resolved is not resolved twice"))))))

(deftest round-increments-on-second-filing
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (fn [_] "/q/x.edn")]
        (let [w (shipped-ticket-ws)]
          (findings/file! :brian (:id w) {:items [{:summary "A" :severity :tweak}]})
          (ws/close! :brian (:id w) :done)
          (let [res2 (findings/file! :brian (:id w) {:items [{:summary "C" :severity :blocker}]})]
            (is (= 2 (:round res2)))
            (is (= #{"f1"} (standing/open-findings :brian (:id w))))))))))

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
              ;; … but reopen + event still applied
              (is (= #{"f1"} (standing/open-findings :brian (:id w))))
              (is (= :in-progress (standing/chosen-stage :brian (:id w) (ws/read-ws :brian (:id w))))))))))))

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
          (is (= :findings (-> (ws/read-ws :brian (:id w)) ws/newest-record :kind)))
          ;; and NOT in the ticket ledger
          (is (not-any? #(= :findings (:kind %))
                        (:entries (tickets/read-meta :brian "BR-8")))))))))

(def ^:private phased-design-edn
  (pr-str {:format :design :strata [] :summary "s" :shape "sh"
           :model {:elements [{:id "m" :sort :module :hides "h" :interface "i"}]
                   :claims [{:id "c" :about ["m"] :statement "st" :falsified-by "f"
                             :evidence {:by :round}}]}
           :holds {"c" :always}
           :standing {:relation :conforms} :intent {:seq 1}
           :baseline {:seq 2 :relation :within} :effort :S
           :phases [{:claim "p1" :habitable "h" :exit {:kind :soak :criterion "w"} :undo {:how :revert :by "r"}}
                    {:claim "p2" :habitable "h" :exit {:kind :completion :criterion "d"} :undo {:how :none :why "g"}}]}))

(deftest resolving-a-round-filed-between-phases-returns-it-to-its-gate
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (constantly "/q/x.edn")]
        (let [w (shipped-ticket-ws)]
          (ws/close! :brian (:id w) :between-phases 3)
          (findings/file! :brian (:id w) {:items [{:summary "A" :severity :tweak}]})
          (is (nil? (ws/closure (ws/read-ws :brian (:id w)))) "filing reopens it, as for any landing")
          (is (= 3 (:between-phases (ws/latest-entry :brian (:id w) :findings))) "the round names the plan")
          (findings/resolve! :brian (:id w) ["f1"] "commit abc")
          (is (= {:outcome :between-phases :design {:seq 3}}
                 (select-keys (ws/closure (ws/read-ws :brian (:id w))) [:outcome :design]))
              "the round belonged to the landed phase; its gate is owed again under the same plan"))))))

(deftest resolving-a-round-with-a-fix-published-leaves-the-landing-to-close-it
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (constantly "/q/x.edn")]
        (let [w (shipped-ticket-ws)]
          (ws/close! :brian (:id w) :between-phases 3)
          (findings/file! :brian (:id w) {:items [{:summary "A" :severity :tweak}]})
          (ws/append-entry! :brian (:id w) {:kind :pr-opened} (pr-str {:format :pr-opened :url "u" :title "fix"}))
          (findings/resolve! :brian (:id w) ["f1"] "pr 12")
          (is (nil? (ws/closure (ws/read-ws :brian (:id w))))
              "its fix PR will close it between phases when it lands"))))))

(deftest resolving-a-round-on-a-done-workstream-leaves-it-open
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (constantly "/q/x.edn")]
        (let [w (shipped-ticket-ws)]
          (findings/file! :brian (:id w) {:items [{:summary "A" :severity :tweak}]})
          (findings/resolve! :brian (:id w) ["f1"] "commit abc")
          (is (nil? (ws/closure (ws/read-ws :brian (:id w))))))))))
