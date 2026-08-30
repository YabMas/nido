(ns nido.coordinator.lane.github-issue-intake-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.lane.github-issue-intake :as intake]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.source.state :as sstate]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.github.client :as gh]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(def ^:private cfg {:repo "o/r" :issues {:assignee "@me"}})

(defn- issue-ids [project]
  (->> (ws/list-ids project) (map #(ws/read-ws project %))
       (keep (fn [w] (some #(when (= :github-issue (:adapter %)) (:id %)) (:external-refs w))))
       set))

(deftest cold-start-creates-a-ready-workstream-per-assigned-issue
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues
                    (fn [_ _] {:status :ok :issues [{:number 1 :url "u1" :title "a"}
                                                    {:number 2 :url "u2" :title "b"}]})]
        (intake/poll-and-reconcile! :brian cfg)
        (is (= #{"o/r#1" "o/r#2"} (issue-ids :brian)))
        (let [w (ws/find-by-ref :brian :github-issue "o/r#1")]
          (is (= :ready (:stage w)))
          (is (= "a" (some #(when (= :github-issue (:adapter %)) (:title %)) (:external-refs w)))))))))

(deftest re-poll-is-idempotent
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues [{:number 1}]})]
        (intake/poll-and-reconcile! :brian cfg)
        (intake/poll-and-reconcile! :brian cfg)
        (is (= 1 (count (ws/list-ids :brian))))))))

(deftest unassigned-unpromoted-issue-is-dropped
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues [{:number 1}]})]
        (intake/poll-and-reconcile! :brian cfg))
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues []})]
        (intake/poll-and-reconcile! :brian cfg))
      (is (empty? (ws/list-ids :brian))))))

(deftest unassigned-promoted-issue-is-kept
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues [{:number 1}]})]
        (intake/poll-and-reconcile! :brian cfg))
      (let [ws-id (:id (ws/find-by-ref :brian :github-issue "o/r#1"))]
        (ws/advance-stage! :brian ws-id :in-progress)
        (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues []})]
          (intake/poll-and-reconcile! :brian cfg))
        (is (some? (ws/read-ws :brian ws-id)) "promoted ⇒ left alone")))))

(deftest auth-error-trips-the-breaker
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:error :auth})]
        (intake/poll-and-reconcile! :brian cfg))
      (is (= :open (:breaker (sstate/read-state (#'intake/state-key :brian))))))))

(deftest open-breaker-within-cooldown-skips-the-poll
  (with-tmp
    (fn [_]
      ;; Pre-seed an open breaker opened "now"; the poll must NOT call gh.
      (sstate/write-state! (#'intake/state-key :brian)
                           {:type :github-issues :project :brian
                            :breaker :open :breaker-opened-at (clock/now-iso)})
      (let [called (atom false)]
        (with-redefs [gh/list-assigned-issues (fn [_ _] (reset! called true) {:status :ok :issues []})]
          (intake/poll-and-reconcile! :brian cfg))
        (is (false? @called) "breaker open + cooldown not elapsed ⇒ no gh call")))))

(deftest three-non-auth-failures-trip-the-breaker
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:error :gh})]
        (intake/poll-and-reconcile! :brian cfg)   ; 1
        (is (nil? (:breaker (sstate/read-state (#'intake/state-key :brian)))) "below threshold")
        (intake/poll-and-reconcile! :brian cfg)   ; 2
        (intake/poll-and-reconcile! :brian cfg))   ; 3 ⇒ trips
      (let [st (sstate/read-state (#'intake/state-key :brian))]
        (is (= :open (:breaker st)))
        (is (= 3 (:consecutive-failures st)))))))

(deftest unpromoted-considers-sessions-not-just-stage
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues [{:number 1}]})]
        (intake/poll-and-reconcile! :brian cfg))
      (let [ws-id (:id (ws/find-by-ref :brian :github-issue "o/r#1"))]
        ;; still :ready but now has a session ⇒ promoted ⇒ must be kept on unassign
        (session/create! :brian ws-id {:name "impl-1" :weight :light :autonomy nil})
        (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues []})]
          (intake/poll-and-reconcile! :brian cfg))
        (is (some? (ws/read-ws :brian ws-id)) ":ready + has-session ⇒ kept")))))
