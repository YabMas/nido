;; src/nido/review/cache.clj
(ns nido.review.cache
  "What the review already knows, keyed by content.

   A layer that was reviewed and needed no fix is `converged`. The mark is NOT
   granted by an agent and cannot be revoked by one: it is the hash of the
   layer's patch, recorded next to the workstream. A later run recomputes the
   hash and either finds it — in which case that layer is genuinely unchanged
   and skipping it is safe — or does not, in which case the layer is reviewed
   again. Nothing decides; it falls out.

   An entry carries TWO things and only one of them is the skip. `:status`
   decides whether the patch is reviewed again, and `:converged` is the single
   value that says no; `:answered` is what was decided about the patch, and it
   is worth recording whatever the status. A store that held only the first was
   a store of skips: every entry in it was by construction a patch nothing would
   look at again, so nothing ever read the second. An entry that still owes
   something is the one whose answers a next round actually needs — it is the
   entry that gets reviewed.

   Keying on the PATCH is what makes this survive the trip to merge. Commit ids
   die at `/align`'s rebase and change ids die at `/squash`'s fold, but the patch
   a layer contributes is identical on the other side of both: a clean rebase
   preserves it, and folding N commits into one produces exactly the diff the
   range already had.

   `The patch` means what `layers/contribution` leaves once the blob ids and
   hunk offsets are gone. Those answer where a range sits rather than what it
   changes, and they move under a fix landing on a layer below — so keyed on
   jj's raw output this claim holds for `/align` and not for the in-loop rebase
   it is relied on for.

   Keyed by hash and never by slug: a restack renames and reorders slugs, so a
   slug reused for different content would silently skip a changed layer — the
   one failure this must never have. The store only ever grows, so a patch that
   comes back (an align that was reverted, a layer spun out and re-landed) is
   still known.

   Over-invalidating costs one review. Under-invalidating ships unreviewed code.
   Everything here leans the first way."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [nido.coordinator.record.state :as cstate]))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :Path]}
  path
  [project ws-id]
  (str (fs/path (cstate/workstream-dir project ws-id) "review-cache.edn")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  read-cache
  "The workstream's cache, or {} — for a workstream that has none, and for one
   whose file is unreadable or corrupt. A cache that cannot be read must degrade
   to reviewing everything, never to skipping."
  [project ws-id]
  (try
    (let [f (path project ws-id)]
      (if (fs/exists? f)
        (or (edn/read-string (slurp f)) {})
        {}))
    (catch Throwable _ {})))

(defn ^{:malli/schema [:=> [:cat :map :string] :boolean]}
  converged?
  "Has this exact patch already been reviewed to convergence?

   The only question that grants a skip, so it is asked of `:status` exactly:
   any other value — a patch reviewed and still owing something, an entry from a
   writer that did not say — means review it."
  [cache patch-hash]
  (= :converged (:status (get cache patch-hash))))

(defn ^{:malli/schema [:=> [:cat :map :string] :any]}
  answered
  "Findings already SETTLED against this exact patch, as
   [{:id … :because …}]. Fed back to a later run's warden so a fresh reviewer
   reporting the same thing gets answered rather than re-adjudicated — the same
   job `:rejected` does for the design one altitude up.

   They hang off the patch hash, so they evaporate the moment the layer's
   content changes. That is deliberate: they were answers about THAT content.

   Which is also the limit of what this can carry, and why it is half of the
   channel rather than all of it. A run that repairs a layer moves that layer's
   patch, so its own earlier rulings are unreachable here from the round after
   the fix — WITHIN a run the answers travel by label instead, out of the round
   history; see `nido.review.stages/answered-by-layer`. What this half is for is
   the gap BETWEEN runs, where no history survives and the patch is the only
   thing that does.

   Asked only of a patch that is under review, which is the complement of what
   `converged?` skips — so what this reads in practice is the entries that still
   owe something. The answers on a converged entry are the record rather than an
   input: nothing re-reads a patch it has decided not to look at."
  [cache patch-hash]
  (vec (:answered (get cache patch-hash))))

(defn ^{:malli/schema [:=> [:cat :map :string :map] :map]}
  record
  "Pure: the cache with `patch-hash` marked as `entry` says. Never removes an
   entry — the store is append-only, so re-encountering a patch is a hit rather
   than a rebuild.

   `entry` supplies its own `:status`, and one that omits it is a patch nothing
   will skip. There was a `:converged` default here, from when convergence was
   the only thing recorded; with two statuses to write, defaulting to the one
   that grants a skip means a caller that forgets ships unreviewed code — the
   single failure this namespace leans away from."
  [cache patch-hash entry]
  (assoc cache patch-hash entry))

(defn ^{:malli/schema [:=> [:cat :map :string :string] :map]}
  reopen
  "Pure: the cache with `patch-hash`'s convergence revoked — `:partial`, stamped
   at `at`, keeping everything else the entry holds.

   Convergence is a claim that nothing is owed of this exact content, and this is
   the one thing that can falsify it without the content moving: a run learning
   that something IS owed of a patch it was about to skip. `record` cannot
   express that, because a skipped target has no review to write an entry from —
   the patch is unchanged, so what wants correcting is the status alone.

   `:answered` survives the downgrade. Those decisions are about this content and
   stay true of it, and a reopen that rewrote the entry would make the next round
   re-argue everything an earlier one settled.

   Only a converged entry is touched. A `:partial` one already owes something,
   and an absent one is a patch this store has never seen — writing a status for
   it would claim a review that nobody ran."
  [cache patch-hash at]
  (cond-> cache
    (converged? cache patch-hash)
    (update patch-hash assoc :status :partial :at at)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :any]}
  write!
  "Persist the cache. Best-effort: a cache that cannot be written costs the next
   run some duplicated review, which is never a reason to fail a finished one."
  [project ws-id cache]
  (try
    (let [f (path project ws-id)]
      (fs/create-dirs (fs/parent f))
      (spit f (pr-str cache))
      true)
    (catch Throwable _ false)))
