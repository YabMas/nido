(ns canvas.claims.host-card
  "The claims the Operations host-card design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.platform.host :refer [read-host]]
            [canvas.coordinator.view.host :refer [view-host host-model process-kind]]
            [canvas.coordinator.control :refer [coordinator-control coordinator-pid]]
            [canvas.ui.surface :refer [ui-server operations-home-cards host-page]]))

(Claim host-read-without-a-process
  "read-host reads only files and JVM APIs — /proc/loadavg, /proc/meminfo, /proc/<pid>/status, /cmdline and /stat, Runtime.availableProcessors and the volume's total and usable space — and starts no process; it never throws. Load, memory and processes come from /proc alone, so on a host without it they are nil; cores and disk come from the JVM, so they are read there too, and disk is nil only when the volume itself cannot be read."
  {:about [read-host] :evidence "round"})

(Claim host-model-pure
  "view-host reads nothing and stores nothing: host-model is a function of the two readings and the pid it is handed."
  {:about [view-host] :evidence "round"})

(Claim host-kinds-partition
  "Every process in a reading except the coordinator is counted in exactly one kind: its own by argv — claude (argv0 basename claude, or under a claude versions directory), mcp (node, npm, npx), repl (java, or bb running nrepl), postgres — else its nearest classified ancestor's, else other. The coordinator pid is shown on its own line and counted in no kind."
  {:about [process-kind] :evidence "round"})

(Claim host-tone-thresholds
  "The card is toned :you when memory available is under 10% of total, or the volume's free space is under 10% of its size, or the 15-minute load exceeds the core count, and carries the reasons that set it; otherwise, and whenever the host could not be read, its tone is nil. A brief load spike — the 1-minute average over the cores while the 15-minute is not — is shown in the figures and does not tone the card."
  {:about [host-model] :evidence "test"})

(Claim coordinator-share-from-two-readings
  "The coordinator's CPU share is the change in its utime+stime over the wall time between the previous reading and the current, as a fraction of one core; with no previous reading, or a different coordinator pid in it, the share is absent and the card says it is being measured."
  {:about [host-model] :evidence "round"})

(Claim host-read-cached-off-tick
  "ui-server calls read-host on the request path, for the volume ~/.nido lives on, at most once per 4 seconds, and answers every request — every viewer, the card and the page — from a reading taken no more than 4 seconds before it; a failed or thrown read is caught and the card says the host could not be read. Nothing on the coordinator's tick calls read-host."
  {:about [ui-server read-host] :evidence "round"})

(Claim host-card-opens-its-page
  "The Host card shows load (1- and 15-minute) against cores; memory used and available, and swap used when any is; the used and total of the volume ~/.nido lives on; the coordinator's CPU share; and per kind the process count and resident memory. It links to /operations/host, which renders the same HostModel — those figures, then each kind's processes, heaviest first."
  {:about [operations-home-cards host-page] :evidence "round"})

(Claim coordinator-pid-from-control
  "The surface learns the coordinator's pid only from Control's coordinator-pid, which answers the pid of a live coordinator or nil; no nido.ui namespace reads the pid file."
  {:about [coordinator-pid] :evidence "round"})

(Claim pid-stays-behind-control
  "No nido.ui namespace reads the coordinator's pid file; Control exposes whether the daemon is alive but not the pid file, and the pids it publishes are those in latest-landing's record and the live coordinator's, as coordinator-pid answers it."
  {:about [coordinator-control] :evidence "round"})
