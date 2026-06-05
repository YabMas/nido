(ns nido.coordinator.review-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.review :as review]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest run-state-from-ticket-mapping
  (is (= :awaiting-review (review/run-state-from-ticket :awaiting-input)))
  (is (= :done (review/run-state-from-ticket :triaged)))
  (is (= :done (review/run-state-from-ticket :skipped)))
  (is (= :done (review/run-state-from-ticket nil)))          ; cancelled/cleared → terminal
  (is (= :awaiting-review (review/run-state-from-ticket :investigating))
      "clean exit while still :investigating ⇒ treat as parked, not dropped"))

(defn- mk-run [id state payload skill]
  (fs/create-dirs (cstate/run-dir id))
  (runs/write-run! {:id id :project :brian :trigger :triage-teacher-bugs
                    :source {:type :notion-view} :event-payload payload
                    :skill skill :first-message "x" :agent :claude
                    :session-name (str "run-" id) :claude-session-id nil
                    :limits {} :priority 0 :session-profile :lite :uncapped? false
                    :state state :state-history [{:at "t" :state state}]
                    :artifacts [] :error nil}))

(deftest sweep-resolves-parked-runs-whose-ticket-is-done
  (with-tmp
    (fn [_]
      ;; resolved (apply) → swept to :done
      (tickets/open! :brian "BR-1" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
      (tickets/complete! :brian "BR-1" :triaged :applied)
      (mk-run "r1" :awaiting-review {:id "BR-1"} :triage-bug)
      ;; still awaiting the human → left parked
      (tickets/open! :brian "BR-2" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
      (tickets/set-status! :brian "BR-2" :awaiting-input)
      (mk-run "r2" :awaiting-review {:id "BR-2"} :triage-bug)
      ;; cancelled (status cleared) → swept to :done (re-triable, source re-exposes later)
      (tickets/open! :brian "BR-3" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
      (tickets/clear-status! :brian "BR-3")
      (mk-run "r3" :awaiting-review {:id "BR-3"} :triage-bug)
      ;; non-triage parked run → ignored
      (mk-run "r4" :awaiting-review {:id "BR-1"} :investigate-bug)
      (let [torn (atom [])
            n    (with-redefs [runs/teardown-session-for-run! (fn [r] (swap! torn conj (:id r)))]
                   (review/sweep-resolved!))]
        (is (= :done           (:state (runs/read-run "r1"))))
        (is (= :awaiting-review (:state (runs/read-run "r2"))) "still in review, untouched")
        (is (= :done           (:state (runs/read-run "r3"))) "cancelled → done")
        (is (= :awaiting-review (:state (runs/read-run "r4"))) "non-triage ignored")
        (is (= 2 n) "two triage runs resolved")
        (is (= #{"r1" "r3"} (set @torn))
            "swept runs tear down their session; parked/ignored runs do not")))))

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

(deftest sweep-resolves-parked-plan-runs-too
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-9" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (tickets/complete! :brian "BR-9" :triaged :applied)   ; triage done; plan resolved
      (mk-run "plan-9" :awaiting-review {:id "BR-9"} :plan-bug)
      (with-redefs [runs/teardown-session-for-run! (fn [_] nil)]
        (is (= 1 (review/sweep-resolved!))))
      (is (= :done (:state (runs/read-run "plan-9")))))))

(deftest sweep-leaves-parked-plan-run-still-in-review
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-10" {:notion-page-id "p" :url "u" :title "T"
                                     :opened-by :triage-new :notion-last-edited-at "t"})
      (tickets/set-status! :brian "BR-10" :planning)        ; still being planned
      (mk-run "plan-10" :awaiting-review {:id "BR-10"} :plan-bug)
      (is (= 0 (review/sweep-resolved!)))
      (is (= :awaiting-review (:state (runs/read-run "plan-10")))))))
