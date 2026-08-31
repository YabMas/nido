(ns canvas.coordinator.daemon.lifecycle
  "Self-spec: keeping the daemon running and saying so — `heartbeat`, `launchctl`, `reconcile`,
   `notify`, `preprocess`."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.clock :as clock]
            [canvas.coordinator.record.runs :as runs]
            [canvas.coordinator.record.state :as state :refer [Path]]
            [fukan.common.typing.malli]))

(Module daemon-heartbeat
  "What the daemon last said about itself, stamped with when it said it.

   One function, and the timestamp is the whole point: the state alone cannot distinguish a
   daemon that is idle from one that stopped while idle."
  (Operation write!
    "Persist the daemon's state with a fresh timestamp."
    {:signature [:=> [:catn [:state :map]] :any]
     :delegates [state/status-path clock/now-iso]}))

(Module daemon-launchctl
  "The macOS LaunchAgent: rendering the plist, and the launchctl verbs around it.

   Rendering is PURE and separated from the shelling, so what gets installed can be asserted in
   a test without installing it."
  (Operation launch-agents-dir
    "Where LaunchAgents live. Wrapped so a test can redirect it."
    {:signature [:=> [:catn] Path]})
  (Operation label
    "The service label the daemon is known to launchd by."
    {:signature [:=> [:catn] :string]})
  (Operation plist-path
    "The plist file for this service."
    {:signature [:=> [:catn] Path]
     :delegates [launch-agents-dir label]})
  (Operation installed?
    "Whether the plist is on disk."
    {:signature [:=> [:catn] :boolean]
     :delegates [plist-path]})
  (Operation write-plist!
    "Install the plist."
    {:signature [:=> [:catn [:contents :string]] :any]
     :delegates [plist-path]})
  (Operation remove-plist!
    "Uninstall it. No-op when absent."
    {:signature [:=> [:catn] :any]
     :delegates [plist-path]})
  (Operation current-uid
    "The numeric user id, for the launchctl service target."
    {:signature [:=> [:catn] :string]})
  (Operation target
    "The launchctl service target — `gui/<uid>/<label>`."
    {:signature [:=> [:catn] :string]
     :delegates [current-uid label]})
  (Operation sh!
    "Shell out and answer with what happened rather than throwing — a redef seam, so the verbs
     below can be tested without a launchd."
    {:signature [:=> [:catn [:args :any]] :map]})
  (Operation loaded?
    "Whether launchd reports the service as loaded."
    {:signature [:=> [:catn] :boolean]
     :delegates [sh! target]})
  (Operation bootstrap!
    "Load the plist into the user's launchd domain."
    {:signature [:=> [:catn] :map]
     :delegates [sh! plist-path]})
  (Operation bootout!
    "Unload the service, which kills the running daemon and stops it coming back."
    {:signature [:=> [:catn] :map]
     :delegates [sh! target]})
  (Operation kickstart!
    "Restart the daemon in place."
    {:signature [:=> [:catn] :map]
     :delegates [sh! target]})
  (Operation render-plist
    "The plist XML for this machine's paths. Pure — what gets installed can be asserted without
     installing it."
    {:signature [:=> [:catn [:opts :map]] :string]
     :delegates [label]}))

(Module daemon-reconcile
  "Startup repair: force every non-terminal Run to a terminal state.

   Runs on startup because a Run left mid-flight by a crash is indistinguishable from one still
   going, and the daemon that could have told them apart is the one that died."
  (Operation reconcile!
    "Settle every Run the last daemon left in flight."
    {:signature [:=> [:catn] :any]
     :delegates [runs/read-run runs/write-run! state/runs-dir]}))

(Module daemon-notify
  "Best-effort outbound notification on a Run's lifecycle events.

   Best-effort by contract: a notification that failed must not fail the Run it was about."
  (Operation on-plan-spawn!
    "Tell the outside world a plan Run started."
    {:signature [:=> [:catn [:run :map]] :any]}))
