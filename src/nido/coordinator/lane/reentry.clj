;; src/nido/coordinator/lane/reentry.clj
(ns nido.coordinator.lane.reentry
  "How far up the arc a workstream's records still stand.

   One question, asked of a ledger: given what is true NOW, which arc stage does
   this workstream have to come back to? nil is the ordinary answer and means
   nothing beneath the trail has moved.

   It exists because the position fold could not ask it. `pipeline/place` decides
   its record-trail clauses on whether an entry KIND is present in the index, and
   an index is append-only — nothing ever takes a kind off it — so those clauses
   are monotone and no later entry can un-pass a stage they placed. That is not a
   defect in the fold; it is what a fold over a growing set can do. The missing
   half is a reading of the SAME ledger that says how far up it is still good
   for, and clamping the monotone answer with it. This is that reading; the
   clamp is `place`'s.

   Kept out of `pipeline` and out of `standing`, which is a claim rather than
   tidiness. `standing` answers about ONE design record and must stay that way —
   it is what the landing gate, the approval gate and this all ask, and a version
   of it that also knew about draft PRs would be answering two questions.
   `pipeline` owns the arc: which kinds belong to which stage, and what outranks
   what. Neither of them was the right home for `does the trail still belong to
   the design it was built under`, and widening either to hold it would have made
   one module keep two secrets.

   DERIVED, never stored — the third such derivation over this ledger and for the
   reason the other two give. What makes a stage stop being passed is precisely a
   later entry, so a stored copy would be wrong exactly when it mattered."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as ws]))

(def ^:private stages-order
  "Arc stage order, for picking the LOWEST owed one. A copy of the spine's order
   rather than a require of it: `pipeline` depends on this namespace, so reaching
   back for `arc-stages` would make the two mutually dependent — which is the
   cycle Parnas warns about, where neither module hides anything from the other."
  [:intent :baseline :design :approval :implementation :review :publication :shipping])

(def stages
  "The arc stages re-entry can name, innermost first.

   A subset of `pipeline/arc-stages` and deliberately not all of it. Nothing here
   sends a workstream back to :intent or :baseline — those are established once
   and only an explicit retraction unseats them, which `place` already reports as
   its own position. The rest are the trail stages, which a later design unseats."
  [:design :approval :implementation :review :publication :shipping])

(def trail-kinds
  "The entry kinds whose stage `place` passes by presence alone, each with the arc
   stage it passes.

   Exactly the four clauses `place` reads between the halts and the approval.
   Keeping the two in step matters: a kind that gains a monotone clause there and
   no entry here is a stage that can be passed and never un-passed, which is the
   whole defect this exists to close."
  {:implementation-completed :implementation
   :review                   :review
   :pr-opened                :publication
   :merged                   :shipping})

(defn- generation
  "The :seq of the newest design appended BEFORE `n` — which design a record
   written at `n` was made under.

   THE PRE-CONTRACT READING, and only that. A trail record now NAMES the design
   it was made under and the index carries that citation as `:under`, so append
   order is no longer evidence about anything written since. What remains is
   every record written before the citation existed, which carries none and can
   be attributed no other way — so this is kept for them, and no writer may
   produce a record it applies to.

   Positional, which is what made it a seam while it was the only answer: it
   reports a fact about the ORDER entries were written in rather than about what
   the records say. Over the pre-contract region that is the same evidence a
   person reading the timeline would use, and the answer rides on the result so a
   reader can disagree with it.

   nil when no design precedes `n`. That is a refusal, not a default: work done
   before any design exists cannot be attributed to one, and calling it stale
   would be inventing a generation to blame."
  [design-seqs n]
  (last (take-while #(< % n) design-seqs)))

(defn ^{:malli/schema [:=> [:cat :Workstream :int] :map]}
  trail-standing
  "How each trail kind on `w` stands against the design at `current`:

     {:current #{kinds holding at least one record made under it}
      :stale   [{:kind :seq :under} …]  — records made under an older design}

   AT LEAST ONE, and that is the whole of the rule. A stage is passed when it
   holds a record of the design being built now; the records of designs before it
   are history and stay in the ledger, not a debt that can never be paid. Asking
   instead that EVERY record be current makes the first stale one permanent — a
   workstream that redesigned, re-approved and re-implemented would still be held
   at the implementation by the entry it had just superseded, and could never
   reach the review again.

   Reads the entry INDEX only — kind and :seq, which the workstream record
   already carries — so this parses nothing. That is what keeps the clamp
   affordable on a board that computes a position per rendered row."
  [w current]
  (let [designs (->> (:entries w)
                     (filter #(= :design (:kind %)))
                     (map :seq)
                     sort
                     vec)
        ;; The citation first, the walk only for rows that predate it. The two
        ;; cannot disagree: a row carrying `:under` was written under this
        ;; contract, where append order stopped being evidence, and one without
        ;; it was written before the field existed at all.
        graded  (into []
                      (keep (fn [e]
                              (when-let [g (or (:under e)
                                               (generation designs (:seq e)))]
                                {:kind (:kind e) :seq (:seq e) :under g})))
                      (filter #(trail-kinds (:kind %)) (:entries w)))]
    {:current (into #{} (comp (filter #(>= (:under %) current)) (map :kind)) graded)
     ;; BEHIND, not merely different. Callers pass the latest design, so a record
     ;; under a newer one cannot arise there — but `stale` should mean what it
     ;; says for any argument, and a `not=` here reports a record from the future
     ;; as rotten.
     :stale   (into [] (filter #(< (:under %) current)) graded)}))

(defn ^{:malli/schema [:=> [:cat :Workstream [:maybe :map] [:maybe :Standing]] [:maybe :map]]}
  of*
  "Re-entry from what a caller already holds: the workstream record, its latest
   design, and that design's standing. Pure, and the arity the position fold
   uses.

   Split from `of` for a measured reason. `pipeline/of` already reads the
   workstream and already runs a standing closure — ~4ms a row, ~190ms for a
   board of forty-five — and a version of this that re-read both would double the
   most expensive part of a render to answer a question the caller had the inputs
   for.

   Returns {:stage :trail :because} or nil. `:because` is the standing's own
   :blocked map where standing is what decided, so a surface renders one
   vocabulary of reasons rather than a translation of it. `:trail` is the trail
   kinds that DO still stand — what the position fold may still read — and it is
   empty whenever the design or the approval is what is owed, because nothing
   above the approval may be reported at all."
  [w design st]
  (cond
    ;; No design, nothing to be standing on, and nothing to come back to. A
    ;; workstream this early is placed by its record trail exactly as before.
    (nil? design) nil

    ;; Indeterminate outranks the rest: standing could not be derived, and it
    ;; fails closed rather than waving a workstream through on a ledger nobody
    ;; can read.
    (:indeterminate? st)
    {:stage :design :trail #{} :because (:blocked st)}

    (not (:decidable? st))
    {:stage :design :trail #{} :because (:blocked st)}

    (nil? (:approved-by st))
    {:stage :approval
     :trail #{}
     :because {:reason :not-approved
               :seq (:seq design)
               :detail (str "no :design-approved names the design at entry "
                            (:seq design))}}

    :else
    (let [{:keys [current stale]} (trail-standing w (:seq design))
          ;; A stage is owed when it holds records and none of them is current.
          ;; The LOWEST such stage is the answer: work resumes at the earliest
          ;; thing that no longer stands, and everything above it follows.
          owed (->> stale
                    (map :kind)
                    (remove current)
                    (map trail-kinds)
                    (sort-by #(.indexOf ^java.util.List stages-order %))
                    first)]
      (when owed
        {:stage owed
         :trail current
         :because {:reason :trail-superseded
                   :seq (:seq design)
                   :records (vec stale)
                   :detail (str (count stale) " record"
                                (when (not= 1 (count stale)) "s")
                                " of the trail "
                                (if (= 1 (count stale)) "was" "were")
                                " written under an earlier design ("
                                (->> stale
                                     (map #(str (name (:kind %)) " at " (:seq %)
                                                " under " (:under %)))
                                     (str/join "; "))
                                ")")}}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  of
  "Re-entry for a workstream, reading what it needs. The convenience arity, for
   callers holding nothing — a task, a test, a one-off. Everything on a render
   path should use `of*` with what it already has."
  [project ws-id]
  (when-let [w (ws/read-ws project ws-id)]
    (let [d (ws/latest-entry project ws-id :design)]
      (of* w d (when d (standing/of-design project ws-id d))))))
