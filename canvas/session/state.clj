(ns canvas.session.state
  "Self-spec: `nido.session.state` — the filesystem layout the session substrate owns."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
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
  (Operation instance-state-dir "One instance's state directory."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [state-dir]})
  (Operation session-mcp-path "A session's rendered MCP config — a launch input."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [instance-state-dir]})
  (Operation session-state-file "One instance's own record."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [instance-state-dir]})
  (Operation log-dir "An instance's logs."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [instance-state-dir]})
  (Operation log-file "One service's log within an instance."
    {:signature [:=> [:catn [:instance-id InstanceId] [:service-name :any]] Path] :delegates [log-dir]})
  (Operation pg-data-dir "An instance's own Postgres data directory."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [instance-state-dir]})
  (Operation sessions-root "The session-home root."
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
  (Operation read-session "One instance's record, or nil."
    {:signature [:=> [:catn [:instance-id InstanceId]] [:maybe :map]] :delegates [session-state-file]})
  (Operation write-session! "Persist an instance's record."
    {:signature [:=> [:catn [:instance-id InstanceId] [:data :map]] :any] :delegates [session-state-file]})
  (Operation delete-session! "Drop an instance's record."
    {:signature [:=> [:catn [:instance-id InstanceId]] :any] :delegates [session-state-file]})
  (Operation read-registry "The registry of live worktrees."
    {:signature [:=> [:catn] :map]})
  (Operation write-registry! "Persist the registry."
    {:signature [:=> [:catn [:registry :map]] :any]})
  (Operation upsert-registry! "Record or update one worktree's entry."
    {:signature [:=> [:catn [:project-dir Path] [:entry :map]] :any]
     :delegates [read-registry write-registry!]})
  (Operation remove-from-registry! "Drop one worktree's entry."
    {:signature [:=> [:catn [:project-dir Path]] :any] :delegates [read-registry write-registry!]})
  (Operation remove-many-from-registry!
    "Drop several entries in ONE write. Removing them one at a time re-read and re-wrote the
     registry per entry, which is both slower and a wider window for a concurrent writer."
    {:signature [:=> [:catn [:ks [:vector :any]]] :any] :delegates [read-registry write-registry!]}))
