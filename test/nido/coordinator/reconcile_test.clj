(ns nido.coordinator.reconcile-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.reconcile :as reconcile]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def base-run
  {:id "2026-05-13-test-foo-zzzzzzzz"
   :project :test :trigger :foo
   :source {:type :manual :fired-at "T" :fired-by "u"}
   :event-payload {} :skill :foo :first-message "/foo"
   :agent :claude :session-name "run-test-foo-zzzzzzzz"
   :claude-session-id nil :limits {} :priority 0 :state :running
   :state-history [{:at "T1" :state :queued} {:at "T2" :state :running}]
   :artifacts [] :error nil})

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(defn- seed-run! [run]
  (fs/create-dirs (cstate/run-dir (:id run)))
  (runs/write-run! run))

(deftest reconcile!-leaves-terminal-runs-alone
  (with-tmp
    (fn []
      (seed-run! (assoc base-run :state :done :state-history
                        [{:at "T" :state :queued} {:at "T" :state :done}]))
      (reconcile/reconcile!)
      (is (= :done (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-promotes-to-done-when-status-says-complete
  (with-tmp
    (fn []
      (seed-run! base-run)
      (io/write-edn! (cstate/run-status-path (:id base-run))
                     {:phase :complete :note "done"})
      (reconcile/reconcile!)
      (is (= :done (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-promotes-to-awaiting-review-when-status-says-awaiting
  (with-tmp
    (fn []
      (seed-run! base-run)
      (io/write-edn! (cstate/run-status-path (:id base-run))
                     {:phase :awaiting-input :note "?"})
      (reconcile/reconcile!)
      (is (= :awaiting-review (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-marks-failed-when-status-says-error
  (with-tmp
    (fn []
      (seed-run! base-run)
      (io/write-edn! (cstate/run-status-path (:id base-run))
                     {:phase :error :note "boom"})
      (reconcile/reconcile!)
      (let [r (runs/read-run (:id base-run))]
        (is (= :failed (:state r)))
        (is (= :skill-reported-error (-> r :error :reason)))))))

(deftest reconcile!-promotes-to-done-when-agent-log-has-result-event
  (with-tmp
    (fn []
      (seed-run! base-run)
      (spit (cstate/run-agent-log (:id base-run))
            "{\"type\":\"system\",\"subtype\":\"init\"}\n{\"type\":\"result\",\"subtype\":\"success\"}\n")
      (reconcile/reconcile!)
      (is (= :done (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-marks-orphan-when-no-evidence
  (with-tmp
    (fn []
      (seed-run! base-run)
      (reconcile/reconcile!)
      (let [r (runs/read-run (:id base-run))]
        (is (= :failed (:state r)))
        (is (= :orphaned-from-restart (-> r :error :reason)))))))

(deftest reconcile!-handles-queued-runs-too
  (with-tmp
    (fn []
      (seed-run! (assoc base-run :state :queued
                        :state-history [{:at "T" :state :queued}]))
      (reconcile/reconcile!)
      (let [r (runs/read-run (:id base-run))]
        ;; Queued Runs that didn't start yet are also orphaned — they never
        ;; got a session or agent.
        (is (= :failed (:state r)))
        (is (= :orphaned-from-restart (-> r :error :reason)))))))

(deftest reconcile-one-handles-legacy-run-without-priority
  ;; Pre-Plan-A on-disk Runs lack :priority. The reconciler reads the Run,
  ;; updates state/error, and calls write-run! which validates the closed
  ;; schema. Without backfill-on-read this crashed daemon startup.
  (with-tmp
    (fn []
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
