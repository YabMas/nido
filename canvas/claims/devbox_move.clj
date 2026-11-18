(ns canvas.claims.devbox-move
  "The claims the devbox-move design commits nido to: nido runs whole on a Linux host, and the
   person drives it from another machine through the desk.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.attention :refer [boot-attention]]
            [canvas.coordinator.daemon.lifecycle :refer [daemon-launchctl daemon-supervisor]]
            [canvas.platform.core :refer [platform-core]]
            [canvas.platform.desk :refer [Effect platform-desk]]
            [canvas.platform.process :refer [platform-process]]
            [canvas.platform.secrets :refer [platform-secrets]]
            [canvas.session.lifecycle :refer [session-lifecycle]]
            [canvas.session.postgres :refer [services-postgresql]]
            [canvas.session.support :refer [session-commands session-template]]
            [canvas.ui.surface :refer [ui-server ui-tui]]))

(Claim desk-reads-no-record
  "A desk on the person's machine reads and writes no file under nido-home on nido's host; it receives effect requests only as a stream a process on nido's host writes."
  {:about [platform-desk] :evidence "round"})

(Claim effects-go-through-the-desk
  "Opening a terminal tab, opening a URL and posting a notification are requested of the desk; no namespace outside platform-desk and platform-desktop runs `open`, writes a Warp tab config, or posts a notification."
  {:about [platform-desk session-lifecycle ui-tui boot-attention] :evidence "round"})

(Claim desk-performs-where-the-person-is
  "When nido's host is the person's machine, an effect is performed in place, as today. Otherwise it is performed by the desk on the person's machine, and a terminal effect opens a local tab that runs `ssh -t <nido's host>` into the requested directory and command."
  {:about [platform-desk] :evidence "round"})

(Claim desk-parses-every-request
  "The desk performs an effect only after it parses as an Effect — a closed map of the terminal, url or notify kind, a url effect's URL being http or https — and drops, logging, any request that does not. A terminal effect's command runs on nido's host: in place when nido's host is the person's machine, inside the ssh session the desk opens otherwise; a desk never runs a requested command in a local shell of a machine that is not nido's host."
  {:about [platform-desk Effect] :evidence "round"})

(Claim no-stale-tabs
  "A desk performs terminal and URL effects requested after it connected, never older ones; notifications missed while no desk was connected are not replayed one by one."
  {:about [platform-desk] :evidence "round"})

(Claim one-supervisor-door
  "Outside daemon-supervisor and the launchd and systemd adapters it composes, nothing invokes launchctl or systemctl or reads or writes the daemon's unit file. With the service installed — under launchd on macOS, systemd --user on Linux — the daemon, and the dashboard that runs inside it, is started again after a crash and after a reboot."
  {:about [daemon-supervisor daemon-launchctl] :evidence "round"})

(Claim dashboard-behind-the-desk
  "The dashboard listens on the loopback interface of nido's host only; from another machine it is reached through a port the desk forwards over its ssh connection."
  {:about [ui-server platform-desk] :evidence "round"})

(Claim host-boot-everywhere
  "host-boot answers an identity that is stable within one boot and new after each reboot, and its start instant, on macOS and Linux; load-average answers on both."
  {:about [platform-process] :evidence "round"})

(Claim java-home-everywhere
  "Resolving a JDK home for a project command answers a home or nil on macOS and Linux; a probe that only macOS has is never what makes a project command fail."
  {:about [session-commands] :evidence "round"})

(Claim clone-is-cheap-where-the-filesystem-allows
  "A stopped cluster's directory is cloned with the filesystem's copy-on-write (clonefile on APFS, reflink elsewhere) and falls back to a full copy only where the filesystem has none, saying so in the session's log; the clone never fails because copy-on-write is missing."
  {:about [services-postgresql session-template] :evidence "round"})

(Claim pg-found-on-linux
  "Postgres binaries are found on PATH, under a Homebrew keg, or under a Debian-layout /usr/lib/postgresql/<major>/bin, the highest major first."
  {:about [services-postgresql] :evidence "round"})

(Claim secrets-one-door
  "Notion and Slack credentials are read and stored only through platform-secrets; nothing else runs `security`, and on a host without a Keychain they live in an owner-only file under nido-home."
  {:about [platform-secrets] :evidence "round"})

(Claim one-writer-across-cutover
  "The laptop's live ledger is copied to the devbox only after the laptop's daemon is stopped and uninstalled, and the devbox daemon runs against that copy only after the copy; before cutover the devbox nido runs against a nido-home holding none of the laptop's records."
  {:about [platform-core] :evidence "round"})
