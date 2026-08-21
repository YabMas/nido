(ns nido.review.prompts
  "Prompt text for the review loop's codex + claude stages."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))   ; used by arbiter-prompt / fix-prompt (Tasks 5–6)

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

(def composition-kinds
  "The kinds of defect that exist ONLY in the composition of layers, each paired
   with the check that finds it.

   A closed set, deliberately. This pass's whole difficulty is that its findings
   are easy to confuse with ordinary ones, and a kind a reviewer has to name is a
   kind it cannot drift into: there is no bucket here for \"something about the
   stack\". `resources/review/composition_schema.json` carries the same list as
   its `kind` enum, and the two are asserted equal in the tests — a taxonomy the
   prompt teaches but the schema will not accept is a 400 on every round."
  [{:kind "broken-intermediate"
    :what (str "the stack does not hold at some layer's own tip. A layer leaves\n"
               "  the tree referring to something only a LATER layer supplies, or\n"
               "  breaks a contract a later layer restores. Every layer is\n"
               "  individually defensible and the stack still cannot land one PR\n"
               "  at a time.")
    :how  (str "for each layer bottom→top, take what its diff REMOVED or renamed\n"
               "  and search the tree AT THAT LAYER'S OWN REV for anything still\n"
               "  referring to it. Look here first: this is the defect the pass\n"
               "  exists for, and no other reader in this loop can reach it.")}
   {:kind "claim-falsified"
    :what (str "a layer's stated claim is contradicted by a layer above it. The\n"
               "  common form: a layer claims `mechanical`, or no behaviour\n"
               "  change, and a layer above quietly compensates for behaviour\n"
               "  that did change.")
    :how  (str "read each layer's claims, then read the layers above it for code\n"
               "  that only makes sense if that claim is false. Name the claim.")}
   {:kind "duplicated-across-layers"
    :what (str "two layers independently introduce the same thing — a helper, a\n"
               "  guard, a migration step — because bounded review guaranteed\n"
               "  neither could see the other.")
    :how  (str "for each thing a layer ADDS, look through the other layers for a\n"
               "  near-twin. The names will differ; the shape will not.")}
   {:kind "order-dependence"
    :what (str "a layer depends on something a layer ABOVE it establishes, so the\n"
               "  stack is in the wrong order. Distinct from broken-intermediate:\n"
               "  there the repair is to complete a layer, here it is to move one.")
    :how  (str "when a layer reaches for something it did not bring, find which\n"
               "  layer supplies it and check whether that layer sits above.")}
   {:kind "orphaned-by-scope"
    :what (str "something in this branch that EVERY layer's `out of scope` pushed\n"
               "  away, so no reviewer ever held it and no warden ever ruled on\n"
               "  it. You are the only pass that can see this hole, because you\n"
               "  are the only one that reads all the exclusions at once.")
    :how  (str "read the `out of scope` lines above as one set, and ask what in\n"
               "  the branch falls through all of them.")}
   {:kind "misplaced-seam"
    :what (str "the cut itself is wrong: one idea split so neither side is\n"
               "  coherent alone, or a layer boundary running through the middle\n"
               "  of a thing. **Report the seam, not a patch.** Saying where the\n"
               "  cut should have been is worth more than repairing either side,\n"
               "  and a fix applied to one side makes the wrong cut permanent.")
    :how  (str "you have usually already found this when a defect has no good\n"
               "  owner. When placing it on either layer feels arbitrary, that is\n"
               "  the cut telling you about itself — say so instead of choosing.")}
   {:kind "aggregate"
    :what (str "each layer's contribution is defensible alone and their sum is\n"
               "  not: a cost, a lock, a query, an allocation added once per\n"
               "  layer.")
    :how  (str "count the added instances of anything that has to stay rare\n"
               "  ACROSS layers rather than within one.")}])

(defn- kinds-block
  []
  (->> composition-kinds
       (map (fn [{:keys [kind what how]}]
              (str "- " kind "\n"
                   "  " what "\n"
                   "  HOW TO LOOK: " how "\n")))
       (str/join "\n")))

(defn- composition-layer-rows
  "The stack rendered for the composition pass: each layer with the range it
   contributes, the rev of the tree it leaves behind, and what it declared.

   Unlike `toc-block` this hands over REVISIONS, and that is the whole
   difference between the two. A warden is given the map precisely so it cannot
   re-derive the other layers; the composition pass is given the coordinates
   precisely so it can, because the states between layers are the only thing it
   is here to look at."
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
     "reviewer holding only that layer's diff, and a warden with authority over\n"
     "that layer will rule on what came back. Those findings exist. Producing\n"
     "them again is not harmless redundancy — it is the exact cost this layering\n"
     "was built to avoid, and it gets paid twice: once by you, once by whoever\n"
     "has to read two copies of the same thing and work out they are one.\n\n"
     "You are the only reader in this loop that can see the SEQUENCE. A layer\n"
     "reviewer saw one range and could not see the others. A reviewer of the\n"
     "finished branch sees only where the stack ENDS UP. Neither can see the\n"
     "states the branch passes THROUGH, and that is where a composition defect\n"
     "lives. Your range spans the whole branch so that you can reach those\n"
     "states — not so that you can review it flat.\n\n"
     "THE STACK, BOTTOM TO TOP:\n\n"
     (composition-layer-rows layers)
     "\n"
     "A layer's rev is the tree AS THAT LAYER LEAVES IT — exactly what that\n"
     "layer's PR would merge on its own, with nothing above it. Read it with\n"
     "`jj --ignore-working-copy file show -r <that rev> -- <path>`, and read one\n"
     "layer's own diff with the `--from`/`--to` pair given for it. Never `cat`:\n"
     "the working copy sits above every layer, so it shows you a state no single\n"
     "layer produces.\n\n"
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
     (kinds-block)
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
  "The stack's table of contents: what each layer claims and which files it
   touches.

   A warden holds only its own layer's diff, so this is the MAP and not the
   territory — enough to say \"that file is also touched by the layer below, so
   this is probably theirs\" deliberately, instead of escalating everything it
   cannot place. Deliberately no diffs: a warden that could read the layer below
   would re-derive it, which is the cost the layering exists to avoid."
  [toc]
  (when (seq toc)
    (str "THE STACK, BOTTOM TO TOP — what each layer claims, and what it touches.\n"
         "You see this as a map only; you are not reviewing these layers.\n"
         (->> toc
              (map-indexed
               (fn [i {:keys [label claim files]}]
                 (str (inc i) ". " label
                      (when claim (str " — claims: " claim)) "\n"
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

(def ^:private disposition-json
  (str "Return EXACTLY one fenced ```json block, nothing after it, matching:\n"
       "{\"dispositions\": [{\"id\": \"<finding id>\",\n"
       "                   \"disposition\": \"fix|closed|escalate\",\n"
       "                   \"authority\": \"out-of-scope|design|false-positive\",\n"
       "                   \"owner_guess\": \"<layer label, when escalating>\",\n"
       "                   \"because\": \"<one sentence>\"}]}\n"
       "Every finding you were given must appear exactly once.\n\n"))

(defn warden-prompt
  "The per-layer decider. Bounded to one layer's brief and findings, given the
   stack's table of contents as a map.

   Its `escalate` means \"above my pay grade\", NOT \"the design is in question\" —
   a warden cannot see far enough to make that call, and the arbiter can. That
   is a second, much more common use of a verb the engine already understands."
  [{:keys [brief findings toc layer answered]}]
  (str
   "You are the WARDEN of ONE layer of a stacked change: **" layer "**.\n"
   "You hold this layer's brief and the findings its review produced. You do\n"
   "NOT see the other layers' diffs — only what they claim, below.\n\n"
   disposition-json
   "- fix: a real defect, and it belongs to THIS layer.\n"
   "- closed: it does not need fixing here, AND you can name the authority —\n"
   "  \"out-of-scope\" (this layer's Out of scope names it), \"design\" (the\n"
   "  layer's own claim puts it behind a boundary), or \"false-positive\" (the\n"
   "  reviewer is wrong; say what they missed).\n"
   "- escalate: you cannot decide from here. The finding looks like it belongs\n"
   "  to another layer, or contradicts what a layer below claims, or questions\n"
   "  the design rather than the execution. Put your best guess at the owning\n"
   "  layer in owner_guess.\n\n"
   "Escalating is not a failure — it is the right answer whenever deciding would\n"
   "need a view you do not have. But do not escalate what your own brief already\n"
   "answers: that is what the brief is for.\n"
   "**Never close a finding without an authority.** \"Not important\" is not one,\n"
   "and neither is \"minor\". If you cannot name one, the disposition is fix or\n"
   "escalate.\n\n"
   (when-let [b (layer-brief-block brief)] (str b "\n"))
   (when-let [t (toc-block toc)] (str t "\n"))
   (when (seq answered)
     (str "ALREADY ANSWERED against this exact version of the layer. The reviewer\n"
          "starts each round fresh, so it can report these again — that is not new\n"
          "information. Close them the same way unless you can say why the answer\n"
          "no longer holds:\n"
          (->> answered
               (map (fn [{:keys [id title authority because]}]
                      (str "- " id " " title " → closed (" authority ")"
                           (when because (str ": " because)))))
               (str/join "\n"))
          "\n\n"))
   "Findings from this layer's review:\n\n"
   (findings-list findings)))

(defn- design-block
  "The design record, rendered for the arbiter. This is the yardstick: findings are
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
   registers; it cannot be violated by a line of code, and an arbiter that cites it
   against one is inventing specificity it does not have."
  [stance]
  (str "PROJECT STANCE — background framing only. Use it to reason about whether a\n"
       "boundary is carrying its weight or whether code is intent or drift. It is\n"
       "NOT a checklist: never cite it against a specific finding.\n"
       stance "\n"))

(defn- warden-says
  "What each layer's warden already decided, keyed by finding id. The arbiter
   overrules freely — but a warden that closed a finding by naming its own
   layer's Out of scope has answered it with something the arbiter cannot see,
   so reversing that silently is how a bounded review stops being bounded."
  [dispositions]
  (when (seq dispositions)
    (str "WHAT THE LAYER WARDENS ALREADY DECIDED:\n"
         (->> dispositions
              (map (fn [{:keys [id disposition authority owner-guess because]}]
                     (str "- " id " → " (name (or disposition :none))
                          (when authority (str " (" authority ")"))
                          (when owner-guess (str " · guesses owner: " owner-guess))
                          (when because (str " · " because)))))
              (str/join "\n"))
         "\n\nA warden escalated what it could not decide from inside one layer.\n"
         "Those are yours. A warden that closed something named its authority;\n"
         "overrule it only if you can say why that authority does not hold.\n\n")))

(defn arbiter-prompt
  "Build the arbiter prompt. The arbiter is the only thing in the loop with a
   view across layers, so attribution — which layer a finding BELONGS to, as
   against which one reported it — is its job and nothing else's.

   Report-only (no tools): everything it reasons from is inlined here. That is
   deliberate and load-bearing. It is the component that decides to interrupt a
   human, so its inputs have to be reconstructable from the report afterwards."
  [{:keys [findings history design stance toc dispositions]}]
  (str
   "You are the ARBITER in an automated code-review loop over a STACK of layers.\n"
   "You are the only reader with a view across all of them.\n\n"
   "Return EXACTLY one fenced ```json block, nothing after it, matching:\n"
   "{\"decision\": \"continue|stop|escalate\",\n"
   " \"reason\": \"...\",\n"
   " \"findings\": [{\"id\": \"<finding id>\",\n"
   "               \"owner_layer\": \"<layer label from the stack below>\",\n"
   "               \"disposition\": \"fix|closed|deviation|park\",\n"
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
   "Then exactly one disposition:\n"
   "- fix: a real defect. It will be handed to a fixer working on owner_layer.\n"
   "- closed: no fix, AND you name the authority — duplicate (of another id in\n"
   "  this round), out-of-scope (a layer's Out of scope names it), design (the\n"
   "  record puts it behind a boundary), spun-out (it is already filed as a ref),\n"
   "  false-positive (the reviewer is wrong; say what they missed).\n"
   "- deviation: the finding shows a layer's stated CLAIM is not true, and it is\n"
   "  not something to fix — the claim was overstated. Put the claim in `of`.\n"
   "  The claim is NOT edited: it is what we intended, and the deviation is what\n"
   "  actually happened. Both are kept.\n"
   "- park: no fix, and not closed either — the loop has no move for it. Two\n"
   "  cases, and only these two. Either the finding contradicts a named\n"
   "  invariant of the design (the escalate case, for the human). Or its `kind`\n"
   "  is misplaced-seam or order-dependence: those say the CUT is in the wrong\n"
   "  place or the layers are in the wrong ORDER, and this loop can fix a line\n"
   "  but cannot re-cut a stack. Sending either to a fixer buys a patch on one\n"
   "  side of a bad seam — which makes the bad seam permanent and lets the\n"
   "  round converge reporting success.\n\n"
   "**Nothing is dropped.** Every finding gets one of those four, and closed and\n"
   "deviation each require their extra field. A `closed` with no authority, or a\n"
   "`deviation` with no claim in `of`, is not a decision — it is a shrug, and it\n"
   "is how a review quietly stops reviewing. If you cannot name one, the answer\n"
   "is fix.\n\n"
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
   (warden-says dispositions)
   "History of prior rounds (findings + what was fixed):\n"
   (pr-str history) "\n\n"
   "THIS ROUND'S FINDINGS:\n\n"
   (findings-list findings)))
