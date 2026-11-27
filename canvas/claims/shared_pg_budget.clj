(ns canvas.claims.shared-pg-budget
  "The claims the shared Postgres connection-budget design (workstream ws-20261126-c3ff9a, design at
   entry 73) commits nido to, declared where they live on. Each Claim's name is the claim's id, its
   docstring the statement, and its :about the elements it is about — the same three the design
   record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.failure :refer [session-failure]]
            [canvas.session.postgres :refer [ensure-up! services-postgresql session-shared-pg]]
            [canvas.session.provisioning :refer [session-engine]]))

(Claim shared-limit-declared
  "Every start nido makes of a project's shared cluster runs its postmaster with the
   max_connections the project's shared :postgresql service declares as :max-connections at that
   start, or nido's default when it declares none, written into a settings file the cluster's
   postgresql.conf includes as its last line, immediately before that start and at no other time;
   that start also drops any max_connections an ALTER SYSTEM left in postgresql.auto.conf, so the
   settings file is what the postmaster applies. The limit a running cluster started with is read
   from that file, or, for a postmaster nido found running with no such file, from what PostgreSQL
   applied: the max_connections postgresql.auto.conf sets, else the one the cluster's own
   postgresql.conf sets, else PostgreSQL's stock 100."
  {:about [session-shared-pg ensure-up!] :evidence "round"})

(Claim no-restart-to-apply
  "nido never stops or restarts a serving shared cluster to apply a changed :max-connections; a
   changed value reaches the cluster at its next start, and until then admission judges against the
   value it is running with."
  {:about [session-shared-pg] :evidence "round"})

(Claim admitted-before-jvm
  "A shared-mode :postgresql start — every one declares :session-connections [declared-or-refused]
   — is admitted only when the connections claimed on the cluster, plus its own
   :session-connections, plus :reserved-connections, are at most the cluster's started-with limit
   [shared-limit-declared]. session-shared-pg answers the admission as a value, admitted or
   refused; on a refusal the :postgresql service throws, so no service declared after it — the
   session's JVM included — is started."
  {:about [services-postgresql session-shared-pg session-engine] :evidence "round"})

(Claim claims-count-every-sibling
  "The connections claimed on a shared cluster are the declared :session-connections of every
   runtime on it that is registered as up or that holds a claim in the cluster's claims file; a
   runtime registered as up that holds no claim — one nido started before admission existed — is
   counted at the :session-connections the definition being admitted declares, a runtime counts
   once however many sessions share it, and two starts are admitted one at a time so each counts
   the other. A claim is taken at admission and is released by an attempt to remove it from the
   claims file, which nido makes at each of these: the :postgresql stop, rollback included; the
   :postgresql stop-recorded! a stop of a start that saved no state reaches; the stop of its
   runtime, by the runtime's id whatever services the definitions name; and a throw from the
   :postgresql start that took it, before the throw goes on. Each makes the attempt whether or not
   the runtime's processes were confirmed gone, and nothing keeps a claim, or a registry entry or
   record naming its runtime, because a process may have survived. An attempt has two outcomes: its
   write persists and the claim is gone; or its write throws, and it is reported naming the claims
   file and the runtime, does not throw on, and leaves the claim in the file, counted, with nothing
   kept to retry it."
  {:about [session-shared-pg] :evidence "round"})

(Claim refusal-names-budget
  "A budget refusal's error names the project's shared cluster, its started-with max_connections,
   the reserve, how many runtimes are on it and what they claim, and the session's own
   :session-connections, and gives the ways out: stop a session, or raise :max-connections and
   restart the shared cluster. It is kept as a failed start like any other, and every budget
   refusal of one project shares one cause."
  {:about [services-postgresql session-failure] :evidence "round"})

(Claim demand-declared-not-derived
  "A runtime's connection demand is the :session-connections its project declares; nido neither
   reads pool sizes out of a project's configuration or templates to derive it nor rewrites them to
   fit."
  {:about [session-engine services-postgresql] :evidence "round"})

(Claim declared-or-refused
  "A shared-mode :postgresql start whose definition does not declare :session-connections as a
   positive integer is refused before session-shared-pg is asked to admit it or ensure-ready! is
   called — so before the shared cluster is seeded, started or connected to, and before any later
   service, the session's JVM included, is started — with an error that names the missing or
   invalid field :session-connections and the project whose shared :postgresql service lacks it. It
   is kept as a failed start like any other. No shared-mode start proceeds without it."
  {:about [services-postgresql session-shared-pg] :evidence "round"})

(Claim brian-budget-admits-eleven
  "brian's shared :postgresql service declares a budget under which [admitted-before-jvm]'s
   inequality admits at least 11 runtimes at once: 11 × :session-connections +
   :reserved-connections is at most :max-connections. Its declared figures are :max-connections
   1000, :reserved-connections 20 and :session-connections 45, which admit 21."
  {:about [session-shared-pg] :evidence "round"})
