(ns nido.coordinator.runs-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
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
   :priority        0
   :session-profile :full
   :uncapped?       false
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

(deftest valid-transitions
  (is (runs/valid-transition? :queued :running))
  (is (runs/valid-transition? :queued :dry-run-would-fire))
  (is (runs/valid-transition? :running :awaiting-review))
  (is (runs/valid-transition? :running :done))
  (is (runs/valid-transition? :running :failed))
  (is (runs/valid-transition? :awaiting-review :running))
  (is (runs/valid-transition? :awaiting-review :done))
  (is (not (runs/valid-transition? :queued :awaiting-review)))
  (is (not (runs/valid-transition? :running :dry-run-would-fire)))
  (is (not (runs/valid-transition? :dry-run-would-fire :running)))
  (is (not (runs/valid-transition? :done :running)))
  (is (not (runs/valid-transition? :failed :running))))

(deftest transition!-updates-state-and-history
  (let [tmp     (fs/create-temp-dir)
        fake-ts "2026-05-13T10:00:00Z"]
    (try
      (with-redefs [cstate/runs-dir (constantly (str tmp))
                    clock/now-iso   (constantly fake-ts)]
        (fs/create-dirs (cstate/run-dir (:id example-run)))
        (runs/write-run! example-run)
        (let [updated (runs/transition! (:id example-run) :running)]
          (is (= :running (:state updated)))
          (is (= 2 (count (:state-history updated))))
          (is (= :running (-> updated :state-history last :state)))
          (is (= fake-ts  (-> updated :state-history last :at))
              "transition! reads time through clock/now-iso (single seam)")))
      (finally (fs/delete-tree tmp)))))

(deftest transition!-rejects-invalid-transition
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir (:id example-run)))
        (runs/write-run! (assoc example-run :state :done))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid transition"
                              (runs/transition! (:id example-run) :running))))
      (finally (fs/delete-tree tmp)))))

(deftest transition!-throws-when-run-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly (str tmp))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Run not found"
                              (runs/transition! "no-such-run" :running))))
      (finally (fs/delete-tree tmp)))))

(deftest create-run!-builds-a-queued-run
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [fire-req {:project :brian
                        :trigger {:name    :investigate-bug
                                  :source  {:type :manual}
                                  :skill   :investigate-bug
                                  :payload "url={{event/url}}"
                                  :agent   :claude}
                        :payload {:url "https://x"}}
              run      (runs/create-run! fire-req {:fired-at "T" :fired-by "u"})]
          (is (= :queued (:state run)))
          (is (= :brian (:project run)))
          (is (= :investigate-bug (:trigger run)))
          (is (= :investigate-bug (:skill run)))
          (is (= "/investigate-bug url=https://x" (:first-message run)))
          (is (re-matches #"\d{4}-\d{2}-\d{2}-brian-investigate-bug-[a-f0-9]{8}" (:id run)))
          (is (re-matches #"run-brian-investigate-bug-[a-f0-9]{8}" (:session-name run))
              "session-name shape stays stable if run-id format changes")
          (is (= 1 (count (:state-history run))))
          (is (fs/exists? (cstate/run-edn-path (:id run))))
          (is (= run (runs/read-run (:id run))) "run.edn round-trips cleanly")))
      (finally (fs/delete-tree tmp)))))

(deftest create-run-carries-priority
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [fire-req {:project :brian
                        :trigger {:name    :investigate-bug
                                  :source  {:type :manual}
                                  :skill   :investigate-bug
                                  :payload "url={{event/url}}"}
                        :payload {:url "https://x"}
                        :priority 7}
              run      (runs/create-run! fire-req {:fired-at "T" :fired-by "u"})]
          (is (= 7 (:priority run)))))
      (finally (fs/delete-tree tmp)))))

(deftest create-run-carries-session-profile
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [run (runs/create-run!
                    {:project :p
                     :trigger {:name :t :skill :triage-bug
                               :payload "x" :source {:type :test}
                               :session-profile :lite}
                     :payload {}
                     :priority 0
                     :session-profile :lite}
                    {})]
          (is (= :lite (:session-profile run)))))
      (finally (fs/delete-tree tmp)))))

(deftest create-run-defaults-session-profile-to-full
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [run (runs/create-run!
                    {:project :p
                     :trigger {:name :t :skill :noop
                               :payload "x" :source {:type :test}}
                     :payload {}
                     :priority 0}
                    {})]
          (is (= :full (:session-profile run))
              "Runs without an explicit :session-profile should default to :full")))
      (finally (fs/delete-tree tmp)))))

(deftest read-run-backfills-missing-session-profile
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        ;; Mimic a legacy Run on disk that has :priority (from Plan A) but
        ;; no :session-profile yet.
        (let [path   (cstate/run-edn-path "legacy-pre-plan-b")
              legacy {:id "legacy-pre-plan-b"
                      :project :brian :trigger :legacy :source {:type :legacy}
                      :event-payload {} :skill :noop :first-message "x"
                      :agent :claude :session-name "s" :claude-session-id nil
                      :limits {:budget "10m" :max-failures 3}
                      :priority 0
                      :state :awaiting-review
                      :state-history [{:at "2026-05-15T00:00:00Z" :state :queued}]
                      :artifacts [] :error nil}]
          (fs/create-dirs (cstate/run-dir "legacy-pre-plan-b"))
          (spit path (pr-str legacy))
          (let [loaded (runs/read-run "legacy-pre-plan-b")]
            (is (= :full (:session-profile loaded))
                "read-run should backfill :session-profile :full on legacy Runs"))))
      (finally (fs/delete-tree tmp)))))

(deftest read-run-backfills-missing-priority
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [old-run {:id              "legacy-run-1"
                       :project         :brian
                       :trigger         :legacy
                       :source          {:type :legacy}
                       :event-payload   {}
                       :skill           :noop
                       :first-message   "x"
                       :agent           :claude
                       :session-name    "s"
                       :claude-session-id nil
                       :limits          {:budget "10m" :max-failures 3}
                       :state           :awaiting-review
                       :state-history   [{:at "2026-05-15T00:00:00Z" :state :queued}]
                       :artifacts       []
                       :error           nil}
              path    (cstate/run-edn-path "legacy-run-1")]
          (fs/create-dirs (cstate/run-dir "legacy-run-1"))
          (spit path (pr-str old-run))
          (let [loaded (runs/read-run "legacy-run-1")]
            (is (= 0 (:priority loaded))
                "read-run should backfill :priority 0 on legacy Runs missing the key"))))
      (finally (fs/delete-tree tmp)))))

(deftest create-run-defaults-uncapped-to-false
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [run (runs/create-run!
                    {:project :p :trigger {:name :t :skill :noop
                                           :payload "x" :source {:type :test}}
                     :payload {} :priority 0} {})]
          (is (= false (:uncapped? run)))))
      (finally (fs/delete-tree tmp)))))

(deftest create-run-carries-uncapped
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [run (runs/create-run!
                    {:project :p :trigger {:name :t :skill :noop
                                           :payload "x" :source {:type :test}
                                           :uncapped? true}
                     :payload {} :priority 0 :uncapped? true} {})]
          (is (= true (:uncapped? run)))))
      (finally (fs/delete-tree tmp)))))

(deftest read-run-backfills-missing-uncapped
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [path  (str (cstate/run-dir "legacy-no-uncapped") "/run.edn")
              legacy {:id "legacy-no-uncapped"
                      :project :brian :trigger :legacy :source {:type :legacy}
                      :event-payload {} :skill :noop :first-message "x"
                      :agent :claude :session-name "s" :claude-session-id nil
                      :limits {:budget "10m" :max-failures 3}
                      :priority 0 :session-profile :full
                      :state :done
                      :state-history [{:at "2026-05-15T00:00:00Z" :state :queued}]
                      :artifacts [] :error nil}]
          (fs/create-dirs (cstate/run-dir "legacy-no-uncapped"))
          (spit path (pr-str legacy))
          (let [loaded (runs/read-run "legacy-no-uncapped")]
            (is (= false (:uncapped? loaded))
                "read-run should backfill :uncapped? false on legacy Runs"))))
      (finally (fs/delete-tree tmp)))))
