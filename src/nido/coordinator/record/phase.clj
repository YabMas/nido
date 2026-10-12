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
   workstream's index rows, whose `:phase-gate` rows carry the `:phase` they opened and the
   `:opens` claim they name.")


(defn- gate-rows
  "The `:phase-gate` index rows, oldest first."
  [entries]
  (->> entries (filter #(= :phase-gate (:kind %))) (sort-by :seq)))

(defn- gate-opens
  "The 0-based index of the phase of `phases` that `gate` opened, given the furthest one
   already reached is `i`, or nil when it opened none of this plan's.

   By the gate's `:phase` — the number of the phase it opened, which is the gate's identity
   — wherever it carries one. Its `:opens` text is a description, and a design amended
   after the gate rewords the claim it names without moving the plan: joined by that
   text, the reworded plan read its gated phase as never opened, and a judge was told the
   second phase was the first. A `:phase` past this plan's end is a phase of a design this
   one superseded with fewer phases, and is not counted.

   A gate written before gates carried `:phase` is joined by its `:opens`, to the first
   phase after `i` whose `:claim` it names — so a plan that repeats a claim is read one
   phase per gate rather than jumping to that claim's last position."
  [phases i gate]
  (if-let [n (:phase gate)]
    (let [k (dec n)]
      (when (and (< i k) (< k (count phases))) k))
    (some #(when (= (:opens gate) (:claim (phases %))) %)
          (range (inc i) (count phases)))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] :map]}
  opened-by
  "`{phase-index gate-row}` — the `:phase-gate` row among `entries` that opened each phase of
   `design`'s plan it opened, 0-based. The join `progress` reads, so a phase a gate is shown
   against is the phase it opened. Empty for an unphased design."
  [design entries]
  (let [phases (vec (:phases design))]
    (loop [i 0 [g & more] (gate-rows entries) acc {}]
      (if-not g
        acc
        (if-let [k (gate-opens phases i g)]
          (recur k more (assoc acc k g))
          (recur i more acc))))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] [:maybe :PhaseProgress]]}
  progress
  "Where the workstream whose index rows are `entries` stands in `design`'s plan, or nil
   when `design` carries no `:phases` (or is nil).

   `:current` and `:of` are 1-based. `:landed?` is whether a `:merged` row was appended
   after the newest `:phase-gate` — after any at all, when there is none. `:exit` is the
   current phase's exit — the gate asserted to open its successor, and so the one a
   workstream between phases awaits. `:next` is the phase after the current one, the whole
   plan entry with its `:claim` and `:exit`, or nil on the last phase.

   Gates are read oldest first and joined to the phase each opened by `gate-opens`; a gate
   that opened none of this plan's phases is not counted, since progress under a plan is
   only ever progress through ITS phases."
  [design entries]
  (when-let [phases (not-empty (vec (:phases design)))]
    (let [gates     (gate-rows entries)
          current   (inc (reduce (fn [i g] (or (gate-opens phases i g) i)) 0 gates))
          last-gate (or (:seq (last gates)) 0)]
      {:current current
       :of      (count phases)
       :landed? (boolean (some #(and (= :merged (:kind %)) (> (:seq %) last-gate)) entries))
       :exit    (:exit (phases (dec current)))
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


(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] :boolean]}
  delivered?
  "Whether `design` — an entry carrying its `:seq` — has landed all the work it planned: a
   `:merged` row follows it, and it holds no phase after the one that landed. False for a
   nil design.

   Read by POSITION, not by the landing's `:design` citation. A landing names a design
   without standing on it — one PR may carry work from several units — and the citation
   is not reliable either: a merge poller has cited a design two units older than the one
   whose door the PR opened. A `:merged` appended after the design is the fact that the
   code it described is on main.

   A phased design with a phase still to come is NOT delivered at its first landing: the
   next phase's work is judged against the same record, so it stays the yardstick."
  [design entries]
  (boolean
   (when-let [n (:seq design)]
     (and (some #(and (= :merged (:kind %)) (> (:seq %) n)) entries)
          (let [p (progress design entries)]
            (or (nil? p) (and (nil? (:next p)) (:landed? p))))))))
