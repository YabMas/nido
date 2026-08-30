(ns canvas.coordinator.record.vocabulary
  "The identifiers the record layer is addressed by.

   Kinds with NO owning Module, deliberately. A Kind belongs to at most one Module — the module
   that HIDES its shape — and no module hides these: a workstream id is minted by the workstream
   record and then spoken by the paths that store it, the runs that cite it, the tickets that
   carry it and every surface that renders it. Owning it somewhere would make one of those the
   owner and the rest adopters, which is a claim about the code that is not true.

   They earn their place by appearing in signatures. `[:=> [:catn [:project ProjectName]
   [:ws-id WorkstreamId]] Path]` says what a path function is for; `[:cat :keyword :string]`
   says it takes a keyword and a string, which every function in the file also does."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.typing.malli]))

(Kind ProjectName
  "A registered project, by the name it was registered under. A keyword everywhere it is
   addressed and a string on disk, which is why the seam that resolves it takes either."
  :keyword)

(Kind WorkstreamId
  "A workstream's identity — `ws-<date>-<hash>`, minted once and never derived from anything
   that can change."
  :string)

(Kind RunId
  "One execution of a trigger against a workstream. The directory under `runs/` is named by it."
  :string)

(Kind SessionName
  "A work-episode's name within its workstream, unique per workstream rather than globally."
  :string)

(Kind Path
  "An absolute filesystem path the coordinator owns. Every one of them is derived from the nido
   home, never configured independently — which is what makes a whole coordinator relocatable by
   moving one root."
  :string)
