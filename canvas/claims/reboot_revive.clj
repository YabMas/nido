(ns canvas.claims.reboot-revive
  "The claims the reboot-revive design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.attention :refer [boot-attention]]
            [canvas.coordinator.record.session-events :refer [record-session-events]]
            [canvas.coordinator.work :refer [coordinator-work]]
            [canvas.session.casualties :refer [session-casualties]]
            [canvas.session.fleet :refer [session-fleet]]
            [canvas.session.lifecycle :refer [session-lifecycle]]
            [canvas.session.misc :refer [session-reclaim]]
            [canvas.session.revive :refer [session-revive]]
            [canvas.session.state :refer [session-state]]
            [canvas.tasks.nido-session :refer [nido-session]]))

(Claim casualty-outlives-housekeeping
  "A casualty record lives outside the per-instance state root, so no reclaim deletes it."
  {:about [session-casualties session-reclaim] :evidence "round"})

(Claim reclaim-spares-a-down-session
  "Writing an instance's session record also writes its worktree record, which deleting the session record leaves in place. Neither the daemon's reclaim sweep nor the CLI reclaim deletes an instance state dir whose worktree record names a directory that exists, whatever cleared its registry entry; a dir whose worktree is gone, or that records none, is reclaimed under their existing guards."
  {:about [session-reclaim session-state] :evidence "round"})

(Claim revive-plan-ranked-and-budgeted
  "The revive plan is a pure function of what it is handed. Unsettled casualties are ranked by last agent activity, newest first, those never seen after them by registration time; each is admitted to :revive while the machine's committed memory plus the estimated cost of every session admitted so far stays within the fleet budget fraction of physical RAM, and the rest are :resumable. :only restricts the casualties considered; :all admits every one regardless of budget; a casualty whose worktree is gone is :gone; one whose worktree holds unresolved conflicts is :conversation-only and costs nothing. When physical RAM or committed memory is unknown, nothing is admitted without :all, and each row left out says that the budget could not be read."
  {:about [session-revive session-fleet] :evidence "round"})

(Claim dry-run-changes-nothing
  "A dry run of revive prints the plan and writes, starts and opens nothing."
  {:about [nido-session] :evidence "round"})

(Claim conflicted-worktree-never-started
  "Revive never calls a lifecycle start on a casualty whose worktree holds unresolved jj conflicts, so it keeps no start failure for it and fires no recovery; it still reopens that casualty's conversation and says why its services stay down."
  {:about [nido-session session-revive] :evidence "round"})

(Claim conversation-reopened-where-it-ran
  "For every :revive and :conversation-only row, revive takes the conversation whose last recorded event is newest among those whose recorded cwd is the session's home or lies within its worktree, and runs `claude --resume <id> --dangerously-skip-permissions` from that recorded cwd as a typed command in a Warp tab; outside Warp it opens nothing and prints the command with its directory. A row with no such conversation opens no tab and says so."
  {:about [nido-session record-session-events session-lifecycle] :evidence "round"})

(Claim revive-owed-per-workstream
  "A casualty is owed a revive while the workstream its runtime key names is open and holds a session a person works in — neither its conductor's nor a Run's — and is owed at most once per workstream; one whose workstream cannot be read, or that names none, is owed nothing. An owed casualty brings back the newest such session, in its own worktree, whatever session the runtime was last registered under. The revive plan, the daemon's attention item and the inbox card each read only owed casualties, and count workstreams."
  {:about [coordinator-work nido-session session-casualties] :evidence "round"})
