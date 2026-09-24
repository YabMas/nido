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
  "Stage order, for picking the LOWEST owed one. Its own list rather than
   `pipeline`'s spine: `pipeline` depends on this namespace, and the rungs a
   re-entry can name — :approval, and the landing stages — are ones the unit's
   spine deliberately leaves out. What reaches the arc is the position this
   clamps, never this vocabulary."
  [:intent :baseline :design :approval :implementation :publication :shipping])

(def stages
  "The arc stages re-entry can name, innermost first.

   Nothing here sends a workstream back to :intent — that is established once and only an
   explicit retraction unseats it, which `place` already reports as its own
   position. :baseline is named for one reason only: the goal the newest survey
   was scoped for was replaced. The rest are the trail stages, which a later
   design unseats."
  [:baseline :design :approval :implementation :publication :shipping])

(def trail-kinds
  "The entry kinds whose stage `place` passes by presence alone, each with the arc
   stage it passes.

   Exactly the four clauses `place` reads between the halts and the approval.
   Keeping the two in step matters: a kind that gains a monotone clause there and
   no entry here is a stage that can be passed and never un-passed, which is the
   whole defect this exists to close."
  {:implementation-completed :implementation
   ;; The implementation's review passes the stage it reviews, now that it folds
   ;; into it. A stale implementation takes its review with it, which is what
   ;; sending the work back to :implementation already meant.
   :review                   :implementation
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

(defn- restart
  "The :seq of the newest `:phase-gate` or `:findings` on `w`, or nil.

   Both put a workstream that already carried a trail back to work: a gate opens
   the next phase, a findings round reopens the landed one. What either owes is
   written after it, so a trail record from before it is history, never a stage
   passed — without this the phase that just landed would read as the next
   phase's implementation, review and publication, and a resolved findings round
   as a landing."
  [w]
  (some->> (:entries w)
           (filter #(#{:phase-gate :findings} (:kind %)))
           (map :seq)
           seq
           (reduce max)))

(defn ^{:malli/schema [:=> [:cat :Workstream :int] :map]}
  trail-standing
  "How each trail kind on `w` stands against the design at `current`:

     {:current #{kinds holding at least one record made under it}
      :stale   [{:kind :seq :under} …]  — records made under an older design}

   Only records written after the newest restart (`restart`) are graded; those
   before it are neither current nor stale.

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
                      (filter #(and (trail-kinds (:kind %))
                                    (> (:seq %) (or (restart w) 0)))
                              (:entries w)))]
    {:current (into #{} (comp (filter #(>= (:under %) current)) (map :kind)) graded)
     ;; BEHIND, not merely different. Callers pass the latest design, so a record
     ;; under a newer one cannot arise there — but `stale` should mean what it
     ;; says for any argument, and a `not=` here reports a record from the future
     ;; as rotten.
     :stale   (into [] (filter #(< (:under %) current)) graded)}))

(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:maybe :map] [:maybe :map]] [:maybe :map]]}
  current-standing
  "The standing that answers for a workstream's current records: the newest
   design's where one exists, else the newest baseline's, else the newest
   intent's. Each is what the work rests on at that stage, so a record beneath
   it speaks through it — a design's standing already reports its cited survey
   and goal — and a record it does not rest on does not speak at all.

   The one place that choice is made. Every reader asking what a workstream's
   records owe takes it from here, so no two of them choose differently."
  [design-standing baseline-standing intent-standing]
  (or design-standing baseline-standing intent-standing))

(defn ^{:malli/schema [:=> [:cat :Workstream [:maybe :map]] [:maybe [:set :keyword]]]}
  standing-trail
  "The trail kinds that stand on `w` since its newest `:phase-gate` or `:findings`,
   or nil when it holds neither — nil meaning the whole index may be read, as it
   always could.

   A set, possibly empty: empty is the answer for a phase just opened or a round
   just filed, whose work is all still owed. Under `design`, only records made
   under it count, as in `trail-standing`; with no design, every trail record
   written since does."
  [w design]
  (when-let [r (restart w)]
    (if design
      (:current (trail-standing w (:seq design)))
      (into #{} (comp (filter #(and (trail-kinds (:kind %)) (> (:seq %) r))) (map :kind))
            (:entries w)))))

(defn ^{:malli/schema [:=> [:cat :Workstream [:maybe :map] [:maybe :Standing] [:maybe :map]]
                        [:maybe :map]]}
  of*
  "Re-entry from what a caller already holds: the workstream record, its latest
   design, that design's standing, and the standing of its newest baseline
   (`standing/of-baseline`). Pure, and the arity the position fold uses.

   Split from `of` for a measured reason. `pipeline/of` already reads the
   workstream and already runs a standing closure — ~4ms a row, ~190ms for a
   board of forty-five — and a version of this that re-read both would double the
   most expensive part of a render to answer a question the caller had the inputs
   for.

   Returns {:stage :trail :because (:indeterminate?)} or nil. `:because` is the
   standing's own :blocked map where standing is what decided, so a surface
   renders one vocabulary of reasons rather than a translation of it. `:trail` is
   the trail kinds that DO still stand — what the position fold may still read —
   and it is empty whenever the baseline, the design or the approval is what is
   owed, because nothing above the approval may be reported at all.
   `:indeterminate?` marks the answer standing could not derive, which the fold
   must not read as a design that is merely owed again."
  [w design st bst]
  (cond
    ;; Indeterminate outranks the rest: standing could not be derived, and it
    ;; fails closed rather than waving a workstream through on a ledger nobody
    ;; can read.
    (and design (:indeterminate? st))
    {:stage :design :trail #{} :because (:blocked st) :indeterminate? true}

    ;; A judgement somebody derived about THIS design keeps its own answer.
    ;; `standing` orders both above a moved goal for that reason, and the fold
    ;; reports each as a position of its own.
    (and design (#{:design-retracted :design-invalidated} (get-in st [:blocked :reason])))
    {:stage :design :trail #{} :because (:blocked st)}

    ;; The survey is owed before anything written over it, a design or none: a
    ;; goal replaced after the newest baseline was scoped, or that baseline
    ;; retracted, leaves it unable to be reviewed or designed over, so the arc
    ;; comes back to the baseline rung. Not to the design that cites it, and not to the
    ;; verification of the survey scoped for the old goal — the boundary refuses
    ;; both. An indeterminate baseline standing blocks nothing here: it is never
    ;; :verified?, which is the fail-closed half, and routing on a ledger nobody
    ;; can read would be advancing on a default. Over a design, owed only when
    ;; the newest survey is the one the design cites: a later survey it does not
    ;; cite unseats nothing, and once one is written over a cited survey the
    ;; work is that survey's, which the record trail already places.
    (and (#{:goal-superseded :premise-retracted} (get-in bst [:blocked :reason]))
         (or (nil? design) (= (get-in design [:baseline :seq]) (:seq bst))))
    {:stage :baseline :trail #{} :because (:blocked bst)}

    ;; No design, nothing to be standing on, and nothing to come back to. A
    ;; workstream this early is placed by its record trail exactly as before.
    (nil? design) nil

    (not (:decidable? st))
    {:stage :design :trail #{} :because (:blocked st)}

    ;; A clearance satisfies this as a grant does. Without that, a conforming
    ;; design that cleared and was implemented reads with an EMPTY trail, so
    ;; `place` ignores the completed implementation, returns :design-cleared
    ;; again, and points the next action back at :implement for ever — looping at
    ;; the gate it was supposed to have skipped.
    (and (nil? (:approved-by st)) (nil? (:cleared-by st)))
    {:stage :approval
     :trail #{}
     :because {:reason :not-approved
               :seq (:seq design)
               :detail (str "neither a :design-approved nor a :design-cleared names"
                            " the design at entry " (:seq design))}}

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
    (let [d (ws/latest-entry project ws-id :design)
          b (ws/latest-entry project ws-id :baseline)]
      (of* w d (when d (standing/of-design project ws-id d))
           (when b (standing/of-baseline project ws-id b))))))
