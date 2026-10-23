(ns canvas.claims.declared-breaks
  "The claims the declared-breaks design commits nido to: what a design says it breaks is not
   carried as true by any model derived from it.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.fork :refer [record-fork fork!]]
            [canvas.coordinator.record.workstream :refer [append-entry!]]
            [canvas.coordinator.report.model :refer [effective unrestated-elements]]
            [canvas.review.merge :refer [review-merge]]
            [canvas.review.record :refer [review-record]]))

(Claim one-effective-model
  "A design's effective model is report-model/effective over the baseline it cites: the overlay, with every baseline claim its :baseline :breaks names — read through report/break-id — and its model does not state taken out, and nothing else changed; with nothing declared broken it is the overlay exactly. The design round's subject resolution, a fork's derived child baseline, the merge proposal's three models and the ledger's merged-design check all derive it that way and no other."
  {:about [effective record-fork review-merge append-entry! review-record] :evidence "test"})

(Claim broken-elements-are-restated
  "No derived model describes an element its design declared broken as the baseline described it, save one: the base of a merge whose child was forked before this held, since the forked design under it is immutable and is not refused. The ledger refuses a design listing under :breaks an element of its cited baseline that its overlay leaves exactly as the baseline describes it; fork! refuses such a parent design before the child is minted, and the merge proposal refuses a parent's or child's current design of that shape, each naming the entry and the element ids."
  {:about [append-entry! fork! review-merge unrestated-elements] :evidence "test"})
