;; src/nido/coordinator/record/standing.clj
(ns nido.coordinator.record.standing
  "Whether a record still holds, derived from the ledger's own citations.

   Nothing marks anything invalid. There is no field, entry or cache recording
   what is currently valid, and so nothing to keep in sync — which is the whole
   shape of this: marking dependents invalid needs an index of dependents kept
   in step with the graph, and that index drifting is the failure this project
   has paid for more than once. A closure computed on every read cannot drift.

   Four things can make a design undecidable and they are not the same.

   A RETRACTION says a record is untrue, and only an explicit one counts. An
   UNVERIFIED PREMISE says nobody has checked the baseline this design names,
   which is the question the design round already asked; it moved here so that
   every surface asks it the same way. An INVALIDATING VERDICT is the review
   round saying the design itself is wrong rather than its execution — the two
   verdicts `report/verdict-invalidates` names, which the round already requires
   a `:needs` and a non-empty `:invariants-broken` on, and which until now
   reached a person only if they were watching the terminal it printed to. A
   SUPERSEDED PREMISE says the baseline this design cites has been re-surveyed
   SINCE the design was written.

   That last one is the narrow reading of a rule this module used to refuse
   outright, and the refusal was right for the reason it gave: a review round
   appends three to six superseding baselines in a normal run, so a rule firing
   on supersession as such would be switched off within a week. Measured across
   the live ledgers, 175 of 275 baselines carry a `:supersedes` and almost all of
   them sit inside an authoring stretch — BEFORE the design that cites them. So
   the rule carries a sequence guard: a replacement only counts when it was
   appended AFTER the design it would unseat. Correction, age and a changed
   working copy still mean nothing here.

   Both new causes are answerable, and by records this vocabulary already has: a
   superseding design cites the corrected baseline, and an approval appended
   after an invalidating verdict is a person having read it and granted the
   design anyway.

   Lives beside the ledger rather than inside it. The store must not know which
   review verdicts count as verification — that is this module's secret, and the
   ledger's job is to hold entries and resolve numbers."
  (:require
   [nido.coordinator.report :as report]
   [nido.coordinator.record.workstream :as ws]))

(defn- indexed-count
  "How many entries of `kind` the workstream's index claims."
  [w kind]
  (count (filter #(= kind (:kind %)) (:entries w))))

(defn- readable
  "Every parsed entry of `kind`, or ::unreadable when the index claims more than
   parse.

   Standing FAILS CLOSED, alone among this ledger's readers. Everything else
   degrades to nil on an entry it cannot parse, and that is right for them: a
   pane that cannot render one record should still render the rest. But an
   unreadable retraction that silently does not retract turns a safety check
   into a formality, and the gates that consult this refuse a branch. So a
   missing entry of a kind standing depends on makes standing indeterminate,
   and an indeterminate standing blocks rather than waves through."
  [project ws-id w kind]
  (let [parsed (ws/entries-of project ws-id kind)]
    (if (< (count parsed) (indexed-count w kind)) ::unreadable parsed)))

(defn- retraction-index
  "Retracted entry :seq → the :seq of the retraction that says so."
  [retractions]
  (into {} (map (juxt #(get-in % [:retracts :seq]) :seq)) retractions))

(defn- replacement
  "The newest baseline reachable from `seq-n` by correction citations, or nil.

   Follows only a citation a correcting baseline WROTE naming what it corrected.
   Every baseline written before that field existed carries none, and those yield
   no replacement — taking the newest baseline instead is exactly the recency the
   ledger's citations exist to refuse. Bounded by the number of baselines, so a
   citation cycle cannot spin here."
  [baselines seq-n]
  (let [by-superseded (into {} (map (juxt #(get-in % [:supersedes :seq]) :seq))
                            (filter #(get-in % [:supersedes :seq]) baselines))]
    (loop [at seq-n, seen #{seq-n}, found nil, budget (count baselines)]
      (let [nxt (by-superseded at)]
        (if (or (nil? nxt) (contains? seen nxt) (neg? budget))
          found
          (recur nxt (conj seen nxt) nxt (dec budget)))))))

(defn- invalidating-verdict
  "The :seq of a verdict that put `design-seq` itself in question and that nobody
   has answered, or nil.

   ANSWERED means an approval naming this design appended after the verdict —
   a person shown the invalidation who granted the design regardless. Compared by
   :seq rather than by presence, because a design approved BEFORE the round ran is
   exactly the case this exists to catch: that grant was made against a reading
   the verdict has since contradicted.

   Keyed on :design-seq, so a verdict about a design that has since been
   superseded says nothing about the one standing now — the same rule
   `review.stages/discover-prior-verdict` already applies for the same reason."
  [verdicts approvals design-seq]
  (->> verdicts
       (filter #(and (= design-seq (:design-seq %))
                     (report/verdict-invalidates (:verdict %))))
       (remove (fn [v] (some #(and (= design-seq (get-in % [:design :seq]))
                                   (> (:seq %) (:seq v)))
                             approvals)))
       last
       :seq))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :Standing]}
  of-design
  "Whether `design` — a stamped :design record — stands, and what stops it.

   :decidable? is the question the design round asks before it will judge:
   this design is not retracted, and the baseline it NAMES was found sufficient
   at exactly that number. :decided? adds the human's grant.

   Supersession never blocks. A baseline corrected but not retracted still
   stands, and a design citing it is decidable exactly when a verdict naming
   that entry found it sufficient. The correction is reported, not enforced:
   it tells a design whose premise was never verified which record would
   re-establish it, instead of repeating an opaque no."
  [project ws-id design]
  (let [w (ws/read-ws project ws-id)]
    (if (nil? w)
      {:indeterminate? true :blocked {:reason :no-workstream
                                      :detail (str "no workstream " ws-id)}}
      (let [rs   (readable project ws-id w :retraction)
            revs (readable project ws-id w :baseline-review)
            oks  (readable project ws-id w :design-approved)
            bls  (readable project ws-id w :baseline)
            vs   (readable project ws-id w :design-verdict)]
        (if (some #{::unreadable} [rs revs oks bls vs])
          {:indeterminate? true
           :blocked {:reason :unreadable-ledger
                     :detail (str "an entry standing depends on could not be read on "
                                  ws-id " — standing cannot be derived, so nothing "
                                  "may proceed on it")}}
          (let [retracted   (retraction-index rs)
                design-seq  (:seq design)
                premise-seq (get-in design [:baseline :seq])
                approval    (->> oks
                                 (filter #(= design-seq (get-in % [:design :seq])))
                                 last)
                sufficient? (boolean
                             (some #(and (= premise-seq (:baseline-seq %))
                                         (report/verdict-holds (:verdict %)))
                                   revs))
                replaced-by (replacement bls premise-seq)
                premise {:seq premise-seq
                         :retracted-by (retracted premise-seq)
                         :sufficient?  sufficient?
                         :replaced-by  replaced-by
                         ;; The replacement only unseats the design when it was
                         ;; appended after it. `replacement` walks the citation
                         ;; chain forward and each step is a later entry, so the
                         ;; seq it returns is the newest — one comparison decides
                         ;; whether any replacement postdates the design.
                         :superseded-after (when (and replaced-by
                                                     (> replaced-by design-seq))
                                             replaced-by)}
                invalidated (invalidating-verdict vs oks design-seq)
                blocked (cond
                          (retracted design-seq)
                          {:reason :design-retracted :seq (retracted design-seq)
                           :detail (str "the design at entry " design-seq
                                        " was retracted by entry " (retracted design-seq))}

                          ;; Above the premise clauses on purpose. A round that
                          ;; judged THIS design wrong has read the code against
                          ;; it; a question about the baseline underneath is the
                          ;; less specific answer and would bury the one somebody
                          ;; actually derived.
                          invalidated
                          {:reason :design-invalidated :seq invalidated
                           :detail (str "the review round at entry " invalidated
                                        " found this design invalid rather than its"
                                        " execution, and no approval since answers it")}

                          (nil? premise-seq)
                          {:reason :no-premise
                           :detail "the design cites no baseline"}

                          (:retracted-by premise)
                          {:reason :premise-retracted :seq (:retracted-by premise)
                           :replaced-by (:replaced-by premise)
                           :detail (str "the baseline at entry " premise-seq
                                        " was retracted by entry " (:retracted-by premise)
                                        (when-let [r (:replaced-by premise)]
                                          (str "; entry " r " corrects it")))}

                          (not sufficient?)
                          {:reason :premise-unverified :seq premise-seq
                           :replaced-by (:replaced-by premise)
                           :detail (str "the design cites the baseline at entry " premise-seq
                                        ", and no round has found that baseline sufficient"
                                        (when-let [r (:replaced-by premise)]
                                          (str "; entry " r " corrects it and is what a "
                                               "superseding design would cite")))}

                          ;; BELOW :premise-unverified, and the order is a claim.
                          ;; A premise nobody ever checked is the more basic fact
                          ;; and the more actionable answer — its own :replaced-by
                          ;; already names what to cite instead — so reporting a
                          ;; re-survey there would tell an author their footing
                          ;; moved when they never had one. This fires only on a
                          ;; design that DID stand on a verified baseline.
                          (:superseded-after premise)
                          {:reason :premise-superseded :seq premise-seq
                           :replaced-by (:superseded-after premise)
                           :detail (str "the baseline at entry " premise-seq
                                        " was found sufficient and then re-surveyed"
                                        " at entry " (:superseded-after premise)
                                        ", after this design was written — the design"
                                        " stands on a reading nobody holds any more")})]
            ;; :blocked answers ONE question — what stops this design being
            ;; DECIDABLE — and the absence of an approval is deliberately not in
            ;; it. The premise gate reads this before a human has had anything
            ;; to approve, and a gate that refused an unapproved design would
            ;; make the design round unreachable. What wants both is the landing
            ;; check, and it composes them itself.
            (cond-> {:live?       (nil? (retracted design-seq))
                     :premise     premise
                     :approved-by (:seq approval)
                     :decidable?  (nil? blocked)
                     :decided?    (and (nil? blocked) (some? approval))}
              blocked (assoc :blocked blocked))))))))

(defn ^{:malli/schema [:=> [:cat :Standing] [:maybe :string]]}
  why-not-decided
  "Why `standing` is not decided, in a form a human can act on, or nil.

   Composes the two questions the map keeps apart: what stops it being
   decidable, and — when nothing does — that nobody has granted it."
  [{:keys [decided? decidable? blocked approved-by] :as st}]
  (cond
    decided?        nil
    (not decidable?) blocked
    (nil? approved-by)
    {:reason :not-approved
     :detail (str "no :design-approved names this design — the decision round "
                  "prepares an approval and does not grant one")}
    :else (:blocked st)))
