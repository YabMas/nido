(ns canvas.session.support
  "Self-spec: the pieces a session is assembled from — context substitution, links, profiles,
   the Postgres template, and project-declared commands."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.state :as sstate :refer [InstanceId]]
            [fukan.common.typing.malli]))

(Kind SessionContext
  "What a session's service definitions are rendered against: every service's contributions,
   under its own name. A `{{pg.port}}` in a service definition resolves here.")

(Kind Profile
  "A named session shape — what gets provisioned and how heavy it is. `:full` brings up
   services, `:lite` is a read-only symlink and nothing running, and the weight follows from
   that rather than being declared twice.")

(Module session-context
  "Template substitution over a session's context.

   The reason services are declared rather than scripted: a service definition names
   `{{pg.port}}` and this resolves it after Postgres has said what port it took, so nothing has
   to know a port before it exists."
  {:child [SessionContext]}
  (Operation resolve-ref "A dotted reference like `pg.port` against the context."
    {:signature [:=> [:catn [:ctx SessionContext] [:ref-str :string]] :any]})
  (Operation substitute-value "One value with its references resolved."
    {:signature [:=> [:catn [:ctx SessionContext] [:v :any]] :any] :delegates [resolve-ref]})
  (Operation substitute "A whole structure with every reference resolved."
    {:signature [:=> [:catn [:ctx SessionContext] [:data :any]] :any] :delegates [substitute-value]})
  (Operation merge-context "A service's contributions, under its own name."
    {:signature [:=> [:catn [:ctx SessionContext] [:service-name :any] [:contributions :map]] SessionContext]})
  (Operation prepare-jvm
    "Joined-string forms of JVM config, so a service definition can interpolate them directly
     instead of every template knowing how to join a classpath."
    {:signature [:=> [:catn [:jvm :map]] :map]}))

(Module session-links
  "The links a session carries: tickets, pull requests, threads.

   Per session rather than per workstream, because what an agent should have open is a property
   of the episode, not of the work."
  (Operation links-path "Where a session's links live."
    {:signature [:=> [:catn [:instance-id InstanceId]] Path] :delegates [sstate/instance-state-dir]})
  (Operation read-links "A session's links. Empty rather than nil when there are none."
    {:signature [:=> [:catn [:instance-id InstanceId]] [:vector :map]] :delegates [links-path]})
  (Operation write-links! "Persist a session's links."
    {:signature [:=> [:catn [:instance-id InstanceId] [:links [:vector :map]]] :any] :delegates [links-path]})
  (Operation add! "Add a link, deduping on its URL so re-adding updates rather than repeats."
    {:signature [:=> [:catn [:instance-id InstanceId] [:link-input :map]] [:vector :map]]
     :delegates [read-links write-links!]})
  (Operation remove-by-url! "Drop a link by URL."
    {:signature [:=> [:catn [:instance-id InstanceId] [:url :string]] [:vector :map]]
     :delegates [read-links write-links!]})
  (Operation group-by-type "Links grouped by type, in display order."
    {:signature [:=> [:catn [:links [:vector :map]]] :any]}))

(Module session-profiles
  "The per-project profile registry: what `:full` and `:lite` mean here."
  {:child [Profile]}
  (Operation load-registry "A project's profile registry."
    {:signature [:=> [:catn [:project ProjectName]] [:maybe :map]]})
  (Operation services-provisioned? "Whether a resolved profile brings services up."
    {:signature [:=> [:catn [:profile Profile]] :boolean]})
  (Operation profile-weight
    "The session weight a profile implies. DERIVED rather than declared, so a profile that
     provisions services cannot also claim to be light."
    {:signature [:=> [:catn [:profile Profile]] :keyword] :delegates [services-provisioned?]})
  (Operation resolve-profile "A profile keyword resolved for a project."
    {:signature [:=> [:catn [:project ProjectName] [:profile-kw :keyword]] [:maybe Profile]]
     :delegates [load-registry]}))

(Module session-template
  "The per-project Postgres template cluster — the source every session's database is cloned from.

   A template plus APFS clones rather than an initdb per session: bringing a session up is a
   copy-on-write clone of a cluster that already has the schema, which is the difference between
   seconds and minutes."
  (Operation init! "Build a fresh template cluster from scratch."
    {:signature [:=> [:catn [:project-name ProjectName] [:opts [:* :any]]] :any]
     :delegates [sstate/template-pg-data-dir sstate/write-template-meta!]})
  (Operation refresh! "Bring the template up to date by running the project's declared refresh."
    {:signature [:=> [:catn [:project-name ProjectName]] :any]
     :delegates [sstate/template-pg-data-dir sstate/write-template-meta!]})
  (Operation status "What state a project's template is in."
    {:signature [:=> [:catn [:project-name ProjectName]] :any] :delegates [sstate/read-template-meta]})
  (Operation stop! "Stop the template cluster. No-op when it was never initialised."
    {:signature [:=> [:catn [:project-name ProjectName]] :any] :delegates [sstate/template-pg-data-dir]})
  (Operation destroy! "Stop and delete the template cluster."
    {:signature [:=> [:catn [:project-name ProjectName]] :any]}))

(Module session-commands
  "Project-declared commands: a keyword-addressable layer over shell invocations.

   The point is that nido calls `:dump-db` rather than a shell string — so what a project's
   commands ARE stays in the project's own configuration, and nido carries no per-project shell."
  (Operation resolve-command "A command definition by reference."
    {:signature [:=> [:catn [:commands-map :map] [:ref :any]] [:maybe :any]]})
  (Operation resolve-java-home "A JDK home for subprocesses that need one. Best-effort."
    {:signature [:=> [:catn] [:maybe :string]]})
  (Operation run-command! "Run a named project command against a context."
    {:signature [:=> [:catn [:commands-map :map] [:ref :any] [:context :map] [:opts [:? :any]]] :any]
     :delegates [resolve-command resolve-java-home]}))
