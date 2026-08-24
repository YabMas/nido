;; src/nido/review/cache.clj
(ns nido.review.cache
  "What the review already knows, keyed by content.

   A layer that was reviewed and needed no fix is `converged`. The mark is NOT
   granted by an agent and cannot be revoked by one: it is the hash of the
   layer's patch, recorded next to the workstream. A later run recomputes the
   hash and either finds it — in which case that layer is genuinely unchanged
   and skipping it is safe — or does not, in which case the layer is reviewed
   again. Nothing decides; it falls out.

   Keying on the PATCH is what makes this survive the trip to merge. Commit ids
   die at `/align`'s rebase and change ids die at `/squash`'s fold, but the patch
   a layer contributes is identical on the other side of both: a clean rebase
   preserves it, and folding N commits into one produces exactly the diff the
   range already had. Verified against jj 0.42.

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
   [nido.coordinator.state :as cstate]))

(defn path
  [project ws-id]
  (str (fs/path (cstate/workstream-dir project ws-id) "review-cache.edn")))

(defn read-cache
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

(defn converged?
  "Has this exact patch already been reviewed to convergence?"
  [cache patch-hash]
  (= :converged (:status (get cache patch-hash))))

(defn answered
  "Findings already closed against this exact patch, as
   [{:id … :because …}]. Fed back to the next round's arbiter so a fresh
   reviewer reporting the same thing gets answered rather than re-adjudicated —
   the same job `:rejected` does for the design one altitude up.

   They hang off the patch hash, so they evaporate the moment the layer's
   content changes. That is deliberate: they were answers about THAT content."
  [cache patch-hash]
  (vec (:answered (get cache patch-hash))))

(defn record
  "Pure: the cache with `patch-hash` marked. Never removes an entry — the store
   is append-only, so re-encountering a patch is a hit rather than a rebuild."
  [cache patch-hash entry]
  (assoc cache patch-hash (merge {:status :converged} entry)))

(defn write!
  "Persist the cache. Best-effort: a cache that cannot be written costs the next
   run some duplicated review, which is never a reason to fail a finished one."
  [project ws-id cache]
  (try
    (let [f (path project ws-id)]
      (fs/create-dirs (fs/parent f))
      (spit f (pr-str cache))
      true)
    (catch Throwable _ false)))
