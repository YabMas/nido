(ns canvas.coordinator.report.ask
  "Self-spec: `nido.coordinator.report.ask` — what asking a person a question is.

   A third shared value type beside the ledger's vocabulary, in the same band for the same reason:
   its authors (a seat, the conductor, the review rounds) and its readers (the work plane, the
   surfaces) sit at every altitude, it reads nothing, and it depends on nothing above the floor."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Kind Ask
  "What a person is asked at one gate, in the one shape every gate kind is asked in: a one-line
   headline, and one or more questions — each its words, the context it presupposes, a
   recommendation and what follows the answer, and the answers on offer with what each costs —
   the action that submits answers collected across questions, what each of the gate's
   actions does, and the goal the question is judged against. Derived from records, never stored,
   and constructed only by `ask`."
  [:map [:kind :keyword]
        [:question [:re "\\S"]]
        [:questions [:vector {:min 1}
                     [:map [:question [:re "\\S"]]
                           [:context [:vector :string]]
                           [:recommendation {:optional true} :string]
                           [:after {:optional true} :string]
                           [:options [:vector [:map [:letter :string] [:label :string] [:summary :string]
                                                    [:consequence {:optional true} :string]
                                                    [:recommended? {:optional true} :boolean]
                                                    [:action {:optional true} :keyword]]]]]]]
        [:submit {:optional true} :keyword]
        [:answer-does [:vector [:map [:action :keyword] [:label :string] [:does [:re "\\S"]]]]]
        [:goal {:optional true} :map]
        [:note {:optional true} :string]])

(Module report-ask
  "What asking a person means, generically and per kind of question.

   HIDES where each kind's question lives in its record and what that kind adds to the generic
   ask. One registry entry per kind — the move a person owes, read off its record into an Ask, and
   the guidance addendum its authors are briefed with — so adding a kind or tuning one touches its
   entry and nothing else."
  {:child [Ask]}
  (Operation ask
    "The Ask for one gate, for the move its position owes, handed the gate's report, the newest
     record of each kind a question can live in (from which the kind's entry picks the record its
     question lives in), the gate's actions, the workstream's live intent and any conductor note.
     Pure. What a kind's entry yields is checked against Ask and against the actions handed in,
     each action's :id named once in :answer-does; an unregistered kind answers through the
     generic entry."
    {:signature [:=> [:catn [:kind :keyword]
                            [:inputs [:map [:report [:maybe :map]]
                                           [:entries [:vector :map]]
                                           [:actions [:vector [:map [:id :keyword]]]]
                                           [:intent {:optional true} :map]
                                           [:note {:optional true} :string]]]]
                 Ask]})
  (Operation guidance
    "The guidance an author of a kind's question is briefed with: the generic asking guidance
     followed by that kind's addendum."
    {:signature [:=> [:catn [:kind :keyword]] :string]})
  (Operation kinds
    "The kinds of question registered, each with its guidance addendum."
    {:signature [:=> [:catn] [:vector :keyword]]}))
