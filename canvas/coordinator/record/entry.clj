(ns canvas.coordinator.record.entry
  "Self-spec: `nido.coordinator.record.entry` — reading a ledger entry's content from the core."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.core.client :as client]
            [canvas.coordinator.report :as report]
            [fukan.common.typing.malli]))

(Module record-entry
  "An entry's content, asked of the core by seq.

   HIDES the cache: an entry never changes once appended, so its content is kept by home,
   workstream and seq for as long as the process runs, and a miss fetches every entry of that
   ledger not yet read in one request."
  (Operation text
    "The content of an entry of a workstream record, as written, or nil."
    {:signature [:=> [:catn [:w :any] [:seq-n :int]] [:maybe :string]]
     :delegates [client/entry-contents]})
  (Operation prime!
    "Keep content a bulk read fetched as what `text` answers for those entries, so a reader folding
     many ledgers asks the core for them once."
    {:signature [:=> [:catn [:w :any] [:contents [:map-of :int :string]]] :nil]})
  (Operation parsed
    "An entry parsed through the READ contract and stamped with its :seq and :at, or nil when it
     no longer parses."
    {:signature [:=> [:catn [:w :any] [:seq-n :int]] [:maybe :map]]
     :delegates [text report/parse-event]}))
