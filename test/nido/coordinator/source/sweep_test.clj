(ns nido.coordinator.source.sweep-test
  "The sweep's firing policy, exercised without a coordinator.

   What these decide is which single envelope reaches the drain, and the drain
   mints a workstream, a run and a session from it before any scheduling gate
   runs — so at-most-one-per-poll is not a preference here, it is the only place
   the rule can be enforced."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.clock]
   [nido.coordinator.record.proposal :as proposal]
   [nido.coordinator.source.sweep :as sweep]))

(defn- plan [ws-id seq date & claims]
  {:format :improvement-plan :date date :ws-id ws-id :seq seq
   :frontier {:proposals [] :attempts []}
   :claims (vec claims)})

(defn- claim [disposition statement & addresses]
  (cond-> {:statement statement :disposition disposition :addresses (vec addresses)}
    (= :file disposition) (assoc :ref "FU-99")))

(defn- attempt [address & {:keys [open?]}]
  {:address address :ws-id (str "ws-" address) :open? (boolean open?)})

;; ── the day ─────────────────────────────────────────────────────────────────

(deftest a-plan-is-for-a-calendar-day-not-a-moment
  (is (= "2026-09-04" (sweep/day-of "2026-09-04T07:15:22.101Z"))))

(deftest todays-plan-is-found-by-its-day
  (let [ps [(plan "ws-p" 1 "2026-09-03") (plan "ws-p" 2 "2026-09-04")]]
    (is (= 2 (:seq (sweep/plan-for ps "2026-09-04"))))
    (is (nil? (sweep/plan-for ps "2026-09-05")))))

;; ── which claim fires ───────────────────────────────────────────────────────

(deftest the-first-unattempted-land-claim-fires
  (let [p (plan "ws-p" 7 "2026-09-04"
                (claim :land "make the cache skip" "ws-a/1.1")
                (claim :land "stamp the reviewer rev" "ws-b/1.0"))]
    (is (= 0 (:index (sweep/next-claim p "ws-p" []))))))

(deftest a-no-op-claim-never-spawns-a-session
  ;; This is the waste the design exists to remove: a proposal whose own text
  ;; says no change is needed would otherwise get a worktree and a budget.
  (let [p (plan "ws-p" 7 "2026-09-04"
                (claim :no-op "already on main" "ws-a/1.0")
                (claim :land "real work" "ws-b/1.0"))]
    (is (= 1 (:index (sweep/next-claim p "ws-p" []))))))

(deftest a-file-claim-never-spawns-a-session-either
  (let [p (plan "ws-p" 7 "2026-09-04" (claim :file "not ours to make" "ws-a/1.0"))]
    (is (nil? (sweep/next-claim p "ws-p" [])))))

(deftest a-claim-already-attempted-does-not-fire-again
  ;; Attempted, not open: a claim whose workstream exists has had its session,
  ;; and re-firing would spend a budget every poll for as long as the ledger stands.
  (let [p (plan "ws-p" 7 "2026-09-04" (claim :land "one" "ws-a/1.0"))
        at [(attempt (proposal/claim-address "ws-p" 7 0) :open? false)]]
    (is (nil? (sweep/next-claim p "ws-p" at)))))

(deftest a-discharged-plan-fires-nothing
  (let [p (plan "ws-p" 7 "2026-09-04"
                (claim :land "one" "ws-a/1.0") (claim :no-op "two" "ws-b/1.0"))
        at [(attempt (proposal/claim-address "ws-p" 7 0) :open? false)]]
    (is (nil? (sweep/next-claim p "ws-p" at)))))

;; ── what the envelope carries ───────────────────────────────────────────────

(deftest a-claim-envelope-is-addressed-by-the-claim-and-carries-it-whole
  (let [c (claim :land "make the answered-cache actually skip" "ws-a/1.1" "ws-b/1.2")
        pl (sweep/claim-payload c "ws-p" 7 {:index 0})]
    (is (= "ws-p/7#0" (:id pl)))
    (is (= :improvement (:adapter pl)) "the same adapter the retired source used, so the hold spans the cutover")
    (is (= "ws-a/1.1,ws-b/1.2" (:addresses pl)))
    (is (= "2" (:covers pl)))))

(deftest a-planning-envelope-is-addressed-by-the-day
  ;; Content-addressed on the date, so a re-emit before the drain has minted the
  ;; workstream lands on the same one rather than starting a second plan.
  (let [pl (sweep/plan-payload "2026-09-04" 20)]
    (is (= "plan/2026-09-04" (:id pl)))
    (is (= :improvement (:adapter pl)))
    (is (= "20" (:owed pl)))))

(deftest a-long-statement-is-clamped-in-the-title-and-whole-in-the-payload
  (let [long (apply str (repeat 200 "x"))
        pl (sweep/claim-payload (claim :land long "ws-a/1.0") "ws-p" 1 {:index 0})]
    (is (< (count (:title pl)) 120))
    (is (= long (:statement pl)) "the payload carries the claim whole")))

;; ── the one slot ────────────────────────────────────────────────────────────

(defn- poll
  "One poll against a stubbed ledger. Returns [emitted-payload state]."
  [& {:keys [attempts plans proposals]}]
  (let [emitted (atom nil)]
    (with-redefs [proposal/attempts       (constantly (vec attempts))
                  proposal/plans-of       (constantly (vec plans))
                  proposal/claim-attempts (constantly [])
                  proposal/of-project     (constantly (vec proposals))
                  proposal/owed           (fn [ps _ _] (vec ps))]
      [nil (sweep/poll-once! {:project :nido} #(reset! emitted %))])))

(deftest an-open-attempt-holds-both-fires
  ;; Including a LEGACY proposal-level attempt: it carries the same :improvement
  ;; adapter, which is what makes the hold span the cutover with no migration.
  (let [[_ st] (poll :attempts [(attempt "ws-old/1.1" :open? true)]
                     :plans [] :proposals [{:ws-id "ws-a" :analysis-seq 1 :observation 0}])]
    (is (nil? (:emitted st)))
    (is (= "ws-ws-old/1.1" (:held-by st)) "the state file says who holds it")))

(deftest a-day-with-no-plan-and-something-owed-fires-a-plan
  (let [[_ st] (poll :attempts [] :plans []
                     :proposals [{:ws-id "ws-a" :analysis-seq 1 :observation 0}])]
    (is (str/starts-with? (str (:emitted st)) "plan/"))
    (is (false? (:planned? st)))))

(deftest a-day-with-no-plan-and-nothing-owed-fires-nothing
  (let [[_ st] (poll :attempts [] :plans [] :proposals [])]
    (is (nil? (:emitted st)))
    (is (zero? (:owed st)))))

(deftest once-a-plan-exists-the-day-fires-implementations-not-a-second-plan
  (let [day (sweep/day-of (nido.coordinator.record.clock/now-iso))
        [_ st] (poll :attempts []
                     :plans [(plan "ws-p" 7 day (claim :land "one" "ws-a/1.0"))]
                     :proposals [{:ws-id "ws-a" :analysis-seq 1 :observation 0}])]
    (is (= "ws-p/7#0" (:emitted st)))
    (is (true? (:planned? st)))))

(deftest a-discharged-day-fires-nothing-and-does-not-replan
  (let [day (sweep/day-of (nido.coordinator.record.clock/now-iso))
        [_ st] (poll :attempts [(attempt (proposal/claim-address "ws-p" 7 0) :open? false)]
                     :plans [(plan "ws-p" 7 day (claim :land "one" "ws-a/1.0"))]
                     :proposals [{:ws-id "ws-a" :analysis-seq 1 :observation 0}])]
    (is (nil? (:emitted st)))
    (is (true? (:planned? st)) "the day is planned; it is not replanned because it is spent")))

(deftest each-leg-is-labelled-so-one-source-can-feed-two-triggers
  ;; route-broadcast returns EVERY trigger whose source-config matches, so two
  ;; triggers on this source would both fire on every emission. The filter keys
  ;; on :leg to take only its own.
  (is (= "plan" (:leg (sweep/plan-payload "2026-09-04" 3))))
  (is (= "claim" (:leg (sweep/claim-payload (claim :land "one" "ws-a/1.0") "ws-p" 1 {:index 0}))))
  (is (not= (:leg (sweep/plan-payload "2026-09-04" 3))
            (:leg (sweep/claim-payload (claim :land "one" "ws-a/1.0") "ws-p" 1 {:index 0})))))
