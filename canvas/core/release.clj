(ns canvas.core.release
  "Self-spec: `nido.core.release`, part of the core cut-over design (workstream ws-20261122-3274ad). Its
   claims are in canvas.claims.core-cutover."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-release
  "The core daemon's composition root: store, writer, feed, api, turn runner, conductor rules and
   provisioner assembled into one supervised JVM process, started, health-gated and upgraded.

   HIDES how the daemon is assembled from its modules, what its health gate checks, and how an
   upgrade hands the command socket from one release to the next.

   It supplies the conductor rules the readers that ask nido's own verbs in the checkout: what the
   recipe owes a workstream, and a drain's coordinator brief."
  (Operation nido-home
    "The nido home: NIDO_HOME, else ~/.nido."
    {:signature [:=> [:catn] :string]})
  (Operation paths
    "The core's paths under a nido home: its store, socket, turns, releases and the live link."
    {:signature [:=> [:catn [:home [:? :string]]] :map]})
  (Operation current-release
    "The directory of the release the live link points at, or nil."
    {:signature [:=> [:catn [:paths :map]] [:maybe :string]]})
  (Operation sessions-lost
    "The keyed commands that record lost, at a host boot, the ready session of every open unit —
     what a start owes once the host booted since the last one, so a session the core records
     ready is one that is up."
    {:signature [:=> [:catn [:boot-id :string] [:wss [:sequential :map]]] [:sequential :map]]})
  (Operation fold-changed?
    "Whether release `b` folds events differently from release `a`: their domains differ."
    {:signature [:=> [:catn [:a [:maybe :string]] [:b :string]] :boolean]})
  (Operation gate
    "Why a store and this release may not serve, as data; empty when they may: the store opens at
     its schema, a sample of workstreams folds to the state stored for them, and each executor and
     the session verb answer."
    {:signature [:=> [:catn [:store :map] [:checks :map]] [:sequential :map]]})
  (Operation install-release!
    "Copy a checkout's deps.edn, src/ and resources/ into a named release, and answer its
     directory. A release already installed is left as it is."
    {:signature [:=> [:catn [:paths :map] [:from-dir :string] [:rev :string]] :string]})
  (Operation serving?
    "Whether the daemon answers on its socket within `timeout-ms`, as the named release when given."
    {:signature [:=> [:catn [:paths :map] [:timeout-ms :int]] :boolean]})
  (Operation run!
    "Run the core daemon until the process is killed. Exits 3 without binding the socket when the
     gate refuses, or when the old coordinator could run beside it."
    {:signature [:=> [:catn [:opts :map]] :any]})
  (Operation upgrade!
    "Make a release of a checkout live, or restore the one that ran: stop the daemon, rebuild the
     stored state when the fold changed, start it, and keep the release only if it serves in time.
     Answers {:live rev} or {:restored previous-rev :why}."
    {:signature [:=> [:catn [:opts :map]] :map]}))
