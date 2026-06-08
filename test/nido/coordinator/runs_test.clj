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
   :on-promote      nil
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

(deftest preprocessing-is-a-state
  (is (contains? runs/states :preprocessing)))

(deftest queued-can-transition-to-preprocessing
  (is (runs/valid-transition? :queued :preprocessing)))

(deftest preprocessing-can-transition-to-running
  (is (runs/valid-transition? :preprocessing :running)))

(deftest preprocessing-can-transition-to-failed
  (is (runs/valid-transition? :preprocessing :failed)))

(deftest preprocessing-can-transition-to-halted
  (is (runs/valid-transition? :preprocessing :halted)))

(deftest queued-still-allows-direct-running
  ;; Triggers without :preprocess skip the preprocessing phase entirely.
  (is (runs/valid-transition? :queued :running)))

;; ---------------------------------------------------------------------------
;; in-progress-count-by-trigger
;; ---------------------------------------------------------------------------

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(defn- mk-run [id trigger state]
  (let [run {:id id :project :brian :trigger trigger
             :source {:type :notion-view} :event-payload {}
             :skill :triage-bug :first-message "x" :agent :claude
             :session-name (str "run-" id) :claude-session-id nil
             :limits {} :priority 0 :session-profile :lite :uncapped? false
             :state state :state-history [{:at "t" :state state}]
             :artifacts [] :error nil}]
    (fs/create-dirs (cstate/run-dir id))
    (runs/write-run! run)))

(deftest in-progress-count-by-trigger-counts-only-in-progress
  (with-tmp
    (fn [_]
      (mk-run "a" :triage-teacher-bugs :running)
      (mk-run "b" :triage-teacher-bugs :awaiting-review)
      (mk-run "c" :triage-teacher-bugs :queued)     ; pending pool — must NOT count
      (mk-run "d" :triage-teacher-bugs :done)        ; terminal — must NOT count
      (mk-run "e" :other-trigger :running)           ; different trigger
      (let [counts (runs/in-progress-count-by-trigger)]
        (is (= 2 (get counts :triage-teacher-bugs)))
        (is (= 1 (get counts :other-trigger)))))))

(deftest create-run-derives-ticket-stable-session-name-and-snapshots-on-promote
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [trigger {:name :plan-bug :source {:type :manual} :skill :plan-bug
                       :payload "Plan {{event/title}}"
                       :session-profile :full
                       :session-name-prefix "impl-"
                       :on-promote {:notion-status "In progress"}
                       :limits {:budget "45m" :max-failures 3}}
              run (runs/create-run! {:project :brian :trigger trigger
                                     :payload {:id "BR-4659" :title "firefox loading"
                                               :notion-page-id "PG1"}
                                     :priority 0 :session-profile :full :uncapped? false}
                                    {:fired-at "t" :fired-by "u"})]
          (is (= "impl-br-4659-firefox-loading" (:session-name run))
              "prefix + slugged BR id + slugged title, lower-cased")
          (is (= {:notion-status "In progress"} (:on-promote run)))
          (is (= "/plan-bug Plan firefox loading" (:first-message run)))))
      (finally (fs/delete-tree tmp)))))

(deftest create-run-session-name-truncates-long-title-and-handles-no-title
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [mk (fn [payload]
                   (:session-name
                    (runs/create-run!
                     {:project :brian
                      :trigger {:name :plan-bug :source {:type :manual} :skill :plan-bug
                                :payload "x" :session-name-prefix "impl-"}
                      :payload payload :priority 0 :session-profile :full :uncapped? false}
                     {:fired-at "t" :fired-by "u"})))]
          ;; id but no title → just the BR slug
          (is (= "impl-br-4659" (mk {:id "BR-4659"})))
          ;; punctuation collapses to dashes; the title slug caps near 40 chars
          ;; on a word boundary (no mid-word cut, no trailing dash)
          (is (= "impl-br-77-new-ai-dialogue-doesn-t-take-course"
                 (mk {:id "BR-77" :title "New AI dialogue doesn’t take course settings into account."})))))
      (finally (fs/delete-tree tmp)))))

(deftest create-run-without-prefix-keeps-random-session-name
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [trigger {:name :triage-bug :source {:type :notion-view} :skill :triage-bug
                       :payload "Triage {{event/title}}"}
              run (runs/create-run! {:project :brian :trigger trigger
                                     :payload {:id "BR-1" :title "x"}
                                     :priority 0 :session-profile :lite :uncapped? false}
                                    {:fired-at "t" :fired-by "u"})]
          (is (re-matches #"run-brian-triage-bug-[0-9a-f]{8}" (:session-name run)))
          (is (nil? (:on-promote run)))))
      (finally (fs/delete-tree tmp)))))

(deftest create-run-with-prefix-but-no-id-falls-back-to-random-name
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        ;; :session-name-prefix set, but the payload carries no :id ⇒ random name
        (let [trigger {:name :plan-bug :source {:type :manual} :skill :plan-bug
                       :payload "Plan {{event/title}}"
                       :session-name-prefix "impl-"}
              run (runs/create-run! {:project :brian :trigger trigger
                                     :payload {:title "no id here"}
                                     :priority 0 :session-profile :full :uncapped? false}
                                    {:fired-at "t" :fired-by "u"})]
          (is (re-matches #"run-brian-plan-bug-[0-9a-f]{8}" (:session-name run)))))
      (finally (fs/delete-tree tmp)))))

(deftest teardown-skips-plan-bug-sessions-but-runs-for-triage
  ;; A provision-only impl (:plan-bug) session is the human's workspace and must
  ;; NOT be reclaimed when its run goes terminal; a triage (:triage-bug) lite
  ;; session is an ephemeral review surface and SHOULD be torn down.
  (let [destroyed (atom [])]
    (with-redefs [nido.session.lifecycle/destroy!
                  (fn [session-name _] (swap! destroyed conj session-name))]
      (runs/teardown-session-for-run!
        {:skill :plan-bug :project :brian :session-name "impl-br-1" :id "r1"})
      (is (= [] @destroyed) "plan-bug session is never reclaimed")
      (runs/teardown-session-for-run!
        {:skill :triage-bug :project :brian :session-name "run-triage-1" :id "r2"})
      (is (= ["run-triage-1"] @destroyed) "triage lite session is reclaimed"))))

(deftest state->phase-maps-every-run-state
  (is (= :running (runs/state->phase :running)))
  (is (= :parked  (runs/state->phase :awaiting-review)))
  (is (= :done    (runs/state->phase :done)))
  (is (= :failed  (runs/state->phase :failed)))
  (is (= :halted  (runs/state->phase :halted)))
  (is (= :queued  (runs/state->phase :queued)))
  (is (= :preprocessing (runs/state->phase :preprocessing)))
  (is (= :failed  (runs/state->phase :dry-run-would-fire)))
  (is (every? runs/state->phase runs/states)))
