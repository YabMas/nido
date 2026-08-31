(ns canvas.review.record
  "Self-spec: `nido.review.record` — the rounds that judge a baseline and a design record."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.agent :as agent]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.record.state :refer [Path WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module review-record
  "The decision rounds over a workstream's own records: does this baseline hold, and does the
   design it supports still stand.

   Both rounds are GATED on being worth running, and the gate is economic rather than
   defensive — a round costs an agent turn, so a baseline nothing cites and a design nothing
   changed are not worth re-deciding.

   Disputes carry across rounds. A finding raised, answered and raised again is not the same as
   one raised once, which is why the counts are folded in rather than the findings being treated
   as fresh each time — otherwise an amender and a reviewer can argue forever without either
   noticing they are repeating themselves."
  (Operation baseline-round-worth-running? "Whether a baseline is worth verifying."
    {:signature [:=> [:catn [:baseline :map]] :boolean]})
  (Operation design-round-worth-running? "Whether a design is worth re-deciding."
    {:signature [:=> [:catn [:design :map]] :boolean]})
  (Operation discover-intent "The intent a design cited."
    {:signature [:=> [:catn [:cwd Path] [:design :map]] [:maybe :map]]})
  (Operation known-ids "Every id a baseline actually defines."
    {:signature [:=> [:catn [:record :map]] :any]})
  (Operation confirmed-in "The confirmations that name ids this record has."
    {:signature [:=> [:catn [:record :map] [:confirmed :any]] :any] :delegates [known-ids]})
  (Operation confirmed-so-far "Every claim an earlier round confirmed."
    {:signature [:=> [:catn [:history :any]] :any]})
  (Operation confirmations-block "What earlier rounds already settled, for the prompt."
    {:signature [:=> [:catn [:confirmed :any]] :string]})
  (Operation disputes-block "What an earlier amendment disputed, for the prompt."
    {:signature [:=> [:catn [:disputes :any]] :string]})
  (Operation lens-block "The perspectives in play, and what each is for."
    {:signature [:=> [:catn] :string]})
  (Operation baseline-prompt "The verification prompt for a baseline."
    {:signature [:=> [:catn [:opts :map]] :string]
     :delegates [confirmations-block disputes-block lens-block]})
  (Operation design-prompt "The decision prompt for a design."
    {:signature [:=> [:catn [:opts :map]] :string] :delegates [disputes-block]})
  (Operation parse-baseline-review "The agent's answer as a baseline review record."
    {:signature [:=> [:catn [:json-str :string] [:baseline-seq :any]] :map]})
  (Operation parse-design-decision "The agent's answer as a design decision record."
    {:signature [:=> [:catn [:json-str :string] [:design-seq :any]] :map]})
  (Operation baseline-review! "Run the verification round over a baseline."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [baseline-prompt parse-baseline-review]})
  (Operation unverified-premise
    "Why a design cannot be judged yet — the premise it rests on has not been verified, which is
     a different answer from the design being wrong."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:design :map]] [:maybe :map]]
     :delegates [standing/of-design]})
  (Operation design-decision! "Run the decision round over a design."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [design-prompt parse-design-decision]})
  (Operation append! "Append a round's record to the ledger."
    {:signature [:=> [:catn [:cwd Path] [:record :map]] :any]})
  (Operation baseline-finding-base-key "What makes two baseline findings the same finding."
    {:signature [:=> [:catn [:f :map]] :any]})
  (Operation dispute-aware "A key that folds in how many times a finding has been disputed."
    {:signature [:=> [:catn [:base-key :any]] :any]})
  (Operation parse-amend-answer "What an amender may hand back."
    {:signature [:=> [:catn [:raw :any] [:findings :any] [:base-key :any]] :map]})
  (Operation dispute-counts "How many times each finding has been disputed."
    {:signature [:=> [:catn [:history :any]] :any]})
  (Operation disputes-for-judge "Every standing objection, for the judge."
    {:signature [:=> [:catn [:history :any]] :any]})
  (Operation amend-prompt "The instruction to repair a baseline."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation broken-checks "The derivations that failed."
    {:signature [:=> [:catn [:record :map]] :any]})
  (Operation underivable-checks "The derivations the round could not make at all."
    {:signature [:=> [:catn [:record :map]] :any]})
  (Operation design-finding-base-key "What makes two design findings the same finding."
    {:signature [:=> [:catn [:c :map]] :any]})
  (Operation trajectory "The run as a human reading it would want it."
    {:signature [:=> [:catn [:history :any]] :any]})
  (Operation design-amend-prompt "The instruction to repair a design."
    {:signature [:=> [:catn [:opts :map]] :string]}))
