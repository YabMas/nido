(ns canvas.coordinator.view.activity
  "Self-spec: `nido.coordinator.view.activity` — whether anything is happening in a workstream,
   and what, as one answer."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.activity :as claims]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Running
  "What runs on a workstream: the core's activity on it, or the claim a review holds inside a
   :working turn. Open beyond its :source, since the core's activity carries what its kind says."
  [:map [:source [:enum :core :claim]]])

(Kind QueuedMerge
  "The one thing underway that is not live: a merge waiting its turn while the core does nothing."
  [:map {:closed true} [:source [:= :merge]] [:phase [:= :queued]]])

(Kind Activity
  "Every facet of what is happening in a workstream: what is underway, whether anything runs,
   who owes the next move, whether a person's pause stands, whether it has stalled, whether its
   turn is stuck, whether it needs the person and in which category — an ask, a landing, a system error — and its
   engagement.

   ONE ANSWER, so no two facets can disagree: each is a field of `of` over one observation.
   Underway is live, except a merge waiting its turn or a pause; stalled implies nothing live, no
   need of the person, a move an agent or the machine owes and an open unit of the core's that
   nobody holds; owed-by :person exactly when needs-you exactly when :needs names a category,
   with no exception — a core ask or a move an activated workstream's position owes them is an
   :ask, a landing it owes them a :landing, a failure only they clear a :system-error. Stuck — the
   core's turn was ended and its process lives on — is read apart from every other facet,
   closure, ask and pause included, and implies nothing underway.

   ONE CLOSED VARIANT PER ENGAGEMENT, each fixing what its facets may be, and within it what runs
   fixed by whether it is live and paused — live is Running and unpaused, paused is never live,
   and anything else underway is a QueuedMerge — so contradictory facets (busy, blocked and not
   needing you at once; live under a pause) are not an Activity at all."
  [:multi {:dispatch :engagement}
   [:settled
    [:map {:closed true}
      [:engagement [:= :settled]]
      [:doing :nil]
      [:live [:= false]]
      [:owed-by [:enum :agent :machine :nobody]]
      [:paused :boolean]
      [:stalled [:= false]]
      [:stuck :boolean]
      [:needs-you [:= false]]
      [:needs :nil]]]
   [:parked-at-gate
    [:or
     [:map {:closed true}
          [:engagement [:= :parked-at-gate]]
          [:doing Running]
          [:live [:= true]]
          [:owed-by [:= :person]]
          [:paused [:= false]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= true]]
          [:needs [:enum :ask :landing :system-error]]]
     [:map {:closed true}
          [:engagement [:= :parked-at-gate]]
          [:doing [:maybe [:or Running QueuedMerge]]]
          [:live [:= false]]
          [:owed-by [:= :person]]
          [:paused [:= true]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= true]]
          [:needs [:enum :ask :landing :system-error]]]
     [:map {:closed true}
          [:engagement [:= :parked-at-gate]]
          [:doing [:maybe QueuedMerge]]
          [:live [:= false]]
          [:owed-by [:= :person]]
          [:paused [:= false]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= true]]
          [:needs [:enum :ask :landing :system-error]]]]]
   [:active
    [:map {:closed true}
          [:engagement [:= :active]]
          [:doing Running]
          [:live [:= true]]
          [:owed-by [:enum :agent :machine :nobody]]
          [:paused [:= false]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]]
   [:stalled
    [:map {:closed true}
          [:engagement [:= :stalled]]
          [:doing [:maybe QueuedMerge]]
          [:live [:= false]]
          [:owed-by [:enum :agent :machine]]
          [:paused [:= false]]
          [:stalled [:= true]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]]
   [:queued
    [:or
     [:map {:closed true}
          [:engagement [:= :queued]]
          [:doing [:maybe [:or Running QueuedMerge]]]
          [:live [:= false]]
          [:owed-by [:= :machine]]
          [:paused [:= true]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]
     [:map {:closed true}
          [:engagement [:= :queued]]
          [:doing [:maybe QueuedMerge]]
          [:live [:= false]]
          [:owed-by [:= :machine]]
          [:paused [:= false]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]]]
   [:idle
    [:or
     [:map {:closed true}
          [:engagement [:= :idle]]
          [:doing [:maybe [:or Running QueuedMerge]]]
          [:live [:= false]]
          [:owed-by [:enum :agent :nobody]]
          [:paused [:= true]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]
     [:map {:closed true}
          [:engagement [:= :idle]]
          [:doing [:maybe QueuedMerge]]
          [:live [:= false]]
          [:owed-by [:enum :agent :nobody]]
          [:paused [:= false]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]]]])

(Kind Observation
  "Everything `of` reads about one workstream: the record facts status gives (closure, activation,
   a pause), the row's band, who owes the move and whether it is a landing as the position the row
   is placed at says — a Land the core already holds folded in — a failure in the core only
   the person clears, the core's activity on it and whether it holds an open unit nobody holds,
   and the claim a :working turn holds."
  [:map
   [:closed?    :boolean]
   [:band       {:optional true} [:maybe :keyword]]
   [:owed-by    {:optional true} [:maybe :keyword]]
   [:activated? {:optional true} :boolean]
   [:landing?   {:optional true} :boolean]
   [:failure    {:optional true} [:maybe [:enum :conductor-stopped :start-failed]]]
   [:paused?    {:optional true} :boolean]
   [:core       {:optional true} [:maybe :map]]
   [:movable?   {:optional true} :boolean]
   [:claim      {:optional true} [:maybe :map]]])

(Module view-activity
  "A workstream's activity: one pure function over one observation, and the read of the claim that
   says what a running turn is doing. Pure read model — NO WRITES."
  {:child [Running QueuedMerge Activity Observation]}
  (Operation of
    "Every facet of a workstream's activity, from its observation alone: the record facts status
     gives, the row's band, who owes the move and whether it is a landing as the row's placed
     position says, the core's state of it — a failure only the person clears among it — and the
     claim its running turn holds. Reads nothing."
    {:signature [:=> [:catn [:obs Observation]] Activity]})
  (Operation observe
    "The activity claim held on a workstream now, with its round's progress — what a turn the core
     runs is doing. Never evidence that anything runs."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [claims/read-live round-progress]})
  (Operation round-progress
    "How far a running round has got — its number, the phase running, what its judging
     raised — read off the report it keeps as it goes."
    {:signature [:=> [:catn [:report [:maybe :map]]] [:maybe :map]]}))
