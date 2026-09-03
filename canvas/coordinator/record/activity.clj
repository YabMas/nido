(ns canvas.coordinator.record.activity
  "Self-spec: `nido.coordinator.record.activity` — what is running against a workstream now."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Claim
  "What a live activity says about itself: which activity, which run, and where its live report
   is. What it deliberately does NOT say is whether it is alive — that is the lock's answer, and
   a field claiming it would be the one thing a crash makes wrong.

   SHAPELESS. The closed malli schema in the code is validated on every take, and a second copy
   here would be the copy nothing checks.")

(Module record-activity
  "The one thing nido could not previously say: that a named process is working on this
   workstream right now.

   What it hides is that the answer is an OPERATING SYSTEM FILE LOCK rather than a record. A
   caller takes a claim and is either running or handed the holder's; it never learns that
   liveness came from the kernel, that a dead holder needs no cleanup, or that the lock and the
   payload are two files so that publishing takes no second lock. Nor that a holder must never
   open a second descriptor to its own lock file, which is the one rule here the operating
   system really does impose. The mechanism is subtle enough — and has been got wrong once
   already — that exactly one place should know it.

   It is also the first exclusion in nido whose LOSER IS GIVEN SOMETHING. `platform-lock` and
   `platform-io/with-file-lock` both block their loser and tell it nothing; a loser here is
   handed the holder's own claim, because the useful thing to do with a review already running
   is to watch it."
  {:child [Claim]}
  (Operation validate
    "The claim, or a throw naming why it is not one. Runs before the lock is taken, so a claim
     nothing could render never excludes anybody."
    {:signature [:=> [:catn [:c :map]] :map]})
  (Operation read-live
    "The claim held against this workstream right now, or nothing. Probes, reads, then probes
     again — the second probe is what stops a dead holder's leftover payload being reported as
     live, and it fails toward absent."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]})
  (Operation held?
    "Whether anything holds the claim. Distinct from read-live returning nothing: a holder that
     has taken the lock and not yet published is held with nothing to report."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :boolean]})
  (Operation with-claim
    "Run a body holding the claim, or refuse and hand back the holder's. Refusing rather than
     waiting is the whole point; releasing happens on every exit path by closing a channel,
     never by deleting a file."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:base :map]
                            [:f [:=> [:catn] :any]]] :any]
     :delegates [validate read-live held?]})
  (Operation refused?
    "Whether a take refused, whatever it learnt about the holder. The two come apart — a take can
     lose the lock and still read no claim, when the holder exits in between — and a caller that
     asked `refused` instead would read that as the body having run."
    {:signature [:=> [:catn [:result :any]] :boolean]})
  (Operation refused
    "The live claim a take refused to compete with, or nothing when it ran. Keeps the sentinel
     this module's business rather than its callers'."
    {:signature [:=> [:catn [:result :any]] [:maybe :map]]}))
