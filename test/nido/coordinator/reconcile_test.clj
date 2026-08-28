(ns nido.coordinator.reconcile-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.reconcile :as reconcile]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as ws]
   [nido.platform.io :as io]))

(def base-run
  {:id "2026-05-13-test-foo-zzzzzzzz"
   :project :test :trigger :foo
   :source {:type :manual :fired-at "T" :fired-by "u"}
   :event-payload {} :skill :foo :first-message "/foo"
   :agent :claude :session-name "run-test-foo-zzzzzzzz"
   :claude-session-id nil :limits {} :priority 0 :session-profile :full :uncapped? false :state :running
   :state-history [{:at "T1" :state :queued} {:at "T2" :state :running}]
   :artifacts [] :error nil})

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- mk-run [id state payload skill]
  (fs/create-dirs (cstate/run-dir id))
  (runs/write-run! {:id id :project :brian :trigger :triage-teacher-bugs
                    :source {:type :notion-view} :event-payload payload
                    :skill skill :first-message "x" :agent :claude
                    :session-name (str "run-" id) :claude-session-id nil
                    :limits {} :priority 0 :session-profile :lite :uncapped? false
                    :state state :state-history [{:at "t" :state state}]
                    :artifacts [] :error nil}))

(defn- seed-run! [run]
  (fs/create-dirs (cstate/run-dir (:id run)))
  (runs/write-run! run))

(deftest reconcile!-leaves-terminal-runs-alone
  (with-tmp
    (fn [_]
      (seed-run! (assoc base-run :state :done :state-history
                        [{:at "T" :state :queued} {:at "T" :state :done}]))
      (reconcile/reconcile!)
      (is (= :done (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-promotes-to-done-when-status-says-complete
  (with-tmp
    (fn [_]
      (seed-run! base-run)
      (io/write-edn! (cstate/run-status-path (:id base-run))
                     {:phase :complete :note "done"})
      (reconcile/reconcile!)
      (is (= :done (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-promotes-to-awaiting-review-when-status-says-awaiting
  (with-tmp
    (fn [_]
      (seed-run! base-run)
      (io/write-edn! (cstate/run-status-path (:id base-run))
                     {:phase :awaiting-input :note "?"})
      (reconcile/reconcile!)
      (is (= :awaiting-review (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-marks-failed-when-status-says-error
  (with-tmp
    (fn [_]
      (seed-run! base-run)
      (io/write-edn! (cstate/run-status-path (:id base-run))
                     {:phase :error :note "boom"})
      (reconcile/reconcile!)
      (let [r (runs/read-run (:id base-run))]
        (is (= :failed (:state r)))
        (is (= :skill-reported-error (-> r :error :reason)))))))

(deftest reconcile!-promotes-to-done-when-agent-log-has-result-event
  (with-tmp
    (fn [_]
      (seed-run! base-run)
      (spit (cstate/run-agent-log (:id base-run))
            "{\"type\":\"system\",\"subtype\":\"init\"}\n{\"type\":\"result\",\"subtype\":\"success\"}\n")
      (reconcile/reconcile!)
      (is (= :done (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-marks-orphan-when-no-evidence
  (with-tmp
    (fn [_]
      (seed-run! base-run)
      (reconcile/reconcile!)
      (let [r (runs/read-run (:id base-run))]
        (is (= :failed (:state r)))
        (is (= :orphaned-from-restart (-> r :error :reason)))))))

(deftest reconcile!-leaves-queued-runs-alone
  (with-tmp
    (fn [_]
      (seed-run! (assoc base-run :state :queued
                        :state-history [{:at "T" :state :queued}]))
      (reconcile/reconcile!)
      (let [r (runs/read-run (:id base-run))]
        ;; :queued runs are pending work, not orphans — leave them intact for
        ;; re-submission after restart.
        (is (= :queued (:state r)))))))

(deftest reconcile-one-handles-legacy-run-without-priority
  ;; Pre-Plan-A on-disk Runs lack :priority. The reconciler reads the Run,
  ;; updates state/error, and calls write-run! which validates the closed
  ;; schema. Without backfill-on-read this crashed daemon startup.
  (with-tmp
    (fn [_]
      (let [old-run {:id "legacy-2"
                     :project :brian
                     :trigger :legacy
                     :source {:type :legacy}
                     :event-payload {}
                     :skill :noop
                     :first-message "x"
                     :agent :claude
                     :session-name "s"
                     :claude-session-id nil
                     :limits {:budget "10m" :max-failures 3}
                     :state :awaiting-review
                     :state-history [{:at "2026-05-15T00:00:00Z" :state :queued}]
                     :artifacts []
                     :error nil}]
        (fs/create-dirs (cstate/run-dir "legacy-2"))
        ;; Write the raw edn directly — bypassing write-run! validation so we
        ;; can persist a record missing :priority as it would appear on disk.
        (spit (cstate/run-edn-path "legacy-2") (pr-str old-run))
        ;; reconcile! should not throw on a legacy run lacking :priority
        (reconcile/reconcile!)
        (let [after (runs/read-run "legacy-2")]
          (is (= 0 (:priority after))
              "post-reconcile run should have :priority backfilled to 0")
          (is (contains? #{:done :failed :awaiting-review} (:state after))
              "reconcile should have transitioned run to a terminal or review state"))))))

(deftest reconcile-preserves-queued-and-parked-triage
  (with-tmp
    (fn [_]
      ;; queued backlog run — must survive untouched
      (mk-run "q1" :queued {} :triage-bug)
      ;; parked run whose ticket is still awaiting review — must stay parked
      (tickets/open! :brian "BR-9" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
      (tickets/set-status! :brian "BR-9" :awaiting-input)
      (mk-run "aw1" :awaiting-review {:id "BR-9"} :triage-bug)
      ;; orphaned running run — must be forced terminal
      (mk-run "r1" :running {} :triage-bug)
      (reconcile/reconcile!)
      (is (= :queued          (:state (runs/read-run "q1"))) "queued backlog preserved")
      (is (= :awaiting-review (:state (runs/read-run "aw1"))) "parked-for-review preserved")
      (is (= :failed          (:state (runs/read-run "r1"))) "orphaned running forced terminal"))))

(deftest reconcile-dismissed-ticket-forces-triage-run-done
  ;; A dismissed ticket is terminally handled — its orphaned triage run
  ;; reconciles to :done (mirrors the :triaged case), not :failed.
  (with-tmp
    (fn [_]
      (tickets/dismiss! :brian "BR-5")
      (mk-run "d1" :running {:id "BR-5"} :triage-bug)
      (reconcile/reconcile!)
      (is (= :done (:state (runs/read-run "d1")))))))

(def ^:private autonomy-running
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :triage-bug :limits {} :priority 0 :uncapped? false :on-promote nil
   :phase :running :phase-history [{:at "2026-06-01T00:00:00Z" :phase :running}]
   :error nil})

(defn- write-run-with-sid!
  "Write a run with a given session-id, workstream-id, session-name, and state."
  [id ws-id sname sid state]
  (fs/create-dirs (cstate/run-dir id))
  (runs/write-run! {:id id :project :brian :trigger :triage-bug
                    :source {:type :manual} :event-payload {} :skill :triage-bug
                    :first-message "/triage-bug" :agent :claude :session-name sname
                    :workstream-id ws-id :claude-session-id sid
                    :limits {} :priority 0 :session-profile :full
                    :uncapped? false :state state
                    :state-history [{:at "2026-06-01T00:00:00Z" :state state}]
                    :artifacts [] :error nil}))

(deftest reconcile-backfills-claude-session-id
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :claude-session-id nil)})
        (write-run-with-sid! "r1" (:id w) "auto" "sid-x" :running)
        (reconcile/reconcile!)
        (is (= "sid-x" (get-in (first (session/list-sessions :brian (:id w)))
                               [:autonomy :claude-session-id])))))))

(deftest orphaned-merge-run-reconciles-to-awaiting-review
  ;; A :running :merge run with no status file / no result line in agent.log
  ;; must park as :awaiting-review (gate inbox), not silently fail.
  ;; The session's autonomy phase must also mirror to :parked (no WARN).
  (with-tmp
    (fn [_]
      (let [id "merge-r1"
            w  (ws/create! :brian {:stage :in-progress :external-refs []})
            _  (session/create! :brian (:id w)
                                {:name "impl-x" :weight :heavy
                                 :autonomy (assoc autonomy-running
                                                  :trigger :merge
                                                  :skill :drive-home
                                                  :first-message "/drive-home")})]
        (seed-run! {:id id :project :brian :trigger :merge :skill :drive-home
                    :source {:type :manual} :event-payload {}
                    :first-message "/drive-home" :agent :claude
                    :session-name "impl-x" :workstream-id (:id w)
                    :claude-session-id nil :limits {} :priority 0
                    :session-profile :full :uncapped? false
                    :state :running
                    :state-history [{:at "T" :state :running}]
                    :artifacts [] :error nil})
        (reconcile/reconcile!)
        (let [r (runs/read-run id)
              s (session/read-session :brian (:id w) "impl-x")]
          (is (= :awaiting-review (:state r))
              "orphaned merge run should park as :awaiting-review, not :failed")
          (is (= :orphaned-from-restart (-> r :error :reason))
              "error reason should be :orphaned-from-restart")
          (is (= :parked (get-in s [:autonomy :phase]))
              "session autonomy phase must mirror to :parked"))))))
