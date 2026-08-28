;; test/nido/coordinator/drive_test.clj
(ns nido.coordinator.drive-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.drive :as drive]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.pipeline :as pipeline]))

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

(def ^:private a-baseline
  {:format :baseline :area "a" :bounded-by "b" :shape "s"
   :modules [{:id "m" :module "m" :hides "h" :interface "i"}]
   :composition "c"
   :load-bearing [{:id "c1" :property "p" :falsified-by "f"}]
   :read ["src/a.clj"]})

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
  (let [h (drive/halt-for {:stage :verify-baseline :outcome :disputed})]
    (is (str/includes? (:summary h) "verify-baseline"))
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
        (let [r (drive/park! :brian id {:stage :verify-baseline :outcome :disputed})]
          (is (= "auto" (:parked r)))
          (is (= :parked (get-in (first (session/list-sessions :brian id))
                                 [:autonomy :phase]))))))))

(deftest parking-still-records-when-there-is-no-session-to-park
  ;; A mechanical stage runs as a task, not an agent, so there is often nobody to
  ;; put to sleep. Failing the park would throw away the record that matters.
  (with-tmp
    (fn []
      (let [id (a-ws)
            r  (drive/park! :brian id {:stage :verify-baseline :outcome :disputed})]
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
                                       :brian id {:stage :verify-baseline
                                                  :outcome :sufficient}))))
        (is (nil? (ws/latest-entry :brian id :blocker))
            "a baseline that held is not a halt")

        (is (= :retry (:disposition (drive/park-on-escalate!
                                     :brian id {:stage :verify-baseline
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

;; ── The allow-list is the safety ────────────────────────────────────────────

(deftest nobody-is-driven-by-default
  ;; Landing the driver must not start advancing every open workstream at once.
  (with-tmp (fn [] (is (= #{} (drive/driven))))))

(deftest a-workstream-is-driven-only-once-named-and-stops-when-unnamed
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (is (false? (drive/driving? :brian id)))
        (drive/drive! :brian id)
        (is (true? (drive/driving? :brian id)))
        (drive/undrive! :brian id)
        (is (false? (drive/driving? :brian id))
            "the cheapest undo a phase with none can have")))))

;; ── What the driver decides to fire ─────────────────────────────────────────

(deftest only-a-mechanical-stage-with-a-runner-is-fired
  (is (= :verify-baseline (:fire (drive/fireable
                                {:next {:stage :verify-baseline :mode :mechanical}}))))
  (is (= :decide-design (:fire (drive/fireable
                                {:next {:stage :decide-design :mode :mechanical}})))))

(deftest the-driver-declines-everything-it-is-not-for
  (is (= :terminal (:skip (drive/fireable {:next nil}))))
  (is (= :waiting-on-a-human
         (:skip (drive/fireable {:next {:stage :approve-design :mode :human}})))
      "a human gate is not something to fire past")
  (is (= :not-mechanical
         (:skip (drive/fireable {:next {:stage :write-baseline :mode :authoring}})))
      "authoring is a later phase")
  (is (= :not-mechanical
         (:skip (drive/fireable {:next {:stage :implement :mode :working-copy}}))))
  (is (= :no-runner
         (:skip (drive/fireable {:next {:stage :publish-draft-pr :mode :mechanical}})))
      "the projection can name a stage this phase cannot run"))

;; ── Firing ──────────────────────────────────────────────────────────────────

(deftest the-tick-fires-nothing-for-a-workstream-nobody-named
  (with-tmp
    (fn []
      (let [id (a-ws)
            submitted (atom [])]
        (ws/append-entry! :brian id {:kind :intent}
                          (pr-str {:format :intent :goal "g" :done-when ["d"]}))
        (is (= [] (drive/tick! #(swap! submitted conj %))))
        (is (= [] @submitted))))))

(deftest the-tick-submits-one-run-and-does-not-wait-for-it
  ;; Fire-and-forget is what keeps a driven chain from wedging: with
  ;; :global-parallel-cap at 2, a driver that held a slot while waiting on one
  ;; would deadlock against a second chain doing the same.
  (with-tmp
    (fn []
      (let [id (a-ws)
            submitted (atom [])]
        (session/create! :brian id {:name "auto" :weight :heavy
                                    :autonomy a-running-agent})
        (ws/append-entry! :brian id {:kind :intent}
                          (pr-str {:format :intent :goal "g" :done-when ["d"]}))
        (ws/append-entry! :brian id {:kind :baseline} (pr-str a-baseline))
        (drive/drive! :brian id)
        (let [out (drive/tick! #(swap! submitted conj %))]
          (is (= 1 (count out)))
          (is (= :verify-baseline (:fired (first out)))
              "a baseline with no verdict is at :baselined, whose next act is mechanical")
          (is (= 1 (count @submitted)))
          (is (= :drive (:trigger (first @submitted))))
          (is (false? (:uncapped? (first @submitted)))
              "a driven stage takes a slot like everything else"))))))

(deftest the-tick-does-not-fire-a-second-stage-while-one-is-running
  ;; The projection still reports the OLD position until the stage writes its
  ;; record, so an unguarded tick would re-fire the same stage every second.
  (with-tmp
    (fn []
      (let [id (a-ws)
            submitted (atom [])]
        (session/create! :brian id {:name "auto" :weight :heavy
                                    :autonomy a-running-agent})
        (ws/append-entry! :brian id {:kind :intent}
                          (pr-str {:format :intent :goal "g" :done-when ["d"]}))
        (ws/append-entry! :brian id {:kind :baseline} (pr-str a-baseline))
        (drive/drive! :brian id)
        (drive/tick! #(swap! submitted conj %))
        (let [out (drive/tick! #(swap! submitted conj %))]
          (is (= :already-running (:skipped (first out))))
          (is (= 1 (count @submitted)) "still one, not two"))))))

;; ── Retries are bounded ─────────────────────────────────────────────────────

(defn- stage-returning
  "A mechanical stage whose task returns the given statuses in turn, counting
   calls. Sleeps are captured rather than taken."
  [statuses]
  (let [calls (atom 0) slept (atom [])]
    {:calls calls :slept slept
     :run (fn [project ws-id]
            (with-redefs [drive/mechanical-stages
                          {:verify-baseline {:task ::fake :label "baseline"}}
                          requiring-resolve
                          (fn [_] (fn [_] (let [i @calls]
                                            (swap! calls inc)
                                            (nth statuses (min i (dec (count statuses)))))))]
              ;; :cwd injected — a real session home is not what these tests
              ;; are about, and run-stage!'s own no-session branch is covered
              ;; separately.
              (drive/run-stage! project ws-id :verify-baseline
                                {:cwd "/tmp" :sleep-fn #(swap! slept conj %)})))}))

(deftest a-machine-failure-is-retried-and-then-stops
  ;; :codex-failed says the machinery failed, not that the round decided
  ;; something. Trying again is right; trying forever is not.
  (with-tmp
    (fn []
      (let [id (a-ws)
            f  (stage-returning [:codex-failed])
            r  ((:run f) :brian id)]
        (is (= drive/max-attempts @(:calls f)) "tried exactly max-attempts times")
        (is (true? (:exhausted? r)))
        (is (= 2 (count @(:slept f))) "backed off between attempts, not after the last")
        (is (some? (:seq r)) "and parked")
        (let [e (ws/latest-entry :brian id :blocker)]
          (is (= 3 (count (:tried e))) "every attempt is on the halt")
          (is (str/includes? (:summary e) "failed to run 3 times"))
          (is (str/includes? (:needs e) "machinery")))))))

(deftest a-transient-failure-that-clears-is-not-parked
  (with-tmp
    (fn []
      (let [id (a-ws)
            f  (stage-returning [:codex-failed :sufficient])
            r  ((:run f) :brian id)]
        (is (= 2 @(:calls f)) "one retry, then it worked")
        (is (nil? (:exhausted? r)))
        (is (= :advance (:disposition r)))
        (is (nil? (ws/latest-entry :brian id :blocker))
            "nothing to tell a human about")))))

(deftest a-decided-round-is-never-run-twice
  ;; :disputed is the round SAYING something. Retrying it would run a decided
  ;; stage again and could only produce the same answer.
  (with-tmp
    (fn []
      (let [id (a-ws)
            f  (stage-returning [:disputed])
            r  ((:run f) :brian id)]
        (is (= 1 @(:calls f)))
        (is (= [] @(:slept f)) "no backoff for something that was not a failure")
        (is (= :escalate (:disposition r)))
        (is (some? (ws/latest-entry :brian id :blocker)))))))

(deftest parking-is-itself-the-stop
  ;; The driver needs no rule about leaving parked workstreams alone: a halt
  ;; makes the position :blocked, whose next action is a person's, so fireable
  ;; skips it. This is what bounds a retry loop across ticks as well as within
  ;; one — once it parks, nothing fires it again.
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (ws/append-entry! :brian id {:kind :intent}
                          (pr-str {:format :intent :goal "g" :done-when ["d"]}))
        (ws/append-entry! :brian id {:kind :baseline} (pr-str a-baseline))
        (is (= :verify-baseline (:fire (drive/fireable (pipeline/of :brian id))))
            "fireable before the halt")
        (drive/park! :brian id {:stage :verify-baseline :outcome :codex-failed})
        (let [pos (pipeline/of :brian id)]
          (is (= :blocked (:at pos)))
          (is (= :waiting-on-a-human (:skip (drive/fireable pos)))))))))
