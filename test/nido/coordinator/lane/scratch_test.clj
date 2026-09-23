(ns nido.coordinator.lane.scratch-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.scratch :as scratch]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as workstream]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
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

(deftest birth-onto-a-named-workstream-mints-nothing
  (with-tmp
    (fn [_]
      (let [child (:id (workstream/create! :brian {:stage :in-progress :external-refs []}))]
        (is (= child (scratch/birth! :brian "child-work" nil child)))
        (is (= [child] (workstream/list-ids :brian)) "no one-off minted")
        (is (= child (session/workstream-id-for :brian "child-work")))
        (is (= :light (:weight (session/read-session :brian child "child-work"))))
        (testing "run again after provisioning, it only reconciles the weight"
          (is (= child (scratch/birth! :brian "child-work" :heavy child)))
          (is (= :heavy (:weight (session/read-session :brian child "child-work"))))
          (is (= [child] (workstream/list-ids :brian))))
        (testing "an unnamed start of the same session joins its holder"
          (is (= child (scratch/birth! :brian "child-work" :heavy))))))))

(deftest a-start-that-cannot-join-is-refused-and-writes-nothing
  (with-tmp
    (fn [_]
      (let [held-by (scratch/birth! :brian "taken" :light)
            other   (:id (workstream/create! :brian {:stage :in-progress :external-refs []}))
            closed  (:id (workstream/create! :brian {:stage :in-progress :external-refs []}))
            refusal (fn [name ws-id]
                      (try (scratch/birth! :brian name nil ws-id) nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))]
        (workstream/close! :brian closed :done)
        (is (= {:reason :name-held :holder held-by}
               (select-keys (refusal "taken" other) [:reason :holder])))
        (is (= :closed (:reason (refusal "fresh" closed))))
        (is (= :no-such-workstream (:reason (refusal "fresh" "ws-nope"))))
        (is (every? #(= :join (:refused (apply refusal %))) [["taken" other] ["fresh" closed]]))
        (is (empty? (session/list-sessions :brian other)))
        (is (empty? (session/list-sessions :brian closed)))
        (is (nil? (scratch/joinable :brian "taken" held-by)) "the holder itself is joinable — same start again")))))

(deftest a-one-off-that-loses-the-name-is-not-left-behind
  (with-tmp
    (fn [_]
      (let [holder (:id (workstream/create! :brian {:stage :in-progress :external-refs []}))]
        ;; The claim lands between birth!'s lookup and its create — as a named start's would.
        (with-redefs [session/workstream-id-for (fn [& _] nil)]
          (session/create! :brian holder {:name "raced" :weight :light :autonomy nil})
          (is (= holder (scratch/birth! :brian "raced" :light))))
        (is (= [holder] (workstream/list-ids :brian)) "the one-off minted for it is deleted")))))

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
