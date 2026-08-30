(ns canvas.coordinator.record.clock
  "Self-spec: `nido.coordinator.record.clock` — the coordinator's single time seam."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module record-clock
  "Every current-time read in the coordinator, through one door.

   One function, and the fan-in is the point: twenty-eight namespaces call it, so a test fakes
   time by redefining this rather than by poking each call site. A module whose whole value is
   that nobody goes around it."
  (Operation now-iso
    "The current instant, ISO-8601."
    {:signature [:=> [:catn] :string]}))
