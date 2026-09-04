;; src/nido/coordinator/source/sweep.clj
(ns nido.coordinator.source.sweep
  "The :improvement-sweep source. Emits one fire at a time: a plan for today when
   there is none and something is owed, then an implementation for the next claim
   that plan still owes.

   Replaces :improvement, which fires one PROPOSAL at a time. That unit is why
   six proposals describing one defect cost six sessions, and why a proposal
   whose own text says no change is needed gets a worktree and a budget like any
   other. The claim is the unit here; the address stays the unit of record.

   BOTH FIRES TAKE THE SAME ONE SLOT, and the slot is the one the area already
   has: an open workstream carrying the :improvement adapter, released by a close
   and by nothing weaker. Keeping that adapter rather than minting a new one is
   what lets the hold span its own cutover — `attempts` reads by adapter, so a
   legacy proposal-level workstream still open holds this source on the day it
   ships, with no migration and no window in which the two are invisible to each
   other.

   The plan-for-today test is NOT a second hold and releases nothing. It stops a
   second plan being written on a day that already has one, and orders planning
   ahead of implementing within a day. Appending the plan does not free the slot:
   while the planning workstream is open nothing else fires, and a session that
   dies without closing wedges the path exactly as one does today. That wedge is
   inherited whole and deliberately: every release condition weaker than a close
   lets a second agent start on a branch the first abandoned mid-edit.

   Emits AT MOST ONE EVENT PER POLL, for the reason improvement.clj gives:
   provisioning mints workstream, run and session at drain time, before any
   scheduling gate runs, so two envelopes drained in one tick become two sessions
   whatever any cap says."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.proposal :as proposal]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.state :as sst]))

(def ^:private title-cap
  "How much of a claim's statement rides in the ref title. The title labels the
   workstream on the board; the payload carries the claim whole."
  90)

(defn- clamp [s]
  (let [s (str s)]
    (if (> (count s) title-cap) (str (subs s 0 title-cap) "…") s)))

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  day-of
  "The calendar day of an ISO timestamp — a plan is FOR a day, and `:at` is a
   moment. Substring rather than a date library because the ledger's timestamps
   are ISO-8601 UTC by construction and nothing here needs arithmetic."
  [iso]
  (subs (str iso) 0 10))

(defn ^{:malli/schema [:=> [:cat [:vector :map] :string] [:maybe :map]]}
  plan-for
  "Today's plan, or nil."
  [plans day]
  (first (filter #(= day (:date %)) plans)))

(defn ^{:malli/schema [:=> [:cat :map [:vector :map] [:vector :map]] [:maybe :map]]}
  next-claim
  "The next :land claim of `plan` that has not been attempted, with its index, or
   nil when the plan is discharged.

   Only :land claims fire. :no-op and :file are settled by the plan itself —
   nothing landed and nothing is going to — and spawning a session to discover
   that is the waste this design exists to remove.

   Attempted rather than open: a claim whose workstream exists at all has had its
   session, and re-firing it would spend a budget every poll for as long as the
   ledger stands. A vetoed claim is attempted too; its survivors come back
   through the owed set and are grouped again tomorrow, not re-fired here."
  [plan plan-ws-id attempts]
  (let [tried (into #{} (map :address) attempts)]
    (->> (map-indexed vector (:claims plan))
         (filter (fn [[_ c]] (= :land (:disposition c))))
         (remove (fn [[i _]]
                   (contains? tried (proposal/claim-address plan-ws-id (:seq plan) i))))
         first
         (#(when % {:index (first %) :claim (second %)})))))

(defn ^{:malli/schema [:=> [:cat :map :string :int :map] :map]}
  claim-payload
  "The envelope payload for one claim's implementation.

   `:leg` is what routes it. One source emits two kinds of fire and
   `route-broadcast` returns EVERY trigger whose source-config matches — so two
   triggers sharing this source would both fire on every emission, and the
   one-at-a-time rule would be broken by the very envelope meant to honour it.
   A trigger filter keys on this to take only its own leg."
  [claim plan-ws-id plan-seq {:keys [index]}]
  {:leg       "claim"
   :adapter   :improvement
   :id        (proposal/claim-address plan-ws-id plan-seq index)
   :title     (str "improve: " (clamp (:statement claim)))
   :statement (str (:statement claim))
   :addresses (str/join "," (:addresses claim))
   :plan-ws   plan-ws-id
   :plan-seq  plan-seq
   :claim     index
   :covers    (str (count (:addresses claim)))})

(defn ^{:malli/schema [:=> [:cat :string :int] :map]}
  plan-payload
  "The envelope payload for a day's planning pass."
  [day owed-count]
  {:leg     "plan"
   :adapter :improvement
   :id      (str "plan/" day)
   :title   (str "plan improvements for " day)
   :date    day
   :owed    (str owed-count)})

(defn ^{:malli/schema [:=> [:cat :map :any] :map]}
  poll-once!
  "One poll. Returns the state to persist; emits at most one event."
  [source-config emit-fn]
  (let [project  (:project source-config)
        attempts (proposal/attempts project)
        open     (some #(when (:open? %) %) attempts)
        day      (day-of (clock/now-iso))
        plans    (proposal/plans-of project)
        today    (plan-for plans day)
        owed     (when-not open
                   (proposal/owed (proposal/of-project project)
                                  plans
                                  (proposal/claim-attempts project)))
        pick     (when-not open
                   (if today
                     (when-let [c (next-claim today (:ws-id today) attempts)]
                       {:kind :implement
                        :payload (claim-payload (:claim c) (:ws-id today) (:seq today) c)})
                     (when (seq owed)
                       {:kind :plan :payload (plan-payload day (count owed))})))]
    (when pick (emit-fn (:payload pick)))
    {:type             :improvement-sweep
     :source-config    source-config
     :last-polled-at   (clock/now-iso)
     :last-poll-result :ok
     ;; What this poll DID, because the honest failure mode is silence: a source
     ;; emitting nothing because it is held looks exactly like one emitting
     ;; nothing because the day is discharged, and only the first needs clearing.
     :held-by          (when open (:ws-id open))
     :day              day
     :planned?         (some? today)
     :owed             (count owed)
     :emitted          (when pick (get-in pick [:payload :id]))}))

(defn ^{:malli/schema [:=> [:cat :map :any] :map]}
  start-instance!
  [source-config emit-fn]
  (let [hash (sources/config-hash source-config)
        emit (fn [p] (emit-fn {:type :improvement-sweep :source-config source-config :payload p}))]
    {:poll! (fn [] (sst/write-state! hash (poll-once! source-config emit)))
     :stop! (fn [] nil)}))

(defn ^{:malli/schema [:=> [:cat] :any]}
  register! []
  (sources/register-source!
   {:type   :improvement-sweep
    :schema [:map
             [:type    [:= :improvement-sweep]]
             [:project keyword?]
             [:poll    {:optional true} string?]]
    :events [:map [:adapter [:= :improvement]] [:id string?]]
    :start! start-instance!}))
