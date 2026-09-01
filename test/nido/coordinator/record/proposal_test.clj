(ns nido.coordinator.record.proposal-test
  "The sweep's scheduling rule, exercised as the pure function it is.

   Everything here runs against literal proposals, plans and attempts. What
   these decide — what a day still owes, and whether a grouping accounts for it
   — is the policy that lands code on nido's main unattended, and a policy that
   can only be exercised against a live coordinator is one nobody checks."
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.proposal :as proposal]))

(defn- p
  "A proposal row, as `of-project` emits one."
  [ws-id seq obs & {:keys [verdict landed at]}]
  (cond-> {:ws-id ws-id :analysis-seq seq :observation obs
           :at (or at "2026-08-31T22:56:06Z")
           :kind :misfire :summary "the rebase is never checked"
           :proposal "call layers/conflicted"}
    verdict (assoc :decision {:verdict verdict :decided-by "yabmas"})
    landed  (assoc :landed {:rev landed :landed-by "yabmas"})))

(defn- plan [& claims]
  {:format :improvement-plan :date "2026-09-04"
   :frontier {:proposals [] :attempts []}
   :claims (vec claims)})

(defn- claim [disposition & addresses]
  (cond-> {:statement "one change" :disposition disposition :addresses (vec addresses)}
    (= :file disposition) (assoc :ref "FU-99")))

(defn- attempt [& {:keys [addresses open? outcome]}]
  (cond-> {:addresses (vec addresses) :open? (boolean open?)}
    outcome (assoc :outcome outcome)))

(defn- addrs [rows] (mapv proposal/address rows))

;; ── what a day owes ─────────────────────────────────────────────────────────

(deftest an-undecided-proposal-is-owed
  ;; The change this makes: approval stops being required, so an undecided
  ;; proposal is owed exactly as an approved one is.
  (is (= ["ws-a/1.0"] (addrs (proposal/owed [(p "ws-a" 1 0)] [] [])))))

(deftest a-declined-proposal-is-never-owed
  (is (empty? (proposal/owed [(p "ws-a" 1 0 :verdict :declined)] [] []))))

(deftest an-approved-proposal-is-owed-exactly-as-an-undecided-one-is
  (is (= ["ws-a/1.0"] (addrs (proposal/owed [(p "ws-a" 1 0 :verdict :approved)] [] [])))))

(deftest a-landed-proposal-is-not-owed
  (is (empty? (proposal/owed [(p "ws-a" 1 0 :verdict :approved :landed "abc")] [] []))))

(deftest a-plan-settles-what-it-dispositions-file-or-no-op
  ;; Neither records a landing, because nothing landed — so without reading the
  ;; plans they would be owed forever.
  (let [ps [(p "ws-a" 1 0) (p "ws-b" 1 0) (p "ws-c" 1 0)]
        pl (plan (claim :no-op "ws-a/1.0") (claim :file "ws-b/1.0"))]
    (is (= ["ws-c/1.0"] (addrs (proposal/owed ps [pl] []))))))

(deftest a-land-claim-on-a-plan-leaves-its-addresses-owed-until-something-settles-them
  ;; Planning to land is not landing. The claim has to be carried out.
  (let [ps [(p "ws-a" 1 0)]]
    (is (= ["ws-a/1.0"] (addrs (proposal/owed ps [(plan (claim :land "ws-a/1.0"))] []))))))

(deftest an-address-a-closed-attempt-covered-is-not-owed-again
  (let [ps [(p "ws-a" 1 0)]]
    (is (empty? (proposal/owed ps [] [(attempt :addresses ["ws-a/1.0"] :open? false)])))))

(deftest an-open-attempt-does-not-settle-what-it-covers
  (let [ps [(p "ws-a" 1 0)]]
    (is (= ["ws-a/1.0"] (addrs (proposal/owed ps [] [(attempt :addresses ["ws-a/1.0"] :open? true)]))))))

(deftest a-vetoed-close-returns-its-undeclined-addresses-to-the-owed-set
  ;; A vetoed close is the veto working, not a session giving up. The proposals
  ;; that were merely grouped alongside the declined one were never the reason
  ;; it stopped, and burying them would make one decline cost three proposals.
  (let [ps [(p "ws-a" 1 0 :verdict :declined) (p "ws-b" 1 0) (p "ws-c" 1 0)]
        at [(attempt :addresses ["ws-a/1.0" "ws-b/1.0" "ws-c/1.0"]
                     :open? false :outcome :vetoed)]]
    (is (= ["ws-b/1.0" "ws-c/1.0"] (addrs (proposal/owed ps [] at))))))

(deftest a-later-plan-reconsiders-an-address-an-earlier-one-dispositioned
  (let [ps [(p "ws-a" 1 0)]]
    (is (empty? (proposal/owed ps [(plan (claim :land "ws-a/1.0"))
                                   (plan (claim :no-op "ws-a/1.0"))] [])))
    (is (= ["ws-a/1.0"] (addrs (proposal/owed ps [(plan (claim :no-op "ws-a/1.0"))
                                                  (plan (claim :land "ws-a/1.0"))] []))))))

(deftest the-owed-set-is-oldest-first
  ;; A backlog drained newest-first lets a busy week starve a proposal forever.
  (let [ps [(p "ws-b" 1 0 :at "2026-09-02T00:00:00Z")
            (p "ws-a" 1 0 :at "2026-08-30T00:00:00Z")]]
    (is (= ["ws-a/1.0" "ws-b/1.0"] (addrs (proposal/owed ps [] []))))))

;; ── whether a grouping accounts for it ──────────────────────────────────────

(deftest a-partition-covering-the-owed-set-exactly-once-has-no-defect
  (let [ow [(p "ws-a" 1 0) (p "ws-b" 1 1)]]
    (is (nil? (proposal/partition-defect
               [(claim :land "ws-a/1.0" "ws-b/1.1")] ow)))))

(deftest a-proposal-the-plan-did-not-mention-is-uncovered
  (let [ow [(p "ws-a" 1 0) (p "ws-b" 1 1)]]
    (is (= {:uncovered ["ws-b/1.1"]}
           (proposal/partition-defect [(claim :land "ws-a/1.0")] ow)))))

(deftest a-claim-naming-an-address-that-is-not-owed-is-a-plan-derived-against-a-state-that-moved
  (let [ow [(p "ws-a" 1 0)]]
    (is (= {:unowed ["ws-gone/9.9"]}
           (proposal/partition-defect [(claim :land "ws-a/1.0" "ws-gone/9.9")] ow)))))

(deftest both-defects-are-reported-together
  ;; A refusal a writer cannot act on costs the same round trip as no check.
  (let [ow [(p "ws-a" 1 0)]]
    (is (= {:uncovered ["ws-a/1.0"] :unowed ["ws-x/1.0"]}
           (proposal/partition-defect [(claim :land "ws-x/1.0")] ow)))))

(deftest an-empty-plan-over-an-empty-owed-set-is-a-partition
  (is (nil? (proposal/partition-defect [] []))))

;; ── a claim's address ───────────────────────────────────────────────────────

(deftest a-claim-address-cannot-be-confused-with-a-proposal-address
  ;; Same shape, different separator: `tried` sets and ref lookups compare these
  ;; by string, so the two must not collide.
  (is (= "ws-a/12#0" (proposal/claim-address "ws-a" 12 0)))
  (is (not= (proposal/claim-address "ws-a" 1 0)
            (proposal/address {:ws-id "ws-a" :analysis-seq 1 :observation 0}))))
