(ns nido.coordinator.scratch-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as workstream]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest birth-creates-loose-workstream-with-human-session
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot" :light)]
        (is (some? ws-id))
        (let [w (workstream/read-ws :brian ws-id)]
          (is (scratch/scratch? w) "no external refs")
          (is (= :scratch (:stage w))))
        (let [ss (session/list-sessions :brian ws-id)]
          (is (= ["refshot"] (mapv :name ss)))
          (is (nil? (:autonomy (first ss))) "human session"))))))

(deftest birth-is-idempotent
  (with-tmp
    (fn [_]
      (let [a (scratch/birth! :brian "refshot" :light)
            b (scratch/birth! :brian "refshot" :light)]
        (is (= a b) "same ws-id, no second workstream")
        (is (= 1 (count (workstream/list-ids :brian))))
        (is (= 1 (count (session/list-sessions :brian a))))))))

(deftest birth-stamps-the-provisioned-weight
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "impl-x" :heavy)]
        (is (= [:heavy] (mapv :weight (session/list-sessions :brian ws-id)))
            "a session provisioned with services is the workstream's environment")))))

(deftest birth-reconciles-a-stale-weight-on-an-owned-session
  ;; birth! is the reconcile point, not just the create point: every manual
  ;; session born before the weight was derived carries :light, and the orphan
  ;; sweep re-runs birth! on it. A wrong weight must heal, not persist.
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "impl-x" :light)]
        (is (= ws-id (scratch/birth! :brian "impl-x" :heavy)) "same workstream")
        (is (= 1 (count (workstream/list-ids :brian))))
        (is (= [:heavy] (mapv :weight (session/list-sessions :brian ws-id))))))))

(deftest birth-leaves-a-stored-weight-alone-when-provisioning-is-unknown
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "impl-x" :heavy)]
        (scratch/birth! :brian "impl-x" nil)
        (is (= [:heavy] (mapv :weight (session/list-sessions :brian ws-id)))
            "nil ⇒ no profile.edn to read; the stored weight stands")))))

(deftest birth-falls-back-to-light-when-provisioning-is-unknown-at-creation
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "ghost" nil)]
        (is (= [:light] (mapv :weight (session/list-sessions :brian ws-id)))
            "unknown at birth ⇒ the conservative weight")))))

(deftest find-ws-for-session-locates-owner
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot" :light)]
        (is (= ws-id (#'scratch/find-ws-for-session :brian "refshot")))
        (is (nil? (#'scratch/find-ws-for-session :brian "nope")))))))

(deftest reap-deletes-a-bare-loose-workstream
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot" :light)]
        (scratch/reap! :brian "refshot")
        (is (nil? (workstream/read-ws :brian ws-id)))
        (is (empty? (workstream/list-ids :brian)))))))

(deftest reap-spares-a-workstream-with-a-ref
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot" :light)]
        (workstream/add-ref! :brian ws-id {:adapter :notion :id "BR-1"})
        (scratch/reap! :brian "refshot")
        (is (some? (workstream/read-ws :brian ws-id)) "ref => not reaped")))))

(deftest reap-spares-a-workstream-with-entries
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot" :light)]
        (workstream/append-entry! :brian ws-id {:kind :note} "hi")
        (scratch/reap! :brian "refshot")
        (is (some? (workstream/read-ws :brian ws-id)) "entry => not reaped")))))

(deftest reap-spares-a-workstream-with-another-session
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot" :light)]
        (session/create! :brian ws-id {:name "sibling" :weight :light :autonomy nil})
        (scratch/reap! :brian "refshot")
        (is (some? (workstream/read-ws :brian ws-id)) "other session ⇒ not reaped")))))

(deftest reap-is-a-noop-when-absent
  (with-tmp
    (fn [_]
      (is (nil? (scratch/reap! :brian "ghost"))))))

(deftest birth-is-idempotent-for-slash-names
  (with-tmp
    (fn [_]
      (let [a (scratch/birth! :brian "feat/x" :light)
            b (scratch/birth! :brian "feat/x" :light)]
        (is (= a b) "slash-named session: same ws-id, no duplicate workstream")
        (is (= 1 (count (workstream/list-ids :brian))))))))
