(ns canvas.claims.pr-opened-per-design
  "The claims the pr-opened-per-design design commits nido to: an outstanding :pr-opened suppresses a
   newly stamped PR mark only when both carry the design the PR publishes, so a rebuild under a newer
   design is published while a stack under one design still marks its shipment once.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.reentry :refer [trail-standing]]
            [canvas.coordinator.record.workstream :refer [add-ref!]]))

(Claim one-mark-per-design-shipment
  "Across add-ref! calls on a workstream made one after another, a newly stamped :github ref that carries a :url and :title and whose published design is named appends a :pr-opened citing that design unless a :pr-opened written after the newest :merged already cites the same design (no design on both counting as the same); so add-ref! never appends a second :pr-opened for a design whose mark is outstanding. Its two skips — no :url/:title, and no nameable design on a ledger holding one — are unchanged."
  {:about [add-ref!] :evidence "round"})

(Claim rebuild-reads-published
  "A workstream whose earlier design's PR is still outstanding, redesigned and rebuilt, reads :pr-opened as current in trail-standing once add-ref! stamps the rebuild's first :github ref carrying a :url and :title and published under the new design."
  {:about [add-ref! trail-standing] :evidence "round"})

(Claim per-design-suppression-is-pinned
  "The change lands with tests in the suite bb nido:test runs that pin both sides of the suppression: a :github ref stamped through add-ref! under a design newer than the one an outstanding :pr-opened cites appends a new :pr-opened citing the newer design, and stacked layers stamped under the same design as the outstanding :pr-opened leave exactly one :pr-opened; bb nido:test is green with the change."
  {:about [add-ref!] :evidence "round"})
