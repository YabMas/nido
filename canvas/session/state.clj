(ns canvas.session.state
  "Self-spec: `nido.session.state` — the filesystem layout the session substrate owns."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.io :as io]
            [canvas.platform.process :as proc]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind InstanceId
  "One provisioned session instance — a worktree, its services and its ports.

   Distinct from the coordinator's session record, and the difference is what each outlives: the
   work episode survives archival, the instance does not. Two concepts, two ids, and conflating
   them is what would make an archived episode look like a running one."
  :string)

(Module session-state
  "Where the session substrate keeps everything: instance state, session homes, templates, the
   shared cluster, and the registry of live worktrees.

   FOUR roots rather than one, and the split is by lifetime. `state/` is per-instance and dies
   with it; `sessions/` is the home an agent reads and outlives the services; `templates/` is
   per-project and shared across every session; `shared/` is one cluster many sessions attach to.
   A layout with one root would make reclaiming an instance a question of which files to spare."
  {:child [InstanceId]}
  (Operation state-dir "The per-instance state root." {:signature [:=> [:catn] Path]})
  (Operation keyed-id
    "`<project>--<key>`, with `/` written `~` so the id is one path segment — the one spelling of a
     key-derived id: a runtime's instance id, and a session's own key."
    {:signature [:=> [:catn [:project-name ProjectName] [:key :string]] :string]})
  (Operation session-key "One session's key, whatever runtime it is on: what its links are kept under."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]] :string]
     :delegates [keyed-id]})
  (Operation session-profile-path
    "Where the profile one session was brought up with is kept: under its own key, never its
     runtime's, because sessions sharing a runtime may each have created their working copy
     differently. Outside every instance's state directory, so neither a shared runtime nor
     reclaim replaces it."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]] Path]
     :delegates [session-key]})
  (Operation read-session-profile
    "The profile one session was brought up with, or nil — read where `session-profile-path` keeps
     it, else where a session that predates it had its runtime to itself — the state directory
     keyed by its worktree's leaf, the last segment of its name — copied to its own place on that
     first read so it outlives the runtime it left."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]] [:maybe :any]]
     :delegates [session-profile-path instance-state-dir]})
  (Operation instance-state-dir "One instance's state directory."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [state-dir]})
  (Operation session-state-file "One instance's own record."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [instance-state-dir]})
  (Operation log-dir "An instance's logs."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [instance-state-dir]})
  (Operation log-file "One service's log within an instance."
    {:signature [:=> [:catn [:instance-id InstanceId] [:service-name :any]] Path] :delegates [log-dir]})
  (Operation pid-file
    "One process service's pid within an instance, written at spawn — before session.edn exists
     to name it, so a boot killed half-way still leaves its detached process on record."
    {:signature [:=> [:catn [:instance-id InstanceId] [:service-name :any]] Path]
     :delegates [instance-state-dir]})
  (Operation pid-files
    "Every pid file an instance holds. One left after its session was stopped names a process the
     stop could not show was gone."
    {:signature [:=> [:catn [:instance-id InstanceId]] [:vector Path]] :delegates [instance-state-dir]})
  (Operation pg-data-dir "An instance's own Postgres data directory."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [instance-state-dir]})
  (Operation sessions-root "The session-home root."
    {:signature [:=> [:catn] Path]})
  (Operation casualties-dir
    "Where the record of sessions that died with the host is kept — under the nido home, never
     under the per-instance state root, so reclaiming an instance cannot delete it."
    {:signature [:=> [:catn] Path]})
  (Operation session-home-dir
    "A session's home — where its briefing, its MCP config and its agent files live. Keyed by
     project and session NAME rather than instance id, because the home outlives the services."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]] Path]
     :delegates [sessions-root]})
  (Operation templates-dir "The template root." {:signature [:=> [:catn] Path]})
  (Operation project-template-dir "A project's template directory."
    {:signature [:=> [:catn [:project-name ProjectName]] Path] :delegates [templates-dir]})
  (Operation template-pg-data-dir "A project template's Postgres data directory."
    {:signature [:=> [:catn [:project-name ProjectName]] Path] :delegates [project-template-dir]})
  (Operation refresh-snapshot-dir "Where a template refresh keeps its pre-refresh snapshot. Present
     outside a running refresh only when one was killed, and then it is the known-good template."
    {:signature [:=> [:catn [:data-dir Path]] Path]})
  (Operation template-meta-file "A project template's metadata."
    {:signature [:=> [:catn [:project-name ProjectName]] Path] :delegates [project-template-dir]})
  (Operation template-log-file "A project template's log."
    {:signature [:=> [:catn [:project-name ProjectName]] Path] :delegates [project-template-dir]})
  (Operation read-template-meta "A project template's metadata, or nil."
    {:signature [:=> [:catn [:project-name ProjectName]] [:maybe :map]] :delegates [template-meta-file]})
  (Operation write-template-meta! "Persist a project template's metadata."
    {:signature [:=> [:catn [:project-name ProjectName] [:data :map]] :any] :delegates [template-meta-file]})
  (Operation shared-dir "The shared-cluster root." {:signature [:=> [:catn] Path]})
  (Operation project-shared-dir "A project's shared-cluster directory."
    {:signature [:=> [:catn [:project-name ProjectName]] Path] :delegates [shared-dir]})
  (Operation shared-pg-data-dir "The shared cluster's data directory."
    {:signature [:=> [:catn [:project-name ProjectName]] Path] :delegates [project-shared-dir]})
  (Operation shared-log-file "The shared cluster's log."
    {:signature [:=> [:catn [:project-name ProjectName]] Path] :delegates [project-shared-dir]})
  (Operation shared-meta-file "The shared cluster's metadata."
    {:signature [:=> [:catn [:project-name ProjectName]] Path] :delegates [project-shared-dir]})
  (Operation shared-lock-file
    "The lock guarding shared-cluster CREATION — two sessions coming up at once would otherwise
     both find no cluster and both build one."
    {:signature [:=> [:catn [:project-name ProjectName]] Path] :delegates [project-shared-dir]})
  (Operation read-shared-meta "The shared cluster's metadata, or nil."
    {:signature [:=> [:catn [:project-name ProjectName]] [:maybe :map]] :delegates [shared-meta-file]})
  (Operation write-shared-meta! "Persist the shared cluster's metadata."
    {:signature [:=> [:catn [:project-name ProjectName] [:data :map]] :any] :delegates [shared-meta-file]})
  (Operation pg-mode-override-file "A session's Postgres-mode override file."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [instance-state-dir]})
  (Operation read-pg-mode-override "A session's Postgres-mode override, or nil."
    {:signature [:=> [:catn [:instance-id InstanceId]] [:maybe :map]] :delegates [pg-mode-override-file]})
  (Operation write-pg-mode-override! "Set a session's Postgres mode."
    {:signature [:=> [:catn [:instance-id InstanceId] [:mode :keyword]] :any] :delegates [pg-mode-override-file]})
  (Operation clear-pg-mode-override! "Drop a session's Postgres-mode override."
    {:signature [:=> [:catn [:instance-id InstanceId]] :any] :delegates [pg-mode-override-file]})
  (Operation read-instance-worktree
    "The worktree an instance's state dir records — its worktree record, or the :project-dir of
     its session record where it has none — or nil when it records neither. Written with the
     instance's record and left when that record is deleted — so a down session's state dir still
     says whose it is."
    {:signature [:=> [:catn [:instance-id InstanceId]] [:maybe Path]] :delegates [instance-state-dir]})
  (Operation read-session "One instance's record, or nil."
    {:signature [:=> [:catn [:instance-id InstanceId]] [:maybe :map]] :delegates [session-state-file]})
  (Operation write-session!
    "Persist an instance's record, and the worktree it names in a record of its own that outlives it."
    {:signature [:=> [:catn [:instance-id InstanceId] [:data :map]] :any] :delegates [session-state-file]})
  (Operation delete-session!
    "Drop an instance's record; its worktree record stays, written from the record first when
     there was none."
    {:signature [:=> [:catn [:instance-id InstanceId]] :any] :delegates [session-state-file]})
  (Operation read-registry "The registry of live worktrees."
    {:signature [:=> [:catn] :map]})
  (Operation session-live?
    "Whether the session a registry entry records is up RIGHT NOW: one that recorded an app or nREPL
     port while one answers — the registry records what was started, the port says it is still
     there — and a lite session, which records none, while its entry stands. The one predicate
     every reader of liveness asks, whichever level it sits at."
    {:signature [:=> [:catn [:s :map]] :boolean] :delegates [proc/tcp-open?]})
  (Operation upsert-registry!
    "Record or update one worktree's entry.

     Every session verb mutates this one file — up, down, destroy, reclaim, the daemon adopting
     an orphan — so it is the most contended state here, and the read happens inside the lock."
    {:signature [:=> [:catn [:project-dir Path] [:entry :map]] :any]
     :delegates [io/update-edn!]})
  (Operation remove-from-registry! "Drop one worktree's entry."
    {:signature [:=> [:catn [:project-dir Path]] :any] :delegates [io/update-edn!]})
  (Operation remove-many-from-registry!
    "Drop several entries in ONE write. N locked updates would be correct too; one write is kept
     because removing a set of sessions is one decision, and a reader never catches it half-done."
    {:signature [:=> [:catn [:ks [:vector :any]]] :any] :delegates [io/update-edn!]}))
