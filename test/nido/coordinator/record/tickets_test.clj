(ns nido.coordinator.record.tickets-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- verdict!
  "A typed triage verdict on the workstream carrying `br`, minting it; returns the verdict's :seq."
  [br]
  (ws/seq-of-path
   (ws/append-to-ref! :brian br {:kind :triage}
                      (pr-str {:format :triage-report :ticket-key br :determination :bug
                               :title "Verdict" :summary "bug — reproduced."
                               :confidence {:level :high :reason "r"}
                               :routing nil :directions [] :notion-writes nil :trail []}))))

(defn- ws-of [br] (ws/find-by-ref-id :brian br))

(defn- accept! [br n]
  (ws/append-entry! :brian (:id (ws-of br)) {:kind :triage-accepted}
                    (pr-str {:format :triage-accepted :triage-seq n})))

(defn- a-run!
  "A run of `skill` in `state` against the workstream carrying `br`, registered as create-run! does."
  [id br skill state]
  (fs/create-dirs (cstate/run-dir id))
  (runs/write-run! {:id id :project :brian :trigger :t
                    :source {:type :notion-view} :event-payload {:id br}
                    :skill skill :first-message "x" :agent :claude
                    :session-name (str "run-" id) :workstream-id (:id (ws-of br))
                    :claude-session-id nil
                    :limits {} :priority 0 :session-profile :lite :uncapped? false
                    :state state :state-history [{:at "t" :state state}]
                    :artifacts [] :error nil})
  (runs/register! id))

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

(deftest status-is-read-off-the-ledger-and-runs
  (with-tmp
    (fn [_]
      (testing "a ticket no workstream carries has no status, and is triaged by the gate"
        (is (nil? (tickets/status :brian "BR-0")))
        (is (= :spawn (tickets/gate-decision :brian "BR-0"))))

      (testing "an open investigating run is :investigating; its verdict, once the run ends, waits for a person"
        (verdict! "BR-1")
        (a-run! "r1" "BR-1" :triage-bug :running)
        (is (= :investigating (tickets/status :brian "BR-1")))
        (is (= :skip-active (tickets/gate-decision :brian "BR-1")))
        (a-run! "r1" "BR-1" :triage-bug :done)
        (is (= :awaiting-input (tickets/status :brian "BR-1")))
        (is (= :skip-active (tickets/gate-decision :brian "BR-1")))
        (is (= :skip-untriaged (tickets/promote-decision :brian "BR-1"))))

      (testing "an accepted verdict is :triaged, the one promotable status"
        (accept! "BR-1" 1)
        (is (= :triaged (tickets/status :brian "BR-1")))
        (is (= :skip-completed (tickets/gate-decision :brian "BR-1")))
        (is (= :promote (tickets/promote-decision :brian "BR-1"))))

      (testing "an open plan run is :planning, ahead of the verdict it plans from"
        (a-run! "r2" "BR-1" :plan-bug :awaiting-review)
        (is (= :planning (tickets/status :brian "BR-1")))
        (is (= :skip-active (tickets/promote-decision :brian "BR-1")))
        (a-run! "r2" "BR-1" :plan-bug :failed)
        (is (= :triaged (tickets/status :brian "BR-1"))
            "a plan run that ended owns the ticket no longer, so it is promotable again"))

      (testing "work recorded past triage is :implementing"
        (ws/append-entry! :brian (:id (ws-of "BR-1")) {:kind :note} "an account, not work")
        (is (= :triaged (tickets/status :brian "BR-1")))
        (ws/append-entry! :brian (:id (ws-of "BR-1")) {:kind :impl} "# work")
        (is (= :implementing (tickets/status :brian "BR-1")))
        (is (= :skip-active (tickets/gate-decision :brian "BR-1"))))

      (testing "a triage run that ended without a verdict leaves the ticket re-triable"
        (ws/find-or-mint-by-ref! :brian "BR-2")
        (a-run! "r3" "BR-2" :triage-bug :failed)
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
