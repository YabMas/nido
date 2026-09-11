(ns nido.review.prompts
  "Prompt text for the review loop's codex + claude stages."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]     ; used by warden-prompt / fix-prompt (Tasks 5–6)
   ;; For `seam-closure` only. The three ways a seam can be closed are a fact
   ;; about the record's schema, which lives there; rendering them here in a
   ;; second `case` would drop a fourth closure kind silently on whichever side
   ;; was not edited.
   [nido.coordinator.report :as report]))

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
   it as context flags the item anyway and lets the reader sort it out.

   `Verify` is stated as evidence for the same reason. The reviewer works under
   a read-only sandbox and cannot execute a test, so a Verify field naming a
   test namespace is discharged by READING it — each assertion the layer left
   standing, held against what the layer changed. Phrase it as checks to run
   and a reviewer with the contradicted assertion open in front of it reports
   nothing: it has no reading of an instruction it cannot carry out."
  [{:keys [subject mode claims verify lane out-of-scope deviations]}]
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
     "- Claims is what this layer asserts about itself, and CHECKING IT IS\n"
     "  YOURS. A finding that shows a claim is FALSE is the most valuable thing\n"
     "  you can return — say plainly which claim, and what you saw that\n"
     "  contradicts it. The commonest case is the sharp one: a layer labelled\n"
     "  `mechanical` with a site that got special handling is not mechanical,\n"
     "  and that is a real decision smuggled into a diff a reviewer was about\n"
     "  to skim.\n"
     "- If you CANNOT check a claim from this diff alone, say so and say which\n"
     "  claim. Do not guess, and do not go looking above — you cannot see up\n"
     "  there, and a claim that needs the layer above to verify was written at\n"
     "  the wrong altitude. That is itself the finding, and it is one only you\n"
     "  can make: nobody else is holding this claim against this diff.\n"
     "- Verify names the concrete checks this layer should pass, and the test\n"
     "  namespaces it names are EVIDENCE you must read rather than background.\n"
     "  Read each at <head> and hold every assertion still standing there\n"
     "  against what this layer changed — you cannot run them, so that reading\n"
     "  is the whole discharge. An assertion this layer UPDATED is the author\n"
     "  keeping its own brief true; one it contradicted and left standing is a\n"
     "  finding, and it goes red the moment anybody runs it.\n"
     "- A `mechanical` layer asserts uniformity: your job is to find the one\n"
     "  place that got special handling, not to reopen the decision. A\n"
     "  `judgment` layer owns a decision: weigh it.\n\n"
     ;; The claim is the thing this reviewer is told to attack, so a claim
     ;; already known to be too strong is the defect it is most likely to find —
     ;; and a `deviation` is precisely the ruling that this one is not going to
     ;; be repaired. Withheld, the reviewer spends the round rediscovering it and
     ;; the warden spends another settling it again.
     (when (seq deviations)
       (str "WHERE THIS CLAIM IS ALREADY KNOWN NOT TO HOLD\n\n"
            "A previous run found each of these, and it was decided the claim\n"
            "was overstated rather than that the code was wrong. Both are kept:\n"
            "the claim says what was intended, these say what happened. They are\n"
            "SETTLED — do not report them again.\n\n"
            (->> deviations (map #(str "- " % "\n")) (apply str))
            "\n"
            "A defect these do not cover is still yours, including one in the\n"
            "same area. Say what is different about it.\n\n")))))

(def ^:private fixer-account-chars
  "How much of a fixer's own account a reader is shown, PER FINDING the fixer was
   handed.

   Enough for the summary a fixer ends on — what it changed, and what it could
   not check — and short of the transcript some end on instead. The whole text
   is on the fix row in report.json either way, so the cap costs a reader
   nothing and bounds a prompt that is otherwise sized by however talkative one
   agent was.

   Per handed finding rather than per account, because an account's length is
   set by how much the fixer was asked to do: a batch of three findings is three
   repairs to describe, and a flat budget spends the same characters on it as on
   one. Held flat, every account of one run — seven of them, 2001 to 3251 chars
   — was over the cap."
  1200)

(def ^:private min-section-chars
  "The smallest budget worth cutting a section into, and so the cap on how many
   sections one account is cut into.

   A cut section is a head, an elision marker and a tail. Below about this much
   there is no room for a sentence at either end, and the marker — which is not
   account text and is not paid for out of the budget — costs more than the
   fragments it separates. So a budget serves at most `budget` /
   `min-section-chars` sections, and adjacent sections past that are grouped and
   cut as one: a shorter account read in whole treatments beats a longer one
   read in fragments."
  300)

(defn- cut-middle
  "`s` reduced to `budget` characters by removing its MIDDLE.

   The elision says how much it dropped, so a reader can tell a whole text from
   a cut one and knows the two halves it is holding are not adjacent."
  [s budget]
  (if (<= (count s) budget)
    s
    (let [half (quot budget 2)]
      (str (subs s 0 half)
           "\n  …[" (- (count s) (* 2 half)) " chars elided]…\n  "
           (subs s (- (count s) half))))))

(def ^:private section-opener
  "A line that opens a new section of an account: a markdown heading, a numbered
   item, or a bold lead-in — the three ways a fixer marks off one repair's
   treatment from the next.

   A BULLET is deliberately not one. A list under a heading is a single
   treatment, and splitting at every bullet hands each item a share too small to
   hold a sentence."
  #"^\s{0,3}(?:#{1,6}\s|\d{1,2}[.)]\s|\*\*\S)")

(defn- account-sections
  "`account` split at each `section-opener` line. Text before the first one is a
   section too — the summary an account opens with."
  [account]
  (->> (str/split-lines account)
       (reduce (fn [secs line]
                 (if (or (empty? secs) (re-find section-opener line))
                   (conj secs [line])
                   (update secs (dec (count secs)) conj line)))
               [])
       (mapv #(str/join "\n" %))))

(defn- merge-adjacent
  "`secs` merged into at most `k` groups of adjacent sections, near-equal in
   count. The identity when there are already `k` or fewer."
  [secs k]
  (if (<= (count secs) k)
    secs
    (->> secs
         (map-indexed (fn [i s] [(quot (* i k) (count secs)) s]))
         (partition-by first)
         (mapv #(str/join "\n" (map second %))))))

(defn- section-budgets
  "`budget` split across sections of lengths `lens`, so that no section is cut
   while another keeps more than its equal share: one that already fits its
   share is kept whole and returns the difference to the sections that do not.

   Equal shares rather than proportional ones, because the long section of an
   account is the transcript and the short ones are the conclusions. Sizing a
   share by the text it is cutting spends the budget on exactly the part this
   cut exists to drop."
  [lens budget]
  (loop [budgets (vec lens)
         open    (set (range (count lens)))
         budget  budget]
    (let [share (quot budget (max 1 (count open)))
          whole (filter #(<= (nth lens %) share) open)]
      (if (seq whole)
        (recur budgets
               (reduce disj open whole)
               (- budget (reduce + (map #(nth lens %) whole))))
        (reduce #(assoc %1 %2 share) budgets open)))))

(defn- account-excerpt
  "A fixer's account, cut to what its handed findings buy — from the middle of
   each SECTION, so both ends of every one of them survive.

   The two ends of a treatment are the two things worth reading and they say
   different things: it opens with what the fixer changed and closes with what
   it could not — the verification it could not run, the sibling in another
   layer it was ordered to name rather than touch. What sits between them is
   transcript.

   That premise is about one repair, not about the message that carries them
   all. An account of two findings closes the first one in the MIDDLE of its
   text, so a single cut through the whole account is aimed straight at it: on a
   4058-char account at budget 2400, one contiguous 1658-char elision removed
   the entire treatment of the second finding — including the residual its
   repair had deliberately left, which the post-loop verdict then found breaks a
   design invariant no reviewer had been shown it. Cutting each section against
   its own share keeps the closing sentence of every repair, which is the one
   the next round needs.

   The budget buys account text; the elision markers are extra, and
   `min-section-chars` is what bounds how many of them there can be."
  [account handed]
  (let [a (str/trim (str account))
        budget (* fixer-account-chars (max 1 handed))]
    (if (<= (count a) budget)
      a
      (let [secs (merge-adjacent (account-sections a)
                                 (max 1 (quot budget min-section-chars)))]
        (str/join "\n" (map cut-middle secs (section-budgets (map count secs) budget)))))))

(defn- handed-line
  [{:keys [title sweep]}]
  (str "    · " title (when sweep "  [SWEEP]") "\n"))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :string]]}
  prior-fixes-block
  "What a fixer already did to the range under review — rows of
   `nido.review.stages/fix-outcomes` — the findings it was handed, what it said
   about them, and whether any edit of its is in the code.

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
   where to look.

   A REFUSED entry is the same claim about an edit that is NOT in the range: the
   rebase would not take the repair and it was put back, so the code is what the
   round before it read and the finding is untouched. It is the one entry here
   whose findings are still true by construction, and saying so is the whole
   point of carrying it — the round that had none of this re-read a
   byte-identical patch and returned `correct` on the P2 it was hiding. Its
   commit is not shown: this reader cannot look at it, and a change id beside a
   landed one would read as a claim that the edit is in the range.

   An ARGUED entry wrote no edit at all, so the range is again what the round
   before read, and the account is the fixer's argument for leaving it — where
   it looked and why it stopped. That can be the most decisive evidence a run
   holds: one such account recorded an integration test executed and the
   refusal firing on exactly the case its finding described, and the reviewer
   of that byte-identical patch was shown none of it. It is an argument, not a
   verdict on the code, for the reason an account is a claim. ARGUED and not
   declined, because `declined` is the warden's word for a defect the branch
   decided to ship, and one word for both reads a fixer's refusal as a
   decision; see `fixer-declines-block`.

   An ABOVE entry landed, but on a higher layer, for a finding this layer's
   reviewer raised. It is the inverse of REFUSED: the edit is in the merged tree
   and not in the range, so the finding under it is still true of the code this
   reviewer reads and already closed where the stack lands. Unsaid, that reads
   as a defect nobody has touched, and one layer re-raised a defect repaired
   above it in every round after the repair. Its commit IS shown, beside the
   layer that holds it: it is in the branch, and opening it is how this reader
   checks the claim.

   Each marker is explained only where an entry carries it. The block is paid
   for on every round of every layer a fixer touched, and most of them hold
   only landed repairs."
  [prior-fixes]
  (when (seq prior-fixes)
    (let [has?   (set (map :outcome prior-fixes))
          above? (some :above prior-fixes)]
      (str
       "A FIXER ALREADY WORKED ON WHAT YOU ARE REVIEWING, EARLIER IN THIS RUN.\n\n"
       "Each entry is what a fixer was handed, what it did about it, and the fixer's\n"
       "own words about it. Those words are a CLAIM about the code, not a record\n"
       "of it — check them against the range below rather than accepting them.\n\n"
       (when (has? :refused)
         (str "An entry marked REFUSED is NOT in the range below. The rebase would not\n"
              "take that repair and it was put back, so the code you are reading is\n"
              "unchanged and every finding under it is still true. Its account says\n"
              "where the fixer looked, not what the code now does.\n\n"))
       (when (has? :declined)
         (str "An entry marked ARGUED wrote no edit. The fixer read what it was\n"
              "handed, changed nothing, and said why, so the code you are reading is\n"
              "what that round read. Its account is where the fixer looked and why it\n"
              "stopped — evidence to check against the code, not a ruling on it.\n\n"))
       (when above?
         (str "An entry marked ABOVE is a repair for something reported from THIS\n"
              "layer that landed on a HIGHER one. You read this layer at its own\n"
              "head, beneath that repair, so the defect is still in the range below\n"
              "— and gone in the merged tree. Its commit is in the branch, not in\n"
              "your range; read it there if you need to see what it changed.\n\n"))
       (->> prior-fixes
            (map (fn [{:keys [outcome round commit findings account conflicted layer above]}]
                   (str "- round " round
                        (case outcome
                          :refused  (str ", REFUSED"
                                         (when (seq conflicted)
                                           (str " — it conflicted " (str/join ", " conflicted))))
                          :declined ", ARGUED — no edit was written"
                          (cond
                            above  (str ", ABOVE — landed on " layer
                                        (when commit (str " at " commit)))
                            commit (str ", landed " commit)))
                        (when (seq findings) " — handed:") "\n"
                        (apply str (map handed-line findings))
                        (when-not (str/blank? (str account))
                          (str (case outcome
                                 :refused  "  the fixer said of the edit that was put back: "
                                 :declined "  the fixer's argument for changing nothing: "
                                 "  the fixer says: ")
                               (account-excerpt account (count findings))
                               "\n")))))
            (str/join "\n"))
       "\n"
       "A finding here that is STILL TRUE is the most valuable thing you can\n"
       "return: say which one, and what you saw that the repair did not reach. A\n"
       "SWEEP was told to fix its instance and then audit for the rest, so a\n"
       "sibling it missed is at these same lines and is yours to find.\n"
       (when (has? :refused)
         (str "Under a REFUSED repair they are all still true, and nothing else in\n"
              "this run knows it.\n"))
       (when (has? :declined)
         (str "Under an ARGUED one nothing moved either: whether they are still true\n"
              "is what the fixer's argument claims to settle, and you are reading the\n"
              "code it is about — a finding it argued away that still stands is yours\n"
              "to report, with what the argument missed.\n"))
       (when above?
         (str "Under an ABOVE one, still true here is expected and is NOT a finding:\n"
              "reporting it again re-opens a defect the stack has already closed.\n"
              "What is yours is anything at those lines the repair does not reach.\n"))
       "\n"))))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :string]]}
  standing-needs-block
  "The item an earlier run's design verdict left outstanding, put to the one
   reader that can turn it into work.

   A verdict is written after the loop has returned, so it has no reader inside a
   run at all: it names a defect at a file and a line, and the next run's
   reviewers — the only agents that read code against a range — have never been
   told it exists. One item was named by two consecutive verdicts of the same
   branch, in the same words, each time as still unraised.

   Put as a QUESTION about the range rather than as an item to report. The
   verdict read a different tree than the one below, so whether it is still true
   is exactly what this reviewer is being asked; a reviewer that reports it back
   unverified has laundered an old claim into a fresh finding, and the round
   after it inherits a defect nobody looked at. Out of range is the commonest
   answer and it is a silence, not a miss — the item may live in code this layer
   never touches.

   Stated as one round's judgment with its verdict, not as a standing truth, for
   the reason `prior-fixes-block` states an account as a claim: a reviewer told
   something authoritative stops reading the diff."
  [{:keys [round verdict needs]}]
  (when needs
    (str
     "AN EARLIER RUN'S DESIGN VERDICT LEFT THIS OUTSTANDING, AND NO FINDING HAS\n"
     "EVER RAISED IT.\n\n"
     "After round " round " of an earlier run the design was judged "
     (name verdict) ", and\n"
     "this was left open:\n\n"
     needs "\n\n"
     "That judgment read a different tree than the one below, so whether it is\n"
     "still true is a question, not a fact. Check it against the range:\n"
     "- Still true, and inside what you are reviewing: report it as a finding\n"
     "  like any other, at the lines you found it. That is the only way it\n"
     "  becomes work — nothing downstream of a verdict can act on one.\n"
     "- Already answered by the code, or outside this range: say nothing. Do\n"
     "  NOT report it back on the strength of this text; a finding nobody\n"
     "  verified costs the next round exactly what a real one does.\n\n")))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :string]]}
  prior-open-block
  "What an earlier RUN left owed against this exact layer, put to the reviewer
   of the code that holds it.

   The half of the between-run channel that carries obligations. Its sibling
   `prior-fixes-block` carries what a fixer already did to this layer WITHIN a
   run, and the workstream cache carries what a warden SETTLED — a store of
   answers, keyed on a patch hash that a repair moves. Nothing carried what was
   still owed, so a finding ruled `fix` and never repaired reached the next run
   through no channel at all: its file's reviewer started blank, found nothing,
   and the run published that the branch was clean.

   Stated as a previous run's ruling and put as a question, exactly as
   `standing-needs-block` is and for the same reason. That run read a different
   tree — a repair may have landed since, the lines may have moved — so whether
   the defect is there is what this reviewer is being asked. A reviewer that
   copies it back unverified launders a stale claim into a fresh finding, and
   the round after inherits a defect nobody looked at.

   Reporting it back when it IS still there is what turns it into work: a
   finding is the only currency a fixer can be handed, and this list is the one
   thing in the run that knows the defect was ever ruled on."
  [prior-open]
  (when (seq prior-open)
    (str
     "AN EARLIER RUN LEFT THESE OWED AGAINST THIS LAYER, AND NO REPAIR IS\n"
     "RECORDED FOR THEM.\n\n"
     (->> prior-open
          (map (fn [{:keys [title where disposition because handed]}]
                 (str "- " title
                      (when where (str "  (" where ")"))
                      (when disposition (str "\n  ruled " (name disposition)))
                      (when handed
                        (str "\n  a repair for it was landed and no reviewer has read it since"))
                      (when-not (str/blank? (str because))
                        (str "\n  the warden said: " because)))))
          (str/join "\n"))
     "\n\n"
     "That run read a different tree than the one below, so whether each is\n"
     "still true is a question, not a fact. Check it against the range:\n"
     "- Still true: report it as a finding like any other, at the lines you\n"
     "  found it. Nothing else in this run knows it was ever ruled on, so a\n"
     "  silence here is read as the defect being gone.\n"
     "- Repaired, or outside what you are reviewing: say nothing. Do NOT\n"
     "  report it back on the strength of this text.\n\n")))

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

   `:and-requires` names, per value of that field, a further field the value is
   not a ground without, and is enforced the same way. `duplicate` is the one
   authority whose meaning is another finding, so it has to say which: the close
   settles the finding but not its defect, and `nido.review.stages` holds a
   duplicate open for as long as the finding it names is. With nothing named
   there is nothing to hold it by, and the close settles unconditionally.

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
    :and-requires {"duplicate" :duplicate_of}
    :means (str "no fix, AND you name the authority — duplicate (of another id\n"
                "  in this round, which you put in `duplicate_of`; it stays open\n"
                "  for as long as that one does), out-of-scope (a layer's Out of\n"
                "  scope names it), design (the record puts it behind a boundary),\n"
                "  spun-out (it is already filed as a ref), false-positive (the\n"
                "  reviewer is wrong; say what they missed).")}
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
                "  on one side of a bad cut makes that cut permanent and lets\n"
                "  the round converge reporting success.")}
   {:disposition :park
    :means (str "no fix, and nothing above fits: this is a decision rather than\n"
                "  a repair, and it is for the human. Three grounds, and only\n"
                "  three.\n"
                "  (a) The finding contradicts a named invariant of the design.\n"
                "  (b) RECURRENCE: you have already had this defect fixed in an\n"
                "  earlier round — you set `same_as` on it — and it is back. A\n"
                "  third attempt at something two fixes did not settle is not a\n"
                "  repair the loop knows how to make, whatever the fix prompt is\n"
                "  told.\n"
                "  (c) NO MOVE: it is a composition finding — it carries a\n"
                "  `kind` — whose answer is where a boundary belongs rather than\n"
                "  a line to change, and no move under RECUT below is the move\n"
                "  it wants. The boundary question is what gets parked. The\n"
                "  ADVISORY kinds are the exception and are `declined`, for the\n"
                "  reason given there.\n"
                "  Grounds (b) and (c) do NOT need a design record: one is a\n"
                "  fact about this run's own history and the other about the\n"
                "  finding in front of you, and you are holding both.\n"
                "  Otherwise: a defect whose remedy is a move RECUT lists is\n"
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

(defn- kinds-costing [c] (filter #(= c (:costs %)) composition-kinds))

(defn- cut-routing-block
  "What may be done about a composition finding, derived from the taxonomy rather
   than written out beside it.

   Three destinations, and `:costs` is what picks them. A `:packaging` kind is
   ADVISORY: the collapse erases its defect and the only remedy is rearranging
   layers, so a fixer, a reshape and a park all spend a round on something that
   will not exist by the time the branch lands. It is reported and ruled
   `declined`, which settles it, keeps it, and puts it in front of a human.

   `:remedy` still selects what the reshape stage can perform, but it no longer
   selects on its own: a kind with a remedy AND `:packaging` is advisory, because
   being able to make a move is not a reason to make one. That leaves one kind
   the loop still recuts.

   Hardcoding kind names here is what an earlier version did, and it sent
   `claim-falsified` to fixers whose minimal edit on one side of a cut made the
   cut permanent while the round reported success."
  []
  (let [advisory (kinds-costing :packaging)
        recut    (filter #(and (:remedy %) (not= :packaging (:costs %))) composition-kinds)
        no-fix   (remove :remedy (remove #(= :packaging (:costs %)) (kinds-asking :cut)))]
    (str "ADVISORY — kinds whose defect the collapse erases and whose only remedy\n"
         "is rearranging layers. Report them; rule them `declined` with your\n"
         "reason. NEVER `fix`, `recut` or `park` one. The stack is collapsed into\n"
         "one commit before it lands, so no layer boundary is ever a merge\n"
         "boundary and no layer survives — re-cutting to improve one buys nothing\n"
         "that outlives this review, and by the time you can report it the\n"
         "attention it would have saved is already spent:\n"
         (->> advisory (map #(str "- " (:kind %) "\n")) (apply str))
         "\n"
         "RECUT — a defect that reaches the merged tree and that the loop can act\n"
         "on mechanically. Use `recut` for these:\n"
         (->> recut
              (map #(str "- " (:kind %) " → " (name (:remedy %)) "\n"))
              (apply str))
         "The loop performs the move shown, and only where the finding's own\n"
         "`remedy` field is that same move. One asking for anything else — a\n"
         "split, or a repair that is not a move of the layers at all — is\n"
         "refused and becomes a park. Rule it `recut` all the same: that refusal\n"
         "is where the finding gets its answer, and the alternative is the loop\n"
         "performing a move the finding's own author ruled out.\n"
         "\n"
         "NOT A FIXER'S WORK — kinds that ask where a boundary belongs and name\n"
         "no move the loop can make. These are decisions: `park` them, or\n"
         "`deviation` them against the claim they contradict. Handing one to a\n"
         "fixer asks for a minimal edit to a question about shape, and what comes\n"
         "back makes the cut harder to see, not gone:\n"
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
       (->> disposition-vocabulary
            (mapcat (comp seq :and-requires))
            (map (fn [[v field]]
                   (str "or closes as `" v "` without naming the finding in `"
                        (name field) "`,\n")))
            (apply str))
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

:costs is what the defect costs if nobody acts, and it is the field two rules
   read. `:merged-tree` — the defect is in what lands, so it outlives everything
   here. `:false-premise` — the collapse erases the defect itself, but a reviewer
   accepted something on a claim that was untrue, and the repair is a change to
   the code. `:packaging` — the collapse erases it AND the only remedy is
   rearranging layers, so acting on it buys nothing that survives the landing.

   The two rules stay separate because they ask different things. ROUTING asks
   what could be done: a `:packaging` kind is advisory and may not be handed to a
   fixer or a reshape. The stale-park halt asks whether the branch is really
   waiting on the answer, and only `:merged-tree` still stops a run. Read as one
   field-test they are wrong in both directions — `broken-intermediate` is erased
   by the collapse and is still ruled `fix` 8 times in 30, because completing a
   layer is a code change.

   A closed set, deliberately. This pass's whole difficulty is that its findings
   are easy to confuse with ordinary ones, and a kind a reviewer has to name is a
   kind it cannot drift into: there is no bucket here for a vague unease about
   the stack. `nido.review.codex/composition-schema` builds its `kind` enum from
   this same list — a taxonomy the prompt teaches but the schema will not accept
   is not a soft mismatch, it is a 400 on every round."
  [{:kind "broken-intermediate" :asks :wiring :costs :false-premise
    :what (str "the stack does not hold at some layer's own tip. A layer leaves\n"
               "  the tree referring to something only a LATER layer supplies, or\n"
               "  breaks a contract a later layer restores. Every layer is\n"
               "  individually defensible and the stack still cannot land one PR\n"
               "  at a time.")
    :how  (str "for each layer bottom→top, take what its diff REMOVED or renamed\n"
               "  and search the tree AT THAT LAYER'S OWN REV for anything still\n"
               "  referring to it. This is the wiring question at its most\n"
               "  concrete, and the layer tips are the only place to answer it.")}
   {:kind "claim-falsified" :asks :cut :costs :false-premise
    :what (str "a layer's stated claim is contradicted by a layer above it. The\n"
               "  common form: a layer claims `mechanical`, or no behaviour\n"
               "  change, and a layer above quietly compensates for behaviour\n"
               "  that did change.")
    :how  (str "read each layer's claims, then read the layers above it for code\n"
               "  that only makes sense if that claim is false. Name the claim.")}
   {:kind "duplicated-across-layers" :asks :cut :remedy :fold :costs :merged-tree
    :what (str "two layers independently introduce the same thing — a helper, a\n"
               "  guard, a migration step — because bounded review guaranteed\n"
               "  neither could see the other.")
    :how  (str "for each thing a layer ADDS, look through the other layers for a\n"
               "  near-twin. The names will differ; the shape will not.")}
   {:kind "order-dependence" :asks :wiring :remedy :reorder :costs :packaging
    :what (str "a layer depends on something a layer ABOVE it establishes, so the\n"
               "  stack is in the wrong order. Distinct from broken-intermediate:\n"
               "  there the repair is to complete a layer, here it is to move one.")
    :how  (str "when a layer reaches for something it did not bring, find which\n"
               "  layer supplies it and check whether that layer sits above.")}
   {:kind "orphaned-by-scope" :asks :cut :costs :merged-tree
    :what (str "something in this branch that EVERY layer's `out of scope` pushed\n"
               "  away, so no reviewer ever held it and nothing ever ruled on\n"
               "  it. You are the only pass that can see this hole, because you\n"
               "  are the only one that reads all the exclusions at once.")
    :how  (str "read the `out of scope` lines above as one set, and ask what in\n"
               "  the branch falls through all of them.")}
   {:kind "misplaced-cut" :asks :cut :remedy :fold :costs :packaging
    :what (str "the cut itself is wrong: one idea split so neither side is\n"
               "  coherent alone, or a layer boundary running through the middle\n"
               "  of a thing. **Report the cut, not a patch.** Saying where the\n"
               "  cut should have been is worth more than repairing either side,\n"
               "  and a fix applied to one side makes the wrong cut permanent.")
    :how  (str "you have usually already found this when a defect has no good\n"
               "  owner. When placing it on either layer feels arbitrary, that is\n"
               "  the cut telling you about itself — say so instead of choosing.")}
   {:kind "aggregate" :asks :wiring :costs :merged-tree
    :what (str "each layer's contribution is defensible alone and their sum is\n"
               "  not: a cost, a lock, a query, an allocation added once per\n"
               "  layer.")
    :how  (str "count the added instances of anything that has to stay rare\n"
               "  ACROSS layers rather than within one.")}])

(def remedy-vocabulary
  "What a composition finding answers when asked which shape change it needs.

   The reshape stage checks it against the move the finding's KIND routes to and
   refuses where the two differ, because a kind names a class of defect and not
   the move one instance of it wants. Two findings can both be
   `duplicated-across-layers` and one of them be resolvable only by splitting a
   layer — a move the loop does not have. With only the kind to read, that
   finding is folded, and a fold is exactly what its own body ruled out; named
   here, it is refused and put to a human instead.

   `split` is a value of its own because it is the remedy a cut defect most
   often has and the one the loop most conspicuously lacks: nothing here ADDS a
   boundary. `other` takes everything else, including a defect whose repair is a
   change to the code rather than to the layers — which most of
   `composition-kinds` is.

   `move` — squashing one file's changes down between two layers that both
   survive — is deliberately not offered, and `nido.review.stages/reshape-plan`
   is why. No kind routes to it: it is how that stage narrows a fold whose span
   it cannot take whole, which is a decision about carrying out a remedy rather
   than a remedy to ask for. Offered here it would split the honest answer on
   such a finding in two, and the half that named the narrowing would be refused
   as disagreeing with its kind."
  [{:remedy "fold"
    :means "collapse the layers this spans into one, losing every boundary between them"}
   {:remedy "reorder"
    :means "put the upper layer below the lower one; both survive"}
   {:remedy "split"
    :means "divide a layer in two — no move here adds a boundary"}
   {:remedy "other"
    :means (str "anything else, including a repair that changes the code rather "
                "than the layers")}])

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

(defn- file-list
  "A layer's files on one line, capped at twelve with a count of the rest.

   Every block that names a layer's files is a MAP: the reader has to recognise
   a path it is already holding, not inventory the layer. One sweeping layer
   with two hundred files would otherwise push every other layer's row, and the
   instructions under them, out of the reader's reach."
  [files]
  (let [shown 12]
    (str (str/join ", " (take shown files))
         (when (> (count files) shown)
           (str " … +" (- (count files) shown) " more")))))

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
                 (str "   touches:       " (file-list files) "\n")))))
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
     "branch sees only where the stack ENDS UP — one tree, with the cuts gone.\n"
     "Your range spans the whole branch so that you can see the cuts, not so\n"
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
     "where EVERY layer pushed the same thing away — that one is yours, below.\n\n"     "MODULE BOUNDARIES ARE NOT YOUR SUBJECT. Whether a module boundary sits in\n"
     "the right place — whether the lock protocol belongs with the schema or the\n"
     "API, whether two things that hide one decision are one module — is judged\n"
     "BEFORE any code exists, by the design round's `decomposable` check, which\n"
     "reads the area's surveyed modules. You have neither that survey nor that\n"
     "question. A finding of yours phrased as `X belongs in a Y layer` is almost\n"
     "always this mistake wearing a layer's clothes, and what happens to it is\n"
     "that a human parks it and nobody acts. If you believe a module boundary is\n"
     "wrong, that belongs to the ordinary review of the layer containing it —\n"
     "not here.\n\n"
     "WHAT EARNS A FINDING HERE. Your value is not that your range is wider. It\n"
     "is that you are the only reader who can reach the INTERMEDIATE revisions\n"
     "and read every layer at once, so report what nobody else could have seen:\n"
     "the same thing built twice because bounded review guaranteed neither layer\n"
     "saw the other, work every layer's `out of scope` pushed away so no\n"
     "reviewer ever held it, a cost that has to stay rare added once per layer,\n"
     "a layer that will not stand up at its own tip. Measured across 202 runs of\n"
     "this pass, two thirds of what it reported was something another reader\n"
     "already had or nobody could act on. Coming back empty is the common\n"
     "correct answer.\n\n"
     (when (seq already-reported)
       ;; The pass runs fresh every round and is the only reader that can see
       ;; across layers. Told nothing about its own earlier output, it has no way
       ;; to notice it is returning the same cut a third time rather than
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
     "- `remedy` is the shape change THIS defect needs — answer for the defect\n"
     "  in front of you, not for its kind:\n"
     (->> remedy-vocabulary
          (map #(str "    " (:remedy %) " — " (:means %) "\n"))
          (apply str))
     "  Where what you want is not a move the loop has, say so. A split or an\n"
     "  other is refused and put to a human, and that is the right outcome for\n"
     "  it. A fold named because it was the closest word available gets\n"
     "  performed, on layers your own body may have argued against merging.\n"
     "- `layers` names every layer the defect spans, by the label exactly as\n"
     "  written above, in stack order. Two or more, always. Filling it with one\n"
     "  is the test above telling you this finding belongs to that layer.\n"
     "- `code_location` points at the most representative site. For a\n"
     "  misplaced-cut finding that is where the cut runs through — not a line\n"
     "  to patch.\n"
     "- `body` must say what EACH named layer contributes to the defect. A body\n"
     "  that describes only the symptom cannot be acted on: the reader cannot\n"
     "  tell which layer to change, and being able to tell is the entire value\n"
     "  of this pass.\n\n")))

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  manifest-block
  "The files a range-bounded review works from, and the mandate to open every
   one of them.

   The mandate belongs HERE, beside the list it is a mandate over, and not in
   `review_prompt.md` where it used to sit. That prompt is shared by every
   target, so \"you MUST actually pull each changed file's diff\" reached the
   composition pass too — as a MUST, over a manifest of every file in the
   branch, against the thousand words of `composition-block` telling that pass
   its subject is the cut and not the code. A MUST beats a narrowing clause:
   measured across 212 composition-pass logs, 40% of that pass's `jj diff` calls
   swept the whole branch, which is the flat review it exists not to be."
  [manifest]
  (str "Changed files:\n" (str/trim (str manifest)) "\n\n"
       "You MUST actually pull each changed file's diff before concluding."))

(def composition-manifest-note
  "What the composition pass gets where a range-bounded review gets its
   manifest.

   Not a shorter list — no list. Every layer's files are already on its own row
   above, where each is attached to the layer that touches it; the union of them
   is the one view that cannot show a cut, because `<base>`..`<head>` spans
   every layer at once. Handing that union over and then asking for the cut is
   asking two different questions with one set of coordinates, and the flat one
   is the easier to answer."
  (str "There is no flat list of this branch's files, and no whole-branch diff
"
       "to sweep. Each layer's files are on its row above, attached to the layer
"
       "that touches them, and `<base>`..`<head>` spans every layer at once —
"
       "the one range in which no cut is visible. Work from the per-layer
"
       "`--from`/`--to` pairs and the layer trees.\n\n"
       "`<base>` and `<head>` are given below for the two things they are still
"
       "good for: the tree before any of this landed, and the tree the whole
"
       "stack leaves behind."))

(defn- stacked-change-block
  "Where this fixer is standing in the stack, and which files are not its own.

   Gated on the branch being LAYERED — `stack` is the table of contents, which
   is empty exactly when there are no layers — and not on the layer having
   declared anything. Those came apart on a four-layer stack whose layers
   carried no brief: the fix prompt opened straight at the findings list, so a
   fixer was addressed as though the branch were flat, and it edited a file the
   layer above it owns. A layer that claims nothing is still a layer.

   ABOVE and not below, because above is the direction that costs something. A
   fix lands by rewriting its own layer, so jj rebases every layer above onto
   the result, and a file two of them touch conflicts there — `run-fix-stage`
   answers that by restoring the operation, which takes the commit, the edits
   and everything else the fixer did this round with it. A layer BELOW is
   already in the tree this fixer stands on; editing what it touched costs
   nothing.

   The files are what make it actionable. A label alone cannot answer the
   question the fixer has in front of an edit — is this file somebody else's —
   which is the same judgement `toc-block` exists to let the warden make.

   A label the toc does not name gets the heading and nothing positional: the
   reshape stage can rewrite the layers between the review and the repair, and a
   stale row is worse than no row when the whole point of the row is which files
   belong to whom."
  [stack layer]
  (when (seq stack)
    (let [pos   (first (keep-indexed #(when (= (:label layer) (:label %2)) %1) stack))
          above (when pos (drop (inc pos) stack))]
      (str "YOU ARE FIXING ONE LAYER OF A STACKED CHANGE"
           (when (:label layer) (str " — " (:label layer)))
           ".\n"
           (when pos
             (str "It is layer " (inc pos) " of " (count stack) ", bottom to top.\n"))
           (when (seq (:files layer))
             (str "It touches: " (file-list (:files layer)) "\n"))
           (when-let [c (:claim layer)]
             (str "It claims: " c "\n"))
           (when-let [o (:out-of-scope layer)]
             (str "It declared OUT OF SCOPE: " o "\n"
                  "That is a prohibition. Do not fix those things here; another\n"
                  "layer owns them, and someone has already decided which.\n"))
           "\n"
           (cond
             (nil? pos) nil

             (empty? above)
             (str "Nothing sits above yours — you are the top layer, so no other\n"
                  "layer is rebased onto what you land.\n\n")

             :else
             (str "THE LAYERS ABOVE YOURS, and what each of them touches:\n"
                  (->> above
                       (map-indexed
                        (fn [i {:keys [label files]}]
                          (str "  " (+ pos i 2) ". " label
                               (when (seq files) (str " — " (file-list files)))
                               "\n")))
                       (apply str))
                  "\n"
                  "Your fix lands on your own layer and every layer above is then\n"
                  "rebased onto it, so a file listed there is NOT yours to edit:\n"
                  "the rebase conflicts, and the whole attempt — every edit you\n"
                  "made this round, not just that one — is rolled back and lost.\n"
                  "If a finding cannot be resolved without touching one of those\n"
                  "files, do everything else and NAME the file and what it needs\n"
                  "in your final message. That is what reaches the layer that\n"
                  "owns it; editing it here reaches nobody.\n\n"))))))

(defn- settled-block
  "What has already been decided about the code this fixer is being sent into.

   The findings list is what the warden dispositioned `:fix`, so a decision to
   LIVE with a defect reaches the one reader most able to undo it as silence.
   A fixer handed a single finding rewrote two of the three sites named by a
   deviation the same round had KEPT, recognised the edit as a different defect
   class from the one it was assigned, and landed it anyway; the run went on to
   report `2 kept · 0 still open` over a branch where two thirds of one of those
   two no longer stood.

   The reach is why the findings list cannot carry this. A sweep searches the
   defect CLASS past the lines it was handed, and the self-consistency rule
   sends a fixer back through every artifact it edits — both of them arrive at
   lines nobody handed it, and a settled finding is such a line with a decision
   already on it.

   Every SETTLING disposition, which is the set `nido.review.stages/settled?`
   reads off `disposition-vocabulary`. One list, so what a fixer is told is
   decided and what the loop counts as decided cannot come apart.

   The file and line range are here and are not in `answered-block`, and that is
   the whole difference between the two readers. The warden has the finding in
   front of it; the fixer has the repository, and a decision it cannot locate is
   one it can only honour by accident."
  [settled]
  (when (seq settled)
    (str "ALREADY DECIDED — leave these exactly as they are.\n"
         "The reviewer of the whole stack ruled on each one and settled it: the\n"
         "defect is real and this branch is shipping it, or it is not this\n"
         "change's to fix.\n"
         "They are here because your reach is wider than the findings below — a\n"
         "sweep searches past the lines you were handed, and leaving an artifact\n"
         "self-consistent sends you back through everything you edit — so a line\n"
         "you are about to change may already carry a decision.\n"
         (when (some #(= :deviation (:disposition %)) settled)
           (str "A `deviation` is the sharpest of these: a layer's stated CLAIM\n"
                "and the code contradict each other and BOTH are being kept.\n"
                "Making them agree is a change nobody asked for.\n"))
         "Editing one does not retract the ruling. It stands, so the branch and\n"
         "the account the run publishes of it disagree, and nothing downstream\n"
         "notices. If a decision looks wrong, say so in your final message and\n"
         "name the id: that reaches the round that can reverse it, and the edit\n"
         "reaches nobody.\n"
         (->> settled
              (map (fn [{:keys [id title disposition authority file
                                line-start line-end of because]}]
                     (str "\u00b7 " id " " title
                          " \u2192 " (name (or disposition :closed))
                          (when authority (str " (" authority ")")) "\n"
                          (when file
                            (str "  " file
                                 (when line-start
                                   (str ":" line-start
                                        (when line-end (str "-" line-end))))
                                 "\n"))
                          (when of (str "  the claim it deviates from: " of "\n"))
                          (when because (str "  the decision: " because "\n")))))
              (apply str))
         "\n")))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  fix-prompt
  "Instruction to fix the given findings. Do NOT commit — the engine commits.

   Carries four things the warden had already written and the fixer never saw.

   `:because` is the warden's own sentence about this finding, and it is
   addressed to this reader: it says why the finding is real, or which layer it
   was moved to and why. It was produced every round, stored on the ruling, and
   rendered to nobody.

   `:stack` is the table of contents — every layer of the branch, bottom→top —
   and `:layer` is this fixer's own row within it. Together they are the map
   `stacked-change-block` renders: what this layer claims, what it declared out
   of scope, and which files belong to the layers above. Without the brief
   \"make the MINIMAL change\" is the only guidance there is, and for a defect
   that spans a cut the minimal change is a patch on whichever side the finding
   happened to be reported from; without the files above, an edit that resolves
   the finding perfectly can still be thrown away by the rebase.

   `:sweep` widens one finding into its family. The warden recognises a recurring
   class unprompted and had no channel to say so, so rounds surfaced its members
   one per round: a call site fixed, the next one found, ten rounds for ten
   instances of one defect.

   `:settled` is what the warden decided about this same layer and did NOT hand
   over — see `settled-block`. It is the one of the four that constrains rather
   than directs: the other three say what to change, and this says which lines
   somebody has already decided not to.

   Two clauses then bound the reach the prompt would otherwise narrow itself,
   against one reading of it: that the fixer's subject is the patch.

   A sweep searches the defect CLASS and not this change's diff. \"Audit this
   layer\" reads as the diff, so the sibling that survives a sweep is the
   pre-existing line beside the one just edited. It searches by SIGNATURE — a
   token the class shares, grepped over the files the change touched — and the
   repair it was handed is in the tree before the search starts.

   Those two are one remedy and neither holds alone. A whole-file read is a
   prefix every swept finding's search shares, so a fixer holding several
   front-loads all of the reading whatever order the sentences are in: one
   handed three findings the warden had settled spent 42 tool calls, every one
   of them a read, and landed no edit before a person stopped it. Ordering the
   repair first is what makes the phase incremental, and a search cheap enough
   to come second is what lets the ordering hold. Restore the whole read and the
   ordering goes with it.

   The ordering is UNCONDITIONAL, and it used to render only where a finding was
   swept — on the reading that with no search ordered there is no second. There
   is: the orienting read. Measured over eighty-one fixer launches, 37% of the
   phase elapses before the first source edit against 23% after the last, and
   the longest of those windows are whole-file `cat -n`s of files the finding
   already names a line in. A sweep is the one case where the reading is ASKED
   for, which made it the visible one; it was never the only one, and the reason
   the clause gives — a kill lands whatever the tree holds — never mentioned
   sweeps at all.

   What stays conditional is the SWEEP half of the sentence. An ordering clause
   pointing at a section of the prompt that was not rendered reads as a lookup
   that failed, which is a reason to doubt the rest of the prompt rather than a
   reason to obey it. Unswept, the clause orders the repair before the ORIENTING
   read and names nothing that is not there.

   Saying what was searched for is what keeps the narrower search falsifiable —
   a signature that hit nothing is an answer, where a whole-file read leaves no
   artifact separating a sweep that found nothing from one that never happened.

   Where a sibling is somewhere this fixer may not touch, naming it in the final
   message is what puts it in front of the next round: that text lands on the
   fix row as `:account`, and it reaches two readers — `prior-fixes-block`
   renders it to the next reviewer of this layer, and `fixer-accounts-block` to
   the warden, which is the only one holding the file lists a sibling in ANOTHER
   layer can be placed against.

   MINIMAL says how much to change, not what may be left broken. For a
   declarative artifact — a spec, a schema, a policy table — the smallest edit
   that resolves a finding is often exactly the edit that makes the artifact
   contradict itself: a type declared required that no rule in the file produces
   is a defect the next round reports as new.

   `:swept-before` is the run's own history rather than the warden's judgement —
   `nido.review.stages/with-sweep-memory` reads it off the rounds that landed a
   fix — and it changes what the sweep asks for. Enumeration is what a class
   coming back has already disproved: of six sweeps ordered in one run three
   classes returned the next round, and both sweeps that held did so because the
   fixer changed what the instances were derived from, one of them by making
   every caller of a single function read the same shape. The way out the block
   insists on is an ANSWER too, not a failure to sweep: a class whose members are
   one requirement written out three times has no common source to change, and
   saying so is what lets the loop stop instead of spending a third round on it."
  [{:keys [findings layer stack settled]}]
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
   "ORDER — repair first, everything else second.\n"
   (if (some :sweep findings)
     (str "Make every repair you were handed before any wider reading, and\n"
          "before any of the searching a SWEEP below asks for.\n")
     "Make every repair you were handed before any wider reading.\n")
   "You can be stopped at any moment, and a fixer killed on its budget has\n"
   "whatever the tree holds at that instant committed as its repair — so\n"
   "orienting first means an early ending lands nothing at all for findings\n"
   "a reviewer raised and the warden already settled.\n"
   "Each finding below carries the file and lines it sits at and a\n"
   "reviewer's account of what is wrong there. Start from those lines. It is\n"
   "the ORIENTING read that is deferred here, not the re-read above: that one\n"
   "belongs to finishing a repair, and this rule is about starting one.\n\n"
   (stacked-change-block stack layer)
   (settled-block settled)
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
                      (if-let [rounds (seq (:swept-before f))]
                        (str "  SWEEP AGAIN: this class was already swept in round"
                             (when (next rounds) "s") " "
                             (str/join ", " rounds) ",\n"
                             "  and came back. Enumerating its instances is therefore\n"
                             "  the one remedy already known not to hold here; do not\n"
                             "  spend this round on it again. Change what the instances\n"
                             "  are DERIVED from, so the class cannot have another\n"
                             "  member: the function they all call, the value the\n"
                             "  condition reads, the schema they are all checked\n"
                             "  against — one edit at what they have in common, not\n"
                             "  another N edits at the sites. If there is no such\n"
                             "  common source — they are one requirement written out\n"
                             "  several times, or what they derive from is somewhere\n"
                             "  you may not touch — say THAT in your final message,\n"
                             "  and name what you looked at. That is an answer and it\n"
                             "  ends the class; a third enumeration is not.\n")
                        (str "  SWEEP: this is one instance of a recurring defect.\n"
                             "  Fix it and land that repair; only then go and\n"
                             "  find its siblings and fix those too.\n"
                             "  The search is over the defect CLASS, not over this\n"
                             "  change's diff: the sibling that survives a sweep is\n"
                             "  usually the pre-existing line beside the one you just\n"
                             "  edited. So SEARCH for it rather than reading for it.\n"
                             "  Name the signature the class shares — the call, the\n"
                             "  identifier, the shape, the guard that is absent —\n"
                             "  grep the files this change touched for it, and read\n"
                             "  only around what it hits. Reading those files whole\n"
                             "  finds the same class and spends the round doing it.\n"
                             "  Say in your final message what you searched for: a\n"
                             "  signature that hit nothing is an answer, and a class\n"
                             "  that shares a requirement and no token is one too —\n"
                             "  there, name what you read instead.\n"
                             "  Finding them one per round is what this is here to\n"
                             "  stop — the minimal change rule does not apply to the\n"
                             "  search, only to each edit. A sibling you may not\n"
                             "  touch here — another layer's, or outside this\n"
                             "  change — is to be NAMED in your final message, never\n"
                             "  silently left.\n")))
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
                        (str "   touches: " (file-list files) "\n")))))
              (str/join ""))
         "\n")))

(defn- findings-list
  [findings]
  (->> findings
       (map (fn [f]
              (str "- id " (:id f) "  [P" (:priority f) "/"
                   (name (or (:reach f) :unclear)) "]"
                   (when (:contradicts f) " · CONTRADICTS an invariant")
                   (when-let [l (:from-layer f)] (str " reported-by " l))
                   (when-let [k (:kind f)] (str " · " (name k)))
                   (when (seq (:layers f))
                     (str " · across " (str/join " + " (:layers f))))
                   "\n  " (:title f)
                   "\n  " (:file f) ":" (:line-start f) "-" (:line-end f)
                   (when-let [c (:contradicts f)]
                     (str "\n  it departs from, in the reviewer's own citation: " c))
                   (when-let [c (:miscited f)]
                     (str "\n  the reviewer said it departs from an invariant and quoted"
                          " no clause on your list — this is its wording, and it"
                          " cites nothing: " c))
                   "\n  " (:body f))))
       (str/join "\n\n")))

(def invariant-citation-cue
  "The word that turns a warden `because` into an appeal to the design.

   Held here rather than beside the parser for the reason `disposition-vocabulary`
   is: `design-block` publishes the quoting rule into the warden's prompt from
   this var and `nido.review.stages` enforces it from the same one, so a parser
   triggering on a word the warden was never given cannot happen."
  "invariant")

(defn- invariant-lines
  "The invariants, each with the moment it holds.

   A record written before phasing carries plain strings and means :always by
   them; a phased one carries the Invariant map, and an :on-completion invariant
   is deliberately false for the whole middle of a plan — a warden shown one
   without the qualifier reads a designed intermediate state as a broken design.

   Rendered rather than bulleted raw because this text is now checked against:
   `stages/uncited-invariant` asks whether a quoted span appears in one of these
   clauses, so the clause is what the warden has to be able to copy. An EDN map
   printed at it is not one."
  [invariants]
  (bullets (map (fn [i]
                  (let [{t :invariant h :holds} (report/invariant i)]
                    (str t (when (= :on-completion h)
                             "  [holds ON COMPLETION — not yet true mid-plan]"))))
                invariants)))

(defn- seam-lines
  "The gaps the design noticed and left, each with how a reader is supposed to
   see it.

   `:visible-how` is rendered rather than dropped as bookkeeping, because it is
   the half a reviewer can actually check. A seam claims incompleteness is
   VISIBLE — that is what makes it a decision rather than a defect — and a seam
   whose stated visibility is not in the code is a finding, not a licence."
  [seams]
  (bullets (map (fn [{:keys [what visible-how]}]
                  (str what
                       (when-not (str/blank? (str visible-how))
                         (str "  [a reader sees it: " visible-how "]"))))
                seams)))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :string]]}
  design-yardstick-block
  "The design record rendered for a REVIEWER — what the implementation is to be
   validated against.

   `design-block` beside it renders the same record for the warden, and the two
   differ because the readers do. The warden RULES, so it needs everything that
   could make a finding answered rather than new. A reviewer REPORTS, and every
   field that exists to close a finding is a field that can suppress one.

   What is here and why:

   `:shape` and `:invariants` — the yardstick itself. Without them a reviewer
   judges the code against the surrounding code, which is how the intended shape
   gets inferred from the very lines that depart from it.

   `:seams` — a gap the design noticed and left. Shown for the same reason
   `layer-brief-block` shows settled deviations: unshown, a reviewer reports the
   deliberate gap every round and the warden spends the round closing it again.
   It carries the same bounding sentence, because the bound is what makes it safe
   — a defect the seam does not COVER is still the reviewer's, and the warden has
   had to make that distinction by hand round after round (\"harm outside what the
   dropped-audio seam declares\").

   What is deliberately NOT here:

   `:rejected` — a remedy considered and refused. It settles a finding for the
   warden; for a reviewer it is an invitation to suppress a real DEFECT because
   the obvious fix was ruled out. A reviewer reports defects, not remedies, and
   is better off not knowing which remedies are off the table.

   `:layers` — the claimed decomposition. It is a claim about how the branch was
   CUT, which the collapse erases; the warden already holds it, and a layer
   reviewer bounded to one layer cannot see the stack it would be checked
   against.

   nil when there is no record. `tasks.nido-review/no-yardstick` now refuses such
   a run outright, so this is the second reader of the same fact rather than the
   one that has to cope with it."
  [{:keys [shape invariants seams]}]
  (when (or (seq invariants) (not (str/blank? (str shape))))
    (str
     "THE DESIGN THIS CHANGE COMMITTED TO — your job is to validate the\n"
     "implementation against it.\n\n"
     (when-not (str/blank? (str shape)) (str "Shape: " shape "\n\n"))
     (when (seq invariants)
       (str "MUST REMAIN TRUE — the design states each of these:\n"
            (invariant-lines invariants) "\n\n"
            "Where the code departs from one, put THAT invariant in the finding's\n"
            "`contradicts` field, copied VERBATIM and whole. Verbatim because the\n"
            "field is checked against this list, and because a restated invariant\n"
            "is a different rule — the reader downstream acts on the words you\n"
            "wrote, not the ones you were holding. A paraphrase is refused and the\n"
            "finding arrives as though you had named nothing.\n"
            "It is not a severity and it does not change priority. It says the\n"
            "DESIGN is what is in question here, which is a decision for a person\n"
            "rather than a repair for a fixer, and nothing else in this review can\n"
            "say it: you are the reader holding the code against the claim.\n"
            "Most findings contradict nothing. Leave it null then.\n\n"))
     (when (seq seams)
       (str "KNOWN GAPS — noticed when this was designed and deliberately left:\n"
            (seam-lines seams) "\n\n"
            "Do not report these; they are decisions already taken. A defect they\n"
            "do not COVER is still yours, including one at the same seam — say\n"
            "what is outside what the gap declares. And a seam whose stated\n"
            "visibility is not actually in the code is itself a finding.\n\n")))))

(defn- design-block
  "The design record, rendered for the warden. This is the yardstick: findings are
   judged against these invariants and nothing else. :rejected is included because
   a finding that re-proposes a rejected alternative is *answered* rather than new.

   The claimed decomposition is here for a different reason than the invariants.
   The warden judges a STACK, and without the design's own layer claims it cannot
   see that the stack in front of it has three layers where the design named two
   — a mismatch that is a finding about the cut, and one nothing else in the loop
   can reach. `record.clj` already renders it this way for the record judge; the
   warden was the reader that needed it and did not get it.

   :seams is the record's OTHER kind of answer, and it belongs here for the same
   reason :rejected does. A rejected alternative says a remedy was considered and
   refused; a seam says a gap was noticed and left, with what closes it. Either
   makes a finding that names it answered rather than new — so a warden that
   cannot see the seams rules `fix` on a gap the record argued for, and the fixer
   builds the machinery the record decided against, which the next round then
   reviews as new code."
  [{:keys [shape invariants rejected standing layers seams]}]
  (str "THE DESIGN THIS CHANGE COMMITTED TO — judge the findings against this:\n"
       "Shape: " shape "\n"
       "Invariants:\n" (invariant-lines invariants) "\n"
       "QUOTE ONE, DO NOT RESTATE IT. A `because` using the word \""
       invariant-citation-cue "\"\n"
       "must carry the clause it leans on verbatim, in double quotes or\n"
       "backticks, copied from the list above. The loop checks that span against\n"
       "the list and, when it matches none, prefixes your sentence with a refusal\n"
       "before the fixer reads it — so a paraphrase licenses nothing. Restating is\n"
       "how an invariant gets WIDER: \"inside the method's own form\" restated as\n"
       "\"in the file that writes the method\" is a different rule, and the fixer\n"
       "obeys the one you wrote, not the one you were holding.\n\n"
       (when (seq layers)
         (str "CLAIMED DECOMPOSITION — one claim per layer, bottom to top. The stack\n"
              "you are judging should correspond to these, and a mismatch splits\n"
              "in two. A layer the design NEVER NAMED is a finding: work is\n"
              "carrying a decision nobody stated, and that survives the collapse.\n"
              "Two claims folded into one, or an order that does not match, is\n"
              "ADVISORY — the layers are collapsed into one commit before this\n"
              "lands, so rearranging them now buys nothing that outlives the\n"
              "review. Say it and rule it `declined`; do not hand it to a fixer\n"
              "and do not park it:\n"
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
       (when (seq seams)
         (str "DELIBERATE INCOMPLETENESS — what this change leaves open ON PURPOSE,\n"
              "each with what closes it: an argument for leaving it permanently, or\n"
              "a phase or a ref where the closure is scheduled elsewhere.\n"
              "A finding that names one of these is ANSWERED, not new — the gap IS\n"
              "the decision. Rule it `closed` on the authority `design`. Not `fix`:\n"
              "the fixer would re-close a gap someone chose to leave open, and the\n"
              "machinery it writes to do that is what the next round reviews. Not\n"
              "`park`: a park puts to a human a question this record answers.\n"
              "The exception is a finding showing harm OUTSIDE what the seam\n"
              "declares, or that the seam is not visible the way the record claims\n"
              "— that is new, and the seam does not answer it:\n"
              (bullets (map #(str (:what %) " — visible as: " (:visible-how %)
                                  (when-let [c (report/seam-closure %)]
                                    (str "; " c)))
                            seams))
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
              (map (fn [{:keys [layer round findings account]}]
                     (str "- " (or layer "the branch") ", refused in round " round "\n"
                          (->> findings
                               (map (fn [{:keys [id title]}]
                                      (str "    · " id " " title "\n")))
                               (apply str))
                          (when-not (str/blank? (str account))
                            (str "  its argument: " account "\n")))))
              (apply str))
         "\n")))

(defn- refused-repairs-block
  "The repairs a fixer WROTE for these findings that the stack would not take —
   rebasing the layers above onto the edit conflicted, and the loop put it back.

   From the warden's seat a refused repair looks exactly like a defect no fixer
   could move: the same handle, the same `fix` ruling, no commit. Read that way
   it is ground (b) for a park, about a finding whose repair exists — in the
   operation log — and was stopped by the layer order rather than by the code.
   One warden, not shown this, wrote that no fixer account for a layer existed
   while the refused row held a 2.6 KB one naming the repair, the test that
   discriminates it and the REPL check.

   The facts only, and no remedy. What the loop does with a repair the cut
   refuses is a decision still open (FU-141), and a block telling the warden
   what to rule would be taking it. Headed PUT BACK rather than refused: the
   block beside it says a fixer refused, and one verb for two actors invites
   the warden to conflate them."
  [refused]
  (when (seq refused)
    (str "A FIXER REPAIRED THESE AND THE REPAIR WAS PUT BACK\n"
         "The edit was written, and rebasing the layers above it onto the edit\n"
         "conflicted with the changes named, so the loop put it back. The code\n"
         "is exactly what the reviewers read, and every finding under it is\n"
         "still true — not because a repair failed, but because it never reached\n"
         "the code. That is not ground (b). What is in question is the layer\n"
         "order, not the repair:\n"
         (->> refused
              (map (fn [{:keys [layer round conflicted findings account]}]
                     (str "- " (or layer "the branch") ", round " round
                          (when (seq conflicted)
                            (str ", conflicted " (str/join ", " conflicted)))
                          "\n"
                          (->> findings
                               (map (fn [{:keys [id title]}]
                                      (str "    · " id " " title "\n")))
                               (apply str))
                          (when-not (str/blank? (str account))
                            (str "  the fixer said of the edit that was put back: "
                                 (account-excerpt account (count findings))
                                 "\n")))))
              (apply str))
         "\n")))

(defn- unstarted-block
  "The layers whose last fixer launch never started — see
   `nido.review.stages/unstarted-fixers` — and what that does and does not say
   about the findings they were handed.

   The warden is the reader weighing recurrence, and a finding handed to a
   process that never took a turn recurs looking exactly like one a fixer failed
   to move: same handle, same `:fix` ruling, no commit. Read that way it is
   ground (b) for a park about a defect nobody tried to repair. One run's
   wardens worked it out round after round from the absence of commits, in
   prose nothing read."
  [unstarted]
  (when (seq unstarted)
    (str "A FIXER WAS LAUNCHED FOR THESE AND NEVER STARTED\n"
         "claude refused the launch before the fixer took a turn, so no one has\n"
         "read these findings. They are untried, not resisted: that they are back\n"
         "is not a fix that failed, and it is not ground (b). Rule on them as you\n"
         "would a finding no fixer has seen. `fix` launches that layer again; if\n"
         "its fixer fails to start twice in a row the run stops on the machinery.\n"
         (->> unstarted
              (map (fn [{:keys [layer round exit-code findings]}]
                     (str "- " (or layer "the branch") ", round " round
                          (when (some? exit-code) (str " (exit " exit-code ")"))
                          ": " (str/join ", " (map :id findings)) "\n")))
              (apply str))
         "\n")))

(defn- fixer-accounts-block
  "Every repair this run landed and what the fixer said about it, addressed to
   the one reader that can place a sibling in a layer other than the one that
   landed the fix.

   A fixer under a sweep is ordered to fix its instance, audit the class, and
   NAME any sibling it may not touch — another layer's, or outside this change —
   in its final message. That message becomes the fix row's `:account`, and
   `prior-fixes-block` renders it to the next round's reviewer OF THE SAME
   LAYER, keyed by the label the fix landed under. So the one sentence in the
   account that is by construction about somewhere else is delivered to the only
   reader that cannot act on it. One run ended `clean` with two out-of-layer
   siblings named in a fix account, in a file another layer owned, read by
   nobody.

   The warden is the reader with the stack's file lists in front of it, so
   attributing a named path to a layer is its job here exactly as it is for a
   finding — and a sibling it can place, it PROMOTES: the `promote` entry becomes
   a finding of this round, ruled `fix` on that attribution, handed to the named
   layer's fixer, and holding that layer open whether or not the cache had its
   patch converged.

   That does not make the warden a reviewer, and the bound is what keeps the rest
   of the loop's accounting true. A promotion's REPORTER is the fixer: it read
   the code, made the repair the sibling survived, and diagnosed the sibling in
   its own account. What the warden adds is the one thing the fixer could not —
   which layer the path belongs to. A defect no account named is not promotable,
   and neither is one no layer can be found for; those go in `standing`, the
   slot that says what is open and is not being handed to anyone.

   The accounts were already in the warden's prompt before this block, whole and
   unlabelled, inside the `pr-str` of the round history: present in the bytes
   and absent from anything that told the warden they existed or what a fixer is
   ordered to put in them."
  [accounts]
  (when (seq accounts)
    (str "WHAT THE FIXERS SAID ABOUT THE REPAIRS THEY LANDED\n"
         "A fixer told to SWEEP is ordered to fix its instance, audit the class,\n"
         "and NAME any sibling it may not touch — another layer's, or outside\n"
         "this change — in its account. The reviewer that reads an account next\n"
         "round is the one reviewing the layer the fix landed on, so a sibling\n"
         "somewhere else reaches nobody who can go and look. You hold the file\n"
         "lists; you are that reader:\n"
         (->> accounts
              (map (fn [{:keys [layer round commit findings account]}]
                     (str "- " (or layer "the branch") ", round " round
                          (when commit (str ", landed " commit))
                          (when (seq findings) " — handed:") "\n"
                          (apply str (map handed-line findings))
                          (when-not (str/blank? (str account))
                            (str "  the fixer says: "
                                 (account-excerpt account (count findings))
                                 "\n")))))
              (str/join "\n"))
         "\n"
         "These are CLAIMS about code a fixer edited, not a record of the tree.\n"
         "Two uses. When a finding below sits where an account says a repair did\n"
         "not reach, that is the same open work and it belongs to the layer whose\n"
         "files hold those lines — say so in `because`.\n"
         "And a sibling named here and never repaired is open work with no\n"
         "finding behind it: no reviewer raised it, and none will until one\n"
         "happens to read that file again. PROMOTE it. Each `promote` entry\n"
         "becomes a finding of THIS round, ruled `fix` on the layer you name and\n"
         "handed to that layer's fixer — and it holds that layer open even if no\n"
         "reviewer read it this round because its patch was already converged,\n"
         "which is where a surviving sibling usually is.\n"
         "Promote only what an account above NAMED and you can place. The fixer\n"
         "read the code and diagnosed it; you are supplying the layer. A defect\n"
         "you inferred yourself is not a promotion — everything else here was\n"
         "read off code by something that read the code.\n"
         "Do not promote what a finding below already reports: rule that one.\n"
         "Promoting is deciding there IS work, so it overrides `stop` in the same\n"
         "answer — say `continue`.\n"
         "What you cannot place goes in `standing`. A promotion is work; that\n"
         "list is what is open and being handed to nobody.\n\n")))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  warden-prompt
  "Build the warden prompt. The warden is the only thing in the loop with a
   view across layers, so attribution — which layer a finding BELONGS to, as
   against which one reported it — is its job and nothing else's.

   Report-only (no tools): everything it reasons from is inlined here. That is
   deliberate and load-bearing. It is the component that decides to interrupt a
   human, so its inputs have to be reconstructable from the report afterwards.

   `standing` is the other thing only this reader can answer, and it is a report
   rather than a dispatch: what it knows to be open that this round is handing
   to nobody. It is asked for on every answer, not only on a `stop`, because the
   round that turns out to be the last one is not knowable while it is running."
  [{:keys [findings history design stance toc answered seen parked fix-outcomes]}]
  ;; A branch with no layers is reviewed flat, and there is then no layer label
  ;; for a finding to be attributed to. Asked for one anyway, the warden supplied
  ;; the only stack-shaped thing it had — a file path — on every ruling of the
  ;; run, and the loop absorbed the nonsense silently. Asking only where an
  ;; answer exists is cheaper than validating one that never should have been
  ;; requested.
  (let [layered? (boolean (seq toc))
        ;; `nido.review.stages/fix-outcomes`, whole. Each outcome gets a block of
        ;; its own because each asks something different of the warden — place
        ;; a sibling, answer an argument, see that a repair exists, see that no
        ;; one looked. An outcome given no block here reaches the warden only as
        ;; a missing commit, which is the misreading every one of them is for.
        {fixer-accounts :landed fixer-declines :declined
         refused :refused unstarted :unstarted} (group-by :outcome fix-outcomes)]
   (str
   "You are the WARDEN of an automated code-review loop over "
   (if layered? "a STACK of layers.\n" "a single unlayered branch.\n")
   (if layered?
     "You are the only reader with a view across all of them.\n\n"
     "There are no layers: the branch was reviewed flat.\n\n")
   "Return EXACTLY one fenced ```json block, nothing after it, matching:\n"
   "{\"decision\": \"continue|stop|escalate\",\n"
   " \"reason\": \"...\",\n"
   " \"standing\": [{\"what\": \"<one open item, in a sentence>\",\n"
   "               \"why_no_finding\": \"<why this round is handing it to\n"
   "                                     nobody>\"}],\n"
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
   "               \"duplicate_of\": \"<id of the finding in this round a\n"
   "                                  duplicate close repeats>\",\n"
   ;; The criterion rides on the field rather than in the SWEEP section below,
   ;; because `sweep` is the one field a warden can answer without deciding: an
   ;; omitted boolean is `false`, so the default is a ruling nobody made. Two
   ;; runs paid for that — one warden wrote a `because` granting a sweep and
   ;; emitted the field false, another withheld a sweep on the ground that no
   ;; sibling was named yet and spent a whole round on the sibling.
   "               \"sweep\": <true when the layer's claim could fail this same\n"
   "                         way at another site — a question about the\n"
   "                         defect's CLASS, not about whether a sibling is\n"
   "                         already named. Unsure is true: a needless sweep\n"
   "                         costs one fixer some reading, a missed one costs\n"
   "                         a round>,\n"
   "               \"because\": \"<one sentence>\"}]"
   ;; Offered only when there is an account to promote OUT OF. The field's whole
   ;; premise is that a fixer read the code and named what its repair did not
   ;; reach; with no accounts in front of it there is no such reader, and a
   ;; warden given the field anyway would be being invited to invent findings.
   (if (seq fixer-accounts)
     (str ",\n"
          " \"promote\": [{\"title\": \"<the defect, as a finding title>\",\n"
          "              \"file\": \"<absolute path>\", \"line\": <line or null>,\n"
          "              \"priority\": <1|2|3>,\n"
          (if layered?
            "              \"owner_layer\": \"<layer label from the stack below>\",\n"
            "")
          "              \"body\": \"<what is wrong there, for the fixer>\",\n"
          "              \"because\": \"<which account named it, and why it stands>\"}]}\n"
          "Empty unless an account under WHAT THE FIXERS SAID names a defect no\n"
          "finding below covers; that section says when to fill it.\n")
     "}\n")
   "Every finding below must appear exactly once.\n\n"
   "DECISION:\n"
   "- continue: something is worth fixing now.\n"
   "- stop: nothing left worth fixing; remaining items are nits.\n"
   "- escalate: a finding CONTRADICTS A NAMED INVARIANT of the design below —\n"
   "  the design is in question, not its execution. Name the invariant in your\n"
   "  reason. Do not escalate because a finding merely feels fundamental.\n\n"
   "STANDING — what you know is open and are handing to nobody\n"
   "One entry for each: a sibling an account named that you could not place, a\n"
   "defect a round's history shows and no reviewer raised, anything about the\n"
   "branch you would want a person to see before it lands. `why_no_finding` is\n"
   "why this round is not acting on it — outside the change, no layer owns it,\n"
   "not a fixer's work.\n"
   "Put it HERE, not in your `reason`. This list is carried onto the\n"
   "workstream's ledger, which outlives this run's directory; the reason is not,\n"
   "and one run's ten items reached nobody because they were prose.\n"
   "State it whole every round, not the difference since the last one: the round\n"
   "that ends the run is the one that is read, and nothing else in the loop can\n"
   "retract an item you named earlier or restate one you dropped.\n"
   "Empty is an answer, and it is the answer for most rounds.\n\n"
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
   "SWEEP — what it buys, and what your sentence has to match\n"
   "The criterion is on the field above; this is what turns on it. The fixer is\n"
   "otherwise told to make the minimal change, so it repairs the one line it was\n"
   "handed and the next round finds the next instance — ten rounds for ten\n"
   "instances of one defect. With `sweep` it fixes this one and then audits for\n"
   "the rest. You are the reader that sees the class; nothing else can.\n"
   "`sweep` and `because` are one ruling. The fixer is told to sweep by the\n"
   "FIELD — your sentence is not read for it — so a `because` that says the\n"
   "fixer should audit for siblings, over a field that says false, orders\n"
   "nothing and leaves the class open with an account claiming it was closed.\n\n"
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
   "Each finding carries a reach the reviewer assigned, and the reviewer was\n"
   "holding the same design record you are: local (the design settles it and the\n"
   "code departs from it), structural (the design is SILENT here — a question\n"
   "about where a boundary sits that nothing stated answers), or unclear. It is\n"
   "not a severity.\n"
   "A structural finding is where park and recut live, and it is the one you\n"
   "must NOT hand to a fixer when it contradicts an invariant below: patching a\n"
   "design question makes it disappear without anyone deciding it.\n"
   "A finding may also carry CONTRADICTS — the invariant the reviewer says the\n"
   "code departs from, quoted from the same list you hold. That is a CLAIM and\n"
   "not a ruling: the reviewer read the code against the clause, which is the one\n"
   "reading you cannot redo, but whether the departure is real and what follows\n"
   "from it are yours. Check it against the list; a citation that quotes no\n"
   "clause on it is a paraphrase and licenses nothing.\n"
   "The two are different answers and not alternatives. CONTRADICTS says the\n"
   "design SPEAKS and the code disagrees — your park ground (a), where the\n"
   "question is whether the design stands. `structural` with no citation says the\n"
   "design is SILENT, and nothing in the record answers it: that is ground (c)\n"
   "shaped like a boundary question, and handing it to a fixer asks for a minimal\n"
   "edit to a hole in the design.\n\n"
   (if design
     (str (design-block design) "\n")
     (str "No design record on this workstream. Weigh the findings on their own\n"
          "merits, and do NOT park anything for contradicting an invariant: with\n"
          "no stated invariant there is nothing for a finding to contradict.\n"
          "Park on RECURRENCE still applies — it rests on this run's history,\n"
          "which you have, and not on any record. So does park on NO MOVE:\n"
          "it rests on the finding's own kind. Ground (a) is the only one\n"
          "the record was carrying, so a boundary question the loop cannot\n"
          "move is still a park here — not a `deviation`, which would\n"
          "settle it and report nothing remaining.\n"
          "The RECUT kinds above are still `recut`, and the kinds listed as NOT A\n"
          "FIXER'S WORK are still not a fixer's — neither case turns on the\n"
          "design record.\n\n"))
   (when stance (str (stance-block stance) "\n"))
   (when-let [t (toc-block toc)] (str t "\n"))
   (answered-block answered)
   (when (seq parked)
     ;; A park is never raised again — that is what a park IS — so it leaves the
     ;; findings the moment the reviewer stops mentioning it and the next warden
     ;; has no idea it exists. Fifteen rounds re-adjudicated one cut from
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
   (refused-repairs-block refused)
   (unstarted-block unstarted)
   (fixer-accounts-block fixer-accounts)
   (seen-block seen)
   "History of prior rounds (findings + what was fixed):\n"
   (pr-str history) "\n\n"
   "THIS ROUND'S FINDINGS:\n\n"
   (findings-list findings))))
