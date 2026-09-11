(ns nido.review.report
  "The review report: a single immutable value that is BOTH the defined report
   shape and the per-round ledger. Built by folding the engine's typed events
   (apply-event). Pure — persistence is one explicit fn (persist!)."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]))

(def schema-version 1)

(defn ^{:malli/schema [:=> [:cat :map] :ReviewReport]}
  init
  "The empty report a run folds its events into.

   `:context` on the target says what this run could reach — the ledger, the
   cache, a design record, a stance. A review outside a nido session runs
   without any of them and reports exactly as a complete one does, so a thin run
   and a full one were indistinguishable from their reports and only the second
   was worth trusting.

   `:machinery` is the counterpart of `:target`: the target pins the code under
   review, this pins the code that did the reviewing. Beside rather than inside,
   because a run over nido's own repo would otherwise read as one fact where
   there are two. Not `:reviewer` — that word is already the codex process, and
   `:reviewer-unavailable` is a status about it. Supplied by the caller — see
   `nido.review.provenance/loaded-from` — because this namespace is pure and
   answering it means reading the classpath and asking jj."
  [{:keys [run-id cwd base started-at context machinery]}]
  {:schema     schema-version
   :run-id     run-id
   :status     "running"
   :target     (cond-> {:cwd cwd :base base :base-rev nil :files []}
                 context (assoc :context context))
   :machinery  machinery
   :started-at started-at
   :ended-at   nil
   :rounds     []
   :summary    nil
   ;; Filled by `finalize` from the terminal ctx; nil while the run is going and
   ;; nil at the end of one whose status is the whole story. See `stopped-on`.
   :reason     nil})

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  stopped-on
  "What the run stopped ON, read off its terminal ctx — as against `:status`,
   which is what it stopped AS. nil when the status says everything.

   `:unfixable` is what the loop gave up on, by the identity the pipeline tells
   findings apart with; `:parked` is every question still unanswered when it
   ended, oldest first, each with the round it was raised in. Both were on the
   ctx and reached nothing durable: the artifact for a run whose entire point
   was that it had something specific to hand over said `unfixable` and left the
   rest to be inferred from the last warden's prose.

   A park's identity is its handle, so it is the key of the carried map and is
   folded back into each entry — a list of anonymous questions is not a
   handover.

   `:drift` is the same failure caught earlier: the fix stage computes the
   revision the reviewers read and the one it found, refuses on the difference,
   and until this key existed neither number left the ctx. Which revision moved
   is the whole of what a reader can act on, and reconstructing it meant reading
   the stage source.

   `:standing` is the last warden's own list of what it knows is open and handed
   to nobody — no finding covers it, no fixer was launched at it, so nothing
   else in the run mentions it. The terminal ROUND's list rather than the union
   over the run, and that is the accurate reading: a standing item carries no id
   and no handle, so nothing in the loop can settle one and a union could only
   grow. Which is why the warden is asked for the whole list every round, and
   why a later round dropping an item is that warden's answer rather than a
   loss.

   Beside the warden's list, `:unplaced`: what the last run left owed that the
   terminal round could place on no layer of this stack — see
   `nido.review.stages/placed-on`. The loop's own entries rather than a
   warden's, because no warden is shown those rows, and a run that ends on a
   quiet round has no warden at all; they are still counted open, and without
   them here a row counted open would be named nowhere a person reads."
  [ctx]
  (let [parks    (get-in ctx [:carry :parks])
        standing (into [] (distinct) (concat (get-in ctx [:warden :standing])
                                             (:unplaced ctx)))]
    (not-empty
     (cond-> {}
       (seq (:unfixable ctx))
       (assoc :unfixable (vec (:unfixable ctx)))

       (:drift ctx)
       (assoc :drift (:drift ctx))

       (seq standing)
       (assoc :standing standing)

       (seq parks)
       (assoc :parked (->> parks
                           (map (fn [[handle p]] (assoc p :handle (str handle))))
                           (sort-by (juxt #(or (:since %) 0) :handle))
                           vec))))))

(defn ^{:malli/schema [:=> [:cat :ReviewReport] [:sequential :map]]}
  applied-reshapes
  "Every recut this run actually carried out, in round order, each carrying the
   round it happened in.

   Read off the folded report rather than off the terminal ctx, because a ctx is
   rebuilt every round: a run that folded two layers in round 2 and ended in
   round 5 holds nothing about the fold by the time it stops. The report is the
   only value that remembers the whole run.

   Applied ones alone. A reshape the stage refused becomes a park and travels
   with the other open findings, so what is missing everywhere outside
   report.json is the one that succeeded — the loop rewriting the branch under
   the person who asked it to review one."
  [report]
  (into []
        (comp (mapcat (fn [r] (map (fn [ph] [(:round r) ph]) (:phases r))))
              (filter (fn [[_ ph]] (= "reshape" (:phase ph))))
              (mapcat (fn [[round ph]]
                        (->> (:reshapes ph)
                             (filter :applied?)
                             (map #(assoc % :round round))))))
        (:rounds report)))

;; ---- round/phase helpers -------------------------------------------------

(defn- round-status
  "Derive a closed round's status from its phases. Order matters."
  [round]
  (let [phases (:phases round)
        ph     (fn [n] (last (filter #(= n (:phase %)) phases)))
        review (ph "review") warden (ph "warden") fix (ph "fix")
        judge  (ph "judge")  amend  (ph "amend")
        ;; Rows a reviewer actually opened. A skipped row is a converged layer
        ;; deliberately not re-read, which says nothing about whether the rest
        ;; of the round had anything in it.
        read-rows (remove #(= "skipped" (:status %)) (:layers review))
        nothing?  (and (seq read-rows)
                       (every? #(= "nothing-to-review" (:status %)) read-rows))]
    (cond
      (some #(= "error" (:status %)) phases)                       "failed"
      ;; A record round, whose two stages tell the same story the review's three
      ;; do: nothing left to say, something given up, or another round earned.
      (and judge (= "ok" (:status judge)) (empty? (:findings judge))) "clean"
      (and amend (seq (:retreats amend)))                          "weakened"
      (and judge amend)                                            "continued"
      judge                                                        "ended"

      ;; Before both readings below, which are the ones a round with no findings
      ;; and no warden otherwise falls into. This round has neither because it
      ;; never fanned out: the stack was holding conflict markers when it was
      ;; asked, so nothing was reviewed and "clean" would be the report asserting
      ;; a clean bill nobody issued.
      (seq (:conflicted review))                                   "stack-conflicted"

      ;; Before "clean", because the two are indistinguishable by findings
      ;; alone — both have none — and only this one had no reviewer read a line.
      (and review (= "ok" (:status review)) (nil? warden) nothing?) "nothing-to-review"
      (and review (= "ok" (:status review))
           (empty? (:findings review)) (nil? warden))              "clean"

      ;; Before both of the warden's own decisions, because the stage can end
      ;; the run over the warden's head: a park that has stood long enough stops
      ;; it whatever the warden returned. Read from the decision alone, the
      ;; round that ended a run printed `escalated` while the run printed
      ;; `unfixable`, and the two words came out of the same phase.
      (seq (:unfixable warden))                                    "unfixable"
      (= "escalate" (:decision warden))                            "escalated"

      ;; Before "continued", which is what a round holding a landed fix reads as
      ;; and what this one used to be called. A round that stopped on a conflict
      ;; it could not roll back continued nothing: the stack is holding markers,
      ;; and the fixers above the conflict were never launched.
      (seq (:conflicted fix))                                      "fix-conflicted"
      (and fix (seq (:fixes fix)))                                "continued"
      (= "stop" (:decision warden))                                "stopped"
      :else                                                       "ended")))

(defn- close-current-round
  "Close the last round if still running. ended-at is the last phase's ended-at
   (when the round actually finished) and falls back to `at` (the closing
   event's time) for a round with no completed phase."
  [report at]
  (let [idx (dec (count (:rounds report)))]
    (if (and (>= idx 0) (= "running" (get-in report [:rounds idx :status])))
      (let [round (get-in report [:rounds idx])
            end   (or (some-> (last (:phases round)) :ended-at) at)]
        (-> report
            (assoc-in [:rounds idx :status] (round-status round))
            (assoc-in [:rounds idx :ended-at] end)))
      report)))

(defn- open-round
  [report iter at]
  (if (some #(= iter (:round %)) (:rounds report))
    report
    (-> (close-current-round report at)
        (update :rounds conj {:round iter :status "running"
                              :started-at at :ended-at nil :phases []}))))

(defn- append-phase
  [report ph]
  (let [idx (dec (count (:rounds report)))]
    (update-in report [:rounds idx :phases] conj ph)))

(defn- update-current-phase
  [report phase-name f]
  (let [ridx   (dec (count (:rounds report)))
        phases (get-in report [:rounds ridx :phases])
        pidx   (last (keep-indexed (fn [i ph] (when (= phase-name (:phase ph)) i)) phases))]
    (if pidx (update-in report [:rounds ridx :phases pidx] f) report)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  in-stack-order
  "Rows as the stack has them: layers bottom→top by :index, the composition pass
   last.

   Ordering belongs here rather than in the renderer because report.json is the
   durable artifact — a renderer that sorted would leave every other reader of
   the file holding an order nobody chose. And the order nobody chose is what
   was there: rows arrived split into reviewed-then-skipped simply because
   `to-review` returns two vectors, so a layer appeared to leave the stack in
   the round it converged.

   `sort-by` is stable, so rows with no :index keep the order they came in —
   which is what a report written before :index existed relies on, and what
   keeps an unnumbered orphan next to its neighbours instead of at the front."
  [rows]
  (vec (sort-by (juxt #(if (:stack? %) 1 0) #(or (:index %) 0)) rows)))

(defn- row
  "A display row for `target`: its identity, its kind, its place in the stack,
   the change it sits on and the patch it is identified by, plus whatever this
   particular row reports.

   :index is OMITTED rather than nil when the target has none — the composition
   pass, and anything from a stack too short to have layers — so an unnumbered
   row is exactly the map it was before numbering existed. :change, :patch-hash
   and :range-hash are omitted the same way, so a target the cache cannot key
   says nothing rather than nil.

   :change is the layer's jj change id, which is what `layers/conflicted`
   reports a conflicted stack under. It is the only join between the two, and
   without it a fix-conflicted report names twelve-character ids and holds
   nothing that maps them to layers — so the ids can be resolved only against a
   branch the reader has not opened. The composition row has none, being no
   layer.

   :patch-hash is on every row that has one, REVIEWED as much as skipped. A
   skipped row needs it to justify the skip; a reviewed row needs it to explain
   why there was no skip, and that is the direction that costs agents — the
   report could prove a hit and said nothing about a miss. With it on both, `why
   didn't this skip?` is a comparison of two numbers across rounds, rather than
   an excavation of review-cache.edn, which lives outside the run and is
   overwritten by the next run on the same branch.

   :range-hash is the composition row's only other component: its :patch-hash is
   not a patch hash at all but a key derived from the range's patch and the cut
   (see stages/composition-key), and every layer's half of that cut is now the
   :patch-hash of its own row. Carrying the range's makes the derived key
   re-derivable from the report — so a composition that failed to skip names the
   component that moved instead of leaving a reader to infer it. Not carrying it
   is why a key that missed on every rebase survived fourteen review runs."
  [target extra]
  (let [change (get-in target [:layer :change])]
    (merge (cond-> {:label  (:label target)
                    :stack? (boolean (:stack? target))}
             (:index target)      (assoc :index (:index target))
             change               (assoc :change change)
             (:patch-hash target) (assoc :patch-hash (:patch-hash target))
             (:range-hash target) (assoc :range-hash (:range-hash target)))
           extra)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  review-layers
  "One entry per review target this round: what it found, or that it was not
   looked at because its patch had already converged. This is what makes the
   round legible per layer instead of as one number.

   A reporter that contributed findings but is no layer of the stack — the
   mechanical design reviewer — gets a row too. It is not in `:reviews`,
   because it reviews the worktree rather than a range and has neither a brief
   nor a manifest to put in the warden's table of contents. Without a row here
   its findings would still be listed while every counted row read zero, which
   is the one way a summary can be worse than no summary."
  [ctx]
  (let [counts   (frequencies (keep :from-layer (:findings ctx)))
        reviewed (into #{} (map (comp :label :target)) (:reviews ctx))
        skipped  (into #{} (map :label) (:skipped ctx))
        accounted (into reviewed skipped)]
    (in-stack-order
     (-> (mapv (fn [{:keys [target] :as r}]
                 ;; A target whose diff was empty is rowed as what it was, not
                 ;; as a review that found nothing: "reviewed · 0" is the same
                 ;; two words a genuinely clean layer earns, and the reader who
                 ;; most needs them apart is the one asking why a run was free.
                 (if (= :nothing-to-review (:status r))
                   (row target {:status "nothing-to-review"})
                   (row target {:status   "reviewed"
                                :findings (get counts (:label target) 0)})))
               (:reviews ctx))
         ;; On top of the patch every row carries, a skipped row says WHEN the
         ;; convergence it is standing on was recorded — a timestamp, and often
         ;; from a run hours before this one, since the cache outlives any single
         ;; run. Without it the report asserts a layer needed no review and
         ;; offers nothing to check that against, and a wrongly cached
         ;; convergence is precisely the failure that hides a finding for as long
         ;; as the layer sits unchanged.
         (into (mapv (fn [t] (row t (cond-> {:status "skipped"}
                                      (:converged-at t) (assoc :converged-at (:converged-at t)))))
                     (:skipped ctx)))
         (into (for [[label n] counts
                     :when (and label (not (contains? accounted label)))]
                 (row {:label label} {:status "reported" :findings n})))))))

(def ^:private ruling-keys
  "What one per-finding ruling keeps in the report.

   :handle and :same-as are the run's cross-round identity — what `no-progress?`,
   `unfixable` and the answered cache all key on. Without them a reader can see
   the same defect ruled on in four rounds and has nothing saying the loop knew
   it was one defect.

   :sweep is whether the warden read this finding as one instance of a class and
   ordered the fixer to audit for siblings. It is the difference between a round
   that repaired a line and a round that repaired a shape, so it is what the
   round after it has to be read against — a sibling reported next round is the
   sweep's scope being wrong, and an instance reported next round is the sweep
   not having happened.

   :duplicate-of is the finding a `duplicate` close repeats. The close holds its
   layer open for as long as that finding is owed, so whether the layer should
   have converged is answered by the target's ruling, and this is how a reader
   gets from one to the other."
  [:id :handle :same-as :owner-layer :disposition :authority :of :duplicate-of
   :because :sweep])

(defn- rulings
  "One entry per finding the warden's decision was applied to, projected to
   `ruling-keys`.

   Read off the RULED findings rather than off the parsed decision, because the
   parsed decision is only the INPUT to a ruling: `stages/apply-rulings` is what
   assigns the handle, and what rules a finding the warden passed over as :fix
   with a `:because` saying nobody ruled on it. Both are decisions this phase is
   the record of, and neither is on the answer the warden returned.

   A finding carries a disposition only once that merge has run, so a warden
   that could not answer — rate-limited, unparseable — reports no rulings here
   rather than a list of bare ids."
  [findings]
  (into [] (comp (filter :disposition) (map #(select-keys % ruling-keys))) findings))

(defn- finish-phase
  [ph phase ctx at]
  (let [ph (assoc ph :status "ok" :ended-at at)]
    (case phase
      ;; A record loop's two stages. What each keeps is what a reader of the
      ;; finished report has to be able to reconstruct: what the judge decided
      ;; and against what, and what the amendment cost.
      ;; :outcome as well as :verdict, and they are not alternatives to each
      ;; other: a verdict is what the judge decided, an outcome is why there is
      ;; no verdict. A phase that kept only the first renders a codex failure
      ;; exactly like a clean round.
      :judge  (assoc ph :verdict (some-> (get-in ctx [:record :verdict]) name)
                        :outcome (some-> (get-in ctx [:record :outcome]) name)
                        :findings (vec (:findings ctx)))
      ;; What the stage actually DID, not what its name suggests. An amend phase
      ;; that spent its round re-surveying and never reached an amendment must
      ;; not report itself as having amended anything.
      :amend  (assoc ph :retreats (vec (:retreats ctx))
                        :disputes (vec (:disputes ctx))
                        :amended? (boolean (:amended? ctx))
                        :resurveyed (some-> (:resurveyed ctx) name))

      ;; :conflicted is what ended THIS round, where the copy on the target is
      ;; what the stack looked like when the run last asked. A round that stopped
      ;; on it has no findings and no warden, so without it here the only two
      ;; facts left about the round say a reviewer read the branch and liked it.
      ;; :unplaced is what the last run left owed that this round could place on
      ;; no layer — handed to no reviewer, so it is in none of the rows above.
      ;; Per round for the reason the warden's `standing` is: the terminal
      ;; round's is what the run leaves, and the stack can move under the rest.
      :review (cond-> (assoc ph :overall-correctness (:overall-correctness ctx)
                             :findings (vec (:findings ctx))
                             :layers (review-layers ctx))
                (seq (:conflicted ctx)) (assoc :conflicted (vec (:conflicted ctx)))
                (seq (:unplaced ctx))   (assoc :unplaced (vec (:unplaced ctx))))
      ;; :cause as well as :reason, and they are not alternatives: a reason says
      ;; what was wrong with the answer, a cause says whether there WAS one. A
      ;; phase that kept only the first renders a 429 exactly like a malformed
      ;; ruling.
      ;; :rulings come off the round's ruled findings; see `rulings`.
      ;; :unfixable is the stage overruling the warden — a park that has stood
      ;; too long ends the run whatever the warden decided. It belongs on this
      ;; phase because the phase is the only place both facts sit, and a round
      ;; carrying only the overruled decision is the report contradicting the
      ;; run in its own words.
      ;; :promoted is what the warden RAISED, as against what it ruled. It is on
      ;; this phase because this is the only phase that can carry it: the review
      ;; phase folded before these findings existed, and a ruling row is an id
      ;; and a disposition — enough to say what was decided about a finding, not
      ;; enough to say what the finding was. Without the title, file and line
      ;; here, a defect the run raised, repaired and reported reads out of the
      ;; report as a ruling on nothing.
      :warden (let [a (:warden ctx)]
                 (cond-> (assoc ph :decision (some-> (:decision a) name)
                                :cause (some-> (:cause a) name)
                                :reason (:reason a)
                                :rulings (rulings (:findings ctx)))
                   (seq (:promoted ctx)) (assoc :promoted (vec (:promoted ctx)))
                   ;; Per round, because it is a judgement that round made and a
                   ;; later one may not repeat: only the terminal round's list is
                   ;; what the run leaves behind, and a reader asking why an item
                   ;; stopped being named needs the round it was last named in.
                   (seq (:standing a)) (assoc :standing (vec (:standing a)))
                   (seq (:unfixable ctx)) (assoc :unfixable (vec (:unfixable ctx)))))
      ;; The finding ids a fixer was handed, not only how many. It is the join
      ;; every cross-round question needs — did this fix stop that finding coming
      ;; back — and the report held one side of it and threw the other away.
      ;; :declined comes off the ctx rather than the history entry, because a
      ;; round in which EVERY fixer declined writes no history entry at all —
      ;; which is exactly the round whose reasons a reader needs.
      ;; :rolled-back beside them, because a repair the stack refused leaves no
      ;; trace anywhere else: the commit is gone, the fixer's log says it
      ;; succeeded, and the findings come back next round looking untouched.
      ;; :conflicted and :unattempted are the account of an abort: what the stack
      ;; is holding, and which layers the stage was still going to reach. Without
      ;; the second, the only record of a fixer that never ran is the ABSENCE of
      ;; its fix-<layer>-round-N.err.log from the run dir — so the lists together
      ;; are what makes the phase add up to every :fix ruling it held.
      ;; :launch-failed is a layer whose fixer was launched and never started,
      ;; with the exit code — that and its err.log are all anyone has of why.
      :fix    (let [h (last (filter #(= (:iter ctx) (:iter %)) (:history ctx)))]
                (cond-> (assoc ph :fixes (vec (:fixes h)) :fixed-count (:fixed-count h))
                  (seq (:declined ctx))    (assoc :declined (vec (:declined ctx)))
                  (seq (:launch-failed ctx)) (assoc :launch-failed (vec (:launch-failed ctx)))
                  (seq (:rolled-back ctx)) (assoc :rolled-back (vec (:rolled-back ctx)))
                  (seq (:conflicted ctx))  (assoc :conflicted (vec (:conflicted ctx)))
                  (seq (:unattempted ctx)) (assoc :unattempted (vec (:unattempted ctx)))))
      ;; What the stage decided about each recut, whether or not it could act.
      ;; Kept because a reshape is the only remedy a recut has — the warden
      ;; withholds it from the fixers — so an empty reshape phase is the report
      ;; saying nothing about the one path the finding was left.
      :reshape (assoc ph :reshapes (vec (:reshapes ctx)))
      ph)))

(defn- resolve-target
  "Record what the round is about to review, and seed one row per target on the
   running review phase.

   This replaces deriving the same three values from the whole-stack target's
   RESULT. They were never results: the fork point, the target list and the
   manifest are all known before an agent starts, and reading them off the
   slowest target of the round meant a watcher learned what was under review
   only once it no longer mattered — and an interrupted run never learned it.

   The seeded rows are put in stack order here, the same way the finished
   payload is, so a target sits in the same place from the moment it is named to
   the moment it reports."
  [report {:keys [base-rev files targets]}]
  (-> report
      (assoc-in [:target :base-rev] base-rev)
      (assoc-in [:target :files] (vec files))
      (assoc-in [:target :layers] (count (remove :stack? targets)))
      (update-current-phase
       "review"
       (fn [ph] (assoc ph :layers (in-stack-order targets))))))

(defn- record-conflicts
  "Put the round's conflict preflight on the target, whatever it said.

   Written even when it is empty, and that is the point: `[]` is the stack read
   and found clean, and an ABSENT key is a run that never got an answer — a
   review outside a jj workspace, or a report written before this was asked.
   Four consecutive runs on one branch ended holding the same two change ids
   with no artifact saying whether they had been standing since the run before,
   which is the question that decides whether anything upstream can help."
  [report {:keys [conflicted]}]
  (assoc-in report [:target :conflicted] (vec conflicted)))

(def ^:private row-rank
  "How far along a row is. A row only ever moves forward: events cross threads
   and can be folded out of order, and a target that flickered back to `running`
   after reporting would be the display lying about work that is done.

   Rank 2 is `this row has stopped`, and it is four different endings — an
   answer, a cache hit, a failure, an empty diff — plus the two stamps no event
   produces: `close-unfinished-round` writes `orphaned-status` on a row whose run
   died and `interrupted-status` on one whose run was stopped. Which of them mean
   the run ANSWERED for the target is `read-statuses`, a narrower question this
   map deliberately does not answer."
  {"pending" 0 "running" 1
   "reviewed" 2 "skipped" 2 "error" 2 "nothing-to-review" 2
   "orphaned" 2 "interrupted" 2})

(defn- terminal-row?
  "Whether a target row has stopped moving.

   A status `row-rank` does not name reads as still in flight, which is the safe
   direction: the fold only ever moves a row forward, so an unknown status is
   treated as one an answer may still overwrite."
  [row]
  (<= 2 (row-rank (:status row) 0)))

(defn- move-target
  "Advance one target's row on the running review phase.

   Rows are matched by label, which is unique within a round: it is the layer's
   bookmark slug, or `stack` for the composition pass."
  [report {:keys [label status findings]}]
  (update-current-phase
   report "review"
   (fn [ph]
     (update ph :layers
             (fn [rows]
               (mapv (fn [row]
                       (if (and (= label (:label row))
                                (> (row-rank status 0) (row-rank (:status row) 0)))
                         (cond-> (assoc row :status status)
                           findings (assoc :findings findings))
                         row))
                     (vec rows)))))))

(defn- fix-rows-with-a-fixer
  "Every row of a fix phase that names a fixer which RAN.

   The phase's lists are one account of the round's `:fix` rulings, and three of
   them are a fixer that ran: `:fixes` and `:rolled-back` are repairs it wrote —
   kept and refused — and `:declined` is one that wrote nothing. The other two
   name no fixer. `:launch-failed` is a launch claude refused before a turn, and
   `:unattempted` is what a layer was OWED when the stage aborted below it.

   The same line `nido.review.stages/repair-attempted?` draws for the give-up
   counter, off the same reading in the fix stage: the count a run publishes and
   the count that ends it are of one set of attempts.

   A `:declined` row carrying `:ran? false` is a launch that never started, in a
   report.json written before those had a list of their own — and such a report
   is still re-summarized, when a later run settles it as an orphan."
  [ph]
  (concat (:fixes ph) (:rolled-back ph) (remove #(false? (:ran? %)) (:declined ph))))

(defn- fix-attempts
  "How many repairs the run DISPATCHED — one per finding per round it was handed
   to a fixer in, so a finding handed out in three rounds counts three times.

   Counted off `:handed`, which every row naming a launched fixer carries and
   which is the same list the fixer's prompt was built from. `:fixed-count` is
   the wrong source and cannot be made right: only a LANDED fix carries it, so a
   round whose fixer wrote nothing — because it refused, because the stack
   rolled its repair back, or because its budget killed it mid-verification —
   sums to zero, and two findings handed to a fixer that ran for thirty minutes
   publish as `0 repairs dispatched`. That is the same conflation the naming
   rule below guards against, reached from the other side: this field means
   asked-for, and a source that only a completed repair writes can only ever
   mean finished.

   Not how many defects were removed, which is a fact no stage in the loop
   produces. A fixer reporting success is a fixer's claim about its own work;
   what turns that into evidence is the next round re-reviewing the layer and
   not raising the finding again, and a run's LAST fix round has no such round
   after it. `verdict/settled-by-fixing` is the count with that evidence behind
   it.

   The name is load-bearing, and the obvious one is a lie: anything with `fixed`
   in it reads as defects removed, and this is nearly double that on any run
   whose findings took more than one round to settle. Publish it under such a
   name and every reader downstream — a ledger entry, a dashboard card, an
   analysis — states it as work the run finished."
  [report]
  (->> (:rounds report)
       (mapcat :phases)
       (filter #(= "fix" (:phase %)))
       (mapcat fix-rows-with-a-fixer)
       (map #(count (:handed %)))
       (reduce + 0)))

(def ^:private read-statuses
  "The row statuses that say THIS run answered for the target: a reviewer came
   back, a reviewer failed, or the diff was empty and there was nothing to come
   back about.

   Every other status is a target this run did not answer for, for one of two
   reasons. `skipped` is the convergence cache answering instead. `pending`,
   `running`, `orphaned` and `interrupted` are a run that stopped before it
   could — and reading them as read is what `not= \"skipped\"` did, which held
   only for as long as every row reaching `coverage` had finished."
  #{"reviewed" "error" "nothing-to-review"})

(defn ^{:malli/schema [:=> [:cat :ReviewReport] :map]}
  coverage
  "How much of the stack this run READ, as `{:reviewed n :skipped n}` over
   distinct target labels — the composition pass included, since it is a target
   like any other.

   A skipped target is one the convergence cache already held at this exact
   patch, so the loop declined to re-open it: the verdict on it is remembered
   from an earlier run rather than reached in this one. Skipped in EVERY round
   is what counts as skipped, because a layer re-read once and converged after
   was read here.

   THE TWO DO NOT PARTITION THE TARGETS, and a run that stopped is where the gap
   opens: a target left `pending`, `running`, `orphaned` or `interrupted` was
   neither read here nor remembered from before, so it is counted in neither
   number. `:reviewed` is the load-bearing one — two gates spend an agent session
   on the strength of it being positive — and inflating it with rows nobody ever
   opened is a claim about work that did not happen.

   The pair is what separates a branch reviewed clean from one mostly remembered
   clean. Without it a `clean` verdict over three targets out of eight is
   recorded identically to one over all eight, and the difference is the whole of
   what the verdict is worth. It is derived from the report rather than from the
   terminal ctx for the same reason `applied-reshapes` is — a ctx holds the round
   it is in, and this is a question about the run."
  [report]
  (let [by-label (->> (:rounds report)
                      (mapcat :phases)
                      (filter #(= "review" (:phase %)))
                      (mapcat :layers)
                      (group-by :label))
        statuses (fn [[_ rows]] (map :status rows))
        read?    (fn [group] (boolean (some read-statuses (statuses group))))
        skipped? (fn [group] (every? #(= "skipped" %) (statuses group)))]
    {:reviewed (count (filter read? by-label))
     :skipped  (count (filter skipped? by-label))}))

(defn- finalize
  [report status ctx at]
  (let [s (name status)]
    (assoc report
           :status   s
           :ended-at at
           :reason   (stopped-on ctx)
           :summary  {:rounds       (count (:rounds report))
                      :fix-attempts (fix-attempts report)
                      :final-status s})))

(def orphaned-status
  "What a run whose process vanished is recorded as.

   Not one of the loop's own terminal statuses — `nido.review.loop/engine-statuses`
   and `nido.review.stages/stage-statuses` are what a run REACHES, and a run that
   reaches nothing is what this names. It is assigned from outside, by whoever
   next takes the workstream's claim, and so it is deliberately absent from the
   ledger's ReviewReport enum: no `:review` entry is ever written on it."
  "orphaned")

(def interrupted-status
  "What a run a person stopped is recorded as.

   Reaches no ledger entry either, for the same reason as `orphaned-status`, and
   states a different fact. An orphan is a run nobody can account for, stamped
   later by whoever next took the claim; an interrupt is the run itself saying it
   was told to stop, written from the shutdown hook while it still knows. Kept
   apart so a reader can tell a decision from an accident — a crash, a SIGKILL
   and a closed lid all still arrive as `orphaned`, because none of them runs any
   nido code on the way out."
  "interrupted")

(def rewriting-phase
  "The one phase that REWRITES the tree rather than reading it.

   Named rather than spelled twice because two readers turn on it and they are
   one decision: `interrupted` refuses to close a run stopped here, and
   `nido.review.reconcile/fixing?` is what then refuses the next claimant. A
   repair in flight is the one thing a stopped run leaves behind that the next
   run has to be told about, and a report still saying `running` is the only
   channel that tells it."
  "fix")

(defn ^{:malli/schema [:=> [:cat :ReviewReport] [:maybe :map]]}
  in-flight
  "The round, and the phase within it, this report was still in when it was last
   written — nil for a report whose run closed its own rounds.

   `some?` on it is the question `did this run end`, asked of the value rather
   than of `:status`: a run finalizes by closing its round and stamping a status
   in one fold, so the two cannot disagree, and this is the half that also says
   WHERE it stopped.

   The phase is the part a later claimant acts on. Every phase but `fix` reads
   the tree; `fix` launches agents that rewrite it, and those agents outlive the
   process that launched them — so a report stopped there is the branch having
   been left mid-repair by nobody.

   A round between phases has no phase in flight, and a run that died before its
   first phase-started has no round: both say `nothing was in progress`, which is
   what a caller needs to hear."
  [report]
  (let [round (last (:rounds report))]
    (when (= "running" (:status round))
      (let [ph (last (:phases round))]
        (cond-> {:round (:round round)}
          (= "running" (:status ph)) (assoc :phase (:phase ph)))))))

(defn- close-unfinished-round
  "Close a round whose run never came back — every phase still open in it, and
   every target row still in flight inside those phases, stamped `status`.

   Nothing is left saying `running`, because `running` is what the renderer
   draws a spinner for: left alone, the final frame of a terminal report
   animates a stage that stopped hours ago. That argument reaches the rows as
   well as the phases, and it is not the only one that does. A row is also a
   COUNT: `coverage` reads the rows to say how much of the stack this run
   answered for, and a `pending` row inside a terminal report is a target
   claimed as covered by a run that never opened it.

   Restamped, not dropped. The round named every target before it reviewed any
   of them, so the rows are what say what this run set out to read — which is
   the one thing a run that stopped can still tell anybody."
  [round status at]
  (letfn [(close-row [row]
            (cond-> row
              (not (terminal-row? row)) (assoc :status status)))
          (close-phase [ph]
            (if (= "running" (:status ph))
              (cond-> (assoc ph :status status :ended-at at)
                (seq (:layers ph)) (update :layers #(mapv close-row %)))
              ph))]
    (-> round
        (assoc :status status :ended-at at)
        (update :phases #(mapv close-phase %)))))

(defn- forced-terminal
  "`report` stamped terminal as `status`, for a run there is no terminal ctx to
   read.

   The counterpart of `finalize` for such a run: what it stopped ON is not a
   judgement it reached but the phase it was in, so `:reason` carries `in-flight`
   under the status's own key where a finished run carries `stopped-on`.
   Everything else is derived from the rounds already folded, which is all the
   evidence there is.

   Applied to a report that already ended it would overwrite a real verdict, so
   every caller establishes first that the run never wrote one."
  [report status at]
  (let [flight (in-flight report)]
    (cond-> (assoc report
                   :status   status
                   :ended-at at
                   :reason   (when flight {(keyword status) flight})
                   :summary  {:rounds       (count (:rounds report))
                              :fix-attempts (fix-attempts report)
                              :final-status status})
      flight (update-in [:rounds (dec (count (:rounds report)))]
                        close-unfinished-round status at))))

(defn ^{:malli/schema [:=> [:cat :ReviewReport :string] :ReviewReport]}
  orphaned
  "`report` forced to a terminal state, for a run that stopped without writing
   one and left nobody to say so.

   `at` is when the run was last OBSERVED — the newest write in its run dir, not
   now. A dead run's `:ended-at` is a fact about the run rather than about the
   process that noticed, and stamping the reconciler's clock would date every
   orphan to whenever somebody next reviewed the branch.

   Idempotent in the sense that matters: applied to a report that already ended,
   it would overwrite a real verdict, so the caller asks `in-flight` first."
  [report at]
  (forced-terminal report orphaned-status at))

(defn ^{:malli/schema [:=> [:cat :ReviewReport :string] [:maybe :ReviewReport]]}
  interrupted
  "`report` forced to a terminal state by the run itself, on being stopped — or
   nil when it must be left to the reconciler instead.

   `at` is now, and unlike `orphaned`'s that is the run's own clock: this is a
   live process recording its own end, so there is no gap between when it
   stopped and when anyone noticed.

   nil in the two cases where writing would say something false. A report
   already carrying a terminal status belongs to a run that finished and is
   doing its post-processing, and stamping over it would discard the verdict it
   reached. A run stopped in its `rewriting-phase` left the tree mid-repair, and
   the report saying `running` is what tells the next claimant so — closing it
   here would hand that claimant a branch nobody signed off, silently."
  [report at]
  (when (and (= "running" (:status report))
             (not= rewriting-phase (:phase (in-flight report))))
    (forced-terminal report interrupted-status at)))

;; ---- fold ----------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :ReviewReport :map] :ReviewReport]}
  apply-event
  "Fold one engine event into the report.

   AN INTERRUPTED REPORT IS FINAL and every later event is dropped. It is the
   one status written from off the engine's own thread: the shutdown hook stamps
   it and then destroys the reviewer children, which unblocks that thread to
   spend the reap's five-second grace unwinding. Everything that unwinding emits
   describes a run already stopped — and `:run-finalized` among it would restate
   the interrupt as a verdict the loop reached, which is the one reading the
   status exists to prevent."
  [report {:keys [event] :as ev} _clock]
  (if (= interrupted-status (:status report))
    report
    (case event
      :run-started
      ;; Constructs the report when nothing seeded it — `report` is nil for a
      ;; caller that folds from scratch — but must not discard what did. Only the
      ;; caller can know `:context` and `:machinery`; the loop that emits this
      ;; event knows neither, so both are read back off the report being replaced.
      (init {:run-id     (:run-id ev)
             :cwd        (:cwd ev)
             :base       (:base ev)
             :started-at (:at ev)
             :context    (get-in report [:target :context])
             :machinery  (:machinery report)})

      :phase-started
      (-> report
          (open-round (:iter ev) (:at ev))
          (append-phase {:phase (name (:phase ev)) :status "running"
                         :started-at (:at ev) :ended-at nil}))

      :phase-finished
      (let [ctx (assoc (:ctx ev) :iter (:iter ev))]
        (update-current-phase report (name (:phase ev))
                              #(finish-phase % (:phase ev) ctx (:at ev))))

      :stack-conflicts
      (record-conflicts report ev)

      :targets-resolved
      (resolve-target report ev)

      :target-moved
      (move-target report ev)

      :phase-errored
      (update-current-phase report (name (:phase ev))
                            #(assoc % :status "error" :error (:error ev)
                                      :ended-at (:at ev)))

      :run-finalized
      (-> report
          (close-current-round (:at ev))
          (finalize (:status ev) (:ctx ev) (:at ev)))

      ;; The one event no stage emits: it arrives from the shutdown hook, on the
      ;; hook's own thread, and is folded here so that it is serialized with the
      ;; engine's events and persisted by the same writer. `interrupted` answers
      ;; nil for a run that must be left to the reconciler, and a no-op fold is
      ;; how that refusal reaches the report — unsealed, still `running`.
      :run-interrupted
      (or (interrupted report (:at ev)) report)

      report)))

;; ---- the design verdict --------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :ReviewReport :map] :ReviewReport]}
  with-verdict
  "The report, carrying what became of the design-verdict pass.

   Applied to an already-finalized report rather than folded as an event,
   because the pass judges the whole run and so cannot start until the run has
   ended. `:ended-at` is deliberately left where it was — it is when the REVIEW
   ended, and moving it would silently redefine the field for every report ever
   written.

   `outcome` describes the pass and not what it decided. `:outcome` is what the
   pass returned (`:answered`, `:no-answer`, `:skipped` — the pass never
   launched, and `:because` says what stopped it), `:ledger` is whether the
   workstream took the verdict (`:appended`, `:refused`, `:no-workstream`), and
   either half can be the one that went wrong: a verdict the ledger refuses is
   as lost as a verdict that was never produced, and both used to leave one
   stderr line. The verdict value is carried whole, byte-identical to what the
   ledger is offered, so a refusal can be diagnosed from the report alone.

   Keywords are written out as strings to match the rest of the report, whose
   statuses have been strings since it was a JSON document."
  [report {:keys [outcome ledger because verdict]}]
  (assoc report :design-verdict
         (cond-> {:outcome (name outcome)}
           ledger  (assoc :ledger (name ledger))
           because (assoc :because because)
           verdict (assoc :verdict verdict))))

(defn ^{:malli/schema [:=> [:cat :ReviewReport] [:maybe :map]]}
  verdict-summary
  "What the design verdict DECIDED, in the two facts that fit somewhere a reader
   who cannot open the report will look: `{:design-verdict \"strained\"
   :verdict-implementation 2}`. nil when the pass produced no verdict at all.

   `with-verdict` beside it records what became of the PASS, which is a different
   question and a longer answer — the verdict travels whole so a ledger refusal
   can be diagnosed from the report alone. This is the headline.

   The count is of findings the judge laid at the IMPLEMENTATION's door: real
   defects on the branch that the design does not explain away. `:design` and
   `:stance` name work on the record rather than on the code, and `:baseline`
   says the survey was wrong — none of them is repair the loop failed to
   dispatch, which is what this number is asked about.

   Shape-agnostic on the classification and the verdict alike, because the same
   value is a keyword in the process that folded it and a string once the report
   has been through JSON, and a reader that silently answered 0 for the second
   would be wrong exactly where the report outlived its process."
  [report]
  (let [v (get-in report [:design-verdict :verdict])]
    (when-let [k (:verdict v)]
      {:design-verdict         (name k)
       :verdict-implementation (count (filter #(= "implementation" (some-> (:as %) name))
                                              (:findings-classified v)))})))

;; ---- persistence ---------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :ReviewReport :Path] :any]}
  persist!
  "Atomically write `report` to `path` as pretty JSON: write <path>.tmp then
   rename over `path`, so a concurrent reader never sees a half-written file."
  [report path]
  (let [tmp (str path ".tmp")]
    (fs/create-dirs (fs/parent path))
    (spit tmp (json/generate-string report {:pretty true}))
    (fs/move tmp path {:replace-existing true :atomic-move true})))
