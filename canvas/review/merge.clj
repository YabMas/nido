(ns canvas.review.merge
  "Self-spec: `nido.review.merge` — bringing a forked unit's design back into its parent."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.fork :as fork]
            [canvas.coordinator.report.model :as model]
            [canvas.design.check :as design]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind MergeProposal
  "What a merge found: the combined model, stating as removed what the parent's current baseline
   holds and the combination dropped; every conflict, by id from the models and by law from the
   declaration; and the citations a merged design carries. A proposal with conflicts appends
   nothing."
  [:map [:model :map] [:conflicts [:vector :map]]])

(Module review-merge
  "The merge of a child unit's design into its parent's, as a proposal.

   It COMPUTES only the part that is data — the elements and claims, keyed by id — and asks
   fukan whether the combined declaration still obeys its laws. It never composes prose: a
   merged design's summary, shape, layers and seams are authored beside the proposal, and the
   merged design is judged by the ordinary design round, whose record-level derivations run
   whatever the proposal found."
  {:child [MergeProposal]}
  (Operation proposal
    "Combine the fork's base, the parent's current design and the child's design, then check the
     combined declaration in the child's working copy. Conflicts from both are reported together."
    {:signature [:=> [:catn [:project ProjectName] [:child-ws :string] [:worktree :string]] MergeProposal]
     :delegates [fork/lineage fork/parent-records model/overlay model/combine design/check]})
  (Operation conflicts-text
    "The conflicts for a person, each named by claim id or by law."
    {:signature [:=> [:catn [:proposal MergeProposal]] :string]})
  (Operation merge!
    "Append to the parent the merged design whose own fields are authored, while no conflict stands.
     Its model, its citations and what it supersedes come from the proposal, never from the author."
    {:signature [:=> [:catn [:project ProjectName] [:child-ws :string] [:worktree :string]
                            [:authored :map]]
                 :map]
     :delegates [proposal]}))
