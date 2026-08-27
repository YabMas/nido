;; test/nido/coordinator/drive_test.clj
(ns nido.coordinator.drive-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.drive :as drive]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (f))
      (finally (fs/delete-tree tmp)))))

(def ^:private a-running-agent
  {:skill :plan-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :plan-bug :limits {:budget "8h"} :priority 0 :uncapped? false
   :on-promote nil :phase :running
   :phase-history [{:at "2026-08-27T00:00:00Z" :phase :running}] :error nil})

(defn- a-ws []
  (:id (ws/create! :brian {:stage :in-progress :external-refs []})))

;; ── What a halt says ────────────────────────────────────────────────────────

(deftest a-halt-carries-the-terminal-verbatim
  ;; So the record a human reads and the value the driver classified are one
  ;; thing, and the outcome can be looked up rather than guessed at.
  (let [h (drive/halt-for {:stage :decide-design :outcome :disputed
                           :tried [{:stage :decide-design :outcome :disputed :rounds 4}]})]
    (is (= :disputed (:outcome (first (:tried h)))))
    (is (= 4 (:rounds (first (:tried h)))))))

(deftest a-halt-with-no-stated-question-asks-an-honest-one
  ;; Better than a confident sentence nobody derived: the driver does not know
  ;; what the round could not settle, and should not invent it.
  (let [h (drive/halt-for {:stage :verify-survey :outcome :disputed})]
    (is (str/includes? (:summary h) "verify-survey"))
    (is (str/includes? (:summary h) "disputed"))
    (is (str/includes? (:needs h) "everything derivable"))))

(deftest a-zero-round-attempt-does-not-claim-a-round
  ;; A stage that could not start ran no rounds, and saying "after 0 rounds"
  ;; would be a claim about work that did not happen.
  (is (nil? (:rounds (drive/attempt {:stage :design :outcome :no-record :rounds 0}))))
  (is (nil? (:rounds (drive/attempt {:stage :design :outcome :no-record})))))

;; ── Parking ─────────────────────────────────────────────────────────────────

(deftest parking-writes-the-halt-to-the-ledger
  (with-tmp
    (fn []
      (let [id (a-ws)
            r  (drive/park! :brian id
                            {:stage :decide-design :outcome :disputed
                             :needs "which way to cut phase three"
                             :tried [{:stage :decide-design :outcome :disputed :rounds 4}]})]
        (is (some? (:seq r)))
        (let [e (ws/latest-entry :brian id :blocker)]
          (is (= :blocker (:format e)))
          (is (= "which way to cut phase three" (:needs e)))
          (is (= :disputed (:outcome (first (:tried e))))))))))

(deftest parking-puts-a-live-autonomous-session-to-sleep
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (session/create! :brian id {:name "auto" :weight :heavy
                                    :autonomy a-running-agent})
        (let [r (drive/park! :brian id {:stage :verify-survey :outcome :disputed})]
          (is (= "auto" (:parked r)))
          (is (= :parked (get-in (first (session/list-sessions :brian id))
                                 [:autonomy :phase]))))))))

(deftest parking-still-records-when-there-is-no-session-to-park
  ;; A mechanical stage runs as a task, not an agent, so there is often nobody to
  ;; put to sleep. Failing the park would throw away the record that matters.
  (with-tmp
    (fn []
      (let [id (a-ws)
            r  (drive/park! :brian id {:stage :verify-survey :outcome :disputed})]
        (is (nil? (:parked r)))
        (is (some? (:seq r)) "the ledger entry is the durable half")
        (is (some? (ws/latest-entry :brian id :blocker)))))))

(deftest a-halt-the-ledger-would-refuse-is-refused-here-instead-of-thrown
  ;; A park that threw would leave the workstream running with its stage already
  ;; finished — worse than one that stopped and said it could not explain itself.
  (with-tmp
    (fn []
      (let [id (a-ws)
            r  (drive/park! :brian id {:stage :design :outcome :disputed
                                       :options [{:label "only one branch"
                                                  :summary "a choice needs two"}]})]
        (is (= :invalid-halt (:refused r)))
        (is (nil? (ws/latest-entry :brian id :blocker)) "and nothing was written")))))

;; ── Only :escalate parks ────────────────────────────────────────────────────

(deftest only-an-escalate-parks-and-the-others-say-what-they-are
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (is (= :advance (:disposition (drive/park-on-escalate!
                                       :brian id {:stage :verify-survey
                                                  :outcome :sufficient}))))
        (is (nil? (ws/latest-entry :brian id :blocker))
            "a survey that held is not a halt")

        (is (= :retry (:disposition (drive/park-on-escalate!
                                     :brian id {:stage :verify-survey
                                                :outcome :codex-failed}))))
        (is (nil? (ws/latest-entry :brian id :blocker))
            "the machinery failing is not a person's business yet")

        (is (= :route-back (:disposition (drive/park-on-escalate!
                                          :brian id {:stage :review-implementation
                                                     :outcome :escalated}))))
        (is (nil? (ws/latest-entry :brian id :blocker))
            "a finding that indicts the design routes back, it does not stop")

        (let [r (drive/park-on-escalate! :brian id {:stage :decide-design
                                                    :outcome :proceed})]
          (is (= :escalate (:disposition r)))
          (is (some? (:seq r)))
          (is (some? (ws/latest-entry :brian id :blocker))))))))
