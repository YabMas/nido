(ns nido.coordinator.session-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.session :as sess]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]))

(def human-session
  {:name "explore-firefox"
   :workstream-id "ws-1"
   :project :brian
   :weight :light
   :substrate :live
   :substrate-history [{:at "2026-06-05T09:00:00Z" :substrate :live}]
   :autonomy nil
   :created-at "2026-06-05T09:00:00Z"})

(def autonomous-session
  (assoc human-session
         :name "run-triage-x"
         :weight :light
         :autonomy {:skill :triage-bug
                    :first-message "/triage-bug BR-1"
                    :agent :claude
                    :claude-session-id nil
                    :trigger :triage-bug
                    :limits {:budget "30m" :max-failures 3}
                    :priority 0
                    :uncapped? false
                    :on-promote nil
                    :phase :running
                    :phase-history [{:at "2026-06-05T09:00:00Z" :phase :queued}
                                    {:at "2026-06-05T09:01:00Z" :phase :running}]
                    :error nil}))

(deftest schema-accepts-human-and-autonomous
  (is (m/validate sess/Session human-session))
  (is (m/validate sess/Session autonomous-session)))

(deftest schema-rejects-bad-substrate
  (is (not (m/validate sess/Session (assoc human-session :substrate :nonsense)))))

(deftest schema-rejects-bad-weight
  (is (not (m/validate sess/Session (assoc human-session :weight :medium)))))

(deftest round-trip
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (sess/write! autonomous-session)
        (is (= autonomous-session
               (sess/read-session :brian "ws-1" "run-triage-x"))))
      (finally (fs/delete-tree tmp)))))

(deftest read-session-returns-nil-when-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (is (nil? (sess/read-session :brian "ws-1" "no-such"))))
      (finally (fs/delete-tree tmp)))))

(deftest create-seeds-substrate-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [s (sess/create! :brian "ws-1"
                              {:name "sx" :weight :heavy :autonomy nil})]
          (is (= :live (:substrate s)))
          (is (= [{:at "2026-06-05T09:00:00Z" :substrate :live}] (:substrate-history s)))
          (is (= s (sess/read-session :brian "ws-1" "sx")))))
      (finally (fs/delete-tree tmp)))))

(deftest list-sessions-returns-records-for-a-workstream
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (sess/create! :brian "ws-1" {:name "a" :weight :light :autonomy nil})
        (sess/create! :brian "ws-1" {:name "b" :weight :heavy :autonomy nil})
        (is (= #{"a" "b"} (set (map :name (sess/list-sessions :brian "ws-1"))))))
      (finally (fs/delete-tree tmp)))))

(deftest archive-flips-substrate-and-appends-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T13:00:00Z")]
        (sess/write! human-session)
        (let [a (sess/archive! :brian "ws-1" "explore-firefox")]
          (is (= :archived (:substrate a)))
          (is (= {:at "2026-06-05T13:00:00Z" :substrate :archived}
                 (last (:substrate-history a))))))
      (finally (fs/delete-tree tmp)))))

(deftest set-phase-updates-autonomy-and-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T13:30:00Z")]
        (sess/write! autonomous-session)
        (let [p (sess/set-phase! :brian "ws-1" "run-triage-x" :parked)]
          (is (= :parked (get-in p [:autonomy :phase])))
          (is (= {:at "2026-06-05T13:30:00Z" :phase :parked}
                 (last (get-in p [:autonomy :phase-history]))))))
      (finally (fs/delete-tree tmp)))))

(deftest set-phase-throws-on-human-session
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (sess/write! human-session)
        (is (thrown? clojure.lang.ExceptionInfo
                     (sess/set-phase! :brian "ws-1" "explore-firefox" :parked))))
      (finally (fs/delete-tree tmp)))))

(deftest predicates
  (is (sess/live? human-session))
  (is (not (sess/live? (assoc human-session :substrate :archived))))
  (is (not (sess/parked? autonomous-session)))
  (is (sess/parked? (assoc-in autonomous-session [:autonomy :phase] :parked)))
  ;; parked requires BOTH live and phase :parked — an archived session with a
  ;; :parked phase is NOT parked.
  (is (not (sess/parked? (-> autonomous-session
                             (assoc :substrate :archived)
                             (assoc-in [:autonomy :phase] :parked)))))
  (is (sess/autonomous? autonomous-session))
  (is (not (sess/autonomous? human-session))))

(deftest archive-is-idempotent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T13:00:00Z")]
        (sess/write! human-session)
        (sess/archive! :brian "ws-1" "explore-firefox")
        (let [again (sess/archive! :brian "ws-1" "explore-firefox")]
          (is (= :archived (:substrate again)))
          ;; live + archived only — second archive adds no duplicate entry.
          (is (= 2 (count (:substrate-history again))))))
      (finally (fs/delete-tree tmp)))))

(deftest engagement-projection
  ;; settled wins regardless of sessions
  (is (= :settled (sess/engagement-state {:at "t" :outcome :done} [autonomous-session])))
  ;; parked beats active (a parked session is also live)
  (let [parked (assoc-in autonomous-session [:autonomy :phase] :parked)]
    (is (= :parked-at-gate (sess/engagement-state nil [human-session parked]))))
  ;; any live session ⇒ active
  (is (= :active (sess/engagement-state nil [human-session])))
  ;; no live sessions ⇒ idle
  (is (= :idle (sess/engagement-state nil [(assoc human-session :substrate :archived)])))
  (is (= :idle (sess/engagement-state nil []))))

(deftest engagement-state-distinguishes-queued-from-active
  (let [auton (fn [phase] {:substrate :live :autonomy {:phase phase}})
        human {:substrate :live :autonomy nil}]
    (is (= :settled        (sess/engagement-state {:at "t" :outcome :done} [(auton :running)])))
    (is (= :parked-at-gate (sess/engagement-state nil [(auton :parked) (auton :queued)])))
    (is (= :active         (sess/engagement-state nil [(auton :running)])))
    (is (= :active         (sess/engagement-state nil [(auton :preprocessing)])))
    (is (= :active         (sess/engagement-state nil [human])))
    (is (= :queued         (sess/engagement-state nil [(auton :queued)])))
    (is (= :active         (sess/engagement-state nil [(auton :queued) (auton :running)])))
    (is (= :idle           (sess/engagement-state nil [{:substrate :archived :autonomy {:phase :done}}])))
    (is (= :idle           (sess/engagement-state nil [])))))

(deftest in-flight-by-trigger-counts-live-in-progress-autonomous-sessions
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [w (ws/create! :brian {:stage :investigation})
              auto (fn [nm phase]
                     {:name nm :weight :light
                      :autonomy (assoc (:autonomy autonomous-session)
                                       :phase phase
                                       :trigger :triage-bug)})]
          (sess/create! :brian (:id w) (auto "r1" :running))
          (sess/create! :brian (:id w) (auto "r2" :running))
          (sess/create! :brian (:id w) (auto "p1" :parked))
          (sess/create! :brian (:id w) (auto "q1" :queued))
          (sess/create! :brian (:id w) {:name "human" :weight :light :autonomy nil})
          (sess/archive! :brian (:id w)
                         (:name (sess/create! :brian (:id w) (auto "gone" :running))))
          (is (= {:triage-bug 2} (sess/in-flight-by-trigger :brian)))))
      (finally (fs/delete-tree tmp)))))

(deftest gating-count-includes-parked
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [w  (ws/create! :brian {:stage :investigation})
              mk (fn [nm phase]
                   (sess/create! :brian (:id w)
                                 {:name nm :weight :light
                                  :autonomy (assoc (:autonomy autonomous-session)
                                                   :phase phase
                                                   :trigger :triage-bug)}))]
          (mk "r-run" :running)
          (mk "r-park" :parked)
          (mk "r-queued" :queued)
          ;; gating counts running + parked (backpressure), NOT queued
          (is (= {:triage-bug 2} (sess/gating-count-by-trigger :brian)))
          ;; in-flight-by-trigger (active work) still excludes parked
          (is (= {:triage-bug 1} (sess/in-flight-by-trigger :brian)))))
      (finally (fs/delete-tree tmp)))))
