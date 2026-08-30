(ns canvas.session.postgres
  "Self-spec: `nido.session.shared-pg` and `nido.session.services.postgresql` — the database a
   session works against.

   Two modes, and the whole design is the trade between them. A PRIVATE cluster is an APFS clone
   of the project template: seconds to make, safe to destroy, and one per session. A SHARED
   cluster is one instance many sessions attach to: no clone cost at all, and everyone sees
   everyone's writes. A session picks per its profile and can switch with `isolate!`."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.state :as sstate]
            [fukan.common.typing.malli]))

(Module session-shared-pg
  "The per-project shared Postgres cluster: bringing it up, keeping it at main, and the
   application role that may not run DDL.

   Creation is under an OS FILE LOCK, because two sessions coming up at once would otherwise
   both find no cluster and both build one. The port is DERIVED from the project name rather
   than allocated, so the same project always lands on the same port and a stale connection
   string does not silently reach someone else's database.

   Migrations are advanced to `main@origin` rather than to the working copy: a shared cluster
   that followed one session's branch would break every other session on it."
  (Operation resolve-shared-port
    "A project's shared-cluster port, derived from its name so it is stable across restarts."
    {:signature [:=> [:catn [:project-name ProjectName]] :int]})
  (Operation with-lock
    "Run a function holding an exclusive OS file lock — what stops two sessions building the
     cluster at the same time."
    {:signature [:=> [:catn [:lock-path Path] [:f :any]] :any]})
  (Operation ensure-up! "Ensure the shared cluster exists and is running."
    {:signature [:=> [:catn [:project-name ProjectName]] :any]
     :delegates [with-lock resolve-shared-port sstate/shared-lock-file sstate/shared-pg-data-dir
                 sstate/write-shared-meta!]})
  (Operation status "What state the shared cluster is in."
    {:signature [:=> [:catn [:project-name ProjectName]] :any] :delegates [sstate/read-shared-meta]})
  (Operation down! "Stop the shared cluster, preserving its data."
    {:signature [:=> [:catn [:project-name ProjectName]] :any] :delegates [sstate/shared-pg-data-dir]})
  (Operation reset! "Drop the cluster's data and re-clone it from the template."
    {:signature [:=> [:catn [:project-name ProjectName]] :any]
     :delegates [down! sstate/shared-pg-data-dir]})
  (Operation destroy! "Stop and remove the shared cluster entirely."
    {:signature [:=> [:catn [:project-name ProjectName]] :any]})
  (Operation migration-file->version "A migration filename's version, or nil."
    {:signature [:=> [:catn [:filename :string]] [:maybe :int]]})
  (Operation migration-file->description "A migration filename's description."
    {:signature [:=> [:catn [:filename :string]] :string]})
  (Operation pending-migrations "The migrations newer than what has been applied."
    {:signature [:=> [:catn [:applied-max :any] [:filenames [:vector :string]]] [:vector :string]]
     :delegates [migration-file->version]})
  (Operation app-role-sql
    "Idempotent SQL establishing the application role — which may read and write but NOT run
     DDL, so a session cannot migrate the cluster everyone else is on."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation run-owner-sql! "Run SQL against the shared cluster as its owner."
    {:signature [:=> [:catn [:opts :map]] :any]})
  (Operation ensure-app-role! "Create or refresh the DDL-less application role."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [app-role-sql run-owner-sql!]})
  (Operation history-insert-sql "One row matching what Flyway itself would have written."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation shared-applied-max "The highest version and rank the cluster has applied."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation list-main-migration-files
    "The migration files on `main@origin`, read through jj — one checkout-free read, so a
     session's own branch cannot influence what the shared cluster migrates to."
    {:signature [:=> [:catn [:source-repo Path]] [:vector :string]]})
  (Operation materialize-one! "Write one `main@origin` migration file into a directory."
    {:signature [:=> [:catn [:source-repo Path] [:dest-dir Path] [:filename :string]] :any]})
  (Operation advance-shared-to-main! "Apply every pending `main@origin` migration."
    {:signature [:=> [:catn [:opts :map]] :any]
     :delegates [list-main-migration-files pending-migrations materialize-one! shared-applied-max history-insert-sql]})
  (Operation ensure-ready! "Bring the shared cluster up, migrated and with its app role in place."
    {:signature [:=> [:catn [:project-name ProjectName] [:opts :map]] :any]
     :delegates [ensure-up! advance-shared-to-main! ensure-app-role!]}))

(Module services-postgresql
  "The Postgres service itself: initdb, clone, start, stop, and the fresh-database setup.

   `clone-pgdata!` is the reason a session comes up in seconds — an APFS copy-on-write clone of
   the project template rather than an initdb and a migration run per session."
  (Operation find-pg-bin-dir "Where the Postgres binaries are."
    {:signature [:=> [:catn] [:maybe Path]]})
  (Operation pg-cmd "One Postgres command's full path."
    {:signature [:=> [:catn [:bin-dir Path] [:cmd :string]] :string]})
  (Operation flyway-checksum
    "A Flyway-compatible checksum of a migration file — computed here so a row nido inserts is
     indistinguishable from one Flyway would have."
    {:signature [:=> [:catn [:file-path Path]] :any]})
  (Operation initdb! "Initialise a cluster if it is not already. Idempotent."
    {:signature [:=> [:catn [:bin-dir Path] [:data-dir Path] [:db-user :string]] :any]
     :delegates [pg-cmd]})
  (Operation template-stopped?
    "Whether a data directory has no live postmaster — checked before cloning, because cloning a
     running cluster copies its in-flight state."
    {:signature [:=> [:catn [:data-dir Path]] :boolean]})
  (Operation clone-pgdata! "APFS copy-on-write clone of a data directory."
    {:signature [:=> [:catn [:source-data-dir Path] [:target-data-dir Path]] :any]
     :delegates [template-stopped?]})
  (Operation pg-ctl-start! "Start a cluster on a port."
    {:signature [:=> [:catn [:bin-dir Path] [:data-dir Path] [:pg-port :int] [:log-path Path]
                            [:opts [:? :any]]] :any]
     :delegates [pg-cmd]})
  (Operation wait-for-tcp! "Block until a port accepts connections, or give up."
    {:signature [:=> [:catn [:pg-port :int] [:opts [:? :any]]] :any]})
  (Operation pg-ctl-stop! "Stop a cluster. True on a clean shutdown."
    {:signature [:=> [:catn [:data-dir Path]] :boolean] :delegates [pg-cmd find-pg-bin-dir]})
  (Operation read-pg-pid "A running cluster's postmaster pid."
    {:signature [:=> [:catn [:data-dir Path]] [:maybe :int]]})
  (Operation detect-running-postmaster "Whether a data directory has a live postmaster, and which."
    {:signature [:=> [:catn [:data-dir Path]] :map]})
  (Operation dropdb! "Drop a database if it exists."
    {:signature [:=> [:catn [:bin-dir Path] [:pg-port :int] [:db-user :string] [:db-name :string]] :any]
     :delegates [pg-cmd]})
  (Operation setup-fresh-database! "Create the application database and apply its baseline."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [pg-cmd flyway-checksum]})
  (Operation resolve-pg-mode
    "The effective provisioning mode for a service — private clone or shared cluster."
    {:signature [:=> [:catn [:service-def :map]] :keyword]}))
