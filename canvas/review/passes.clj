(ns canvas.review.passes
  "Self-spec: the review passes themselves — codex, the verdict, the layer stack, the stage
   orchestration, and the record rounds."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.review.core :refer [Finding]]
            [fukan.common.typing.malli]))

(Module review-codex
  "Running codex over a range and reading what it said.

   Findings are identified by WHAT they are about — file, line, title — not by when they were
   found, so the same finding raised by two passes is one finding and a finding that survives a
   fix is recognisably the same one."
  (Operation finding-id "A finding's identity, derived from what it is about."
    {:signature [:=> [:catn [:f Finding]] :string]})
  (Operation normalize-finding "A native codex finding in nido's shape."
    {:signature [:=> [:catn [:raw :map]] Finding]})
  (Operation parse-output "Codex's structured output as findings."
    {:signature [:=> [:catn [:json-str :string]] :any] :delegates [normalize-finding]})
  (Operation codex-argv "The argument vector for one codex run."
    {:signature [:=> [:catn [:opts :map]] :any]})
  (Operation run-codex! "Run codex and read its output."
    {:signature [:=> [:catn [:opts :map]] :map] :delegates [codex-argv]})
  (Operation composition-schema "The findings schema for the whole-stack pass."
    {:signature [:=> [:catn [:base :any]] :any]})
  (Operation schema-json "The output schema to hand codex."
    {:signature [:=> [:catn [:composition? :boolean]] :string] :delegates [composition-schema]})
  (Operation merge-base "The merge base a range is measured from."
    {:signature [:=> [:catn [:cwd Path] [:base :any]] :string]})
  (Operation changed-files "The files a range touches."
    {:signature [:=> [:catn [:cwd Path] [:from :any] [:to :any]] :any]})
  (Operation safe-label "A label made safe to put in a path."
    {:signature [:=> [:catn [:label :any]] :string]})
  (Operation unavailability
    "Why no reviewer ran, out of the log codex streamed — or nothing, when the log does not say
     the reviewer was unavailable.

     The distinction the review path exists to draw at all: a review that BROKE is evidence
     about the branch and a reviewer that could not be RUN is evidence about a quota, a rate
     limit or a credential, and only one of them is answered by opening the diff. Carries the
     line verbatim, because the remedy and the reset hour are in the vendor's words and nowhere
     else."
    {:signature [:=> [:catn [:tail [:maybe :string]]] [:maybe :map]]})
  (Operation review! "Review one range and answer with its findings."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [run-codex! schema-json safe-label unavailability]}))

(Module review-verdict
  "The pass that decides whether the round is done.

   Separate from the reviewers, because a reviewer that also decided when to stop would stop
   when it ran out of things to say rather than when the work was right.

   It has a MEMORY of its own last answer, which is what makes it one judge rather than a fresh
   opinion per run: the verdict rests on a design record, a baseline and a stance, none of which
   move between runs, so a pass told nothing about its predecessor re-derives the same
   outstanding question in new prose every time — and can contradict itself with neither half
   knowing the other exists."
  (Operation build-prompt "The verdict prompt for this round."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation parse "The verdict out of what the agent said."
    {:signature [:=> [:catn [:text :string] [:round :any] [:design-seq :any]] :map]})
  (Operation decision? "Whether a verdict is one."
    {:signature [:=> [:catn [:v :any]] :boolean]})
  (Operation still-open "The findings that actually remain."
    {:signature [:=> [:catn [:findings :any]] :any]})
  (Operation open-across-run
    "Everything the run is still OWED when it ends, folded over every round. A count answers
     'did this finish clean' and cannot answer 'what is it waiting for'.

     Owed is `review-stages/settled?`, which is the same question convergence asks. Reading it
     as 'not closed' instead let one run report as still open the finding its own convergence
     check had settled."
    {:signature [:=> [:catn [:final :map]] :any]})
  (Operation kept-across-run
    "What the run DECIDED to live with — the defects it declined and the layer claims it let
     stand. Nobody owes anything on these, which is what keeps them out of `open-across-run`
     and what makes them easy to lose: a decision to ship a known defect is precisely what a
     record is for."
    {:signature [:=> [:catn [:final :map]] :any]})
  (Operation kept-by-the-verdict
    "The judge's own half of that remainder: the `:needs` of a verdict that asks nobody to
     decide anything.

     Same shape as a decline — a located defect the branch ships, owed to no one — and until it
     was counted a `sound` verdict naming three of them published `0 still open` with nothing
     kept beside it. Read off the REPORT, not the round: the pass judges the whole run and so
     answers after it, which is also why the `:review` ledger entry can never carry this and the
     analysis payload is where the two halves are summed.

     Never from a decision. Those put their `:needs` to a person and reach the gate as a
     blocker; counted here they would read as something already settled."
    {:signature [:=> [:catn [:report :map]] [:maybe :string]]
     :delegates [decision?]})
  (Operation settled-by-fixing
    "The defects the run REMOVED: findings a repair was aimed at and that no later reviewer
     raised again.

     The third reading of the one fold, beside `open-across-run` and `kept-across-run`, and
     the only count of the run's output with evidence under it — a fixer's success report is a
     claim about its own work, and the round after it is the check. What the loop DISPATCHED
     is a different and larger number: a handle handed out in three rounds is three repairs
     and at most one defect, and publishing the first as the second overstates every run by
     roughly its own persistence.

     A run's last round contributes nothing here by construction: its repairs are exactly the
     ones nobody has read, which is what `handed-to-a-fixer` is for."
    {:signature [:=> [:catn [:final :map]] :any]})
  (Operation handed-to-a-fixer
    "The findings a landed fix commit named as its own, across every round.

     The other half of `open-across-run`. A remainder whose repair is already in the branch and
     a remainder no fixer was launched for are both open, and one count for both is the whole of
     what a run that aborted its fix plan reported."
    {:signature [:=> [:catn [:final :map]] :any]})
  (Operation handed?
    "Whether a repair for this finding is sitting in the branch, unverified."
    {:signature [:=> [:catn [:handed :any] [:f :any]] :boolean]})
  (Operation still-answers?
    "Whether a standing verdict is still this run's answer, so no agent need be launched at all.

     Three things could move a judgment: the record it judges, the code it reads and the
     findings it classifies. The first is held fixed by matching :design-seq; this asks the
     other two, and holds when the run raised nothing, decided nothing and dispatched no fix.
     Never for a DECISION — a question owed to a human is re-asked rather than re-asserted
     unlooked-at."
    {:signature [:=> [:catn [:prior :any] [:final :map] [:report :any]] :boolean]
     :delegates [decision? open-across-run kept-across-run]})
  (Operation carried-forward
    "A standing verdict re-stated as this run's, marked with the entry an agent actually
     reached it at. The mark is what keeps the ledger honest: six unmarked identical verdicts
     claim six readings of the code, and only the first of them is one."
    {:signature [:=> [:catn [:prior :map] [:rounds :int]] :map]})
  (Operation run!
    "Run the verdict pass, or carry the standing one when this run gave it nothing to revisit."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [build-prompt parse still-open still-answers? carried-forward]}))

(Module review-layers
  "The session's stack of layers, and the reshaping the review may do to it.

   A layer's identity is its PATCH, not its revision — so a rebase is invisible to the review.
   Reshaping is guarded by `attempt-reshape!`, which keeps what a reshape did only if it worked:
   an operation that half-moved a stack is worse than one that did not move it."
  (Operation stack "This session's layers, bottom first."
    {:signature [:=> [:catn [:cwd Path] [:session :any] [:base :any]] :any]})
  (Operation ranges "Each layer paired with the range it covers."
    {:signature [:=> [:catn [:stack :any] [:base-rev :any]] :any]})
  (Operation contribution
    "A range's diff with the coordinates saying where it sits taken out."
    {:signature [:=> [:catn [:git-diff :string]] :string]})
  (Operation patch-hash "The identity of what a range changes."
    {:signature [:=> [:catn [:cwd Path] [:from :any] [:to :any]] :string]
     :delegates [contribution]})
  (Operation description "A revision's full message."
    {:signature [:=> [:catn [:cwd Path] [:rev :any]] :string]})
  (Operation parse-brief "A layer message as its review brief."
    {:signature [:=> [:catn [:description :string]] :map]})
  (Operation brief "A layer's review brief."
    {:signature [:=> [:catn [:cwd Path] [:rev :any]] :map] :delegates [description parse-brief]})
  (Operation position-for-fix! "Put the working copy on a layer so a fix lands in it."
    {:signature [:=> [:catn [:cwd Path] [:layer :map]] :any]})
  (Operation land-fix! "Turn the working copy into the layer's new content."
    {:signature [:=> [:catn [:cwd Path] [:layer :map] [:msg :string]] :any]})
  (Operation restore-top! "Return the working copy to the top of the stack."
    {:signature [:=> [:catn [:cwd Path] [:stack :any]] :any]})
  (Operation current-op "The id of the repository's latest operation."
    {:signature [:=> [:catn [:cwd Path]] :string]})
  (Operation conflicted "The changes in this stack that are conflicted."
    {:signature [:=> [:catn [:cwd Path] [:base :any]] :any]})
  (Operation restore-op! "Put the repository back as it was at an operation."
    {:signature [:=> [:catn [:cwd Path] [:op :string]] :any]})
  (Operation attempt-reshape!
    "Run a reshape and keep it only if it worked — a half-moved stack is worse than an unmoved
     one, and the operation log is what makes undo exact rather than approximate."
    {:signature [:=> [:catn [:cwd Path] [:base :any] [:f :any]] :map]
     :delegates [current-op restore-op! conflicted]})
  (Operation reorder! "Move a layer to sit directly on another."
    {:signature [:=> [:catn [:cwd Path] [:base :any] [:layer :map] [:other :map]] :map]
     :delegates [attempt-reshape!]})
  (Operation fold! "Squash a layer into another."
    {:signature [:=> [:catn [:cwd Path] [:base :any] [:layer :map] [:into-layer :map]] :map]
     :delegates [attempt-reshape!]})
  (Operation workspace-relative "A reviewer's absolute path as jj will read it."
    {:signature [:=> [:catn [:cwd Path] [:path :string]] :string]})
  (Operation layer-touches? "Whether a layer's own diff names a path."
    {:signature [:=> [:catn [:cwd Path] [:layer :map] [:path :string]] :boolean]})
  (Operation move!
    "Move one file's changes from a layer down into another. The remedy for a seam whose ends
     are not adjacent, where a fold would absorb every layer between them."
    {:signature [:=> [:catn [:cwd Path] [:base :any] [:layer :map] [:into-layer :map]
                            [:path :string]] :map]
     :delegates [workspace-relative layer-touches? attempt-reshape!]})
  (Operation resolve-rev
    "The commit a revision names right now, or nil when the workspace cannot be asked. Pins @
     once per round, since @ is whatever the working copy happens to be."
    {:signature [:=> [:catn [:cwd Path] [:rev :string]] [:maybe :string]]})
  (Operation descends-from?
    "Whether the working copy is still at or above a revision — false means somebody moved the
     tree under a round in flight. TRUE when the workspace cannot be asked: a guard that cannot
     run must not become a failure of the thing it guards."
    {:signature [:=> [:catn [:cwd Path] [:rev :string]] :boolean]}))

(Module review-stages
  "What a round reviews, in what order, and what it does with the rulings.

   `to-review` against the cache is what makes a re-run cheap: a target whose patch already
   converged is skipped, and announcing the skip is what stops that looking like a pass that
   silently did nothing."
  (Operation settled? "Whether a finding has been decided."
    {:signature [:=> [:catn [:f Finding]] :boolean]})
  (Operation kept?
    "Whether a decided finding is still TRUE of the branch — a decline or a deviation, as
     against a close, which was a duplicate or out of scope and leaves nothing to carry."
    {:signature [:=> [:catn [:f Finding]] :boolean]})
  (Operation repair-attempted?
    "Whether the round that ruled a finding aimed a repair at it — false for a `park`, which
     answers the finding by putting it to a human and launches nothing.

     The engine's give-up counter asks how many repairs were tried and failed, and it cannot
     read a disposition itself, so this is what the diff loop hands it."
    {:signature [:=> [:catn [:f Finding]] :boolean]})
  (Operation parse-warden-decision
    "The warden's ruling out of what it said, judged against the design record it was shown.

     The record is an argument rather than a read because the check it feeds is a substring
     test: a `because` appealing to an invariant has to quote one, and the list quoted from
     is the list the prompt rendered. Re-reading it here would let a failure to match mean
     an amendment landing mid-run instead of a restatement. The one-argument arity is for
     callers holding no record, where every such appeal is refused."
    {:signature [:function
                 [:=> [:catn [:text :string]] :map]
                 [:=> [:catn [:text :string] [:design [:maybe :map]]] :map]]})
  (Operation warden-failure "Why a round has no ruling: no run, no answer, or no parse."
    {:signature [:=> [:catn [:launch :map] [:decision :map]] :map]})
  (Operation project+ws-from-cwd "The project and workstream a directory belongs to."
    {:signature [:=> [:catn [:cwd Path]] :any]})
  (Operation session-stack "This session's layers, bottom first."
    {:signature [:=> [:catn [:cwd Path] [:base :any]] :any]})
  (Operation in-parallel "Run thunks, at most n at once."
    {:signature [:=> [:catn [:n :int] [:thunks :any]] :any]})
  (Operation composition-of "What the whole-stack pass is given."
    {:signature [:=> [:catn [:cwd Path] [:targets :any]] :any]})
  (Operation review-targets "What this round reviews."
    {:signature [:=> [:catn [:cwd Path] [:base :any]] :any] :delegates [session-stack]})
  (Operation with-patch-hashes "Each target stamped with the identity of its patch."
    {:signature [:=> [:catn [:cwd Path] [:targets :any]] :any]})
  (Operation to-review "Targets split into those to review and those already converged."
    {:signature [:=> [:catn [:cache :map] [:targets :any]] :map]})
  (Operation content-hashes
    "What the branch holds this round, as a set of patch hashes — the SKIPPED targets included,
     since a layer left alone because it converged is still part of what the branch contains.
     A target nothing could hash contributes nothing, so a round jj could not diff produces the
     empty set rather than a false reading."
    {:signature [:=> [:catn [:targets :any]] :any]})
  (Operation announce-targets! "Publish what this round is reviewing and what it skipped."
    {:signature [:=> [:catn [:ctx :map] [:split :map]] :any]})
  (Operation round-correctness
    "The round's correctness verdict: the worst answer its reviewers gave, or nil when none
     reached one. A round has as many verdicts as it opened targets and the whole-stack pass's
     is not the round's — on a layered stack that pass reviews the CUT rather than the code, so
     publishing its answer alone put `correct` on a report row whose own findings array held a
     P1. A dissent carries through VERBATIM: an answer outside the vocabulary is a reviewer
     that said something, and normalising it would hide that it was never asked for."
    {:signature [:=> [:catn [:results :any]] [:maybe :string]]})
  (Operation discover-design-record "This workstream's latest design record."
    {:signature [:=> [:catn [:cwd Path]] [:maybe :map]]})
  (Operation discover-baseline
    "The baseline a design CITED, not the newest one. A design committed to a particular
     reading, and judging it against a later baseline checks it against a premise it never made."
    {:signature [:=> [:catn [:cwd Path] [:design :map]] [:maybe :map]]})
  (Operation discover-prior-verdict
    "The verdict this workstream last recorded against the SAME design record. Matched on
     :design-seq for the reason `discover-baseline` follows a citation rather than reading the
     newest: a verdict against a superseded record answered a different question, and offering
     it as a standing answer would have the judge defend a yardstick nobody is using."
    {:signature [:=> [:catn [:cwd Path] [:design :map]] [:maybe :map]]})
  (Operation standing-needs
    "What the last verdict against this workstream's design record left outstanding, from a
     verdict that leaves the design STANDING. The verdict pass runs after the loop returns, so
     nothing in the run that produced one can act on it; this is what carries it to the next
     run's reviewers, which are the only agents that can turn it into a finding. A verdict
     that INVALIDATES puts its :needs to a person instead, and seeding that would have a fixer
     patch the question somebody was asked to answer."
    {:signature [:=> [:catn [:cwd Path]] [:maybe :map]]
     :delegates [discover-design-record discover-prior-verdict]})
  (Operation prior-open
    "What the LAST review of this workstream left owed, as ledger rows carrying the layer each
     is owed of. Every run writes that list and, until this, no run read it — so an obligation
     the loop itself recorded reached the next run through no channel: the reviewer of the
     file holding it started blank, the layer took an irrevocable :converged mark over it, and
     the design verdict was carried forward as though the run had produced no evidence. One
     read, three refusals. The carry is one hop — a row an earlier run already inherited is
     dropped — because a defect whose layer was handed to a reviewer and still went unreported
     is not evidence enough to hold a branch open for ever."
    {:signature [:=> [:catn [:cwd Path]] :any]})
  (Operation with-prior-open
    "Each layer's reviewer told what the last run left owed against THAT layer, matched by
     label: a repair moves the patch a hash is taken over, so a hash cannot carry an
     obligation across the repair it is asking for. A park is withheld — it is a question
     already put to a human — and so is the composition pass, as with `with-standing-needs`."
    {:signature [:=> [:catn [:targets :any] [:inherited :any]] :any]})
  (Operation unanswered-of
    "The inherited rows this run has said nothing about. Pure. Answered means RULED, not
     repaired: from the moment a reviewer raises one again the run's own accounting decides
     what is owed on it, and carrying the inherited copy beside it would count one defect
     twice."
    {:signature [:=> [:catn [:inherited :any] [:rounds :any]] :any]})
  (Operation unanswered-inherited
    "The same question asked of a terminal ctx — what the last run left owed that this whole
     run neither raised nor answered. Three readers: the round that ends quiet may not call
     itself clean while it holds one, the ledger entry carries them so the next run inherits
     what this one could not settle, and the design verdict is not carried forward over one."
    {:signature [:=> [:catn [:final :map]] :any]
     :delegates [unanswered-of]})
  (Operation deny-inherited-convergence
    "The same [target status] pairs with :converged downgraded to :partial on every target an
     unanswered inherited finding names. Pure. :converged is not granted by an agent and
     cannot be revoked by one, so a layer that takes it while a known defect stands in it is
     exempt from the code lane until somebody happens to edit the file."
    {:signature [:=> [:catn [:statuses :any] [:unanswered :any]] :any]})
  (Operation stance-path "Where a project's stance text lives."
    {:signature [:=> [:catn [:project ProjectName]] Path]})
  (Operation read-stance "A project's stance text."
    {:signature [:=> [:catn [:project ProjectName]] [:maybe :string]] :delegates [stance-path]})
  (Operation answered-by-layer
    "What earlier rounds already answered, for the layers UNDER REVIEW — which is why the
     recording side must cover more than convergence: a converged patch is one this round
     skips, so a store of only those is a store this can never read."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation converged-targets
    "The targets this round left owing nothing — no open finding names them and no carried
     park does either. Pure. The parks are a separate argument because they are the half a
     round's own findings cannot carry: a park is raised once and lives in the carry after."
    {:signature [:=> [:catn [:reviews :any] [:findings :any] [:parks :any]] :any]})
  (Operation reopened-patches
    "The patches of targets this round SKIPPED that something owed still names — the recorded
     convergences this round has falsified. Pure. `converged-targets` over the complement, and
     the half that was missing: only a target a reviewer READ ever rewrites its entry, so a
     defect attributed to a layer whose patch was already converged was owed by a layer nothing
     would look at again, and the attribution changed nothing. That layer is where a promotion
     most often points — a sibling survives a sweep precisely where no reviewer has been."
    {:signature [:=> [:catn [:skipped :any] [:findings :any] [:parks :any]] :any]})
  (Operation reviewed-statuses
    "Every reviewed target paired with the status its patch is left at — converged when it owes
     nothing, partial when it still does. Only the first is a skip; the second is the entry a
     next round comes back to, and so the only one whose answers anything reads."
    {:signature [:=> [:catn [:reviews :any] [:findings :any] [:parks :any]] :any]
     :delegates [converged-targets]})
  (Operation salvaged-statuses
    "The same, for a round that ABORTED mid-fan-out. No warden ran, so no finding carries an
     owner and convergence cannot be asked the usual way; what a reviewer that returned SAID
     about its own patch can be, and a clean bill is not retracted by a sibling losing its
     reviewer. The composition target never converges here — it converges on nothing anywhere
     being open, which a round holding a target nobody read cannot know."
    {:signature [:=> [:catn [:results :any]] :any]})
  (Operation answered-for
    "What one target reported and the run settled, folded over every round. The converging
     round is the one least likely to hold anything: a run ends by finding nothing."
    {:signature [:=> [:catn [:label :any] [:rounds :any]] :any]})
  (Operation record-review!
    "Write what this round left each target at into the cache: for the ones a reviewer read,
     its status and what the run has settled about it; for the ones it skipped, the revocation
     of any convergence the round has since falsified."
    {:signature [:=> [:catn [:cwd Path] [:ctx :map]] :any]
     :delegates [reviewed-statuses reopened-patches answered-for
                 deny-inherited-convergence unanswered-of]})
  (Operation resolve-handle "The identity a finding is filed under."
    {:signature [:=> [:catn [:handles :any] [:f Finding]] :any]})
  (Operation apply-rulings "The warden's per-finding rulings, merged in."
    {:signature [:=> [:catn [:findings :any] [:rulings :any] [:handles :any]] :any]
     :delegates [resolve-handle]})
  (Operation promoted-findings
    "The warden's `promote` entries as ruled findings of this round — a sibling a fixer named
     and could not touch, placed on a layer and dispositioned `fix`, so it is handed out now
     rather than waiting for a fresh reviewer to rediscover it.

     The REPORTER is the fixer, and that bound is what keeps every finding one that something
     which read the code raised: the fixer made the repair the sibling survived and diagnosed
     it in its own account, and what the warden adds is the layer. An entry naming no place,
     or naming a defect a finding of this round already reports, is refused."
    {:signature [:=> [:catn [:handles :any] [:findings :any] [:promotions :any]] :any]
     :delegates [resolve-handle finding-id]})
  (Operation seen-findings "Every finding an earlier round saw."
    {:signature [:=> [:catn [:history :any]] :any]})
  (Operation working-copy-dirty? "Whether the working copy has uncommitted changes."
    {:signature [:=> [:catn [:cwd Path]] :boolean]})
  (Operation working-copy-state "What the working copy currently is."
    {:signature [:=> [:catn [:cwd Path]] :map]})
  (Operation layer-label "A layer's label."
    {:signature [:=> [:catn [:layer :map]] :string]})
  (Operation reshape-plan
    "What to do about one finding that asks for a reshape, or why nothing can be."
    {:signature [:=> [:catn [:stack :any] [:finding Finding]] :map]})
  (Operation fix-plan "The findings the warden disposed of into fixes, by layer."
    {:signature [:=> [:catn [:stack :any] [:findings :any]] :any] :delegates [layer-label]})
  (Operation settled-by-layer
    "What the run has already SETTLED, by the layer whose fixer would meet it — this round's
     own rulings included, because the warden rules inside the round the fixers then run in.
     The companion to `fix-plan` over the same map of the stack: that one routes the work and
     this one routes the decisions the work must not undo. A fixer shown only the first honours
     a kept deviation by accident or not at all, and editing one retracts nothing — the ruling
     stands, so the branch and the run's published account of it come apart unwatched."
    {:signature [:=> [:catn [:stack :any] [:rounds :any]] :any]
     :delegates [layer-label settled?]})
  (Operation layer-fixer-session
    "A stable session id per layer, so a fixer resumed twice continues rather than restarts."
    {:signature [:=> [:catn [:impl-session-id :any] [:label :any]] :string]})
  (Operation with-composition-memory
    "The whole-stack target, told what it has already reported. Without it the composition pass
     re-derives the same seam every round from an empty memory."
    {:signature [:=> [:catn [:targets :any] [:history :any]] :any]})
  (Operation fix-accounts
    "Every repair this run landed, grouped by the layer it landed on: the round, the commit,
     the findings that commit was handed, and what the fixer said. Two readers, which is why
     it is a step of its own — the target's own layer's entries go to that layer's next
     reviewer, and ALL of them go to the warden, because a sibling a fixer names in an account
     is by construction somewhere its own layer's reviewer cannot go."
    {:signature [:=> [:catn [:history :any]] :any]})
  (Operation with-fix-memory
    "Each target told what a fixer already aimed at it in this run — the repairs that landed
     and the ones the stack put back — and what the fixer said about each. Nothing else in the
     loop asks whether a repair closed what it was handed: the reviewer that would is shown a
     diff and no history, so a swept defect comes back at the lines the fix was made on, and a
     REFUSED repair leaves the range byte-identical with nothing at all to notice."
    {:signature [:=> [:catn [:targets :any] [:history :any] [:refused :any]] :any]
     :delegates [fix-accounts]})
  (Operation with-standing-needs
    "Each code-reading reviewer told what the last run's verdict left outstanding — never the
     composition pass, which is asked whether the cut holds and is told not to report what the
     layer reviews are already holding."
    {:signature [:=> [:catn [:targets :any] [:standing :any]] :any]})
  (Operation with-sweep-memory
    "Each finding told which earlier rounds already swept its class. A class that comes back
     has disproved the enumerate-the-instances remedy, and the fixer is the reader who could
     act on that and the only one the run's history never reaches."
    {:signature [:=> [:catn [:findings :any] [:history :any]] :any]})
  (Operation carried-parks
    "The open parks this run is holding, each with the round it was first parked in and the
     layer it names. A park is never raised again, so it vanishes from the findings the moment
     the reviewer stops mentioning it — carrying it is what stops the warden re-adjudicating
     one from scratch and what keeps its layer out of the convergence cache."
    {:signature [:=> [:catn [:prior :any] [:ruled :any] [:iter :int]] :any]})
  (Operation carried-while-open
    "A per-layer carry, dropped as this round settles what each entry is about. Two channels
     are carried this way and neither is a ruling: a fixer's argument for refusing work it was
     handed, and a repair the stack would not take. Both leave their finding at :fix and the
     code as it was, so without the carry each reaches the report and neither the warden that
     could settle it nor the next reviewer that would have to find it again."
    {:signature [:=> [:catn [:prior :any] [:ruled :any]] :any]})
  (Operation park-refused-recuts
    "A park for every recut the reshape stage could not act on, carrying its own refusal. The
     warden withholds a recut from the fixers on purpose, so a refusal leaves it with no path."
    {:signature [:=> [:catn [:parks :any] [:outcomes :any] [:iter :int]] :any]})
  (Operation round-changed?
    "Whether the round before this one moved the code: it landed repairs, AND the content this
     round's reviewers read differs from what the last round's did. What the engine's stall
     check cannot ask for itself — a repeated finding set is a stall only if nothing moved,
     and a defect CLASS being narrowed repeats its handles by construction."
    {:signature [:=> [:catn [:ctx :map] [:prior :any]] :boolean]}))
