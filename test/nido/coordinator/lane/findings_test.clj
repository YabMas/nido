(ns nido.coordinator.lane.findings-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.findings :as findings]
   [nido.coordinator.source.queue :as queue]
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

(deftest file-appends-event-to-workstream-ledger-seeds-tracker-reopens-enqueues
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
          (is (nil? (:closed (ws/read-ws :brian (:id w)))) "filing reopens it, as for any landing")
          (is (= 3 (:between-phases (:findings (ws/read-ws :brian (:id w))))) "the tracker keeps the plan")
          (findings/resolve! :brian (:id w) ["f1"] "commit abc")
          (is (= {:outcome :between-phases :design {:seq 3}}
                 (select-keys (:closed (ws/read-ws :brian (:id w))) [:outcome :design]))
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
          (is (nil? (:closed (ws/read-ws :brian (:id w))))
              "its fix PR will close it between phases when it lands"))))))

(deftest resolving-a-round-on-a-done-workstream-leaves-it-open
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (constantly "/q/x.edn")]
        (let [w (shipped-ticket-ws)]
          (findings/file! :brian (:id w) {:items [{:summary "A" :severity :tweak}]})
          (findings/resolve! :brian (:id w) ["f1"] "commit abc")
          (is (nil? (:closed (ws/read-ws :brian (:id w))))))))))
