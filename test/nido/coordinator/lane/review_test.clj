(ns nido.coordinator.lane.review-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.review :as review]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest run-state-from-ticket-mapping
  (is (= :awaiting-review (review/run-state-from-ticket :awaiting-input)))
  (is (= :done (review/run-state-from-ticket :triaged)))
  (is (= :done (review/run-state-from-ticket :skipped)))
  (is (= :done (review/run-state-from-ticket nil)))          ; cancelled/cleared → terminal
  (is (= :awaiting-review (review/run-state-from-ticket :investigating))
      "clean exit while still :investigating ⇒ treat as parked, not dropped"))

(defn- verdict!
  "A typed triage verdict on the workstream carrying `br`, minting it; returns the workstream."
  [br]
  (ws/append-to-ref! :brian br {:kind :triage}
                     (pr-str {:format :triage-report :ticket-key br :determination :bug
                              :title "Verdict" :summary "bug — reproduced."
                              :confidence {:level :high :reason "r"}
                              :routing nil :directions [] :notion-writes nil :trail []}))
  (ws/find-by-ref-id :brian br))

(defn- mk-run
  "A run against the workstream carrying the payload's :id, when one does — registered there, as
   `runs/create-run!` registers it."
  [id state payload skill]
  (fs/create-dirs (cstate/run-dir id))
  (runs/write-run! {:id id :project :brian :trigger :triage-teacher-bugs
                    :source {:type :notion-view} :event-payload payload
                    :skill skill :first-message "x" :agent :claude
                    :session-name (str "run-" id)
                    :workstream-id (some->> (:id payload) (ws/find-by-ref-id :brian) :id)
                    :claude-session-id nil
                    :limits {} :priority 0 :session-profile :lite :uncapped? false
                    :state state :state-history [{:at "t" :state state}]
                    :artifacts [] :error nil})
  (runs/register! id))

(deftest sweep-resolves-parked-runs-whose-ticket-is-done
  (with-tmp
    (fn [_]
      ;; its verdict accepted → swept to :done
      (verdict! "BR-1")
      (tickets/complete! :brian "BR-1")
      (mk-run "r1" :awaiting-review {:id "BR-1"} :triage-bug)
      ;; a verdict still awaiting the human → left parked
      (verdict! "BR-2")
      (mk-run "r2" :awaiting-review {:id "BR-2"} :triage-bug)
      ;; dismissed → swept to :done
      (verdict! "BR-3")
      (tickets/dismiss! :brian "BR-3")
      (mk-run "r3" :awaiting-review {:id "BR-3"} :triage-bug)
      ;; non-triage parked run → ignored
      (mk-run "r4" :awaiting-review {:id "BR-1"} :investigate-bug)
      (let [torn (atom [])
            n    (with-redefs [runs/teardown-session-for-run! (fn [r] (swap! torn conj (:id r)))]
                   (review/sweep-resolved!))]
        (is (= :done           (:state (runs/read-run "r1"))))
        (is (= :awaiting-review (:state (runs/read-run "r2"))) "still in review, untouched")
        (is (= :done           (:state (runs/read-run "r3"))) "dismissed → done")
        (is (= :awaiting-review (:state (runs/read-run "r4"))) "non-triage ignored")
        (is (= 2 n) "two triage runs resolved")
        (is (= #{"r1" "r3"} (set @torn))
            "swept runs tear down their session; parked/ignored runs do not")))))

(deftest sweep-leaves-a-parked-triage-run-with-no-verdict
  (with-tmp
    (fn [_]
      (ws/find-or-mint-by-ref! :brian "BR-5")
      (mk-run "r5" :awaiting-review {:id "BR-5"} :triage-bug)
      (is (= 0 (review/sweep-resolved!))
          "a triage agent parked before writing its verdict is still waiting on its person"))))

(deftest sweep-tolerates-parked-run-without-br-id
  ;; A legacy/anomalous parked triage run whose event-payload predates the :id
  ;; field. (some-> run :event-payload :id) is nil — the sweep must NOT NPE on
  ;; the nil br-id; such a run can't map to a ticket, so it's not-in-review and
  ;; gets resolved to :done.
  (with-tmp
    (fn [_]
      (mk-run "old" :awaiting-review {:page-id "pg" :title "smoke target"} :triage-bug)
      (with-redefs [runs/teardown-session-for-run! (fn [_] nil)]
        (is (= 1 (review/sweep-resolved!)) "nil-id parked run swept without NPE"))
      (is (= :done (:state (runs/read-run "old")))))))

(deftest run-state-from-ticket-maps-planning-parked
  (is (= :awaiting-review (review/run-state-from-ticket :planning))
      "a clean plan exit while :planning ⇒ parked, not dropped"))

(deftest sweep-resolves-a-parked-plan-run-once-work-is-recorded-past-it
  (with-tmp
    (fn [_]
      (let [w (verdict! "BR-9")]
        (tickets/complete! :brian "BR-9")
        (ws/advance-stage! :brian (:id w) :in-progress :person)
        (mk-run "plan-9" :awaiting-review {:id "BR-9"} :plan-bug)
        (is (= 0 (review/sweep-resolved!)) "while the choice to plan it holds, the plan run is needed")
        (ws/append-entry! :brian (:id w) {:kind :impl} "# work")
        (with-redefs [runs/teardown-session-for-run! (fn [_] nil)]
          (is (= 1 (review/sweep-resolved!))))
        (is (= :done (:state (runs/read-run "plan-9"))))))))
