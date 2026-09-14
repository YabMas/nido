(ns canvas.review.settled
  "Self-spec: `nido.review.settled` — which subjects of a record a round need not ask its judge
   to check, and what they were checked against."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.fork :as fork]
            [canvas.coordinator.record.state :refer [Path WorkstreamId]]
            [canvas.design.check :refer [DeclaredElements]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module review-settled
  "What a round already knows about a subject, keyed by its CONTENT and what it was judged against
   — the record-side counterpart of `review-cache`, derived rather than stored.

   A subject is settled when a baseline review or a design decision named its id in `:confirmed`
   while judging a record whose subject with that id is byte-identical, at this subject's key; when
   no judgement at that same content and key, before or since, found against it; and when the
   record the confirming judgement judged is not retracted. The id alone is never the key: an id
   confirmed and then amended is a subject nobody has checked.

   The key is what the subject rests on. A claim or element of a model, in a project that declares
   a design, rests on the declared elements it names — each one's declaration and the code its
   module pairs with — so a change elsewhere in the tree leaves it settled. Everything else, and
   every subject in a project that declares no design, rests on the whole tree.

   Confirmations count from the workstream's own ledger, from the parent ledger its :fork entry
   cites, and from the child ledger a merged design's :merges cites.

   Nothing here is stored. What it reads are observations only a judge can make — the ids it
   checked, and the tree and element identities it read — and they are on the judgement. So a
   ledger that cannot be read, or a reading that yields no identity, settles nothing."
  (Operation code-identity
    "A hash of the tree jj lists at `cwd` — every path with the content id of what it holds — or
     nil when the listing cannot be read in full. Moves when any content, path, symlink or
     executable bit in the tree moves; a description edit, or a rebase that leaves the tree as it
     was, does not move it. Read as a judge launches and again as it returns, and the tree is not
     frozen between the two readings."
    {:signature [:=> [:catn [:cwd Path]] [:maybe :string]]})
  (Operation subject-identities
    "Each declared element's identity at a worktree, by id, from the element listing: its
     declaration digests and the content of the file its module pairs with. Nil when the listing
     is not listed — a project that declares no design has no element to identify."
    {:signature [:=> [:catn [:listing DeclaredElements] [:worktree Path]]
                 [:maybe [:map-of :string :string]]]})
  (Operation subjects
    "Every subject a baseline or design carries, by id — claims, modules, health observations, or
     in the shared model its claims and elements, and the whole-record fields named by their field
     names. Pure."
    {:signature [:=> [:catn [:record :map]] :map]})
  (Operation ledger
    "One workstream's reviews and decisions, the baselines and designs they judged, and its
     retractions, as `settled` reads them — or nil when any entry of those kinds cannot be read, since
     the judgement or retraction nobody can parse could be the one that unsettles a subject."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]})
  (Operation ledgers
    "Every ledger a round over a record may find its subjects settled on — its own workstream's,
     its fork parent's, and the child a merged design cites — or nil when any of them cannot be
     read."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:record :map]]
                 [:maybe [:vector :map]]]
     :delegates [ledger fork/lineage]})
  (Operation settled
    "The subjects of a record settled at a reading — the code identity and the element identities a
     judge is about to read — each with the workstream and seq of the judgement that settled it.
     Pure: the ledgers are handed in, and nil ledgers or a reading with no identity settle nothing.
     Only the record's own subjects are candidates; a role's players are read from the effective
     record when one is given — a design's model laid over its baseline's — and the record's when not."
    {:signature [:function
                 [:=> [:catn [:ledgers [:maybe [:vector :map]]] [:record :map] [:reading :map]] :map]
                 [:=> [:catn [:ledgers [:maybe [:vector :map]]] [:record :map] [:reading :map]
                       [:effective :map]] :map]]
     :delegates [subjects]}))
