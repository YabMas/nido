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
   [nido.vsdd.jj :as jj]))

(def ^:private row-template
  "One tab-separated row per commit. Descriptions are deliberately NOT in here:
   they are multi-line, which would need a record separator and a parser. The
   few layer tips that need their brief fetch it by bookmark (`description`)."
  "commit_id ++ \"\\t\" ++ change_id ++ \"\\t\" ++ local_bookmarks ++ \"\\n\"")

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

(defn stack
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

(defn ranges
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

(defn description
  "A revision's full commit message, or nil."
  [cwd rev]
  (let [{:keys [exit out]} (jj/jj! cwd "log" "-r" rev "--no-graph" "-T" "description")]
    (when (zero? exit) out)))

(def ^:private brief-fields
  {"Layer" :mode "Claims" :claims "Verify" :verify
   "Lane" :lane "Out of scope" :out-of-scope})

(def ^:private field-re #"^(Layer|Claims|Verify|Lane|Out of scope):\s*(.*)$")
(def ^:private continuation-re #"^\s+\S.*$")

(defn parse-brief
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

(defn brief
  "The review brief of the layer whose tip is `rev`, or nil."
  [cwd rev]
  (parse-brief (description cwd rev)))

;; ---- landing a fix on a layer -------------------------------------------

(defn position-for-fix!
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

(defn land-fix!
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

(defn restore-top!
  "Return the working copy to a fresh empty commit on top of the stack, so
   whatever runs next sees the whole stack in `<base>..@` again.

   Restoring by bookmark rather than by remembering the previous `@`: jj
   abandons an empty, description-less, bookmark-less commit as soon as the
   working copy moves off it, so the `@` that was there before the insert is
   usually gone by now. Trying to `jj edit` it back fails with `Revision doesn't
   exist` and leaves the working copy stranded mid-stack.

   Also cleans up after a fixer that changed nothing: the empty commit the
   insert created is abandoned by the same rule when we move away from it."
  [cwd stack]
  (when-let [top (last stack)]
    (jj/jj! cwd "new" (:bookmark top))))
