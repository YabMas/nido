(ns canvas.coordinator.record.vocabulary
  "The identifiers the record layer is addressed by.

   Kinds with NO owning Module — and unlike `Path` and `ProjectName`, which do have one, that is
   a report rather than a preference.

   Each of these IS minted by exactly one module, which hides its format: `mint-id` is the only
   place a `ws-` id is constructed anywhere in nido. By Parnas that module owns it. But owning it
   makes the module graph CYCLE, and the cycle is measured, not feared — `record-state`'s path
   functions take these ids, so ownership adds `state ⟶ workstream`, while `workstream ⟶ state`
   already exists because workstream calls those path functions. Same for run and session.

   The cycle is real, and it is a finding about `record-state`: twelve of its twenty-eight
   functions are one record's layout rather than the coordinator's roots, so it holds knowledge
   the records could hold themselves. Until that is settled these stay unowned, which under-states
   the design rather than asserting a cycle that would be true only because we declared it.

   They earn their place by appearing in signatures. `[:=> [:catn [:project ProjectName]
   [:ws-id WorkstreamId]] Path]` says what a path function is for; `[:cat :keyword :string]`
   says it takes a keyword and a string, which every function in the file also does."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind WorkstreamId
  "A workstream's identity — `ws-<date>-<hash>`, minted once by `record-workstream/mint-id` and
   never derived from anything that can change."
  :string)

(Kind RunId
  "One execution of a trigger against a workstream. The directory under `runs/` is named by it."
  :string)

(Kind SessionName
  "A work-episode's name within its workstream, unique per workstream rather than globally."
  :string)
