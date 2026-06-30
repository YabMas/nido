(ns nido.coordinator.ship-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is use-fixtures]]
   [nido.coordinator.executor :as ex]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.ship :as sut]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.status-file :as status-file]
   [nido.coordinator.workstream :as ws]))

;; Redirect ~/.nido to a temp dir for the duration of each test, and reset
;; the executor — same pattern as spawn_test.clj (with-tmp) +
;; executor_test.clj (reset-executor!).
(defn- each-fixture [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (ex/configure! {:global-cap 2})
      (ex/clear!)
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(use-fixtures :each each-fixture)

(deftest handle-ship-advances-stage-and-creates-one-merge-run
  (let [submitted (atom [])
        w (ws/create! :brian {:stage :in-progress
                              :external-refs [{:adapter :notion :id "BR-42"}]})]
    (with-redefs [ex/submit! (fn [& a] (swap! submitted conj a))]
      (let [run (sut/handle-ship! {:type :ship :project :brian
                                   :session "impl-br-42" :ws-id (:id w)})]
        ;; stage flipped
        (is (= :shipping (:stage (ws/read-ws :brian (:id w)))))
        ;; one merge run, correct shape
        (is (= :merge      (:trigger run)))
        (is (= :drive-home (:skill run)))
        (is (true?         (:uncapped? run)))
        (is (= "impl-br-42" (:session-name run)))
        (is (= "BR-42"     (-> run :event-payload :id)))
        (is (= "/drive-home" (:first-message run)))
        ;; submitted uncapped to the :merge lane at max-in-flight 1
        (is (= 1 (count @submitted)))
        (let [[_rid _prio uncapped? trigger mif] (first @submitted)]
          (is (true? uncapped?)) (is (= :merge trigger)) (is (= 1 mif)))))))

(deftest handle-ship-is-idempotent-while-in-flight
  (let [w (ws/create! :brian {:stage :in-progress
                              :external-refs [{:adapter :notion :id "BR-7"}]})]
    (with-redefs [ex/submit! (fn [& _] nil)]
      (let [r1 (sut/handle-ship! {:type :ship :project :brian :session "impl-br-7" :ws-id (:id w)})]
        ;; r1 run is :queued → not in-progress yet; force it in-flight:
        (runs/transition! (:id r1) :running)
        (let [r2 (sut/handle-ship! {:type :ship :project :brian :session "impl-br-7" :ws-id (:id w)})]
          (is (nil? r2)))))))

(deftest handle-ship-allows-second-call-while-queued
  (let [w (ws/create! :brian {:stage :in-progress
                              :external-refs [{:adapter :notion :id "BR-99"}]})]
    (with-redefs [ex/submit! (fn [& _] nil)]
      (sut/handle-ship! {:type :ship :project :brian :session "impl-br-99" :ws-id (:id w)})
      ;; r1 is still :queued (never transitioned in-progress) — second call must NOT return nil
      (let [r2 (sut/handle-ship! {:type :ship :project :brian :session "impl-br-99" :ws-id (:id w)})]
        (is (some? r2))))))

(deftest classify-reads-ledger-fingerprint
  (with-redefs [tickets/read-meta (fn [_ _] {:entries [{:kind :implementation-completed}]})]
    (is (= :awaiting-merge (sut/classify-outcome :brian "BR-1" "r1" {:exit-code 0 :num-turns 5}))))
  (with-redefs [tickets/read-meta (fn [_ _] {:entries [{:kind :blocker}]})]
    (is (= :blocked (sut/classify-outcome :brian "BR-1" "r1" {:exit-code 0 :num-turns 5})))))

(deftest classify-hard-failures-are-blocked
  (is (= :blocked (sut/classify-outcome :brian "BR-1" "r1" {:timed-out? true :exit-code 143})))
  (is (= :blocked (sut/classify-outcome :brian "BR-1" "r1" {:spawn-error true})))
  ;; exit 0 but zero turns = no-op (e.g. "Unknown command")
  (is (= :blocked (sut/classify-outcome :brian "BR-1" "r1" {:exit-code 0 :num-turns 0}))))

(deftest classify-falls-back-to-run-status-then-blocked
  ;; no BR / no ledger entry → consult run-status file
  (with-redefs [tickets/read-meta   (fn [_ _] nil)
                status-file/read-status (fn [_] {:phase :complete})]
    (is (= :awaiting-merge (sut/classify-outcome :brian nil "r1" {:exit-code 0 :num-turns 3}))))
  (with-redefs [tickets/read-meta   (fn [_ _] nil)
                status-file/read-status (fn [_] nil)]
    (is (= :blocked (sut/classify-outcome :brian nil "r1" {:exit-code 0 :num-turns 3})))))
