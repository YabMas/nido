(ns canvas.review.settled
  "Self-spec: `nido.review.settled` — which subjects of a record a round need not ask its judge
   to check, and the identity of the code they were checked against."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module review-settled
  "What a baseline round already knows about a subject, keyed by its CONTENT and the CODE it was
   judged against — the record-side counterpart of `review-cache`, derived rather than stored.

   A subject is settled when a review named its id in `:confirmed` while judging a baseline whose
   subject with that id is byte-identical, at the code identity this round's judge will read; when
   no review at that same content and identity, before or since, found against it, whatever
   baseline it judged; and when the baseline the confirming review judged is not retracted. The
   key is the tuple, never the id: an id confirmed and then amended is a subject nobody has
   checked.

   Nothing here is stored. The two facts it reads are observations only a judge can make — the ids
   it checked and the tree it read — and both are on the review. So a ledger that cannot be read,
   or a tree that yields no identity, settles nothing, and everything is checked.

   The code identity is the WHOLE working tree, which over-invalidates: any change anywhere
   unsettles every subject. The finer key is the set of modelled elements a claim is about, which
   claims do not yet name."
  (Operation code-identity
    "A hash of the tree jj lists at `cwd` — every path with the content id of what it holds — or
     nil when the listing cannot be read in full. Moves when any content, path, symlink or
     executable bit in the tree moves; a description edit, or a rebase that leaves the tree as it
     was, does not move it. Read as a judge launches and again as it returns, and the tree is not
     frozen between the two readings."
    {:signature [:=> [:catn [:cwd Path]] [:maybe :string]]})
  (Operation subjects
    "Every subject a baseline carries, by id — claims, modules, health observations, and the
     whole-record fields named by their field names. Pure."
    {:signature [:=> [:catn [:record :map]] :map]})
  (Operation ledger
    "The reviews, baselines and retractions of a workstream as `settled` reads them, or nil when
     any entry of those kinds cannot be read — a review or a retraction nobody can parse could be
     the one that unsettles a subject, so an unreadable ledger settles nothing."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]})
  (Operation settled
    "The subjects of `record` settled at `code-identity`, each with the :seq of the review that
     settled it. Pure: the reviews, baselines and retractions are handed in, and a nil ledger or
     identity settles nothing."
    {:signature [:=> [:catn [:ledger [:maybe :map]] [:record :map] [:code-identity [:maybe :string]]] :map]
     :delegates [subjects]}))
