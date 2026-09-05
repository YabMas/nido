;; src/nido/review/verdict.clj
(ns nido.review.verdict
  "The design verdict: one pass after the review rounds terminate, asking whether
   the findings were the ironing-out of implementation details of a sound design,
   or evidence the design itself is wrong.

   Distinct from the in-loop warden in two ways. The warden decides whether to spend
   another fix round and is report-only (`:tools \"\"`), so it cannot read code —
   it reasons purely from what the record says. This pass is asked whether the
   design survived contact with the code, which cannot be answered without looking
   at it, so it runs with tools."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.report :as report]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.review.stages :as stages]))

(def ^:private fenced-json-re #"(?s)```json\s*(\{.*?\})\s*```")

(def ^:private verdicts #{:sound :strained :invalidated :standing-challenged})

(defn- bullets [xs] (str/join "\n" (map #(str "- " %) xs)))

(defn- baseline-section
  "The second yardstick. `baseline` is the :baseline record the design cited (nil
   for a pre-baseline design); `relation` is what the design declared about it.

   Two different checks live here, and the prompt keeps them apart. Whether the
   change RESPECTED its declared relation is a claim about the change: it said
   :within, so every load-bearing property should still stand. Whether the
   baseline was ACCURATE is a claim about the premise, and a design can be sound
   while resting on a baseline that was wrong — a different failure with a different
   remedy, which is why it gets its own classification rather than being rounded
   to the design being wrong."
  [baseline relation]
  (when baseline
    (str "\nWHAT THE AREA ALREADY WAS — the baseline this change was judged\n"
         "against, baselined BEFORE the design was written. These are properties\n"
         "the code relied on beforehand, with where they were read:\n"
         (str/join "\n"
                   (map #(str "- " (:property %)
                              " [" (str/join ", " (:evidence %)) "]")
                        (:load-bearing baseline)))
         "\n\n"
         "The change declared itself " (str/upper-case (name (:relation relation)))
         " this design"
         (case (:relation relation)
           :within  ", meaning it needs NONE of those properties to change.\n"
           :extends ", meaning it adds without contradicting any of them.\n"
           :revisit (str ", and named these as the ones it has to break:\n"
                         (bullets (:breaks relation)) "\n"))
         "\n"
         "So there are two things to check that the invariants alone cannot tell\n"
         "you:\n"
         "1. Did it honour that declaration? A load-bearing property broken\n"
         "   WITHOUT being named in the declaration is the design failing to be\n"
         "   what it said it was — report it in load_bearing_broken. A property\n"
         "   the change named and broke on purpose is NOT a finding.\n"
         "2. Was the baseline right? If a finding shows a stated property is\n"
         "   simply not true of the code, the design may be sound and the BASELINE\n"
         "   wrong. Classify that finding as \"baseline\" — it means re-survey,\n"
         "   not redesign, and the two must not be confused.\n"
         "Populate load_bearing_held with the properties this round confirmed\n"
         "still stand, for the same reason invariants_held exists.\n")))

(defn- phase-section
  "What a phase plan changes about the question being asked, or nil when the
   design lands in one go.

   Without this the verdict pass judges the middle of a migration against the end
   of it. A phased design's intermediate states are correct BY DESIGN — during an
   expand/migrate/contract, \"there is exactly one writer\" is deliberately untrue
   for the whole middle phase — so an :on-completion invariant that does not hold
   yet is the plan working, not the design failing.

   The pass is not told WHICH phase is current, because nothing tracks that yet.
   That is a real limit and the prompt says so rather than inviting a guess: an
   :on-completion invariant is reported on only when the code shows the last
   phase has landed and it still does not hold."
  [phases]
  (when (seq phases)
    (str "\nTHIS DESIGN LANDS IN " (count phases) " PHASES, not one. Each phase is a\n"
         "separate deploy that the system has to be able to live in:\n"
         (str/join "\n"
                   (map-indexed
                    (fn [i {:keys [claim habitable exit]}]
                      (str (inc i) ". " claim
                           "\n   while live: " habitable
                           "\n   moves on when: " (:criterion exit)))
                    phases))
         "\n\n"
         "An invariant marked \"holds on completion\" is NOT expected to hold before\n"
         "the last phase lands. Finding that one does not hold yet is the plan\n"
         "working as written — do not report it as broken. Report it only if the\n"
         "code shows the final phase has landed and it still does not hold.\n"
         "Invariants marked \"holds always\" are the ones that must be true at\n"
         "EVERY phase boundary, including this one; judge those normally.\n")))

(defn- prior-verdict-section
  "The standing answer — what this pass last concluded about this same design
   record — and what a fresh pass owes it.

   Every input this judgment rests on moves slowly or not at all: the design
   record, the baseline it cites, the project stance. Only the run's findings
   are new. Without the standing answer a branch reviewed six times produces six
   independent opinions rather than one held position: each rewrites the
   outstanding question in fresh prose, so a reader of the ledger sees six
   decisions where there is one, and two of them can contradict each other about
   whether an invariant is broken with neither knowing the other exists.

   Offered as a default to confirm or overturn, never as a ruling to defer to —
   the same footing `prompts/answered-block` puts an earlier round's closes on.
   The design's own :rejected list does this for alternatives somebody else
   proposed; nothing did it for a decision this pass raised itself."
  [prior]
  (when prior
    (str "\nWHAT YOU CONCLUDED LAST TIME, about this same design record, after\n"
         "round " (:round prior) " — verdict " (name (:verdict prior)) ":\n"
         (:reason prior) "\n"
         (when-let [b (seq (:invariants-broken prior))]
           (str "It named these invariants contradicted:\n"
                (bullets (map #(str (:invariant %) " — by " (:finding %)) b)) "\n"))
         (when-let [b (seq (:load-bearing-broken prior))]
           (str "And these load-bearing properties broken without being declared:\n"
                (bullets (map #(str (:invariant %) " — by " (:finding %)) b)) "\n"))
         (when-let [n (:needs prior)]
           (str "It left this outstanding, and nobody has answered it:\n" n "\n"))
         "\n"
         "That verdict STANDS unless this round moved it, and your job is to say\n"
         "which:\n"
         "- Nothing moved: reach the same verdict and say so in a line. Do NOT\n"
         "  restate the outstanding question in new words. It is above, it is\n"
         "  still open, and rewriting it every run makes one standing decision\n"
         "  read as several.\n"
         "- Something moved: name WHAT — a finding that contradicts it, an\n"
         "  invariant this round confirmed, a boundary since repaired — and\n"
         "  reach the verdict that follows from it.\n"
         "Overturning it is allowed. Re-deriving it from scratch is not.\n")))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  build-prompt
  "The verdict prompt. `design` is the workstream's :design record, `baseline` the
   :baseline record it cited (nil when it predates them), `findings` the findings
   still open at the end, `history` the per-round digest, `prior` the last
   verdict against this same design record (nil when there is none)."
  [{:keys [design baseline stance findings history rounds prior]}]
  (str
   "You are judging whether a DESIGN survived a code review, not whether the code\n"
   "is correct. The review loop has finished; the fixes it wanted are already in.\n\n"
   "Read the code where you need to — you have tools, and the question cannot be\n"
   "answered from the diff summary alone.\n\n"
   "THE DESIGN THIS CHANGE COMMITTED TO:\n"
   "Shape: " (:shape design) "\n"
   "Invariants:\n"
   (bullets (map (fn [i]
                   (let [{t :invariant h :holds} (report/invariant i)]
                     (str t " [holds " (name h) "]")))
                 (:invariants design)))
   "\n"
   (phase-section (:phases design))
   (when-let [r (seq (:rejected design))]
     (str "Already rejected (a finding re-proposing one of these is ANSWERED,\n"
          "not evidence against the design — unless the reason no longer holds):\n"
          (bullets (map #(str (:alternative %) " — because " (:why-not %)) r)) "\n"))
   (when-let [a (seq (:assumes design))]
     (str "What this change ASSUMED about the area's current design. If a finding\n"
          "shows an assumption was false, the design may have been sound and the\n"
          "premise wrong — say so, it is a different failure:\n"
          (bullets (map :about a)) "\n"))
   (baseline-section baseline (:baseline design))
   "\n"
   (when stance
     (str "PROJECT STANCE — framing only, never cite it against a specific\n"
          "finding:\n" stance "\n\n"))
   "Rounds run: " rounds "\n"
   "Round history (findings + what was fixed):\n" (pr-str history) "\n\n"
   "Findings still open at the end. Each carries the reach the reviewer assigned:\n"
   "local (a defect inside the current design), structural (about where a\n"
   "boundary sits — the reviewer saw shape without intent), or unclear. The\n"
   "structural ones are what you are really here to adjudicate: the reviewer\n"
   "could not see the design, and you can.\n"
   (if (seq findings)
     (->> findings
          (map-indexed (fn [i f] (str i ": [P" (:priority f) "/"
                                      (name (or (:reach f) :unclear)) "] "
                                      (:title f) " — " (:body f))))
          (str/join "\n"))
     "(none)")
   "\n"
   ;; Last, so the judge reads this round's evidence before it is reminded what
   ;; it already decided — the standing answer is what the new evidence is
   ;; weighed against, not the frame it is read through.
   (prior-verdict-section prior)
   "\n"
   "Return EXACTLY one fenced ```json block, nothing after it:\n"
   "{\"verdict\": \"sound|strained|invalidated|standing_challenged\",\n"
   " \"reason\": \"...\",\n"
   " \"invariants_held\": [\"...\"],\n"
   " \"invariants_broken\": [{\"invariant\": \"...\", \"finding\": \"...\"}],\n"
   " \"load_bearing_held\": [\"...\"],\n"
   " \"load_bearing_broken\": [{\"invariant\": \"...\", \"finding\": \"...\"}],\n"
   " \"findings_classified\": [{\"finding\": \"...\", \"as\": \"implementation|design|stance|baseline\"}],\n"
   " \"needs\": \"...\"}\n\n"
   "- sound: the findings were implementation details. THIS IS THE EXPECTED\n"
   "  OUTCOME. Populate invariants_held with the ones this round actually\n"
   "  confirmed — that is the point of the verdict, not a formality.\n"
   "- strained: the design holds, but a boundary is visibly under pressure —\n"
   "  findings clustering on one seam, the same argument recurring. Ship it, but\n"
   "  say where the pressure is.\n"
   "- invalidated: the findings contradict a named invariant; the design itself\n"
   "  is wrong. REQUIRES invariants_broken and needs.\n"
   "- standing_challenged: the finding is right, the change is right, and the\n"
   "  PROJECT STANCE is what needs to move. Rare. REQUIRES needs.\n\n"
   "Do not reach for invalidated because the review was noisy. A design is only\n"
   "invalidated when you can name the invariant that cannot hold."))

(defn ^{:malli/schema [:=> [:cat :string :any :any] :map]}
  parse
  "Last fenced ```json block -> verdict map, or nil when absent/unparseable/unknown.
   nil is a non-answer, not a verdict: the caller records nothing rather than
   inventing one."
  [text round design-seq]
  (when-let [body (some-> (when (string? text) (last (re-seq fenced-json-re text)))
                          second)]
    (try
      (let [m (json/parse-string body true)
            v (keyword (str/replace (str (:verdict m)) "_" "-"))]
        (when (verdicts v)
          (cond-> {:format :design-verdict
                   :verdict v
                   :round round
                   :design-seq design-seq
                   :reason (str (:reason m))}
            (seq (:invariants_held m))
            (assoc :invariants-held (mapv str (:invariants_held m)))

            (seq (:invariants_broken m))
            (assoc :invariants-broken
                   (mapv #(-> {:invariant (str (:invariant %))
                               :finding   (str (:finding %))})
                         (:invariants_broken m)))

            (seq (:load_bearing_held m))
            (assoc :load-bearing-held (mapv str (:load_bearing_held m)))

            (seq (:load_bearing_broken m))
            (assoc :load-bearing-broken
                   (mapv #(-> {:invariant (str (:invariant %))
                               :finding   (str (:finding %))})
                         (:load_bearing_broken m)))

            (seq (:findings_classified m))
            (assoc :findings-classified
                   (into []
                         (keep #(let [as (keyword (str (:as %)))]
                                  (when (#{:implementation :design :stance :baseline} as)
                                    {:finding (str (:finding %)) :as as})))
                         (:findings_classified m)))

            (not (str/blank? (str (:needs m))))
            (assoc :needs (str (:needs m))))))
      (catch Exception _ nil))))

(defn ^{:malli/schema [:=> [:cat :any] :boolean]}
  decision?
  "True when the verdict is one a human has to answer rather than read."
  [v]
  (boolean (#{:invalidated :standing-challenged} (:verdict v))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  still-open
  "What the verdict pass is asked to judge, out of one round's findings — the
   warden closed the rest, by a named authority. Handing a closed finding to the
   verdict would have it re-adjudicate something already decided, against a
   design it is supposed to be checking.

   Only a close is dropped, and the narrower filter is deliberate: a decline is
   a real defect this branch chose to ship and a deviation is a layer's claim it
   has stopped meeting, and both are exactly the evidence this pass is asked to
   classify as implementation-or-design. `open-across-run` drops them because it
   answers a different question — what is still OWED — and nobody owes anything
   on a decision.

   ONE round's findings. For what the whole run is still holding, which is what
   a reader of the workstream needs, see `open-across-run`."
  [findings]
  (into [] (remove #(= :closed (:disposition %))) findings))

(defn- finding-identity
  "What makes two reports across rounds the same finding. The warden's handle
   when it assigned one, since that is the only identity a re-wording cannot
   move; then the id; then the same file/line/title triple the diff loop falls
   back to.

   The triple is load-bearing rather than decorative: a finding that reached no
   warden has neither of the first two, and keying those on nil would fold every
   one of them onto a single entry — turning a run that ended holding eight
   unruled findings into a run reporting one. Splitting a re-worded finding into
   two rows over-reports; collapsing distinct ones under-reports, and this fold
   exists because under-reporting is the failure that hid a parked P1."
  [f]
  (or (:handle f) (:id f) [(:file f) (:line-start f) (:title f)]))

(defn- final-rulings
  "Every finding the run raised, folded over all its rounds to one entry each
   carrying the ruling that stuck, ordered by the round that ruling landed in.

   The final round is the round LEAST likely to hold the run's open items: a
   finding parked in round 1 and never resolved does not appear in round 9's
   report, so reading it alone lets a run end holding eight parked findings and
   record two. What a park IS, is a thing no round will raise again — so the
   last round is exactly where it cannot be found.

   Per identity, the LATEST ruling wins: a seam parked in round 3 and closed in
   round 7 is closed, and only its final disposition is asked about.

   A `:fix` is dropped from every round but the last. It was actioned, and the
   round after it is the check — if the fix did not take, the next round reports
   it again and that later report is the one that survives the fold. In the FINAL
   round there is no such round, so a finding handed to a fixer with nothing left
   to verify it is still owed."
  [{:keys [history findings]}]
  (let [rounds   (conj (vec (map :findings history)) (vec findings))
        last-idx (dec (count rounds))
        latest   (reduce (fn [acc [idx round-findings]]
                           (reduce (fn [a f]
                                     (assoc a (finding-identity f)
                                            (assoc f ::round idx)))
                                   acc round-findings))
                         {}
                         (map-indexed vector rounds))]
    (->> (vals latest)
         (remove #(and (= :fix (:disposition %))
                       (< (::round %) last-idx)))
         (sort-by (juxt ::round #(str (:id %))))
         (mapv #(dissoc % ::round)))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  open-across-run
  "Everything the run is still OWED when it ends — a fixer's work nobody
   checked, a question put to a human, a finding no round ruled on. See
   `final-rulings` for the fold.

   Owed is `stages/settled?`, not `is not closed`. The two differ for a decline
   and for a deviation, and reading the second counts a finding this run's own
   convergence check has settled as one the run is still holding — so the same
   run reports `converged` and `1 still open` about one finding, and two ledger
   entries it wrote minutes apart disagree about how much is left.
   `prompts/disposition-vocabulary` carries `:settles?` precisely so that
   convergence, the carried answers and this count cannot come to different
   views.

   What a decline and a deviation leave behind is not nothing, and it is not
   here — see `kept-across-run`."
  [final]
  (into [] (remove stages/settled?) (final-rulings final)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  kept-across-run
  "What the run DECIDED to live with — the real defects it declined and the
   layer claims it let stand. Nobody owes anything on these; that is what makes
   them not open, and it is also what makes them easy to lose.

   The other half of the remainder, and the reason `open-across-run` can afford
   to be strict about what is owed. Counted together the two are one number that
   answers neither question a reader has: a park is somebody must decide and a
   decline is somebody already did, and the second is a decision to ship a
   defect, which is precisely the kind of thing a record exists to hold."
  [final]
  (into [] (filter stages/kept?) (final-rulings final)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  handed-to-a-fixer
  "The findings a landed fix commit named as its own, across every round.

   The other half of `open-across-run`. A `:fix` in the final round stays open
   because no round after it re-read the layer — but so does a finding no fixer
   was ever launched for, and one number for both is the whole of what a run
   that aborted its fix plan reported: `1 fixed · 11 still open`, with one
   repaired-but-unchecked finding counted alongside nine nobody touched. The
   join is `fixes[].handed`, which the fix stage has written since it was added
   and nothing read back.

   A ROLLED-BACK repair is deliberately not here. It left no commit, so the code
   is exactly what the reviewers read and the finding is as untouched as one no
   fixer saw."
  [{:keys [history]}]
  (into #{} (comp (mapcat :fixes) (mapcat :handed) (remove nil?)) history))

(defn ^{:malli/schema [:=> [:cat :any :any] :boolean]}
  handed?
  "Whether a repair for this finding is sitting in the branch, unverified.
   `handed` is a `handed-to-a-fixer` set."
  [handed f]
  (contains? handed (or (:handle f) (:id f))))

(defn ^{:malli/schema [:=> [:cat :any :map :any] :boolean]}
  still-answers?
  "Whether `prior` — a verdict against this run's own design record — is still
   this run's answer, so the pass need not be launched at all.

   Three things could move a verdict: the record it judges, the code it reads,
   and the findings it classifies. The record is held fixed by the caller, which
   only offers a verdict carrying the same :design-seq. The other two are what
   this asks about, and nido can answer them without an agent:

   - The run raised nothing and decided nothing. Every finding is settled and
     none was kept, so there is no evidence in front of this pass that was not
     in front of the last one. `open-across-run` and `kept-across-run` are read
     rather than the final round's findings, because a park raised in round 1
     is never raised again and so leaves the final round empty.
   - No fix was dispatched. A fixer edits code, and a repair that moves a
     boundary is a thing no reviewer judged against the design — which is
     precisely what this pass exists to catch. A run that landed one has a tree
     the standing verdict never saw.

   A DECISION is never carried. :invalidated and :standing-challenged put a
   question to a human, and re-asserting one unlooked-at would keep escalating a
   design that may since have been repaired in the code without the record being
   amended. That case is worth the minutes.

   What this cannot know is whether an invariant has been NEWLY broken — that is
   the judgment, and it costs the pass. What it knows is that this round produced
   no evidence one could have been: `tasks.nido-review/verdict-worth-running?`
   admits a clean review because a design's invariants still need confirming
   against the code, and this is the other half of that reasoning — once a verdict
   has confirmed them against this same record, a second silent review confirms
   nothing new."
  [prior final report]
  (boolean
   (and prior
        (not (decision? prior))
        (empty? (open-across-run final))
        (empty? (kept-across-run final))
        (zero? (or (get-in report [:summary :findings-fixed]) 0)))))

(defn ^{:malli/schema [:=> [:cat :map :int] :map]}
  carried-forward
  "`prior` re-stated as this run's verdict: the same judgment, stamped with the
   round it now answers for and with the entry an agent actually reached it at.

   :carried-from is what keeps the ledger honest, and it is the reason this is a
   re-statement rather than a silence. A run that records a verdict no agent
   reached this time has to say so on the entry itself — six unmarked identical
   judgments read as six independent confirmations, which is a stronger claim
   than nido has evidence for. Appending nothing would be the other lie: a
   reader of the workstream could not tell a run whose verdict was carried from
   one whose pass never ran.

   It names the ORIGINAL entry, not the one just read, so a verdict carried
   across five runs still points at the single place a judgment was made.

   `unstamp` because :seq and :at belong to the reader: the write schema is
   closed and refuses an entry carrying them."
  [prior rounds]
  (-> prior
      ws/unstamp
      (assoc :round rounds
             :carried-from (or (:carried-from prior) (:seq prior)))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  run!
  "Run the verdict pass. Returns the verdict map, or nil when there is no design
   record to judge against, the agent no-ops, or the answer is unparseable — all
   three mean 'nothing to record', never a fabricated :sound.

   A standing verdict this run gave no reason to revisit is carried forward
   instead of re-derived; see `still-answers?` for when that holds and
   `carried-forward` for what the entry then says."
  [{:keys [cwd run-id budget final report]}]
  (when-let [design (stages/discover-design-record cwd)]
    (let [prior  (stages/discover-prior-verdict cwd design)
          rounds (or (get-in report [:summary :rounds]) 0)]
      (if (still-answers? prior final report)
        (carried-forward prior rounds)
        (let [prompt (build-prompt
                      {:design design
                       :baseline (stages/discover-baseline cwd design)
                       :stance (stages/read-stance (first (stages/project+ws-from-cwd cwd)))
                       :findings (still-open (:findings final))
                       :history (mapv #(dissoc % :findings) (:history final))
                       :rounds rounds
                       :prior prior})
              {:keys [num-turns result-error? result-text]}
              (agent/launch! {:run-id run-id :cwd cwd
                              :first-message prompt :budget budget
                              :err-file (str (fs/path (cstate/run-dir run-id) "agent.err.log"))})]
          (when-not (or (zero? (or num-turns 0)) result-error?)
            (parse result-text rounds (:seq design))))))))
