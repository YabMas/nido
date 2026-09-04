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
  (Operation review! "Review one range and answer with its findings."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [run-codex! schema-json safe-label]}))

(Module review-verdict
  "The pass that decides whether the round is done.

   Separate from the reviewers, because a reviewer that also decided when to stop would stop
   when it ran out of things to say rather than when the work was right."
  (Operation build-prompt "The verdict prompt for this round."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation parse "The verdict out of what the agent said."
    {:signature [:=> [:catn [:text :string] [:round :any] [:design-seq :any]] :map]})
  (Operation decision? "Whether a verdict is one."
    {:signature [:=> [:catn [:v :any]] :boolean]})
  (Operation still-open "The findings that actually remain."
    {:signature [:=> [:catn [:findings :any]] :any]})
  (Operation open-across-run
    "Everything the run is still holding when it ends, folded over every round. A count answers
     'did this finish clean' and cannot answer 'what is it waiting for'."
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
  (Operation run! "Run the verdict pass."
    {:signature [:=> [:catn [:opts :map]] :map] :delegates [build-prompt parse still-open]}))

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
  (Operation parse-warden-decision "The warden's ruling out of what it said."
    {:signature [:=> [:catn [:text :string]] :map]})
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
  (Operation announce-targets! "Publish what this round is reviewing and what it skipped."
    {:signature [:=> [:catn [:ctx :map] [:split :map]] :any]})
  (Operation discover-design-record "This workstream's latest design record."
    {:signature [:=> [:catn [:cwd Path]] [:maybe :map]]})
  (Operation discover-baseline
    "The baseline a design CITED, not the newest one. A design committed to a particular
     reading, and judging it against a later baseline checks it against a premise it never made."
    {:signature [:=> [:catn [:cwd Path] [:design :map]] [:maybe :map]]})
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
  (Operation reviewed-statuses
    "Every reviewed target paired with the status its patch is left at — converged when it owes
     nothing, partial when it still does. Only the first is a skip; the second is the entry a
     next round comes back to, and so the only one whose answers anything reads."
    {:signature [:=> [:catn [:reviews :any] [:findings :any] [:parks :any]] :any]
     :delegates [converged-targets]})
  (Operation answered-for
    "What one target reported and the run settled, folded over every round. The converging
     round is the one least likely to hold anything: a run ends by finding nothing."
    {:signature [:=> [:catn [:label :any] [:rounds :any]] :any]})
  (Operation record-review!
    "Write what this round left each reviewed target at into the cache: its status, and what
     the run has settled about it."
    {:signature [:=> [:catn [:cwd Path] [:ctx :map]] :any]
     :delegates [reviewed-statuses answered-for]})
  (Operation resolve-handle "The identity a finding is filed under."
    {:signature [:=> [:catn [:handles :any] [:f Finding]] :any]})
  (Operation apply-rulings "The warden's per-finding rulings, merged in."
    {:signature [:=> [:catn [:findings :any] [:rulings :any] [:handles :any]] :any]
     :delegates [resolve-handle]})
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
  (Operation layer-fixer-session
    "A stable session id per layer, so a fixer resumed twice continues rather than restarts."
    {:signature [:=> [:catn [:impl-session-id :any] [:label :any]] :string]})
  (Operation with-composition-memory
    "The whole-stack target, told what it has already reported. Without it the composition pass
     re-derives the same seam every round from an empty memory."
    {:signature [:=> [:catn [:targets :any] [:history :any]] :any]})
  (Operation carried-parks
    "The open parks this run is holding, each with the round it was first parked in and the
     layer it names. A park is never raised again, so it vanishes from the findings the moment
     the reviewer stops mentioning it — carrying it is what stops the warden re-adjudicating
     one from scratch and what keeps its layer out of the convergence cache."
    {:signature [:=> [:catn [:prior :any] [:ruled :any] [:iter :int]] :any]})
  (Operation park-refused-recuts
    "A park for every recut the reshape stage could not act on, carrying its own refusal. The
     warden withholds a recut from the fixers on purpose, so a refusal leaves it with no path."
    {:signature [:=> [:catn [:parks :any] [:outcomes :any] [:iter :int]] :any]}))
