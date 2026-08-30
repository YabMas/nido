(ns canvas.session.fleet
  "Self-spec: `nido.session.fleet` — what the running sessions cost, and which nobody is driving."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.state :as sstate]
            [fukan.common.typing.malli]))

(Module session-fleet
  "The memory arithmetic behind deciding whether to boot another session, and which idle one to
   reclaim.

   Every probe can FAIL to answer, and that is modelled rather than papered over: `signals-ok?`
   says whether the snapshot is trustworthy, and a candidate is only a candidate when the signals
   that would have contradicted it actually answered. Reclaiming a session because a probe was
   silent is how you kill the one somebody was using."
  (Operation machine-bytes "Physical RAM, or nil when it cannot be read."
    {:signature [:=> [:catn] [:maybe :int]]})
  (Operation in-use-bytes "What the machine has committed right now."
    {:signature [:=> [:catn] [:maybe :int]]})
  (Operation session-homes "Worktree to session-home, from the registry."
    {:signature [:=> [:catn] :map]})
  (Operation transcripts-available? "Whether the agent-activity signal can be read at all."
    {:signature [:=> [:catn] :boolean]})
  (Operation agent-last-seen "When an agent last touched a session."
    {:signature [:=> [:catn [:worktree Path] [:home Path]] [:maybe :int]]})
  (Operation candidate?
    "Whether nobody appears to be driving a session. Only answers yes when the signals that
     would have said otherwise actually answered."
    {:signature [:=> [:catn [:row :map]] :boolean]})
  (Operation signals-ok? "Whether every probe answered for this snapshot."
    {:signature [:=> [:catn [:rows [:vector :map]]] :boolean]})
  (Operation snapshot "Every live session, what it costs, and what has touched it."
    {:signature [:=> [:catn] [:vector :map]]
     :delegates [session-homes agent-last-seen transcripts-available?]})
  (Operation totals "What the fleet holds, and what one more would cost."
    {:signature [:=> [:catn [:rows [:vector :map]] [:project [:maybe ProjectName]]] :map]
     :delegates [machine-bytes in-use-bytes]})
  (Operation over-budget? "Whether booting one more is projected to cross the budget."
    {:signature [:=> [:catn [:totals :map]] :boolean]})
  (Operation candidates "Sessions nobody appears to be driving, dearest first."
    {:signature [:=> [:catn [:rows [:vector :map]]] [:vector :map]]}))
