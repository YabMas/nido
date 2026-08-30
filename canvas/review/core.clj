(ns canvas.review.core
  "Self-spec: the review loop's supporting modules — what it queues, caches, digests, reports,
   renders and prompts with."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.control :as control]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.coordinator.record.vocabulary :refer [WorkstreamId]]
            [canvas.design.check :as design]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Finding
  "One thing a review pass says is wrong, identified by what it is about rather than by when it
   was found — so the same finding raised twice by two passes is one finding, and a finding that
   survives a fix is recognisably the same one.")

(Kind ReviewReport
  "The live state of a review run: its layers, its rounds, and what each found. Written
   atomically as it goes, so a crashed run leaves a readable partial rather than nothing.")

(Module review-analysis
  "Queueing a finished review run for retrospective analysis.

   Best-effort by contract: the coordinator need not be running, because an envelope in the
   queue is picked up on the next drain — so a review run with the daemon down is analysed when
   it comes back rather than lost."
  (Operation payload "The envelope payload for one run's analysis. Pure."
    {:signature [:=> [:catn [:run :map]] :map]})
  (Operation worth-analysing? "Whether a terminal outcome is worth analysing at all. Pure."
    {:signature [:=> [:catn [:status :any] [:dry-run? :boolean] [:report? :boolean]] :boolean]})
  (Operation enqueue! "Queue one run's analysis."
    {:signature [:=> [:catn [:run :map]] [:maybe :any]]
     :delegates [worth-analysing? payload control/fire!]}))

(Module review-cache
  "What this workstream has already reviewed, keyed by the PATCH.

   Keyed by patch hash rather than by revision, which is the whole point: a rebase changes every
   revision id and changes no code, so a cache keyed on ids would re-review the entire stack
   after every reshape."
  (Operation path "Where a workstream's review cache lives."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Path]})
  (Operation read-cache "A workstream's cache, or an empty one."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map] :delegates [path]})
  (Operation converged? "Whether this exact patch has already converged."
    {:signature [:=> [:catn [:cache :map] [:patch-hash :string]] :boolean]})
  (Operation answered "The findings already closed against this patch."
    {:signature [:=> [:catn [:cache :map] [:patch-hash :string]] :any]})
  (Operation record "The cache with one patch's result folded in. Pure."
    {:signature [:=> [:catn [:cache :map] [:patch-hash :string] [:entry :map]] :map]})
  (Operation write! "Persist the cache. Best-effort — a lost cache costs time, not correctness."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:cache :map]] :any]
     :delegates [path]}))

(Module review-digest
  "Stable short identities for content."
  (Operation sha256-hex "The hex digest of a string."
    {:signature [:=> [:catn [:s :string]] :string]})
  (Operation short-id "The first characters of a digest — long enough to be unique in a stack."
    {:signature [:=> [:catn [:s :string]] :string] :delegates [sha256-hex]}))

(Module review-conformance
  "Design violations as review findings.

   The seam that puts fukan's verdict into the same loop as everything else, so a design
   violation is fixed the way a review finding is rather than through a separate ceremony."
  (Operation findings "The design violations in a worktree, as findings."
    {:signature [:=> [:catn [:project ProjectName] [:worktree Path]] [:vector Finding]]
     :delegates [design/check design/design-of]}))

(Module review-report
  "The run's own record, built up event by event and written as it goes."
  {:child [ReviewReport]}
  (Operation init "A fresh report for a run about to start."
    {:signature [:=> [:catn [:opts :map]] ReviewReport]})
  (Operation in-stack-order "Rows in the order the stack has them, bottom first."
    {:signature [:=> [:catn [:rows :any]] :any]})
  (Operation review-layers "One entry per review target."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation apply-event "The report with one event folded in. Pure."
    {:signature [:=> [:catn [:report ReviewReport] [:event :map]] ReviewReport]})
  (Operation persist! "Write the report atomically, so a reader never sees half of one."
    {:signature [:=> [:catn [:report ReviewReport] [:path Path]] :any]}))

(Module review-retreat
  "What a superseding record gave up, and what it grew.

   A round that replaces a record can quietly drop what the previous one claimed; naming the
   retreat is what makes that a decision rather than an erasure."
  (Operation baseline-retreats "What a superseding baseline no longer claims."
    {:signature [:=> [:catn [:prev :map] [:curr :map]] :any]})
  (Operation design-retreats "What a superseding design no longer claims."
    {:signature [:=> [:catn [:prev :map] [:curr :map]] :any]})
  (Operation growth "The prose fields that grew rather than shrank."
    {:signature [:=> [:catn [:as-authored :map] [:curr :map]] :any]})
  (Operation growth-summary "The growth, in a line."
    {:signature [:=> [:catn [:growth :any]] :string]})
  (Operation summary "One line per retreat."
    {:signature [:=> [:catn [:retreats :any]] :string]}))

(Module review-render
  "The live display and its final frame."
  (Operation frame "The live block for a review run."
    {:signature [:=> [:catn [:report ReviewReport] [:now :any]] :string]})
  (Operation final "What is printed once at the end."
    {:signature [:=> [:catn [:report ReviewReport]] :string]})
  (Operation plain-line "One line per event, for output that is not a terminal."
    {:signature [:=> [:catn [:report :any] [:event :map]] [:maybe :string]]})
  (Operation record-frame "The live block for a record round."
    {:signature [:=> [:catn [:report ReviewReport] [:now :any] [:opts :map]] :string]})
  (Operation record-final "The final block for a record round."
    {:signature [:=> [:catn [:report ReviewReport] [:opts :map]] :string]}))

(Module review-frontend
  "Driving a terminal that may not be one.

   `plain?` is the fork the whole module turns on: an animated frame in a pipe is noise, so the
   same run renders as one line per event when nobody is watching it live."
  (Operation plain? "Whether output is not an interactive terminal."
    {:signature [:=> [:catn] :boolean]})
  (Operation emit-fn "The emit function: fold each event into the report and show it."
    {:signature [:=> [:catn [:report-atom :any] [:report-path Path] [:clock :any] [:plain? :boolean]] :any]})
  (Operation tty-size "The terminal's dimensions."
    {:signature [:=> [:catn] :map]})
  (Operation fit "A string bounded to a width and height, eliding the middle."
    {:signature [:=> [:catn [:s :string] [:geom :map]] :string]})
  (Operation with-live-frame "Animate a frame while a body runs."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [tty-size fit]})
  (Operation with-live-display "Run a body under the live display, or plainly."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [plain? emit-fn with-live-frame]}))

(Module review-loop
  "The generic round loop: run the pipeline, judge, repeat until it converges or the cap is hit.

   Capped, for the same reason every agent loop is: convergence decided by the thing being
   judged is not convergence."
  (Operation default-finding-key "How the diff review tells two findings apart."
    {:signature [:=> [:catn [:f Finding]] :any]})
  (Operation run-loop "Drive one review to convergence or to its cap."
    {:signature [:=> [:catn [:opts :map]] :map]}))

(Module review-prompts
  "The prompt blocks each pass is built from.

   Assembled from blocks rather than written per pass, so what bounds a review — its subject,
   its lane, what is out of scope — says the same thing everywhere it appears."
  (Operation layer-brief-block "The bounding brief for one layer."
    {:signature [:=> [:catn [:brief :map]] :string]})
  (Operation composition-block "The primer for the pass that reviews the stack as a whole."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation fix-prompt "The instruction to fix given findings."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation toc-block "The stack's table of contents."
    {:signature [:=> [:catn [:toc :any]] :string]})
  (Operation warden-prompt "The prompt that asks for a per-finding ruling."
    {:signature [:=> [:catn [:opts :map]] :string] :delegates [toc-block]}))
