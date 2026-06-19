(ns nido.coordinator.resume-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [nido.coordinator.agent :as agent]
            [nido.coordinator.resume :as resume]
            [nido.coordinator.runs :as runs]
            [nido.coordinator.session :as session]
            [nido.coordinator.state :as cstate]
            [nido.coordinator.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(def ^:private autonomy-parked
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :triage-bug :limits {:budget "30m"} :priority 4 :uncapped? false
   :on-promote nil :phase :parked
   :phase-history [{:at "2026-06-18T00:00:00Z" :phase :parked}] :error nil})

(defn- write-run! [id ws-id sname sid]
  (fs/create-dirs (cstate/run-dir id))
  (runs/write-run! {:id id :project :brian :trigger :triage-bug
                    :source {:type :manual} :event-payload {} :skill :triage-bug
                    :first-message "/triage-bug" :agent :claude :session-name sname
                    :workstream-id ws-id :claude-session-id sid
                    :limits {:budget "30m"} :priority 0 :session-profile :full
                    :uncapped? false :state :awaiting-review
                    :state-history [{:at "2026-06-18T00:00:00Z" :state :queued}]
                    :artifacts [] :error nil}))

;; run-turn! is synchronous → unit-test the actual launch + re-park here.
(deftest run-turn-launches-resume-and-reparks
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})
            calls (atom nil)]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [runs/home-present? (fn [_] true)
                      agent/launch! (fn [opts] (reset! calls opts) {:exit-code 0 :num-turns 1})]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "do the fix"))
        (is (= "sid-9" (:claude-session-id @calls)))
        (is (true? (:resume? @calls)) "continues the recorded conversation")
        (is (= "do the fix" (:first-message @calls)))
        (is (= "30m" (:budget @calls)) "the run's budget bounds the turn")
        (is (= :parked (get-in (first (session/list-sessions :brian (:id w)))
                               [:autonomy :phase]))
            "the turn re-parks the session for re-review")))))

(deftest run-turn-reparks-when-launch-throws
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [runs/home-present? (fn [_] true)
                      agent/launch! (fn [_] (throw (ex-info "boom" {})))]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "x"))
        (is (= :parked (get-in (first (session/list-sessions :brian (:id w))) [:autonomy :phase]))
            "re-parks even when the launch throws")))))

(deftest resume!-flips-running-and-spawns-turn
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})
            spawned (atom nil)]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [resume/run-turn! (fn [& args] (reset! spawned (vec args)))]
          (is (= {:resumed "auto"} (resume/resume! :brian (:id w) "go"))))
        ;; resume! sets :running synchronously before handing off to the turn.
        (is (= :running (get-in (first (session/list-sessions :brian (:id w)))
                                [:autonomy :phase])))))))

(deftest resume!-throws-when-not-parked
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (resume/resume! :brian (:id w) "go"))
            "no parked session → no resume target")))))

(deftest resume!-throws-when-no-claude-session-id
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy :autonomy autonomy-parked})
        ;; no run on disk → no recoverable claude-session-id
        (is (thrown? clojure.lang.ExceptionInfo
                     (resume/resume! :brian (:id w) "go")))))))

(deftest resume!-records-error-when-no-claude-session
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        ;; no run on disk → no recoverable claude-session-id
        (is (thrown? clojure.lang.ExceptionInfo (resume/resume! :brian (:id w) "go")))
        (is (= :no-claude-session
               (-> (first (session/list-sessions :brian (:id w))) :autonomy :error :reason))
            "the pre-flight failure is recorded on the parked session so the badge surfaces it")))))

(deftest run-turn-skips-rehydrate-when-home-present
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})
            spawned (atom 0) launched (atom nil)]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [runs/home-present? (fn [_] true)
                      runs/spawn-session-for-run! (fn [_] (swap! spawned inc))
                      agent/launch! (fn [opts] (reset! launched opts) {:exit-code 0 :num-turns 1})]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "apply"))
        (is (zero? @spawned) "home present → no re-provision")
        (is (= "sid-9" (:claude-session-id @launched)) "launches --resume")
        (is (= :parked (get-in (first (session/list-sessions :brian (:id w))) [:autonomy :phase])))))))

(deftest run-turn-rehydrates-when-home-absent
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})
            spawned (atom 0) launched (atom nil)]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [runs/home-present? (fn [_] false)
                      runs/spawn-session-for-run! (fn [_] (swap! spawned inc))
                      agent/launch! (fn [opts] (reset! launched opts) {:exit-code 0})]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "apply"))
        (is (= 1 @spawned) "home absent → re-provision once")
        (is (some? @launched) "then launches --resume")
        (is (= :parked (get-in (first (session/list-sessions :brian (:id w))) [:autonomy :phase])))))))

(deftest run-turn-records-error-on-failure
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [runs/home-present? (fn [_] true)
                      agent/launch! (fn [_] (throw (ex-info "boom" {})))]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "apply"))
        (let [auto (:autonomy (first (session/list-sessions :brian (:id w))))]
          (is (= :resume-failed (-> auto :error :reason)) "failure recorded on the session")
          (is (= "boom" (-> auto :error :message)))
          (is (= :parked (:phase auto)) "still re-parks"))))))

(deftest run-turn-rehydrate-failure-tagged
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [runs/home-present? (fn [_] false)
                      runs/spawn-session-for-run! (fn [_] (throw (ex-info "no branch" {})))
                      agent/launch! (fn [_] {:exit-code 0})]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "apply"))
        (is (= :rehydrate-failed
               (-> (first (session/list-sessions :brian (:id w))) :autonomy :error :reason))
            "a re-provision failure is tagged :rehydrate-failed")))))

(deftest run-turn-clears-error-on-success
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-parked :error {:reason :resume-failed})})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [runs/home-present? (fn [_] true)
                      agent/launch! (fn [_] {:exit-code 0})]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "apply"))
        (is (nil? (-> (first (session/list-sessions :brian (:id w))) :autonomy :error))
            "a clean turn clears the prior error")))))
