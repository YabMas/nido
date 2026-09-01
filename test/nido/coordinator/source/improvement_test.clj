(ns nido.coordinator.source.improvement-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.proposal :as proposal]
   [nido.coordinator.source.improvement :as improvement]))

(defn- p
  "A proposal row, as `record.proposal/of-project` emits one."
  [ws-id seq obs & {:keys [verdict landed at]}]
  (cond-> {:ws-id ws-id :analysis-seq seq :observation obs
           :at (or at "2026-08-31T22:56:06Z")
           :kind :misfire :where "stages/run-fix-stage"
           :summary "the rebase is never checked" :evidence "round 3"
           :proposal "call layers/conflicted"}
    verdict (assoc :decision {:verdict verdict :decided-by "yabmas"})
    landed  (assoc :landed {:rev landed :landed-by "yabmas"})))

(deftest an-approved-proposal-nobody-has-implemented-is-what-fires
  (let [pick (proposal/next-to-implement
              [(p "ws-a" 1 0 :verdict :approved)] [])]
    (is (= ["ws-a" 1 0] ((juxt :ws-id :analysis-seq :observation) pick)))))

(deftest nothing-fires-for-a-proposal-that-owes-nothing
  (is (nil? (proposal/next-to-implement [(p "ws-a" 1 0)] []))
      "undecided — nobody has approved it")
  (is (nil? (proposal/next-to-implement [(p "ws-a" 1 0 :verdict :declined)] []))
      "declined")
  (is (nil? (proposal/next-to-implement
             [(p "ws-a" 1 0 :verdict :approved :landed "qlosnwus")] []))
      "approved and already discharged"))

(deftest an-open-attempt-holds-everything-including-unrelated-proposals
  ;; The one-at-a-time rule. It cannot be :max-in-flight — gating-phases is
  ;; #{:preprocessing :running :parked}, so a session that FAILS releases the
  ;; slot and the next poll starts a second improvement on top of a branch the
  ;; first one abandoned mid-edit.
  (let [ps [(p "ws-a" 1 0 :verdict :approved) (p "ws-b" 1 0 :verdict :approved)]]
    (is (nil? (proposal/next-to-implement
               ps [{:address "ws-a/1.0" :ws-id "ws-i" :open? true}]))
        "the held proposal AND the one beside it — the hold is on the pipeline,
         not on the proposal")
    (is (some? (proposal/next-to-implement
                ps [{:address "ws-a/1.0" :ws-id "ws-i" :open? false}]))
        "and a closed attempt releases it")))

(deftest a-closed-attempt-that-landed-nothing-does-not-fire-again
  ;; A session that gave up. Re-firing would spend an agent budget every poll
  ;; for as long as the ledger stands; the row stays visibly approved-and-not-
  ;; implemented on the board instead, and a human re-fires it by hand.
  (let [ps [(p "ws-a" 1 0 :verdict :approved)]]
    (is (nil? (proposal/next-to-implement
               ps [{:address "ws-a/1.0" :ws-id "ws-i" :open? false}])))))

(deftest the-oldest-approved-proposal-goes-first
  ;; A backlog drained newest-first lets a busy week starve one indefinitely.
  (let [ps [(p "ws-b" 1 0 :verdict :approved :at "2026-08-31T00:00:00Z")
            (p "ws-a" 1 0 :verdict :approved :at "2026-08-01T00:00:00Z")]]
    (is (= "ws-a" (:ws-id (proposal/next-to-implement ps []))))))

(deftest a-poll-emits-at-most-one-event
  ;; Provisioning mints workstream, run and a :queued session at DRAIN, before
  ;; any scheduling gate — so two envelopes drained in one tick become two
  ;; improvement sessions whatever any cap says. Emitting one is the only point
  ;; at which the rule can be enforced.
  (let [emitted (atom [])]
    (with-redefs [proposal/of-project (fn [_] [(p "ws-a" 1 0 :verdict :approved)
                                               (p "ws-b" 1 0 :verdict :approved)
                                               (p "ws-c" 1 0 :verdict :approved)])
                  proposal/attempts   (fn [_] [])]
      (let [st (improvement/poll-once! {:type :improvement :project :nido}
                                       #(swap! emitted conj %))]
        (is (= 1 (count @emitted)) "three are owed and one is emitted")
        (is (= "ws-a/1.0" (:emitted st)))))))

(deftest a-held-poll-emits-nothing-and-says-what-is-holding-it
  ;; The honest failure mode is silence: held and idle look identical from
  ;; outside, and only one of them needs somebody to go and clear it.
  (let [emitted (atom [])]
    (with-redefs [proposal/of-project (fn [_] [(p "ws-a" 1 0 :verdict :approved)])
                  proposal/attempts   (fn [_] [{:address "ws-a/1.0" :ws-id "ws-improve"
                                                :open? true}])]
      (let [st (improvement/poll-once! {:type :improvement :project :nido}
                                       #(swap! emitted conj %))]
        (is (empty? @emitted))
        (is (= "ws-improve" (:held-by st)))
        (is (nil? (:emitted st)))))))

(deftest the-payload-dedups-on-the-proposals-own-address
  ;; What makes the hold work: a re-emit lands back in the workstream that
  ;; already holds the attempt rather than starting a second one. The adapter is
  ;; named because spawn/external-ref defaults it to :notion.
  (let [pl (improvement/payload (p "ws-a" 3 2 :verdict :approved))]
    (is (= :improvement (:adapter pl)))
    (is (= "ws-a/3.2" (:id pl)))
    (is (= "call layers/conflicted" (:fix pl)))
    (is (= "round 3" (:evidence pl))
        "the observation rides whole — the run dir it came from may be gone")))
