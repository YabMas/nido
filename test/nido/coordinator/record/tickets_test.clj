(ns nido.coordinator.record.tickets-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.triggers :as triggers]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]
   [nido.test.units :as units]))

(def ^:private brian-triggers
  [{:name :triage-new :skill :triage-bug} {:name :plan :skill :plan-work}])

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    triggers/load-for-project (constantly brian-triggers)]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- verdict!
  "A typed triage verdict on the workstream carrying `br`, minting it; returns the verdict's :seq."
  [br]
  (ws/append-to-ref! :brian br {:kind :triage}
                      (pr-str {:format :triage-report :ticket-key br :determination :bug
                               :title "Verdict" :summary "bug — reproduced."
                               :confidence {:level :high :reason "r"}
                               :routing nil :questions [] :notion-writes nil :trail []})))

(defn- ws-of [br] (ws/find-by-ref-id :brian br))

(defn- accept! [br n]
  (ws/append-entry! :brian (:id (ws-of br)) {:kind :triage-accepted}
                    (pr-str {:format :triage-accepted :triage-seq n})))

(defn- a-fire!
  "A unit opened by a fire of `trigger` on the workstream carrying `br`; answers its id."
  [br trigger]
  (units/fire! :brian (:id (ws-of br)) trigger))

(deftest read-meta-is-nil-safe-for-nil-or-blank-br-id
  ;; A nil/blank br-id (e.g. a triage run whose event-payload predates :id)
  ;; must not NPE in fs/path — it has no ticket record, so reads return nil.
  (with-tmp
    (fn [_]
      (is (nil? (tickets/read-meta :brian nil)))
      (is (nil? (tickets/read-meta :brian "")))
      (is (nil? (tickets/status :brian nil)))
      (is (= :spawn (tickets/gate-decision :brian nil))))))

(deftest write-paths-are-nil-safe-for-nil-or-blank-br-id
  ;; A run with no resolvable br-id has no ticket — every write path must no-op
  ;; rather than NPE in fs/path (the daemon crash-loop behind the '36 sessions'
  ;; incident traced to a ticket op on a nil br-id).
  (with-tmp
    (fn [_]
      (doseq [br [nil ""]]
        (is (nil? (tickets/write-meta! :brian br {:title "T"})))
        (is (nil? (tickets/open! :brian br {:notion-page-id "p" :url "u" :title "T"
                                            :opened-by :triage-new :notion-last-edited-at "t0"})))
        (is (nil? (tickets/complete! :brian br)))
        (is (nil? (tickets/dismiss! :brian br)))))))

(deftest open-records-the-tickets-descriptive-fields
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-5236"
                     {:notion-page-id "pg1" :url "u" :title "T" :opened-by :triage-new
                      :notion-last-edited-at "t0"})
      (let [m (tickets/read-meta :brian "BR-5236")]
        (is (= "BR-5236" (:br-id m)))
        (is (= "pg1" (:notion-page-id m)))
        (is (= :triage-new (:opened-by m)))))))

(deftest status-is-read-off-the-ledger-and-units
  (with-tmp
    (fn [_]
      (testing "a ticket no workstream carries has no status, and is triaged by the gate"
        (is (nil? (tickets/status :brian "BR-0")))
        (is (= :spawn (tickets/gate-decision :brian "BR-0"))))

      (testing "a unit of a trigger whose skill does not investigate is not :investigating"
        (verdict! "BR-3")
        (a-fire! "BR-3" :plan)
        (is (= :awaiting-input (tickets/status :brian "BR-3"))))

      (testing "an open unit of an investigating skill is :investigating; its verdict, once the unit is over, waits for a person"
        (verdict! "BR-1")
        (let [u (a-fire! "BR-1" :triage-new)]
          (is (= :investigating (tickets/status :brian "BR-1")))
          (is (= :skip-active (tickets/gate-decision :brian "BR-1")))
          (units/retract! :brian (:id (ws-of "BR-1")) u))
        (is (= :awaiting-input (tickets/status :brian "BR-1")))
        (is (= :skip-active (tickets/gate-decision :brian "BR-1")))
        (is (= :skip-untriaged (tickets/promote-decision :brian "BR-1"))))

      (testing "an accepted verdict is :triaged, the one promotable status"
        (accept! "BR-1" (:seq (ws/latest-entry :brian (:id (ws-of "BR-1")) :triage)))
        (is (= :triaged (tickets/status :brian "BR-1")))
        (is (= :skip-completed (tickets/gate-decision :brian "BR-1")))
        (is (= :promote (tickets/promote-decision :brian "BR-1"))))

      (testing "work recorded past triage is :implementing"
        (ws/append-entry! :brian (:id (ws-of "BR-1")) {:kind :note} "an account, not work")
        (is (= :triaged (tickets/status :brian "BR-1")))
        (ws/append-entry! :brian (:id (ws-of "BR-1")) {:kind :impl} "# work")
        (is (= :implementing (tickets/status :brian "BR-1")))
        (is (= :skip-active (tickets/gate-decision :brian "BR-1"))))

      (testing "a triage unit over without a verdict leaves the ticket re-triable"
        (ws/find-or-mint-by-ref! :brian "BR-2")
        (units/retract! :brian (:id (ws-of "BR-2")) (a-fire! "BR-2" :triage-new))
        (is (nil? (tickets/status :brian "BR-2")))
        (is (= :spawn (tickets/gate-decision :brian "BR-2")))))))

(deftest dismiss-closes-the-workstream-and-mints-one-when-absent
  (with-tmp
    (fn [_]
      (tickets/dismiss! :brian "BR-9")
      (is (some? (ws-of "BR-9")) "a never-triaged ticket is dismissable: its workstream is minted")
      (is (= :dismissed (tickets/status :brian "BR-9")))
      (is (= :skip-completed (tickets/gate-decision :brian "BR-9")))
      (is (= :skip-completed (tickets/promote-decision :brian "BR-9")))
      (let [closes #(count (filter (comp #{:closed} :kind) (:entries (ws-of "BR-9"))))
            before (closes)]
        (tickets/dismiss! :brian "BR-9")
        (is (= before (closes)) "a workstream standing dismissed is left as it is")))))

(deftest a-restored-dismissal-makes-the-ticket-re-triable
  (with-tmp
    (fn [_]
      (verdict! "BR-3")
      (tickets/dismiss! :brian "BR-3")
      (ws/reopen! :brian (:id (ws-of "BR-3")) :triaging :person)
      (is (nil? (tickets/status :brian "BR-3"))
          "the verdict written before the dismissal does not stand")
      (is (= :spawn (tickets/gate-decision :brian "BR-3")))
      (verdict! "BR-3")
      (is (= :awaiting-input (tickets/status :brian "BR-3"))
          "the next triage's verdict is the one that counts"))))

(deftest complete-accepts-the-newest-verdict-once
  (with-tmp
    (fn [_]
      (let [n (verdict! "BR-4")]
        (tickets/complete! :brian "BR-4")
        (is (= :triaged (tickets/status :brian "BR-4")))
        (tickets/complete! :brian "BR-4")
        (is (= [n] (map :triage-seq (ws/entries-of :brian (:id (ws-of "BR-4")) :triage-accepted)))
            "an accepted verdict is not accepted again")))))

(deftest tickets-no-longer-writes-entries
  (is (nil? (resolve 'nido.coordinator.record.tickets/append-entry!))
      "entry writes moved to the workstream ledger"))

(deftest complete-leaves-every-question-of-the-verdict-open
  (with-tmp
    (fn [_]
      (let [n (ws/append-to-ref! :brian "BR-5" {:kind :triage}
                                  (pr-str {:format :triage-report :ticket-key "BR-5" :determination :bug
                                           :title "Two defects" :summary "s"
                                           :confidence {:level :high :reason "r"}
                                           :routing nil :notion-writes nil :trail []
                                           :questions [{:question "Where should it round?" :context "c" :after "a"
                                                        :directions [{:label "Once" :shape "s" :effort :S :confidence {:level :high :reason "r"}}]}
                                                       {:question "Should the receipt show it?" :context "c" :after "a"
                                                        :directions [{:label "Show" :shape "s" :effort :XS :confidence {:level :high :reason "r"}}]}]}))]
        (tickets/complete! :brian "BR-5")
        (is (= [{:format :triage-accepted :triage-seq n
                 :answers [{:question "Where should it round?"} {:question "Should the receipt show it?"}]}]
               (map #(dissoc % :seq :at) (ws/entries-of :brian (:id (ws-of "BR-5")) :triage-accepted)))
            "an acceptance that chose nothing still answers each question — left open")))))
