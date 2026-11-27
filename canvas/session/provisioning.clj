(ns canvas.session.provisioning
  "Self-spec: `nido.session.engine` — starting and stopping a session's services from its
   declared session.edn."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.state :as sstate :refer [InstanceId]]
            [fukan.common.typing.malli]))

(Module session-checkout
  "A runtime's own checkout: a jj workspace of the project, outside every member's worktree, whose
   working copy is a copy of the merge of every member's working copy. It is the boundary between
   members' files and the runtime's JVM — a file reaches what the runtime reads, and so what its
   app's Flyway migrates, only through a refresh of it.

   A refresh REFUSES BEFORE IT WRITES: a merge that conflicts, members that cannot be snapshotted,
   or a merged tree the caller's admission rejects all leave the checkout's files exactly as they
   were. The checkout holds a COPY of the merge parented on the root, never the merge itself,
   since jj rebases a merge whenever a member snapshots and a workspace on a rebased commit goes
   stale."
  (Operation jj-workspace? "Whether a directory is a jj workspace of a colocated source."
    {:signature [:=> [:catn [:dir Path]] :boolean]})
  (Operation checkout-dir "Where a runtime's checkout lives, inside its state directory."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [sstate/instance-state-dir]})
  (Operation git-dir "The git directory of the colocated source a checkout is a workspace of."
    {:signature [:=> [:catn [:dir Path]] Path]})
  (Operation refresh!
    "Bring a runtime's checkout up to date with its members' working copies, creating it on first
     use; refused, changing nothing, on a conflict, an unsnapshottable member, or an admission
     that rejects the merged tree."
    {:signature [:function
                 [:=> [:catn [:instance-id InstanceId] [:members [:map-of :string [:maybe :string]]]] Path]
                 [:=> [:catn [:instance-id InstanceId] [:members [:map-of :string [:maybe :string]]] [:setup! :any]] Path]
                 [:=> [:catn [:instance-id InstanceId] [:members [:map-of :string [:maybe :string]]] [:setup! :any] [:admit! :any]] Path]
                 [:=> [:catn [:instance-id InstanceId] [:members [:map-of :string [:maybe :string]]] [:setup! :any] [:admit! :any] [:candidate [:maybe :string]]] Path]]
     :delegates [checkout-dir git-dir sstate/read-session]})
  (Operation examine!
    "Refuse, as a refresh would, a refresh of a runtime's checkout with members under an
     admission, writing nothing — so a caller about to stop services can be refused first; returns
     the commit it admitted, the candidate a later refresh writes from."
    {:signature [:=> [:catn [:instance-id InstanceId] [:members [:map-of :string [:maybe :string]]] [:admit! :any]] :string]})
  (Operation remove! "Forget a runtime's checkout workspace and delete its directory."
    {:signature [:=> [:catn [:instance-id InstanceId] [:from [:maybe Path]]] :nil]
     :delegates [checkout-dir git-dir]}))

(Module session-engine
  "Bring a project's declared services up and down for one worktree.

   Services are DECLARED in the project's session.edn and rendered against a context, not
   scripted — which is what lets a service reference `{{pg.port}}` before Postgres has chosen
   one, and what keeps nido free of per-project shell.

   The `session-edn` this hands to the launcher is the project's SHARED configuration and
   nothing else. Per-session facts — the owning Run's directory — travel as their own argument.
   They used to be merged into it in memory, which made a shared record impersonate a
   per-session record that does not exist."
  (Operation session-edn-path "Where a project's session configuration lives."
    {:signature [:=> [:catn [:project-name ProjectName]] Path]})
  (Operation read-session-edn "A project's session configuration, or nil when it has none."
    {:signature [:=> [:catn [:project-name ProjectName]] [:maybe :map]] :delegates [session-edn-path]})
  (Operation load-session-edn
    "A project's session configuration, or a refusal naming where to create one. The hard read,
     for anything that boots or mutates a session."
    {:signature [:=> [:catn [:project-name ProjectName]] :map] :delegates [session-edn-path]})
  (Operation resolve-project-name "A directory's registered project name, falling back to its leaf."
    {:signature [:=> [:catn [:project-dir Path]] ProjectName]})
  (Operation resolve-instance-id
    "The instance id of the runtime a worktree's session is on: the one recorded when it came up,
     else the project name for the project's own checkout — nil for a worktree with no session up,
     whose runtime is never guessed from its path."
    {:signature [:=> [:catn [:project-dir Path]] [:maybe InstanceId]]})
  (Operation instance-id-for-key
    "The instance id of a project's runtime keyed by the caller's key. The key is taken whole;
     nothing here derives one from a path."
    {:signature [:=> [:catn [:project-name ProjectName] [:runtime-key :string]] InstanceId]
     :delegates [sstate/keyed-id]})
  (Operation runtime-key-of "The runtime key a worktree's session came up under, or nil when none is up there."
    {:signature [:=> [:catn [:project-dir Path]] [:maybe :string]]})
  (Operation runtime-instance-id
    "The instance id a worktree's session comes up on for a runtime key: the project name for the
     project's own checkout, else the key's. Refuses a worktree given no key."
    {:signature [:=> [:catn [:project-dir Path] [:project-name ProjectName] [:runtime-key [:maybe :string]]] InstanceId]
     :delegates [instance-id-for-key]})
  (Operation sessions-backed-by
    "The other sessions on a runtime, other than one excepted, that runs out of a worktree — the
     one its starter brought it up in, whose JVM, classpath and config live there whether or not
     the starter is still on it."
    {:signature [:=> [:catn [:project-dir Path] [:except [:maybe InstanceId]]] [:sequential :string]]
     :delegates [sstate/read-session sstate/state-dir]})
  (Operation refuse-backing-worktree!
    "Refuse removing a worktree, or starting another runtime in it, while it backs a runtime other
     sessions are on — the one guard both go through."
    {:signature [:=> [:catn [:verb :keyword] [:name [:maybe :string]] [:project-dir Path]
                            [:except [:maybe InstanceId]]] :nil]
     :delegates [sessions-backed-by]})
  (Operation refuse-start-in-backing-worktree!
    "Refuse a start that would bring a runtime up in a worktree backing another runtime others
     are on — asked by every verb before it stops the session it would start."
    {:signature [:=> [:catn [:name [:maybe :string]] [:project-dir Path] [:instance-id InstanceId]] :nil]
     :delegates [refuse-backing-worktree! sstate/read-session]})
  (Operation sessions-on "The sessions on one runtime; empty when it is not up."
    {:signature [:=> [:catn [:instance-id InstanceId]] [:sequential :string]]
     :delegates [sstate/read-session]})
  (Operation filter-services "A session's services narrowed to an allowlist."
    {:signature [:=> [:catn [:services :any] [:allowlist :any]] :any]})
  (Operation write-profile-for-session!
    "Persist the profile a session was brought up with, under the session's own key: sessions
     sharing a runtime each created their working copy their own way."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string] [:profile :any]] :any]
     :delegates [sstate/session-profile-path]})
  (Operation read-profile-for-session
    "The profile a session was brought up with. Persisted rather than re-resolved, so a session
     keeps the shape it was started with even if the project's profiles change under it."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]] [:maybe :any]]
     :delegates [sstate/read-session-profile]})
  (Operation run-setup-step!
    "Run one declared setup step. POLYMORPHIC on the step's `:type` — a project declares the
     steps its worktree needs and each type is a method, so adding a kind of setup adds a method
     rather than a branch in a function every project already depends on."
    {:signature [:=> [:catn [:step :map] [:project-dir Path]] :any]})
  (Operation reconcile-app! "Restart a running session's eval service if its process is gone."
    {:signature [:=> [:catn [:existing :map]] :any]})
  (Operation start-session! "Bring a worktree's declared services up."
    {:signature [:=> [:catn [:project-dir Path] [:opts :map]] :any]
     :delegates [load-session-edn resolve-project-name resolve-instance-id
                 write-profile-for-session! sstate/write-session! sstate/upsert-registry!
                 refresh! examine!]})
  (Operation restart-session!
    "Stop a worktree's session and bring it up again, admitting the start before the caller's
     stop — and whatever it does between the two — runs, and writing the tree it admitted."
    {:signature [:=> [:catn [:project-dir Path] [:opts :map] [:stop! :any] [:refused! [:maybe :any]]] :any]
     :delegates [resolve-project-name load-session-edn sstate/read-session examine! start-session!]})
  (Operation stop-session! "Bring a worktree's services down."
    {:signature [:=> [:catn [:project-dir Path]] :any]
     :delegates [resolve-instance-id sstate/read-session sstate/remove-from-registry!]})
  (Operation owes-checkout?
    "Whether a live runtime record runs out of a member's worktree though every member is a jj
     workspace — started before such runtimes ran out of a checkout of their own."
    {:signature [:=> [:catn [:runtime [:maybe :map]]] :boolean]})
  (Operation sync-runtime!
    "Bring the checkout a worktree's runtime runs out of up to date with every member's working
     copy; nil when it runs out of a member's worktree."
    {:signature [:function
                 [:=> [:catn [:project-dir Path]] [:maybe Path]]
                 [:=> [:catn [:project-dir Path] [:instance-id [:maybe InstanceId]]] [:maybe Path]]]
     :delegates [resolve-instance-id sstate/read-session refresh!]})
  (Operation session-status "What a worktree's session is doing."
    {:signature [:=> [:catn [:project-dir Path]] :any]
     :delegates [resolve-instance-id sstate/read-session]})
  (Operation list-sessions "Every session the registry knows about."
    {:signature [:=> [:catn] :any] :delegates [sstate/read-registry]}))
