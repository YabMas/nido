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
  "The newest record in `records` reachable from `seq-n` by :supersedes
   citations, or nil.

   Follows only a citation a replacing record WROTE naming what it replaced.
   Every record written before its kind carried that field has none, and those
   yield no replacement — taking the newest of the kind instead is exactly the
   recency the ledger's citations exist to refuse. Bounded by the number of
   records, so a citation cycle cannot spin here.

   Over any one kind, not baselines alone: an intent replaces an intent by the
   same edge and is walked by the same rule, so a goal that moved is found the
   way a re-survey already was. Callers pass one kind's records — a chain must
   not step between kinds."
  [records seq-n]
  (let [by-superseded (into {} (map (juxt #(get-in % [:supersedes :seq]) :seq))
                            (filter #(get-in % [:supersedes :seq]) records))]
    (loop [at seq-n, seen #{seq-n}, found nil, budget (count records)]
      (let [nxt (by-superseded at)]
        (if (or (nil? nxt) (contains? seen nxt) (neg? budget))
          found
          (recur nxt (conj seen nxt) nxt (dec budget)))))))

(defn- goal-replaced
  "The :seq of the intent that replaced the goal at `goal-seq` after the record
   at `record-seq` was written, or nil.

   The one goal walk every rung is unseated by — `replacement` over the intents,
   under the sequence guard a re-survey is held to — so a design and the survey
   beneath it cannot disagree about whether the same goal still holds.
   `replacement` walks forward and every step is a later entry, so the seq it
   returns is the newest: the live goal, and one comparison places it."
  [ins goal-seq record-seq]
  (when goal-seq
    (when-let [r (replacement ins goal-seq)]
      (when (> r record-seq) r))))

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
            vs   (readable project ws-id w :design-verdict)
            ins  (readable project ws-id w :intent)]
        (if (some #{::unreadable} [rs revs oks bls vs ins])
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
                ;; The goal this design was written to serve, and the goal the
                ;; baseline under it was scoped for. Either moving unseats the
                ;; design, and they are asked separately because a design may
                ;; cite a :triage entry as its intent while its baseline cites
                ;; an :intent — two edges to one question.
                goal-seq    (get-in design [:intent :seq])
                goal-moved  #(goal-replaced ins % design-seq)
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
                                             replaced-by)
                         ;; The goal the design serves, and the goal its baseline
                         ;; was scoped for, are one chain: root agreement refuses a
                         ;; design whose baseline roots elsewhere. So one answer
                         ;; says whether the survey is owed too.
                         :goal-replaced-by (goal-moved goal-seq)}
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

                          ;; ABOVE the premise clauses, and the order is the
                          ;; claim. A moved goal is the deeper fact: re-verifying
                          ;; the baseline underneath it answers a question nobody
                          ;; is asking any more, so reporting the premise first
                          ;; would send an author to re-establish footing for work
                          ;; whose point has changed. Below the two clauses that
                          ;; name a judgement about THIS design, for the reason
                          ;; those already give — somebody derived those.
                          ;; ONE goal edge, not two. An earlier cut also asked
                          ;; whether the goal the BASELINE was scoped for had
                          ;; moved, which was reachable only while a design could
                          ;; cite a :triage as its intent — its baseline could
                          ;; then be scoped for a different goal. With :intent the
                          ;; only goal kind, root agreement refuses a design whose
                          ;; baseline roots elsewhere, so the two edges always name
                          ;; one chain and this clause answers for both.
                          (goal-moved goal-seq)
                          {:reason :goal-superseded :seq goal-seq
                           :replaced-by (goal-moved goal-seq)
                           :detail (str "the design serves the goal at entry " goal-seq
                                        ", which was superseded at entry "
                                        (goal-moved goal-seq) " after this design"
                                        " was written — it serves a goal nobody holds")}

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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  of-baseline
  "Whether `baseline` — a stamped :baseline record — is still footing a design
   may be written on: a round found it sufficient at exactly this number, and
   the goal it was scoped for has not been replaced since.

   The baseline rung's own reading of the fact `of-design` reaches through its
   premise, by the same walk. It is asked of the baseline because the case that
   needs it has no design to ask: a goal replaced after its survey was verified
   leaves the arc owing a design, and the append boundary refuses every design
   over that survey, and every review of it — either would stand on the
   replaced goal through it. So :verified? stops counting the sufficient
   verdict, and :blocked says why. What re-opens is the survey, not its
   verification: a reader that routes on :verified? alone asks for the one
   review nothing may write.

   :blocked's :replaced-by is the live goal, and it is the citation a baseline
   written under the amended goal carries. Whoever writes that survey reads the
   answer here rather than taking the newest intent, which is the recency the
   intent citation exists to refuse.

   Fails closed like `of-design`: a review or an intent the index claims and
   nobody can parse leaves verification indeterminate, never granted."
  [project ws-id baseline]
  (let [w (ws/read-ws project ws-id)]
    (if (nil? w)
      {:indeterminate? true :verified? false
       :blocked {:reason :no-workstream :detail (str "no workstream " ws-id)}}
      (let [revs (readable project ws-id w :baseline-review)
            ins  (readable project ws-id w :intent)]
        (if (some #{::unreadable} [revs ins])
          {:indeterminate? true :verified? false
           :blocked {:reason :unreadable-ledger
                     :detail (str "an entry standing depends on could not be read on "
                                  ws-id " — standing cannot be derived, so nothing "
                                  "may proceed on it")}}
          (let [seq-n       (:seq baseline)
                goal-seq    (get-in baseline [:intent :seq])
                moved       (goal-replaced ins goal-seq seq-n)
                sufficient? (boolean
                             (some #(and (= seq-n (:baseline-seq %))
                                         (report/verdict-holds (:verdict %)))
                                   revs))]
            (cond-> {:sufficient? sufficient?
                     :verified?   (and sufficient? (nil? moved))}
              moved (assoc :blocked
                           {:reason :goal-superseded :seq goal-seq
                            :replaced-by moved
                            :detail (str "the baseline at entry " seq-n
                                         " was scoped for the goal at entry " goal-seq
                                         ", superseded at entry " moved
                                         " — a baseline under the amended goal cites"
                                         " entry " moved)}))))))))

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
