(ns canvas.coordinator.record.generation
  "Self-spec: `nido.coordinator.record.generation` — a workstream's ledger read as generations."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module record-generation
  "Which generation of a workstream's design an entry belongs to.

   HIDES how `:generation` entries partition a ledger: an entry other than an intent, a baseline, a
   baseline's review or a retraction of one of these belongs to the generation the newest
   `:generation` entry at or before it opened, and a ledger holding none is wholly its first; those
   four belong to none and are in every span; which design of a generation's span governs
   its plan; which of a design's elements define its generation, read off the mark the design
   puts on them; and which of those ids a generation fixes, and from when. Every reader that must not see an earlier generation's designs reads through here
   rather than cutting the ledger itself — the append checks below it and standing, position and
   the seats above.

   Intents and baselines are what no generation owns: a re-cut changes the design, never what the
   arc is for or the status quo it was surveyed against, so a new generation re-enters at the design
   round. The mark is all this level knows of why an element matters — that changing it makes a new
   generation; how a marked element is carried is decided above."
  (Operation generation-of
    "The generation number the entry at `seq` belongs to — 1 on a ledger with no `:generation`
     entry, and for a `:generation` entry the one it opens — or nil for an intent, a baseline, a
     baseline review or a retraction of one of these."
    {:signature [:=> [:catn [:entries [:sequential :map]] [:seq :int]] [:maybe :int]]})
  (Operation current
    "The number of the current generation: 1 until a `:generation` entry opens another."
    {:signature [:=> [:catn [:entries [:sequential :map]]] :int]})
  (Operation current-span
    "The index rows of the current generation, with every entry that belongs to no generation."
    {:signature [:=> [:catn [:entries [:sequential :map]]] [:sequential :map]]})
  (Operation opened-by
    "The `:generation` row that opened the current generation, or nil for the first."
    {:signature [:=> [:catn [:entries [:sequential :map]]] [:maybe :map]]})
  (Operation current-design
    "The seq of the current span's design whose plan governs the workstream: the one its
     `:between-phases` close names, else the one the span's newest phase gate is under while no
     design follows that gate, else the span's newest design — or nil when the span holds none.
     The append checks take the current design from here, and standing gives it as the governing
     design, so the two cannot disagree."
    {:signature [:=> [:catn [:workstream :map]] [:maybe :int]]})
  (Operation closed-design
    "The seq of the design that governed the generation the `:generation` entry at `seq` closes,
     read over that generation's span as current-design reads the current one — or nil when it
     held none. The append checks take from here the one design a generation's first design may
     supersede across the boundary, so none of them cuts the ledger by generation itself."
    {:signature [:=> [:catn [:workstream :map] [:seq :int]] [:maybe :int]]})
  (Operation defining-ids
    "The ids of a design's elements marked generation-defining; empty for a design that marks
     none. The append checks compare these and the seats level reads its seats through them, so
     the two cannot disagree on what a generation fixes."
    {:signature [:=> [:catn [:design :map]] [:set :string]]})
  (Operation fixed-ids
    "The generation-defining ids the current generation fixes: those its `:generation` entry
     carries, or in a first generation those of the design marking any element that is first
     cleared or approved — nil while none is fixed. The append checks hold a generation's designs
     to these and the seats level reads its seats from them, so seats and the ids they are held to
     start from one event."
    {:signature [:=> [:catn [:workstream :map]] [:maybe [:set :string]]]}))
