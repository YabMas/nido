(ns canvas.platform.process
  "Self-spec: `nido.platform.process` — child processes, ports, and the numbers a human reads.

   Two responsibilities that share a namespace because they share a subject: what nido has
   spawned, and where it can be reached. Nothing here knows what the process IS."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]))

(Module platform-process
  "Spawned children, port allocation, and process measurement."
  (Operation stop-live-children!
    "Stop every registered child still running, returning how many. Called from the shutdown
     hook AND directly by tests — a reaper that only runs at JVM exit is one nothing can check."
    {:signature [:=> [:catn] :int]})
  (Operation wait-within!
    "Wait for a process to exit, killing it once its budget has passed; says whether it did."
    {:signature [:=> [:catn [:proc :any] [:budget-ms [:maybe :int]]] :map]})
  (Operation with-child-registered
    "Run `f` with `proc` registered for shutdown, deregistering however f ends."
    {:signature [:=> [:catn [:proc :any] [:f [:=> [:catn] :any]]] :any]})
  (Operation run-exit-notes!
    "Run every registered exit note, returning how many returned without throwing. A note that
     throws is dropped: at shutdown an exception costs more than what it was reporting, because
     the hook thread dies with the children still running."
    {:signature [:=> [:catn] :int]})
  (Operation with-exit-note
    "Run `f` with `note!` registered to run at JVM shutdown, deregistering however f ends. What
     lets a caller put something on the record at the moment a person stops the command — SIGINT
     runs shutdown hooks, and until the hook returns this process's memory is the only account
     of what was interrupted."
    {:signature [:=> [:catn [:note! [:=> [:catn] :any]] [:f [:=> [:catn] :any]]] :any]})
  (Operation at-exit!
    "What the shutdown hook does: the notes, THEN the reap. The order is the point — destroying
     a child unblocks the thread that was reading it, and everything that thread does while
     unwinding would land on top of a note written afterwards."
    {:signature [:=> [:catn] :any]
     :delegates [run-exit-notes! stop-live-children!]})
  (Operation process-alive? "Is this pid alive?"
    {:signature [:=> [:catn [:pid :int]] :boolean]})
  (Operation process-started
    "When the process now running under a pid started, in epoch milliseconds; :gone when nothing
     runs under it; :unknown when something does and the OS will not say when it started."
    {:signature [:=> [:catn [:pid :int]] [:or :int [:enum :gone :unknown]]]})
  (Operation stop-process! "Stop one pid, if it is alive."
    {:signature [:=> [:catn [:pid :int]] :any]
     :delegates [process-alive?]})
  (Operation stop-process-group!
    "SIGTERM a whole process group, escalating to SIGKILL."
    {:signature [:=> [:catn [:pgid :int]] :any]})
  (Operation awake-ms
    "Milliseconds on a clock that stands still while the host sleeps — the clock a timeout on a
     process nido waits for belongs on, since a spawned process makes no progress asleep."
    {:signature [:=> [:catn] :int]})
  (Operation port-free? "Can this port be bound?"
    {:signature [:=> [:catn [:port :int]] :boolean]})
  (Operation tcp-open? "Is something listening on this port?"
    {:signature [:=> [:catn [:port :int]] :boolean]})
  (Operation deterministic-port
    "A deterministic port from a seed string within [low, high) — so a session's ports are the
     same every time it comes up."
    {:signature [:=> [:catn [:seed :string] [:low :int] [:high :int]] :int]})
  (Operation find-available-port
    "The first port at or above `preferred-port` that is free AND reachable by a browser. Free
     is not sufficient: a browser refuses its restricted-port list, and the failure looks like a
     dead session rather than an unusable port. Free is bindable, or — `:probe :listen` — that
     nothing listens there, for a server that rebinds a TIME_WAIT port and keeps its preferred
     one so. Nor is it, within a runtime start (`*picked-ports*`), a port it already handed out,
     nor, ever, one another owner holds in the port ledger: every pick reads the ledger, and one
     made for an owner (`*port-owner*`) is published to it, under one cross-process lock. The one
     way nido chooses a port to bind."
    {:signature [:=> [:catn [:preferred-port :int] [:max-attempts :int]
                  [:opts [:? [:map [:probe {:optional true} [:enum :bind :listen]]]]]] :int]
     :delegates [port-free? tcp-open?]})
  (Operation release-ports!
    "Drop every port the port ledger holds for `owner` — a runtime whose start failed or which
     was stopped."
    {:signature [:=> [:catn [:owner :string]] :any]})
  (Operation quoted "Shell-quote a string."
    {:signature [:=> [:catn [:s :string]] :string]})
  (Operation inherited-env "This process's environment, as a map."
    {:signature [:=> [:catn] [:map-of :string :string]]})
  (Operation project-shell-env
    "The environment for a shell that runs a project's own code: this process's, minus the
     shell-startup variables (BASH_ENV, ENV) a caller exported, plus `extra`. A project command
     runs the same whether a person or an agent started it; an agent's inherited BASH_ENV once
     made a git stub recurse until the core's cgroup ran out of tasks."
    {:signature [:=> [:catn [:extra [:maybe [:map-of :string :string]]]] [:map-of :string :string]]
     :delegates [inherited-env]})
  (Operation log-tail "The last n lines of a file, or empty when it does not exist."
    {:signature [:=> [:catn [:path :string] [:lines :int]] :string]})
  (Operation rss-bytes "Resident set size of a pid in bytes, or nil."
    {:signature [:=> [:catn [:pid :int]] [:maybe :int]]})
  (Operation human-bytes "A byte count as a short human string."
    {:signature [:=> [:catn [:v [:maybe :int]]] :string]})
  (Operation host-boot
    "This host boot: its identity and the instant it began, or nil when it cannot be read."
    {:signature [:=> [:catn] [:maybe :map]]})
  (Operation load-average
    "The machine's 1-minute load average, or nil when it cannot be read. What tells a slow
     reading apart from a busy machine."
    {:signature [:=> [:catn] [:maybe :double]]}))
