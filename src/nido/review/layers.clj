;; src/nido/review/layers.clj
(ns nido.review.layers
  "The session's stack, read from jj.

   A layer is one PR in a `/stack`: a `<session>--<slug>` bookmark on a commit
   in `<base>..@`, owning every commit between the bookmark below it and its own
   tip. An unstacked branch carries one layer (the bare `<session>` bookmark) or
   none at all, and every fn here degrades to both.

   Layers are read off the `<base>..@` log rather than off `jj bookmark list` —
   this repo holds a dozen live workspaces and `bookmark list` spans all of
   them, while only this stack's commits are in this range. The session prefix
   is still required on top of that: a bookmark belonging to a session that
   branched from ours can sit inside our history, and mistaking one for a layer
   tip would move someone else's bookmark onto our fix.

   Note `<base>..@` is a REVSET, not a diff, so it stays correct when base's tip
   has moved on: it is the ancestors of @ that are not ancestors of base. The
   merge-base subtlety that `nido.review.codex` documents applies to diffing,
   not to enumerating."
  (:require
   [clojure.string :as str]
   [nido.review.digest :as digest]
   [nido.vsdd.jj :as jj]))

(def ^:private row-template
  "One tab-separated row per commit. Descriptions are deliberately NOT in here:
   they are multi-line, which would need a record separator and a parser. The
   few layer tips that need their brief fetch it by bookmark (`description`).

   Bookmarks go through `.name()` rather than being interpolated bare. A bare
   `local_bookmarks` renders jj's DISPLAY form of each ref, which decorates the
   name with sync markers — `*` when the local bookmark has diverged from a
   tracked remote, `??` when it is conflicted. That marker is not part of the
   name, and a layer read with one on it is unusable as a revision: the moment
   this loop lands a fix on a pushed layer, the layer diverges from its remote
   and every later round would ask jj for `<layer>*`, which does not exist."
  "commit_id ++ \"\\t\" ++ change_id ++ \"\\t\" ++ local_bookmarks.map(|b| b.name()).join(\" \") ++ \"\\n\"")

(defn- parse-row
  [line]
  (let [[commit change bookmarks] (str/split line #"\t" -1)]
    {:commit    commit
     :change    change
     :bookmarks (remove str/blank? (str/split (or bookmarks "") #"\s+"))}))

(defn- layer-bookmark
  "The bookmark naming a layer of THIS session, or nil. `<session>--<slug>` is a
   stack layer; the bare `<session>` is the single implicit layer of an
   unstacked branch. Anything else on the commit belongs to someone else."
  [session names]
  (let [prefix (str session "--")]
    (or (first (filter #(str/starts-with? % prefix) names))
        (first (filter #(= session %) names)))))

(defn- slug-of
  "The layer's slug, or nil for the bare session bookmark (which names the whole
   branch rather than a layer within it)."
  [session bookmark]
  (let [prefix (str session "--")]
    (when (str/starts-with? bookmark prefix)
      (subs bookmark (count prefix)))))

(defn ^{:malli/schema [:=> [:cat :Path :any :any] :any]}
  stack
  "This session's layers, ordered bottom→top:
     [{:bookmark :slug :tip <commit-id> :change <change-id>} …]

   Empty when the branch carries no session bookmark at all — which is a normal
   state (work in progress before `/stack` has cut anything), not an error."
  [cwd session base]
  (let [{:keys [exit out]} (jj/jj! cwd "log" "-r" (str base "..@")
                                   "--reversed" "--no-graph" "-T" row-template)]
    (if-not (zero? exit)
      []
      (into []
            (keep (fn [line]
                    (let [{:keys [commit change bookmarks]} (parse-row line)]
                      (when-let [bm (layer-bookmark session bookmarks)]
                        {:bookmark bm
                         :slug     (slug-of session bm)
                         :tip      commit
                         :change   change}))))
            (remove str/blank? (str/split-lines out))))))

;; ---- what each layer contributes ----------------------------------------

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  ranges
  "Pair each layer with the revision range it contributes:
     [{… :from <rev> :to <commit-id>} …]   bottom→top

   A layer's range runs from the tip of the layer beneath it to its own tip —
   the same `<lower>..<this-bookmark>` range `/squash` folds, which is why a
   verdict about this range survives the fold.

   The bottom layer's `from` is `base-rev`, which must be the FORK POINT and not
   the tip of base (see `nido.review.codex/merge-base`): diffing from a base
   that has moved on turns everything base gained into spurious deletions."
  [stack base-rev]
  (into []
        (map-indexed (fn [i layer]
                       (assoc layer
                              :from (if (zero? i) base-rev (:tip (nth stack (dec i))))
                              :to   (:tip layer))))
        stack))

(def ^:private index-line
  "git's `index <blob>..<blob> <mode>` header. Each blob id names a whole file
   on one side of the diff, so both move whenever anything at all changes that
   file — a commit BELOW this range included."
  #"(?m)^index [0-9a-f]+\.\.[0-9a-f]+.*\R")

(def ^:private hunk-offsets
  "The line numbers in a `@@ -a,b +c,d @@` header. Only the header itself is
   matched, so any function context git appends after it survives."
  #"(?m)^@@ -\d+(?:,\d+)? \+\d+(?:,\d+)? @@")

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  contribution
  "A git-format diff with the two fields that say where it SITS removed: the
   blob ids and the hunk offsets.

   Neither is part of what a range contributes, and both move under a commit
   below it. Measured on jj 0.45, a one-line edit near the bottom of a file came
   back with new blob ids and `@@ -15` → `@@ -17` once a fix two commits down had
   inserted two lines near the top — every line this range adds or removes
   byte-identical either side. Leave them in and a layer nobody edited takes a
   fresh identity every time anything lands beneath it, so the review cache
   never hits on a stack whose layers share a file.

   Paths, modes and every body line survive. Two ranges therefore collide only
   by making the same edit to the same files in the same surrounding context —
   this is `git patch-id`'s normalisation, which is how git itself recognises a
   patch across a rebase."
  [git-diff]
  (-> (str git-diff)
      (str/replace index-line "")
      (str/replace hunk-offsets "@@")))

(defn ^{:malli/schema [:=> [:cat :Path :any :any] :string]}
  patch-hash
  "The identity of what a range CONTRIBUTES, as against where it sits.

   Hashes the range's `contribution`, so it is stable across everything that
   rewrites commits without changing what this range adds: a clean rebase, a
   fold of the range into one commit, and a fix landing on a layer below it.
   nil when jj cannot produce the diff — the caller must then review rather than
   assume anything about it."
  [cwd from to]
  (let [{:keys [exit out]} (jj/jj! cwd "diff" "--git" "--from" from "--to" (or to "@"))]
    (when (zero? exit)
      (digest/sha256-hex (contribution out)))))

(defn ^{:malli/schema [:=> [:cat :Path :any] :string]}
  description
  "A revision's full commit message, or nil."
  [cwd rev]
  (let [{:keys [exit out]} (jj/jj! cwd "log" "-r" rev "--no-graph" "-T" "description")]
    (when (zero? exit) out)))

(def ^:private brief-fields
  {"Layer" :mode "Claims" :claims "Verify" :verify
   "Lane" :lane "Out of scope" :out-of-scope})

(def ^:private field-re #"^(Layer|Claims|Verify|Lane|Out of scope):\s*(.*)$")
(def ^:private continuation-re #"^\s+\S.*$")

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  parse-brief
  "A layer commit message → its `/stack` §5 review brief:
     {:mode :claims :verify :lane :out-of-scope :subject :raw}

   Fields the message doesn't carry come back nil. That is the normal case for
   an ordinary commit written before the stack doctrine, and for a fixup commit
   sitting on a layer — neither is an error, so this never throws. A field's
   value continues onto following indented lines, which is how the four brief
   fields are written."
  [description]
  (when-not (str/blank? description)
    (let [lines  (str/split-lines description)
          parsed (->> lines
                      (reduce
                       (fn [{:keys [current] :as st} line]
                         (if-let [[_ k v] (re-matches field-re line)]
                           (let [field (get brief-fields k)]
                             (-> st (assoc :current field)
                                 (assoc-in [:out field] [v])))
                           (if (and current (re-matches continuation-re line))
                             (update-in st [:out current] conj (str/trim line))
                             (assoc st :current nil))))
                       {:current nil :out {}})
                      :out)
          joined (into {} (map (fn [[k parts]]
                                 [k (str/trim (str/join " " (remove str/blank? parts)))]))
                       parsed)]
      (cond-> (assoc joined
                     :subject (first (remove str/blank? lines))
                     :raw     (str/trim description))
        (seq (:mode joined)) (update :mode #(keyword (str/lower-case (str/trim %))))))))

(defn ^{:malli/schema [:=> [:cat :Path :any] :map]}
  brief
  "The review brief of the layer whose tip is `rev`, or nil."
  [cwd rev]
  (parse-brief (description cwd rev)))

;; ---- landing a fix on a layer -------------------------------------------

(defn ^{:malli/schema [:=> [:cat :Path :map] :any]}
  position-for-fix!
  "Put the working copy on `layer` so a fixer's edits land there rather than on
   top of the whole stack. Layers above rebase onto the new commit
   automatically. A nil layer (unstacked branch) means the working copy is
   already the only place a fix can go, so this is a no-op.

   `--insert-after` is required, not a stylistic choice: a bare
   `jj new <layer-tip>` creates a SIBLING of the layers above rather than a link
   in the chain, which silently forks the stack — `<base>..@` then stops
   containing the upper layers and `/squash` folds the wrong set.

   Positions by BOOKMARK, not by the tip commit id read when the stack was
   enumerated. Landing a fix on a lower layer rewrites every layer above it, so
   those tips are stale by the time a second fix runs in the same round; the
   bookmark still names the right commit because jj carries it along."
  [cwd layer]
  (when layer
    (let [{:keys [exit err]} (jj/jj! cwd "new" "--insert-after"
                                     (or (:bookmark layer) (:tip layer)))]
      (when-not (zero? exit)
        (throw (ex-info (str "could not position the working copy on layer "
                             (:bookmark layer) " — " err)
                        {:reason :review-failed :cwd cwd :layer layer}))))))

(defn ^{:malli/schema [:=> [:cat :Path :map :string] :any]}
  land-fix!
  "Turn the working copy into the layer's fix commit and move the layer's
   bookmark onto it. Returns the fix's commit id.

   **The bookmark move is not optional.** jj moves a bookmark when the commit it
   points at is *rewritten*, not when a child is added — so after
   `position-for-fix!` the layer bookmark still points at the old tip. Skip the
   move and the push publishes nothing for that layer: the reviewer sees an
   unchanged PR and the fix rides up into the layer above instead.

   `jj describe` rather than `jj commit`, for the same reason `position-for-fix!`
   uses `--insert-after`: `commit` creates an empty child, and mid-stack that
   child is a SECOND child, which forks the stack.

   With no layer this degrades to the flat-branch behaviour it replaces."
  [cwd layer msg]
  (if layer
    (do (jj/jj! cwd "describe" "-m" msg)
        (jj/jj! cwd "bookmark" "set" (:bookmark layer) "-r" "@")
        (:out (jj/jj! cwd "log" "-r" "@" "-T" "commit_id" "--no-graph")))
    (do (jj/jj! cwd "commit" "-m" msg)
        (:out (jj/jj! cwd "log" "-r" "@-" "-T" "commit_id" "--no-graph")))))

(defn ^{:malli/schema [:=> [:cat :Path :any] :any]}
  restore-top!
  "Return the working copy to a fresh empty commit on top of the stack, so
   whatever runs next sees the whole stack in `<base>..@` again.

   Restoring by bookmark rather than by remembering the previous `@`: jj
   abandons an empty, description-less, bookmark-less commit as soon as the
   working copy moves off it, so the `@` that was there before the insert is
   usually gone by now. Trying to `jj edit` it back fails with `Revision doesn't
   exist` and leaves the working copy stranded mid-stack.

   Also cleans up after a fixer that changed nothing: the empty commit the
   insert created is abandoned by the same rule when we move away from it.

   **Fails loud.** Swallowing jj's exit code here does not degrade the run, it
   corrupts the next one: the working copy stays parked mid-stack, so every
   later `<base>..@` read sees a TRUNCATED stack and silently reviews fewer
   layers than the branch has. A crash is recoverable; a review that quietly
   skipped half the stack while reporting success is not."
  [cwd stack]
  (when-let [top (last stack)]
    (let [{:keys [exit err]} (jj/jj! cwd "new" (:bookmark top))]
      (when-not (zero? exit)
        (throw (ex-info (str "could not return the working copy to the top of the stack ("
                             (:bookmark top) ") — " err
                             "\nThe working copy is parked mid-stack: move it back with"
                             " `jj new " (:bookmark top) "` before reviewing again,"
                             " or the next run will see a truncated stack.")
                        {:reason :review-failed :cwd cwd :layer top}))))))

;; ── Reshaping the stack ─────────────────────────────────────────────────────
;;
;; Two operations, and one rule that makes them safe to attempt: an attempt that
;; does not come out clean leaves the stack exactly as it was. jj supplies both
;; halves — the operation log is an exact undo, and whether a reorder was legal
;; is a question it answers rather than one anybody has to judge.

(defn ^{:malli/schema [:=> [:cat :Path] :string]}
  current-op
  "The id of jj's latest operation — the point a reshape can be rolled back to."
  [cwd]
  (let [{:keys [exit out]} (jj/jj! cwd "op" "log" "--no-graph" "-T" "id.short()"
                                   "--limit" "1")]
    (when (and (zero? exit) (not (str/blank? out)))
      (str/trim (first (str/split-lines out))))))

(defn ^{:malli/schema [:=> [:cat :Path :any] :any]}
  conflicted
  "Change ids in this stack that jj left conflicted, or [].

   The `conflicts()` revset, and NOT `jj resolve --list`. That command inspects
   one revision — the working copy by default — and an illegal reorder puts its
   conflict on a rewritten commit MID-STACK, so it answers `No conflicts found`
   for the legal and the illegal case alike. Measured on jj 0.42 against two
   probe stacks: identical output, identical exit code, opposite truth.

   Scoped to `<base>..@` because the revset is repo-wide, and this repo holds a
   dozen workspaces whose conflicts are not ours to read."
  [cwd base]
  (let [{:keys [exit out]} (jj/jj! cwd "log" "-r" (str "conflicts() & (" base "..@)")
                                   "--no-graph" "-T" "change_id.short() ++ \"\\n\"")]
    (if (zero? exit)
      (vec (remove str/blank? (str/split-lines out)))
      [])))

(defn ^{:malli/schema [:=> [:cat :Path :string] :any]}
  restore-op!
  "Put the repo back as it was at `op`. Best-effort: this runs on the failure
   path, and a restore that also fails must not replace the diagnosis."
  [cwd op]
  (when op
    (try (jj/jj! cwd "op" "restore" op) (catch Throwable _ nil))))

(defn ^{:malli/schema [:=> [:cat :Path :any :any] :map]}
  attempt-reshape!
  "Run `f`, and keep what it did only if the stack came out clean.

   Returns {:ok? true} or {:ok? false :reason \"…\"}, never throws for a reshape
   that simply would not apply — an attempt is meant to be cheap enough to make
   on a maybe, and its failure is information rather than an error. What makes
   that true is the rollback: whatever f did is undone by operation id, so a
   refused attempt costs nothing but the seconds it took.

   The conflict check is the point. A reorder jj can replay cleanly is one the
   layers did not actually depend on each other for; a conflict is jj saying the
   dependency is real and the original order was right. That is a mechanical
   answer to a question that would otherwise be a judgement call, which is the
   whole reason this can run without asking anyone."
  [cwd base f]
  (let [op (current-op cwd)
        {:keys [exit err]} (f)]
    (cond
      (not (zero? exit))
      (do (restore-op! cwd op)
          {:ok? false :reason (str "jj refused it: " (first (str/split-lines (str err))))})

      (seq (conflicted cwd base))
      (do (restore-op! cwd op)
          {:ok? false :reason (str "it conflicts: the layers depend on each other, "
                                   "so the order they are in is the order they need")})

      :else {:ok? true})))

(defn ^{:malli/schema [:=> [:cat :Path :any :map :map] :map]}
  reorder!
  "Move `layer` to sit directly below `other`. Both are layers of this stack.

   The remedy an order-dependence finding names: a layer reaching for something
   a layer above it supplies is in the wrong place, and moving it is the repair
   rather than patching either side."
  [cwd base layer other]
  (attempt-reshape!
   cwd base
   #(jj/jj! cwd "rebase" "-r" (:bookmark layer) "--insert-before" (:bookmark other))))

(defn ^{:malli/schema [:=> [:cat :Path :any :map :map] :map]}
  fold!
  "Squash `layer` into `into-layer`, leaving one layer where there were two.

   Always legal where a reorder may not be — folding removes a boundary rather
   than moving one, so there is no dependency for it to violate. It is therefore
   the answer when a reorder is refused and the defect is still real.

   Deletes the absorbed bookmark, which is not tidying. jj leaves both bookmarks
   on the squashed commit, and `layer-bookmark` takes the first match — so a
   fold that left them both would hide one of the two layers from every later
   read of the stack, while whatever that bookmark had published stayed
   published."
  [cwd base layer into-layer]
  (let [r (attempt-reshape!
           cwd base
           #(jj/jj! cwd "squash" "--from" (:bookmark layer)
                    "--into" (:bookmark into-layer) "--use-destination-message"))]
    (when (:ok? r)
      (jj/jj! cwd "bookmark" "delete" (:bookmark layer)))
    r))

(defn ^{:malli/schema [:=> [:cat :Path :string] :string]}
  workspace-relative
  "`path` as jj will read it: relative to the workspace root, whatever the
   reviewer wrote.

   A finding's location is an ABSOLUTE path — that is the field codex fills —
   and jj resolves a bare fileset argument against the working directory. The
   two coincide only when the review happened to run from the root, so passing
   the reviewer's path through unchanged would silently address a different
   file, or none."
  [cwd path]
  (let [root (str cwd)
        root (if (str/ends-with? root "/") root (str root "/"))]
    (if (str/starts-with? (str path) root)
      (subs (str path) (count root))
      (str path))))

(defn ^{:malli/schema [:=> [:cat :Path :map :string] :boolean]}
  layer-touches?
  "Does `layer`'s own diff name `path`? Path is workspace-relative.

   The precondition a scoped squash needs and jj will not enforce: `jj squash`
   over a fileset the source does not touch moves nothing, says so on stdout and
   exits 0. Without this the stage would report a move it did not make, which is
   the same silent success the conflict check exists to stop.

   FALSE when the workspace cannot be asked, unlike the drift guards, and for
   the opposite reason: those refuse to run and let the round proceed as it
   would have, while proceeding here means claiming a reshape happened. An
   unaskable workspace makes this refuse, and a refusal is visible."
  [cwd layer path]
  (try
    (let [{:keys [exit out]} (jj/jj! cwd "diff" "--name-only" "-r" (:bookmark layer))]
      (and (zero? exit)
           (boolean (some #(= path (str/trim %)) (str/split-lines (str out))))))
    (catch Throwable _ false)))

(defn ^{:malli/schema [:=> [:cat :Path :any :map :map :string] :map]}
  move!
  "Move `layer`'s changes to ONE file down into `into-layer`. Both layers stay.

   The remedy for a seam whose two ends are not adjacent. A fold there is
   refused, and correctly — it would absorb every unnamed layer in between,
   landing changes no reviewer implicated under a claim that never covered them
   — but a refusal was the ONLY answer the stage had, so a seam the warden had
   already described a move for got none. The case it comes from: layer 9
   rewrote a migration whose checksum layer 1's deploy had already recorded, and
   both the reviewer and the warden named the same repair — put that file's
   change in layer 1. That absorbs nothing.

   Neither bookmark is deleted, which is what separates this from `fold!`. A
   fold leaves one layer where there were two, so the absorbed bookmark must go
   or it would hide a layer from every later read of the stack; here both layers
   survive and only their contents move."
  [cwd base layer into-layer path]
  (let [rel (workspace-relative cwd path)]
    (if-not (layer-touches? cwd layer rel)
      {:ok? false
       :reason (str "the finding names " rel " but " (:bookmark layer)
                    " does not change it, so there is nothing there to move down")}
      (attempt-reshape!
       cwd base
       #(jj/jj! cwd "squash" "--from" (:bookmark layer)
                "--into" (:bookmark into-layer) "--use-destination-message" rel)))))

(defn ^{:malli/schema [:=> [:cat :Path :string] [:maybe :string]]}
  resolve-rev
  "The commit id `rev` names right now, or nil when it names nothing.

   Used to pin `@` once per round. `@` is a moving target — it is whatever the
   working copy happens to be — and every stage that re-resolves it is asking a
   different question than the one the round started with.

   nil when the workspace cannot be asked at all. The drift check this feeds is
   a guard, and a guard that cannot run must not become a failure of the thing
   it guards: an unpinnable round proceeds exactly as it did before there was a
   check, which is the behaviour it is an improvement on."
  [cwd rev]
  (try
    (let [{:keys [exit out]} (jj/jj! cwd "log" "-r" rev "-T" "commit_id" "--no-graph")]
      (when (zero? exit)
        (not-empty (str/trim (or out "")))))
    (catch Throwable _ nil)))

(defn ^{:malli/schema [:=> [:cat :Path :string] :boolean]}
  descends-from?
  "Is the working copy still at or above `rev`?

   False means somebody moved the tree under a round in flight — a rebase, an
   abandon, another session sharing the workspace. The round's reviews were of
   content that is no longer what `@` means, so its fixes would land somewhere
   nobody reviewed.

   TRUE when the workspace cannot be asked, for the same reason `resolve-rev`
   returns nil: this guard refusing on its own inability would stop rounds that
   are perfectly fine, which is a worse failure than the one it prevents."
  [cwd rev]
  (try
    (let [{:keys [exit out]} (jj/jj! cwd "log" "-r" (str rev "::@") "-T" "commit_id\n"
                                     "--no-graph")]
      (and (zero? exit) (boolean (not-empty (str/trim (or out ""))))))
    (catch Throwable _ true)))
