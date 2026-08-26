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

(defn layer-brief-block
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

   `:requires` is the field a disposition is not a decision without. A close with
   no authority and a deviation with no claim are shrugs, and a shrug is how a
   review quietly stops reviewing."
  [{:disposition :fix
    :means (str "a real defect. It will be handed to a fixer working on\n"
                "  owner_layer.")}
   {:disposition :closed
    :requires :authority
    :means (str "no fix, AND you name the authority — duplicate (of another id\n"
                "  in this round), out-of-scope (a layer's Out of scope names it),\n"
                "  design (the record puts it behind a boundary), spun-out (it is\n"
                "  already filed as a ref), false-positive (the reviewer is wrong;\n"
                "  say what they missed).")}
   {:disposition :deviation
    :requires :of
    :means (str "the finding shows a layer's stated CLAIM is not true, and it is\n"
                "  not something to fix — the claim was overstated. Put the claim\n"
                "  in `of`. The claim is NOT edited: it is what we intended, and\n"
                "  the deviation is what actually happened. Both are kept.")}
   {:disposition :park
    :means (str "no fix, and not closed either — the loop has no move for it. Two\n"
                "  cases, and only these two. Either the finding contradicts a named\n"
                "  invariant of the design (the escalate case, for the human). Or its\n"
                "  `kind` is misplaced-seam or order-dependence: those say the CUT is\n"
                "  in the wrong place or the layers are in the wrong ORDER, and this\n"
                "  loop can fix a line but cannot re-cut a stack. Sending either to\n"
                "  a fixer buys a patch on one side of a bad seam — which\n"
                "  makes the bad seam permanent and lets the round converge\n"
                "  reporting success.")}])

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
       "and it is how a review quietly stops reviewing. If you cannot name one,\n"
       "the answer is fix.\n\n"))

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
   {:kind "duplicated-across-layers" :asks :cut
    :what (str "two layers independently introduce the same thing — a helper, a\n"
               "  guard, a migration step — because bounded review guaranteed\n"
               "  neither could see the other.")
    :how  (str "for each thing a layer ADDS, look through the other layers for a\n"
               "  near-twin. The names will differ; the shape will not.")}
   {:kind "order-dependence" :asks :wiring
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
   {:kind "misplaced-seam" :asks :cut
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

(defn composition-block
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
  [{:keys [layers]}]
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

(defn fix-prompt
  "Instruction to fix the given findings. Do NOT commit — the engine commits."
  [{:keys [findings]}]
  (str
   "Fix the following code-review findings in this working directory. Make the\n"
   "MINIMAL change that resolves each. Do NOT commit — the orchestrator commits.\n\n"
   (->> findings
        (map (fn [f]
               (str "- [P" (:priority f) "] " (:title f) "\n"
                    "  file: " (:file f) ":" (:line-start f) "-" (:line-end f) "\n"
                    "  " (:body f))))
        (clojure.string/join "\n\n"))))

(defn- bullets [xs] (str/join "\n" (map #(str "- " %) xs)))

(defn toc-block
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
   a finding that re-proposes a rejected alternative is *answered* rather than new."
  [{:keys [shape invariants rejected standing]}]
  (str "THE DESIGN THIS CHANGE COMMITTED TO — judge the findings against this:\n"
       "Shape: " shape "\n"
       "Invariants:\n" (bullets invariants) "\n"
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
  "What earlier rounds already closed, grouped by the layer that reported it.

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
    (str "ALREADY CLOSED IN AN EARLIER ROUND, against this exact version of each\n"
         "layer. A reviewer reporting one of these again has not found anything\n"
         "new. Close it the same way unless you can say why that answer no longer\n"
         "holds:\n"
         (->> answered
              (map (fn [{:keys [label answered]}]
                     (str "· " label "\n"
                          (->> answered
                               (map (fn [{:keys [id title authority because]}]
                                      (str "  - " id " " title
                                           " → closed (" authority ")"
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

(defn warden-prompt
  "Build the warden prompt. The warden is the only thing in the loop with a
   view across layers, so attribution — which layer a finding BELONGS to, as
   against which one reported it — is its job and nothing else's.

   Report-only (no tools): everything it reasons from is inlined here. That is
   deliberate and load-bearing. It is the component that decides to interrupt a
   human, so its inputs have to be reconstructable from the report afterwards."
  [{:keys [findings history design stance toc answered seen]}]
  (str
   "You are the WARDEN of an automated code-review loop over a STACK of layers.\n"
   "You are the only reader with a view across all of them.\n\n"
   "Return EXACTLY one fenced ```json block, nothing after it, matching:\n"
   "{\"decision\": \"continue|stop|escalate\",\n"
   " \"reason\": \"...\",\n"
   " \"findings\": [{\"id\": \"<finding id>\",\n"
   "               \"same_as\": \"<id of the earlier-round finding this is the\n"
   "                             same defect as, or null>\",\n"
   "               \"owner_layer\": \"<layer label from the stack below>\",\n"
   "               \"disposition\": \""
   (clojure.string/join "|" (map (comp name :disposition) disposition-vocabulary))
   "\",\n"
   "               \"authority\": \"duplicate|out-of-scope|design|spun-out|false-positive\",\n"
   "               \"of\": \"<the claim a deviation departs from>\",\n"
   "               \"because\": \"<one sentence>\"}]}\n"
   "Every finding below must appear exactly once.\n\n"
   "DECISION:\n"
   "- continue: something is worth fixing now.\n"
   "- stop: nothing left worth fixing; remaining items are nits.\n"
   "- escalate: a finding CONTRADICTS A NAMED INVARIANT of the design below —\n"
   "  the design is in question, not its execution. Name the invariant in your\n"
   "  reason. Do not escalate because a finding merely feels fundamental.\n\n"
   "PER FINDING — owner_layer first. The layer that REPORTED a finding is often\n"
   "not the layer that caused it: a defect seen from an upper layer frequently\n"
   "originates below. Use the file lists in the stack map to attribute it, and\n"
   "say so in `because` when you move one.\n"
   "A finding from the `stack` pass exists only in the COMPOSITION of layers,\n"
   "and names the ones it spans after `across`. Use them:\n"
   "assign it to the HIGHEST layer involved, because that is the first point in\n"
   "the stack at which the defect actually exists; every layer below it is\n"
   "individually fine. A `stack` finding that spans only ONE layer is by its own\n"
   "account not a composition defect — that layer's own review holds it, so\n"
   "close it `duplicate` unless nothing from that layer reported it.\n\n"
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
   "A structural finding is where park lives, and it is the one you must NOT\n"
   "hand to a fixer when it contradicts an invariant below: patching a design\n"
   "question makes it disappear without anyone deciding it.\n\n"
   (if design
     (str (design-block design) "\n")
     (str "No design record on this workstream. Weigh the findings on their own\n"
          "merits, and do NOT park anything for contradicting an invariant: with\n"
          "no stated invariant there is nothing for a finding to contradict. A\n"
          "misplaced-seam or order-dependence finding still parks — that case\n"
          "does not turn on the design record.\n\n"))
   (when stance (str (stance-block stance) "\n"))
   (when-let [t (toc-block toc)] (str t "\n"))
   (answered-block answered)
   (seen-block seen)
   "History of prior rounds (findings + what was fixed):\n"
   (pr-str history) "\n\n"
   "THIS ROUND'S FINDINGS:\n\n"
   (findings-list findings)))
