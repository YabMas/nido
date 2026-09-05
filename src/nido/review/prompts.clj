(ns nido.review.prompts
  "Prompt text for the review loop's codex + claude stages."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))   ; used by warden-prompt / fix-prompt (Tasks 5–6)

(def review-prompt
  "codex review-guidelines prompt (lifted from codex's review template)."
  (slurp (io/resource "review/review_prompt.md")))

(defn- brief-field
  [label v]
  (when-not (str/blank? (str v))
    (str label ": " v "\n")))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  layer-brief-block
  "The bounding preamble for a review aimed at ONE layer of a stack, built from
   that layer's `/stack` §5 brief. nil when there is no brief — a whole-stack
   review is not bounded by one, and saying nothing is better than saying
   \"no brief\", which reads as an instruction to go wide.

   `Out of scope` is the field that makes bounded review work: without it every
   reviewer re-derives the whole change and the stack's benefit is lost. It is
   stated as a prohibition here rather than as context, because a reviewer given
   it as context flags the item anyway and lets the reader sort it out."
  [{:keys [subject mode claims verify lane out-of-scope]}]
  (when (or claims verify out-of-scope)
    (str
     "THIS REVIEW IS BOUNDED TO ONE LAYER OF A STACKED CHANGE.\n\n"
     (brief-field "Layer" subject)
     (brief-field "Review mode" (some-> mode name))
     (brief-field "Claims" claims)
     (brief-field "Verify" verify)
     (brief-field "Lane" lane)
     (brief-field "Out of scope" out-of-scope)
     "\nHow to use this:\n"
     "- Review ONLY what this layer changes. The range below is its whole diff.\n"
     "- Out of scope is a PROHIBITION, not context. Do not flag those items —\n"
     "  they belong to another layer or another ticket, and someone has already\n"
     "  decided where. Flagging them re-derives the whole change, which is the\n"
     "  cost this layering exists to avoid.\n"
     "- Claims is what this layer asserts about itself. A finding that shows a\n"
     "  claim is FALSE is the most valuable thing you can return — say plainly\n"
     "  which claim, and what you saw that contradicts it.\n"
     "- Verify names the concrete checks this layer should pass. Discharge them\n"
     "  and report any that fail.\n"
     "- A `mechanical` layer asserts uniformity: your job is to find the one\n"
     "  place that got special handling, not to reopen the decision. A\n"
     "  `judgment` layer owns a decision: weigh it.\n\n")))

(def ^:private fixer-account-chars
  "How much of a fixer's own account the next round's reviewer is shown.

   Enough for the summary a fixer ends on — what it changed, and what it could
   not check — and short of the transcript some end on instead. The whole text
   is on the fix row in report.json either way, so the cap costs a reader
   nothing and bounds a prompt that is otherwise sized by however talkative one
   agent was."
  1200)

(defn- handed-line
  [{:keys [title sweep]}]
  (str "    · " title (when sweep "  [SWEEP]") "\n"))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :string]]}
  prior-fixes-block
  "What a fixer already landed on the range under review, the findings it was
   handed, and what it said about them.

   The reviewer starts cold every round and is shown a diff, never a history, so
   nobody in the loop is ever asked the one question a repair raises: did that
   fix close what it was handed. A sweep is where it costs most — the fixer is
   told to repair one instance and then audit for its siblings, so a partially
   completed sweep is its expected failure and it leaves the rest at exactly the
   lines this reviewer is already reading. One such defect was repaired in round
   1, went unreported in round 2, and came back at the same window in rounds 3
   and 4, recognised each time only by the warden chaining handles across the
   gap.

   The account is handed over as a CLAIM rather than as a record, and stated as
   one: a reviewer that believes it has been talked out of the diff, which is
   worse than not being told. What it buys is something falsifiable — a fixer
   that says it covered the enum check but not the cross-field rule has named
   where to look."
  [prior-fixes]
  (when (seq prior-fixes)
    (str
     "A FIXER ALREADY WORKED ON WHAT YOU ARE REVIEWING, EARLIER IN THIS RUN.\n\n"
     "Each entry is a repair that landed, what it was handed, and the fixer's\n"
     "own words about it. Those words are a CLAIM about the code, not a record\n"
     "of it — check them against the range below rather than accepting them.\n\n"
     (->> prior-fixes
          (map (fn [{:keys [round commit findings account]}]
                 (str "- round " round
                      (when commit (str ", landed " commit))
                      (when (seq findings) " — handed:") "\n"
                      (apply str (map handed-line findings))
                      (when-not (str/blank? (str account))
                        (str "  the fixer says: "
                             (let [a (str/trim (str account))]
                               (if (> (count a) fixer-account-chars)
                                 (str (subs a 0 fixer-account-chars) " …[truncated]")
                                 a))
                             "\n")))))
          (str/join "\n"))
     "\n"
     "A finding here that is STILL TRUE is the most valuable thing you can\n"
     "return: say which one, and what you saw that the repair did not reach. A\n"
     "SWEEP was told to fix its instance and then audit for the rest, so a\n"
     "sibling it missed is at these same lines and is yours to find.\n\n")))

(def disposition-vocabulary
  "What may become of a finding. One entry per destination: the word the warden
   answers with, what it means, and the extra field it may not omit.

   Data rather than prose because the vocabulary has two halves that have to
   agree — what the warden is TOLD a disposition means, published here into its
   prompt, and what the code ACCEPTS as one, read off this same list by
   `nido.review.stages`. Kept apart, they drift: a word can be offered to the
   warden that no consumer recognises, or accepted by a consumer that the warden
   is never told to use, and neither shows up as a failure. Same arrangement, and
   for the same reason, as `composition-kinds` and the schema codex builds from
   it.

   `:requires` is the field a disposition is not a decision without, and
   `nido.review.stages` enforces it: a ruling that omits the field is not a
   decision and is read as `fix`. A close with no authority and a deviation with
   no claim are shrugs, and a shrug is how a review quietly stops reviewing.

   `:one-of` closes the field further, where the answers are enumerable. An
   authority is one of five named grounds; a deviation's claim and a decline's
   reason are prose and have no such list. It is here rather than beside the
   parser for the same reason the words are: a set of grounds the warden is
   offered and the parser refuses is a destination that silently becomes `fix`.

   `:settles?` marks a disposition that ENDS a finding: it was decided, nobody
   owes anything further, and a later round re-raising it has found nothing new.
   It is what convergence, the carried answers and the run's remainder all read,
   so they cannot disagree about whether a finding is still open.

   `:kept?` splits the settled ones by what the decision left behind. A `:closed`
   finding is not this branch's — a duplicate, out of scope, already filed, or
   simply wrong — and once it is decided there is nothing to carry. A `:declined`
   or a `:deviation` is TRUE of the branch and stays true: the decision was to
   live with it. Neither is owed to anyone, so neither is open; but a record that
   drops the second kind has quietly deleted a defect a human agreed to ship."
  [{:disposition :fix
    ;; Deliberately does not name owner_layer. On an unlayered branch that field
    ;; is not in the shape at all, and a vocabulary entry that references it
    ;; would reintroduce the very prompt the flat case exists to suppress. Where
    ;; there ARE layers, the PER FINDING block says which one the fixer gets.
    :means "a real defect. It will be handed to a fixer."}
   {:disposition :closed
    :settles? true
    :requires :authority
    :one-of ["duplicate" "out-of-scope" "design" "spun-out" "false-positive"]
    :means (str "no fix, AND you name the authority — duplicate (of another id\n"
                "  in this round), out-of-scope (a layer's Out of scope names it),\n"
                "  design (the record puts it behind a boundary), spun-out (it is\n"
                "  already filed as a ref), false-positive (the reviewer is wrong;\n"
                "  say what they missed).")}
   {:disposition :deviation
    :settles? true
    :kept? true
    :requires :of
    :means (str "the finding shows a layer's stated CLAIM is not true, and it is\n"
                "  not something to fix — the claim was overstated. Put the claim\n"
                "  in `of`. The claim is NOT edited: it is what we intended, and\n"
                "  the deviation is what actually happened. Both are kept.")}
   {:disposition :declined
    :settles? true
    :kept? true
    :requires :because
    :means (str "the finding is TRUE and we are not acting on it. Not a\n"
                "  duplicate, not out of scope, not wrong — a real defect this\n"
                "  branch is choosing to leave. Say why in `because`, in one\n"
                "  sentence a reader who disagrees could argue with. A decline\n"
                "  without a reason is indistinguishable from an oversight, and\n"
                "  the next round has no way to tell it was ever decided.")}
   {:disposition :recut
    :means (str "the remedy is the SHAPE of the stack rather than a line in it,\n"
                "  and the loop can perform it. Its `kind` is one of the kinds\n"
                "  listed under RECUT below, and its `across` names the layers it\n"
                "  spans. The loop will move or merge them and let the next round\n"
                "  judge the result. Do NOT send one of these to a fixer: a patch\n"
                "  on one side of a bad seam makes the bad seam permanent and lets\n"
                "  the round converge reporting success.")}
   {:disposition :park
    :means (str "no fix, and nothing above fits: this is a decision rather than\n"
                "  a repair, and it is for the human. Two grounds, and only two.\n"
                "  (a) The finding contradicts a named invariant of the design.\n"
                "  (b) RECURRENCE: you have already had this defect fixed in an\n"
                "  earlier round — you set `same_as` on it — and it is back. A\n"
                "  third attempt at something two fixes did not settle is not a\n"
                "  repair the loop knows how to make, whatever the fix prompt is\n"
                "  told. Ground (b) does NOT need a design record: it is a fact\n"
                "  about this run's own history, which you are holding.\n"
                "  Otherwise: a defect whose remedy is the stack's shape is\n"
                "  `recut`, and one that is true and not worth doing is\n"
                "  `declined`.")}])

(defn- values-for
  "The values a required field may take, in the order the vocabulary declares
   them, or nil where the field is prose.

   Two blocks of the warden prompt name the authorities and `stages` refuses a
   close outside them, so all three come off the one entry: an authority the
   warden is invited to give and the parser rejects is a close that silently
   becomes a fix."
  [field]
  (some #(when (= field (:requires %)) (:one-of %)) disposition-vocabulary))

;; Read by disposition-block, which is rendered long after load. Declared rather
;; than moved so the kinds taxonomy stays beside the reviewer prompt it teaches.
(declare composition-kinds)

(defn- kinds-asking [asks] (filter #(= asks (:asks %)) composition-kinds))

(defn- cut-routing-block
  "Which composition kinds the loop can act on, and which it cannot — derived
   from the taxonomy rather than written out beside it.

   The distinction is `:remedy`. A kind that has one names a move the reshape
   stage can actually perform, so `recut` is a real destination for it. A kind
   that asks about the CUT and has no remedy — `claim-falsified` is the case —
   names a defect whose repair is a decision about where a boundary belongs, and
   there is no mechanical move for that. Hardcoding two kind names here sent
   those to fixers instead, and a fixer's minimal edit on one side of a
   misplaced seam makes the seam permanent while the round reports success."
  []
  (let [recut  (filter :remedy composition-kinds)
        no-fix (remove :remedy (kinds-asking :cut))]
    (str "RECUT — kinds the loop can act on mechanically. Use `recut` for these:\n"
         (->> recut
              (map #(str "- " (:kind %) " → " (name (:remedy %)) "\n"))
              (apply str))
         "\n"
         "NOT A FIXER'S WORK — kinds that ask where a boundary belongs and name\n"
         "no move the loop can make. These are decisions: `park` them, or\n"
         "`deviation` them against the claim they contradict. Handing one to a\n"
         "fixer asks for a minimal edit to a question about shape, and what comes\n"
         "back makes the seam harder to see, not gone:\n"
         (->> no-fix (map #(str "- " (:kind %) "\n")) (apply str))
         "\n")))

(defn- disposition-block
  "The vocabulary rendered for the warden, plus the rule that binds it.

   The count comes off the list rather than being written out: a destination
   added without the sentence following it would tell the warden that every
   finding gets one of four while offering it five."
  []
  (str "Then exactly one disposition:\n"
       (->> disposition-vocabulary
            (map (fn [{:keys [disposition means]}]
                   (str "- " (name disposition) ": " means "\n")))
            (apply str))
       "\n"
       "**Nothing is dropped.** Every finding gets one of those "
       (count disposition-vocabulary) ", and\n"
       (->> disposition-vocabulary
            (filter :requires)
            (map #(name (:disposition %)))
            (clojure.string/join " and "))
       " each require their extra field. A `closed` with no authority, or\n"
       "a `deviation` with no claim in `of`, is not a decision — it is a shrug,\n"
       "and it is how a review quietly stops reviewing. So the loop does not\n"
       "take one: a ruling that omits its field, or closes on an authority\n"
       "outside\n"
       "  " (clojure.string/join ", " (values-for :authority)) "\n"
       "is read as `fix` and handed to a fixer. If you cannot name the field,\n"
       "the answer is `fix` — say so yourself, and put your reasoning in\n"
       "`because`, where the fixer reads it.\n\n"
       (cut-routing-block)))

(def composition-kinds
  "The kinds of defect that exist ONLY in the composition of layers, each paired
   with the check that finds it.

   `:asks` is which of the pass's two questions the kind answers — :cut, whether
   the change was decomposed into the right pieces, or :wiring, whether those
   pieces hold together. The primer groups them by it, because that is the first
   thing a reviewer has to settle about a defect it has found and the two lead
   somewhere different: a cut defect is usually not fixable in place at all.

   A closed set, deliberately. This pass's whole difficulty is that its findings
   are easy to confuse with ordinary ones, and a kind a reviewer has to name is a
   kind it cannot drift into: there is no bucket here for a vague unease about
   the stack. `nido.review.codex/composition-schema` builds its `kind` enum from
   this same list — a taxonomy the prompt teaches but the schema will not accept
   is not a soft mismatch, it is a 400 on every round."
  [{:kind "broken-intermediate" :asks :wiring
    :what (str "the stack does not hold at some layer's own tip. A layer leaves\n"
               "  the tree referring to something only a LATER layer supplies, or\n"
               "  breaks a contract a later layer restores. Every layer is\n"
               "  individually defensible and the stack still cannot land one PR\n"
               "  at a time.")
    :how  (str "for each layer bottom→top, take what its diff REMOVED or renamed\n"
               "  and search the tree AT THAT LAYER'S OWN REV for anything still\n"
               "  referring to it. This is the wiring question at its most\n"
               "  concrete, and the layer tips are the only place to answer it.")}
   {:kind "claim-falsified" :asks :cut
    :what (str "a layer's stated claim is contradicted by a layer above it. The\n"
               "  common form: a layer claims `mechanical`, or no behaviour\n"
               "  change, and a layer above quietly compensates for behaviour\n"
               "  that did change.")
    :how  (str "read each layer's claims, then read the layers above it for code\n"
               "  that only makes sense if that claim is false. Name the claim.")}
   {:kind "duplicated-across-layers" :asks :cut :remedy :fold
    :what (str "two layers independently introduce the same thing — a helper, a\n"
               "  guard, a migration step — because bounded review guaranteed\n"
               "  neither could see the other.")
    :how  (str "for each thing a layer ADDS, look through the other layers for a\n"
               "  near-twin. The names will differ; the shape will not.")}
   {:kind "order-dependence" :asks :wiring :remedy :reorder
    :what (str "a layer depends on something a layer ABOVE it establishes, so the\n"
               "  stack is in the wrong order. Distinct from broken-intermediate:\n"
               "  there the repair is to complete a layer, here it is to move one.")
    :how  (str "when a layer reaches for something it did not bring, find which\n"
               "  layer supplies it and check whether that layer sits above.")}
   {:kind "orphaned-by-scope" :asks :cut
    :what (str "something in this branch that EVERY layer's `out of scope` pushed\n"
               "  away, so no reviewer ever held it and nothing ever ruled on\n"
               "  it. You are the only pass that can see this hole, because you\n"
               "  are the only one that reads all the exclusions at once.")
    :how  (str "read the `out of scope` lines above as one set, and ask what in\n"
               "  the branch falls through all of them.")}
   {:kind "misplaced-seam" :asks :cut :remedy :fold
    :what (str "the cut itself is wrong: one idea split so neither side is\n"
               "  coherent alone, or a layer boundary running through the middle\n"
               "  of a thing. **Report the seam, not a patch.** Saying where the\n"
               "  cut should have been is worth more than repairing either side,\n"
               "  and a fix applied to one side makes the wrong cut permanent.")
    :how  (str "you have usually already found this when a defect has no good\n"
               "  owner. When placing it on either layer feels arbitrary, that is\n"
               "  the cut telling you about itself — say so instead of choosing.")}
   {:kind "aggregate" :asks :wiring
    :what (str "each layer's contribution is defensible alone and their sum is\n"
               "  not: a cost, a lock, a query, an allocation added once per\n"
               "  layer.")
    :how  (str "count the added instances of anything that has to stay rare\n"
               "  ACROSS layers rather than within one.")}])

(def ^:private asks-heading
  {:cut    "THE CUT — are these the right pieces?"
   :wiring "THE WIRING — do the pieces hold together?"})

(defn- kinds-block
  [asks]
  (->> composition-kinds
       (filter #(= asks (:asks %)))
       (map (fn [{:keys [kind what how]}]
              (str "- " kind "\n"
                   "  " what "\n"
                   "  HOW TO LOOK: " how "\n")))
       (str/join "\n")
       (str (asks-heading asks) "\n\n")))

(defn- composition-layer-rows
  "The stack rendered for the composition pass: each layer with the range it
   contributes, the rev of the tree it leaves behind, and what it declared.

   Unlike `toc-block` this hands over REVISIONS, and that is the whole
   difference between the two. The warden is given the map precisely so it
   cannot re-derive the other layers; the composition pass is given the
   coordinates precisely so it can, because the states between layers are the
   only thing it is here to look at."
  [layers]
  (->> layers
       (map-indexed
        (fn [i {:keys [label from tip claim out-of-scope files]}]
          (str (inc i) ". " label "\n"
               "   its own diff:  --from " from " --to " tip "\n"
               "   its tree:      -r " tip "\n"
               (when-not (str/blank? (str claim))
                 (str "   claims:        " claim "\n"))
               (when-not (str/blank? (str out-of-scope))
                 (str "   out of scope:  " out-of-scope "\n"))
               (when (seq files)
                 (str "   touches:       " (str/join ", " (take 12 files))
                      (when (> (count files) 12)
                        (str " … +" (- (count files) 12) " more"))
                      "\n")))))
       (str/join "\n")))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  composition-block
  "The primer for the pass that reviews the stack as a COMPOSITION rather than as
   one wide diff.

   Without it that pass is byte-for-byte the flat-branch reviewer, pointed at
   `base..@` and never told a stack exists — so it re-derives every layer, and
   the findings that are actually its own arrive indistinguishable from the ones
   the layer reviewers already hold. The pass's power was never the wider range:
   it is the only reader in the loop that can reach the INTERMEDIATE revisions,
   and this block is what hands them over.

   nil below two layers, which is not a degradation — there is no composition to
   review, the whole-stack target is the branch review, and it should be primed
   as one."
  [{:keys [layers already-reported]}]
  (when (> (count layers) 1)
    (str
     "THIS IS THE COMPOSITION PASS OVER A STACKED CHANGE. Where it narrows the\n"
     "instructions above, it wins.\n\n"
     "Every layer of this stack has ALREADY been reviewed on its own, by a\n"
     "reviewer holding only that layer's diff, and one warden with a view\n"
     "across layers will rule on what came back. Those findings exist. Producing\n"
     "them again is not harmless redundancy — it is the exact cost this layering\n"
     "was built to avoid, and it gets paid twice: once by you, once by whoever\n"
     "has to read two copies of the same thing and work out they are one.\n\n"
     "Your subject is not the code. It is the CUT and the WIRING — whether this\n"
     "change was decomposed into the right pieces, and whether those pieces hold\n"
     "together. Two questions, and every finding you return answers one of them:\n\n"
     "  THE CUT — are these the right pieces? Right boundaries, nothing built\n"
     "  twice because two layers could not see each other, nothing falling\n"
     "  through the gap between what they all excluded, each layer's stated\n"
     "  claim actually true of what it contains.\n\n"
     "  THE WIRING — do the pieces hold together? Each one has to stand up where\n"
     "  it sits, given only what the layers below it actually supply, and what\n"
     "  they add up to has to be defensible too.\n\n"
     "Nobody else in this loop is asked either question. A layer reviewer was\n"
     "handed a piece and asked whether the piece is correct; it cannot see the\n"
     "cut it was handed, let alone judge it. And a reviewer of the finished\n"
     "branch sees only where the stack ENDS UP — one tree, with the seams gone.\n"
     "Your range spans the whole branch so that you can see the seams, not so\n"
     "that you can review it flat.\n\n"
     "THE STACK, BOTTOM TO TOP:\n\n"
     (composition-layer-rows layers)
     "\n"
     "A layer's rev is the tree AS THAT LAYER LEAVES IT — exactly what that\n"
     "layer's PR would merge on its own, with nothing above it. That is how the\n"
     "wiring question gets answered rather than guessed: you can go and look at\n"
     "each piece standing on its own, which is the state every reviewer before\n"
     "you was structurally unable to see.\n\n"
     "  the tree as of a layer:\n"
     "    jj --ignore-working-copy file show -r <that layer's rev> -- <path>\n"
     "  one layer's own diff:\n"
     "    the --from/--to pair given for it above\n\n"
     "Never `cat`: the working copy sits above every layer, so it shows you a\n"
     "state no single layer produces.\n\n"
     "WHAT IS YOURS, AND WHAT IS NOT\n\n"
     "One sentence decides it: **if you can state the defect without naming two\n"
     "layers, it is not yours.**\n\n"
     "\"`x` can be nil here\" is a layer's own finding, and that layer's reviewer\n"
     "has it. \"`x` can be nil here because layer 1 stopped guaranteeing it and\n"
     "layer 3 never noticed\" is yours — and you must be able to show BOTH\n"
     "halves out of the diffs, not assume the second. If the second half is not\n"
     "there, you are holding an ordinary defect.\n\n"
     "A layer's `out of scope` binds you too wherever it names something that\n"
     "layer deliberately left to another. What it does not cover is the case\n"
     "where EVERY layer pushed the same thing away — that one is yours, below.\n\n"
     (when (seq already-reported)
       ;; The pass runs fresh every round and is the only reader that can see
       ;; across layers. Told nothing about its own earlier output, it has no way
       ;; to notice it is returning the same seam a third time rather than
       ;; looking further — so a round costs a full pass and produces a repeat.
       (str "WHAT YOU ALREADY REPORTED IN THIS RUN\n\n"
            "These came from THIS pass in earlier rounds. They are on the record\n"
            "and a warden has already ruled on them. Reporting one again buys the\n"
            "run nothing and costs it a round:\n\n"
            (->> already-reported
                 (map (fn [f]
                        (str "- round " (:round f) ": " (:title f)
                             (when-let [k (:kind f)] (str "  [" k "]"))
                             "\n")))
                 (apply str))
            "\n"
            "If one of these is genuinely unresolved, say so plainly and say what\n"
            "is different now — do not restate it as though it were new. If they\n"
            "are all settled, spend this round somewhere you have not looked.\n\n"))
     "THE KINDS OF DEFECT THAT ARE YOURS\n\n"
     (kinds-block :cut)
     "\n"
     (kinds-block :wiring)
     "\n"
     "OUTPUT\n\n"
     "Report ONLY composition findings. Returning none is a good outcome and a\n"
     "real one: a well-cut stack has no defect in its composition, and this pass\n"
     "says so by coming back empty. Do not pad it with what the layer reviews\n"
     "already hold.\n\n"
     "- `kind` is one of the values above.\n"
     "- `layers` names every layer the defect spans, by the label exactly as\n"
     "  written above, in stack order. Two or more, always. Filling it with one\n"
     "  is the test above telling you this finding belongs to that layer.\n"
     "- `code_location` points at the most representative site. For a\n"
     "  misplaced-seam finding that is where the cut runs through — not a line\n"
     "  to patch.\n"
     "- `body` must say what EACH named layer contributes to the defect. A body\n"
     "  that describes only the symptom cannot be acted on: the reader cannot\n"
     "  tell which layer to change, and being able to tell is the entire value\n"
     "  of this pass.\n\n")))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  fix-prompt
  "Instruction to fix the given findings. Do NOT commit — the engine commits.

   Carries three things the warden had already written and the fixer never saw.

   `:because` is the warden's own sentence about this finding, and it is
   addressed to this reader: it says why the finding is real, or which layer it
   was moved to and why. It was produced every round, stored on the ruling, and
   rendered to nobody.

   The owning layer's brief — what it claims, what it declared out of scope —
   bounds the edit. Without it \"make the MINIMAL change\" is the only guidance
   there is, and for a defect that spans a seam the minimal change is a patch on
   whichever side the finding happened to be reported from.

   `:sweep` widens one finding into its family. The warden recognises a recurring
   class unprompted and had no channel to say so, so rounds surfaced its members
   one per round: a call site fixed, the next one found, ten rounds for ten
   instances of one defect.

   Two clauses then bound the reach the prompt would otherwise narrow itself,
   against one reading of it: that the fixer's subject is the patch.

   A sweep searches the defect CLASS, over every file the change touched and not
   over its diff. \"Audit this layer\" reads as the diff, so the sibling that
   survives a sweep is the pre-existing line beside the one just edited. Where a
   sibling is somewhere this fixer may not touch, naming it in the final message
   is what puts it in front of the next round: that text lands on the fix row as
   `:account` and `prior-fixes-block` renders it to the reviewer.

   MINIMAL says how much to change, not what may be left broken. For a
   declarative artifact — a spec, a schema, a policy table — the smallest edit
   that resolves a finding is often exactly the edit that makes the artifact
   contradict itself: a type declared required that no rule in the file produces
   is a defect the next round reports as new."
  [{:keys [findings layer]}]
  (str
   "Fix the following code-review findings in this working directory. Make the\n"
   "MINIMAL change that resolves each. Do NOT commit — the orchestrator commits.\n\n"
   "MINIMAL bounds how much you change, not what you may leave broken. Re-read\n"
   "each artifact you edit WHOLE before you finish, and leave it self-consistent:\n"
   "for a spec, a schema or a policy table the smallest edit that resolves a\n"
   "finding is very often the one that makes the artifact contradict itself — a\n"
   "type declared required that no rule in the file produces, a case the table no\n"
   "longer covers — and the next round finds that as a fresh defect. If restoring\n"
   "consistency would go past what you were asked for, say so in your final\n"
   "message rather than landing the contradiction.\n\n"
   (when (or (:claim layer) (:out-of-scope layer))
     (str "YOU ARE FIXING ONE LAYER OF A STACKED CHANGE"
          (when (:label layer) (str " — " (:label layer))) ".\n"
          (when-let [c (:claim layer)]
            (str "It claims: " c "\n"))
          (when-let [o (:out-of-scope layer)]
            (str "It declared OUT OF SCOPE: " o "\n"
                 "That is a prohibition. Do not fix those things here; another\n"
                 "layer owns them, and someone has already decided which.\n"))
          "\n"))
   (->> findings
        (map (fn [f]
               (str "- [P" (:priority f) "] " (:title f) "\n"
                    "  file: " (:file f) ":" (:line-start f) "-" (:line-end f) "\n"
                    (when-let [k (:kind f)]
                      (str "  kind: " k
                           (when-let [a (seq (:across f))]
                             (str " — spans " (str/join ", " a)))
                           "\n"))
                    (when-let [b (:because f)]
                      (str "  the reviewer of the whole stack says: " b "\n"))
                    (when (:sweep f)
                      (str "  SWEEP: this is one instance of a recurring defect.\n"
                           "  Fix it, then find its siblings and fix those too.\n"
                           "  The search is over the defect CLASS, not over this\n"
                           "  change's diff: read every file this change touched\n"
                           "  WHOLE, because the sibling that survives a sweep is\n"
                           "  usually the pre-existing line beside the one you just\n"
                           "  edited. Finding them one per round is what this is\n"
                           "  here to stop — the minimal change rule does not apply\n"
                           "  to the search, only to each edit. A sibling you may\n"
                           "  not touch here — another layer's, or outside this\n"
                           "  change — is to be NAMED in your final message, never\n"
                           "  silently left.\n"))
                    "  " (:body f))))
        (str/join "\n\n"))))

(defn- bullets [xs] (str/join "\n" (map #(str "- " %) xs)))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  toc-block
  "The stack's table of contents: what each layer claims, what it declared out of
   scope, and which files it touches.

   This is the MAP and not the territory — enough to say \"that file is also
   touched by the layer below, so this is probably theirs\" deliberately, instead
   of guessing at everything it cannot place. Deliberately no diffs: a reader
   that could see the layer below would re-derive it, which is the cost the
   layering exists to avoid.

   `out of scope` is here because it is the only authority in the closing
   vocabulary that lives in a layer's brief rather than in the design record or
   the round itself. Cited without being shown, it is a word standing in for
   evidence."
  [toc]
  (when (seq toc)
    (str "THE STACK, BOTTOM TO TOP — what each layer claims, what it rules out,\n"
         "and what it touches.\n"
         "You see this as a map only; you are not reviewing these layers.\n"
         (->> toc
              (map-indexed
               (fn [i {:keys [label claim out-of-scope files]}]
                 (str (inc i) ". " label
                      (when claim (str " — claims: " claim)) "\n"
                      (when out-of-scope
                        (str "   out of scope: " out-of-scope "\n"))
                      (when (seq files)
                        (str "   touches: " (str/join ", " (take 12 files))
                             (when (> (count files) 12)
                               (str " … +" (- (count files) 12) " more")) "\n")))))
              (str/join ""))
         "\n")))

(defn- findings-list
  [findings]
  (->> findings
       (map (fn [f]
              (str "- id " (:id f) "  [P" (:priority f) "/"
                   (name (or (:reach f) :unclear)) "]"
                   (when-let [l (:from-layer f)] (str " reported-by " l))
                   (when-let [k (:kind f)] (str " · " (name k)))
                   (when (seq (:layers f))
                     (str " · across " (str/join " + " (:layers f))))
                   "\n  " (:title f)
                   "\n  " (:file f) ":" (:line-start f) "-" (:line-end f)
                   "\n  " (:body f))))
       (str/join "\n\n")))

(defn- design-block
  "The design record, rendered for the warden. This is the yardstick: findings are
   judged against these invariants and nothing else. :rejected is included because
   a finding that re-proposes a rejected alternative is *answered* rather than new.

   The claimed decomposition is here for a different reason than the invariants.
   The warden judges a STACK, and without the design's own layer claims it cannot
   see that the stack in front of it has three layers where the design named two
   — a mismatch that is a finding about the cut, and one nothing else in the loop
   can reach. `record.clj` already renders it this way for the record judge; the
   warden was the reader that needed it and did not get it."
  [{:keys [shape invariants rejected standing layers]}]
  (str "THE DESIGN THIS CHANGE COMMITTED TO — judge the findings against this:\n"
       "Shape: " shape "\n"
       "Invariants:\n" (bullets invariants) "\n"
       (when (seq layers)
         (str "CLAIMED DECOMPOSITION — one claim per layer, bottom to top. The stack\n"
              "you are judging should correspond to these. If it does not — a layer\n"
              "the design never named, two claims folded into one, an order that\n"
              "does not match — that is a finding about the CUT, and you are the\n"
              "only reader positioned to make it:\n"
              (bullets (map #(str (:claim %)
                                  (when-let [m (:mode %)] (str " (" (name m) ")")))
                            layers))
              "\n"))
       (when (seq rejected)
         (str "Already considered and rejected. A finding that re-proposes one of\n"
              "these is ANSWERED, not new — unless it gives a reason the rejection\n"
              "no longer holds:\n"
              (bullets (map #(str (:alternative %) " — rejected because "
                                  (:why-not %)) rejected))
              "\n"))
       (when standing
         (str "Relation to the project's stance: " (name (:relation standing))
              (when-let [n (:note standing)] (str " — " n)) "\n"))))

(defn- stance-block
  "The project's stance, as framing only. It primes reasoning about boundaries and
   registers; it cannot be violated by a line of code, and an warden that cites it
   against one is inventing specificity it does not have."
  [stance]
  (str "PROJECT STANCE — background framing only. Use it to reason about whether a\n"
       "boundary is carrying its weight or whether code is intent or drift. It is\n"
       "NOT a checklist: never cite it against a specific finding.\n"
       stance "\n"))

(defn- answered-block
  "What earlier rounds already settled, grouped by the layer that reported it.

   The reviewer starts fresh every round, so it re-reports what was closed —
   that is not new information, and re-adjudicating it every round is how a
   converging stack stops converging. These hang off each layer's patch hash
   (`nido.review.cache/answered`), so they are answers about THAT content and
   vanish the moment the layer changes.

   They are this stage's own prior closes handed back to it, which is why they
   are stated as a default rather than as a ruling to defer to: it may reverse
   one, but it has to say what changed."
  [answered]
  (when (seq answered)
    (str "ALREADY SETTLED IN AN EARLIER ROUND, against this exact version of each\n"
         "layer. A reviewer reporting one of these again has not found anything\n"
         "new. Answer it the same way unless you can say why that answer no\n"
         "longer holds:\n"
         (->> answered
              (map (fn [{:keys [label answered]}]
                     (str "· " label "\n"
                          (->> answered
                               (map (fn [{:keys [id title disposition authority because]}]
                                      (str "  - " id " " title
                                           " → " (name (or disposition :closed))
                                           (when authority (str " (" authority ")"))
                                           (when because (str ": " because)))))
                               (str/join "\n")))))
              (str/join "\n"))
         "\n\n")))

(defn- seen-block
  "Every finding an earlier round raised, by id and title, oldest first.

   The `same_as` question cannot be answered from the round history beside it in
   this prompt: that carries what each round DECIDED, and deciding whether this
   defect is that one needs the defect. Titles only — a body per finding per
   round would grow the prompt without helping, because what a fresh reviewer
   rewrites between rounds is exactly the title."
  [seen]
  (when (seq seen)
    (str "ALREADY RAISED IN AN EARLIER ROUND — the pool `same_as` points into.\n"
         "A finding here was seen before; whether it is the same DEFECT as one\n"
         "below is what you are being asked.\n"
         (->> seen
              (map (fn [{:keys [round id title]}]
                     (str "  r" round " " id "  " title)))
              (str/join "\n"))
         "\n\n")))

(defn- fixer-declines-block
  "The findings a fixer was handed, refused, and argued against — carried from
   the round it happened in, because nothing else carries it.

   A fixer that changes nothing leaves the finding at `:fix`, so the round after
   it re-derives the same repair from a fresh session and hands it to a fixer
   whose own refutation it has never read. One fixer refused a P1 with four
   hundred words out of the shipped bundle it had gone and read; the finding's
   disposition did not move, the words reached report.json and nothing else, and
   only a conflict ending the run stopped the next round paying for them again.

   Deliberately NOT called a decline in the text below. `declined` is a
   disposition the warden issues — a decision that the defect is real and this
   branch is shipping it — and a fixer refusing work it was handed has decided
   nothing. One word for both is how a warden comes to read this block as a
   ruling already made."
  [declines]
  (when (seq declines)
    (str "A FIXER WAS HANDED THESE AND CHANGED NOTHING\n"
         "It read the finding, refused to repair it, and said why. That is an\n"
         "argument, not a ruling: the finding is still open and nobody has\n"
         "answered it. Answer it now — accept the argument and settle the\n"
         "finding, or reject it and say what the fixer missed. Handing it back\n"
         "unchanged buys another refusal from the same session:\n"
         (->> declines
              (map (fn [{:keys [layer since findings reason]}]
                     (str "- " (or layer "the branch") ", refused in round " since "\n"
                          (->> findings
                               (map (fn [{:keys [id title]}]
                                      (str "    · " id " " title "\n")))
                               (apply str))
                          (when-not (str/blank? (str reason))
                            (str "  its argument: " reason "\n")))))
              (apply str))
         "\n")))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  warden-prompt
  "Build the warden prompt. The warden is the only thing in the loop with a
   view across layers, so attribution — which layer a finding BELONGS to, as
   against which one reported it — is its job and nothing else's.

   Report-only (no tools): everything it reasons from is inlined here. That is
   deliberate and load-bearing. It is the component that decides to interrupt a
   human, so its inputs have to be reconstructable from the report afterwards."
  [{:keys [findings history design stance toc answered seen parked fixer-declines]}]
  ;; A branch with no layers is reviewed flat, and there is then no layer label
  ;; for a finding to be attributed to. Asked for one anyway, the warden supplied
  ;; the only stack-shaped thing it had — a file path — on every ruling of the
  ;; run, and the loop absorbed the nonsense silently. Asking only where an
  ;; answer exists is cheaper than validating one that never should have been
  ;; requested.
  (let [layered? (boolean (seq toc))]
   (str
   "You are the WARDEN of an automated code-review loop over "
   (if layered? "a STACK of layers.\n" "a single unlayered branch.\n")
   (if layered?
     "You are the only reader with a view across all of them.\n\n"
     "There are no layers: the branch was reviewed flat.\n\n")
   "Return EXACTLY one fenced ```json block, nothing after it, matching:\n"
   "{\"decision\": \"continue|stop|escalate\",\n"
   " \"reason\": \"...\",\n"
   " \"findings\": [{\"id\": \"<finding id>\",\n"
   "               \"same_as\": \"<id of the earlier-round finding this is the\n"
   "                             same defect as, or null>\",\n"
   (if layered?
     "               \"owner_layer\": \"<layer label from the stack below>\",\n"
     "")
   "               \"disposition\": \""
   (clojure.string/join "|" (map (comp name :disposition) disposition-vocabulary))
   "\",\n"
   "               \"authority\": \""
   (clojure.string/join "|" (values-for :authority))
   "\",\n"
   "               \"of\": \"<the claim a deviation departs from>\",\n"
   "               \"sweep\": <true if this is one instance of a recurring class>,\n"
   "               \"because\": \"<one sentence>\"}]}\n"
   "Every finding below must appear exactly once.\n\n"
   "DECISION:\n"
   "- continue: something is worth fixing now.\n"
   "- stop: nothing left worth fixing; remaining items are nits.\n"
   "- escalate: a finding CONTRADICTS A NAMED INVARIANT of the design below —\n"
   "  the design is in question, not its execution. Name the invariant in your\n"
   "  reason. Do not escalate because a finding merely feels fundamental.\n\n"
   (if-not layered?
     (str "PER FINDING — there is no owner_layer to give and the field is not in\n"
          "the shape above. Do not invent one, and do not put a file path where a\n"
          "layer label would go: a flat branch has exactly one place a defect can\n"
          "live, and naming it adds nothing.\n\n")
     (str
      "PER FINDING — owner_layer first. It is the layer the fixer will work on.\n"
      "The layer that REPORTED a finding is often not the layer that caused it:\n"
      "a defect seen from an upper layer frequently originates below. Use the\n"
      "file lists in the stack map to attribute it, and say so in `because` when\n"
      "you move one.\n"
      "A finding from the `stack` pass exists only in the COMPOSITION of layers,\n"
      "and names the ones it spans after `across`. Use them:\n"
      "assign it to the HIGHEST layer involved, because that is the first point in\n"
      "the stack at which the defect actually exists; every layer below it is\n"
      "individually fine. A `stack` finding that spans only ONE layer is by its own\n"
      "account not a composition defect — that layer's own review holds it, so\n"
      "close it `duplicate` unless nothing from that layer reported it.\n"
      "That rule is for ONE layer and stops there. A `stack` finding spanning TWO\n"
      "OR MORE layers is a claim about the cut, and it stays one even when the\n"
      "repair it suggests is already being made somewhere: the remedy can be a\n"
      "duplicate while the observation is not. Do not close it `duplicate` — the\n"
      "close absorbs the only cut-level signal the round produced, and a run can\n"
      "converge having silently discarded the finding that mattered most. Rule on\n"
      "the observation itself: `deviation` against the claim it departs from, or\n"
      "`park` when it contradicts a named invariant. Either leaves a human\n"
      "something to read.\n\n"))
   "SWEEP — is this one of many?\n"
   "Set `sweep` true on a `fix` when the finding is one INSTANCE of a defect\n"
   "class rather than a one-off: the same mistake made at several call sites,\n"
   "the same guard missing in several places. The fixer is otherwise told to make\n"
   "the minimal change, so it repairs the one line it was handed and the next\n"
   "round finds the next instance — ten rounds for ten instances of one defect.\n"
   "With `sweep` it is told to fix this one and then audit its layer for the\n"
   "siblings. You are the reader that sees the class; nothing else can.\n\n"
   "SAME_AS — is this a defect we have already seen?\n"
   "A reviewer starts fresh every round and writes its own words, so one defect\n"
   "comes back under a new title, at a line the last round's fixes moved. You are\n"
   "the only reader who sees this round beside every earlier one, so recognising\n"
   "it is yours. When a finding below is the SAME DEFECT as one already seen —\n"
   "not merely similar, not merely the same file — put that earlier id in\n"
   "`same_as`. Otherwise null.\n"
   "This is what lets the loop tell a defect it cannot move from a run that is\n"
   "still making progress. Guessing costs more than leaving it null: two defects\n"
   "welded together are reported as one, and the second is never fixed.\n\n"
   (disposition-block)
   "Each finding carries a reach the reviewer assigned: local (a defect inside\n"
   "the current design), structural (about where a boundary sits — the reviewer\n"
   "could see shape but not intent), or unclear. It is not a severity.\n"
   "A structural finding is where park and recut live, and it is the one you\n"
   "must NOT hand to a fixer when it contradicts an invariant below: patching a\n"
   "design question makes it disappear without anyone deciding it.\n\n"
   (if design
     (str (design-block design) "\n")
     (str "No design record on this workstream. Weigh the findings on their own\n"
          "merits, and do NOT park anything for contradicting an invariant: with\n"
          "no stated invariant there is nothing for a finding to contradict.\n"
          "Park on RECURRENCE still applies — it rests on this run's history,\n"
          "which you have, and not on any record.\n"
          "The RECUT kinds above are still `recut`, and the kinds listed as NOT A\n"
          "FIXER'S WORK are still not a fixer's — neither case turns on the\n"
          "design record.\n\n"))
   (when stance (str (stance-block stance) "\n"))
   (when-let [t (toc-block toc)] (str t "\n"))
   (answered-block answered)
   (when (seq parked)
     ;; A park is never raised again — that is what a park IS — so it leaves the
     ;; findings the moment the reviewer stops mentioning it and the next warden
     ;; has no idea it exists. Fifteen rounds re-adjudicated one seam from
     ;; scratch, with prose minutes as the only memory.
     ;;
     ;; "Parked" rather than "you parked": a recut the reshape stage refused
     ;; lands here too. Its finding was withheld from the fixers on the warden's
     ;; own ruling and then had no path at all, which is the state a park
     ;; describes whoever put it in that state.
     (str "STILL PARKED, FROM EARLIER ROUNDS OF THIS RUN\n"
          "No one has answered these — you parked them, or the loop refused the\n"
          "recut they asked for and has no other move. They are open. Do not\n"
          "re-adjudicate them from scratch, and do not treat their absence from\n"
          "this round's findings as resolution — nothing raises a park twice:\n"
          (->> parked
               (map (fn [p] (str "- since round " (:since p) ": " (:title p)
                                 (when-let [b (:because p)] (str "\n    " b)) "\n")))
               (apply str))
          "\n"))
   (fixer-declines-block fixer-declines)
   (seen-block seen)
   "History of prior rounds (findings + what was fixed):\n"
   (pr-str history) "\n\n"
   "THIS ROUND'S FINDINGS:\n\n"
   (findings-list findings))))
