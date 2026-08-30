(ns canvas.tasks.test-task
  "Self-spec: `tasks.nido-test` — a bb task entry point.

   ⚠ THE FILE NAME IS DELIBERATE. Fukan's spec discovery skips `*_test.clj`, so a canvas file
   named for this namespace could never be found. A Module's name is what pairs it with a
   namespace; the file it lives in is free, so it lives here instead. Worth a fukan fix — the
   exclusion is meant to skip a project's TESTS, not to make a namespace unmodellable because of
   what it is called.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-test
  "Run unit tests under test/. Optional :only <ns-prefix> filter."
  (Operation run
    "The `run` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
