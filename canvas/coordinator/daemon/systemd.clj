(ns canvas.coordinator.daemon.systemd
  "Self-spec: `nido.coordinator.daemon.systemd` — the daemon's service on Linux. Its own file
   because its verbs share names with daemon-supervisor's, and an instance's symbol is its var."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [fukan.common.typing.malli]))

(Module daemon-systemd
  "The Linux systemd --user unit: rendering it, and the systemctl verbs around it — the half
   daemon-supervisor composes on Linux, as daemon-launchctl is on macOS.

   Restart=always and WantedBy=default.target are the unit's point: a daemon that exits is
   started again, and with the user lingering it starts at boot without a login."
  (Operation unit-dir
    "Where user units live. Wrapped so a test can redirect it."
    {:signature [:=> [:catn] Path]})
  (Operation unit-name
    "The unit's name, as systemctl addresses it."
    {:signature [:=> [:catn] :string]})
  (Operation unit-path
    "The unit file for this service."
    {:signature [:=> [:catn] Path] :delegates [unit-dir unit-name]})
  (Operation installed?
    "Whether the unit file is on disk."
    {:signature [:=> [:catn] :boolean] :delegates [unit-path]})
  (Operation write-unit!
    "Install the unit file."
    {:signature [:=> [:catn [:contents :string]] :any] :delegates [unit-path]})
  (Operation remove-unit!
    "Remove it. No-op when absent."
    {:signature [:=> [:catn] :any] :delegates [unit-path]})
  (Operation working-directory
    "The checkout the installed unit runs the daemon from, or nil when none is installed."
    {:signature [:=> [:catn] [:maybe :string]] :delegates [installed? unit-path]})
  (Operation sh!
    "Shell out and answer with what happened — a redef seam, so the verbs can be tested
     without a systemd."
    {:signature [:=> [:catn [:args :any]] :map]})
  (Operation active?
    "Whether systemd reports the unit active."
    {:signature [:=> [:catn] :boolean] :delegates [sh! unit-name]})
  (Operation daemon-reload!
    "Make systemd re-read unit files."
    {:signature [:=> [:catn] :map] :delegates [sh!]})
  (Operation enable!
    "Enable the unit at boot and (re)start it now."
    {:signature [:=> [:catn] :map] :delegates [sh! unit-name]})
  (Operation disable!
    "Stop the unit and disable it at boot."
    {:signature [:=> [:catn] :map] :delegates [sh! unit-name]})
  (Operation start!
    "Start the installed unit."
    {:signature [:=> [:catn] :map] :delegates [sh! unit-name]})
  (Operation stop!
    "Stop it, leaving it installed and enabled."
    {:signature [:=> [:catn] :map] :delegates [sh! unit-name]})
  (Operation restart!
    "A fresh daemon in place of the running one."
    {:signature [:=> [:catn] :map] :delegates [sh! unit-name]})
  (Operation render-unit
    "The unit file for this machine's paths. Pure."
    {:signature [:=> [:catn [:opts :map]] :string]}))
