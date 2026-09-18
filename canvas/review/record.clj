(ns canvas.review.record
  "Self-spec: `nido.review.record` — the rounds that judge a baseline and a design record."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.agent :as agent]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.record.state :refer [Path WorkstreamId]]
            [canvas.design.check :as design :refer [DeclaredElements]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.review.settled :as settled]
            [fukan.common.typing.malli]))

(Module review-record
  "The decision rounds over a workstream's own records: does this baseline hold, and does the
   design it supports still stand.

   The baseline round is GATED on being worth running, and the gate is economic rather than
   defensive — a round costs an agent turn, so a baseline that recorded nothing checkable is not
   worth verifying. The decision round is never skipped: what a design declares decides whether
   a person's grant is additionally owed, and only a round that proceeded can clear a design
   that owes nobody one.

   Disputes carry across rounds. A finding raised, answered and raised again is not the same as
   one raised once, which is why the counts are folded in rather than the findings being treated
   as fresh each time — otherwise an amender and a reviewer can argue forever without either
   noticing they are repeating themselves."
  (Operation baseline-round-worth-running? "Whether a baseline is worth verifying."
    {:signature [:=> [:catn [:baseline :map]] :boolean]})
  (Operation discover-intent "The intent a design cited."
    {:signature [:=> [:catn [:cwd Path] [:design :map]] [:maybe :map]]})
  (Operation unresolved-subjects
    "The subjects a record's claims name that a listing of the declared design does not hold under
     the sort the record gives them — empty when every one resolves. Given the listing rather than
     reading it, so the round that reads it once can say why when it could not be read. In a
     project that declares a design, a round with any launches no judge: a claim about nothing
     declared is not one a judge can check. A project that declares none is not asked."
    {:signature [:=> [:catn [:record :map] [:listing DeclaredElements]] [:vector :string]]})
  (Operation misplayed-roles
    "The roles a record's model holds that a listing of the declared design declares with other
     players — empty when every declared role is played as recorded. A round with any launches no
     judge: a claim about a role binds exactly its players, and a judge checking it against players
     the design does not declare checks something else."
    {:signature [:=> [:catn [:record :map] [:listing DeclaredElements]] [:vector :string]]})
  (Operation settled-block
    "The settled subjects, shown for the record-level derivations and outside the round's checks —
     text, readings and id, without the counterexample or evidence a check carries. Says nothing
     about why a subject is there."
    {:signature [:=> [:catn [:settled :map] [:record :map]] :string]})
  (Operation disputes-block "What an earlier amendment disputed, for the prompt."
    {:signature [:=> [:catn [:disputes :any]] :string]})
  (Operation lens-block "The perspectives in play, and what each is for."
    {:signature [:=> [:catn] :string]})
  (Operation baseline-prompt "The verification prompt for a baseline."
    {:signature [:=> [:catn [:opts :map]] :string]
     :delegates [settled-block disputes-block lens-block]})
  (Operation design-prompt "The decision prompt for a design."
    {:signature [:=> [:catn [:opts :map]] :string] :delegates [disputes-block]})
  (Operation parse-baseline-review "The agent's answer as a baseline review record."
    {:signature [:=> [:catn [:json-str :string] [:baseline-seq :any]] :map]})
  (Operation parse-design-decision "The agent's answer as a design decision record."
    {:signature [:=> [:catn [:json-str :string] [:design-seq :any]] :map]})
  (Operation baseline-review!
    "Run the verification round over a baseline, recording on its review the code identity its
     judge read when the readings taken either side of the judge agree."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [baseline-prompt parse-baseline-review settled/code-identity
                 unresolved-subjects design/elements]})
  (Operation unverified-premise
    "Why a design cannot be judged yet — the premise it rests on has not been verified, which is
     a different answer from the design being wrong."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:design :map]] [:maybe :map]]
     :delegates [standing/of-design]})
  (Operation design-decision! "Run the decision round over a design."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [design-prompt parse-design-decision unresolved-subjects design/elements]})
  (Operation append! "Append a round's record to the ledger."
    {:signature [:=> [:catn [:cwd Path] [:record :map]] :any]})
  (Operation clear!
    "Write the clearance a proceeding decision already on the ledger implies, and nothing else —
     no round re-runs and nothing becomes a grant."
    {:signature [:=> [:catn [:cwd Path]] :keyword]})
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
  (Operation judges-launched
    "How many of a record run's rounds launched a judge, read off its report; zero whatever status
     the run ended in means it judged nothing."
    {:signature [:=> [:catn [:report [:maybe :map]]] :int]})
  (Operation run-figures
    "What one record run's rounds did, per derived check or derivation, read off the decisions and
     reviews it appended: in how many rounds each was broken, in how many it was the only thing
     broken, and whether it was still broken when the run's last round answered. Pure over those
     entries, so the figures are derived on every read and stored nowhere."
    {:signature [:=> [:catn [:entries [:vector :map]]] :map]})
  (Operation design-amend-prompt "The instruction to repair a design."
    {:signature [:=> [:catn [:opts :map]] :string]}))
