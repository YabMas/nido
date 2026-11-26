(ns canvas.claims.qa-stage
  "The claims the qa-stage design (workstream ws-20261126-89b0fe, design at ledger entry 39) commits
   nido to, declared where they live on: a workstream's QA plan and QA walks as checked records, one
   QA reading over them, that reading gating the release candidate in a project that declares :qa,
   a QA stage on the spine, and the landing brief rendered from the plan. Each Claim's name is the
   claim's id, its docstring the statement, and its :about the elements it is about."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [arc lane-pipeline of]]
            [canvas.coordinator.record.standing :refer [record-standing]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.core.buffer :refer [core-buffer]]
            [canvas.platform.project :refer [platform-project]]
            [canvas.tasks.nido-workstream :refer [entry-add* screenshot-add* show*]]
            [canvas.ui.surface :refer [arc-mark ui-components-state]]))

(Claim qa-records-are-checked
  "A QA plan and a QA walk are registered record kinds, parsed at the append: a :product plan carries at least one step, a :none plan carries its reason, step ids are unique in a plan, and a walk is refused unless the plan it cites is a :product plan and the walk gives exactly one result for every step id of it, each result naming at least one screenshot digest; a result is :pass or a failing one — :fail, or :blocked for a step the walk could not carry out, which everything reading the walk counts as not passed. A screenshot is kept with `bb nido:workstream:screenshot:add`, which keeps a file's bytes through core-images' put! under the nido home's QA screenshot root, `qa/screenshots/`, and prints their digest. Whether those bytes are kept is checked by the task layer, which may reach core-images, not by the ledger: `bb nido:workstream:entry:add` refuses, before it appends, a walk naming a digest core-images' fetch finds nothing kept for under that root."
  {:about [coordinator-report record-workstream entry-add* screenshot-add*] :evidence "round"})

(Claim qa-is-readable
  "QA plans and walks are appended through the existing `bb nido:workstream:entry:add` verb, and `bb nido:workstream:show` prints, for any workstream and whichever entry is newest, its QA reading, whether its change is product-facing — :product or :none from the plan that reading stands on, or `unclassified` where no plan cites the governing design — and that plan's sections and steps."
  {:about [show* record-standing] :evidence "round"})

(Claim qa-has-one-reading
  "A workstream's QA is answered by one reading in record-standing from the ledger — the plans citing the governing design, their walks, and the newest build — and from whether the project declares :qa, which its caller passes in: lane-pipeline reads platform-project's `qa` beside the landing declaration it already reads. Which kinds are a build is a per-kind fact of coordinator-report's registry; record-standing reads the newest build by it, and implementation-review-passed? asks record-standing for that newest build instead of naming the kinds itself. The reading is a value carrying its state, the seq of the plan it stands on and that plan's surface, :product or :none; in every state the plan it stands on is the newest plan citing the governing design, and there is none when no plan cites it. Its state is exactly one of :off, :not-applicable, :owed, :failed or :passed: :off without the declaration; :not-applicable while the newest such plan is :none, whatever earlier plans or walks say; :failed while any walk recorded after the newest build of the newest plan has a step not :pass; :passed when at least one such walk exists and none failed; :owed otherwise. The fold, the arc, show and the brief all ask that reading, and take the plan from it."
  {:about [record-standing lane-pipeline coordinator-report] :evidence "round"})

(Claim qa-declared-per-project
  "A project takes on the QA stage only by a :qa declaration on its registry entry, read through one platform-project accessor that refuses an unrecognised value by name; every workstream of a project without one reads :off."
  {:about [platform-project] :evidence "round"})

(Claim qa-gates-the-candidate
  "A QA reading of :owed or :failed keeps a workstream off :reviewed and off every standard-arc position whose next action is :land — :land-granted, :release-candidate and :published — whether or not its implementation review passed or a merge grant is unspent. With the implementation review passed, :owed places :qa-owed, next :walk-qa, where the newest plan citing the governing design is a :product plan, and :qa-unplanned, next :plan-qa, where it is not — both in :authoring mode, and both next actions in spine stage :qa. Where a :review stands in the trail but the implementation review has not passed — as after a build recorded since it — :owed places :implemented, next :review-implementation. :failed places :qa-failed, next :address-qa in :working-copy mode, in spine stage :implementation. Under :off, :not-applicable or :passed the fold places what it places today."
  {:about [lane-pipeline of] :evidence "round"})

(Claim failure-returns-through-build
  "A failed walk is held by the QA reading, as [qa-has-one-reading] states it, and a later walk of the same plan with no build between does not outweigh it. While that reading is :failed or :owed the workstream is placed as [qa-gates-the-candidate] says, at no position whose next action is :land."
  {:about [lane-pipeline] :evidence "round"})

(Claim qa-stage-drawn-or-greyed
  "The spine is intent, baseline, design, implementation, qa. Which stage a record is grouped in is its kind's, apart from the spine stage of the next action that writes it: :qa-walk entries belong to :qa and :qa-plan entries to :design, whichever action wrote the plan. A workstream whose QA reads :off or :not-applicable draws :qa in a sixth state, :off, whose greyed mark and words arc-mark and ui-state-marks give — never :skipped or :ahead, and never a colour arc-row chooses itself; every other workstream draws :qa by the arc's existing rule from its position's stage, as every spine stage is drawn."
  {:about [arc ui-components-state arc-mark] :evidence "round"})

(Claim brief-is-the-plan-rendered
  "The landing QA brief is a rendering of the plan the QA reading stands on — its sections in qa-brief.md's shape for :passed, the one-line 'nothing a user sees changes' brief from its reason for :not-applicable — and /land writes that rendering or no brief; it composes none of its own."
  {:about [coordinator-report] :evidence "round"})

(Claim conductor-told-qa
  "The conductor learns what is owed from the pipeline's next-action stage, worded per stage, including :walk-qa and :address-qa; both its standing rule and its :review-implementation direction say a workstream is a release candidate once the implementation review converges and its QA passes or does not apply, and neither calls a converged review a release candidate by itself."
  {:about [core-buffer] :evidence "round"})
