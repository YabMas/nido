(ns canvas.session.test-pg
  "Self-spec: `nido.session.test-pg` — the databases a session's test runs work against.

   A third answer to the trade `canvas.session.postgres` names. A private cluster isolates a
   session at the price of a postmaster each; the shared cluster costs nothing per session and
   isolates nobody. Test runs need the isolation and cannot afford the postmasters, and they hold
   nothing worth keeping — so they get one disposable cluster per project and a database each."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.patterns.fulfilment :refer [Fulfilment]]
            [canvas.session.service :as service]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module session-test-pg
  "The per-project test cluster, its template database, and the per-session databases cloned from
   it.

   The cluster is DISPOSABLE: it runs without fsync, full-page writes or synchronous commit, and
   holds nothing a rebuild from `main@origin`'s migrations cannot reproduce, so a crash is
   answered by deleting it. That is also why it is a different cluster from the shared one — the
   shared cluster holds a dev database worth keeping, and durability is a cluster-wide setting.

   The template is migrated to `main@origin` only, by nido, and no session is ever handed its
   name: a database cannot be cloned while anything else is connected to it, and a session
   connected to the template would also be a session whose writes every later clone inherits.
   A session's own branch migrations are its own test database's business, applied by the
   project's fixture."
  (Operation ensure-ready!
    "Bring the project's test cluster up and its template database to `main@origin`, under the
     project's test lock. Returns the cluster's port."
    {:signature [:=> [:catn [:project-name ProjectName] [:opts :map]] :map]})
  (Operation session-db-name
    "The test database name for one session: the declared stem and the session's instance, within
     Postgres's identifier limit. Distinct sessions never share one."
    {:signature [:=> [:catn [:stem :string] [:instance-id :string]] :string]})
  (Operation ensure-session-db!
    "Create a session's test database from the template if it does not exist. Idempotent; an
     existing database is left as the session last left it."
    {:signature [:=> [:catn [:project-name ProjectName] [:opts :map]] :map]
     :delegates [ensure-ready! session-db-name]})
  (Operation drop-session-db!
    "Bring the project's test cluster up and drop a session's test database if it exists. A
     cluster that is down cannot drop anything, and its databases would be back when it next
     started."
    {:signature [:=> [:catn [:project-name ProjectName] [:opts :map]] :any]
     :delegates [ensure-ready! session-db-name]})
  (Operation status "What state the project's test cluster is in, and which databases it holds."
    {:signature [:=> [:catn [:project-name ProjectName]] :any]})
  (Operation reset! "Delete the test cluster and rebuild it from the migrations."
    {:signature [:=> [:catn [:project-name ProjectName]] :any]}))

;; The test cluster is reached by sessions as one implementation of the session-service
;; protocol, by dispatch on :test-pg. Declared beside the supplier because the protocol must not
;; name its implementations.
(Fulfilment test-pg-starts-service {:satisfier session-test-pg :surface service/start-service!})
(Fulfilment test-pg-stops-service {:satisfier session-test-pg :surface service/stop-service!})
(Fulfilment test-pg-reports-status {:satisfier session-test-pg :surface service/service-status})
