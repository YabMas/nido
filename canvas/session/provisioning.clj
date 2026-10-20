(ns canvas.session.provisioning
  "Self-spec: `nido.session.engine` — starting and stopping a session's services from its
   declared session.edn."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.state :as sstate :refer [InstanceId]]
            [fukan.common.typing.malli]))

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
                 write-profile-for-session! sstate/write-session! sstate/upsert-registry!]})
  (Operation stop-session! "Bring a worktree's services down."
    {:signature [:=> [:catn [:project-dir Path]] :any]
     :delegates [resolve-instance-id sstate/read-session sstate/remove-from-registry!]})
  (Operation session-status "What a worktree's session is doing."
    {:signature [:=> [:catn [:project-dir Path]] :any]
     :delegates [resolve-instance-id sstate/read-session]})
  (Operation list-sessions "Every session the registry knows about."
    {:signature [:=> [:catn] :any] :delegates [sstate/read-registry]}))
