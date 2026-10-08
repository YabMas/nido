(ns canvas.review.passes
  "Self-spec: the review passes themselves — reviewer launch, the diff pass, the verdict, the layer
   stack, the stage orchestration, and the record rounds."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.review.core :refer [Finding]]
            [fukan.common.typing.malli]))

(Module review-claude
  "Claude as a read-only reviewer, behind the contract codex's runner keeps: a prompt and a
   schema in, the structured answer in a file, the stream in a log.

   Read-only comes from the CLI — restricted mode, a shell allowlist of the two jj reads and grep
   beside what the CLI itself runs as read-only, and everything else denied — never from the
   prompt, and never from the user's settings, which it ignores."
  (Operation claude-argv "The argument vector for one claude review."
    {:signature [:=> [:catn [:opts :map]] :any]})
  (Operation run-claude! "Run claude as a reviewer and keep its answer."
    {:signature [:=> [:catn [:opts :map]] :map] :delegates [claude-argv]}))

(Module review-codex
  "Launching a reviewer: codex unless a run or its project names claude, and claude in codex's
   place when codex has run out of quota — and, when none could be run, why, in the vendor's words.

   Everything that judges is launched through `run-reviewer!` with a prompt and an answer schema,
   and nothing past it knows which reviewer ran except by the `:judged-by` it answers with."
  (Operation codex-argv "The argument vector for one codex run."
    {:signature [:=> [:catn [:opts :map]] :any]})
  (Operation run-codex! "Run codex and read its output."
    {:signature [:=> [:catn [:opts :map]] :map] :delegates [codex-argv]})
  (Operation unavailability
    "Why no reviewer ran, out of the log the reviewer streamed — or nothing, when the log does not say
     the reviewer was unavailable.

     The distinction the review path exists to draw at all: a review that BROKE is evidence
     about the branch and a reviewer that could not be RUN is evidence about a quota, a rate
     limit, a credential or a model at capacity, and only one of them is answered by opening the diff. Carries the
     line verbatim, because the remedy and the reset hour are in the vendor's words and nowhere
     else."
    {:signature [:=> [:catn [:tail [:maybe :string]]] [:maybe :map]]})
  (Operation last-line
    "The last non-blank line of a reviewer's log — what a failure nothing classifies is reported
     by, since the reviewer's own last words are usually its error."
    {:signature [:=> [:catn [:log-path [:maybe :string]]] [:maybe :string]]})
  (Operation reviewer-for
    "The reviewer a run is judged by: its own choice, else its project's, else codex. A name that
     is no reviewer is refused, never read as the default."
    {:signature [:=> [:catn [:override :any] [:configured :any]] :keyword]})
  (Operation run-reviewer!
    "Run the chosen reviewer, again after a short wait while its vendor says it is at capacity,
     and when codex could not be run for want of quota, claude in its place on the same prompt —
     saying which one judged, and, when the last run could not be run at all, why."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [run-codex! run-claude! unavailability]}))

(Module review-pass
  "The diff review pass: one revision range reviewed against the design, and what the reviewer
   said read back as findings.

   Findings are identified by WHAT they are about — file, line, title — not by when they were
   found, so the same finding raised by two passes is one finding and a finding that survives a
   fix is recognisably the same one."
  (Operation finding-id "A finding's identity, derived from what it is about."
    {:signature [:=> [:catn [:f Finding]] :string]})
  (Operation normalize-finding "A reviewer's native finding in nido's shape."
    {:signature [:=> [:catn [:raw :map]] Finding]})
  (Operation parse-output "A reviewer's structured output as findings."
    {:signature [:=> [:catn [:json-str :string]] :any] :delegates [normalize-finding]})
  (Operation composition-schema "The findings schema for the whole-stack pass."
    {:signature [:=> [:catn [:base :any]] :any]})
  (Operation schema-json "The answer schema to hand a reviewer."
    {:signature [:=> [:catn [:composition? :boolean]] :string] :delegates [composition-schema]})
  (Operation merge-base "The merge base a range is measured from."
    {:signature [:=> [:catn [:cwd Path] [:base :any]] :string]})
  (Operation changed-files "The files a range touches."
    {:signature [:=> [:catn [:cwd Path] [:from :any] [:to :any]] :any]})
  (Operation safe-label "A label made safe to put in a path."
    {:signature [:=> [:catn [:label :any]] :string]})
  (Operation review! "Review one range and answer with its findings."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [run-reviewer! last-line schema-json safe-label]}))

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
  (Operation still-owed
    "What the WORKSTREAM is still owed when a run ends: the run's own remainder, then every row
     the last run left owed that this one neither raised nor answered, marked inherited.

     The one derivation of the remainder. The status a run stops as, its ledger entry, the
     analysis headline, the parked-blocker gate and the carried verdict all read it, because
     each used to derive its own: one run published `0 still open` beside an entry holding
     five, and a run whose only debts were a park from round 1 and an inherited row stopped
     `converged`."
    {:signature [:=> [:catn [:final :map]] :any]
     :delegates [open-across-run]})
  (Operation kept-across-run
    "What the run DECIDED to live with — the defects it declined and the layer claims it let
     stand. Nobody owes anything on these, which is what keeps them out of `open-across-run`
     and what makes them easy to lose: a decision to ship a known defect is precisely what a
     record is for. Rulings only: a defect the design judge found and no round raised was
     decided by nobody, and the analysis counts it open."
    {:signature [:=> [:catn [:final :map]] :any]})
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
  (Operation settled-the-loop-made
    "The part of `settled-by-fixing` the loop's own repairs had put on the branch, on the
     warden's `:introduced-by-round` attribution.

     A defect a fixer creates and the next round removes is settled like any other, so a run
     whose repairs kept breaking what they touched read as a run that found and fixed more.
     The attribution is a judgement, not a diff, so an unattributed defect is one nobody named
     as the loop's, not one proven to be the branch's."
    {:signature [:=> [:catn [:final :map]] :any]
     :delegates [settled-by-fixing]})
  (Operation handed-to-a-fixer
    "The findings a landed fix commit named as its own, across every round.

     The other half of `open-across-run`. A remainder whose repair is already in the branch and
     a remainder no fixer was launched for are both open, and one count for both is the whole of
     what a run that aborted its fix plan reported."
    {:signature [:=> [:catn [:final :map]] :any]})
  (Operation handed?
    "Whether a repair for this finding is sitting in the branch, unverified."
    {:signature [:=> [:catn [:handed :any] [:f :any]] :boolean]})
  (Operation ledger-findings
    "Findings trimmed to the ledger's rows — what a reader of the workstream needs and nothing
     that only makes sense inside a run — each marked where a repair for it is already in the
     branch, unread."
    {:signature [:=> [:catn [:handed :any] [:findings :any]] :any]
     :delegates [handed?]})
  (Operation owed-rows
    "What the run leaves owed, as the ledger's `:open` rows — `still-owed`, trimmed. The one
     list the `:review` entry, the settled entry, the analysis headline and the parked-blocker
     gate count, so the remainder a person scans and the one they open cannot disagree."
    {:signature [:=> [:catn [:final :map]] :any]
     :delegates [still-owed handed-to-a-fixer ledger-findings]})
  (Operation still-answers?
    "Whether a standing verdict is still this run's answer, so no agent need be launched at all.

     Three things could move a judgment: the record it judges, the code it reads and the
     findings it classifies. The first is held fixed by matching :design-seq; this asks the
     other two, and holds when the verdict's `:patch-hashes` are this run's final tree, and the
     run raised nothing, decided nothing and dispatched no fix.
     Never for a DECISION — a question owed to a human is re-asked rather than re-asserted
     unlooked-at."
    {:signature [:=> [:catn [:prior :any] [:final :map] [:report :any]] :boolean]
     :delegates [decision? still-owed kept-across-run]})
  (Operation carried-forward
    "A standing verdict re-stated as this run's, marked with the entry an agent actually
     reached it at. The mark is what keeps the ledger honest: six unmarked identical verdicts
     claim six readings of the code, and only the first of them is one. It keeps the round it
     was reached after, which is the round its reason talks about."
    {:signature [:=> [:catn [:prior :map]] :map]})
  (Operation against-the-run
    "A parsed verdict reconciled with the run it judged: an `:unraised` row naming a finding the
     run raised is dropped, an invariant held over a still-open finding that contradicts it is
     `:invariants-unmet`, an invariant the prior verdict named unmet stays so while a park is
     open unless the judge names what changed, standing answers are matched to the warden's
     items by index, and the tree the judge read is stamped as `:patch-hashes` when it is known."
    {:signature [:=> [:catn [:verdict :map] [:final :map] [:standing :any] [:prior [:maybe :map]]] :map]
     :delegates [open-across-run]})
  (Operation outside-the-change
    "A verdict with every `:unraised` row whose file the reviewed range does not touch moved
     onto its `:needs`. `:unraised` is what the next run's reviewers are handed, and a row no
     reviewer of the branch can raise would be copied forward as kept, run after run; a person
     is who can act on it."
    {:signature [:=> [:catn [:verdict :map] [:files :any]] :map]})
  (Operation answers-standing?
    "Whether a verdict's standing answers retire one of a run's standing items. By the item
     itself, carried on the answer: matched as printed text, the answer retired nothing and
     every item a verdict answered was published open again beside its answer."
    {:signature [:=> [:catn [:answered :any] [:item :any]] :boolean]})
  (Operation settled
    "The terminal ctx with the verdict's answers folded in, so every reader of the remainder
     reads one answered set: an inherited row it answers by id is marked answered, a warden
     close it contests reopens as a park, a standing item it answers leaves the standing lists,
     and the status is read again. Runs before the `:review` entry publishes the status — the
     verdict is the only reader a quiet run has, and without this a run ended `unresolved` and
     asked a person about rows its own verdict had found repaired."
    {:signature [:=> [:catn [:final :map] [:verdict [:maybe :map]]] :map]
     :delegates [still-owed answers-standing?]})
  (Operation run!
    "Run the verdict pass, or carry the standing one when this run gave it nothing to revisit."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [build-prompt parse still-open still-answers? carried-forward against-the-run
                 outside-the-change]}))

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
  (Operation deviation-line "One deviation as its layer's commit message carries it."
    {:signature [:=> [:catn [:finding :map]] [:maybe :string]]})
  (Operation with-deviations
    "A layer message with the deviations it does not yet carry appended."
    {:signature [:=> [:catn [:description :string] [:lines [:sequential :string]]] :string]})
  (Operation record-deviations!
    "Write each settled deviation onto the layer whose claim it qualifies, so the sentence
     that says the claim did not hold ships in the same message as the claim."
    {:signature [:=> [:catn [:cwd Path] [:stack :any] [:findings :any]] :any]
     :delegates [deviation-line with-deviations description]})
  (Operation refusal
    "The failure a jj call on the fix path throws when jj exits non-zero — never an answer read
     off its empty output. A stale refusal names `jj workspace update-stale` ahead of any other
     remedy, since until it has run every jj command fails the same way."
    {:signature [:=> [:catn [:what :string] [:result :map] [:data :map]
                            [:then [:? [:maybe :string]]]] :any]})
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
    {:signature [:=> [:catn [:cwd Path] [:rev :string]] :boolean]})
  (Operation stale?
    "Whether jj refuses the working copy as stale — its commit rewritten by another operation
     that left the files as they were. Asked apart from descent, whose check reads the same
     refusal as a move and so cannot say that this one needs `jj workspace update-stale`."
    {:signature [:=> [:catn [:cwd Path]] :boolean]})
  (Operation working-copy-patch
    "The files on disk against a commit's tree as a git patch, read without jj — the one
     reading a stale working copy still allows, and the only copy of edits no commit holds."
    {:signature [:=> [:catn [:cwd Path] [:from [:maybe :string]]] [:maybe :string]]}))

(Module review-stages
  "What a round reviews, in what order, and what it does with the rulings.

   `to-review` against the cache is what makes a re-run cheap: a target whose patch already
   converged is skipped, and announcing the skip is what stops that looking like a pass that
   silently did nothing."
  (Operation default-finding-key "How the diff review tells two findings apart."
    {:signature [:=> [:catn [:f Finding]] :any]})
  (Operation default-attempt-key
    "How the engine's give-up counter tells one ATTEMPT at a diff finding from another.

     The finding key alone counts APPEARANCES, so a finding re-routed to a layer that can
     actually fix it spends the counter on the round that first aimed it correctly."
    {:signature [:=> [:catn [:finding-key :any]] :any]})
  (Operation settled? "Whether a finding has been decided."
    {:signature [:=> [:catn [:f Finding]] :boolean]})
  (Operation kept?
    "Whether a decided finding is still TRUE of the branch — a decline or a deviation, as
     against a close, which was a duplicate or out of scope and leaves nothing to carry."
    {:signature [:=> [:catn [:f Finding]] :boolean]})
  (Operation repair-attempted?
    "Whether the round that ruled a finding aimed a repair at it — false for a `park`, which
     answers the finding by putting it to a human and launches nothing, and false for a finding
     the fix stage handed to a fixer that never started, which it stamps as it files the launch.

     The engine's give-up counter asks how many repairs were tried and failed, and it cannot
     read a disposition or a launch itself, so this is what the diff loop hands it. It draws
     the line the report's published dispatch count draws, off the same reading: the count a
     run publishes and the count that ends it are of one set of attempts."
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
  (Operation wrote-tool-calls?
    "Whether an answer from a launch holding no tools wrote a tool call out as text, outside
     its json block. Such an answer is unusable whatever its json says: the call ran nothing,
     so what follows it may rest on output the model invented."
    {:signature [:=> [:catn [:text [:maybe :string]]] :boolean]})
  (Operation warden-failure "Why a round has no ruling: no run, no answer, or no parse."
    {:signature [:=> [:catn [:launch :map] [:decision :map]] :map]})
  (Operation project+ws-from-cwd "The project and workstream a directory belongs to."
    {:signature [:=> [:catn [:cwd Path]] :any]})
  (Operation session-stack "This session's layers, bottom first."
    {:signature [:=> [:catn [:cwd Path] [:base :any]] :any]})
  (Operation in-parallel "Run every thunk at once, preserving order."
    {:signature [:=> [:catn [:thunks :any]] :any]})
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
  (Operation discover-design-record
    "This workstream's latest design record, unless it is DELIVERED — a :merged follows it and
     no phase is left. Then it is absent, and the diff loop refuses as for a workstream holding
     none: the diff on the branch is other work, and every invariant of the landed record holds
     vacuously over it."
    {:signature [:=> [:catn [:cwd Path]] [:maybe :map]]})
  (Operation delivered-design
    "The latest design record when it is delivered, else nil — what `discover-design-record`
     declines, named so the refusal can say why a workstream holding a design has no yardstick."
    {:signature [:=> [:catn [:cwd Path]] [:maybe :map]]})
  (Operation named-locations
    "The places in the code a design and its cited baseline name, as path fragments: the files
     the baseline read, and the file paths and namespaces the design's text mentions. Evidence
     of what the record is ABOUT, never a bound on what a change may touch."
    {:signature [:=> [:catn [:design [:maybe :map]] [:baseline [:maybe :map]]] [:set :string]]})
  (Operation off-yardstick
    "A standing item saying a design is not the yardstick of a stack, when the design names
     places in the code and the stack changes none of them — or nil. The verdict pass does not
     run under one: judged against another story's record, every invariant holds vacuously."
    {:signature [:=> [:catn [:design [:maybe :map]] [:baseline [:maybe :map]]
                  [:files [:sequential :string]]] [:maybe :map]]
     :delegates [named-locations]})
  (Operation discover-baseline
    "The baseline a design CITED, not the newest one. A design committed to a particular
     reading, and judging it against a later baseline checks it against a premise it never made."
    {:signature [:=> [:catn [:cwd Path] [:design :map]] [:maybe :map]]})
  (Operation cited-baseline
    "`discover-baseline` on a ledger already resolved. A run reads through the ledger it pinned
     at start, because resolving one again from a directory can answer nothing while the session
     restarts under it."
    {:signature [:=> [:catn [:ledger [:maybe [:tuple :keyword :string]]] [:design [:maybe :map]]] [:maybe :map]]})
  (Operation discover-prior-verdict
    "The verdict this workstream last recorded against the SAME design record. Matched on
     :design-seq for the reason `discover-baseline` follows a citation rather than reading the
     newest: a verdict against a superseded record answered a different question, and offering
     it as a standing answer would have the judge defend a yardstick nobody is using. For a
     phased design, only a verdict reached in the phase the workstream is in now: a gate
     changes what the design owes, so an earlier phase's verdict is as stale."
    {:signature [:=> [:catn [:cwd Path] [:design :map]] [:maybe :map]]})
  (Operation in-files?
    "Whether a verdict row's `where` names a file among a reviewed range's paths. A judge writes
     `where` freehand and abbreviates, so a row matches a path either is a whole trailing
     segment of — never a bare substring, which would hand a reviewer a row about another file."
    {:signature [:=> [:catn [:files :any] [:where :any]] :boolean]})
  (Operation across-amendment
    "The last verdict when it judged a record the current design has since superseded, holding
     only the `:unraised` rows the amendment does not name. Its JUDGMENT answered another
     question, which is why `discover-prior-verdict` refuses it; a row is a defect at a line,
     and an amendment that never mentions the line has not answered it. Never across a phase
     gate."
    {:signature [:=> [:catn [:cwd Path] [:design :map]] [:maybe :map]]})
  (Operation standing-needs
    "What the last verdict left outstanding in the code the round reviews, from a verdict that
     leaves the design STANDING — the one against this workstream's design record, or failing
     that its rows carried `across-amendment`. The verdict pass runs after the loop returns, so
     nothing in the run that produced one can act on it; this is what carries it to the next
     run's reviewers, which are the only agents that can turn it into a finding. Only rows in
     the round's files: a reviewer answers out of range with silence, so any other row could
     only circle. A verdict that INVALIDATES puts its :needs to a person instead, and seeding
     that would have a fixer patch the question somebody was asked to answer."
    {:signature [:=> [:catn [:cwd Path] [:files :any]] [:maybe :map]]
     :delegates [discover-design-record discover-prior-verdict across-amendment in-files?]})
  (Operation last-review
    "The newer of the workstream's last `:review` and last `:review-settled` — what the last run
     left, whether it finished or was settled from its run dir. A run killed mid-fix may be
     holding exactly the rulings the next run needs, and `:review` alone would inherit from the
     run before it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]})
  (Operation fixer-log
    "Where one fixer's log goes, per layer and per round — the only record of which fixer wrote
     what, and so what a settled run's refusal reads to say whether the fixer it stopped under
     had written anything."
    {:signature [:=> [:catn [:run-id :string] [:label [:maybe :string]] [:iter [:maybe :int]]
                  [:suffix :string]] :string]})
  (Operation prior-open
    "What the LAST review of this workstream left owed, as ledger rows carrying the layer each
     is owed of. Every run writes that list and, until this, no run read it — so an obligation
     the loop itself recorded reached the next run through no channel: the reviewer of the
     file holding it started blank, the layer took an irrevocable :converged mark over it, and
     the design verdict was carried forward as though the run had produced no evidence. One
     read, three refusals. A defect's carry is one hop — a row an earlier run already inherited
     is dropped — because a defect whose layer was handed to a reviewer and put before a warden,
     and still went unreported and unruled, is not evidence enough to hold a branch open for
     ever. A PARK is carried until a person answers on the ledger: no quiet run answers a
     question put to one."
    {:signature [:=> [:catn [:cwd Path]] :any]
     :delegates [last-review]})
  (Operation prior-standing
    "What the LAST review of this workstream left standing — open, and handed to nobody. Carried
     as a park is, because it is addressed to a person and no quiet run answers it: until a
     person answers on the ledger, or a warden of the next run, shown the list, restates what
     still holds."
    {:signature [:=> [:catn [:cwd Path]] [:vector :map]]
     :delegates [last-review]})
  (Operation placed-on
    "The layer a finding NO REVIEWER RAISED is owed of: the layer it names when the stack still
     has it, otherwise the highest layer whose files include its file, and nothing when neither
     places it. Two kinds of finding arrive naming their layer rather than read off one — a
     warden's promotion and a row the last run left open — and either can name a layer the
     stack does not have, which leaves it counted open while no convergence is held and no
     reviewer is told. Highest, for a file several layers touch, because nothing above that
     layer changes the file: a repair made there is rebased over no later edit to it."
    {:signature [:=> [:catn [:cwd Path] [:toc :any] [:named :any] [:file :any]] [:maybe :string]]})
  (Operation within-the-fence
    "Every `fix` ruling owned by a layer whose fixer may edit the finding's file: one owned
     below the highest layer touching that file is moved up to it, and says so. The warden
     attributes by where a defect was caused, and a fixer is fenced by where it may write —
     every file a layer above its own touches is not its to edit, because rebasing that layer
     over the edit is what the fix stage rolls back. Only ever up: an owner above every
     toucher was put there on purpose."
    {:signature [:=> [:catn [:cwd Path] [:toc :any] [:findings :any]] :any]
     :delegates [placed-on]})
  (Operation accounts-naming
    "The accounts of fixers that already ran this stage that name one of a layer's files, by
     path or by file name. What a lower fixer could not make because the file is above it is
     what the upper fixer, running later in the same stage, may make — handed over as a claim,
     since no warden has read it yet."
    {:signature [:=> [:catn [:said :any] [:files :any]] :any]})
  (Operation unplaced-standing
    "The standing entry for an inherited row no layer of the stack holds. Matched by value when
     the design verdict answers the row after the loop, so it is built in one place."
    {:signature [:=> [:catn [:row :map]] :map]})
  (Operation place-inherited
    "The last run's open rows placed on this round's stack, and the ones that place nowhere set
     apart so the run can name them in its standing. A flat branch places every row on its one
     reviewer."
    {:signature [:=> [:catn [:cwd Path] [:toc :any] [:rows :any]] :map]
     :delegates [placed-on]})
  (Operation with-prior-open
    "Each layer's reviewer told what the last run left owed against THAT layer, matched by
     label: a repair moves the patch a hash is taken over, so a hash cannot carry an
     obligation across the repair it is asking for. A park is withheld — it is a question
     already put to a human, and the warden is shown it instead — and so is a row a reviewer
     already answered, and so is the composition pass, as with `with-standing-needs`."
    {:signature [:=> [:catn [:targets :any] [:inherited :any]] :any]})
  (Operation unanswered-of
    "The inherited rows this run has said nothing about. Pure. A row is answered by a ruling on
     it, by a `same_as` naming it, by the same defect raised again, or by a reviewer's explicit
     evidenced `repaired` — from then the run's own accounting decides what is owed, and
     carrying the inherited copy beside it would count one defect twice. Silence stays
     unanswered: not reporting a defect is not evidence it is gone."
    {:signature [:=> [:catn [:inherited :any] [:rounds :any]] :any]})
  (Operation unanswered-inherited
    "The same question asked of a terminal ctx — what the last run left owed that this whole
     run neither raised nor answered. The inherited half of `review-verdict/still-owed`, which
     every reader of the remainder reads; the design judge is shown it on its own, because no
     round's findings mention these."
    {:signature [:=> [:catn [:final :map]] :any]
     :delegates [unanswered-of]})
  (Operation deny-inherited-convergence
    "The same [target status] pairs with :converged downgraded to :partial on every target an
     unanswered inherited finding names. Pure. :converged is not granted by an agent and
     cannot be revoked by one, so a layer that takes it while a known defect stands in it is
     exempt from the code lane until somebody happens to edit the file."
    {:signature [:=> [:catn [:statuses :any] [:unanswered :any]] :any]})
  (Operation pair-quiet-readings
    "The same pairs with every target whose reading was its patch's FIRST quiet one turned to
     :read-once. Pure. A reviewer is not deterministic at a byte-identical patch — across five
     analysed runs a second read at an unchanged hash found a P1 or P2 the first had missed — so
     convergence is earned by two readings that each left nothing owed. Paired on the READING,
     not the grant: a park holding a target :partial used to cancel the rule, and a run ended
     clean on one reading. Paired per TARGET: pairing whole ROUNDS by branch content discarded a
     layer's second reading whenever any other layer moved in between."
    {:signature [:=> [:catn [:statuses :any] [:known :any] [:quiet :any]] :any]})
  (Operation with-quiet-reads
    "The ctx with what this round read of each patch folded into the carry — the patches a
     reading left owing nothing added, the patches a reading found something owed of dropped.
     The carry covers what the cache cannot: a cache write is best-effort, and a review outside
     a workstream has no cache at all. The drop is what stops a reading from before a defect
     was found pairing with one from after, where a refused repair returns the layer to content
     an earlier round read quiet. Quiet is what the READING found, so a park holding the
     target open does not take its reading back."
    {:signature [:=> [:catn [:ctx :map] [:statuses :any] [:quiet :any]] :map]})
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
     of any convergence the round has since falsified. A patch with no earlier quiet reading
     behind it is left at :read-once rather than :converged, whichever stage records the round.
     Returns the pairs it resolved, which is how the stage that called it learns which targets
     are still owed a second reading before it decides whether the round may end the run."
    {:signature [:=> [:catn [:cwd Path] [:ctx :map]] :any]
     :delegates [reviewed-statuses reopened-patches answered-for
                 deny-inherited-convergence pair-quiet-readings unanswered-of]})
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
     or naming a defect a finding of this round already reports, is refused.

     The layer goes through `placed-on`, the rule the last run's open rows are placed by: the
     warden's where the stack has it, the promotion's file otherwise. One neither places is
     not a finding — it is returned as a standing entry, which is where the warden is told to
     put a sibling it cannot place."
    {:signature [:=> [:catn [:cwd Path] [:toc :any] [:handles :any] [:findings :any]
                      [:promotions :any]] :map]
     :delegates [resolve-handle finding-id placed-on]})
  (Operation seen-findings "Every finding an earlier round saw."
    {:signature [:=> [:catn [:history :any]] :any]})
  (Operation working-copy-dirty? "Whether the working copy has uncommitted changes."
    {:signature [:=> [:catn [:cwd Path]] :boolean]})
  (Operation working-copy-state
    "What the working copy's tree holds, path by path, with an identity for the whole — or that
     it could not be read, which is never the same answer as an empty tree."
    {:signature [:=> [:catn [:cwd Path]] :map]})
  (Operation amender-tools
    "The launch options that confine a record amender: it may write its answer file and under its
     permitted dirs, and run read-only jj and its check command, and nothing else — so a path it
     moved is one a file-writing tool of its own names."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation amender-trespass
    "What moved in the tree while a record amender ran, and which of those paths its own
     file-writing tools wrote. Only those count against it; a path moved by anyone else in a live
     worktree is reported, and a move inside a permitted dir never counts."
    {:signature [:=> [:catn [:opts :map]] :map]})
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
  (Operation fixer-answers
    "What a fixer that changed nothing said about each finding it was handed: absent at its
     head (with the line that shows it), belongs in a file its layer may not edit, or disputed.
     The fix stage settles the first, hands the second to the layer that may make it, and stops
     for a person only on the third — read as one refusal, a fixer showing its finding already
     gone ended the run `fix-declined` over a tip that was right. Silence is a dispute: nothing
     is settled or moved on nobody's word."
    {:signature [:=> [:catn [:text :any] [:ids :any]] :map]})
  (Operation plan-with-rerouted
    "The fix plan with a finding added to the entry of a layer above the one whose fixer named
     that layer's file, inserted at its place in the stack when the plan held no entry for it —
     so that layer's fixer runs this stage instead of the finding being re-raised at the lower
     head and routed back to the fixer that could not repair it."
    {:signature [:=> [:catn [:plan :any] [:stack :any] [:i :int] [:to :any] [:findings :any]] :any]
     :delegates [layer-label]})
  (Operation layer-fixer-session
    "A stable session id per layer, so a fixer resumed twice continues rather than restarts."
    {:signature [:=> [:catn [:impl-session-id :any] [:label :any]] :string]})
  (Operation with-composition-memory
    "The whole-stack target, told what it has already reported. Without it the composition pass
     re-derives the same seam every round from an empty memory."
    {:signature [:=> [:catn [:targets :any] [:history :any]] :any]})
  (Operation fix-outcomes
    "What the fix stage has recorded of its fixers this run, one row per outcome — a repair
     that landed, one the stack refused and put back, a fixer's argument for writing nothing,
     a launch that never started — each with its layer, its round, the findings it was handed
     and what the fixer said. Off the history for what landed and the carry for the rest,
     because a round that landed nothing enters the history only when it settled a finding.

     ONE record for every reader of these — the next reviewer of a layer, the warden, the
     design judge — because a reader reasons as if what it is not shown did not happen. Each
     renders the part it can use; none is wired to a subset."
    {:signature [:=> [:catn [:history :any] [:carry :any]] [:sequential :map]]
     :delegates [unstarted-fixers]})
  (Operation with-fix-memory
    "Each target told what a fixer already did to it in this run — its layer's rows of
     `fix-outcomes` where a fixer ran: a repair landed, one put back, an argument for writing
     none. Nothing else in the loop asks whether a repair closed what it was handed: the
     reviewer that would is shown a diff and no history, so a swept defect comes back at the
     lines the fix was made on, and a REFUSED repair or a DECLINED one leaves the range
     byte-identical with nothing at all to notice.

     And each told of a repair that landed on a HIGHER layer for a finding it reported, which
     is why it reads the rounds: the warden files a finding under the layer that owns it, the
     reporting layer is still read at its own head beneath the repair, and keyed on the landing
     layer alone its reviewer re-raises the repaired defect under a new id every round."
    {:signature [:=> [:catn [:targets :any] [:outcomes :any] [:rounds :any]] :any]})
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
  (Operation unstarted-fixers
    "The layers whose latest fixer launch never started, off the run's launch record — the one
     account of whether a fixer ran on a layer, written by the fix stage at launch and never
     pruned, since a session once opened stays opened whatever becomes of its findings. What it
     names are findings ruled `fix` that no process ever read: untried, not resisted. Read
     through `fix-outcomes` by the warden and the design judge, either of which would otherwise
     read their recurrence as fixes that failed, and directly by the run's ledger entry, where
     a `fix-launch-failed` status names the machinery and only this names the layer."
    {:signature [:=> [:catn [:launches :any]] [:sequential :map]]})
  (Operation park-refused-recuts
    "A park for every recut the reshape stage could not act on, carrying its own refusal. The
     warden withholds a recut from the fixers on purpose, so a refusal leaves it with no path."
    {:signature [:=> [:catn [:parks :any] [:outcomes :any] [:iter :int]] :any]})
  (Operation round-changed?
    "Whether the round before this one moved the code: it landed repairs, AND the content this
     round's reviewers read differs from what the last round's did. What the engine's stall
     check cannot ask for itself — a repeated finding set is a stall only if nothing moved,
     and a defect CLASS being narrowed repeats its handles by construction."
    {:signature [:=> [:catn [:ctx :map] [:prior :any]] :boolean]})
  (Operation owed-reading
    "The diff loop's :owes-reading for the engine: the targets a round went on to read a second
     time, read quiet once with nothing to repair. The round after is not counted against the
     cap, and a cap that still falls there ends :owed-second-reading naming them, not :max-iters."
    {:signature [:=> [:catn [:ctx :map]] [:maybe :map]]})
  (Operation escalation-repairs
    "The findings an escalating round still hands to fixers: ruled `fix` under a handle no park
     holds. The parks are the question; a fix beside them is a defect judged repairable alone."
    {:signature [:=> [:catn [:ctx :map]] [:vector :any]]})
  (Operation repair-before-escalate?
    "The diff loop's :repair-before-escalate? for the engine — whether an escalating round has
     escalation repairs to run before it ends."
    {:signature [:=> [:catn [:ctx :map]] :boolean]}))
