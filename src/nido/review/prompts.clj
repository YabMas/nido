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
  [{:keys [brief findings toc layer]}]
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
   "A finding from the `stack` pass exists only in the COMPOSITION of layers —\n"
   "assign it to the HIGHEST layer involved, because that is the first point in\n"
   "the stack at which the defect actually exists; every layer below it is\n"
   "individually fine.\n\n"
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
   "- park: this is the escalate case, for the human. Only ever for a finding\n"
   "  that contradicts a named invariant.\n\n"
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
          "merits, and do NOT park anything: with no stated invariant there is\n"
          "nothing for a finding to contradict.\n\n"))
   (when stance (str (stance-block stance) "\n"))
   (when-let [t (toc-block toc)] (str t "\n"))
   (warden-says dispositions)
   "History of prior rounds (findings + what was fixed):\n"
   (pr-str history) "\n\n"
   "THIS ROUND'S FINDINGS:\n\n"
   (findings-list findings)))
