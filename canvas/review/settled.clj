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

   A subject is settled when a baseline review or a design decision named its id in `:confirmed`,
   with where it was read in `:checked-at`, while judging a record whose subject with that id is
   byte-identical — beside the same entries for the elements it is about, the same other claims
   about them, and the same cited intent and baseline — at this subject's key; when
   no judgement at that same content and key has found against it since that confirmation; and
   when the record the confirming judgement judged is not retracted. A finding stands until a later
   confirmation at the same key answers it, so a record amended elsewhere in answer to a finding
   leaves the subject confirmable rather than checked for ever. The id alone is never the key: an id
   confirmed and then amended is a subject nobody has checked.

   The key is what the subject rests on. A claim or element of a model, in a project that declares
   a design, rests on the declared elements it names — each one's declaration and the code its
   module pairs with — so a change elsewhere in the tree leaves it settled. Everything else, and
   every subject in a project that declares no design, rests on the whole tree.

   One confirmation is a reading, not a settlement: a round skips a subject only on its second
   consecutive confirmation at the same content and key. A judge is not deterministic at an
   unchanged record, so the first is a sample; the two need not be in one run, and a finding
   between them ends the pair.

   Confirmations count from the workstream's own ledger, from the parent ledger its :fork entry
   cites, and from the child ledger a merged design's :merges cites.

   Nothing here is stored. What it reads are observations only a judge can make — the ids it
   checked, and the tree and element identities it read — and they are on the judgement. So a
   ledger that cannot be read, or a reading that yields no identity, settles nothing."
  (Operation code-identity
    "A hash of the tree at `cwd` — in a jj repository the tree jj lists, every path with the content
     id of what it holds; in a plain git repository the tree git would commit from the working copy,
     ignored files left out — or nil when neither can be read in full. Moves when any content, path,
     symlink or executable bit in the tree moves; a description edit, or a rebase that leaves the
     tree as it was, does not move it. Read as a judge launches and again as it returns, and the
     tree is not frozen between the two readings."
    {:signature [:=> [:catn [:cwd Path]] [:maybe :string]]})
  (Operation subject-identities
    "Each declared element's identity at a worktree, by id, from the element listing: its
     declaration digests and the content of the file its module pairs with — for a stratum, of the
     files of the modules providing it. Nil when the listing is not listed — a project that declares
     no design has no element to identify."
    {:signature [:=> [:catn [:listing DeclaredElements] [:worktree Path]]
                 [:maybe [:map-of :string :string]]]})
  (Operation subjects
    "Every subject a baseline or design carries, by id — claims, modules, health observations, or
     in the shared model its claims and elements, and the whole-record fields named by their field
     names. Pure."
    {:signature [:=> [:catn [:record :map]] :map]})
  (Operation nothing-to-check?
    "Whether a subject carries nothing a judge could check — every part of it an element stating no
     more than its id and sort — and so is never owed a ruling. Pure."
    {:signature [:=> [:catn [:content [:vector :any]]] :boolean]})
  (Operation rested-on
    "Every declared element some subject of a record rests on, a role's players read from the
     effective record — the only element identities a judgement over that record keeps. Pure."
    {:signature [:=> [:catn [:record :map] [:effective :map]] [:set :string]]
     :delegates [subjects]})
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
     record when one is given — a design's model laid over its baseline's — and the record's when not.
     Names every subject whose latest judgement at the key confirmed it; those on one reading are
     `single-readings`, and a round still asks them."
    {:signature [:function
                 [:=> [:catn [:ledgers [:maybe [:vector :map]]] [:record :map] [:reading :map]] :map]
                 [:=> [:catn [:ledgers [:maybe [:vector :map]]] [:record :map] [:reading :map]
                       [:effective :map]] :map]]
     :delegates [subjects]})
  (Operation single-readings
    "The subjects `settled` names on one reading: the confirmation standing at the key is not
     itself preceded there by another. Still put to the judge; the next confirmation settles them.
     Pure."
    {:signature [:function
                 [:=> [:catn [:ledgers [:maybe [:vector :map]]] [:record :map] [:reading :map]]
                  [:set :string]]
                 [:=> [:catn [:ledgers [:maybe [:vector :map]]] [:record :map] [:reading :map]
                       [:effective :map]] [:set :string]]]
     :delegates [subjects]})
  (Operation checked-confirmations
    "The ids a judgement confirmed and said where it read — the only confirmations that count.
     Pure."
    {:signature [:=> [:catn [:judgement :map]] [:set :string]]})
  (Operation prior-findings
    "For each subject of a record, the newest finding against its id by a judgement of another run,
     at any content or key, with the judgement that made it and whether the subject was restated
     since — what confirming it now would overturn. Pure."
    {:signature [:=> [:catn [:ledgers [:maybe [:vector :map]]] [:record :map]
                  [:run-id [:maybe :string]]] :map]
     :delegates [subjects]}))
