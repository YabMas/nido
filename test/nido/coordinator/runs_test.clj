(ns nido.coordinator.runs-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]))

(def example-run
  {:id              "2026-05-13-brian-investigate-bug-a1b2c3"
   :project         :brian
   :trigger         :investigate-bug
   :source          {:type :manual :fired-at "2026-05-13T09:14:22Z" :fired-by "yabmas"}
   :event-payload   {:url "https://notion.so/page/abc"}
   :skill           :investigate-bug
   :first-message   "/investigate-bug https://notion.so/page/abc"
   :agent           :claude
   :session-name    "run-2026-05-13-investigate-bug-a1b2c3"
   :claude-session-id nil
   :limits          {:budget "30m"}
   :state           :queued
   :state-history   [{:at "2026-05-13T09:14:22Z" :state :queued}]
   :artifacts       []
   :error           nil})

(deftest schema-accepts-valid-run
  (is (m/validate runs/Run example-run)))

(deftest schema-rejects-bad-state
  (is (not (m/validate runs/Run (assoc example-run :state :nonsense)))))

(deftest schema-rejects-missing-required-fields
  (is (not (m/validate runs/Run (dissoc example-run :id))))
  (is (not (m/validate runs/Run (dissoc example-run :state)))))

(deftest round-trip
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir (:id example-run)))
        (runs/write-run! example-run)
        (is (= example-run (runs/read-run (:id example-run)))))
      (finally (fs/delete-tree tmp)))))

(deftest read-run-returns-nil-when-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly (str tmp))]
        (is (nil? (runs/read-run "does-not-exist"))))
      (finally (fs/delete-tree tmp)))))
