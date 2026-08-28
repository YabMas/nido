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
  (Operation with-child-registered
    "Run `f` with `proc` registered for shutdown, deregistering however f ends."
    {:signature [:=> [:catn [:proc :any] [:f [:=> [:catn] :any]]] :any]})
  (Operation process-alive? "Is this pid alive?"
    {:signature [:=> [:catn [:pid :int]] :boolean]})
  (Operation stop-process! "Stop one pid, if it is alive."
    {:signature [:=> [:catn [:pid :int]] :any]
     :delegates [process-alive?]})
  (Operation stop-process-group!
    "SIGTERM a whole process group, escalating to SIGKILL."
    {:signature [:=> [:catn [:pgid :int]] :any]})
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
     dead session rather than an unusable port."
    {:signature [:=> [:catn [:preferred-port :int] [:max-attempts :int]] :int]
     :delegates [port-free?]})
  (Operation quoted "Shell-quote a string."
    {:signature [:=> [:catn [:s :string]] :string]})
  (Operation log-tail "The last n lines of a file, or empty when it does not exist."
    {:signature [:=> [:catn [:path :string] [:lines :int]] :string]})
  (Operation rss-bytes "Resident set size of a pid in bytes, or nil."
    {:signature [:=> [:catn [:pid :int]] [:or :int :nil]]})
  (Operation human-bytes "A byte count as a short human string."
    {:signature [:=> [:catn [:v [:or :int :nil]]] :string]}))
