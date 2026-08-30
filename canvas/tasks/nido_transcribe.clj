(ns canvas.tasks.nido-transcribe
  "Self-spec: `tasks.nido-transcribe` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-transcribe
  "bb task entry point for video transcription."
  (Operation exit!
    "The `exit!` entry point."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation parse-duration
    "Parse a duration into integer seconds."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation run
    "bb nido:transcribe-video <url> :out <path> [:model :small] [:timeout 5m]"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
