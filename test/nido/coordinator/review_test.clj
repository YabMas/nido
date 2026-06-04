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
      (let [n (review/sweep-resolved!)]
        (is (= :done           (:state (runs/read-run "r1"))))
        (is (= :awaiting-review (:state (runs/read-run "r2"))) "still in review, untouched")
        (is (= :done           (:state (runs/read-run "r3"))) "cancelled → done")
        (is (= :awaiting-review (:state (runs/read-run "r4"))) "non-triage ignored")
        (is (= 2 n) "two triage runs resolved")))))
