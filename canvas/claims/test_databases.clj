(ns canvas.claims.test-databases
  "The claims the per-session test database design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.postgres :refer [session-shared-pg]]
            [canvas.session.test-pg :refer [session-test-pg ensure-ready! session-db-name
                                            ensure-session-db! drop-session-db!]
             :as tpg]))

(Claim test-db-per-session
  "Every session whose project declares a test database is handed, in its service context, exactly one test database, and no two sessions of a project are ever handed the same one."
  {:about [ensure-session-db! session-db-name] :evidence "round"})

(Claim one-test-cluster-per-project
  "Every test database nido creates for a project lives in that project's one test cluster; starting a session never starts a test postmaster when the project's test cluster is already running, so a project runs at most one test postmaster however many sessions it has. A session on a private dev cluster still runs its own dev postmaster; that is the dev side's cost, not the test side's."
  {:about [ensure-ready! session-test-pg] :evidence "round"})

(Claim test-cluster-is-disposable
  "The test cluster runs with fsync, full_page_writes and synchronous_commit off, trading its databases' survival across a crash for speed."
  {:about [session-test-pg] :evidence "round"})

(Claim lost-test-db-rebuilt-by-next-run
  "While the project's test cluster is running, a test run in a session whose test database is missing recreates that database in the test cluster under the name its session's context gives, discards any connection pool it had cached on the lost database, and migrates the new one to the branch's migrations before any test uses it; the credentials the session's context gives may create it."
  {:about [session-test-pg] :evidence "round"})

(Claim test-cluster-rebuilt-only-when-down
  "A session start never deletes or reinitializes the project's test cluster while a postmaster is running over it; the operator's reset! is the only thing that rebuilds a running test cluster."
  {:about [ensure-ready! tpg/reset!] :evidence "round"})

(Claim dev-cluster-untouched
  "Nothing in nido creates a test database in the shared cluster, and nothing changes the shared cluster's durability settings."
  {:about [session-shared-pg session-test-pg] :evidence "round"})

(Claim template-is-nidos-alone
  "The template database every session's test database is cloned from is migrated by nido to main@origin only, and no session's service context names it."
  {:about [ensure-ready! session-test-pg] :evidence "round"})

(Claim test-connections-bounded-apart
  "Test database connections count only against the test cluster's own max_connections, which the project's service definition declares; none count against the shared cluster's."
  {:about [session-test-pg] :evidence "round"})

(Claim test-db-ends-with-its-session
  "The session destroy verb drops the session's test database, bringing the project's test cluster up to do so when it is down; stopping a session keeps it, unless the test cluster itself is reset or rebuilt meanwhile, which takes every session's test database with it. A session that ends without the destroy verb leaves its test database in the test cluster until the cluster is reset or rebuilt."
  {:about [drop-session-db! ensure-session-db!] :evidence "round"})

(Claim test-run-uses-only-its-own-db
  "Every connection a session's test run opens or uses goes to the test database its session's service context names; none goes to the dev database the session's :postgresql service names, nor to another session's test database."
  {:about [session-test-pg] :evidence "round"})
