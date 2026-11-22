(ns canvas.coordinator.daemon.lifecycle
  "Self-spec: keeping the daemon running and saying so — `heartbeat`, `launchctl`, `supervisor`,
   `reconcile`, `restart`, `preprocess`."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.clock :as clock]
            [canvas.coordinator.record.runs :as runs]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.state :as state :refer [Path]]
            [canvas.coordinator.record.workstream :as ws]
            [canvas.platform.process :as process]
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
     :delegates [label]})
  (Operation working-directory
    "The checkout the installed plist runs the daemon from, or nil when none is installed."
    {:signature [:=> [:catn] [:maybe :string]]
     :delegates [installed? plist-path]}))

(Kind Landing
  "The latest landing: owed onto a commit — when, and by which landing process — or running it, in
   which daemon process and since when. Only what nothing else can tell: what an owed restart waits
   for is derived when it is asked, never stored."
  [:or
   [:map [:state [:enum :owed]] [:commit :string] [:owed-at :string] [:by-pid :int]]
   [:map [:state [:enum :running]] [:commit :string] [:owed-at :string]
         [:since :string] [:pid :int]]])

(Module daemon-supervisor
  "Which service manager keeps the daemon alive — launchd on macOS, systemd --user on Linux. Its
   callers ask for an outcome and never name either. The dashboard runs inside the daemon
   process, so supervising the daemon supervises it.

   The restart a landing owes is paid by the daemon exiting and the supervisor starting it
   again, so a supervisor that does not restart on exit is not one."
  (Operation manager
    "The service manager on this host — :launchd or :systemd. Wrapped so a test can pick one."
    {:signature [:=> [:catn] [:enum :launchd :systemd]]})
  (Operation install!
    "Install the daemon's service and start it, saying in words what it installed and where."
    {:signature [:=> [:catn [:spec :map]] :map]})
  (Operation uninstall!
    "Stop and remove it. No-op when absent."
    {:signature [:=> [:catn] :map]})
  (Operation start!
    "Start the installed service without installing it again."
    {:signature [:=> [:catn] :map]})
  (Operation stop!
    "Stop the installed service, leaving it installed."
    {:signature [:=> [:catn] :map]})
  (Operation restart!
    "Restart it now."
    {:signature [:=> [:catn] :map]})
  (Operation status
    "Whether it is installed and running, with the manager's own words for why not."
    {:signature [:=> [:catn] :map]})
  (Operation checkout
    "The checkout the installed service loads its code from — the working directory its unit file
     names — or nil when no service is installed."
    {:signature [:=> [:catn] [:maybe :string]]})
  (Operation with-service
    "Call `f` with every verb here acting on service `k` (a key of `services`) rather than the coordinator."
    {:signature [:=> [:catn [:k :keyword] [:f fn?]] :any]}))

