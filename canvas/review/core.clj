(ns canvas.review.core
  "Self-spec: the review loop's supporting modules — what it queues, caches, digests, reports,
   renders and prompts with."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.control :as control]
            [canvas.coordinator.record.state :refer [Path WorkstreamId]]
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
   it comes back rather than lost.

   `worth-analysing?` is the ONLY gate, and a run reaches it from two places — the loop's own
   exit and the reconciler that settles a run whose process died. A second gate at either call
   site is a second place for the exclusion list to be incomplete, which is how an orphan that
   read nothing came to buy a worktree and an hour of budget."
  (Operation payload "The envelope payload for one run's analysis. Pure."
    {:signature [:=> [:catn [:run :map]] :map]})
  (Operation worth-analysing?
    "Whether a terminated run is worth an analysis session at all. Pure.

     Takes the run rather than its status alone, because two of the exclusions are facts about
     what the run DID: a dry run, and an orphan that stopped before a reviewer answered for any
     target."
    {:signature [:=> [:catn [:run :map] [:report? :boolean]] :boolean]})
  (Operation enqueue! "Queue one run's analysis."
    {:signature [:=> [:catn [:run :map]] [:maybe :any]]
     :delegates [worth-analysing? payload control/fire!]}))

(Module review-cache
  "What this workstream has already reviewed, keyed by the PATCH.

   Keyed by patch hash rather than by revision, which is the whole point: a rebase changes every
   revision id and changes no code, so a cache keyed on ids would re-review the entire stack
   after every reshape.

   An entry holds two things and only one of them is the skip: `:status`, of which `:converged`
   alone means do not look again, and `:answered`, what was decided about that patch. The second
   is worth recording at either status — the patch a next round comes back to is precisely the
   one that still owes something."
  (Operation path "Where a workstream's review cache lives."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Path]})
  (Operation read-cache "A workstream's cache, or an empty one."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map] :delegates [path]})
  (Operation converged? "Whether this exact patch has already converged — the only skip."
    {:signature [:=> [:catn [:cache :map] [:patch-hash :string]] :boolean]})
  (Operation answered "The findings already settled against this patch."
    {:signature [:=> [:catn [:cache :map] [:patch-hash :string]] :any]})
  (Operation record
    "The cache with one patch's result folded in. Pure. The entry carries its own status, so a
     writer that does not say leaves a patch nothing will skip."
    {:signature [:=> [:catn [:cache :map] [:patch-hash :string] [:entry :map]] :map]})
  (Operation reopen
    "One patch's convergence revoked — `:partial`, keeping what the entry holds. Pure. This is
     the only thing that falsifies a convergence without the content moving: a run learning
     that something IS owed of a patch it was about to skip. `record` cannot express it, since
     a skipped target has no review to write an entry from. `:answered` survives, because those
     decisions are about this content and stay true of it."
    {:signature [:=> [:catn [:cache :map] [:patch-hash :string] [:at :string]] :map]
     :delegates [converged?]})
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

(Module review-provenance
  "Which copy of the review loop's own code a run is executing.

   The counterpart of the report's target: the target pins the code under review, this pins the
   code that did the reviewing. Without it a finding about the loop cannot be told from an
   artefact of a stale invocation — a run eleven commits behind main reproduced four record
   defects those commits had already fixed, and nothing in it said so."
  (Operation loaded-from "The source root the review namespaces came from, and the commit it stands at."
    {:signature [:=> [:catn] [:maybe :map]]}))

(Module review-report
  "The run's own record, built up event by event and written as it goes."
  {:child [ReviewReport]}
  (Operation init "A fresh report for a run about to start."
    {:signature [:=> [:catn [:opts :map]] ReviewReport]})
  (Operation in-stack-order "Rows in the order the stack has them, bottom first."
    {:signature [:=> [:catn [:rows :any]] :any]})
  (Operation review-layers "One entry per review target."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation stopped-on
    "What the run stopped ON, off its terminal context — the findings it gave up on, the
     questions it parked, and what was open and handed to nobody: the last warden's list, and
     what the last run left owed that the terminal round could place on no layer — as against
     :status, which is what it stopped AS.

     Public because two readers need the same answer: the report folds it in at
     :run-finalized, and the analysis payload carries it because a run dir is reclaimed
     long before an analysis of it is read."
    {:signature [:=> [:catn [:ctx :map]] [:maybe :map]]})
  (Operation applied-reshapes
    "Every recut the run actually carried out, in round order.

     Over the report rather than over the terminal context, and that is the whole reason it
     exists: a context is rebuilt each round, so a fold made in round 2 is forgotten by the
     time a round 5 ends. The report is the only value that remembers the run."
    {:signature [:=> [:catn [:report ReviewReport]] [:sequential :map]]})
  (Operation coverage
    "How much of the stack the run READ — targets a reviewer opened, against targets
     carried from an earlier run's convergence.

     What a status is a status OF. `clean` over three targets out of eight and `clean` over
     all eight are the same word and different evidence, and every reader downstream of the
     run dir — a ledger entry, an analysis briefing — had no way to tell them apart.

     Over the report for the same reason `applied-reshapes` is: a skip is decided per round
     and the question is about the run."
    {:signature [:=> [:catn [:report ReviewReport]] :map]})
  (Operation in-flight
    "The round, and the phase within it, a report was still in when it was last written — nil
     for one whose run closed its own rounds.

     `some?` on it is `did this run end`, asked of the value rather than of :status. The phase
     is the part a later claimant acts on: every phase but `fix` reads the tree, and `fix`
     launches agents that rewrite it."
    {:signature [:=> [:catn [:report ReviewReport]] [:maybe :map]]})
  (Operation errored
    "The phase whose throw ended a run the loop closed, and what it said — nil for a run no
     phase of which threw.

     `in-flight`'s counterpart for a run that finalized, asking it the same first question —
     was it reading the branch or rewriting it. Public because two readers need it: the
     `:review` entry carries the message, and the analysis payload titles the run by the phase."
    {:signature [:=> [:catn [:report ReviewReport]] [:maybe :map]]})
  (Operation orphaned
    "The report forced terminal, for a run that stopped without writing one.

     The counterpart of finalizing where there is no terminal context to read: what the run
     stopped ON is the phase it was in rather than a judgement it reached. Dated when the run
     was last OBSERVED, never now — a dead run's ending is a fact about the run, and the
     reconciler's clock would date every orphan to whenever somebody next reviewed the branch."
    {:signature [:=> [:catn [:report ReviewReport] [:at :string]] ReviewReport]
     :delegates [in-flight]})
  (Operation interrupted
    "The report forced terminal BY THE RUN ITSELF, on being stopped — or nothing when it must be
     left to the reconciler instead.

     The other half of `orphaned`, and a different fact. An orphan is a run nobody can account
     for; an interrupt is a run saying it was told to stop, written from the shutdown hook while
     it still knows. Refused for a run that already ended, whose verdict this would discard, and
     for one stopped mid-repair — that one left the tree rewritten and a report still saying
     `running` is the only thing that tells the next claimant so."
    {:signature [:=> [:catn [:report ReviewReport] [:at :string]] [:maybe ReviewReport]]
     :delegates [in-flight]})
  (Operation apply-event
    "The report with one event folded in. Pure.

     An interrupted report is FINAL and every later event is dropped: the shutdown hook stamps
     it and then reaps the reviewers, which unblocks the engine's thread to spend the reap's
     grace unwinding — and a finalize out of that would restate the interrupt as a verdict."
    {:signature [:=> [:catn [:report ReviewReport] [:event :map]] ReviewReport]
     :delegates [interrupted]})
  (Operation with-verdict
    "The report carrying what became of the design-verdict pass.

     Not an event, because the pass judges the finished run and so cannot report
     into a report that is still being built. It is the one thing the report
     learns after :run-finalized."
    {:signature [:=> [:catn [:report ReviewReport] [:outcome :map]] ReviewReport]})
  (Operation verdict-summary
    "What the verdict DECIDED, in the two facts small enough to travel: the verdict itself, and
     how many findings it laid at the implementation's door rather than the design's.

     A reader of the report has `with-verdict`'s whole value and needs none of this. This is for
     the readers who will not have the report — the analysis payload, and a title on a board —
     for whom a run that published `converged · 0 still open` over a `strained` verdict naming
     two unrepaired implementation defects was indistinguishable from one that was finished."
    {:signature [:=> [:catn [:report ReviewReport]] [:maybe :map]]})
  (Operation persist! "Write the report atomically, so a reader never sees half of one."
    {:signature [:=> [:catn [:report ReviewReport] [:path Path]] :any]}))

(Module review-reconcile
  "Settling the review runs that died on a tree, so a new one can start.

   The counterpart of the daemon's startup reconciliation, run at the moment that makes it
   sound: a caller holding the workstream's claim is the one process that may conclude every
   other `running` report on this tree belongs to something dead, exactly as the daemon may at
   startup. Without it a dead run's report says `running` for the life of the run dir, and
   `review-analysis` fires only on a terminated run — so the loops that die are the ones never
   read.

   WHAT THE CLAIM NEVER COVERED is the agents a run launched. A fixer outlives the loop that
   spawned it, keeps writing to the run dir and keeps rewriting the branch, so the workstream
   reads as free while the tree is still moving. An orphan that stopped in its fix phase
   therefore refuses the next run rather than letting it read a stack mid-edit — and settling
   that orphan is what clears the refusal, so it fires once. One still being written to is left
   non-terminal on purpose, so its refusal repeats until the writing stops."
  (Operation fixing?
    "Whether an orphan stopped in the phase that rewrites the tree. The whole of what a
     claimant decides on: every other phase only reads the branch."
    {:signature [:=> [:catn [:orphan :map]] :boolean]})
  (Operation orphans
    "Every review run that was reading this tree and never wrote a terminal status, oldest
     write first. Sound only under the claim, which is what makes a `running` report a dead
     process; a record round is not one of these, judging a ledger entry rather than the tree."
    {:signature [:=> [:catn [:cwd Path] [:own-run-id [:maybe :string]]] [:sequential :map]]})
  (Operation settle!
    "Force each of them terminal, queue its analysis, and say whether the caller may review the
     tree. Settles nothing, and proceeds, where there is no workstream — the same condition
     `claiming` takes no claim on, and a run that took none has excluded nobody."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [orphans fixing?]}))

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
  (Operation read-report "A run's report off disk, or nothing when it is absent or unreadable."
    {:signature [:=> [:catn [:path Path]] [:maybe :map]]})
  (Operation follow!
    "Paint someone ELSE's run until they finish. The same animator over a different frame source:
     the holder writes its report atomically on every event and the renderer is pure over one, so
     joining a live run is a read rather than a channel. Writes nothing anywhere — a follower
     that touched the report, the ledger or the claim could damage the run it came to watch."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [read-report with-live-frame]})
  (Operation recording-interruption
    "Run a body with the run recorded as interrupted if the JVM is stopped while it is going.

     A loop unwinds through a `finally` when it throws and through nothing at all when it is
     stopped — which is what SIGINT does, and what a person pressing Ctrl-C does. The report is
     the whole of what such a run leaves behind, so it is written from the shutdown hook."
    {:signature [:=> [:catn [:emit :any] [:clock :any] [:f [:=> [:catn] :any]]] :any]})
  (Operation with-live-display "Run a body under the live display, or plainly."
    {:signature [:=> [:catn [:opts :map]] :any]
     :delegates [plain? emit-fn with-live-frame recording-interruption]}))

(Module review-loop
  "The generic round loop: run the pipeline, judge, repeat until it converges or the cap is hit.

   Capped, for the same reason every agent loop is: convergence decided by the thing being
   judged is not convergence."
  (Operation default-finding-key "How the diff review tells two findings apart."
    {:signature [:=> [:catn [:f Finding]] :any]})
  (Operation default-attempt-key
    "How the give-up counter tells one ATTEMPT at a defect from another.

     The finding key alone counts APPEARANCES, so a finding re-routed to a layer that can
     actually fix it spends the counter on the round that first aimed it correctly."
    {:signature [:=> [:catn [:finding-key :any]] :any]})
  (Operation run-loop "Drive one review to convergence or to its cap."
    {:signature [:=> [:catn [:opts :map]] :map]}))

(Module review-prompts
  "The prompt blocks each pass is built from.

   Assembled from blocks rather than written per pass, so what bounds a review — its subject,
   its lane, what is out of scope — says the same thing everywhere it appears."
  (Operation design-yardstick-block
    "The design record rendered for a REVIEWER — what the implementation is validated against.

     The same record the warden is given, minus the fields that exist to CLOSE a finding. A
     warden rules and needs everything that could make a finding answered rather than new; a
     reviewer reports, and every closing field is one that can suppress a report. So the
     invariants and the shape are here, the seams are here under the bound that makes them safe
     (a defect the gap does not cover is still the reviewer's), and the refused remedies and the
     claimed decomposition are not."
    {:signature [:=> [:catn [:design [:maybe :map]]] [:maybe :string]]})
  (Operation layer-brief-block "The bounding brief for one layer."
    {:signature [:=> [:catn [:brief :map]] :string]})
  (Operation manifest-block
    "The files a range-bounded review works from, and the mandate to open every one."
    {:signature [:=> [:catn [:manifest :string]] :string]})
  (Operation composition-block "The primer for the pass that reviews the stack as a whole."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation prior-fixes-block
    "What a fixer already did to this target — a repair landed or put back, or an argument
     for writing none — put to the reviewer as a claim to check."
    {:signature [:=> [:catn [:prior-fixes :any]] [:maybe :string]]})
  (Operation standing-needs-block
    "What an earlier run's design verdict left outstanding, put to the reviewer as a question
     about the range in front of it. The verdict read a different tree, so reporting it back
     unverified would launder an old claim into a fresh finding."
    {:signature [:=> [:catn [:standing :any]] [:maybe :string]]})
  (Operation prior-open-block
    "What an earlier RUN left owed against this exact layer, put to the reviewer of the code
     that holds it — the same question `standing-needs-block` asks, about the other half of
     what a run leaves behind. A settled ruling reaches a later run through the workstream
     cache; an UNSETTLED one had no channel at all, so a defect ruled `fix` and never repaired
     was invisible to the reviewer of its own file."
    {:signature [:=> [:catn [:prior-open :any]] [:maybe :string]]})
  (Operation fix-prompt "The instruction to fix given findings."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation toc-block "The stack's table of contents."
    {:signature [:=> [:catn [:toc :any]] :string]})
  (Operation warden-prompt "The prompt that asks for a per-finding ruling."
    {:signature [:=> [:catn [:opts :map]] :string] :delegates [toc-block]}))
