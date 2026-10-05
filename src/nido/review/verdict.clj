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
   [nido.coordinator.report.model :as claim-model]
   [nido.coordinator.record.phase :as phase]
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
                   (if (contains? baseline :model)
                     (map #(str "- [" (:id %) "] " (:statement %)
                                (when (seq (:read-at %))
                                  (str " [" (str/join ", " (:read-at %)) "]")))
                          (claim-model/claims baseline))
                     (map #(str "- " (:property %)
                                " [" (str/join ", " (:evidence %)) "]")
                          (:load-bearing baseline))))
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
         (if (contains? baseline :model)
           (str "Populate load_bearing_held with the ids of the claims this round\n"
                "confirmed still stand, for the same reason invariants_held exists, and\n"
                "name a broken claim by its id in load_bearing_broken.\n")
           (str "Populate load_bearing_held with the properties this round confirmed\n"
                "still stand, for the same reason invariants_held exists.\n")))))

(defn- phase-section
  "What a phase plan changes about the question being asked, or nil when the
   design lands in one go.

   Without this the verdict pass judges the middle of a migration against the end
   of it. A phased design's intermediate states are correct BY DESIGN — during an
   expand/migrate/contract, \"there is exactly one writer\" is deliberately untrue
   for the whole middle phase — so an :on-completion invariant that does not hold
   yet is the plan working, not the design failing. On the LAST phase the same
   invariant is simply owed, and excusing it there would let the plan end with
   the design never having held.

   `progress` is `record.phase/progress` over the workstream's ledger, and says
   which of the two this is. nil when the ledger could not be read, and then the
   pass is told so rather than invited to guess: an :on-completion invariant is
   reported on only when the code shows the last phase has landed."
  [phases progress]
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
         (cond
           (nil? progress)
           (str "Which phase is current could not be read. An invariant marked \"holds on\n"
                "completion\" is NOT expected to hold before the last phase lands — report\n"
                "it only if the code shows the final phase has landed and it still does not\n"
                "hold.\n")

           (:next progress)
           (str "THIS CHANGE IS PHASE " (:current progress) " OF " (:of progress)
                " — not the last. An invariant marked\n"
                "\"holds on completion\" is NOT expected to hold yet. Finding that one does\n"
                "not hold is the plan working as written — do not report it as broken.\n")

           :else
           (str "THIS CHANGE IS PHASE " (:current progress) " OF " (:of progress)
                " — the LAST. Every invariant is owed\n"
                "now: judge one marked \"holds on completion\" as required, exactly as you\n"
                "judge the others.\n"))
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
         (when-let [u (seq (:unraised prior))]
           (str "It found these defects in the code that no round raised:\n"
                (bullets (map #(str (:where %) " — " (:what %)) u)) "\n"))
         (when-let [n (:needs prior)]
           (str "And it advised:\n" n "\n"))
         "\n"
         "That verdict STANDS unless this round moved it, and your job is to say\n"
         "which:\n"
         "- Nothing moved: reach the same verdict and say so in a line. Do NOT\n"
         "  restate the advice in new words, and do NOT answer `unchanged` —\n"
         "  copy every defect above that is still in the code into `unraised`\n"
         "  as it stands. A pointer to an earlier verdict is lost the moment the\n"
         "  earlier verdict stops being shown.\n"
         "- Something moved: name WHAT — a finding that contradicts it, an\n"
         "  invariant this round confirmed, a boundary since repaired — and\n"
         "  reach the verdict that follows from it.\n"
         "Overturning it is allowed. Re-deriving it from scratch is not.\n")))

(defn- standing-section
  "The terminal warden's standing items — `report/stopped-on`'s `:standing` —
   numbered, so the judge can answer one by its index, or nil when there are
   none.

   A standing item names something no finding covers, so the verdict pass is
   the only reader after the loop that can check one against the code. Answered
   in prose, it stayed open everywhere a person reads: the payload published the
   warden's question beside a verdict that had already run the tests it asked
   for. By index, never by rewording, because the fold that retires an answered
   item matches it exactly."
  [standing]
  (when (seq standing)
    (str "\nSTANDING — what the last warden knew was open and handed to nobody:\n"
         (->> standing
              (map-indexed (fn [i item] (str i ": " item)))
              (str/join "\n"))
         "\nWhere the code settles one, put it in standing_answered with its\n"
         "index and the answer. Leave out any you could not settle — those stay\n"
         "open.\n")))

(defn- standing-text
  "A standing item as a person reads it: the warden's `:what` when the item is
   the map `report/stopped-on` builds, the item itself when it is a sentence."
  [item]
  (str (if (map? item) (:what item) item)))

(defn ^{:malli/schema [:=> [:cat :any :any] :boolean]}
  answers-standing?
  "Whether a verdict's `standing-answered` answers `item`, one of a run's
   standing items. By value — the item recorded on the answer under `:standing`.
   The `:item` text is compared as well, against the item printed whole, for a
   verdict recorded before answers carried the item: such a verdict is still
   carried forward, and its `:item` holds exactly that printed form."
  [answered item]
  (boolean (some #(or (and (contains? % :standing) (= item (:standing %)))
                      (= (str item) (:item %)))
                 answered)))

(def ^:private inherited-questions
  "Why nothing in the loop could answer an inherited row, by its `:question`.
   Each is a row this pass is the only reader of, so the judge is told it is
   being asked rather than shown context."
  {:unplaced "no layer of this stack holds its file, so no reviewer was shown it"
   :unruled  "no warden ever ruled on it — the run that left it never reached one"})

(defn- inherited-section
  "What the last run left owed that this one never answered — `inherited`, each
   row by id — or nil when there is none.

   Answered by id and with evidence, because the answer is folded back onto the
   row: `settled` marks it `:answered`, so the status, the `:review` entry and
   the gate stop counting it. An answer in prose can be joined to no row, and
   a run whose rounds were all quiet would publish `unresolved` over rows its
   own verdict had found repaired.

   A row tagged with a `:question` is one only this pass can answer — see
   `inherited-questions`."
  [inherited]
  (when (seq inherited)
    (str "\nLeft owed by the LAST run of this workstream and answered by nobody in\n"
         "this one. They are still open on the branch, whatever the rounds above say:\n"
         (->> inherited
              (map (fn [{:keys [id title where disposition question]}]
                     (str "- " id " " title (when where (str " (" where ")"))
                          (when disposition (str " — ruled " (name disposition)))
                          (when-let [q (inherited-questions question)]
                            (str "\n  QUESTION FOR YOU: " q)))))
              (str/join "\n"))
         "\nWhere the code settles one — the defect is gone, or never was — put it\n"
         "in inherited_answered by its id, with the file:line that shows it. One\n"
         "you leave out stays owed, and the run ends unresolved over it.\n")))

(defn- closed-section
  "The findings whose last ruling was a warden's close — `closed`, as
   `closed-across-run` reads them — or nil when there is none.

   The warden cannot read code, so it closes on what the record says; this pass
   reads the code. Where the two disagree the close was counted settled and the
   verdict's objection reached a person only as advice, three rounds running on
   one run. Named by handle so `contested_closes` reopens exactly that finding."
  [closed]
  (when (seq closed)
    (str "\nCLOSED BY THE WARDEN — it ruled these settled without reading the code:\n"
         (->> closed
              (map (fn [{:keys [handle id title authority because]}]
                     (str "- " (or handle id) " " title
                          (when authority (str " — closed as " (name authority)))
                          (when-not (str/blank? (str because)) (str ": " because)))))
              (str/join "\n"))
         "\nWhere the code or the design shows a close was wrong, put it in\n"
         "contested_closes by its handle with the reason. It reopens as a question\n"
         "for a person; do not contest a close you merely would have worded\n"
         "differently.\n")))

(defn- not-landed
  "The rows of a `stages/fix-outcomes` whose fixer got nothing into the code.
   The landed rest reach this pass inside the round history, accounts and all."
  [fix-outcomes]
  (remove #(= :landed (:outcome %)) fix-outcomes))

(defn- not-landed-section
  "What the loop's fixers were handed and did not get into the code — a
   `not-landed` — or nil when there is none.

   The judge reads the code, so a finding whose repair was written and put back
   is, to it, a finding nobody repaired: it finds the defect in the tree and
   prescribes the fix. One judge did exactly that on a `fix-rolled-back` run, in
   nearly the words of the account the refused fixer had written. A refused
   row's commit is the one thing it needs and the reviewers could not use — it
   has tools, and `jj show` on the commit is the edit.

   Accounts are rendered whole, as the landed ones are in the round history:
   cutting these would make the repairs this reader cannot see the ones it reads
   least of. A fixer's refusal is ARGUED, never declined: this reader holds the
   warden's `declined` rulings too, and `prompts/fixer-declines-block` says
   what one word for both costs."
  [rows]
  (when (seq rows)
    (str "\nNOT IN THE CODE — what the loop's fixers were handed and did not land.\n"
         "A finding still true in the tree you read may be one of these.\n"
         "- REFUSED: the repair was written, rebasing the layers above it onto\n"
         "  the edit conflicted, and it was put back. `jj show <commit>` is the\n"
         "  edit. Where one is what the branch needs, say \"recover the repair at\n"
         "  <commit>\" in `needs` rather than describing how to build it again.\n"
         "- ARGUED: a fixer read the findings and argued for changing nothing.\n"
         "  The argument is evidence to weigh, not a ruling.\n"
         "- NEVER STARTED: no fixer read the findings at all. They are untried,\n"
         "  not resisted — their coming back is no pressure on the design.\n\n"
         (->> rows
              (map (fn [{:keys [outcome layer round commit conflicted exit-code
                                findings account]}]
                     (str "- " (or layer "the branch") ", round " round ", "
                          (case outcome
                            :refused   (str "REFUSED"
                                            (when commit (str " — commit " commit))
                                            (when (seq conflicted)
                                              (str ", conflicted "
                                                   (str/join ", " conflicted))))
                            :declined  "ARGUED — no edit was written"
                            :unstarted (str "NEVER STARTED"
                                            (when (some? exit-code)
                                              (str " (exit " exit-code ")"))
                                            " — no fixer read these")
                            (name outcome))
                          "\n"
                          (->> findings
                               (map (fn [{:keys [id title]}]
                                      (str "    · " id (when title (str " " title)) "\n")))
                               (apply str))
                          (when-not (str/blank? (str account))
                            (str (if (= :declined outcome)
                                   "  the fixer's argument for changing nothing: "
                                   "  the fixer said of the edit that was put back: ")
                                 account "\n")))))
              (apply str)))))

(defn- opening
  "The sentence the judge starts from: how the loop ended, and whether what it
   wanted repaired is in the code it is about to read.

   The status is named, not glossed. Its glosses are
   `tasks.nido-review/diff-remedies`, addressed to an operator and out of this
   band's reach, and a second copy here would drift from them. Whether the
   repairs are in is read off the fix record rather than off the status,
   because it is a fact about the record: a run can stop on a status that says
   nothing about repairs while an earlier round's refusal or decline still
   stands."
  [status not-landed]
  (str "You are judging whether a DESIGN survived a code review, not whether the code\n"
       "is correct. The review loop has finished"
       (when status (str ", on `" (name status) "`"))
       ".\n"
       (if (seq not-landed)
         (str "What its fixers landed is in the code. Not everything they were handed\n"
              "is — see NOT IN THE CODE below.\n\n")
         "What its fixers landed is in the code.\n\n")))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  build-prompt
  "The verdict prompt. `design` is the workstream's :design record, `baseline` the
   :baseline record it cited (nil when it predates them), `findings` the findings
   still open at the end, `history` the per-round digest, `prior` the last
   verdict against this same design record in the current phase (nil when there
   is none), `status`
   the run's terminal status, and `fix-outcomes` the run's
   `stages/fix-outcomes`. `progress` is where the workstream stands in the
   design's phase plan (`record.phase/progress`), nil when unphased or unread.
   `inherited` is what the last run left owed that this one never answered —
   `stages/unanswered-inherited` — which no round's findings mention, so a judge
   shown only those says `nothing is open` beside an entry that lists them.
   `standing` is the terminal warden's standing list, see `standing-section`,
   and `closed` the findings the run's last word on was a warden's close, see
   `closed-section`. Each inherited row may carry `:question`, see
   `inherited-section`."
  [{:keys [design baseline stance findings inherited closed history rounds prior status
           fix-outcomes progress standing]}]
  (str
   (opening status (not-landed fix-outcomes))
   "Read the code where you need to — you have tools, and the question cannot be\n"
   "answered from the diff summary alone.\n\n"
   "THE DESIGN THIS CHANGE COMMITTED TO:\n"
   "Shape: " (:shape design) "\n"
   ;; A design in the shared model names its claims, and the judge names them back by id in
   ;; invariants_held, invariants_unverified and invariants_broken; the shape before it
   ;; lists bare clauses.
   (if (contains? design :model)
     (str "Claims — name a claim by its id in invariants_held, invariants_unverified and\n"
          "invariants_broken:\n"
          (bullets (map (fn [{:keys [id statement]}]
                          (str "[" id "] " statement
                               " [holds " (name (get (:holds design) id :always)) "]"))
                        (claim-model/claims design))))
     (str "Invariants:\n"
          (bullets (map (fn [i]
                          (let [{t :invariant h :holds} (report/invariant i)]
                            (str t " [holds " (name h) "]")))
                        (:invariants design)))))
   "\n"
   (phase-section (:phases design) progress)
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
   "Round history (findings + what was fixed):\n" (pr-str history) "\n"
   (not-landed-section (not-landed fix-outcomes))
   "\n"
   "Findings still open at the end. Each carries the reach the reviewer assigned:\n"
   "local (a defect inside the current design), structural (about where a\n"
   "boundary sits — the reviewer saw shape without intent), or unclear. The\n"
   "structural ones are what you are really here to adjudicate: the reviewer\n"
   "could not see the design, and you can.\n"
   (if (seq findings)
     (->> findings
          (map-indexed (fn [i f] (str i ": [P" (:priority f) "/"
                                      (name (or (:reach f) :unclear)) "] "
                                      ;; What NOT IN THE CODE names a finding by,
                                      ;; and a launch that never started names
                                      ;; its findings by nothing else.
                                      (when-let [h (or (:handle f) (:id f))] (str h " "))
                                      (:title f) " — " (:body f))))
          (str/join "\n"))
     "(none)")
   "\n"
   (inherited-section inherited)
   (closed-section closed)
   (standing-section standing)
   ;; Last, so the judge reads this round's evidence before it is reminded what
   ;; it already decided — the standing answer is what the new evidence is
   ;; weighed against, not the frame it is read through.
   (prior-verdict-section prior)
   "\n"
   "Return EXACTLY one fenced ```json block, nothing after it:\n"
   "{\"verdict\": \"sound|strained|invalidated|standing_challenged\",\n"
   " \"reason\": \"...\",\n"
   " \"invariants_held\": [\"...\"],\n"
   " \"invariants_unverified\": [{\"invariant\": \"...\", \"missing\": \"...\"}],\n"
   " \"invariants_broken\": [{\"invariant\": \"...\", \"finding\": \"...\"}],\n"
   " \"load_bearing_held\": [\"...\"],\n"
   " \"load_bearing_broken\": [{\"invariant\": \"...\", \"finding\": \"...\"}],\n"
   " \"findings_classified\": [{\"finding\": \"...\", \"as\": \"implementation|design|stance|baseline\"}],\n"
   " \"unraised\": [{\"where\": \"file:line\", \"what\": \"...\", \"finding\": null}],\n"
   " \"standing_answered\": [{\"index\": 0, \"answer\": \"...\"}],\n"
   " \"inherited_answered\": [{\"id\": \"...\", \"evidence\": \"file:line — ...\"}],\n"
   " \"contested_closes\": [{\"id\": \"...\", \"reason\": \"...\"}],\n"
   " \"needs\": \"...\"}\n\n"
   "- findings_classified: name each finding by the handle shown before its\n"
   "  title, then say what it is.\n"
   "- unraised: every DEFECT you found in the code that no round raised,\n"
   "  one row each, located at a file and line — whatever the verdict. It is\n"
   "  the only way such a defect reaches the next run's reviewers. A row\n"
   "  about a finding the rounds did raise names its handle in `finding`;\n"
   "  it is already counted, so prefer leaving it out.\n"
   "- invariants_unverified: every invariant you could not confirm from what\n"
   "  you could read or run, each with the evidence that would confirm it —\n"
   "  a test only run on one platform, a path nobody exercised. An invariant\n"
   "  your needs says is still to be verified belongs HERE and never in\n"
   "  invariants_held: held means you confirmed it.\n"
   "- inherited_answered: only ids from the left-owed list above, each with\n"
   "  the evidence in the code. Leave it empty when there is no such list.\n"
   "- contested_closes: only handles from the closed-by-the-warden list.\n"
   "- needs: what a PERSON should do or decide — advice, a record to amend,\n"
   "  a chore before landing. Never a defect in the code; those go in\n"
   "  unraised. Leave it empty when there is nothing to say.\n"
   "- sound: the findings were implementation details. THIS IS THE EXPECTED\n"
   "  OUTCOME. Populate invariants_held with the ones this round actually\n"
   "  confirmed — that is the point of the verdict, not a formality.\n"
   "- strained: the design holds, but a boundary is visibly under pressure —\n"
   "  findings clustering on one cut, the same argument recurring. Ship it, but\n"
   "  say where the pressure is.\n"
   "- invalidated: the findings contradict a named invariant; the design itself\n"
   "  is wrong. REQUIRES invariants_broken and needs.\n"
   "- standing_challenged: the finding is right, the change is right, and the\n"
   "  PROJECT STANCE is what needs to move. Rare. REQUIRES needs.\n\n"
   "Do not reach for invalidated because the review was noisy. A design is only\n"
   "invalidated when you can name the invariant that cannot hold."))

(defn- by-id
  "A judge's `[{id <k>}]` answer as `[{:id :<k>}]`, dropping any row missing
   either half: an id with no evidence is the silence the slot replaces, and
   evidence with no id answers nothing in particular."
  [rows k]
  (into []
        (keep (fn [row]
                (let [id (str/trim (str (:id row)))
                      v  (str/trim (str (get row k)))]
                  (when-not (or (str/blank? id) (str/blank? v))
                    {:id id k v}))))
        rows))

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

            (seq (:invariants_unverified m))
            (assoc :invariants-unverified
                   (into []
                         (keep (fn [{:keys [invariant missing]}]
                                 (when-not (str/blank? (str invariant))
                                   {:invariant (str/trim (str invariant))
                                    :missing   (str/trim (str missing))})))
                         (:invariants_unverified m)))

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

            (seq (:unraised m))
            (assoc :unraised
                   (into []
                         (keep (fn [{:keys [where what finding]}]
                                 (when-not (or (str/blank? (str where)) (str/blank? (str what)))
                                   (cond-> {:where (str/trim (str where))
                                            :what  (str/trim (str what))}
                                     (not (str/blank? (str finding)))
                                     (assoc :finding (str/trim (str finding)))))))
                         (:unraised m)))

            (seq (:standing_answered m))
            (assoc ::standing-answers
                   (into []
                         (keep (fn [{:keys [index answer]}]
                                 (when (and (integer? index) (not (str/blank? (str answer))))
                                   {:index index :answer (str/trim (str answer))})))
                         (:standing_answered m)))

            (seq (:inherited_answered m))
            (assoc ::inherited-answers (by-id (:inherited_answered m) :evidence))

            (seq (:contested_closes m))
            (assoc ::contested-closes (by-id (:contested_closes m) :reason))

            (not (str/blank? (str (:needs m))))
            (assoc :needs (str (:needs m))))))
      (catch Exception _ nil))))

(defn ^{:malli/schema [:=> [:cat :any] :boolean]}
  decision?
  "True when the verdict is one a human has to answer rather than read.

   Shape-agnostic on the verdict, because one of its readers is a verdict read
   back OUT of the report: the same value is a keyword in the process that
   folded it and a string once report.json has been through JSON, and a reader
   that only knew the first would call a decision readable exactly where the
   report has outlived its run. `report/verdict-summary` is careful for the same
   reason."
  [v]
  (boolean (some-> (:verdict v) name keyword report/verdict-invalidates)))

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

(defn- repairs-aimed-at
  "The findings a round's fix commits named as their own — the handles under
   `:handed` on each of its `:fixes` rows, written by `stages/handed-ids` as the
   warden's handle where it assigned one and the reviewer's id otherwise.

   `:fixes` holds only what the stack KEPT. A repair it refused was rolled back
   and is recorded under `:rolled-back`, and a round that stopped before its
   fixers ran has no rows at all — so a handle here means a commit aimed at that
   finding is in the branch, which is the premise both readers below rest on."
  [fixes]
  (into #{} (comp (mapcat :handed) (remove nil?)) fixes))

(defn- fold-rulings
  "Every finding the run raised, folded over all its rounds to one entry each
   carrying the ruling that stuck, ordered by the round that ruling landed in,
   and split by whether a repair is what ended it:

     :standing — the run's last word on the finding, whatever that word is
     :repaired — a `:fix` a commit was aimed at and no later round raised again

   The final round is the round LEAST likely to hold the run's open items: a
   finding parked in round 1 and never resolved does not appear in round 9's
   report, so reading it alone lets a run end holding eight parked findings and
   record two. What a park IS, is a thing no round will raise again — so the
   last round is exactly where it cannot be found.

   Per identity, the LATEST ruling wins: a cut parked in round 3 and closed in
   round 7 is closed, and only its final disposition is asked about.

   A `:fix` from an earlier round is `:repaired` only where a repair for it
   actually landed in that round. Then the round after it is the check — if the
   fix did not take, the next round reports it again and that later report is
   the one that survives the fold. Where no commit was ever aimed at it, the
   round after read the same code its predecessor did and had nothing to
   re-report, so the ruling is the last word on the finding and it is still
   owed. In the FINAL round there is no round after it either way, so nothing
   raised there is ever `:repaired`.

   That last condition is exactly the evidence a defect was removed, which is
   why the two halves come out of one fold rather than two: `:standing` is what
   the run is still holding and `:repaired` is what it settled, and a finding
   double-counted or dropped between them would make the pair not add up.

   A final round whose review ABORTED (`:review-aborted?`) is no round after
   anything: the reviewer that would have re-reported a repair that did not
   take may be the one that never ran, so its silence is not evidence. The
   round before it is then the last that read the branch, and its repairs stay
   owed. What the aborted round's surviving reviewers did raise is still
   folded in, unruled, and so owed."
  [{:keys [history findings review-aborted?]}]
  (let [rounds    (conj (vec (map :findings history)) (vec findings))
        repaired  (mapv #(repairs-aimed-at (:fixes %)) history)
        ;; The last round that READ the branch, which is what a repair has to be
        ;; before to have been checked.
        last-idx  (cond-> (dec (count rounds)) review-aborted? dec)
        latest    (reduce (fn [acc [idx round-findings]]
                            (reduce (fn [a f]
                                      (assoc a (finding-identity f)
                                             (assoc f ::round idx)))
                                    acc round-findings))
                          {}
                          (map-indexed vector rounds))
        repaired? (fn [f] (and (= :fix (:disposition f))
                               (< (::round f) last-idx)
                               (contains? (get repaired (::round f) #{})
                                          (or (:handle f) (:id f)))))
        ordered   (fn [fs] (->> fs
                                (sort-by (juxt ::round #(str (:id %))))
                                (mapv #(dissoc % ::round))))
        by-fate   (group-by (comp boolean repaired?) (vals latest))]
    {:standing (ordered (get by-fate false))
     :repaired (ordered (get by-fate true))}))

(defn- final-rulings
  "The run's last word on every finding it raised. See `fold-rulings`."
  [final]
  (:standing (fold-rulings final)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  settled-by-fixing
  "The defects this run REMOVED: findings a fixer was handed a commit for and
   that no later reviewer raised again. See `fold-rulings`.

   The number a reader takes `N fixed` to mean, and the only one in the run that
   has evidence behind it. `report/fix-attempts` counts dispatches — a handle
   handed out in three rounds is three — so a run that repaired two defects and
   parked two others published `7 fixed`. Here a finding is counted once, and
   only where the round after it read the repaired code and had nothing to say.

   A run's LAST round can contribute nothing: its repairs are exactly the ones
   no reviewer has read, which is what `handed-to-a-fixer` is for. So a one-round
   run settles nothing by fixing however many fixers it launched, and that is the
   honest answer rather than a gap in the fold."
  [final]
  (:repaired (fold-rulings final)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  settled-the-loop-made
  "The part of `settled-by-fixing` the loop itself put on the branch: findings
   the warden ruled were introduced by an earlier round's repair, as against
   defects the branch arrived with.

   A defect a fixer creates and the next round removes counts as settled like
   any other, so without this split a run whose repairs kept breaking what they
   touched reads as a run that found and fixed more. The attribution is the
   warden's judgement, not a diff — it is the one reader holding every round —
   so a nil here means no one said so, not that the loop is innocent."
  [final]
  (filterv :introduced-by-round (settled-by-fixing final)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  open-across-run
  "Everything the run is still OWED when it ends — a fixer's work nobody
   checked, a question put to a human, a finding no round ruled on. See
   `fold-rulings` for the fold.

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

(defn- finding-key
  "The id a finding is named by to the verdict pass and back: the warden's
   handle where it assigned one, as `fold-rulings` keys it."
  [f]
  (str (or (:handle f) (:id f))))

(defn- closed-across-run
  "The findings whose last ruling in the run was a warden's close — what the
   verdict pass may contest. See `closed-section`."
  [final]
  (into [] (filter #(= :closed (:disposition %))) (final-rulings final)))

(defn- fix-attempts-on
  "How many rounds of this run landed a repair aimed at `f` — `repairs-aimed-at`
   over each round in the history, which holds exactly the rounds that landed
   one. A repair the stack refused is no attempt here: nothing of it reached the
   branch."
  [final f]
  (let [k (or (:handle f) (:id f))]
    (count (filter #(contains? (repairs-aimed-at (:fixes %)) k) (:history final)))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  still-owed
  "What the WORKSTREAM is still owed when this run ends: everything the run is
   holding — `open-across-run` — and, after it, every row the last run left owed
   that this one neither raised nor answered, each marked `:inherited`. See
   `stages/unanswered-inherited` for what answers one.

   THE one derivation of the remainder. The status a run stops as, the ledger
   entry it writes, the analysis headline, the parked-blocker gate and the
   carried verdict all read it, because each used to read its own: the entry
   counted the inherited rows and the headline did not, so one run published
   `0 still open` beside an entry holding five, and a run whose only owed rows
   were a park from round 1 and an inherited one stopped `:converged`.

   An inherited row this run raised again is this run's, and its own accounting
   decides what is owed on it — deduped on the id and on the handle, since a
   `same_as` naming the row files the new finding under the row's id.

   `:handed` does not survive the carry. It claims a repair is sitting in the
   branch that no reviewer has read, and this run put the row in front of the
   reviewer of its own layer — so whatever is true of it now, unread is not.

   Each of this run's own carries `:attempts` when a repair for it has landed —
   in this run, or in the last one for a row the warden took on — so the run
   after meets a defect that has resisted two repairs as one."
  [final]
  (let [raised (mapv (fn [f]
                       (let [n (+ (fix-attempts-on final f) (or (:prior-attempts f) 0))]
                         (cond-> (dissoc f :prior-attempts) (pos? n) (assoc :attempts n))))
                     (open-across-run final))
        seen   (into #{} (comp (mapcat (juxt :id :handle)) (remove nil?)) raised)]
    (into raised
          (comp (remove #(contains? seen (:id %)))
                (map #(-> % (dissoc :handed) (assoc :inherited true))))
          (stages/unanswered-inherited final))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  kept-across-run
  "What the run DECIDED to live with — the real defects it declined and the
   layer claims it let stand. Nobody owes anything on these; that is what makes
   them not open, and it is also what makes them easy to lose.

   The other half of the remainder, and the reason `open-across-run` can afford
   to be strict about what is owed. Counted together they are one number that
   answers neither question a reader has: a park is somebody must decide and a
   decline is somebody already did, and the second is a decision to ship a
   defect, which is precisely the kind of thing a record exists to hold.

   The rounds are not the whole remainder — see `kept-by-the-verdict` for the
   half of it the judge contributes after they end."
  [final]
  (into [] (filter stages/kept?) (final-rulings final)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  kept-by-the-verdict
  "The design judge's own remainder, out of `report`: the `:unraised` rows of a
   verdict that asks nobody to decide anything — or nil.

   Same shape as a decline. The judge names a located defect, no round raised
   it, no fixer was handed it, and the run ships it anyway; nobody is owed
   anything, which is what makes it kept rather than open and what makes it easy
   to lose. Uncounted, a `sound` verdict naming three defects in a layer three
   rounds of reviewers had read published `clean · 0 still open` with no
   remainder beside it — a headline the judge's own entry contradicts.

   One per ROW, and rows only. `:needs` is the judge's advice to a person and
   counts as nothing: counted, it made `1 kept` out of `nothing is needed to
   ship`, a list of landing chores, a record edit, a pointer to an earlier
   verdict and a restatement of a finding still open. A row restating a finding
   the run raised never reaches here — `against-the-run` drops it.

   Only from a verdict that leaves the design STANDING, on
   `stages/standing-needs`' argument: :invalidated and :standing-challenged put
   their :needs to a person, `tasks.nido-review/parked-blocker` carries that to
   the gate, and a question somebody must answer is the definition of not kept.

   Off the REPORT rather than the loop's `final`, because the pass judges the
   whole run and so answers after it: `final` predates the verdict, and the
   report is where `report/with-verdict` has put it."
  [report]
  (let [v (get-in report [:design-verdict :verdict])]
    (when (and (:verdict v) (not (decision? v)))
      (not-empty (vec (:unraised v))))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  handed-to-a-fixer
  "The findings a fix commit the FINAL round landed named as its own.

   The other half of `open-across-run`, and its question is narrow: is there a
   repair in the branch that no reviewer has read. Only the final round can be
   holding one. An earlier round's repairs were re-read by the round after it,
   and a finding raised again on top of a fix has by construction been checked
   — so counting one here sends a reader to verify a repair the run's own later
   reviewers have already read.

   One number for this and for a finding no fixer was ever launched for is the
   whole of what a run that aborted its fix plan reported: `1 fixed · 11 still
   open`, with one repaired-but-unchecked finding counted alongside nine nobody
   touched. `repairs-aimed-at` is why the final round's own `:fixes` is the
   whole record — a rolled-back repair and a plan the round never reached are
   both absent from it, and in both cases the code is exactly what the reviewers
   read.

   A final round whose review aborted read nothing whole and ran no fixer, so
   the round with the unread repairs is the one before it — the last in the
   history, as `fold-rulings` reads it."
  [final]
  (repairs-aimed-at (:fixes (if (:review-aborted? final)
                              (last (:history final))
                              final))))

(defn ^{:malli/schema [:=> [:cat :any :any] :boolean]}
  handed?
  "Whether a repair for this finding is sitting in the branch, unverified.
   `handed` is a `handed-to-a-fixer` set."
  [handed f]
  (contains? handed (or (:handle f) (:id f))))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  ledger-findings
  "Findings trimmed to what a reader of the workstream needs and nothing that
   only makes sense inside a run. `where` is assembled here because file and
   line are two fields in the report and one fact to a reader.

   `handed` is a `handed-to-a-fixer` set, and `:handed` is the one field
   here that is not the finding's own: whether a repair for it is sitting
   unverified in the branch. Two remainders read alike in a list of titles and
   ask opposite things of whoever picks them up — one needs checking, the other
   needs doing. Empty for a list where nothing is owed and the question cannot
   arise.

   `:layer` is what makes this list joinable back onto a later run's targets —
   `nido.review.stages/prior-open` is the reader, and a label is the only identity that
   survives the repair a `:fix` row is asking for. The warden's owner where it
   assigned one, since that is where the repair goes whoever reported it; the
   reviewer that raised it otherwise, which is the best available answer for a
   finding no warden ruled on and still the layer whose reviewer read the code.

   `:attempts` is `still-owed`'s count of the repairs that landed for
   it. It rides to the next run's warden and reviewer, which otherwise meet a
   row that has resisted two repairs exactly as they meet one nobody has tried.

   `:belongs-in` is the file a fixer said the repair has to be made in, and it
   is what `nido.review.stages/prior-open` carries a row past one hop on.

   `:same-as` is the warden's recurrence mark. A blocker raised over these rows
   reads its ground from it, so a row that dropped it would put a park on
   recurrence to a person as a design question."
  [handed findings]
  (into []
        (map (fn [{:keys [id title file line-start disposition because same-as
                          owner-layer from-layer attempts belongs-in] :as f}]
               (cond-> {:title (str (or title "(untitled finding)"))}
                 id          (assoc :id (str id))
                 file        (assoc :where (str file (when line-start (str ":" line-start))))
                 disposition (assoc :disposition (keyword disposition))
                 because     (assoc :because (str because))
                 same-as     (assoc :same-as (str same-as))
                 (or owner-layer from-layer) (assoc :layer (str (or owner-layer from-layer)))
                 (handed? handed f) (assoc :handed true)
                 (and attempts (pos? attempts)) (assoc :attempts attempts)
                 (not (str/blank? (str belongs-in))) (assoc :belongs-in (str belongs-in)))))
        findings))

(def ledger-row-keys
  "What an inherited row may carry back into an entry: the ledger's own
   `ReviewFinding` keys. The carry adds what the run used it for, and none of
   that is a fact about the branch."
  [:id :title :where :disposition :because :same-as :layer :attempts :inherited
   :belongs-in])

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  owed-rows
  "`still-owed`, as the ledger's `:open` rows: this run's own remainder
   first, trimmed by `ledger-findings`, then every inherited row nobody
   answered. The one list the entry, the analysis payload and the gate count."
  [final]
  (let [owed   (still-owed final)
        handed (handed-to-a-fixer final)]
    (into (ledger-findings handed (remove :inherited owed))
          (map #(select-keys % ledger-row-keys))
          (filter :inherited owed))))

(defn ^{:malli/schema [:=> [:cat :any :map :any] :boolean]}
  still-answers?
  "Whether `prior` — a verdict against this run's own design record — is still
   this run's answer, so the pass need not be launched at all.

   Three things could move a verdict: the record it judges, the code it reads,
   and the findings it classifies. The record is held fixed by the caller, which
   only offers a verdict carrying the same :design-seq. The other two are what
   this asks about, and nido can answer them without an agent:

   - The tree is the one the verdict read: its `:patch-hashes` are this run's
     final round's, exactly. A branch rebased, re-cut or edited BETWEEN runs is
     code no judge read, and a run that dispatched no fixer says nothing about
     that — carried over it, a verdict republished a repaired defect as shipped
     and a sound design over a layer no judge had seen. A verdict with no
     hashes read a tree nobody recorded, and is never carried.
   - The run raised nothing and decided nothing. Every finding is settled and
     none was kept, so there is no evidence in front of this pass that was not
     in front of the last one. `still-owed` and `kept-across-run` are read
     rather than the final round's findings, because a park raised in round 1
     is never raised again and so leaves the final round empty.
   - No fix was dispatched. A fixer edits code, and a repair that moves a
     boundary is a thing no reviewer judged against the design — which is
     precisely what this pass exists to catch. A run that landed one has a tree
     the standing verdict never saw.
   - The WORKSTREAM is holding nothing either, which is why the first test
     reads `still-owed` rather than the run's own remainder: a run whose
     reviewers said nothing over a defect an earlier run ruled `:fix` is holding
     nothing of its own. Carrying the verdict there republishes a `:needs`
     naming that unrepaired defect as a thing to do, on an entry claiming
     nothing is owed.

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
        (seq (:patch-hashes prior))
        (= (set (:patch-hashes prior)) (set (map str (:patch-hashes final))))
        (empty? (still-owed final))
        (empty? (kept-across-run final))
        (zero? (or (get-in report [:summary :fix-attempts]) 0)))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  carried-forward
  "`prior` re-stated as this run's verdict: the same judgment, stamped with the
   entry an agent actually reached it at.

   Its `:round` is the round it was REACHED after, not this run's: the reason
   quotes that run's rounds, and restamped it read `after round 2` over prose
   about round 3 of a run the reader cannot see.

   :carried-from is what keeps the ledger honest, and it is the reason this is a
   re-statement rather than a silence. A run that records a verdict no agent
   reached this time has to say so on the entry itself — six unmarked identical
   judgments read as six independent confirmations, which is a stronger claim
   than nido has evidence for. Appending nothing would be the other lie: a
   reader of the workstream could not tell a run whose verdict was carried from
   one whose pass never ran.

   It names the ORIGINAL entry, not the one just read, so a verdict carried
   across five runs still points at the single place a judgment was made.

   What described that run's reading goes, because this run did none: the
   :reason narrates the rounds and fixes a judge saw, and :findings-classified
   sorts findings this run never raised. Kept whole, a carry after a clean round
   read as a judgment about a fix it never made, and counted that run's
   implementation findings as this one's. The :reason becomes a pointer to the
   entry that holds the real one; `as-reached` follows it back for the next judge.

   `unstamp` because :seq and :at belong to the reader: the write schema is
   closed and refuses an entry carrying them."
  [prior]
  (let [from (or (:carried-from prior) (:seq prior))]
    (-> prior
        ws/unstamp
        (dissoc :findings-classified)
        (assoc :carried-from from
               :reason (str "Carried from entry " from ", unchanged: this run gave that"
                            " verdict nothing to revisit, and no judge read the code for it.")))))

(defn- as-reached
  "`prior` with the :reason the judge that reached it wrote. A carried entry's own
   :reason only points at entry :carried-from, and a fresh judge is asked to confirm
   or move a line of reasoning, which a pointer is not. `prior` as it is when it was
   not carried, or the original entry cannot be read — a thinner prompt, not a lost
   run."
  [cwd prior]
  (or (when-let [n (:carried-from prior)]
        (try
          (when-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
            (when-let [r (:reason (ws/entry-at-seq project ws-id n))]
              (assoc prior :reason r)))
          (catch Exception _ nil)))
      prior))

(defn- bare-claim
  "A claim id as the design states it. The prompt renders ids in brackets and a
   judge quotes them back so."
  [x]
  (str/replace (str/trim (str x)) #"^\[|\]$" ""))

(defn- held-to-claims
  "`verdict` as the ledger may record it against a design stating `claim-ids`, or nil when it
   cannot be. A design in the shared model names its claims by id and asks for them back by id,
   and an id the design does not state names nothing a reader can find.

   The two lists fail differently, so they are held differently. A held id the design does not
   state is dropped: a confirmation of nothing loses nothing true by going. A broken one makes the
   whole answer a non-answer, because dropping it could turn an accusation into a clean verdict.

   An unverified one is kept whatever it names, bare when it is an id: it is evidence somebody
   owes, and dropping it would turn an owed verification into a clean verdict just as dropping a
   broken one turns an accusation into one.

   Ids are compared bare — see `bare-claim`.
   `claim-ids` nil, for a design from before the shared model whose invariants carry no ids,
   leaves the verdict as parsed."
  [verdict claim-ids]
  (if (nil? claim-ids)
    verdict
    (let [broken (mapv #(update % :invariant bare-claim) (:invariants-broken verdict))
          held   (into [] (comp (map bare-claim) (filter claim-ids)) (:invariants-held verdict))
          unver  (mapv #(update % :invariant bare-claim) (:invariants-unverified verdict))]
      (when (every? #(contains? claim-ids (:invariant %)) broken)
        (cond-> (dissoc verdict :invariants-held :invariants-broken :invariants-unverified)
          (seq held)   (assoc :invariants-held held)
          (seq unver)  (assoc :invariants-unverified unver)
          (seq broken) (assoc :invariants-broken broken))))))

(defn- raised-identities
  "Every id and handle the run raised or was handed — every round's findings
   and the rows the last run left that this one inherited. What an `:unraised`
   row may not name."
  [final]
  (into #{}
        (comp (mapcat (juxt :id :handle)) (remove nil?) (map str))
        (concat (:findings final)
                (mapcat :findings (:history final))
                (stages/unanswered-inherited final))))

(defn ^{:malli/schema [:=> [:cat :map :map :any] :map]}
  against-the-run
  "A parsed verdict, reconciled with the run it judged, as the ledger records
   it. Six things the judge cannot be trusted to keep straight, each decided
   here mechanically:

   - An `:unraised` row naming a finding the run raised is a restatement, and
     is dropped: that finding is already open or kept, and counting it again
     made one set of two defects read `2 still open · 1 kept`.
   - An invariant the judge lists as held that a finding still open
     `:contradicts` moves to `:invariants-unmet`. A sound verdict had no slot
     for a design the code falls short of, so it listed the contradicted claim
     as confirmed. That applies to an unverified invariant too: a finding
     contradicting it answers the question the missing evidence was for.
   - An invariant the judge lists as unverified is not held, whatever else it
     says. Listed as both, it read as confirmed beside a `:needs` asking a
     person to go and confirm it.
   - Standing answers name an item by index into `standing`, and are recorded
     with the item itself under `:standing` beside its text under `:item`; an
     index outside it names nothing and is dropped. The item, not its text,
     because `answers-standing?` retires it by value — recorded as the printed
     map, the answer matched no item and every answered one was republished.
   - An inherited answer or a contested close naming an id this run is not
     holding — no unanswered inherited row, no finding a warden closed — names
     nothing it could settle or reopen, and is dropped.
   - `:patch-hashes` is stamped when the tree the judge read is known — the
     final round's hashes, when that round landed no repair. A repair after the
     reading moved the tree, and a verdict stamped with the tree before it would
     carry onto a tree it never read."
  [v final standing]
  (let [raised    (raised-identities final)
        unraised  (into [] (comp (remove #(contains? raised (:finding %)))
                                 (map #(dissoc % :finding)))
                        (:unraised v))
        contra    (into {} (keep (fn [f] (when-let [c (:contradicts f)]
                                           [(bare-claim c)
                                            (str (or (:handle f) (:id f) (:title f)))])))
                        (open-across-run final))
        unver     (into [] (remove #(contains? contra (bare-claim (:invariant %))))
                        (:invariants-unverified v))
        owed      (into #{} (map (comp bare-claim :invariant)) (:invariants-unverified v))
        unmet     (reduce (fn [acc i]
                            (let [f (contra (bare-claim i))]
                              (if (and f (not-any? #(= (bare-claim i) (bare-claim (:invariant %))) acc))
                                (conj acc {:invariant (str i) :finding f})
                                acc)))
                          []
                          (concat (:invariants-held v) (map :invariant (:invariants-unverified v))))
        held      (into [] (remove #(or (contains? contra (bare-claim %))
                                        (contains? owed (bare-claim %))))
                        (:invariants-held v))
        answered  (into [] (keep (fn [{:keys [index answer]}]
                                   (when (< -1 index (count standing))
                                     (let [s (nth standing index)]
                                       (cond-> {:item (standing-text s) :answer answer}
                                         (map? s) (assoc :standing s))))))
                        (::standing-answers v))
        holding   (into #{} (keep :id) (stages/unanswered-inherited final))
        inherited (into [] (filter #(contains? holding (:id %))) (::inherited-answers v))
        closed    (into #{} (map finding-key) (closed-across-run final))
        contested (into [] (filter #(contains? closed (:id %))) (::contested-closes v))
        hashes    (sort (map str (:patch-hashes final)))]
    (cond-> (dissoc v ::standing-answers ::inherited-answers ::contested-closes
                    :unraised :invariants-held :invariants-unverified)
      (seq unraised) (assoc :unraised unraised)
      (seq inherited) (assoc :inherited-answered inherited)
      (seq contested) (assoc :contested-closes contested)
      (seq held)     (assoc :invariants-held held)
      (seq unver)    (assoc :invariants-unverified unver)
      (seq unmet)    (assoc :invariants-unmet unmet)
      (seq answered) (assoc :standing-answered answered)
      (and (seq hashes) (empty? (:fixes final)))
      (assoc :patch-hashes (vec hashes)))))

(defn- reopened
  "`f` reopened as a park when its close is one the verdict contests — `contested`
   is `{handle reason}` — and `f` otherwise."
  [contested f]
  (if-let [reason (and (= :closed (:disposition f)) (contested (finding-key f)))]
    (assoc f :disposition :park
           :because (str "the design verdict contests the warden's close: " reason))
    f))

(defn- restatus
  "The status a run ends as once the verdict's answers are folded in, `owed`
   being what it is then owed. Only the three statuses that are a reading of the
   remainder move; every other one names a condition the verdict did not touch.
   An `:unresolved` run whose remainder the verdict emptied ends as its last
   round would have without it: `:clean` after a quiet round, `:converged` after
   a warden's stop."
  [{:keys [status findings]} owed]
  (case status
    :unresolved          (cond (seq owed) :unresolved
                               (seq findings) :converged
                               :else :clean)
    (:converged :clean)  (if (seq owed) :unresolved status)
    status))

(defn ^{:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  settled
  "`final`, the loop's terminal ctx, with `v` — this run's design verdict, as
   `against-the-run` records it, or carried — folded in, so that every reader of
   the remainder reads one answered set. nil `v` leaves `final` as it is.

   The verdict is the only reader after the loop that reads code, and in a quiet
   run the only one at all: no warden runs on a round with no findings, and no
   reviewer is shown a row no layer holds. So what it answers has to change
   what the run is owed, or a run ends `:unresolved` — and asks a person to
   decide — over rows its own verdict found repaired:

   - an `:inherited-answered` row is marked `:answered`, which
     `stages/unanswered-of` reads as answered — so `still-owed`, `owed-rows`, the
     gate and the next run's `prior-open` all drop it, and its `:unplaced`
     standing entry goes with it;
   - a `:contested-closes` finding is reopened as a park, in the rulings and in
     the carried parks, so the gate puts it to a person;
   - a `:standing-answered` item leaves every list `report/stopped-on` builds
     `:standing` from, so neither the report, the `:review` entry nor the next
     run's `stages/prior-standing` holds it.

   Then `:owed` and `:status` are read again — see `restatus`. This must run
   BEFORE the `:review` entry and the report's `:reason` are written: they are
   what publishes the status, and the run after reads the entry."
  [final v]
  (if-not v
    final
    (let [answers   (into {} (map (juxt :id :evidence)) (:inherited-answered v))
          contested (into {} (map (juxt :id :reason)) (:contested-closes v))
          said      (:standing-answered v)
          rows      (get-in final [:carry :inherited-open])
          answered? #(and (not (:answered %)) (contains? answers (:id %)))
          gone      (into #{} (comp (filter answered?) (map stages/unplaced-standing)) rows)
          standing  (fn [items] (into [] (remove #(or (contains? gone %)
                                                      (answers-standing? said %)))
                                      items))
          reopen    (fn [fs] (mapv #(reopened contested %) fs))
          parks     (into {}
                          (comp (filter #(and (= :closed (:disposition %))
                                              (contains? contested (finding-key %))))
                                (map (fn [f] [(finding-key f)
                                              {:since (:iter final)
                                               :owner-layer (:owner-layer f)
                                               :kind (:kind f)
                                               :title (:title f)
                                               :because (:because (reopened contested f))}])))
                          (closed-across-run final))
          final'    (cond-> final
                      (seq rows)
                      (assoc-in [:carry :inherited-open]
                                (mapv #(if (answered? %)
                                         (assoc % :answered {:by "design-verdict"
                                                             :evidence (answers (:id %))})
                                         %)
                                      rows))

                      (seq parks)
                      (-> (update :findings reopen)
                          (update :history (fn [h] (mapv #(cond-> % (:findings %) (update :findings reopen))
                                                         h)))
                          (update-in [:carry :parks] merge parks))

                      (seq (:unplaced final))
                      (update :unplaced standing)

                      (seq (get-in final [:warden :standing]))
                      (update-in [:warden :standing] standing)

                      (seq (get-in final [:carry :inherited-standing]))
                      (update-in [:carry :inherited-standing] standing))
          owed      (vec (still-owed final'))]
      (assoc final' :owed owed :status (restatus final' owed)))))

(defn- as-questions
  "`stages/unanswered-inherited`, each row nothing in the loop could have
   answered tagged with why — see `inherited-questions`. A row whose layer has
   fallen out of the stack is in the run's `:unplaced`; a row with no
   disposition was left by a run whose warden never ruled."
  [final]
  (let [unplaced (set (:unplaced final))]
    (mapv (fn [row]
            (cond
              (contains? unplaced (stages/unplaced-standing row)) (assoc row :question :unplaced)
              (nil? (:disposition row))                          (assoc row :question :unruled)
              :else                                              row))
          (stages/unanswered-inherited final))))

(defn- plan-progress
  "Where the workstream at `cwd` is in the plan that governs it (`ws/plan-design` —
   the plan the board shows and the gate opens), or nil when it is unphased or its
   ledger could not be read. `ws/read-ws` throws on a malformed or unreadable
   ledger, and an unread position is a prompt the pass can still answer — not a
   run lost."
  [cwd]
  (try
    (when-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
      (when-let [w (ws/read-ws project ws-id)]
        (phase/progress (ws/plan-design project ws-id) (:entries w))))
    (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  run!
  "Run the verdict pass. Returns the verdict map, or nil when there is no design
   record to judge against, the agent no-ops, the answer is unparseable, or it
   names as broken a claim the design does not state — each means 'nothing to
   record', never a fabricated :sound.

   A fresh verdict is recorded as `against-the-run` reconciles it.

   A standing verdict this run gave no reason to revisit is carried forward
   instead of re-derived; see `still-answers?` for when that holds and
   `carried-forward` for what the entry then says. `stages/discover-prior-verdict`
   offers no verdict from an earlier phase, so none is ever carried across a gate."
  [{:keys [cwd run-id budget final report]}]
  (when-let [design (stages/discover-design-record cwd)]
    (let [;; nil for a verdict reached in another phase, which answered another
          ;; question: it is neither carried nor offered to the fresh pass as standing.
          prior    (stages/discover-prior-verdict cwd design)
          rounds   (or (get-in report [:summary :rounds]) 0)
          standing (vec (get-in report [:reason :standing]))]
      (if (still-answers? prior final report)
        (carried-forward prior)
        (let [prompt (build-prompt
                      {:design design
                       :baseline (stages/discover-baseline cwd design)
                       :stance (stages/read-stance (first (stages/project+ws-from-cwd cwd)))
                       :findings (still-open (:findings final))
                       :inherited (as-questions final)
                       :closed (closed-across-run final)
                       :history (mapv #(dissoc % :findings :patch-hashes) (:history final))
                       :fix-outcomes (stages/fix-outcomes (:history final) (:carry final))
                       :status (:status final)
                       :rounds rounds
                       :prior (as-reached cwd prior)
                       :progress (plan-progress cwd)
                       :standing standing})
              {:keys [num-turns result-error? result-text]}
              (agent/launch! {:run-id run-id :cwd cwd
                              :first-message prompt :budget budget
                              :err-file (str (fs/path (cstate/run-dir run-id) "agent.err.log"))})]
          (when-not (or (zero? (or num-turns 0)) result-error?)
            (some-> (parse result-text rounds (:seq design))
                    (held-to-claims (when (contains? design :model)
                                      (into #{} (map :id) (claim-model/claims design))))
                    (against-the-run final standing))))))))
