;; src/nido/coordinator/record/phase.clj
(ns nido.coordinator.record.phase
  "Where a workstream is in a phased design's plan, read off the ledger rows it is handed.

   DERIVED, never stored, for the reason standing is: the current phase changes exactly
   when an entry is appended, so a stored copy would be wrong at the moment it mattered.

   The current phase is the furthest phase of the plan that a `:phase-gate` opens — the
   first when none does. `:merged` rows decide only whether that phase has LANDED, by
   being appended after the newest gate; they never decide which phase it is, because a
   findings round merging on the same workstream, or a landing both landing paths
   recorded, would move a count and not the plan.

   Reads no ledger and resolves no design. Both are the caller's, which is what lets the
   ledger's own gate check ask this under its lock without the two namespaces depending
   on each other: every reader outside the ledger passes the design it stands on and the
   workstream's index rows, whose `:phase-gate` rows carry the `:opens` they name.")


(defn- gate-rows
  "The `:phase-gate` index rows, oldest first."
  [entries]
  (->> entries (filter #(= :phase-gate (:kind %))) (sort-by :seq)))

(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] [:maybe :PhaseProgress]]}
  progress
  "Where the workstream whose index rows are `entries` stands in `design`'s plan, or nil
   when `design` carries no `:phases` (or is nil).

   `:current` and `:of` are 1-based. `:landed?` is whether a `:merged` row was appended
   after the newest `:phase-gate` — after any at all, when there is none. `:next` is the
   phase after the current one, the whole plan entry with its `:claim` and `:exit`, or nil
   on the last phase.

   Gates are read oldest first, each opening the first phase AFTER the one already reached
   whose `:claim` it names, so a plan that repeats a claim is read one phase per gate
   rather than jumping to that claim's last position. A gate whose `:opens` names no such
   phase is not counted: it opened a phase of a design this one superseded without keeping
   that phase's claim, and progress under a plan is only ever progress through ITS phases."
  [design entries]
  (when-let [phases (not-empty (vec (:phases design)))]
    (let [gates     (gate-rows entries)
          opens     (fn [i gate]
                      (or (some #(when (= (:opens gate) (:claim (phases %))) %)
                                (range (inc i) (count phases)))
                          i))
          current   (inc (reduce opens 0 gates))
          last-gate (or (:seq (last gates)) 0)]
      {:current current
       :of      (count phases)
       :landed? (boolean (some #(and (= :merged (:kind %)) (> (:seq %) last-gate)) entries))
       :next    (get phases current)})))

(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] :keyword]}
  landing-outcome
  "The outcome a landing of the current phase closes the workstream with: `:between-phases`
   while `design`'s plan has a phase after the current one, `:done` otherwise — an
   unphased design, and no design at all, included.

   Asks only which phase is current, never whether it has landed. The merge poll closes
   the workstream BEFORE it appends the landing's `:merged`, so at the moment of asking
   the landing being recorded is not yet on the ledger."
  [design entries]
  (if (some? (:next (progress design entries)))
    :between-phases
    :done))

