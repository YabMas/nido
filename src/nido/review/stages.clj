;; src/nido/review/stages.clj
(ns nido.review.stages
  "The review loop's three stages (review/warden/fix) as {:name :run} maps,
   plus the warden-decision parser. Stages only transform the iteration
   context; the engine owns flow."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.report :as report]
   [nido.platform.core :as core]
   [nido.review.cache :as cache]
   [nido.review.codex :as codex]
   [nido.review.conformance :as conformance]
   [nido.review.digest :as digest]
   [nido.review.layers :as layers]
   [nido.review.prompts :as prompts]
   [nido.session.launcher :as launcher]
   [nido.session.lifecycle :as lifecycle]
   [nido.vsdd.jj :as jj]))

(def stage-statuses
  "Every status these stages end a run on themselves, beside the ones the engine
   assigns (`nido.review.loop/engine-statuses`).

   The two sets together are the diff loop's whole terminal vocabulary, and the
   ledger's ReviewReport enum has to admit all of it — that enum is closed, and
   an append it refuses is swallowed. See `engine-statuses` for why the check
   lives in a test rather than in the enum itself.

   `:unfixable` is in both: the engine's give-up counter reaches it, and so does
   the warden stage, over the warden's own head, when a park has stood too long.
   `:unresolved` is in both for the same shape of reason — the engine reaches it
   when a round stops holding something, and the review stage reaches it over a
   round that found nothing while the last run's `:open` list still names a
   layer nobody answered for."
  #{:stack-conflicted :nothing-to-review :clean :warden-indeterminate :unfixable
    :dry-run :workspace-drifted :fix-unrouted :fix-conflicted :fix-rolled-back
    :fix-declined :fix-timed-out :fix-launch-failed :unresolved})

(def ^:private fenced-json-re #"(?s)```json\s*(\{.*?\})\s*```")

(def ^:private dispositions
  "What the parser accepts, read off the same list the warden is told.

   Derived rather than restated: a word offered to the warden that no consumer
   recognises, or accepted here and never offered, is a drift neither side
   fails on. See `prompts/disposition-vocabulary`."
  (into #{} (map :disposition) prompts/disposition-vocabulary))

(def ^:private settling-dispositions
  "The dispositions that END a finding — decided, nothing further owed.

   Read off the same list, so convergence and the carried answers cannot come to
   different views about whether a finding is still open. A finding that is NOT
   settled is one somebody still owes something on, whether that is a fixer, a
   human, or a destination this loop does not have yet."
  (into #{} (comp (filter :settles?) (map :disposition))
        prompts/disposition-vocabulary))

(defn ^{:malli/schema [:=> [:cat :Finding] :boolean]}
  settled?
  "Has this finding been decided? See `settling-dispositions`."
  [f]
  (contains? settling-dispositions (:disposition f)))

(def ^:private kept-dispositions
  "The settled dispositions whose finding is still TRUE of the branch — the
   decision was to live with it rather than that it was never ours.

   Read off the same list, for the same reason as `settling-dispositions`. What
   it separates is what a record must carry: a close is a duplicate, an
   out-of-scope or a false positive and there is nothing left of it once it is
   decided, while a decline is a real defect this branch is shipping and a
   deviation is a layer's own claim it has stopped meeting. Dropping those two
   out of the record along with the closes deletes the only trace that anyone
   agreed to them."
  (into #{} (comp (filter :kept?) (map :disposition))
        prompts/disposition-vocabulary))

(defn ^{:malli/schema [:=> [:cat :Finding] :boolean]}
  kept?
  "Was this finding decided and left standing in the branch? See
   `kept-dispositions`."
  [f]
  (contains? kept-dispositions (:disposition f)))

(defn ^{:malli/schema [:=> [:cat :Finding] :boolean]}
  repair-attempted?
  "Did the round that ruled this finding aim a repair at it?

   `park` is the one ruling that answers a finding by putting it to a human, so
   it launches no fixer and orders no reshape: nothing was tried, and the round
   is no evidence that the loop cannot move the defect. Every other ruling
   either dispatches work or ends the finding.

   And a dispatch is a repair only if a fixer RAN. The fix stage stamps
   `:fixer-ran?` on every finding it handed to a launch, off the same reading
   that files the launch (see `record-launch`), so a finding whose fixer claude
   refused at the door reads false here exactly as it is left out of
   `nido.review.report/fix-attempts`. Unstamped is a round the fix stage has not
   reached yet — the counter is asked after the warden, before any fixer runs —
   and there the ruling is all there is.

   Read by `nido.review.loop/run-loop`'s give-up counter, which asks how many
   repairs were tried and failed. It counted a parked round as a failure, so a
   defect repaired once and parked in the three rounds after it hit the counter
   at four — a round ahead of `park-persists-for`, and through a door
   `park-blocks?` does not gate, which is where the question of whether a
   standing park should stop a run was decided. A launch that never started is
   bounded by `launch-failure-limit` instead, which ends the run on the
   machinery rather than on the defect."
  [f]
  (and (not= :park (:disposition f))
       (not (false? (:fixer-ran? f)))))

(def ^:private requirements
  "Per disposition, the field it is not a decision without and the values that
   field may take — read off the same list the warden is told.

   Derived for the same reason `dispositions` is: a requirement stated to the
   warden and not checked here is prompt text, and one checked here and not
   stated is a rule the warden is failed by without being told."
  (into {}
        (comp (filter :requires)
              (map (juxt :disposition #(select-keys % [:requires :one-of :and-requires]))))
        prompts/disposition-vocabulary))

(defn- unmet-requirement
  "Why disposition `d` is not a decision on ruling `r`, as a sentence for the
   fixer, or nil when it is one.

   The field has to be PROSE — a non-blank string — because that is what the
   next round reads: the answered block hands a close's authority back to the
   reviewer verbatim, and the fix prompt hands a `because` to the fixer. A JSON
   `true` there says nothing to either, and one run returned exactly that.

   A settling disposition is the loop deciding a finding is owed to nobody, so
   the cost of accepting a bad one is a defect that leaves the run looking
   answered. That is the asymmetry the demotion trades on.

   The field a value goes on to require is held to a non-blank string and
   nothing more. Whether a `duplicate_of` names a finding the round holds is
   asked where the round is in scope — see `owed-by` — and not here, where one
   ruling is."
  [d r]
  (when-let [{:keys [requires one-of and-requires]} (get requirements d)]
    (let [v     (get r requires)
          ruled (str "ruled `" (name d) "` ")
          field (str "`" (name requires) "`")
          then  (get and-requires v)
          w     (when then (get r then))]
      (cond
        (or (nil? v) (and (string? v) (str/blank? v)))
        (str ruled "with no " field)

        (not (string? v))
        (str ruled "with " (pr-str v) " where " field " wants a sentence")

        (and one-of (not (some #{v} one-of)))
        (str ruled "on an " field " of \"" v "\", which is not one of "
             (str/join ", " one-of))

        (and then (or (not (string? w)) (str/blank? w)))
        (str ruled "as `" v "` with "
             (if (or (nil? w) (string? w)) "no " (str (pr-str w) " in "))
             "`" (name then) "`")))))

(def ^:private min-citation-chars
  "How much of an invariant a delimited span has to be before it counts as a
   citation of one.

   A citation is a CLAUSE. Below about four words a span is a word or an
   identifier — `sweep`, `same_as` — and those occur inside almost any
   invariant, so a lower threshold would let a `because` quoting its own
   vocabulary pass as a citation of the design. Deliberately not published to
   the warden: a warden told a character count pads to it."
  20)

(defn- citation-text
  "One string reduced to what a substring test should compare on: case, run
   lengths of whitespace and the typographic variants a model emits for quotes,
   apostrophes and dashes it is copying are differences nobody intended."
  [s]
  (-> (str s)
      (str/replace #"[‘’ʼ]" "'")
      (str/replace #"[“”]" "\"")
      (str/replace #"[‐-―]" "-")
      str/lower-case
      (str/replace #"\s+" " ")
      str/trim))

(defn- quoted-spans
  "The delimited spans of `s` long enough to be a clause, as `citation-text`
   compares them.

   Double quotes and backticks both delimit: the warden is writing JSON, where
   the first has to be escaped, and reaches for the second when it would rather
   not. `min-citation-chars` is what keeps the backtick spelling from turning
   every mention of a field name into a citation."
  [s]
  (->> (re-seq #"[\"`]([^\"`]+)[\"`]" s)
       (map (comp str/trim second))
       (filter #(>= (count %) min-citation-chars))))

(defn- design-invariants
  "The invariant clauses of `design`, as `citation-text` compares them. Empty
   for a workstream with no record."
  [design]
  (into []
        (comp (map report/invariant) (map :invariant) (map citation-text)
              (remove str/blank?))
        (:invariants design)))

(defn- cite-invariants
  "Hold each finding's `:contradicts` against the design's own clauses, and keep
   only the ones that are actually there.

   The reviewer prompt tells a reviewer the field is checked against the list, so
   this is what makes that sentence true. A promise of a check that no code
   performs is worse than no promise: it buys the compliance of whoever believed
   it and none of the guarantee.

   Same substring test, same normalisation and the same reason as
   `uncited-invariant` one reader downstream: an invariant is a text inside the
   prompt that quoted it, which makes this the one claim in the loop a string
   comparison settles at no agent cost. And the same failure it exists for — a
   clause restated slightly wider is a DIFFERENT rule, and everything after here
   acts on the words the citation carries.

   A citation that matches nothing is MOVED rather than dropped, to
   `:miscited`. The reviewer's reading may be right even where its quoting was
   sloppy, and the warden is the reader that can tell; deleting it would take
   away the finding's whole account of why it thought the design was in question.
   What it must not do is keep counting as a citation, because ground (a) turns
   on one."
  [findings design]
  (let [clauses (design-invariants design)]
    (mapv (fn [f]
            (if-let [c (:contradicts f)]
              (if (some #(str/includes? % (citation-text c)) clauses)
                f
                (-> f (dissoc :contradicts) (assoc :miscited c)))
              f))
          findings)))

(defn- uncited-invariant
  "Why this `because` does not establish the design invariant it appeals to, as
   a sentence for the fixer, or nil when it appeals to none.

   The word is the trigger and a verbatim quote is the discharge. A warden is
   report-only and holds no tools, so almost nothing it asserts is falsifiable
   by the loop — but an invariant is a text inside its own prompt, which makes
   this the one claim a substring test settles at no agent cost.

   What it is for: a `because` is the only sentence the loop carries from the
   warden to the fixer, and `fix-prompt` renders it as the reviewer of the whole
   stack speaking. A warden restated the invariant it was holding in a weaker
   form there — an edge traced to a call inside a method's own FORM became every
   candidate call being in the FILE that writes the method — the fixer took the
   licence explicitly, and the post-loop verdict found the repair broke the very
   invariant the ruling claimed to satisfy.

   It fires on a MENTION rather than on a claim, so a `because` saying no
   invariant is in play is refused too. That costs one sentence in front of a
   sentence and the warden can avoid it by quoting or by not reaching for the
   word; the alternative — a citation field the warden may simply omit — leaves
   the paraphrase exactly as unchecked as it was."
  [because invariants]
  (when (string? because)
    (let [text (citation-text because)]
      (when (str/includes? text prompts/invariant-citation-cue)
        (cond
          (empty? invariants)
          "the warden leaned on a design invariant and this workstream records none"

          (not-any? (fn [q] (some #(str/includes? % q) invariants))
                    (quoted-spans text))
          (str "the warden leaned on a design invariant without quoting one the "
               "record contains, so that ground is not a licence"))))))

(defn- ruling
  "One per-finding ruling from the warden's JSON.

   A disposition outside the vocabulary is read as :fix rather than dropped —
   the fail-safe direction, and the one that keeps \"nothing is dropped\" true
   of a malformed answer. A disposition INSIDE it that omits what the
   vocabulary says it requires goes the same way, and for the stronger reason:
   an unknown word settles nothing, while a close on an authority nobody named
   ends a finding while appearing to have grounds, and rides into the next
   round as an answer the reviewer is told not to re-argue.

   The rejected field is dropped rather than carried, so no reader downstream
   renders a ground the loop refused; `:because` says what was rejected, in
   front of whatever the warden wrote, because that is the one channel that
   reaches both the report and the fixer.

   An unquoted appeal to a design invariant is refused through that same
   channel and leaves the DISPOSITION alone. Demotion is the fail-safe for a
   missing field because a settling ruling with one is a shrug, but the ruling
   an unchecked invariant most often licenses is already `fix`, and demoting the
   others would hand a design question to a fixer — which is the one move the
   warden is told never to make about one. It is the ground that fails here,
   not the ruling; see `uncited-invariant`."
  [r invariants]
  (let [d       (some-> (:disposition r) keyword)
        d       (if (contains? dispositions d) d :fix)
        unmet   (unmet-requirement d r)
        uncited (uncited-invariant (:because r) invariants)
        refused (str/join "; and " (remove nil?
                                          [(when unmet
                                             (str "the warden " unmet
                                                  ", so this is being fixed rather than settled"))
                                           uncited]))
        base    {:id          (:id r)
                 :same-as     (:same_as r)
                 :owner-layer (:owner_layer r)
                 :disposition d
                 :authority   (:authority r)
                 :of          (:of r)
                 ;; Only on the ground that asks for it. The answer shape offers
                 ;; the field on every ruling, and a pointer filled in beside
                 ;; any other authority would have `owed-by` hold a layer open
                 ;; over the template.
                 :duplicate-of (when (= :duplicate_of
                                        (get-in requirements [d :and-requires (:authority r)]))
                                 (:duplicate_of r))
                 ;; Whether this finding is one instance of a class the fixer
                 ;; should sweep. The warden recognises a recurring family
                 ;; unprompted — it says so in `because`, in prose, every time —
                 ;; and had no field to say it in.
                 :sweep       (boolean (:sweep r))
                 :because     (:because r)}]
    ;; The demotion runs FIRST: the field it clears is sometimes `because`
    ;; itself — a decline is not one without a reason — and clearing it after
    ;; the refusal was written would drop the sentence explaining the demotion.
    (cond-> base
      unmet
      (-> (assoc (get-in requirements [d :requires]) nil)
          (assoc :duplicate-of nil)
          (assoc :disposition :fix))

      (seq refused)
      (assoc :because (str refused
                           (let [b (:because r)]
                             (when (and (string? b) (not (str/blank? b)))
                               (str " — it said: " b))))))))

(defn- standing-items
  "The warden's `standing` entries, normalised — what it says is open and is
   handing to nobody this round.

   Settled here rather than downstream, unlike `:promote`: a promotion becomes a
   finding and so has to be weighed against the ids and layers the round holds,
   while a standing item is a report about the branch and is answerable from its
   own two fields. Nothing in the loop acts on one, which is what it is saying.

   An entry with an empty `what` names nothing and is dropped; two entries
   naming the same thing are one. Both are what a warden restating its list
   whole every round will produce, and neither is worth a round-trip to ask
   about."
  [xs]
  (into []
        (comp (map (fn [s]
                     (cond-> {:what (str/trim (str (:what s)))}
                       (not (str/blank? (str (:why_no_finding s))))
                       (assoc :why-no-finding (str/trim (str (:why_no_finding s)))))))
              (remove #(str/blank? (:what %)))
              (distinct))
        xs))

(defn ^{:malli/schema [:function
                       [:=> [:cat :string] :map]
                       [:=> [:cat :string [:maybe :map]] :map]]}
  parse-warden-decision
  "Last fenced ```json block in `text` -> {:decision :reason :rulings :promote
   :standing}. Unparseable -> indeterminate.

   `:promote` is carried raw, exactly as the warden wrote it. A ruling names a
   finding this round already holds, so the parser can decide on its own whether
   it is a decision; a promotion names a defect that is not a finding yet, and
   whether it can become one turns on what else the round holds — the ids
   already raised, the layers in the stack. `promoted-findings` asks that, where
   both are in scope.

   `:standing` is neither, and `standing-items` settles it here: it is what the
   round is NOT acting on, so no other part of the round can contradict it.

   `design` is the record the warden was SHOWN, and it is an argument rather
   than a read so that `uncited-invariant` compares a quote against the very
   text the prompt rendered. Re-reading the ledger here would let the two
   diverge, and a quote failing to match would then mean either a restatement or
   an amendment landing mid-run. Omitted, every appeal to an invariant is
   refused — which is the answer for a workstream that records none."
  ([text] (parse-warden-decision text nil))
  ([text design]
   (let [invariants (design-invariants design)
         block      (when (string? text) (last (re-seq fenced-json-re text)))]
     (if-let [body (second block)]
       (try
         (let [m (json/parse-string body true)
               d (keyword (:decision m))]
           (if (contains? #{:continue :stop :escalate} d)
             {:decision d
              :reason   (:reason m)
              :rulings  (into [] (comp (filter :id) (map #(ruling % invariants)))
                              (:findings m))
              :promote  (vec (:promote m))
              :standing (standing-items (:standing m))}
             {:decision :indeterminate :reason (str "unknown decision: " (:decision m))}))
         (catch Exception e
           {:decision :indeterminate :reason (str "unparseable: " (ex-message e))}))
       {:decision :indeterminate :reason "no json decision block"}))))

(defn ^{:malli/schema [:=> [:cat :Path] :any]}
  project+ws-from-cwd
  "Resolve cwd → [project ws-id] via the session, or nil. The same path
   tasks.nido-review/append-review-entry! takes to find where to write."
  [cwd]
  (try
    (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
      (when-let [ws-id (csession/workstream-id-for (keyword project) session)]
        [(keyword project) ws-id]))
    (catch Throwable _ nil)))

(defn ^{:malli/schema [:=> [:cat :Path :any] :any]}
  session-stack
  "This session's layers, bottom→top, or [] when cwd resolves to no session (a
   review run outside a nido worktree still has to work — it just has no stack
   to land on)."
  [cwd base]
  (try
    (if-let [session (:session (lifecycle/session-from-cwd cwd))]
      (layers/stack cwd session (or base "main"))
      [])
    (catch Throwable _ [])))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  in-parallel
  "Run every thunk at once, preserving order.

   UNBOUNDED, and that is the point. These are codex processes that spend their
   life waiting on an API, so the machine is not what a bound would protect —
   and the bound that was here (six) cost more than it saved. It chunked with
   `partition-all`, so chunk N+1 waited on the SLOWEST member of chunk N rather
   than on a free slot: measured across the runs on disk, a round of nine
   reviewed targets took 822s against a slowest single review of 442s, while a
   round of six took 600s against a slowest of 599s. A stack pays that penalty
   exactly when it is wide, which is when the parallelism was the whole reason
   to cut it into layers.

   An exception in any thunk propagates carrying its ORIGINAL ex-data: a bare
   future deref wraps it in ExecutionException, which would hide the `:reason` a
   caller branches on and turn a handled failure into an unhandled crash.

   The review fan-out deliberately does NOT lean on that. A throw here abandons
   the results after it, and for a fan-out of codex reviews those are results
   that have already been paid for — so `review-target!` returns its failure as
   a value and the stage decides what to do with it."
  [thunks]
  (let [futs (mapv #(future (%)) thunks)]
    (mapv (fn [f]
            (try @f
                 (catch java.util.concurrent.ExecutionException e
                   (throw (or (.getCause e) e)))))
          futs)))

(def ^:private stack-label
  "The composition target's label — what its findings are stamped :from-layer
   with, and so how they are told apart from a layer's own."
  "stack")

(defn ^{:malli/schema [:=> [:cat :Path :any] :any]}
  composition-of
  "What the composition pass is told about the stack it is composing: one entry
   per layer, in stack order, carrying the range that layer contributes, the rev
   of the tree its own PR would merge, what it claims, what it declared out of
   scope, and what it touches.

   Built here rather than from the round's results, because the composition pass
   runs IN that round: `build-toc` reads each target's manifest once its review
   has returned, which is exactly too late to prime one with.

   The revisions are the point. The warden gets `toc-block` — a map with no
   coordinates — precisely so it cannot re-derive the layers around it. The
   composition pass gets the coordinates for the opposite reason: it is asked
   whether each piece holds together where it sits, and a layer's own tip is the
   only place that can be looked at rather than guessed."
  [cwd targets]
  (into []
        (map (fn [{:keys [label index from to brief]}]
               {:label        label
                :index        index
                :from         from
                :tip          to
                :claim        (:claims brief)
                :out-of-scope (:out-of-scope brief)
                :files        (codex/changed-files cwd from to)}))
        targets))

(defn ^{:malli/schema [:=> [:cat :Path :any] :any]}
  review-targets
  "What this round reviews: one target per layer, bounded by that layer's brief,
   plus one over the whole stack.

   Each layer target carries its :index — its 1-based place in the stack, which
   is known here and nowhere else downstream: `layers/ranges` hands them over
   bottom→top, and after this the order is just the order of a vector. The
   whole-stack target deliberately has none, because it is not a layer.

   The whole-stack target is what finds a defect that exists only in the
   COMPOSITION of two layers — something no layer reviewer can see, since each
   one is shown a diff in which the other layer does not appear. It is therefore
   only worth running when there are at least two layers to compose; below that
   it is the same diff twice, and the stack target is the only one.

   With layers to compose it also carries :composition, and that is what makes
   it a composition pass rather than a second, wider layer review. Without it
   the target is the flat-branch reviewer pointed at the whole branch and never
   told a stack exists — so it re-derives every layer it was supposed to trust,
   and the findings that are genuinely its own come back indistinguishable from
   the ones the layer reviews already hold."
  [cwd base]
  (let [base-rev (codex/merge-base cwd base)
        stack    (session-stack cwd base)
        whole    {:label stack-label :from base-rev :to "@" :brief nil :stack? true}]
    (if (< (count stack) 2)
      [whole]
      (let [per-layer (into []
                            (map-indexed
                             (fn [i r] {:label (or (:slug r) (:bookmark r))
                                        :index (inc i)
                                        :layer r
                                        :from  (:from r)
                                        :to    (:to r)
                                        :brief (layers/brief cwd (:tip r))}))
                            (layers/ranges stack base-rev))]
        (conj per-layer
              (assoc whole :composition
                     {:layers (composition-of cwd per-layer)}))))))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  with-composition-memory
  "Hand the composition target the composition findings this run already made.

   It is the only reader that can see across layers, and it starts cold every
   round: nothing tells it what it reported last time. So a pass that finds one
   cut reports it, sees the same stack next round, and reports it again — three
   rounds of a full fan-out to say one thing once. Its own prior findings cost
   nothing to recognise and are the cheapest possible way to make it look
   somewhere else.

   Nothing it adds reaches the target's cache key: `with-patch-hashes` builds
   that from the layers and their patches, so a value that changes every round
   cannot switch the cache off by living here."
  [targets history]
  (let [prior (into []
                    (comp (mapcat (fn [h]
                                    (map #(assoc % :round (:iter h)) (:findings h))))
                          (filter #(= stack-label (:from-layer %)))
                          (map #(select-keys % [:round :title :kind])))
                    history)]
    (if (empty? prior)
      targets
      (mapv (fn [t]
              (cond-> t
                (:composition t) (assoc-in [:composition :already-reported] prior)))
            targets))))

(defn- fix-label
  "The label a fix stage records against this target, which is not always the
   label the review stage gave it.

   A branch with no layers is reviewed by one whole-stack target labelled
   `stack`, and `fix-plan` groups the same branch under nil — it has no layer to
   name. The two vocabularies meet only here, and matching them on the review
   label alone silently loses the memory in exactly the flat case, which is most
   sessions. A whole-stack target with no `:composition` is by construction the
   flat one: `review-targets` attaches that key only when there are layers to
   compose."
  [t]
  (if (and (:stack? t) (not (:composition t))) nil (:label t)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  fix-accounts
  "Every repair this run landed, grouped by the layer it landed on: the round,
   the commit, the findings that commit was handed, and what the fixer said.

   Titles come from the round the fix was in rather than from the round asking:
   a finding is handed by handle-or-id and a later round rewords it, so the
   words the fixer actually saw are the ones on that round's findings.

   One derivation with two readers, which is the point of it being here rather
   than inside either. `with-fix-memory` gives a target its own layer's entries;
   `run-warden-stage` takes all of them, because a sibling a fixer names in an
   account is by construction somewhere its own layer's reviewer cannot go and
   the warden is the only reader holding the file lists to place it against."
  [history]
  (reduce
   (fn [acc {:keys [iter fixes findings]}]
     (let [by-id (into {} (map (juxt #(or (:handle %) (:id %)) identity))
                       findings)]
       (reduce (fn [a {:keys [layer commit handed account]}]
                 (update a layer (fnil conj [])
                         {:round iter
                          :commit commit
                          :account account
                          :findings (mapv (fn [h]
                                            (let [f (get by-id h)]
                                              {:title (or (:title f) h)
                                               :sweep (boolean (:sweep f))}))
                                          handed)}))
               acc
               fixes)))
   {}
   history))

(defn- refused-accounts
  "The repairs the stack REFUSED, grouped by layer in the shape `fix-accounts`
   produces, so that both reach a reviewer as one list of what a fixer has
   already tried on the code in front of it.

   Off the carry rather than the history: a round appends a history entry only
   when a fix landed, and the round this exists for landed one repair and had
   another put back.

   `:refused` is what tells the two apart, and it holds the change ids the
   rebase collided with rather than a bare flag — a reviewer asking why the
   repair is not in front of it gets the answer in the same row. The commit the
   repair was on does NOT ride along: this reader cannot look at it, and a
   change id beside a landed one would read as a claim that the edit is in the
   range."
  [carried]
  (reduce-kv (fn [acc label {:keys [since account findings conflicted]}]
               (assoc acc label
                      [{:round since
                        :account account
                        :refused (vec conflicted)
                        :findings (mapv #(select-keys % [:title :sweep]) findings)}]))
             {}
             carried))

(defn ^{:malli/schema [:=> [:cat :any :any :any] :any]}
  with-fix-memory
  "Hand each target the repairs a fixer already aimed at it in this run — the
   ones that landed, and the ones the stack put back.

   Every reviewer starts cold and is shown a diff, so nothing in the loop ever
   asks whether a fix closed what it was handed. The fix stage has recorded the
   join since `:handed` was added — the commit, and the findings that commit was
   for — and no reader used it to go and check. A swept defect came back at the
   same window in rounds 2, 3 and 4 of one run, each time as a fresh finding,
   because the reviewer reading those lines had never been told a repair for
   them had already landed there.

   A refused repair is here for the mirror-image reason. The code is exactly
   what the round before read, so the finding is untouched and its reviewer has
   no diff to notice — one round re-read a byte-identical patch and returned
   `correct` on a P2 the round before had ruled `fix`. Both kinds are one list
   in round order, because they answer one question: what has already been tried
   here.

   Keyed on the layer label, which is what `fix-plan` groups by and what the
   commit is recorded under — except on a branch with no layers, where the two
   sides spell the same thing differently; see `fix-label`.

   Like `with-composition-memory`, nothing it adds reaches the cache key —
   `with-patch-hashes` builds that from the range, so a value that changes every
   round cannot switch the cache off by living here."
  [targets history refused]
  (let [by-label (merge-with into (fix-accounts history) (refused-accounts refused))]
    (if (empty? by-label)
      targets
      (mapv (fn [t]
              (if-let [prior (seq (get by-label (fix-label t)))]
                (assoc t :prior-fixes (vec (sort-by #(or (:round %) 0) prior)))
                t))
            targets))))

;; Defined below, with the other readings taken off the workstream's ledger.
(declare standing-needs prior-open discover-design-record)

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  with-standing-needs
  "Hand each target what the last run's design verdict left outstanding, so a
   reviewer can turn it into a finding — see `standing-needs`.

   Never the composition pass. The verdict names located defects in code, and
   that pass is asked whether the cut holds and is told in as many words not to
   report what the layer reviews are already holding; a defect at a line is
   exactly what it must not answer with. The flat whole-stack target has no
   `:composition` and does get it — on a branch with no layers it is the only
   reviewer there is.

   Nothing it adds reaches the cache key, for the reason `with-fix-memory` gives:
   `with-patch-hashes` builds that from the range, so a value that changes
   between runs cannot switch the cache off by living here. That matters more
   here than there — a standing item is the same string every round, and a
   target skipped on a converged hash is a target whose code nobody is claiming
   changed."
  [targets standing]
  (if-not standing
    targets
    (mapv #(cond-> % (nil? (:composition %)) (assoc :standing standing))
          targets)))

(defn ^{:malli/schema [:=> [:cat :Path :any :any :any] [:maybe :string]]}
  placed-on
  "The layer of `toc` a finding NO REVIEWER RAISED is owed of: `named` when that
   is one of its layers, otherwise the highest layer whose files include `file`,
   and nil when neither places it. `toc` is the round's, bottom→top — see
   `build-toc`.

   Two kinds of finding enter a run naming their layer instead of being read off
   one: a warden's promotion, and a row the last run left open. Either can name
   no layer the stack has — a warden that gave null, a label a rename or a fold
   has since removed — and a finding owed of no layer holds no layer's
   convergence open and reaches no layer's reviewer, while every count still
   calls it open. Both are placed here, so the rule and its tie-break are stated
   once for both.

   A named layer the stack still has wins: whoever named it read more than a
   path. A file several layers touch goes to the HIGHEST of them. Nothing above
   that layer changes the file, so a repair made there is rebased over no later
   edit to it, where one made lower down is rebased through every one of them —
   the collision the fix stage rolls back.

   Both paths are resolved against `cwd` before they are compared: a finding's
   file is absolute, and the file lists are relative to the worktree."
  [cwd toc named file]
  (let [labels (into #{} (map :label) toc)
        path   #(str (fs/normalize (fs/path (str cwd) (str %))))
        target (when-not (str/blank? (str file)) (path file))]
    (cond
      (contains? labels named) named
      target (some (fn [{:keys [label files]}]
                     (when (some #(= target (path %)) files) label))
                   (rseq (vec toc))))))

(defn- where-file
  "The file in a ledger row's `:where` — `file:line`, or the file alone when the
   finding had no line."
  [where]
  (some->> where str (re-matches #"(.+?)(?::\d+)?") second))

(defn- unplaced-item
  "The `standing` entry for a finding `placed-on` put on no layer. `source` is how
   it entered the run, which its title does not say."
  [{:keys [title where layer]} source]
  {:what           (str title (when where (str " (" where ")")) " — " source)
   :why-no-finding (str (if (str/blank? (str layer))
                          "it names no layer"
                          (str "it names " layer ", which is no layer of this stack"))
                        (if where
                          ", and no layer of this stack touches its file"
                          ", and it carries no file to place it by"))})

(defn ^{:malli/schema [:=> [:cat :Path :any :any] :map]}
  place-inherited
  "The last run's open rows put onto this round's stack: `:rows` is every row,
   each one `placed-on` places carrying that layer as its `:layer`, and
   `:unplaced` is the rows it places nowhere, still naming what they named.

   A row's file comes out of its `:where`, the only form the ledger keeps it in.

   With no layers, every row is the whole-stack target's: a flat branch has
   nowhere else for a defect to be, and nothing to place against."
  [cwd toc rows]
  (if (empty? toc)
    {:rows (mapv #(assoc % :layer stack-label) rows) :unplaced []}
    (reduce (fn [acc r]
              (if-let [l (placed-on cwd toc (:layer r) (where-file (:where r)))]
                (update acc :rows conj (assoc r :layer l))
                (-> acc (update :rows conj r) (update :unplaced conj r))))
            {:rows [] :unplaced []}
            rows)))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  with-prior-open
  "Hand each layer the findings the last run left OWED against it, so the one
   reviewer that reads that code is told what is already known about it — see
   `prior-open`.

   Matched on the label, which is the only identity that survives the gap. The
   patch hash cannot do it: a run that repairs a layer moves its patch, so the
   answers hung off the hash are about content that no longer exists, and
   `answered-by-layer` makes the same argument about the settled half of the
   same history. The label is the one `place-inherited` gave the row this round,
   so a row that named a layer the stack no longer has still reaches the
   reviewer of the layer holding its file.

   A PARK is withheld, and for the reason `with-standing-needs` withholds an
   invalidating verdict's `:needs`: a park is a question put to a human — that
   is what makes it a park — and `tasks.nido-review/parked-blocker` already
   carries it to the gate. A reviewer asked to re-report it would have a fixer
   patch away the very question somebody is being asked.

   Never the composition pass, again as `with-standing-needs`: these are
   defects at lines, and that pass is told not to answer with one. A flat
   branch's whole-stack target has no `:composition` and does get them.

   Nothing it adds reaches the cache key — `with-patch-hashes` builds that from
   the range — which matters here for the same reason it matters there: the
   inherited list is the same text every round of a run, and a target skipped on
   a converged hash is one whose code nobody is claiming changed."
  [targets inherited]
  (let [by-layer (group-by :layer (remove #(= :park (:disposition %)) inherited))]
    (if (empty? by-layer)
      targets
      (mapv (fn [t]
              (if-let [owed (and (nil? (:composition t)) (seq (get by-layer (:label t))))]
                (assoc t :prior-open (vec owed))
                t))
            targets))))

(defn- composition-key
  "The composition target's identity: the patch it spans, plus the cut that
   divides it — each layer's label paired with its own patch hash, in stack
   order.

   Every component is derived from content, so the key holds across a rebase, a
   squash or an amend exactly as a layer's key does. Built from `composition-of`
   instead, it would carry each range's commit ids and the text of its brief,
   and miss whenever the stack was rewritten at all: one run reviewed a
   composition that had converged eight minutes earlier while both of its layers
   hit the cache at their own unchanged hashes.

   The patch stays in beside the cut, because the layers do not quite cover the
   range: they end at the top bookmark and the range ends at `@`, so anything
   sitting above the stack reaches the key through the patch and nowhere else.

   nil when the range or any one layer's hash is unknown. A key built over a
   hole would let a later run skip content nothing has established is
   unchanged."
  [whole-hash cut]
  (when (and whole-hash (seq cut) (every? (comp some? second) cut))
    (digest/sha256-hex (pr-str [whole-hash cut]))))

(defn ^{:malli/schema [:=> [:cat :Path :any] :any]}
  with-patch-hashes
  "Stamp each target with the hash of the patch it contributes — its identity
   for the cache. A target whose hash cannot be computed keeps nil and is
   therefore never skipped.

   The composition target folds in the CUT as well as the patch. Its range is
   `base-rev..@`, and re-cutting a stack — moving code between layers, splitting
   one in two, reordering them — leaves that range byte-identical: the branch
   still contains the same work. So a composition pass that demanded a
   re-layering, got one, and ran again would find its own hash unchanged and
   skip the very thing it asked for. What that pass reviews is not the patch but
   how the patch was divided, so its identity has to include the division.

   Every layer hash is taken first because the cut is built out of them — which
   is also why the composition target has to arrive in the same vector as the
   layers it composes, as `review-targets` hands them over.

   The range's own hash survives the fold as `:range-hash`, because the key that
   replaces it is derived and a reader of the report can otherwise only see the
   derivation. Each layer's half of the cut reaches report.json on that layer's
   row; keeping this half puts the whole of the key's input there, so a
   composition that failed to skip can be traced to the component that moved."
  [cwd targets]
  (let [hashed (mapv #(assoc % :patch-hash (layers/patch-hash cwd (:from %) (:to %)))
                     targets)
        cut    (into [] (comp (remove :stack?) (map (juxt :label :patch-hash))) hashed)]
    (mapv (fn [t]
            (cond-> t
              (:composition t) (assoc :range-hash (:patch-hash t)
                                      :patch-hash (composition-key (:patch-hash t) cut))))
          hashed)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  content-hashes
  "What the branch holds this round, as a set — every target's patch hash,
   skipped ones included.

   Compared across rounds it answers one question: did the code move? Which is
   what tells a stalled loop from one still narrowing a defect class, so it
   covers the targets the round SKIPPED as well as those it read — a layer left
   alone because it converged is still part of what the branch contains.

   A target whose hash could not be computed contributes nothing rather than a
   nil, so a round jj could not diff produces an empty set: unknown content is
   not evidence of change, and the callers that read this treat it as none.

   Two rounds can only collide by contributing the same patches under the same
   cut — the composition target's hash folds in the layer/hash pairs, so a
   re-cut that moves code between layers lands here even though the set of
   layer hashes it is built from could not."
  [targets]
  (into #{} (keep :patch-hash) targets))

(defn ^{:malli/schema [:=> [:cat :any :any] :boolean]}
  quiet-again?
  "Whether a quiet round is the second reading of what the FIRST quiet round
   read: `carried` is that round's `content-hashes`, `targets` this round's,
   skipped ones included.

   The pair of quiet rounds `clean` is earned by is a claim that two independent
   readings of one content found nothing. So a round that read anything else is
   a first reading, however many quiet rounds came before it — whatever moved
   the code in between, a repair the warden promoted out of a quiet round or a
   round that found and fixed, left nobody but this round reading the result.

   Unknown content matches nothing. `content-hashes` drops a target it could not
   hash, which is the safe reading for asking whether the code moved and the
   unsafe one for asking whether it stayed: two readings that could each hash
   only part of the branch agree about that part and nothing else. A round with
   any unhashed target, or with no targets, is a first reading."
  [carried targets]
  (boolean (and (seq targets)
                (every? :patch-hash targets)
                (= carried (content-hashes targets)))))

(defn ^{:malli/schema [:=> [:cat :map :any] :map]}
  to-review
  "Split targets into those this round must review and those already converged
   at exactly this patch. A target with no hash is always reviewed: unknown
   content is reviewed content.

   A skipped target is stamped with WHEN its convergence was recorded. A skip is
   the loop declining to look at something, and the report could not say on what
   authority: the row named the layer and nothing else, so `skipped` was
   indistinguishable from a claim the reader had to take on trust.

   A timestamp rather than the round number the row used to carry. The cache
   outlives any one run, so that round belonged to whichever run wrote the entry
   — a one-round report carried three rows reading `converged-at 2`, from a run
   six hours earlier, and nothing on the row said the number was foreign. A
   round the reader cannot place is worse than none; a timestamp says at a
   glance how old the convergence is, and the patch hash beside it is what makes
   the row checkable against the cache.

   An entry with no timestamp — one written before this was recorded — stamps
   nothing rather than nil, so the row says what it can stand behind."
  [cache targets]
  (let [skip? (fn [t] (and (:patch-hash t) (cache/converged? cache (:patch-hash t))))]
    {:review  (into [] (remove skip?) targets)
     :skipped (into []
                    (comp (filter skip?)
                          (map (fn [t]
                                 (let [at (:at (get cache (:patch-hash t)))]
                                   (cond-> t at (assoc :converged-at at))))))
                    targets)}))

(defn- collect-findings
  "Flatten every target's findings, stamping each with the target that reported
   it, and drop exact repeats.

   A finding seen at the same file, line and title by two targets is one
   finding — the layer reviewer's copy wins because layer targets come first and
   its view is the more specific one. This is only the mechanical case; deciding
   that two DIFFERENTLY worded findings are the same defect needs a view across
   layers, which is the warden's job."
  [results]
  (->> results
       (mapcat (fn [{:keys [target] :as r}]
                 (map #(assoc % :from-layer (:label target)) (:findings r))))
       (reduce (fn [{:keys [seen out] :as acc} f]
                 (let [k [(:file f) (:line-start f) (:title f)]]
                   (if (contains? seen k)
                     acc
                     {:seen (conj seen k) :out (conj out f)})))
               {:seen #{} :out []})
       :out))

(defn- build-toc
  "The stack's table of contents — one entry per layer, bottom→top: what it
   claims, what it declared out of scope, and which files it touches. This is
   what the warden gets INSTEAD of the other layers' diffs, so it can attribute
   deliberately without re-deriving them, what a fixer gets so it can tell its
   own files from the ones a layer above will be rebased over, and what
   `placed-on` places a finding no reviewer raised against.

   EVERY layer, from the round's targets rather than from its results. Built
   from the results it named only the layers this round actually reviewed, so a
   layer that converged and was skipped vanished from the map — a stack of four
   rendered as two by the third round. That is a lie in the one direction that
   hurts: a reader is told a layer does not exist exactly when it has gone
   quiet, and a quiet layer is still in the stack and still owns its files.

   `:out-of-scope` is carried because the warden is told it may close a finding
   on the authority `out-of-scope` — \"a layer's Out of scope names it\". Without
   the field, that authority is one the warden can cite but never actually
   read, which is how a close stops being evidence and becomes a guess.

   The same table `composition-of` builds, minus the revisions. Both read the
   files off the layer's own range, and the omission is deliberate: see
   `prompts/composition-layer-rows` for why only one of the two readers is
   given coordinates."
  [cwd targets]
  (into []
        (comp (remove :stack?)
              (map (fn [{:keys [label from to brief]}]
                     {:label        label
                      :claim        (:claims brief)
                      :out-of-scope (:out-of-scope brief)
                      :files        (codex/changed-files cwd from to)})))
        targets))

(def ^:private cleared-verdict
  "The only correctness answer that clears a target. `review_prompt.md` asks for
   \"correct\" when the patch is free of blocking issues and \"incorrect\"
   otherwise, and the schema types the field as a bare string — so a reviewer
   that answered anything else did not clear what it read."
  "correct")

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :string]]}
  round-correctness
  "The round's correctness verdict: the worst answer its reviewers gave, or nil
   when none of them reached one.

   A round has as many verdicts as it opened targets, and the whole-stack pass's
   is not the round's. On a layered stack that pass is the COMPOSITION reviewer
   — asked whether the cut holds, not whether the code inside a layer is right —
   so a round that took its answer alone published `correct` on a report row
   whose own findings array held a P1 the layer pass had reported `incorrect`
   for. A reader comparing the two fields had no way to tell which was lying.

   A dissent is carried through VERBATIM rather than flattened to \"incorrect\":
   an unrecognised answer is a reviewer that said something, and normalising it
   into the vocabulary would hide that it was never in it. Ties break on target
   order for the same reason the fan-out rethrows the first failure in target
   order — which of several dissents matters most is a judgement this makes no
   claim about, and fixing the order is what makes the round reproducible.

   Silent targets contribute nothing: one that read nothing carries no verdict,
   and a layer skipped on a converged hash had no reviewer this round at all."
  [results]
  (let [answers (keep :overall-correctness results)]
    (or (first (remove #(= cleared-verdict %) answers))
        (first answers))))

(defn ^{:malli/schema [:=> [:cat :map :map] :any]}
  announce-targets!
  "Publish what this round is about to review, BEFORE any agent starts.

   Everything here is known at setup: the fork point from jj, the target list
   from the stack, the manifest from one `jj diff --name-only`. It used to reach
   the report only as a by-product of the WHOLE-STACK target's result — the last
   and widest target of the round — so the header could not name the stack until
   the slowest thing in it had finished, and a run interrupted before that
   reported nothing at all about what it had been reviewing.

   Best-effort by design. A run must not die because a display event could not be
   built, so a failure here is swallowed and the round proceeds exactly as it did
   before this event existed."
  [ctx {:keys [review skipped]}]
  (when-let [emit (get-in ctx [:config :emit])]
    (try
      (let [cwd      (get-in ctx [:config :cwd])
            base-rev (:from (first (filter :stack? (concat review skipped))))
            row      (fn [status t]
                       (cond-> {:label  (:label t)
                                :stack? (boolean (:stack? t))
                                :status status}
                         (:index t) (assoc :index (:index t))))]
        (emit {:event    :targets-resolved
               :iter     (:iter ctx)
               :at       (str (java.time.Instant/now))
               :base-rev base-rev
               :files    (if base-rev (codex/changed-files cwd base-rev "@") [])
               :targets  (into (mapv (partial row "pending") review)
                               (mapv (partial row "skipped") skipped))}))
      (catch Throwable _ nil))))

(defn- announce-target!
  "Move one target's row. Called from the fan-out, so it runs on a worker thread
   — `nido.review.frontend/emit-fn` serializes on our behalf.

   Best-effort for the same reason as announce-targets!: a review that finished
   must not be lost because the event describing it could not be sent."
  [ctx status t extra]
  (when-let [emit (get-in ctx [:config :emit])]
    (try
      (emit (merge {:event  :target-moved
                    :iter   (:iter ctx)
                    :at     (str (java.time.Instant/now))
                    :label  (:label t)
                    :status status}
                   extra))
      (catch Throwable _ nil))))

(defn- stack-conflicts
  "The change ids `<base>..@` is holding conflict markers on, or nil when the
   workspace could not be asked.

   Nil and [] are different answers and every reader here keeps them apart: []
   is the stack read and found clean, nil is a run that got no answer at all.
   That distinction is the whole value of asking — four consecutive runs on one
   branch ended holding the same two change ids, and nothing recorded whether
   they were standing when the run began or created by it.

   Swallowing the failure, where the fix stage's own call deliberately does not:
   a preflight that cannot run must not stop a round that would otherwise
   proceed, which is the reading `layers/resolve-rev` takes of an unaskable
   workspace. Mid-repair the answer is load-bearing and an exception there is
   the right outcome."
  [cwd base]
  (try (vec (layers/conflicted cwd base))
       (catch Throwable _ nil)))

(defn- announce-conflicts!
  "Publish what the stack's conflict state was at the start of this round.

   Emitted whatever the answer is, including the empty one. A report that
   records the ids only when there are some cannot tell a stack that was read
   and found clean from a run that never looked — and the clean answer is the
   one that settles whether a recurring conflict is being re-created each run
   or has been standing since the last one.

   Best-effort like `announce-targets!`: a display event that cannot be built
   must not end a round."
  [ctx conflicted]
  (when conflicted
    (when-let [emit (get-in ctx [:config :emit])]
      (try
        (emit {:event      :stack-conflicts
               :iter       (:iter ctx)
               :at         (str (java.time.Instant/now))
               :conflicted conflicted})
        (catch Throwable _ nil)))))

;; Defined below, beside the cache reasoning they belong with. `record-review!`
;; is called from both stages that can end a round — see its docstring — and
;; `record-statuses!` from the fan-out, for a round that ends by aborting.
(declare record-review! record-statuses! salvaged-statuses unanswered-of)

(defn- review-target!
  "One target's review, as a VALUE: the reviewer's result, or `{:target …
   :failure <throwable>}` when it could not produce one.

   Returned rather than thrown because the siblings are the point. A fan-out is
   the round's whole spend, and a thrown reviewer takes every review that had
   already finished down with it — one round lost two completed layer reviews,
   265,708 codex tokens between them, to a billing quota the third reviewer hit.
   What to do about a failure is the stage's decision, and it cannot make it
   while the exception is still in flight.

   Every throwable, not only the ex-infos the review path raises: a reviewer
   that dies some other way costs its siblings exactly as much. The stage
   rethrows what it catches, so nothing above here sees a different exception
   than it did before."
  [ctx t]
  (let [{:keys [cwd run-id]} (:config ctx)]
    (announce-target! ctx "running" t nil)
    (try
      (let [r (assoc (codex/review!
                      {:cwd cwd :run-id run-id :iter (:iter ctx)
                       :from (:from t) :to (:to t)
                       :label (:label t) :brief (:brief t)
                       :design (:design ctx)
                       :composition (:composition t)
                       :prior-fixes (:prior-fixes t)
                       :standing (:standing t)
                       :prior-open (:prior-open t)})
                     :target t)]
        (announce-target! ctx "reviewed" t {:findings (count (:findings r))})
        r)
      (catch Throwable e
        ;; Which target failed, on the row for it. A round that aborted used to
        ;; leave every unfinished row reading `running` for ever, so the report
        ;; named the phase that died and not the reviewer that died in it.
        (announce-target! ctx "error" t {:error (ex-message e)})
        {:target t :failure e}))))

(defn- fan-out-reviews
  [ctx]
  (let [{:keys [cwd base]} (:config ctx)
        [project ws-id] (project+ws-from-cwd cwd)
        cached  (if ws-id (cache/read-cache project ws-id) {})
        ;; Pin the top of the reviewed range for the whole round. `@` is
        ;; whatever the working copy currently is, and every stage that resolved
        ;; it again was silently asking about a different tree — a concurrent
        ;; rebase moved it out from under a run and nothing noticed, so the
        ;; round reviewed one state and tried to fix another.
        at      (layers/resolve-rev cwd "@")
        targets (review-targets cwd base)
        ;; Before any reviewer runs, because the round needs the map before it
        ;; has results: to place what the last run left open, just below, and
        ;; for the warden of a round that found nothing — which, handed no map,
        ;; is told the branch has no layers and so is never asked which one a
        ;; promotion belongs to.
        toc     (build-toc cwd targets)
        ;; What the last run left owed, placed on this round's layers and put
        ;; onto the carry so the stages after this one can be asked whether the
        ;; run answered it. The carry is the only channel between rounds and it
        ;; survives onto the terminal ctx, which is where the ledger entry and
        ;; the design verdict read it from.
        {inherit :rows unplaced-rows :unplaced} (place-inherited cwd toc (prior-open cwd))
        ctx     (cond-> ctx (seq inherit) (assoc-in [:carry :inherited-open] inherit))
        ;; Once per round rather than per target: every reviewer of a round is
        ;; judging one change against one design, so a second read could only
        ;; differ by racing an author editing the ledger mid-round — which would
        ;; put two reviewers of the same change on two yardsticks.
        ctx     (assoc ctx :design (discover-design-record cwd))
        all     (with-patch-hashes
                 cwd (-> targets
                         (with-composition-memory (:history ctx))
                         (with-fix-memory (:history ctx)
                                          (get-in ctx [:carry :rolled-back] {}))
                         (with-standing-needs (standing-needs cwd))
                         (with-prior-open (get-in ctx [:carry :inherited-open]))))
        {:keys [review skipped]} (to-review cached all)
        targets review
        _       (announce-targets! ctx {:review review :skipped skipped})
        outcomes (in-parallel (map (fn [t] #(review-target! ctx t)) targets))
        failed   (filterv :failure outcomes)
        results  (filterv (complement :failure) outcomes)
        ;; The last point at which this round can leave anything behind: the
        ;; engine has no stage after a throw, so a clean bill a reviewer already
        ;; paid for is discarded unless it is written here. Best-effort like
        ;; every other cache write — a salvage that cannot be persisted costs
        ;; the next run some duplicated review and nothing else.
        ;;
        ;; The FIRST failure is rethrown, in target order. Which of several
        ;; matters most is a judgement this makes no claim about; ordering it
        ;; makes the report of a round two reviewers died in reproducible.
        _        (when (seq failed)
                   (record-statuses! cwd (assoc ctx :cache cached)
                                     (salvaged-statuses results) nil)
                   (throw (:failure (first failed))))
        ;; The whole-range target, and only what is a fact about that range:
        ;; where the review started from and which files it covered. NOT where a
        ;; round-level answer comes from — on a layered stack this target is the
        ;; composition pass, which reviews the cut rather than the code. See
        ;; `round-correctness`.
        whole    (or (first (filter #(:stack? (:target %)) results))
                     (first results))
        ;; The mechanical reviewer joins the fan-out, but not the layer bookkeeping:
        ;; it reports on the worktree rather than on a range, so it is no layer and
        ;; belongs in neither :reviews nor the toc. Its findings are ordinary
        ;; findings from here on — handled, ruled on, owned and fixed like any other.
        conform  (when project
                   {:target   {:label "design"}
                    :findings (conformance/findings project cwd)})
        findings (-> (collect-findings (cond-> (vec results)
                                         (seq (:findings conform)) (conj conform)))
                     (cite-invariants (:design ctx)))
        rounds   (conj (mapv :findings (:history ctx)) findings)
        ;; What the last run left owed that this round could place on no layer
        ;; and no reviewer has raised since, as the run's own `standing`: it is
        ;; handed to nobody and still counted open, and a warden cannot list it
        ;; for being shown none of it. See `placed-on`.
        unplaced (mapv #(unplaced-item % "left owed by the last review of this workstream")
                       (unanswered-of unplaced-rows rounds))]
    (if (empty? findings)
      ;; Two different terminal rounds arrive here, and only one of them is a
      ;; review that found nothing.
      ;;
      ;; EVERY reviewed target came back with a blank manifest: no reviewer read
      ;; anything, so there is no clean bill to record. It is terminal all the
      ;; same — there is nothing to fix and nothing a further round would change
      ;; — but it is reported under its own status so the report row, the ledger
      ;; entry and the analysis gate all inherit the distinction rather than each
      ;; re-deriving it from an empty finding list. Nothing is cached either:
      ;; convergence is a memory of content having been reviewed, and an empty
      ;; patch has no content to remember.
      ;;
      ;; Otherwise something was genuinely reviewed and reported nothing, so
      ;; nothing is owed anywhere, and on the second such reading every target
      ;; reviewed at this patch has converged. Recorded here when this branch is
      ;; terminal: the engine stops on :control :stop, so the warden — which is
      ;; where convergence is otherwise written — never runs for a round that
      ;; ends clean. A first quiet round does not end here, and the warden
      ;; records it under the withholding this branch puts on the ctx.
      (let [nothing? (and (seq results)
                          (every? #(= :nothing-to-review (:status %)) results))
            ;; ONE pass over a range is a sample rather than a verdict: the
            ;; round that missed a change's only P1 reported one of three
            ;; pre-existing defects and called it clean. So `clean` is earned by
            ;; producing nothing TWICE over the same content, and the two are
            ;; independent readings — a first quiet round records no
            ;; convergence, so the second re-reads every target the first one
            ;; read. The carry holds what the first one read; `quiet-again?`
            ;; says whether this round read the same.
            ;;
            ;; A LAYERED stack is no exception. Each layer's code is read by
            ;; exactly one layer reviewer, and the only other pass over that
            ;; range is the composition reviewer — asked whether the cut holds
            ;; and told not to report what the layer reviews already hold (see
            ;; `round-correctness` and `nido.review.prompts`). There is no
            ;; cross-check between layers to stand in for the second round.
            first-quiet-round? (and (not nothing?)
                                    (not (quiet-again? (get-in ctx [:carry :quiet-once])
                                                       all)))
            ;; The last run left something owed, this run's reviewers were
            ;; handed it, and nobody has said a word about it. A quiet round is
            ;; evidence about what the reviewers read; it is not evidence that a
            ;; defect somebody already ruled `:fix` has gone. `clean` published
            ;; over one is the whole of the miss this read exists to close —
            ;; the run says nothing is owed, and the entry it writes is what the
            ;; run after reads. Terminal either way: a third pass over the same
            ;; code by the same reviewers would produce the same silence, so
            ;; what changes is the answer, not the effort.
            unanswered (unanswered-of (get-in ctx [:carry :inherited-open]) rounds)
            ctx'     (cond-> (assoc ctx :findings [] :reviews results :skipped skipped
                                    :reviewed-at at :patch-hashes (content-hashes all)
                                    :toc toc :unplaced unplaced
                                    :control (if first-quiet-round? :continue :stop)
                                    :status (cond
                                              nothing?           :nothing-to-review
                                              first-quiet-round? nil
                                              (seq unanswered)   :unresolved
                                              :else              :clean))
                       first-quiet-round?
                       (-> (assoc-in [:carry :quiet-once] (content-hashes all))
                           ;; On the ctx rather than the carry: it governs THIS
                           ;; round's record, which the warden writes after this
                           ;; stage has returned. See `record-statuses!`.
                           (assoc :withhold-convergence? true))
                       ;; The round's correctness verdict, on the one branch
                       ;; that used to drop it. A terminal clean round is the
                       ;; round whose verdict is most worth keeping — it is the
                       ;; only evidence that anyone looked — and it was the only
                       ;; round the report had none for. Absent for a round that
                       ;; read nothing, because no reviewer reached a verdict.
                       (not nothing?)
                       (assoc :overall-correctness (round-correctness results)))]
        (when-not (or nothing? first-quiet-round?) (record-review! cwd ctx'))
        ctx')
      (assoc ctx
             :findings findings
             :reviews results
             :skipped skipped
             :reviewed-at at
             ;; What the branch held when these findings were read. The next
             ;; round compares its own against it to tell a stall from a class
             ;; still being narrowed; see `round-changed?`.
             :patch-hashes (content-hashes all)
             ;; A reading that found something ends any pair of quiet ones, so
             ;; the carried reading is only ever the round before's. Matching
             ;; content alone would let a repair that is later reverted put a
             ;; quiet reading from before it beside one from after.
             :carry (dissoc (:carry ctx) :quiet-once)
             :cache cached
             :toc toc
             :unplaced unplaced
             :overall-correctness (round-correctness results)
             :base-rev (:base-rev whole)
             :manifest (:manifest whole)))))

(defn- run-review-stage
  "Ask whether the stack can be reviewed at all, then review it.

   A stack holding conflict markers is not reviewable: the markers are in
   committed text, so a reviewer reads a namespace that does not parse and a
   fixer spends its round undoing them. That was reached the long way round —
   four consecutive runs on one branch fanned out six agents, ruled on what they
   found, and aborted at the first landing when jj refused it. The answer was
   one revset call away the whole time, and asking it first turns twenty minutes
   into a second."
  [ctx]
  (let [{:keys [cwd base]} (:config ctx)
        conflicted (stack-conflicts cwd base)]
    (announce-conflicts! ctx conflicted)
    (if (seq conflicted)
      (assoc ctx :control :stop :status :stack-conflicted :conflicted conflicted)
      (fan-out-reviews ctx))))

(def review-stage
  "Every layer and the whole stack, reviewed in one round.

   Reviews are read-only and independent — that independence is exactly what a
   layer's `Out of scope` buys — so they fan out in parallel. Nothing here
   touches the working copy, and file content is read at each target's own
   revision, so concurrent reviews cannot see each other's state."
  {:name :review
   :run  run-review-stage})

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe :map]]}
  discover-design-record
  "This workstream's latest :design record, or nil.

   Replaces a glob for the newest `docs/superpowers/specs/*-design.md`, which
   picked a file by filename order — in a project with a specs directory that is
   almost never the design of the change under review. The yardstick has to be the
   design *this* change committed to, and the ledger is where that lives."
  [cwd]
  (when-let [[project ws-id] (project+ws-from-cwd cwd)]
    (ws/latest-entry project ws-id :design)))

(defn ^{:malli/schema [:=> [:cat :Path :map] [:maybe :map]]}
  discover-baseline
  "The baseline `design` was judged against — the entry it CITES, not the newest
   one. A workstream may baseline more than once, and the design committed to a
   particular reading; handing the judge a later baseline would have it check the
   change against a yardstick the author never saw.

   nil for a pre-baseline design record, which is correct rather than degraded:
   there was no baseline, and the judge is told so instead of being handed
   something invented in its place."
  [cwd design]
  (when-let [n (get-in design [:baseline :seq])]
    (when-let [[project ws-id] (project+ws-from-cwd cwd)]
      (let [e (ws/entry-at-seq project ws-id n)]
        (when (= :baseline (:format e)) e)))))

(defn ^{:malli/schema [:=> [:cat :Path :map] [:maybe :map]]}
  discover-prior-verdict
  "The verdict this workstream last recorded against the SAME design record, or
   nil.

   Matched on :design-seq rather than taken as the newest, for the same reason
   `discover-baseline` follows a citation instead of reading the latest
   baseline: a verdict against a superseded record answered a different
   question, and offering it as a standing answer would have the pass defend a
   yardstick nobody is using. A design record carrying no :seq matches nothing —
   there is no record such a verdict could be about.

   Only the latest is offered. The ones before it are the same answer at an
   earlier round, and a judge handed all of them is reading a changelog when the
   question is what stands now."
  [cwd design]
  (when-let [n (:seq design)]
    (when-let [[project ws-id] (project+ws-from-cwd cwd)]
      (let [v (ws/latest-entry project ws-id :design-verdict)]
        (when (= n (:design-seq v)) v)))))

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe :map]]}
  standing-needs
  "What the last verdict against this workstream's design record left
   outstanding, as `{:round :verdict :needs}` — or nil.

   The verdict pass names concrete, located defects, and it runs after the loop
   has already returned, so no reviewer, warden or fixer in the run that
   produced one can act on it. It reaches the ledger and the report and stops.
   This is the way back in: what one run's judgment left open becomes what the
   next run's reviewers are asked about, where a finding is the one currency a
   fixer can be handed. Without it a verdict can name the same located defect
   run after run and nothing in the loop is ever able to hear it.

   :needs alone, out of everything a verdict carries. :invariants-broken and
   :load-bearing-broken each name the finding that broke them, so they were
   raised by construction; :needs is the field for what nobody raised.

   Only from a verdict that leaves the design STANDING. :invalidated and
   :standing-challenged put their :needs to a person — that is what makes them
   decisions — and `tasks.nido-review/parked-blocker` already carries one to the
   gate. Seeding it here as well would have a reviewer raise, and a fixer patch,
   the very question a human was asked to answer."
  [cwd]
  (when-let [design (discover-design-record cwd)]
    (when-let [v (discover-prior-verdict cwd design)]
      (when (and (not (report/verdict-invalidates (:verdict v)))
                 (not (str/blank? (str (:needs v)))))
        {:round (:round v) :verdict (:verdict v) :needs (:needs v)}))))

(defn ^{:malli/schema [:=> [:cat :Path] :any]}
  prior-open
  "What the last review of this workstream left OWED, as ledger rows carrying
   the layer each is owed of. Empty when the workstream has no `:review` entry,
   when the last run finished owing nothing, or when cwd maps to no workstream.

   THE ONE READ three things in this run depend on. Every run writes this list
   and nothing has ever read it, so an obligation the loop itself recorded
   reached the next run through no channel at all: a finding ruled `:fix` and
   never repaired was invisible to the reviewer of its own file, the layer took
   an irrevocable `:converged` mark over it, and the design verdict was carried
   forward as though the run had produced no evidence. The three are one defect
   — see `with-prior-open`, `deny-inherited-convergence` and
   `nido.review.verdict/still-answers?` for what each does with it.

   The LAST entry only, and rows it marks `:inherited` are dropped. That bounds
   the carry to a single hop, which is what keeps this from becoming a trap: a
   defect whose owning layer was handed to a reviewer and still went unreported
   is not evidence enough to hold a branch open for ever, and a stale one would
   otherwise block every future run with no way out but a human. One run of
   extra attention is the same price `nido.review.cache` pays everywhere else
   for leaning toward over-invalidating.

   Rows are returned as the ledger holds them — the writer already trimmed them
   to what a reader outside the run needs — so the two consumers that put them
   back into a ledger entry can do it without a translation."
  [cwd]
  (when-let [[project ws-id] (project+ws-from-cwd cwd)]
    (into []
          (remove :inherited)
          (:open (ws/latest-entry project ws-id :review)))))

(def ^:private stance-char-cap 12000)

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  stance-path
  "Where `project`'s stance text lives: its own file if it has one, otherwise the
   common `default.md`.

   Falling back rather than requiring a file per project is what makes the
   yardstick reachable at all. The relation-honest derivation is made against the
   stance, and a project without one made it :underivable — a verdict naming a
   missing document, which no amender can repair and which therefore escalated to
   a human every round. A default answers it instead.

   The override still wins, and that is the point of keeping one: a project with
   its own file has DECLARED that it diverges, where a project with none is
   declaring that the common stance governs it. Both are now statements; before,
   the second was silence."
  [project]
  (let [dir (fs/path (core/nido-source-dir) ".claude" "skills" "design" "stances")
        own (fs/path dir (str (name project) ".md"))]
    (if (fs/exists? own) own (fs/path dir "default.md"))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:maybe :string]]}
  read-stance
  "The project's stance text, from nido's own tree, capped so it can't blow up the
   warden prompt. Read from the source dir rather than cwd: the review runs in the
   worktree, and the stance ships with the /design skill in nido's `.claude`, which
   the worktree does not carry. Missing or unreadable degrades to nil — a headless
   review must not die for want of framing."
  [project]
  (when project
    (try
      (let [f (stance-path project)]
        (when (fs/exists? f)
          (let [s (slurp (str f))]
            (if (> (count s) stance-char-cap)
              (str (subs s 0 stance-char-cap) "\n\n…[stance truncated]")
              s))))
      (catch Throwable _ nil))))

(def ^:private halting-kinds
  "The composition kinds a standing park may still stop a run for: the ones whose
   defect is in what LANDS. Read off the taxonomy, so this and the routing
   cannot disagree about which they are."
  (into #{} (comp (filter #(= :merged-tree (:costs %)))
                  (map (comp keyword :kind)))
        prompts/composition-kinds))

(defn- park-blocks?
  "Whether a standing park is something the branch is really waiting on.

   A park with no kind is an ordinary finding the warden put to a human — it
   contradicts a named invariant, or it is a defect two fixes did not settle —
   and it blocks, as it always has. A park on a composition finding blocks only
   when the defect reaches the merged tree.

   The rest ask about a boundary that will not exist: the stack is collapsed into
   one commit before it lands, so the question is about this review's packaging
   and about nothing downstream. Two things read this, and both had to move for
   either to help. `park-persists-for` ends the run outright — 42 of the corpus's
   46 parks were layering findings, and a park standing four rounds stopped the
   whole run `:unfixable` while other findings were still fixable. And
   `converged-targets` counts every standing park as OWED, so a park that stopped
   halting and kept blocking convergence would only trade a stop for a run to
   `max-iters`, which is the same time lost under a worse name.

   It stops blocking; it does not stop existing. The park is still carried, still
   shown back to the next warden instead of being re-adjudicated, and still in
   the run's remainder for a human to read."
  [p]
  (let [k (:kind p)]
    (boolean (or (nil? k) (contains? halting-kinds (keyword k))))))

(defn- owed-by
  "What a round leaves OWED: every finding it did not settle, every duplicate of
   one that is still owed, and every park still blocking.

   One derivation because `converged-targets` and `reopened-patches` are the same
   rule read from opposite ends — a target the round read converges when nothing
   here names it, and a target the round SKIPPED is reopened when something does.
   Two spellings of it would be two rules, and the pair would disagree exactly
   when a defect crosses from a layer under review to one that is not, which is
   the case both exist for.

   A `duplicate` close settles the finding and not the defect. The defect is the
   finding `:duplicate-of` names, and the layer that reported the copy saw it
   too, so the copy is owed for exactly as long as its target is — followed down
   a chain of copies to the one that is not a copy. Settled on its own, a copy of
   a recut the reshape went on to refuse converged its layer, while the only
   thing still holding the defect was a park that names no layer.

   A target this round does not hold, or a chain that comes back on itself, is
   owed: nothing here shows the defect settled. Holding a layer one round too
   long costs a review; converging it wrongly writes the finding out of a store
   that only grows."
  [findings parks]
  (let [by-id (into {} (map (juxt :id identity)) findings)
        owed? (fn owed? [f seen]
                (cond
                  (not (settled? f))       true
                  (nil? (:duplicate-of f)) false
                  :else
                  (let [t (get by-id (:duplicate-of f))]
                    (or (nil? t)
                        (contains? seen (:id t))
                        (owed? t (conj seen (:id f)))))))]
    (concat (filter #(owed? % #{}) findings) (filter park-blocks? parks))))

(defn ^{:malli/schema [:=> [:cat :any :any :any] :any]}
  converged-targets
  "Pure: the targets this round left with nothing OWED, paired with the patch
   they were reviewed at.

   Owed, not unfixed. A target converges when every finding naming it was
   SETTLED — decided, by a disposition that ends it, and a duplicate only once
   what it repeats is — not merely when none of them was handed to a fixer.
   Those two differ for every disposition that is neither, and the difference is
   not academic: a finding the loop has no move for is still open, and marking
   its target converged writes that patch into a store
   that only grows, so a later run at the same content skips the target and the
   finding is gone. `nido.review.cache` leans toward over-invalidating for
   exactly this reason — over-invalidating costs one review, and reading
   `not :fix` as `nothing owed` cost a P1 that no re-run would have shown again.

   Owed by this round's open findings AND by the parks the run is carrying. A
   park is by construction never raised twice — that is what a park IS — so from
   the round after the one that raised it the findings do not mention it, and
   the carry is the only thing that knows it is standing. Read the findings
   alone and that silence becomes the answer: the layer converges on whatever
   the round's fresh reviewer happens not to say, into the same store that only
   grows. `carried-parks` holds the parks between rounds, and each names its
   layer, so a park holds exactly the target an open finding naming that layer
   would.

   A finding an upper layer reported but a lower one owns leaves the upper layer
   converged, correctly: nothing about it needs changing. Something open that
   names no layer — a finding the warden gave no owner, a park raised for the
   shape of the stack rather than for a line in it — blocks none of them, but it
   is still open, so the whole-stack target holds it, and that target converging
   on `nothing anywhere is open` is what stops it being lost.

   A landed fix invalidates the layer it lands on and the composition, and
   nothing else. A layer ABOVE it keeps its key: a fix underneath moves what
   that layer sits on rather than what it contributes, and its reviewer would be
   handed the same diff again. What the fix can still have broken is how the
   pieces fit — which is the composition target's question, and its key spans
   the whole range, so the fix moves it. Re-cutting a stack is the opposite
   case: it moves code between layers without changing `base-rev..@` by a byte,
   which is why that key folds in the cut as well; see `with-patch-hashes`."
  [reviews findings parks]
  (let [owed   (owed-by findings parks)
        owners (into #{} (map :owner-layer) owed)]
    (into []
          (comp (map :target)
                (filter (fn [t]
                          (and (:patch-hash t)
                               (if (:stack? t)
                                 (empty? owed)
                                 (not (contains? owners (:label t))))))))
          reviews)))

(defn ^{:malli/schema [:=> [:cat :any :any :any] :any]}
  reopened-patches
  "Pure: the patches of targets this round SKIPPED that something owed still
   names — the ones whose recorded convergence this round has falsified.

   `converged-targets` over the complement, and it is the half that was missing.
   Only a target a reviewer READ ever rewrites its cache entry, so a defect
   attributed to a layer whose patch the cache already held converged was owed by
   a layer nothing would look at again: the warden could place it and the
   placement changed nothing. One run named an unrepaired credential leak at a
   converged layer's file in a fixer's account, quoted it in the next round's
   warden reason and carried it as a follow-up of the design verdict, while that
   layer sat skipped at an unmoved patch hash for all three rounds and the run
   ended clean with nothing open.

   That layer is where a promotion most often points, and it is not a
   coincidence: a sibling survives a sweep precisely where no reviewer has been.

   The skipped composition target reopens on ANYTHING owed, for the reason it
   converges only on nothing being owed — its question is whether the pieces fit,
   and a defect anywhere is a piece that has moved."
  [skipped findings parks]
  (let [owed   (owed-by findings parks)
        owners (into #{} (map :owner-layer) owed)]
    (into []
          (comp (filter :patch-hash)
                (filter (fn [t]
                          (if (:stack? t)
                            (boolean (seq owed))
                            (contains? owners (:label t)))))
                (map :patch-hash))
          skipped)))

(defn ^{:malli/schema [:=> [:cat :any :any :any] :any]}
  reviewed-statuses
  "Every target this round reviewed, paired with the status its patch is left
   at: `:converged` when the target owes nothing — `converged-targets` is that
   rule — and `:partial` when it still does.

   Only `:converged` grants a skip, so a `:partial` entry is re-reviewed, which
   is correct: something is owed of it. What the entry buys is the other half of
   what the cache holds. Answers were recorded only against converged patches,
   and a converged patch is by definition one the next round skips — so the
   answers sat in a store nothing would ever come back to, and the target that
   HAS a question outstanding, the one a fresh reviewer will report on again,
   was the one nothing was written about at all.

   A target with no patch hash is dropped rather than recorded under nil: an
   entry keyed on unknown content is a claim about every patch and about none."
  [reviews findings parks]
  (let [converged (into #{} (map :label) (converged-targets reviews findings parks))]
    (into []
          (comp (map :target)
                (filter :patch-hash)
                (map (fn [t]
                       [t (if (contains? converged (:label t)) :converged :partial)])))
          reviews)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  salvaged-statuses
  "Every target of an ABORTED round paired with the status its patch is left at
   — the partial-round counterpart of `reviewed-statuses`.

   A round that loses a reviewer never reaches a warden, so no finding carries a
   disposition or an owner and the rule `converged-targets` applies cannot be
   asked here. What CAN be asked is what each reviewer that returned said about
   its own patch, and a reviewer that read a manifest and reported nothing has
   issued a clean bill on that exact content — a bill a sibling's quota does not
   retract.

   The composition target never converges here, whatever it reported. It
   converges on `nothing anywhere is open`, and a round holding a target nobody
   read cannot know that.

   Under-recording is the safe direction and this leans it: a `:partial` entry
   is simply reviewed again, and the worst a missing entry costs is the review
   the abort had already paid for. A target that read nothing is dropped rather
   than recorded, because convergence is a memory of content having been
   reviewed and an empty patch has no content to remember; so is one with no
   patch hash, because an entry keyed on unknown content is a claim about every
   patch and about none."
  [results]
  (into []
        (comp (filter #(:patch-hash (:target %)))
              (remove #(= :nothing-to-review (:status %)))
              (map (fn [{:keys [target findings]}]
                     [target (if (and (not (:stack? target)) (empty? findings))
                               :converged
                               :partial)])))
        results))

(defn- latest-rulings
  "One entry per finding across every round of the run, carrying its LATEST
   ruling — the same fold `verdict/final-rulings` performs, and for the same
   reason: a run's decisions are spread over its rounds and no single round
   holds them all.

   Keyed on the handle, which is the identity a re-wording cannot move, falling
   back to the id for a finding that reached no warden. A later round that
   reverses a ruling wins, so a finding closed in round 1 and re-opened in
   round 3 is open.

   Ordered by FIRST raising, because the reader is a prompt block: a list whose
   order is the map's is stable for a short run and arbitrary for a long one,
   and one that reads oldest-first tells a warden how the run went."
  [rounds]
  (let [all    (apply concat rounds)
        latest (reduce (fn [acc f] (assoc acc (or (:handle f) (:id f)) f)) {} all)]
    (into []
          (comp (map #(or (:handle %) (:id %))) (distinct) (map latest))
          all)))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  answered-for
  "What this target reported and the warden SETTLED, over the WHOLE run —
   `rounds` is each round's findings, oldest first. Two readers, and the same
   answer serves both: `answered-by-layer` shows it to the next round's warden,
   and `record-statuses!` writes it into the workstream cache for the next RUN.

   Every round rather than the converging one, because the converging round is
   the one least likely to hold anything: a run ends by finding nothing, and
   reading its last round alone recorded `:answered` of length zero against
   every target it had just spent three rounds adjudicating. The same silence
   costs a round mid-run — a target's entry is rewritten at its own unchanged
   hash every round it is reviewed, so a reviewer that happens not to re-report
   a settled finding would erase the answer the round before had recorded.

   Every settling disposition, not only a close. A decline is a decision — the
   finding is true and this branch is leaving it — and a decision re-argued
   every round is not one: the reviewer has no memory, so without this the same
   defect is declined again and again at full cost, and the reason given the
   first time is never seen by the round that needs it. The disposition rides
   along so the next warden can tell what kind of answer it is looking at."
  [label rounds]
  (into []
        (comp (filter #(and (= label (:from-layer %)) (settled? %)))
              (map #(select-keys % [:id :title :disposition :authority :because])))
        (latest-rulings rounds)))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  unanswered-of
  "The rows of `inherited` this run has said nothing about — pure over the
   inheritance and the run's rounds, oldest first.

   Answered means RULED, not repaired. A run that raised the finding again owns
   it from that point: its own accounting decides whether it is open, kept or
   settled, and carrying the inherited copy beside it would count one defect
   twice. What is left is the case the read exists for — a defect the last run
   ruled on, that this run's reviewers were told about and did not report.

   Joined on the finding id, which `codex/finding-id` derives from file, line
   and title, so one defect at one site carries the same id whichever run raised
   it. `merge-answered` joins the settled half of the same history the same way."
  [inherited rounds]
  (let [ruled (into #{} (keep :id) (latest-rulings rounds))]
    (into [] (remove #(contains? ruled (:id %))) inherited)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  unanswered-inherited
  "What the last run left owed that this whole run neither raised nor answered,
   off a terminal ctx. See `prior-open` for the read and `unanswered-of` for the
   fold.

   Three readers, one question. The round that ends quiet may not call itself
   `clean` while it holds one of these; `tasks.nido-review/review-event` puts
   them in the entry it writes, so the count and the list a human reads are
   about the branch rather than about the run; and
   `nido.review.verdict/still-answers?` will not carry a standing verdict over
   one, because a verdict republished as though nothing were owed is how a
   `:needs` naming an unrepaired defect gets restated as a thing to do."
  [final]
  (unanswered-of (get-in final [:carry :inherited-open])
                 (conj (mapv :findings (:history final)) (vec (:findings final)))))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  deny-inherited-convergence
  "Pure: the same `[target status]` pairs with `:converged` downgraded to
   `:partial` on every target an unanswered inherited finding names.

   `:converged` is the one mark that grants a skip, and `nido.review.cache`
   states it is not granted by an agent and cannot be revoked by one — so a
   layer that takes it while a known defect stands in it is exempt from the code
   lane until somebody happens to edit the file. That is what happened: a
   finding ruled `:fix` and never repaired sat in a layer the next run marked
   converged at an unchanged hash, and nothing short of a human touching the
   file would ever have re-opened it.

   A downgrade, not a drop. The entry is still written and still carries what
   the run settled about the target — the answers are worth recording whatever
   the status, and `:partial` is exactly the entry a next run comes back to."
  [statuses unanswered]
  (let [owed (into #{} (keep :layer) unanswered)]
    (if (empty? owed)
      statuses
      (mapv (fn [[t status :as pair]]
              (if (and (= :converged status) (contains? owed (:label t)))
                [t :partial]
                pair))
            statuses))))

(defn- merge-answered
  "One row per finding, out of the two sources of answers about a target.

   Deduped on the finding id, which `codex/finding-id` derives from the file,
   the line and the title — so one defect at one site carries the same id
   whichever run raised it, and the two sources can be joined on it. Where they
   collide `in-run` wins, being the later decision.

   Ordered `stored` first, so the row a reader meets first is the oldest."
  [stored in-run]
  (let [by-id (into {} (map (juxt :id identity)) in-run)
        seen  (into #{} (map :id) stored)]
    (into (mapv #(get by-id (:id %) %) stored)
          (remove (comp seen :id))
          in-run)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  answered-by-layer
  "What has already been SETTLED about each layer under review, per layer. Fed
   to the warden so a fresh reviewer reporting a decided finding gets answered
   rather than re-adjudicated — the reviewer starts blank every round, so
   without this a decline is re-argued at full cost for as long as the defect is
   visible.

   Two sources, because a layer has two kinds of history and only one of them
   survives a fix landing on it.

   THIS RUN's rulings arrive by LABEL, out of the round history. The label is
   the only identity that holds across a repair: the moment a fix lands, the
   layer is different content and everything hung off its patch hash is about a
   patch that no longer exists. On the hash alone this block was inert for
   precisely the layers a round had worked on — one run wrote a layer's declined
   deviation three times, under three successive hashes, and read it back none
   of the times.

   EARLIER RUNS' rulings arrive by patch hash, out of the workstream cache,
   where the hash is the right key: those answers were about the content that
   run read, and a layer that has since moved has no answer from it. This is the
   source that carries a decline across the gap between two runs on a branch
   nothing landed on.

   They cover each other exactly, which is why neither alone was enough. Only a
   round that LANDED a fix is in `:history` at all — and a round that lands
   nothing leaves the patch where it was, so what the label cannot reach the
   hash still finds.

   The cache is asked about the targets UNDER REVIEW, which is what makes what
   is written there matter as much as what is read. While convergence was the
   only thing recorded, its entries were exactly the patches `to-review` skips
   and this looked up their complement; a target that owes something is recorded
   too, and that is the entry a next run comes back to.

   Layers with nothing answered are dropped rather than carried as empty rows:
   the prompt block is evidence, and a layer named with nothing under it reads
   as a layer that was asked and had no answer."
  [ctx]
  (let [rounds (mapv :findings (:history ctx))]
    (into []
          (keep (fn [{:keys [target]}]
                  (let [a (merge-answered
                           (cache/answered (:cache ctx) (:patch-hash target))
                           (answered-for (:label target) rounds))]
                    (when (seq a)
                      {:label (:label target) :answered (vec a)}))))
          (:reviews ctx))))

(defn ^{:malli/schema [:=> [:cat :Path :map] :any]}
  record-review!
  "Write what this round's review left each target at into the workstream's
   cache: its status, and what the run has settled about it.

   Called from the two stages that can end a round, rather than from a stage of
   its own: the engine short-circuits on `:control :stop`, so anything sequenced
   after the stage that stopped would never run. Those two are the warden, which
   stops once every finding is settled, and the review stage, which stops before
   a warden exists when the round reported nothing on a second reading. The
   second is the one most worth recording and was the one missing — a round that
   finds nothing is the loop's best outcome, and it was the only outcome it
   forgot, so re-reviewing an untouched patch cost a full fan-out every time.

   Whichever of them calls it, a round the review stage ruled a first quiet
   reading grants no convergence: it is the warden that records that round, and
   `record-statuses!` honours the ruling off the ctx.

   The SKIPPED targets are written about too, and only when the round has
   something to say about them: `reopened-patches` revokes the convergence of any
   the round left owing work. Without that half, what the cache records of a
   round is only ever about the targets a reviewer happened to read — so a defect
   attributed to a layer nobody read was owed by a patch nothing would look at
   again.

   Safe to call from either stage because it reads only `:reviews`, `:skipped`,
   `:findings`, the round history, the carry and `:withhold-convergence?`, all
   of which are set by then, and because `reviewed-statuses` and
   `reopened-patches` are pure.

   Best-effort — a cache that cannot be written costs the next run some
   duplicated review and nothing else."
  [cwd ctx]
  (let [parks (vals (get-in ctx [:carry :parks] {}))]
    (record-statuses! cwd ctx
                      (reviewed-statuses (:reviews ctx) (:findings ctx) parks)
                      (reopened-patches (:skipped ctx) (:findings ctx) parks))))

(defn- record-statuses!
  "Write one cache entry per `[target status]` pair, each carrying what the run
   has settled about that target.

   Split out because a round that ABORTS mid-fan-out derives its statuses by a
   different rule — `salvaged-statuses` rather than `reviewed-statuses`, since
   no warden ran to rule on anything — while WHAT is written about each target,
   and where, is the same either way.

   The history plus this round's findings is the run's whole account, and it is
   assembled here rather than in `answered-for` so that function stays pure over
   what it is given. The fix stage appends a round to the history and the warden
   runs before it, so the two never overlap.

   The inherited denial lands here rather than in `reviewed-statuses` because
   both derivations pass through it — a salvaged round grants `:converged` on a
   reviewer's clean bill alone, which is exactly the bill an unanswered
   inheritance says is not the whole story.

   So does `:withhold-convergence?`, the review stage's ruling that this round is
   a first quiet reading: one sample, and not yet the pair `clean` is earned by.
   That round continues to the warden, which is the stage that records it and
   knows nothing of the ruling but what the ctx carries — so it is honoured
   where every write passes, whichever stage made it. Every `:converged` becomes
   `:partial`, as the inherited denial does it: the entry and its answers are
   still written, and it is simply read again. The reopens are untouched —
   revoking a convergence is never what a sample is withheld from.

   `reopen` is the patches of targets the round did NOT read and has nonetheless
   learnt something about: their content has not moved, so there is no entry to
   write from, and what wants correcting is the status alone. A salvaged round
   passes none — no warden ruled on anything, so nothing is attributed to a layer
   at all."
  [cwd ctx statuses reopen]
  (when-let [[project ws-id] (project+ws-from-cwd cwd)]
    (let [rounds   (conj (mapv :findings (:history ctx)) (vec (:findings ctx)))
          statuses (cond->> (deny-inherited-convergence
                             statuses
                             (unanswered-of (get-in ctx [:carry :inherited-open]) rounds))
                     (:withhold-convergence? ctx)
                     (mapv (fn [[t status]]
                             [t (if (= :converged status) :partial status)])))]
      (when (or (seq statuses) (seq reopen))
        (let [now (str (java.time.Instant/now))
              c   (reduce (fn [c [t status]]
                            (cache/record c (:patch-hash t)
                                          {:status   status
                                           :label    (:label t)
                                           :round    (:iter ctx)
                                           :at       now
                                           :answered (answered-for (:label t) rounds)}))
                          (or (:cache ctx) (cache/read-cache project ws-id))
                          statuses)
              c   (reduce (fn [c h] (cache/reopen c h now)) c reopen)]
          (cache/write! project ws-id c))))))

(defn ^{:malli/schema [:=> [:cat :any :Finding] :any]}
  resolve-handle
  "The identity a finding is filed under: the handle of the finding the warden
   says it restates, or its own id when it restates nothing.

   `handles` maps a finding id to the handle it was filed under, and every entry
   in it is already resolved — so one lookup is enough, and a chain of
   restatements collapses onto the FIRST raising rather than onto its immediate
   predecessor. That is what makes a handle stable across a run rather than
   merely between two rounds.

   A `same_as` naming an id this run never issued resolves to nothing and the
   finding keeps its own. Strict on purpose, and in the cheap direction: a link
   missed costs one round of not recognising a repeat, while a link invented
   welds two defects into one and the second is never fixed."
  [handles {:keys [id same-as]}]
  (or (get handles same-as) id))

(def ^:private packaging-kinds
  "The composition kinds whose defect the collapse erases and whose only remedy
   is rearranging layers — read off the taxonomy the reviewer was taught, so the
   prompt and this enforcement cannot disagree about which they are."
  (into #{} (comp (filter #(= :packaging (:costs %)))
                  (map (comp keyword :kind)))
        prompts/composition-kinds))

(def ^:private advisory-dispositions
  "What a packaging finding may be ruled. `declined` settles it, keeps it, and
   carries the warden's own sentence to the human; the other settling
   dispositions are still legal because a packaging finding can genuinely be a
   duplicate or be answered by the design."
  #{:declined :closed :deviation})

(defn- advisory-ruling
  "Coerce a packaging finding away from any disposition that spends a round on
   it — `fix` hands it to a fixer, `recut` to the reshape stage, `park` stops the
   run for it — and leave every other ruling alone.

   Enforced here rather than trusted to the prompt for the reason the whole
   change exists: composition findings were ruled `fix` 26 times in 133 while
   ordinary findings were ruled `fix` 388 in 424, and the two packaging kinds
   accounted for 2 fixes in 49 against 42 of the run corpus's 46 parks. A rule
   this consequential that only lives in prose is a fourth soft bar.

   The warden's `because` is kept whatever it said, so the human still reads why
   the reviewer thought the cut was wrong. What changes is only where it goes."
  [f]
  (if (and (contains? packaging-kinds (:kind f))
           (not (contains? advisory-dispositions (:disposition f))))
    (assoc f :disposition :declined
           :advisory-of (:disposition f)
           :because (str (or (:because f) "the reviewer reported a defect in the cut")
                         " — advisory: the layers are collapsed before this lands,"
                         " so rearranging them now buys nothing that survives it."))
    f))

(defn ^{:malli/schema [:=> [:cat :any :any :any] :any]}
  apply-rulings
  "Merge the warden's per-finding rulings onto the findings, and file each under
   the identity the warden gave it.

   A finding the warden did not rule on defaults to :fix. That is the fail-safe
   direction and it is what keeps \"nothing is dropped\" true of a malformed
   answer: an omitted finding is worked on, never silently discarded. It keeps
   its own id as its handle for the same reason — an unrecognised repeat costs a
   round, an invented one loses a defect."
  [findings rulings handles]
  (let [by-id (into {} (map (juxt :id identity)) rulings)]
    (mapv (fn [f]
            (let [r (get by-id (:id f))
                  merged (merge f
                                {:same-as     (:same-as r)
                                 :owner-layer (:owner-layer r)
                                 :disposition (or (:disposition r) :fix)
                                 :authority   (:authority r)
                                 :of          (:of r)
                                 :duplicate-of (:duplicate-of r)
                                 :sweep       (boolean (:sweep r))
                                 :because     (or (:because r)
                                                  (when-not r "the warden did not rule on this finding"))})]
              (advisory-ruling
               (assoc merged :handle (resolve-handle handles merged)))))
          findings)))

(def ^:private promoted-by
  "The `:from-layer` a promoted finding carries.

   No layer of the stack, because no layer's reviewer reported it — and that
   matters beyond the rendering: `answered-for` reads `:from-layer` to decide
   what a TARGET reported and settled, and a promotion filed under the layer it
   is aimed at would tell the next round that layer's reviewer had raised and
   answered something it never saw.

   The same shape the mechanical design reviewer already has: a reporter that
   contributes findings and is no layer of the stack."
  "warden")

(defn ^{:malli/schema [:=> [:cat :Path :any :any :any :any] :map]}
  promoted-findings
  "The warden's `promote` entries as ruled findings of this round, under
   `:findings` — and under `:standing`, an entry for each one no layer of the
   stack can take.

   A fixer told to sweep names the siblings its repair could not reach, and the
   warden is the only reader holding the file lists those paths can be placed
   against. Promotion is that placement made into work: the entry arrives here
   already dispositioned `:fix`, so it is handed out this round rather than
   waiting for a fresh reviewer to rediscover it — which is what it waited for
   before, when it converted at all. Of five siblings named across one run, two
   converted and each cost a round; the other three were still standing at the
   end.

   Its layer is the warden's where that is a layer of `toc`, and otherwise the
   one its file places it on — see `placed-on`, which the last run's open rows
   are placed through as well. Taken verbatim, a null or a stale label would be
   an owner nothing matches: the layer the sibling sits in would converge over
   it, and `owned-by` would hand the repair to the top layer's fixer wherever
   the file is. A promotion neither places is not a finding. It goes to
   `standing`, where the warden is told to put a sibling it cannot place: a
   promotion is work for a layer's fixer, and there is no layer to give it. A
   branch with no layers has nothing to place against, and is the one place
   every promotion goes.

   The reporter is the FIXER, and the two refusals here are what hold that line.
   An entry with no `title` or no `file` names no place anything read, and an
   entry whose coordinates are a finding this round already holds is that
   finding — ruling it is what the warden is for, and promoting it as well would
   put one defect in front of two fixers under two ids.

   The id is derived exactly as a reviewer's is, from file, line and title. So a
   sibling promoted from the same account in a later round lands on the same id
   and `resolve-handle` files it under the same handle, which is what lets
   `no-progress?` and the give-up counter see a promotion that keeps coming back
   as one defect rather than as a fresh one each round.

   `:sweep` is false and not offered. A promoted sibling is by construction what
   is left of a class a fixer has already swept, so ordering another sweep of it
   asks for the search that just produced it."
  [cwd toc handles findings promotions]
  (select-keys
   (reduce
    (fn [{:keys [seen] :as acc} p]
      (let [title (str/trim (str (:title p)))
            file  (str/trim (str (:file p)))
            line  (when (number? (:line p)) (long (:line p)))
            f     {:title      title
                   :body       (:body p)
                   :priority   (if (number? (:priority p)) (long (:priority p)) 2)
                   :file       file
                   :line-start line
                   :line-end   line
                   :from-layer promoted-by}
            id    (codex/finding-id f)
            named (:owner_layer p)
            owner (if (seq toc) (placed-on cwd toc named file) named)]
        (cond
          (or (str/blank? title) (str/blank? file) (contains? seen id))
          acc

          (and (seq toc) (nil? owner))
          (-> acc
              (update :seen conj id)
              (update :standing conj
                      (unplaced-item {:title title
                                      :where (str file (when line (str ":" line)))
                                      :layer named}
                                     "promoted by the warden out of a fixer's account")))

          :else
          (let [placed (when (not= owner named)
                         (str "placed on " owner " by its file: the warden named "
                              (if (str/blank? (str named))
                                "no layer"
                                (str named ", which is no layer of this stack"))))
                ruled  (assoc f
                              :id          id
                              :same-as     (:same_as p)
                              :owner-layer owner
                              :disposition :fix
                              :authority   nil
                              :of          nil
                              :sweep       false
                              :because     (if placed
                                             (str/join " — " (remove str/blank? [(:because p) placed]))
                                             (:because p)))]
            (-> acc
                (update :seen conj id)
                (update :findings conj
                        (assoc ruled :handle (resolve-handle handles ruled))))))))
    {:seen (into #{} (map :id) findings) :findings [] :standing []}
    promotions)
   [:findings :standing]))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  seen-findings
  "Every finding an earlier round raised, oldest first, as {:round :id :title}.

   This is the pool the warden's `same_as` points into, and it cannot come from
   the round history beside it in the prompt: that carries what each round
   DECIDED, and deciding whether this defect is that one needs the defect.

   First raising wins on a repeated id, so a finding that survived three rounds
   unchanged appears once, at the round it arrived."
  [history]
  (:out (reduce (fn [acc h]
                  (reduce (fn [{:keys [seen] :as a} f]
                            (if (contains? seen (:id f))
                              a
                              {:seen (conj seen (:id f))
                               :out  (conj (:out a) {:round (:iter h)
                                                     :id    (:id f)
                                                     :title (:title f)})}))
                          acc
                          (:findings h)))
                {:seen #{} :out []}
                history)))

(defn ^{:malli/schema [:=> [:cat :map :map] :map]}
  warden-failure
  "Why there is no ruling, when the round cannot use the answer.

   FOUR DIFFERENT THINGS END A ROUND HERE and they ask different things of
   whoever reads the report: the agent exited on an error it stated, it was
   killed on its budget mid-answer, it ran and produced nothing, or it answered
   and the answer would not parse. Only the last is a claim about JSON.

   Collapsing them onto the parser's verdict is how a session limit came to be
   reported as `no json decision block` — a complaint about a block that was
   never going to exist, with the 429 in agent.log and nowhere a reader looks.
   `result-error?` was already computed and already right; it was the
   explanation that was thrown away.

   A kill is asked about FIRST and asked about at all because it is the one
   ending that leaves no evidence of itself in the launch result: the budget
   timer destroys the process before claude emits its `result` event, so
   `num-turns` is nil exactly as it is for an agent that never started, and
   `:timed-out?` is the only field that tells a warden that spent thirty minutes
   from one that was rejected at the door."
  [{:keys [num-turns result-error? result-text timed-out?]} decision]
  (cond
    timed-out?
    {:cause  :budget-spent
     :reason "the agent was killed on its budget mid-answer — whatever it had settled went with the process"}

    result-error?
    {:cause  :launch-failed
     :reason (or (some-> result-text str/trim not-empty)
                 "the agent exited with an error and said nothing")}

    (zero? (or num-turns 0))
    {:cause  :no-answer
     :reason "the agent ran no turns — nothing was asked of the findings"}

    :else
    {:cause :unusable-answer :reason (:reason decision)}))

(def ^:private park-persists-for
  "How many rounds an unresolved park may survive before the run stops for it.

   Matched to `loop/unfixable-after`, and for the same reason: three rounds is
   long enough that the cut is not going to resolve itself and short enough
   that the human hears about it while the branch is still warm."
  4)

(defn ^{:malli/schema [:=> [:cat :any :any :int] :any]}
  carried-parks
  "The open parks this run is holding, each with the round it was first parked
   in — the previous round's carry, updated with this round's rulings.

   A park is by construction never fixed, so no later round raises it again and
   it vanishes from the findings the moment the reviewer stops mentioning it.
   That is why it needs carrying: the warden re-adjudicated the same cut from
   scratch fifteen times in one run, with its own accumulating prose as the only
   memory, and the run still reported nothing remaining.

   A park that a later round SETTLES drops out. Being parked is not permanent —
   it is a question, and a question can be answered.

   Each entry keeps the layer the warden gave the finding, because a standing
   park is something owed and `converged-targets` has to hold that layer's patch
   out of the cache for as long as it stands. Nil is a real answer to that: a
   park nobody attributed names no layer and holds only the whole-stack target."
  [prior ruled iter]
  (let [settled (into #{} (comp (filter settled?) (map #(or (:handle %) (:id %)))) ruled)
        prior'  (into {} (remove (fn [[k _]] (contains? settled k))) prior)]
    (reduce (fn [acc f]
              (let [k (or (:handle f) (:id f))]
                (if (contains? acc k)
                  acc
                  (assoc acc k {:since iter
                                :owner-layer (:owner-layer f)
                                :kind (:kind f)
                                :title (:title f)
                                :because (:because f)}))))
            prior'
            (filter #(= :park (:disposition %)) ruled))))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  carried-while-open
  "A per-layer carry, with every entry this round SETTLED taken out.

   Two channels are carried this way, and neither is a ruling: a fixer's
   argument for refusing work it was handed, and a repair the stack would not
   take. A fixer decline is not the warden's `:declined` disposition and the two
   are kept apart everywhere they meet — that one is a decision that the defect
   is real and the branch is shipping it, this one is a fixer deciding nothing
   at all — and a rolled-back repair decides even less: an edit was written and
   the rebase refused it.

   Both are ABOUT a finding, so both live exactly as long as that finding is
   open. The round that accepts the argument and closes, declines or deviates
   the finding has answered it, and carrying it past that point tells a later
   reader a decided thing is still owed. Mirrors `carried-parks`, which drops a
   park a later round settles for the same reason.

   A layer whose every finding was settled drops out entirely. A layer holding a
   mix keeps its entry beside the findings still open — the fixer made one case
   about the batch it was handed, and half of it being answered does not make
   the other half unargued."
  [prior ruled]
  (let [settled (into #{} (comp (filter settled?) (map #(or (:handle %) (:id %)))) ruled)]
    (reduce-kv (fn [acc label entry]
                 (let [open (into [] (remove #(contains? settled (:id %)))
                                  (:findings entry))]
                   (if (seq open)
                     (assoc acc label (assoc entry :findings open))
                     acc)))
               {}
               prior)))

(defn ^{:malli/schema [:=> [:cat :any] [:sequential :map]]}
  unstarted-fixers
  "The layers whose LATEST fixer launch never started, one row each, oldest
   first: `:layer`, the `:round`, what it was `:handed`, and the `:exit-code`
   where the process left one. Read off the launch record — see `record-launch`.

   Latest, because a layer some later fixer ran on has been attempted since, and
   the failure before it says nothing about what is open now. What remains is
   the fact nothing else in the run states: findings the loop ruled `fix` and no
   process ever read. The warden is shown it rather than left to infer it from a
   missing commit, and the run's ledger entry carries it, because there the
   status names the machinery and only this names where it failed."
  [launches]
  (->> launches
       (keep (fn [[label rows]]
               (let [r (peek (vec rows))]
                 (when (and r (not (:ran? r)))
                   (-> r (dissoc :ran?) (assoc :layer label))))))
       (sort-by (juxt :round (comp str :layer)))
       vec))

(defn- run-warden-stage
  [ctx]
  (let [{:keys [cwd run-id budget]} (:config ctx)
        handles (get-in ctx [:carry :handles] {})
        ;; Read once and used twice: the block the warden is shown is the list
        ;; its quotes are checked against, so a citation that does not match is
        ;; a restatement rather than a stale read.
        design (discover-design-record cwd)
        prompt (prompts/warden-prompt
                {:findings (:findings ctx)
                 :seen     (seen-findings (:history ctx))
                 ;; Findings are shown separately, and the patch hashes are for
                 ;; the termination check rather than for a reader — a page of
                 ;; sha256 the warden can do nothing with. The accounts come out
                 ;; for the same reason and go back in below, rendered: whole and
                 ;; unlabelled inside this pr-str they were bytes in the prompt
                 ;; that nothing told the warden to read.
                 :history  (mapv #(-> %
                                      (dissoc :findings :patch-hashes)
                                      (cond-> (:fixes %)
                                        (update :fixes
                                                (partial mapv (fn [f] (dissoc f :account))))))
                                 (:history ctx))
                 :design   design
                 :stance   (read-stance (first (project+ws-from-cwd cwd)))
                 :toc      (:toc ctx)
                 :parked   (vals (get-in ctx [:carry :parks] {}))
                 :fixer-declines (vals (get-in ctx [:carry :fixer-declines] {}))
                 :unstarted (unstarted-fixers (get-in ctx [:carry :fixer-launches]))
                 ;; Chronological, and flat with the layer named on each row:
                 ;; the warden is being asked to place an account's contents
                 ;; against a layer OTHER than the one it is filed under, so the
                 ;; label has to be a field it reads rather than the grouping it
                 ;; reads under.
                 :fixer-accounts (sort-by (juxt :round (comp str :layer))
                                          (for [[label rows] (fix-accounts (:history ctx))
                                                row rows]
                                            (assoc row :layer label)))
                 :answered (answered-by-layer ctx)})
        {:keys [num-turns result-error? result-text] :as launch}
        (agent/launch! {:run-id run-id :cwd cwd
                        :first-message prompt :budget budget
                        :tools ""
                        :err-file (str (fs/path (cstate/run-dir run-id) "agent.err.log"))})
        decision (parse-warden-decision result-text design)]
    (if (or (zero? (or num-turns 0)) result-error?
            (= :indeterminate (:decision decision)))
      (assoc ctx :warden (merge decision (warden-failure launch decision))
             :control :stop
             :status :warden-indeterminate)
      (let [{promoted :findings unplaceable :standing}
            (promoted-findings cwd (:toc ctx) handles (:findings ctx) (:promote decision))
            ;; A sibling the warden could not place is one it is told to list
            ;; in `standing`, so one it promoted anyway is listed there for it.
            decision (cond-> decision
                       (seq unplaceable)
                       (update :standing #(into [] (distinct) (concat % unplaceable))))
            ruled (into (apply-rulings (:findings ctx) (:rulings decision) handles)
                        promoted)
            parks (carried-parks (get-in ctx [:carry :parks] {}) ruled (:iter ctx))
            declines (carried-while-open (get-in ctx [:carry :fixer-declines] {}) ruled)
            ;; The same lifetime rule over the other channel a fixer leaves
            ;; behind. A refusal the stack made is spent the moment the finding
            ;; it was aimed at is settled, and holding it past that would tell
            ;; the next reviewer to look for a defect this round just closed.
            refused  (carried-while-open (get-in ctx [:carry :rolled-back] {}) ruled)
            ;; A park is a question put to a human, and a run that keeps fixing
            ;; around one is answering a different question. Once a park has
            ;; stood this long the loop has nothing further to offer it, and
            ;; stopping is the report — even while other findings are still
            ;; fixable, because those fixes are not what the branch is waiting
            ;; on. The warden said exactly this in its own prose and then
            ;; returned :continue, because there was no state between "keep
            ;; fixing" and "escalate".
            stale (seq (for [[k p] parks
                             :when (and (park-blocks? p)
                                        (>= (inc (- (:iter ctx) (:since p)))
                                            park-persists-for))]
                         k))
            ctx' (assoc ctx
                        :warden  decision
                        :findings ruled
                        ;; Kept beside the findings they are now part of,
                        ;; because they are the one thing about the round that
                        ;; the phases either side of this one cannot show: the
                        ;; review phase folded before they existed, and a ruling
                        ;; row carries an id and a disposition but not the title,
                        ;; file and line that say what was raised.
                        :promoted promoted
                        ;; The handles have to reach the next round, and
                        ;; :carry is the only thing here that does —
                        ;; every other key is rebuilt from :config,
                        ;; :iter and :history. Merged rather than
                        ;; replaced, so a finding that stops being
                        ;; reported keeps its filing for a later round
                        ;; that raises it again.
                        :carry (assoc (:carry ctx)
                                      :handles (into handles
                                                     (map (juxt :id :handle))
                                                     ruled)
                                      :parks parks
                                      :fixer-declines declines
                                      :rolled-back refused)
                        ;; A promotion outranks a `stop` in the same answer.
                        ;; They are two fields answering one question — is there
                        ;; work — and the promotion is the specific one: it names
                        ;; the work, on a layer, for a fixer. Honouring the stop
                        ;; instead would end the run holding a finding it had
                        ;; just created, which is the prose outcome this channel
                        ;; exists to replace. `escalate` is untouched: a design
                        ;; question outranks a repair, and the promoted finding
                        ;; stays open for whoever answers it.
                        :control (if (and (seq promoted)
                                          (= :stop (:decision decision)))
                                   :continue
                                   (:decision decision)))
            ctx' (if stale
                   (assoc ctx' :control :stop :status :unfixable
                          :unfixable (vec stale))
                   ctx')]
        ;; Unguarded on purpose: a first quiet round is recorded HERE, and the
        ;; review stage's withholding rides on ctx' to `record-statuses!`.
        (record-review! cwd ctx')
        ctx'))))

(def warden-stage
  "The one reader with a view across layers, so attribution is its job.

Called the arbiter until it absorbed the stage in front of it — a per-layer
   pass, confusingly the original holder of this name, that ruled first and
   handed its dispositions down as advice. That pass held no tools and read no
   diff, the same shape as this one, so the only things it had that this stage
   did not were two pieces of text: each layer's Out of scope, and what earlier
   rounds had already closed. Both are inputs here now (`toc` carries the first,
   `answered-by-layer` the second), and its rulings were advisory anyway: this
   stage was always told it could overrule them freely, and always had to rule
   on every finding itself.

   Report-only and fully inlined, deliberately: it is the component that decides
   to interrupt a human, and its inputs have to be reconstructable from the
   report afterwards. What genuinely accumulates across rounds is carried as an
   inspectable value (history, answered), never as a resumed conversation."
  {:name :warden
   :run  run-warden-stage})

(defn ^{:malli/schema [:=> [:cat :Path] :boolean]}
  working-copy-dirty?
  "True when jj reports working-copy changes in cwd.

   Answers `is there anything uncommitted`, which is what the diff fixer needs:
   it starts from a restored copy, so anything at all means its fixer wrote."
  [cwd]
  (not (str/blank? (:out (jj/jj! cwd "diff" "--git")))))

(defn ^{:malli/schema [:=> [:cat :Path] :map]}
  working-copy-state
  "What the working copy currently contains, as a value to compare against later.

   A record round cannot use `working-copy-dirty?`, and the difference is not
   pedantry. That predicate answers `is anything uncommitted`, and a session
   worktree almost always carries a human's uncommitted work — so a round that
   treated dirty-after as a violation would halt on every real session. The
   guard therefore only fired on a clean-to-dirty transition, which means it did
   not fire at all in the case that matters: on an already-dirty tree an amender
   could write code and nothing would notice.

   Comparing the diff itself has neither problem. Whatever was there stays there
   and compares equal; anything the pass adds does not."
  [cwd]
  (str (:out (jj/jj! cwd "diff" "--git"))))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  layer-label
  [layer]
  (or (:slug layer) (:bookmark layer)))

(def ^:private remedy-by-kind
  "Which reshape a composition defect's own kind calls for, read off the
   taxonomy the reviewer was taught. A kind with no remedy named is not one this
   stage can act on."
  (into {} (keep (fn [{:keys [kind remedy]}]
                   (when remedy [(keyword kind) remedy])))
        prompts/composition-kinds))

(def ^:private routed-remedies
  "The moves some composition kind is reshaped by, as a set.

   What separates the two halves of a remedy refusal in the sentence a human
   reads: a finding asking for a `reorder` where its kind is folded is asking
   for the wrong move, and one asking for a `split` is asking for a move this
   stage does not have at all."
  (set (vals remedy-by-kind)))

(defn ^{:malli/schema [:=> [:cat :any :Finding] :map]}
  reshape-plan
  "What to do about one finding whose remedy is the stack's shape — or, when
   nothing can be done about it here, which precondition failed.

   The move comes from the finding's own `remedy` where it names one, and the
   stage acts only where that is the move its kind is reshaped by. The two
   disagreeing is a refusal rather than a tie broken toward the taxonomy: a
   finding whose body argues that folding two layers cannot resolve the
   dependency between them, and asks instead for one of them to be split, has
   named the fold as the wrong answer — and the kind alone cannot tell that from
   a finding the fold would have settled. A finding that names nothing still
   plans from its kind; the refusal is about a remedy a finding NAMES.

   The layers a defect spans are read back in STACK order rather than in the
   order the finding happens to list them, so `lower` is the bottom-most named
   layer and `upper` the top-most whatever the reviewer wrote. For an
   order-dependence that pair is the whole instruction: the upper layer
   establishes what the lower one reaches for, so it belongs below it. For a
   cut or a duplication there is no order to correct — the division itself is
   the defect — so the two are folded into one.

   A FOLD additionally requires the named layers to be contiguous. A fold does
   not remove one boundary, it removes every boundary between the layers it
   spans, and each unnamed layer in between is absorbed with them — landing
   changes no reviewer implicated under a claim that never covered them. A cut
   reported across layers 2 and 9 of a nine-layer stack is not a request to
   collapse the stack; jj answers it with a conflict on everything in between,
   and the round has spent its one attempt on an operation that could not have
   applied.

   Where a CUT's span has holes there is a smaller move than the fold: put the
   file the cut runs through in the lower layer, and absorb nothing. That was
   the repair both the reviewer and the warden named on the case this comes from
   — layer 9 rewriting a migration whose checksum layer 1's deploy had already
   recorded — while the stage had only the fold, and so only a refusal. Scoped
   to the misplaced-cut kind: for a duplication, moving one copy down puts both in one
   layer without removing either, which is not what the finding asked for.

   Where the span has holes and there is no file to move, the right cut is a
   judgement, so this still says so and leaves it.

   Never nil. A stage that cannot act still has to say why: `:refused` with its
   reason is what the phase shows instead of the silence that let one finding be
   raised four rounds running with no record that anything was ever tried."
  [stack finding]
  (let [index  (into {} (map-indexed (fn [i l] [(layer-label l) i])) stack)
        named  (vec (sort (distinct (keep index (:layers finding)))))
        asked  (:remedy finding)
        remedy (remedy-by-kind (:kind finding))]
    (cond
      (> 2 (count named))
      {:refused :unnamed-layers
       :because (str "it names " (count named) " layer of this stack; a defect "
                     "inside one layer is not a defect of the cut")}

      (nil? remedy)
      {:refused :no-remedy
       :because (str (if-let [k (:kind finding)] (name k) "this kind")
                     " is repaired by completing a layer, not by moving a boundary")}

      (and asked (not= asked remedy))
      {:refused :remedy-mismatch
       :because (str "it asks for a " (name asked) " and a " (name (:kind finding))
                     " is reshaped by a " (name remedy) "; "
                     (if (contains? routed-remedies asked)
                       "the loop performs the move a finding names, never another one"
                       (str "a " (name asked) " is not a move the loop has, and it "
                            "does not answer with the nearest one it does")))}

      :else
      (let [lo    (long (first named))
            hi    (long (peek named))
            lower (nth stack lo)
            upper (nth stack hi)
            held  (set named)
            gap   (mapv #(layer-label (nth stack %))
                        (remove held (range lo (inc hi))))]
        (cond
          (not (and (= :fold remedy) (seq gap)))
          {:remedy remedy :lower lower :upper upper :fold-legal? (empty? gap)}

          (and (= :misplaced-cut (:kind finding)) (not (str/blank? (str (:file finding)))))
          {:remedy :move :lower lower :upper upper :fold-legal? false
           :file (:file finding)}

          :else
          {:refused :span-has-holes
           :because (str "folding " (layer-label lower) "…" (layer-label upper)
                         " would absorb " (str/join ", " gap)
                         ", which this finding does not name")})))))

(defn- reshape!
  "Carry out one plan: fold two layers into one, move one file's changes down
   between two, or reorder a layer below another.

   A reorder that will not apply falls back to a fold, which removes the
   boundary instead of moving it — the defect is real either way, and jj
   refusing the reorder is jj saying the layers genuinely depend on each other,
   which is a reason to merge them rather than to give up.

   That fallback inherits the fold's own precondition. A reorder over a span
   with holes is legal because it moves one layer and absorbs none; the fold
   over that same span is what `reshape-plan` refuses outright, and reaching it
   through a refused reorder would be the same unappliable squash by a longer
   route."
  [cwd base {:keys [remedy lower upper fold-legal? file]}]
  (case remedy
    :fold (assoc (layers/fold! cwd base upper lower) :did :fold)
    ;; No fallback. A move that will not apply is not evidence that the layers
    ;; depend on each other — it is evidence about that one file — and the fold
    ;; it would fall back to is the operation this plan exists because jj cannot
    ;; perform.
    :move (assoc (layers/move! cwd base upper lower file) :did :move)
    (let [r (layers/reorder! cwd base upper lower)]
      (cond
        (:ok? r)    (assoc r :did :reorder)
        fold-legal? (assoc (layers/fold! cwd base upper lower) :did :fold
                           :after-reorder-refused (:reason r))
        :else       (assoc r :reason
                           (str (:reason r) "; and folding instead would absorb "
                                "layers this finding does not name"))))))


(defn- reshape-outcome
  "What became of one recut finding this round, as the phase will report it.

   Every recut the round held gets one, acted on or not. A reshape that cannot
   run is this round's most consequential silence: the warden withheld the
   finding from the fixers precisely BECAUSE the remedy was the shape, so a
   stage that then does nothing and records nothing leaves the finding with no
   path at all — raised, re-raised, and finally reported unfixable with no trace
   that its one remedy was attempted once and rolled back."
  [finding plan extra]
  (merge {:handle (:handle finding)
          :title  (:title finding)}
         (when-let [k (:kind finding)] {:kind (name k)})
         (when-let [l (:lower plan)] {:lower (layer-label l)})
         (when-let [u (:upper plan)] {:upper (layer-label u)})
         ;; Which file moved, on a move and nowhere else. A move between two
         ;; layers that both survive is invisible in the outcome otherwise —
         ;; lower and upper are the same pair a fold would have named, and they
         ;; are the one thing that does not say what happened to them.
         (when-let [f (:file plan)] {:file f})
         extra))

(def ^:private recut-outcomes-that-park
  "Reshape outcomes after which nothing further will happen to the finding in
   this run.

   Not `deferred`, which is the one outcome that means try again: another
   reshape ran this round, so the plan was made against a stack that has since
   moved, and the next round replans it. Everything else here is terminal —
   the plan was refused, the attempt was made and failed, or the run's one
   attempt is already spent — and terminal with no path is exactly the state a
   park describes."
  #{"refused" "unnamed-layers" "no-remedy" "remedy-mismatch" "span-has-holes"
    "already-attempted"})

(defn ^{:malli/schema [:=> [:cat :any :any :int] :any]}
  park-refused-recuts
  "Add a park for every recut this round could not act on, carrying the
   reshape's own words.

   A recut is withheld from the fixers on purpose — the warden rules it `recut`
   BECAUSE a patch on one side of a bad cut makes that cut permanent — so when
   the reshape stage then refuses it, the finding has no path at all. It went to
   the round's `:reshapes` array and to nothing the next warden or the
   termination check could see: `fix-plan` filters on `:disposition :fix`, and
   `:history` is appended by the fix stage alone. One run refused two recuts;
   one was ruled real and then appeared in no later round's findings or rulings,
   and the other ended the run under a status about the fixer's empty input.

   A park is the same shape of answer — no fix, and a question for a human — and
   all of its lifecycle already exists: carried across rounds, shown back to the
   warden instead of being re-adjudicated, counted against `park-persists-for`,
   terminal at four. The refusal sentence is what makes it decidable; \"folding
   diary-message…teacher-diary-section would absorb seven layers this finding
   does not name\" is precisely the thing a human needs in front of them.

   An existing park is never overwritten. It holds the round it was first raised
   in, which is what the give-up counter reads.

   It names no layer, and that is the accurate answer rather than a gap: a recut
   asks where a boundary belongs, so what it holds open is how the pieces fit —
   the whole-stack target's question — and no single layer's content is in
   doubt because of it."
  [parks outcomes iter]
  (reduce (fn [acc {:keys [handle title outcome because]}]
            (if (or (nil? handle)
                    (contains? acc handle)
                    (not (contains? recut-outcomes-that-park outcome)))
              acc
              (assoc acc handle
                     {:since iter
                      :title title
                      :because (str "the loop refused the recut this asked for"
                                    (when because (str ": " because)))})))
          (or parks {})
          outcomes))

(defn- run-reshape-stage
  [ctx]
  (let [{:keys [cwd base dry-run?]} (:config ctx)
        tried  (get-in ctx [:carry :reshaped] #{})
        stack  (session-stack cwd base)
        recuts (filterv #(= :recut (:disposition %)) (:findings ctx))]
    (if (or dry-run? (empty? recuts))
      ctx
      ;; One attempt per round. A second would be planned against a stack the
      ;; first one just rewrote, and the labels it resolved are already stale.
      (let [plans (mapv (fn [f] [f (reshape-plan stack f)]) recuts)
            pick  (first (keep-indexed
                          (fn [i [f p]]
                            (when (and (:remedy p) (not (contains? tried (:handle f)))) i))
                          plans))
            ;; Asked BEFORE anything is attempted, because afterwards it is no
            ;; whatever happened — see the re-pin below. Only a round that still
            ;; held the tree its reviewers read may re-pin; one that had already
            ;; lost it keeps the stale pin, so the fix stage still refuses and
            ;; still names the revision an outside rebase left.
            ours? (and pick (:reviewed-at ctx)
                       (layers/descends-from? cwd (:reviewed-at ctx)))
            done  (when pick (reshape! cwd base (second (nth plans pick))))]
        (when pick (layers/restore-top! cwd (session-stack cwd base)))
        (let [outcomes (vec (map-indexed
                             (fn [i [f p]]
                               (reshape-outcome
                                f p
                                (cond
                                  (= i pick)
                                  (if (:ok? done)
                                    ;; `:applied?` rather than leaving readers to
                                    ;; recognise fold/move/reorder: this is the
                                    ;; only outcome after which the stack is not
                                    ;; what it was, and the reshape vocabulary
                                    ;; that says so is declared in `reshape!`
                                    ;; where nothing downstream can reach it.
                                    {:outcome (name (:did done)) :applied? true}
                                    {:outcome "refused" :because (:reason done)})

                                  (:refused p)
                                  {:outcome (name (:refused p)) :because (:because p)}

                                  (contains? tried (:handle f))
                                  {:outcome "already-attempted"
                                   :because (str "this run's one attempt at it was made "
                                                 "and did not clear it")}

                                  :else
                                  {:outcome "deferred"
                                   :because (str "another reshape ran this round, so the "
                                                 "stack this was planned against has moved")})))
                             plans))]
          (cond-> (assoc ctx :reshapes outcomes)
            ;; Into :carry, which is the only thing a round hands the next one —
            ;; and the same key the warden's own parks ride in, so one lifecycle
            ;; carries both and the termination check cannot see one kind and
            ;; not the other.
            true (update-in [:carry :parks] park-refused-recuts outcomes (:iter ctx))
            ;; The round's pin follows the rewrite this stage just made, or the
            ;; fix stage reads it as somebody else moving the tree and throws the
            ;; round's repairs away. `restore-top!` parks a fresh `@` on the
            ;; stack top and jj drops the empty commit the round pinned, so the
            ;; pinned revision stops being an ancestor of `@` — which is the one
            ;; thing the drift guard tests. True of a REFUSED attempt too: the
            ;; rollback puts that commit back, and moving off it again is what
            ;; loses it.
            ;;
            ;; Re-pinning is honest rather than a way round the guard because a
            ;; reshape moves boundaries and not content: what `@` holds now is
            ;; what the reviewers read.
            ours? (assoc :reviewed-at (layers/resolve-rev cwd "@"))
            pick (update-in [:carry :reshaped] (fnil conj #{})
                            (:handle (first (nth plans pick))))))))))

(def reshape-stage
  "Findings whose remedy is the shape of the stack, acted on once each.

   Between the warden and the fixers, because a reshape rewrites the layers a
   fixer is about to be positioned on — running one after a fix would land the
   fix on a layer that is about to move, and running both in one round is only
   safe in this order.

   Once per defect per run, keyed on the handle rather than on the finding. That
   is what makes the attempt safe to make on a maybe: a defect the reshape did
   not clear comes back next round under new words, and without the handle it
   would be reshaped again every round for as long as the run lasted. The set
   rides in :carry, which is the only thing a round hands the next one.

   Reports on every recut it held, including the ones it did not act on. Acting
   at most once each is what keeps the attempt cheap; saying so every round is
   what keeps the silence from reading like a decision, because a recut is a
   finding the warden has already kept away from the fixers."
  {:name :reshape
   :run  run-reshape-stage})


(defn- toc-row
  "The table-of-contents row for one layer, by label, or nil.

   The fixer's bound. The row already exists — it is what the warden is given so
   it can attribute deliberately — and the fixer, which is the reader that has
   to STAY inside a layer, was the one shown none of it."
  [toc label]
  (first (filter #(= label (:label %)) toc)))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  with-sweep-memory
  "Mark each finding whose class a fixer has ALREADY swept in this run, with the
   rounds it was swept in.

   A sweep that comes back is evidence about the REMEDY rather than about the
   search. Enumerating a class one instance at a time is what a returning class
   has already disproved, and the fixer is the one reader who could act on that
   and the only one never told: `:history` reaches the reviewer through
   `with-fix-memory` and stops there.

   Keyed on the handle, so this is a claim about the CLASS and not about a
   wording — every instance is filed under the handle the class was first raised
   with, and a return under fresh words keeps it.

   Only a round that LANDED a fix is in `:history` at all, so a round named here
   is one whose sweep was carried out and survived. A fixer that declined leaves
   no entry, and nothing here mistakes its refusal for a sweep that failed."
  [findings history]
  (let [swept (reduce (fn [acc round]
                        (reduce (fn [a f]
                                  (if (:sweep f)
                                    (update a (or (:handle f) (:id f))
                                            (fnil conj []) (:iter round))
                                    a))
                                acc
                                (:findings round)))
                      {}
                      history)]
    (mapv (fn [f]
            (if-let [rounds (seq (get swept (or (:handle f) (:id f))))]
              (assoc f :swept-before (vec (distinct rounds)))
              f))
          findings)))

(defn- owned-by
  "The label of the layer a finding is worked under: its `owner-layer` where
   that names a layer of this stack, and the top layer otherwise — the same
   assign-to-highest rule the warden is told to use for a composition defect,
   applied to an answer that named nothing usable. Every label is nil on an
   unlayered branch, which is the one place a defect can live there.

   One rule for the work and for the decisions, because both are read by the
   same fixer. Two would put a settled finding in front of a layer whose fixer
   never runs while the code it is about is handed to one that is never told."
  [known top f]
  (let [o (:owner-layer f)]
    (if (contains? known o) o top)))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  fix-plan
  "Findings the warden dispositioned :fix, grouped by the layer that OWNS them,
   ordered bottom→top.

   Bottom-up is what makes the fixes cheap rather than what makes them correct:
   landing a fix on a lower layer rewrites every layer above it, so doing the
   lower one first means the upper fixer works against code that will not move
   under it again this round. That holds in the conflict case too, because
   `run-fix-stage` puts a conflicting fix back: either the lower fix is in and
   the layers above were rebased onto it before their fixers started, or it is
   gone and they never moved.

   A finding whose owner_layer names no layer of this stack falls to the top
   layer — the same assign-to-highest rule the warden is told to use for a
   composition defect, applied to an answer that named nothing usable."
  [stack findings]
  (let [to-fix (filter #(= :fix (:disposition %)) findings)]
    (if (empty? stack)
      (if (seq to-fix) [{:label nil :layer nil :findings (vec to-fix)}] [])
      (let [labels (mapv layer-label stack)
            known  (set labels)
            top    (last labels)
            by     (group-by #(owned-by known top %) to-fix)]
        (into []
              (keep (fn [layer]
                      (when-let [fs (seq (get by (layer-label layer)))]
                        {:label (layer-label layer) :layer layer :findings (vec fs)})))
              stack)))))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  settled-by-layer
  "What the run has already SETTLED, keyed by the layer whose fixer would meet
   it — `rounds` is each round's findings, oldest first, THIS round's included.

   The fixer of a layer is the reader that can undo a decision without knowing
   there was one, so it is shown what the warden settled about the same code and
   held back from the plan; `prompts/settled-block` renders it and argues why.

   This round's rulings are the point, not a bonus. The warden rules and the
   fixers run inside one round, so the deviation a fixer is about to walk into
   is most often one decided minutes earlier — `:history` gains the round only
   after the fixes land, which is why the caller conjoins the live findings on
   exactly as `record-statuses!` does.

   Attributed by `owned-by`, the rule `fix-plan` routes work with, so what a
   fixer is handed and what it is told to leave come off one map of the stack.
   Every finding lands under nil on an unlayered branch, which is where the flat
   plan's single entry looks for it.

   Latest ruling per finding, via `latest-rulings`: a round that reverses an
   earlier decision has decided, and a fixer told to honour the decision that
   was reversed would be honouring nothing."
  [stack rounds]
  (let [known (set (mapv layer-label stack))
        top   (last (mapv layer-label stack))]
    (->> (latest-rulings rounds)
         (filter settled?)
         (map #(-> (select-keys % [:title :disposition :authority :of :because
                                   :file :line-start :line-end :owner-layer])
                   (assoc :id (or (:handle %) (:id %)))))
         (group-by #(owned-by known top %)))))

(defn ^{:malli/schema [:=> [:cat :any :any] :string]}
  layer-fixer-session
  "A stable claude session id per layer, derived from the run's own id so it is
   the same across rounds without being carried in mutable state.

   One session per LAYER, never one across layers: a fixer resumed across layers
   would carry one layer's context into another, which is exactly the boundary
   the stack exists to keep."
  [impl-session-id label]
  (str (java.util.UUID/nameUUIDFromBytes
        (.getBytes (str impl-session-id "|" label) "UTF-8"))))

(defn- record-launch
  "`ctx` with one fixer launch entered in the run's launch record, and the
   findings it was handed stamped with whether it ran.

   The record is `:carry :fixer-launches`: per layer label, every launch the fix
   stage has made on it this run, oldest first, as `{:round :handed :ran?}` plus
   the `:exit-code` of one that did not run. It is the one account of whether a
   fixer ran on a layer, written at launch by the only thing that launches
   fixers. The outcome lists (`:fixes`, `:rolled-back`, `:declined`,
   `:launch-failed`) say what a launch PRODUCED; each holds only some of the
   launches that ran, so a reader asking whether one ran at all reconstructs the
   answer from them and misses whichever list it did not read.

   Never pruned. A session once opened stays opened whatever later becomes of
   the findings it was opened for — which is why this is not one of the
   channels `carried-while-open` trims.

   The stamp, `:fixer-ran?` on each handed finding, is the same fact filed per
   finding, because a finding is all the give-up counter is handed; see
   `repair-attempted?`. Keyed by handle-or-id, as `:handed` is."
  [ctx label handed ran? exit-code]
  (let [ids (set handed)]
    (-> ctx
        (update-in [:carry :fixer-launches label] (fnil conj [])
                   (cond-> {:round (:iter ctx) :handed handed :ran? ran?}
                     (and (not ran?) (some? exit-code)) (assoc :exit-code exit-code)))
        (update :findings
                (partial mapv #(cond-> %
                                 (contains? ids (or (:handle %) (:id %)))
                                 (assoc :fixer-ran? ran?)))))))

(defn- session-opened?
  "Has a fixer on this layer opened its claude session this run? Its next launch
   resumes that session only then, and records a new one otherwise.

   This is a question about claude's session store, not about what the last
   fixer produced: `--session-id` is refused for an id claude already holds and
   `--resume` for one it has never seen, and either refusal kills the launch
   before a turn. So it is read off the launch record, where every launch that
   ran is entered whatever came of it — a landed repair, one the stack rolled
   back, an argued refusal, a silent one, a fixer the budget killed. Read off
   the outcome lists instead, a layer is re-issued an id claude already holds
   whenever its last fixer's outcome is one the reader skipped or one that was
   pruned when its findings settled, and every launch after that dies at the
   door.

   So a fixer the budget killed with nothing written RESUMES, carrying the lap
   the kill cut short into its next launch. That is the price of one session per
   layer: the only other launch on the same id is one that does not start.

   A launch that took no turn is not counted as having opened anything. If it
   did, the next launch is refused, and that refusal is a launch failure like
   any other, bounded by `launch-failure-limit`."
  [launches label]
  (boolean (some :ran? (get launches label))))

(def ^:private launch-failure-limit
  "How many launches in a row on one layer may produce no running fixer before
   the run stops over it, on `:fix-launch-failed`.

   Needed because the give-up counter does not count such a launch — nothing
   was tried, and `repair-attempted?` says so — so a layer whose fixer never
   starts would otherwise be dispatched to in every round the rest of the stack
   lands a repair in, with nothing to end the run over it. Two, because one can
   be the machinery failing once, and the next launch is how anyone finds out;
   two in a row on one layer is the machinery failing the same way, and a third
   would spend a whole round learning it again."
  2)

(defn- unlaunchable
  "The labels in `plan` whose last `launch-failure-limit` launches all failed to
   start — the layers this machinery has shown it cannot put a fixer on."
  [launches plan]
  (into []
        (comp (map :label)
              (filter (fn [label]
                        (<= launch-failure-limit
                            (count (take-while (complement :ran?)
                                               (rseq (vec (get launches label)))))))))
        plan))

(defn- with-working-copy-restored
  "Run `f`, and whatever happens put the working copy back on top of `stack`
   before returning or before the failure propagates.

   The invariant this defends is that the fix stage NEVER hands back a working
   copy parked mid-stack. A fix inserts onto its own layer, so a plan that dies
   part-way through — a layer that will not position, a fixer that blows its
   budget — leaves `@` somewhere inside the stack, and from there `<base>..@`
   no longer spans the branch. The next run would then review a truncated stack
   without ever saying so.

   The restore is best-effort BECAUSE it runs on the failure path: a restore
   that also fails must not replace the diagnosis the caller is about to see."
  [cwd stack f]
  (try
    (f)
    (catch Throwable t
      (try (layers/restore-top! cwd stack) (catch Throwable _ nil))
      (throw t))))

(defn- handed-ids
  "The findings a fix row is about, named rather than counted — the warden's
   handle where it assigned one, else the reviewer's id."
  [findings]
  (mapv (fn [f] (or (:handle f) (:id f))) findings))

(def ^:private fix-budget-ceiling
  "The most wall clock one fixer launch may hold, however many findings it was
   handed.

   The scale below is per finding and the rounds above it are uncapped, so
   without a ceiling one launch of one round can spend the budget of the whole
   stage: `nido.coordinator.lane.drive` gives a driven review stage 8h to hold
   however many rounds it takes, and the fix stage runs a fixer per layer inside
   each of them.

   Three times the loop's default, which is the whole of the measured range and
   then some: the one fixer known to have overrun was killed at 30m on its final
   verification lap, with its repairs written. Past here a fixer is not slow, it
   has hung — which is the failure a wall clock exists for."
  "90m")

(defn- budget-str
  "Milliseconds as `agent/parse-budget-ms` spells a duration: whole minutes where
   the figure is one, seconds otherwise.

   Seconds rather than a rounded minute, because `0m` is not a short budget. It
   parses, arms the kill timer at zero, and destroys the agent before it has read
   anything — so rounding a sub-minute budget down turns a test's `30s` into a
   launch that cannot succeed."
  [ms]
  (let [s (max 1 (quot ms 1000))]
    (if (zero? (mod s 60)) (str (quot s 60) "m") (str s "s"))))

(defn- fix-budget
  "The wall clock ONE fixer gets: the loop's per-launch budget for the lap it
   ends on, and that budget again for each finding it was handed.

   `:budget` is one wall for every agent a loop launches and it is calibrated on
   the cheapest of them. The fixer is the only one that both edits and verifies:
   on the run this is measured from, the warden answered in 34s and the design
   verdict in 5m, while the fixer was killed at 30m mid-`kaocha` on its final
   verification lap with both repairs already written. Rounds are deliberately
   uncapped, so the per-launch wall is the only cap the loop has and it was set
   by the stage that needs it least.

   Scaled by the findings handed rather than raised flat, because that is what
   sets a fixer's work: the repair, the ordered sweep and the account are per
   finding, while the verification lap it ends on is paid once whatever it was
   asked to do. `prompts/account-excerpt` sizes the other half of the same
   asymmetry the same way.

   Never below `budget` itself — a caller naming a longer wall for a slow project
   is asking for more everywhere, and a fixer given less than the reviewers that
   read for it is the defect this closes, upside down. And never a refusal: a
   budget `agent/parse-budget-ms` cannot read is handed on untouched, so the
   launch refuses it in the one place whose message names the caller."
  [budget handed]
  (if-let [base (try (agent/parse-budget-ms budget) (catch Exception _ nil))]
    (-> (* base (inc (max 1 (long handed))))
        (min (agent/parse-budget-ms fix-budget-ceiling))
        (max base)
        budget-str)
    budget))

(defn- fixer-log
  "Where one fixer's `suffix` log goes — per layer and per round, which is the
   granularity at which two of them can be told apart.

   The name is the ONLY record of which fixer wrote a file. A round on a layered
   branch launches one fixer per layer, and nothing in the run dir maps a claude
   session id back to the launch that made it: `layer-fixer-session` derives the
   id from the impl session and the label, and the impl session id is written
   nowhere the run dir can be read against. So lines that share a file with
   another fixer's cannot be attributed to a layer afterwards at all — not
   tediously, not at all."
  [run-id label iter suffix]
  (str (fs/path (cstate/run-dir run-id)
                (format "fix-%s-round-%d%s"
                        (codex/safe-label label)
                        (or iter 1)
                        suffix))))

(defn- unattempted-tail
  "The plan entries after `from`: layers a fixer was owed and never launched
   for, because the stage stopped on a conflict it could not roll back.

   The one thing that recorded a skipped layer before this was the ABSENCE of
   its `fix-<layer>-round-N.err.log` from the run dir. In the report a finding
   the abort never reached was indistinguishable from one a fixer read and
   refused — the same `:disposition :fix` in the open list, and nothing else
   either way.

   `:handed` names what the layer was OWED — nothing was handed to a fixer that
   never ran. It keeps that name because `:fixes`, `:rolled-back` and this list
   are one account of every :fix ruling the round held, and they only read as one
   if the finding ids are under one key."
  [plan from]
  (mapv (fn [{:keys [label findings]}]
          {:layer label :handed (handed-ids findings)})
        (subvec plan from)))

(defn- refused-repair
  "The record of a repair the stack would not take, as it stood BEFORE
   `layers/restore-op!` put it back.

   `:commit` and `:account` are what a LANDED fix keeps and this row used to
   throw away. The commit stays in the operation log after the restore, so its
   id is what recovers the edit itself; the account is the only reading of that
   edit anyone will get, and recovering one otherwise meant opening the agent's
   transcript. `:conflicted` is what the rebase collided with, which is where
   the layer order is wrong rather than the code."
  [label handed cid account conflicted]
  (cond-> {:layer label :handed handed :conflicted (vec conflicted)}
    (not (str/blank? (str cid)))     (assoc :commit cid)
    (not (str/blank? (str account))) (assoc :account (str account))))

(defn- refused-carry
  "What the NEXT round is handed about a refused repair: the row, the round it
   happened in, and the findings it was for.

   The channel a decline already had. A rolled-back repair leaves its finding at
   `:fix` and the code byte-identical, so without this the round after re-reads
   the same patch with nothing to say that a repair for it was written and
   refused — one did exactly that and returned `correct` on a P2.

   The findings are named by handle where the warden gave one, exactly as
   `:handed` is, so the carry and the report row point at one finding rather
   than at two spellings of it a round apart. `:handed` comes off for that
   reason: the titles and the sweep flag are what the next round's reviewer is
   shown, and the ids alone beside them would be the same list twice."
  [row iter findings]
  (assoc (dissoc row :handed)
         :since iter
         :findings (mapv (fn [f] {:id (or (:handle f) (:id f))
                                  :title (:title f)
                                  :sweep (boolean (:sweep f))})
                         findings)))

(defn- fixer-system-prompt
  "The live-session block a fixer is launched with, or nil.

   A fixer's cwd is the WORKTREE, so the only nido guidance it can discover for
   itself is whatever the project ships at that root — and a project with a real
   CLAUDE.md there keeps it, by `agent-guidance/write!`'s own rule. The ports of
   the services this session is already running therefore reach the fixer
   through the system prompt or not at all, which is what this supplies.

   Nil-tolerant on purpose, twice over: a review run outside a nido worktree
   resolves to no session, and a session with no services renders no block. Both
   launch exactly as before — the block is an addition to what a fixer knows,
   never a precondition for running one."
  [cwd]
  (try
    (some-> (:instance-id (lifecycle/session-from-cwd cwd))
            launcher/live-services-prompt)
    (catch Throwable _ nil)))

(defn- run-fix-stage
  [ctx]
  (if (:dry-run? (:config ctx))
    (assoc ctx :control :stop :status :dry-run)
    (let [{:keys [cwd base run-id budget impl-session-id fixer-model]} (:config ctx)
          stack (session-stack cwd base)
          ;; Once per stage rather than per launch: every fixer in the round
          ;; stands in the same worktree, so the answer cannot differ between
          ;; them and the registry read is not worth repeating.
          sys-prompt (fixer-system-prompt cwd)
          plan  (fix-plan stack (with-sweep-memory (:findings ctx) (:history ctx)))
          ;; The round's own rulings are conjoined on because the warden runs
          ;; inside it: the decision a fixer is about to walk into is usually
          ;; one taken minutes ago, and `:history` does not hold this round
          ;; until the fixes have landed.
          decided (settled-by-layer stack
                                    (conj (mapv :findings (:history ctx))
                                          (vec (:findings ctx))))]
      (cond
        ;; SOMEBODY ELSE moved the tree between the review and the repair. Every
        ;; finding this round holds was found in a state that is no longer what
        ;; `@` means, so landing fixes now writes them onto code nobody reviewed.
        ;; Refusing names both revisions, which is what makes it actionable
        ;; instead of the `fix-noop` this used to end as.
        ;;
        ;; Somebody else, because the loop's own rewrites move the pin with them
        ;; — `run-reshape-stage` re-pins after it reshapes, and without that this
        ;; guard fires on every round the reshape stage acted in.
        (and (:reviewed-at ctx) (not (layers/descends-from? cwd (:reviewed-at ctx))))
        (assoc ctx :control :stop :status :workspace-drifted
               :drift {:reviewed-at (:reviewed-at ctx)
                       :now (layers/resolve-rev cwd "@")}
               ;; The whole plan, from the first entry: this stops before any
               ;; fixer is positioned, so every layer it was owed is a layer
               ;; nobody was launched for. Without it the phase reports `fixes
               ;; []` with nothing beside it, which is the same shape a round
               ;; with no work at all produces — and the round that stopped here
               ;; is the one whose repairs a reader most needs named.
               :unattempted (unattempted-tail plan 0))

        :else
        (if (empty? plan)
        ;; Nothing was routed to a layer a fixer can touch — distinct from
        ;; fixers running and declining, which is :fix-declined below. Both used
        ;; to be :fix-noop, so the one status covered "there was no work",
        ;; "the fixer never started" and "the fixer read it and said no", and a
        ;; reader could not tell which had happened.
        (assoc ctx :control :stop :status :fix-unrouted)
        (let [ctx'
              (with-working-copy-restored
               cwd stack
               #(reduce
               ;; Indexed, because where in the plan the stage stopped is the
               ;; only thing that says which fixers it never reached.
               (fn [acc [i {:keys [label layer findings]}]]
                 (let [;; The point this attempt rolls back to, taken BEFORE the
                       ;; insert so that undoing it undoes the whole attempt —
                       ;; the inserted commit, the fixer's edits, the describe
                       ;; and the bookmark move — rather than half of one.
                       op     (layers/current-op cwd)
                       _      (layers/position-for-fix! cwd layer)
                       ;; What this fixer was HANDED, named rather than counted.
                       ;; The count alone cannot answer the question every
                       ;; cross-round read wants — did this commit stop that
                       ;; finding coming back — because it holds one end of the
                       ;; join and discards the other.
                       handed (handed-ids findings)
                       ;; This fixer's own wall, not the loop's. Bound here
                       ;; because the rows below report it: once the wall varies
                       ;; per launch, "killed on budget" no longer says which
                       ;; budget, and the number is the whole of what a reader
                       ;; would do about the kill.
                       wall   (fix-budget budget (count findings))
                       {:keys [num-turns result-text timed-out? exit-code]}
                       (agent/launch!
                        {:run-id run-id :cwd cwd
                         :system-prompt sys-prompt
                         ;; nil unless a caller named one, and then it is the
                         ;; FIXER's alone: the reviewers are codex and the warden
                         ;; is a different launch, so a model chosen here says
                         ;; nothing about how this branch was judged.
                         :model fixer-model
                         :first-message (prompts/fix-prompt
                                         {:findings findings
                                          :layer (assoc (toc-row (:toc ctx) label)
                                                        :label label)
                                          ;; The whole stack, not just this
                                          ;; layer's row: what a fixer must not
                                          ;; touch lives in the rows ABOVE its
                                          ;; own, and the row alone cannot say
                                          ;; where its own sits.
                                          :stack (:toc ctx)
                                          :settled (get decided label)})
                         :budget wall
                         :claude-session-id (layer-fixer-session impl-session-id label)
                         :resume? (session-opened? (get-in acc [:carry :fixer-launches])
                                                   label)
                         :err-file (fixer-log run-id label (:iter ctx) ".err.log")
                         ;; Its own transcript, not the run's shared agent.log.
                         ;; A fixer emits an order of magnitude more than the
                         ;; warden does, so the shared file was mostly one
                         ;; fixer's lines with the warden's lost among them —
                         ;; and how a fixer spent its budget is read off the
                         ;; order of its own tool calls, which a merge of
                         ;; several destroys.
                         :out-file (fixer-log run-id label (:iter ctx) ".log")})
                       ;; A KILL COUNTS AS HAVING RUN, and everything below turns
                       ;; on it. The budget timer destroys the process before
                       ;; claude emits its `result` event, so `num-turns` comes
                       ;; back nil for a fixer that made 32 edits and for one
                       ;; claude rejected at the door alike, and `:timed-out?` is
                       ;; the only field that separates them. Read only the count,
                       ;; and the tree is never even asked: one killed fixer's
                       ;; completed repair was filed as a fixer that never started
                       ;; and left on the working copy, where `restore-top!`
                       ;; stranded it mid-stack as an undescribed, unbookmarked
                       ;; commit inside the range of the layer above.
                       ;;
                       ;; The count still vetoes a landing when the launch
                       ;; produced a `result` event saying zero turns, and that is
                       ;; not the same concession. There the agent demonstrably
                       ;; did nothing, so anything the tree holds was already
                       ;; there — on an unstacked branch `position-for-fix!` is a
                       ;; no-op and a session worktree routinely carries a human's
                       ;; uncommitted work, which landing would commit under a
                       ;; fixer's name.
                       ran?   (boolean (or (pos? (or num-turns 0)) timed-out?))
                       ;; Entered before the outcome is decided, so no branch
                       ;; below can forget it: the session exists whatever becomes
                       ;; of the repair.
                       acc    (record-launch acc label handed ran? exit-code)]
                   (if (and ran? (working-copy-dirty? cwd))
                     (let [cid (layers/land-fix!
                                cwd layer
                                (str "review-loop: iter " (:iter ctx) " fixes"
                                     (when label (str " (" label ")"))))
                           _   (layers/restore-top! cwd stack)
                           ;; :account is what the fixer SAID, kept on the
                           ;; branch where it landed something and not only where
                           ;; it refused. A repair and a blocked verification
                           ;; arrive in one message — one fixer landed its fix
                           ;; and reported that babashka could not build a
                           ;; classpath in that worktree, so neither gate it was
                           ;; told to run had actually run — and keeping the text
                           ;; on the decline branch alone deleted the second half
                           ;; of every such message.
                           ;;
                           ;; A killed fixer has none, and `:timed-out?` is what
                           ;; says the account is missing because the process was
                           ;; destroyed rather than because the fixer landed its
                           ;; work in silence. It also says the repair may be
                           ;; part-done: the kill lands whatever the tree held at
                           ;; the moment it arrived, which is the only reading of
                           ;; that commit anyone gets.
                           fix (cond-> {:layer label :commit cid
                                        :handed handed
                                        :fixed-count (count findings)}
                                 (not (str/blank? (str result-text)))
                                 (assoc :account (str result-text))
                                 timed-out? (assoc :timed-out? true :budget wall))
                           ;; A fix lands by REWRITING its layer, so jj rebases
                           ;; every layer above it, and a rebase can conflict.
                           ;; Nothing asked. The markers rode up the stack in the
                           ;; committed text and were found a round later by a
                           ;; reviewer reading a namespace that no longer parsed
                           ;; — two of that round's three findings and one of its
                           ;; two fixers existed only to undo them. Had the round
                           ;; before been terminal, the run would have reported
                           ;; ten fixes on a branch whose tests do not read.
                           ;;
                           ;; Not covered by the :workspace-drifted guard: that
                           ;; compares the pinned :reviewed-at against @ once, at
                           ;; the top of the stage, so a conflict the stage
                           ;; itself creates is invisible to it.
                           bad (layers/conflicted cwd base)]
                       (if (empty? bad)
                         (update acc :fixes (fnil conj []) fix)
                         ;; The stack refusing ONE repair is not the round refusing
                         ;; the rest. Rolling this layer's fix back by operation id
                         ;; — the shape `layers/attempt-reshape!` already treats a
                         ;; refusal with — returns the stack to clean and lets every
                         ;; fixer still in the plan run. It also makes `fix-plan`'s
                         ;; bottom→top order true where it used to be merely
                         ;; intended: the layer that would have moved under the
                         ;; fixers above has been put back.
                         (do (layers/restore-op! cwd op)
                             (if-let [still (seq (layers/conflicted cwd base))]
                               ;; `restore-op!` is best-effort by design, so whether
                               ;; it took is asked rather than assumed. It did not:
                               ;; the fix is still on the stack and so are the
                               ;; markers, which is the one case that genuinely
                               ;; needs a human before anything else runs.
                               ;;
                               ;; What the round was still going to do is named on
                               ;; the way out. Stopping here forfeits every fixer
                               ;; above this layer, and a forfeited repair is still
                               ;; owed — reported as nothing at all, it reads as a
                               ;; finding a fixer considered and let stand.
                               (reduced (-> acc
                                            (update :fixes (fnil conj []) fix)
                                            (assoc :conflicted (vec still))
                                            (assoc :unattempted
                                                   (unattempted-tail plan (inc i)))))
                               ;; Both records of the same refusal, and both are
                               ;; new: the row used to name the layer and the
                               ;; conflict and drop everything the fixer had
                               ;; done, and nothing was handed to the next round
                               ;; at all — which is the round that reads this
                               ;; unchanged code.
                               (let [row (refused-repair
                                          label handed cid result-text bad)]
                                 (-> acc
                                     (update :rolled-back (fnil conj []) row)
                                     (assoc-in [:carry :rolled-back label]
                                               (refused-carry row (:iter ctx)
                                                              findings))))))))
                     (if-not ran?
                       ;; No fixer ran: claude refused the launch — a session id
                       ;; it already held, a flag it would not take, a credential
                       ;; — or answered it having taken no turn. Its own outcome,
                       ;; because every other list here is something a fixer DID,
                       ;; and filed among them it reads as a fixer that looked at
                       ;; the findings and refused. The exit code is kept because it
                       ;; and the layer's err.log are all anyone has of why.
                       ;; Nothing is carried as an argument; the launch record is
                       ;; what tells the next warden, and `unlaunchable` what ends
                       ;; the run if it keeps happening.
                       (do (layers/restore-top! cwd stack)
                           (update acc :launch-failed (fnil conj [])
                                   (cond-> {:layer label :handed handed}
                                     (some? exit-code)
                                     (assoc :exit-code exit-code)
                                     (not (str/blank? (str result-text)))
                                     (assoc :reason (str result-text)))))
                     ;; The fixer ran and left the tree unchanged. That is a
                     ;; decision it made and explained, and the explanation was the
                     ;; only account of why a round did nothing — discarded here,
                     ;; so a run could end on "no changes" with the reason it
                     ;; declined stated nowhere. It is kept per layer.
                     (let [;; An argument, as against silence. A killed fixer ran,
                           ;; and the argument it might have made was never
                           ;; emitted; carrying its empty account would tell the
                           ;; next warden a fixer had made a case nobody made.
                           argued? (not (str/blank? (str result-text)))]
                       (layers/restore-top! cwd stack)
                       (cond-> (update acc :declined (fnil conj [])
                                       ;; :handed for the same reason it is on a
                                       ;; landed fix and on an unattempted layer:
                                       ;; the lists are one account of every :fix
                                       ;; ruling the round held, and they add up
                                       ;; only if the finding ids are under one
                                       ;; key.
                                       ;;
                                       ;; :timed-out? is what makes a row with no
                                       ;; `:reason` legible. Every other row of
                                       ;; that shape is a fixer that ran and said
                                       ;; nothing; this one is a fixer whose
                                       ;; account was still in the process when
                                       ;; the budget destroyed it, and the
                                       ;; findings it was handed stand for want of
                                       ;; time rather than on an argument.
                                       (cond-> {:layer label :handed handed}
                                         result-text (assoc :reason (str result-text))
                                         timed-out?  (assoc :timed-out? true :budget wall)))
                         ;; Into :carry, the only thing a round hands the next
                         ;; one. A refusal leaves its finding at :fix, so without
                         ;; this the argument reaches report.json and no reader —
                         ;; not the warden that could settle it, not the session
                         ;; that would otherwise have to build it again.
                         argued?
                         (assoc-in [:carry :fixer-declines label]
                                   {:layer label
                                    :since (:iter ctx)
                                    :reason (str result-text)
                                    ;; Named by handle where the warden gave one,
                                    ;; exactly as :handed is, so the carry and the
                                    ;; report row point at one finding rather than
                                    ;; at two spellings of it a round apart.
                                    :findings (mapv (fn [f]
                                                      {:id (or (:handle f) (:id f))
                                                       :title (:title f)})
                                                    findings)})))))))
                 ctx (map-indexed vector plan)))
              ctx' (if (seq (:fixes ctx'))
                     (update ctx' :history (fnil conj [])
                             {:iter (:iter ctx')
                              :fixes (:fixes ctx')
                              :fixed-count (reduce + 0 (map :fixed-count (:fixes ctx')))
                              :findings (:findings ctx')
                              ;; What the reviewers of THIS round read, so the
                              ;; next round can ask whether these fixes reached
                              ;; the code. `:history` is the only channel that
                              ;; carries a round's account to the termination
                              ;; check; see `round-changed?`.
                              :patch-hashes (:patch-hashes ctx')
                              :warden (:warden ctx')})
                     ctx')]
          (cond
            ;; Reached only when the rollback above could not clear the conflict,
            ;; so the stack really is holding markers. Stop with it named rather
            ;; than reviewing it again: the next round would read those markers as
            ;; source and spend its reviewers and its fixers on repairing a mess
            ;; this stage made. The fixes that DID land stay — throwing them away
            ;; would discard a round's work over a rebase a human can resolve in
            ;; a minute, and the change ids say where.
            (seq (:conflicted ctx'))
            (assoc ctx' :control :stop :status :fix-conflicted)

            ;; A layer this machinery cannot put a fixer on. Its findings are
            ;; untried rather than resisted, so the give-up counter never reaches
            ;; them, and without this the round after re-dispatches to it for as
            ;; long as the rest of the stack keeps landing repairs. Whatever else
            ;; this round landed stays, as it does on a conflict: the stop is
            ;; about one layer's launches, and the repairs above and below it are
            ;; not in question.
            (seq (unlaunchable (get-in ctx' [:carry :fixer-launches]) plan))
            (assoc ctx' :control :stop :status :fix-launch-failed)

            ;; Every repair this round produced was refused by the stack and put
            ;; back, so the tree is exactly what the reviewers already read.
            ;; Distinct from :fix-declined, which is fixers reading the findings
            ;; and saying no: here they said yes and the rebase said no, and the
            ;; two want different things from whoever looks.
            (and (empty? (:fixes ctx')) (seq (:rolled-back ctx')))
            (assoc ctx' :control :stop :status :fix-rolled-back)

            ;; Nothing landed, and which of three things happened is the
            ;; difference between an answer and an interruption. :fix-declined is
            ;; fixers reading the findings and saying no, which is a decision a
            ;; human reads the reasons of; a kill decided nothing, and the run
            ;; wants more room rather than a different answer; a launch that
            ;; never started decided nothing either, and what it wants is the
            ;; machinery looked at. Collapsed onto the decline, a fixer that
            ;; spent thirty minutes on two findings arrives as `fix-declined · 2
            ;; open` — the report asserting a refusal nobody made, in the words of
            ;; the status just above.
            ;;
            ;; After :fix-rolled-back, which is a stronger statement about the
            ;; same round: a rollback is a completed event with a commit id
            ;; behind it and a layer order to question, where a kill on a clean
            ;; tree left nothing to look at. A launch failure goes before the
            ;; kill because it is the one of the two where nothing was attempted
            ;; at all.
            (empty? (:fixes ctx'))
            (assoc ctx' :control :stop
                   :status (cond
                             (seq (:launch-failed ctx'))         :fix-launch-failed
                             (some :timed-out? (:declined ctx')) :fix-timed-out
                             :else                               :fix-declined))

            :else ctx')))))))

(def fix-stage
  "Fixes run only after every finding has an owner, one layer at a time,
   bottom→top.

   Serial because fixers mutate the single working copy — the reviews before
   them could fan out precisely because they do not. Each fix inserts onto its
   own layer and moves that layer's bookmark, so it reaches that layer's PR
   rather than riding up into the one above."
  {:name :fix
   :run  run-fix-stage})

(defn ^{:malli/schema [:=> [:cat :map :any] :boolean]}
  round-changed?
  "Whether the round before this one moved the code, read off the two records it
   left: its fix rows, and the branch content each round's reviewers saw.

   `nido.review.loop/no-progress?` asks this before calling a repeated finding
   set a stall. The set alone cannot tell one apart from a defect class being
   narrowed — every instance of a class is filed under the handle the class was
   first given, so a round that closed two of them reports the same handles as
   the round before, which is the shape a run stopped on with an adjudicated fix
   in hand.

   Both halves are asked, because either alone answers a different question.
   Fix rows say the round attempted repairs at all; patch hashes say those
   repairs reached the branch — a fixer can land a commit that changes nothing,
   and a round whose reviewers read identical content has made no progress
   however many commits it wrote. A round absent from the history landed nothing
   and is no evidence of anything.

   Both sets must be non-empty to be compared: an empty one is a round jj could
   not diff, and the honest answer there is that nothing is known to have
   changed — which leaves the stall check exactly as strict as it was."
  [ctx prior]
  (let [prev  (last (filter #(= (dec (:iter ctx)) (:iter %)) prior))
        was   (:patch-hashes prev)
        holds (:patch-hashes ctx)]
    (boolean (and (pos? (long (or (:fixed-count prev) 0)))
                  (seq was) (seq holds)
                  (not= was holds)))))
