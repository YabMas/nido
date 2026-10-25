(ns canvas.session.revive
  "Self-spec: `nido.session.revive` — which casualties to bring back, in what order."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.session.casualties :refer [Casualty]]
            [canvas.session.state :refer [InstanceId]]
            [fukan.common.typing.malli]))

(Kind Conversation
  "A recorded agent conversation: its id, the cwd its last event carried, and when that was."
  [:map [:id :string] [:cwd Path] [:last-at-ms :int]])

(Kind CasualtyFacts
  "What is known of one casualty at the moment of reviving. A fact the gatherer could not read is
   stated as unknown rather than guessed: :conflicts :unknown when jj could not answer, a nil
   :conversation when none is recorded. Its newest conversation's last event is its last activity."
  [:map [:casualty Casualty]
        [:worktree [:enum :present :gone]]
        [:conflicts [:enum :none :conflicted :unknown]]
        [:conversation [:maybe Conversation]]])

(Kind MachineFacts
  "The machine as the fleet reads it: physical RAM and committed memory, each nil when its probe
   could not answer, and each project's typical session cost, nil where the live fleet holds too
   few of its sessions to say."
  [:map [:physical [:maybe :int]] [:committed [:maybe :int]]
        [:typical [:map-of :string [:maybe :int]]]])

(Kind ReviveInput
  "Everything the plan decides from."
  [:map [:facts [:vector CasualtyFacts]] [:machine MachineFacts]
        [:only {:optional true} [:set InstanceId]] [:all? :boolean]])

(Kind PlanRow
  "One casualty's place in the plan, tagged by what revive does with it; each tag carries its own
   payload and nothing else."
  [:or
   [:map [:row [:enum :revive]] [:facts CasualtyFacts] [:estimated-bytes :int]]
   [:map [:row [:enum :conversation-only]] [:facts CasualtyFacts]
         [:why [:enum :conflicted :conflicts-unknown]]]
   [:map [:row [:enum :resumable]] [:facts CasualtyFacts] [:why [:enum :over-budget :memory-unknown]]]
   [:map [:row [:enum :gone]] [:facts CasualtyFacts]]])

(Module session-revive
  "The order sessions come back in after a host boot, and how many of them fit.

   PURE. It is handed the casualties and every fact it decides on — the newest conversation and
   when it was last active, whether the worktree is conflicted or gone, the machine's memory — and
   answers a plan; it reads and starts nothing. The facts are gathered at the moment of reviving
   rather than stored, because every one of them is stale by the time anyone revives.

   Newest activity first, because the session somebody touched last is the one they will want
   back first. Admitted against the fleet's own budget, in rank order, each one that still fits beside
   those admitted before it, because restoring a whole fleet at once
   is what overran the host the first time. A fact it cannot read never admits a session: an
   unreadable memory probe leaves every casualty resumable unless :all, and an unanswerable
   conflict check keeps its services down."
  {:child [Conversation CasualtyFacts MachineFacts ReviveInput PlanRow]}
  (Operation estimated-bytes
    "What one casualty is expected to cost once it is up: its project's typical cost in the live
     fleet, or a default this module holds where the fleet cannot say."
    {:signature [:=> [:catn [:casualty Casualty] [:machine MachineFacts]] :int]})
  (Operation plan
    "The rows to revive, to reopen only, to leave resumable, and gone — ranked, then admitted."
    {:signature [:=> [:catn [:input ReviveInput]] [:vector PlanRow]]
     :delegates [estimated-bytes]}))
