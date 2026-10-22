(ns canvas.tasks.nido-test
  "Self-spec: `tasks.nido-test` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-test
  "Run unit tests under test/, against a nido home made for the run — never the caller's.
   Optional :only <ns-prefix> filter."
  (Operation run
    "The `run` entry point: make a fresh test home and run the suite in a child bb whose
     NIDO_HOME is that home; in the child, refuse a home it was not given, then run the suite."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation home-refusal
    "Why the suite must not run against a home, or nil when it may: only the home made for this
     run, carrying the marker the run wrote, and never ~/.nido."
    {:signature [:=> [:catn [:expected [:maybe :string]] [:home :string]] [:maybe :string]]}))
