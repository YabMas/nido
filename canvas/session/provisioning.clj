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
  (Operation resolve-instance-id "A directory's instance identifier."
    {:signature [:=> [:catn [:project-dir Path]] InstanceId]})
  (Operation filter-services "A session's services narrowed to an allowlist."
    {:signature [:=> [:catn [:services :any] [:allowlist :any]] :any]})
  (Operation write-profile-for-session! "Persist the profile a session was brought up with."
    {:signature [:=> [:catn [:wt-path Path] [:profile :any]] :any]
     :delegates [resolve-instance-id sstate/instance-state-dir]})
  (Operation read-profile-for-session
    "The profile a session was brought up with. Persisted rather than re-resolved, so a session
     keeps the shape it was started with even if the project's profiles change under it."
    {:signature [:=> [:catn [:wt-path Path]] [:maybe :any]]
     :delegates [resolve-instance-id sstate/instance-state-dir]})
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
