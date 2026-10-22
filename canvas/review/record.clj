(ns canvas.review.record
  "Self-spec: `nido.review.record` — the rounds that judge a baseline and a design record."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.agent :as agent]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.record.state :refer [Path WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream]
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
  (Operation discover-intent "The intent a design cited, read off the ledger the run pinned."
    {:signature [:=> [:catn [:ledger [:maybe [:tuple :keyword :string]]] [:design :map]] [:maybe :map]]})
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
  (Operation drifted-statements
    "A finding for each claim of a record that a listing of the declared design holds under the
     same id with another statement — compared as the landing compares them, so the round that can
     first repair a mismatch is handed it rather than a later reading that happens to open canvas/.
     The amender's to repair in whichever of the two is wrong; a claim not declared yet is none."
    {:signature [:=> [:catn [:record :map] [:listing DeclaredElements]] [:vector :map]]
     :delegates [design/declared-claims design/claim-text]})
  (Operation settled-block
    "The settled subjects, shown for the record-level derivations and outside the round's checks —
     text, readings and id, without the counterexample or evidence a check carries. Says nothing
     about why a subject is there."
    {:signature [:=> [:catn [:settled :map] [:record :map]] :string]})
  (Operation disputes-block "What an earlier amendment disputed, for the prompt."
    {:signature [:=> [:catn [:disputes :any]] :string]})
  (Operation prior-findings-block
    "What an earlier run found against the subjects a round checks, for the prompt — what a
     confirmation now would overturn."
    {:signature [:=> [:catn [:prior :any]] :string]})
  (Operation lens-block "The perspectives in play, and what each is for."
    {:signature [:=> [:catn] :string]})
  (Operation baseline-prompt "The verification prompt for a baseline."
    {:signature [:=> [:catn [:opts :map]] :string]
     :delegates [settled-block prior-findings-block disputes-block lens-block]})
  (Operation stratum-prompt
    "The prompt for one stratum's judge: that level's declared vocabulary, its modules, the strata
     it rests on and those resting on it, and what the design asks of it — asked first whether it is
     a level at all, then three questions only that level can answer."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation parse-stratum-reading
    "A stratum judge's answer as the reading a decision records, or nil when it is unusable."
    {:signature [:=> [:catn [:json :string] [:stratum :string]] [:maybe :map]]})
  (Operation design-prompt "The decision prompt for a design."
    {:signature [:=> [:catn [:opts :map]] :string] :delegates [prior-findings-block disputes-block]})
  (Operation parse-baseline-review "The agent's answer as a baseline review record."
    {:signature [:=> [:catn [:json-str :string] [:baseline-seq :any]] :map]})
  (Operation parse-design-decision
    "The agent's answer as a design decision record, holding only the checks its round asked —
     a finding citing one of the `health` ids is routing-coherent's, and a recommendation or a
     finding contradicting the answer's own check rulings is read as those rulings support. On an
     :ask, a finding on a claim the asks decides, or citing the `intent`, is the person's. A
     finding the judge keyed to no claim is paired with the one of `claims` its prose names."
    {:signature [:=> [:catn [:json-str :string] [:design-seq :any] [:asked [:set :keyword]]
                            [:health [:? [:maybe [:set :string]]]] [:intent [:? :any]]
                            [:claims [:? [:maybe [:set :string]]]]] :map]})
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
  (Operation answered
    "What a person has already answered about a design, for its judge's ask: the nearest grant up
     its supersedes chain against the same intent and baseline, with what changed since and the
     questions it answered, the newest reply they wrote into the record and the ask it answered,
     what they asked for in findings, and a question put to a person that neither a grant nor a
     reply has answered. A record the loop's amender wrote answers and grants nothing; a decision
     the judging run appended on a first reading asks nobody."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:design :map]
                      [:run-id [:? [:maybe :string]]]] :map]
     :delegates [workstream/amended-seqs]})
  (Operation design-decision! "Run the decision round over a design."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [design-prompt parse-design-decision unresolved-subjects design/elements answered]})
  (Operation append!
    "Append a round's record to the ledger the run resolved when it started — never one re-resolved
     from a directory mid-run."
    {:signature [:=> [:catn [:ledger [:maybe [:tuple :keyword :string]]] [:record :map]] :any]})
  (Operation clear!
    "Write the clearance a proceeding decision already on the ledger implies, and nothing else —
     no round re-runs and nothing becomes a grant."
    {:signature [:=> [:catn [:cwd Path]] :keyword]})
  (Operation baseline-finding-base-key "What makes two baseline findings the same finding."
    {:signature [:=> [:catn [:f :map]] :any]})
  (Operation dispute-aware "A key that folds in how many times a finding has been disputed."
    {:signature [:=> [:catn [:base-key :any]] :any]})
  (Operation refuted-running
    "How many readings in a row have refuted each subject of the judged record, counting back from
     its newest, over that record's own lineage. What decides whether an amender may remove a
     refuted claim with a reason rather than reword it."
    {:signature [:=> [:catn [:subject [:maybe :map]] [:reviews [:sequential :map]]] [:map-of :string :int]]})
  (Operation spent
    "The subjects a refutation run has reached the count at which rewording is no longer a repair —
     reworded once and refuted again — among those an amender is offered a weakening or withdrawal
     for. A derivation check or a whole-record field is never spent: nothing can be offered for it.
     What every surface a hand amender or a capped run reads names."
    {:signature [:=> [:catn [:running [:maybe [:map-of :string :int]]] [:offerable? :any]]
                 [:map-of :string :int]]})
  (Operation heat
    "How many decisions of a design's lineage refuted each area of it since a step-back on the area
     was last carried out — a recorded :recut or :resurvey, which re-does the whole design and so
     resets every area, an :ask under a :step-back naming it or a person's answer to it, or a design
     adding a :rejected entry under it. An area is an element of the design
     that is neither a module nor a stratum — refuted by a decision that refutes any claim about it,
     once per decision — or a claim about no such element, alone. A confirmation ends nothing:
     findings landing on one area across rounds and runs are the signal that its approach, not its
     wording, is off."
    {:signature [:=> [:catn [:design :map] [:decisions [:sequential :map]]] [:map-of :string :int]]})
  (Operation hot-areas
    "The areas of a design whose heat has reached the threshold, each with its claims, every
     finding against them with those since its reset marked, the approaches the design's :rejected already gave up
     for it, and the person's answers bearing on it. What a design round's judge and amender are
     shown; it decides nothing about when a round proceeds, stops or what it hands on."
    {:signature [:=> [:catn [:design :map] [:entries [:sequential :map]]] [:sequential :map]]})
  (Operation hot-figures
    "Over a workstream's ledger, or every ledger, within a window: the share of claim findings that
     landed on an area already hot, counted with no reset; the share of decisions ruling on a hot
     area that stepped back on it, as heat counts a step-back carried out, off the recorded
     :recommend — with step-backs recorded but not carried out counted apart;
     and the asks put to a person, by claim and by area. Pure over the entries, so the window before
     a change is read by the same code as the window after it."
    {:signature [:=> [:catn [:ledgers [:sequential [:sequential :map]]] [:window [:? [:maybe :map]]]] :map]})
  (Operation lineage-entries
    "The ledger entries heat and hot-areas read for a design: the designs of its lineage, the
     decisions that judged them, and the workstream's grants and person-written intents."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:design :map]] [:sequential :map]]})
  (Operation spent-at-cap
    "What a record run reaching its cap ends :unfixable on instead of :max-iters: the spent subjects
     of a round that still owes an amendment, less those held only as a person's question."
    {:signature [:=> [:catn [:ctx :map]] [:maybe [:sequential :string]]]})
  (Operation unspent-at-cap
    "What a capped round over a spent subject still repairs before it ends :unfixable: its findings
     against anything not spent, with those against only spent subjects withheld for the person."
    {:signature [:=> [:catn [:ctx :map]] [:maybe :map]]})
  (Operation round-open
    "What a round left open — the entry it judged, the subjects it read once, what it left unruled.
     Equal across two rounds exactly when neither moved anything, which is what the engine stops a
     circling run on."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation owed-reading
    "What a round sent on to its second reading read once — the record, the ids, whether it was an
     amendment's first reading. The engine counts no round against the cap that only reads this
     again, so a cap never ends a run between a reading and the one it owes."
    {:signature [:=> [:catn [:ctx :map]] [:maybe :map]]})
  (Operation owed-split-ask
    "The :ask by which one reading refuted, as a person's question, a claim a capped run still owes
     its second reading — the entry, its question, the owed ids it was about. The two readings split
     over a decision, which another reading cannot make."
    {:signature [:=> [:catn [:final :map]] [:maybe :map]]})
  (Operation cap-account
    "What the cap ended a record run between — an owed second reading, an amendment not yet read, a
     second reading that overturned a sufficient verdict, findings left unamended — read off the
     terminal ctx; what the terminal line and the analysis headline say in place of a fixed sentence."
    {:signature [:=> [:catn [:final :map]] [:maybe :string]]})
  (Operation write-owed-amend-prompt!
    "The prompt a capped round's amender would have been given, written to the run dir when the run
     ended holding findings no amender reached, so a hand amendment works under the same rules."
    {:signature [:=> [:catn [:final :map]] [:maybe :string]]})
  (Operation unchecked-running
    "How many readings in a row have declared each subject unchecked, counting back from its newest,
     with every reason given. The reasons are carried, never compared — no two judges word one alike."
    {:signature [:=> [:catn [:judgements [:sequential :map]]] [:map-of :string :map]]})
  (Operation unsettled-findings
    "A finding for each subject a judgement declared unchecked for the second reading running: the
     amender's to reword, move to the record's unknowns or drop, since no judge can rule on it and a
     sufficient verdict would otherwise stand over it."
    {:signature [:=> [:catn [:record :map] [:running [:map-of :string :map]]] [:vector :map]]})
  (Operation parse-amend-answer "What an amender may hand back."
    {:signature [:=> [:catn [:raw :any] [:findings :any] [:base-key :any]] :map]})
  (Operation disputed-n
    "How many times the run has objected to one finding: disputes under its identity whose disputed
     finding pointed at the same code — an objection to one counterexample does not answer another."
    {:signature [:=> [:catn [:history :any] [:base-key :any] [:f :map]] :int]})
  (Operation record-round-veto
    "Whether a record round repeating the last round's findings is still moving, with what decided
     it: the last round amended; every repeated identity moved — its code, the subject's anchor
     sites out, disjoint from last round's, or its quotes of the record new — and no repeated spent
     subject has been refuted past the readings its withdrawal is offered at. Reported on the judge
     phase, so a stop says which leg failed."
    {:signature [:=> [:catn [:base-key :any] [:ctx :map] [:prior :any]] [:maybe :map]]})
  (Operation record-round-changed?
    "The record loops' :changed? veto: record-round-veto's answer alone."
    {:signature [:=> [:catn [:base-key :any] [:ctx :map] [:prior :any]] :boolean]})
  (Operation attempt-key
    "A record loop's attempt identity: the finding's identity and the round its defect was first
     raised in, so the give-up counter counts the rounds one defect survived rather than the
     counterexamples one claim has had."
    {:signature [:=> [:catn [:finding-key :any]] :any]})
  (Operation baseline-round-changed? "record-round-changed? over baseline finding identity."
    {:signature [:=> [:catn [:ctx :map] [:prior :any]] :boolean]})
  (Operation design-round-changed? "record-round-changed? over design finding identity."
    {:signature [:=> [:catn [:ctx :map] [:prior :any]] :boolean]})
  (Operation disputes-for-judge
    "The objections the round before raised that the judge can still end: none an earlier round
     ended, none about only claims this round asks no ruling on."
    {:signature [:=> [:catn [:history :any] [:iter :int] [:asked [:maybe [:set :string]]]] :any]})
  (Operation amend-prompt "The instruction to repair a baseline."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation refusal-prompt
    "The instruction to repair an amended baseline or design the ledger refused: the refusal, the
     record as offered, and nothing the judge found."
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
  (Operation rounds-judged
    "How many of a record run's rounds a launched judge judged — a verdict or a recommendation was
     parsed from it — read off its report. Short of judges-launched by every judge that ran and
     left no judgement."
    {:signature [:=> [:catn [:report [:maybe :map]]] :int]})
  (Operation amender-account
    "One line on how an amend phase's amender ended — completed or not, exit code, turns or no
     result event — or nil when no amender was launched."
    {:signature [:=> [:catn [:launch [:maybe :map]]] [:maybe :string]]})
  (Operation judgements-unappended
    "Each round of a record run's report whose judge judged and whose judgement is on no ledger
     entry, with the ledger's refusal when the round kept one, read off the report — what the
     headline counts as refused beside a refused amendment."
    {:signature [:=> [:catn [:report [:maybe :map]]] [:vector :map]]})
  (Operation unreadable-rounds
    "Each round of a record run's report whose confirmations could not settle because its tree had
     no identity, with why, read off the report."
    {:signature [:=> [:catn [:report [:maybe :map]]] [:vector :map]]})
  (Operation readable-from
    "The first round after a record run's last unreadable one whose judge read a tree identity, or
     nil — what retires an unreadable round's `until then` in the headline."
    {:signature [:=> [:catn [:report [:maybe :map]]] [:maybe :int]]})
  (Operation continued
    "What a record run continued of earlier readings and runs, read off its report's judge phases:
     per entry, the single confirmations its readings paired; each read-once confirmation a reading
     denied; and the earlier runs round 1 read, with how each ended. What the headline says in
     place of a 1-round run that stopped for a reason only the ledger held."
    {:signature [:=> [:catn [:report [:maybe :map]]] :map]})
  (Operation subject-at-start
    "What a record run is about to judge, relative to the ledger as it starts: the subject entry,
     whether the caller named it, the newest entry of that kind beside it — flagged when it
     supersedes the subject and was skipped — and, for a baseline, the standing design's citation
     with whether the subject has moved past it."
    {:signature [:=> [:catn [:ledger [:maybe [:tuple :keyword :string]]]
                             [:kind [:enum :baseline :design]]
                             [:named [:maybe :map]]]
                 [:maybe :map]]})
  (Operation run-figures
    "What one record run's rounds did, per derived check, claim or derivation, read off the
     decisions and reviews it appended: in how many rounds each was broken, in how many it was the
     only thing broken, and whether it was still broken when the run's last round answered — a claim
     under whatever check it was filed, a check that cleared with how it cleared. What it ended
     without ruling on is held to the `final` record's subjects when they are known. Per subject, its
     reading sequence; and, given `texts` — the subjects of the record each judgement read — how far
     a confirmation can be trusted: refutations after N sound readings of the same text at the same
     tree, overturned confirmations, same-text splits and the run's flip rate. Given `amended`, the
     seqs of the records the run appended, a check or claim the last decision broke and an
     amendment after it repaired is amended-unjudged rather than broken at the end. Pure over those
     entries, so the figures are derived on every read and stored nowhere."
    {:signature [:=> [:catn [:entries [:vector :map]] [:final [:? [:maybe [:set :string]]]]
                  [:texts [:? :any]] [:amended [:? :any]]] :map]})
  (Operation outstanding
    "What one design decision leaves open, split by whose it is to close: the subjects broken for an
     amender, and on an :ask the subjects its question is about — outstanding, but a person's to
     answer, so never counted broken."
    {:signature [:=> [:catn [:decision :map]] :map]})
  (Operation design-amend-prompt "The instruction to repair a design."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation cite-corrected
    "An amendment citing the record it corrects under :supersedes — the loop's to set, not the
     amender's — keeping a :why its author wrote and carrying the corrected record's own."
    {:signature [:=> [:catn [:kind :keyword] [:prev [:maybe :map]] [:record :map] [:at :map]] :map]})
  (Operation relation-moved-under-ask
    "When a design run ends asking a person, how the newest design's :baseline :relation moved
     from the one the asking decision judged — the standing change a person answering scope is
     owed. nil when it did not move, or nothing was asked."
    {:signature [:=> [:catn [:final :map]] [:maybe :map]]})
  (Operation appended-amendment-delta
    "What the amendment a run's amender appended superseding a judged entry moved, read back off the
     ledger — for a run stopped in its amend phase, whose phase never finished to say so. nil when
     that run appended none."
    {:signature [:=> [:catn [:ledger [:maybe [:sequential :any]]] [:run-id :any] [:judged-seq [:maybe :int]]]
                 [:maybe :map]]})
  (Operation append-stopped-answer!
    "Append the amendment a stopped run left unappended, as its amender wrote it, through the loop's
     own citation and ledger checks; the record stamped and what it weakened, or why nothing was
     written."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation amendment-refusal
    "What the ledger would refuse of an amendment appended now, citing what the loop would have it
     cite; nil when it would be taken. Writes nothing."
    {:signature [:=> [:catn [:project :keyword] [:ws-id :string] [:kind :keyword] [:record :map]] [:maybe :string]]}))
