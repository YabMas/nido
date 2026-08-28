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
   [nido.coordinator.session :as csession]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.platform.core :as core]
   [nido.review.cache :as cache]
   [nido.review.codex :as codex]
   [nido.review.layers :as layers]
   [nido.review.prompts :as prompts]
   [nido.session.lifecycle :as lifecycle]
   [nido.vsdd.jj :as jj]))

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

(defn settled?
  "Has this finding been decided? See `settling-dispositions`."
  [f]
  (contains? settling-dispositions (:disposition f)))

(defn- ruling
  "One per-finding ruling from the warden's JSON. A disposition outside the
   four is read as :fix rather than dropped — the fail-safe direction, and the
   one that keeps \"nothing is dropped\" true of a malformed answer."
  [r]
  (let [d (some-> (:disposition r) keyword)]
    {:id          (:id r)
     :same-as     (:same_as r)
     :owner-layer (:owner_layer r)
     :disposition (if (contains? dispositions d) d :fix)
     :authority   (:authority r)
     :of          (:of r)
     :because     (:because r)}))

(defn parse-warden-decision
  "Last fenced ```json block in `text` -> {:decision :reason :rulings}.
   Unparseable -> indeterminate."
  [text]
  (let [block (when (string? text) (last (re-seq fenced-json-re text)))]
    (if-let [body (second block)]
      (try
        (let [m (json/parse-string body true)
              d (keyword (:decision m))]
          (if (contains? #{:continue :stop :escalate} d)
            {:decision d
             :reason   (:reason m)
             :rulings  (into [] (comp (filter :id) (map ruling)) (:findings m))}
            {:decision :indeterminate :reason (str "unknown decision: " (:decision m))}))
        (catch Exception e
          {:decision :indeterminate :reason (str "unparseable: " (ex-message e))}))
      {:decision :indeterminate :reason "no json decision block"})))

(defn project+ws-from-cwd
  "Resolve cwd → [project ws-id] via the session, or nil. The same path
   tasks.nido-review/append-review-entry! takes to find where to write."
  [cwd]
  (try
    (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
      (when-let [ws-id (csession/workstream-id-for (keyword project) session)]
        [(keyword project) ws-id]))
    (catch Throwable _ nil)))

(defn session-stack
  "This session's layers, bottom→top, or [] when cwd resolves to no session (a
   review run outside a nido worktree still has to work — it just has no stack
   to land on)."
  [cwd base]
  (try
    (if-let [session (:session (lifecycle/session-from-cwd cwd))]
      (layers/stack cwd session (or base "main"))
      [])
    (catch Throwable _ [])))

(def ^:private max-concurrent-reviews
  "Reviews are codex processes that spend their life waiting on an API, so they
   are cheap to hold open — but not free, and a wide stack should not open a
   dozen at once."
  6)

(defn in-parallel
  "Run each thunk, at most `n` at a time, preserving order.

   An exception in any thunk propagates carrying its ORIGINAL ex-data: a bare
   future deref wraps it in ExecutionException, which would hide the
   :review-failed reason the engine branches on and turn a failed review into an
   unhandled crash."
  [n thunks]
  (into []
        (mapcat (fn [chunk]
                  (let [futs (mapv #(future (%)) chunk)]
                    (mapv (fn [f]
                            (try @f
                                 (catch java.util.concurrent.ExecutionException e
                                   (throw (or (.getCause e) e)))))
                          futs))))
        (partition-all n thunks)))

(defn composition-of
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

(defn review-targets
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
        whole    {:label "stack" :from base-rev :to "@" :brief nil :stack? true}]
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

(defn with-patch-hashes
  "Stamp each target with the hash of the patch it contributes — its identity
   for the cache. A target whose hash cannot be computed keeps nil and is
   therefore never skipped."
  [cwd targets]
  (mapv #(assoc % :patch-hash (layers/patch-hash cwd (:from %) (:to %))) targets))

(defn to-review
  "Split targets into those this round must review and those already converged
   at exactly this patch. A target with no hash is always reviewed: unknown
   content is reviewed content."
  [cache targets]
  (let [skip? (fn [t] (and (:patch-hash t) (cache/converged? cache (:patch-hash t))))]
    {:review (into [] (remove skip?) targets)
     :skipped (into [] (filter skip?) targets)}))

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
  "The stack's table of contents — one entry per layer: what it claims, what it
   declared out of scope, and which files it touches. This is what the warden
   gets INSTEAD of the other layers' diffs, so it can attribute deliberately
   without re-deriving them.

   `:out-of-scope` is carried because the warden is told it may close a finding
   on the authority `out-of-scope` — \"a layer's Out of scope names it\". Without
   the field, that authority is one the warden can cite but never actually
   read, which is how a close stops being evidence and becomes a guess."
  [results]
  (into []
        (comp (remove #(:stack? (:target %)))
              (map (fn [{:keys [target manifest]}]
                     {:label        (:label target)
                      :claim        (get-in target [:brief :claims])
                      :out-of-scope (get-in target [:brief :out-of-scope])
                      :files        (vec (remove str/blank?
                                                 (str/split-lines (or manifest ""))))})))
        results))

(defn announce-targets!
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

;; Defined below, beside the cache reasoning it belongs with, and called from
;; both stages that can end a round holding nothing owed — see its docstring.
(declare record-convergence!)

(def review-stage
  "Every layer and the whole stack, reviewed in one round.

   Reviews are read-only and independent — that independence is exactly what a
   layer's `Out of scope` buys — so they fan out in parallel. Nothing here
   touches the working copy, and file content is read at each target's own
   revision, so concurrent reviews cannot see each other's state."
  {:name :review
   :run  (fn [ctx]
           (let [{:keys [cwd base run-id]} (:config ctx)
                 [project ws-id] (project+ws-from-cwd cwd)
                 cached  (if ws-id (cache/read-cache project ws-id) {})
                 all     (with-patch-hashes cwd (review-targets cwd base))
                 {:keys [review skipped]} (to-review cached all)
                 targets review
                 _       (announce-targets! ctx {:review review :skipped skipped})
                 results (in-parallel
                          max-concurrent-reviews
                          (map (fn [t]
                                 #(do
                                    (announce-target! ctx "running" t nil)
                                    (let [r (assoc (codex/review!
                                                    {:cwd cwd :run-id run-id :iter (:iter ctx)
                                                     :from (:from t) :to (:to t)
                                                     :label (:label t) :brief (:brief t)
                                                     :composition (:composition t)})
                                                   :target t)]
                                      (announce-target! ctx "reviewed" t
                                                        {:findings (count (:findings r))})
                                      r)))
                               targets))
                 whole    (or (first (filter #(:stack? (:target %)) results))
                              (first results))
                 findings (collect-findings results)]
             (if (empty? findings)
               ;; Nothing was reported anywhere, so nothing is owed anywhere and
               ;; every target reviewed at this patch has converged. Recorded
               ;; here because this branch is terminal: the engine stops on
               ;; :control :stop, so the warden — which is where convergence is
               ;; otherwise written — never runs for a round that starts clean.
               (let [ctx' (assoc ctx :findings [] :reviews results :skipped skipped
                                 :control :stop :status :clean)]
                 (record-convergence! cwd ctx')
                 ctx')
               (assoc ctx
                      :findings findings
                      :reviews results
                      :skipped skipped
                      :cache cached
                      :toc (build-toc results)
                      :overall-correctness (:overall-correctness whole)
                      :base-rev (:base-rev whole)
                      :manifest (:manifest whole)))))})

(defn discover-design-record
  "This workstream's latest :design record, or nil.

   Replaces a glob for the newest `docs/superpowers/specs/*-design.md`, which
   picked a file by filename order — in a project with a specs directory that is
   almost never the design of the change under review. The yardstick has to be the
   design *this* change committed to, and the ledger is where that lives."
  [cwd]
  (when-let [[project ws-id] (project+ws-from-cwd cwd)]
    (ws/latest-entry project ws-id :design)))

(defn discover-baseline
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

(def ^:private stance-char-cap 12000)

(defn stance-path
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

(defn read-stance
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

(defn answered-by-layer
  "What earlier rounds already CLOSED, per layer, for the layers under review.

   Written by `answered-for` from this stage's own closes and hung off each
   layer's patch hash, so an answer evaporates the moment that layer's content
   changes. Reading it back here closes the loop: the reviewer starts fresh every
   round and will report a closed finding again, which is not new information —
   without this the same finding is re-adjudicated for as long as the layer sits
   unchanged.

   Layers with nothing answered are dropped rather than carried as empty rows:
   the prompt block is evidence, and a layer named with nothing under it reads
   as a layer that was asked and had no answer."
  [ctx]
  (into []
        (keep (fn [{:keys [target]}]
                (when-let [a (seq (cache/answered (:cache ctx) (:patch-hash target)))]
                  {:label (:label target) :answered (vec a)})))
        (:reviews ctx)))


(defn converged-targets
  "Pure: the targets this round left with nothing OWED, paired with the patch
   they were reviewed at.

   Owed, not unfixed. A target converges when every finding naming it was
   SETTLED — decided, by a disposition that ends it — not merely when none of
   them was handed to a fixer. Those two differ for every disposition that is neither,
   and the difference is not academic: a finding the loop has no move for is
   still open, and marking its target converged writes that patch into a store
   that only grows, so a later run at the same content skips the target and the
   finding is gone. `nido.review.cache` leans toward over-invalidating for
   exactly this reason — over-invalidating costs one review, and reading
   `not :fix` as `nothing owed` cost a P1 that no re-run would have shown again.

   A finding an upper layer reported but a lower one owns leaves the upper layer
   converged, correctly: nothing about it needs changing. A finding the warden
   gave no owner names no layer, so it blocks none of them — but it is still
   open, so the whole-stack target holds it, and that target converging on
   `nothing anywhere is open` is what stops it being lost. Any fix that lands
   changes the patch of every layer above it, so its hash stops matching on its
   own."
  [reviews findings]
  (let [open   (remove settled? findings)
        owners (into #{} (map :owner-layer) open)]
    (into []
          (comp (map :target)
                (filter (fn [t]
                          (and (:patch-hash t)
                               (if (:stack? t)
                                 (empty? open)
                                 (not (contains? owners (:label t))))))))
          reviews)))

(defn answered-for
  "What this target reported and the warden SETTLED. Carried forward under the
   patch hash so next round's fresh reviewer, reporting the same thing, gets
   answered rather than re-adjudicated.

   Every settling disposition, not only a close. A decline is a decision — the
   finding is true and this branch is leaving it — and a decision re-argued
   every round is not one: the reviewer has no memory, so without this the same
   defect is declined again and again at full cost, and the reason given the
   first time is never seen by the round that needs it. The disposition rides
   along so the next warden can tell what kind of answer it is looking at."
  [label findings]
  (into []
        (comp (filter #(and (= label (:from-layer %)) (settled? %)))
              (map #(select-keys % [:id :title :disposition :authority :because])))
        findings))

(defn record-convergence!
  "Write what converged this round into the workstream's cache.

   Called from the two stages that can end a round with nothing owed, rather
   than from a stage of its own: the engine short-circuits on `:control :stop`,
   so anything sequenced after the stage that stopped would never run. Those two
   are the warden, which stops once every finding is settled, and the review
   stage, which stops before a warden exists when the round reported nothing at
   all. The second is the one most worth recording and was the one missing —
   a round that finds nothing is the loop's best outcome, and it was the only
   outcome it forgot, so re-reviewing an untouched patch cost a full fan-out
   every time.

   Safe to call from either because it reads only `:reviews` and `:findings` and
   both are set by then, and because `converged-targets` is pure: with no
   findings nothing is owed, so every target reviewed at this patch converges.
   Best-effort — a cache that cannot be written costs the next run some
   duplicated review and nothing else."
  [cwd ctx]
  (when-let [[project ws-id] (project+ws-from-cwd cwd)]
    (let [converged (converged-targets (:reviews ctx) (:findings ctx))]
      (when (seq converged)
        (let [now (str (java.time.Instant/now))
              c   (reduce (fn [c t]
                            (cache/record c (:patch-hash t)
                                          {:status   :converged
                                           :label    (:label t)
                                           :round    (:iter ctx)
                                           :at       now
                                           :answered (answered-for (:label t) (:findings ctx))}))
                          (or (:cache ctx) (cache/read-cache project ws-id))
                          converged)]
          (cache/write! project ws-id c))))))

(defn resolve-handle
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

(defn apply-rulings
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
                                 :because     (or (:because r)
                                                  (when-not r "the warden did not rule on this finding"))})]
              (assoc merged :handle (resolve-handle handles merged))))
          findings)))

(defn seen-findings
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
   :run  (fn [ctx]
           (let [{:keys [cwd run-id budget]} (:config ctx)
                 handles (get-in ctx [:carry :handles] {})
                 prompt (prompts/warden-prompt
                         {:findings (:findings ctx)
                          :seen     (seen-findings (:history ctx))
                          :history  (mapv #(dissoc % :findings) (:history ctx))
                          :design   (discover-design-record cwd)
                          :stance   (read-stance (first (project+ws-from-cwd cwd)))
                          :toc      (:toc ctx)
                          :answered (answered-by-layer ctx)})
                 {:keys [num-turns result-error? result-text]}
                 (agent/launch! {:run-id run-id :cwd cwd
                                 :first-message prompt :budget budget
                                 :tools ""
                                 :err-file (str (fs/path (cstate/run-dir run-id) "agent.err.log"))})
                 decision (parse-warden-decision result-text)]
             (if (or (zero? (or num-turns 0)) result-error?
                     (= :indeterminate (:decision decision)))
               (assoc ctx :warden decision :control :stop
                      :status :warden-indeterminate)
               (let [ruled (apply-rulings (:findings ctx) (:rulings decision) handles)
                     ctx' (assoc ctx
                                 :warden  decision
                                 :findings ruled
                                 ;; The handles have to reach the next round, and
                                 ;; :carry is the only thing here that does —
                                 ;; every other key is rebuilt from :config,
                                 ;; :iter and :history. Merged rather than
                                 ;; replaced, so a finding that stops being
                                 ;; reported keeps its filing for a later round
                                 ;; that raises it again.
                                 :carry (assoc (:carry ctx) :handles
                                               (into handles
                                                     (map (juxt :id :handle))
                                                     ruled))
                                 :control  (:decision decision))]
                 (record-convergence! cwd ctx')
                 ctx'))))})

(defn working-copy-dirty?
  "True when jj reports working-copy changes in cwd.

   Answers `is there anything uncommitted`, which is what the diff fixer needs:
   it starts from a restored copy, so anything at all means its fixer wrote."
  [cwd]
  (not (str/blank? (:out (jj/jj! cwd "diff" "--git")))))

(defn working-copy-state
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

(defn layer-label
  [layer]
  (or (:slug layer) (:bookmark layer)))

(def ^:private remedy-by-kind
  "Which reshape a composition defect's own kind calls for, read off the
   taxonomy the reviewer was taught. A kind with no remedy named is not one this
   stage can act on."
  (into {} (keep (fn [{:keys [kind remedy]}]
                   (when remedy [(keyword kind) remedy])))
        prompts/composition-kinds))

(defn reshape-plan
  "What to do about one finding whose remedy is the stack's shape, or nil.

   `across` names the layers the defect spans, in stack order, so the lower one
   is first and the upper one last. For an order-dependence that is the whole
   instruction: the upper layer reaches for something the lower one does not
   supply, so it belongs below it. For a seam or a duplication there is no order
   to correct — the boundary itself is the defect — so the two are folded into
   one.

   nil when the finding names fewer than two layers of this stack. A composition
   finding that spans one layer is by its own account not a composition defect,
   and one naming a label the stack does not have cannot be acted on without
   guessing which layer was meant."
  [stack finding]
  (let [by-label (into {} (map (juxt layer-label identity)) stack)
        named    (keep by-label (:layers finding))]
    (when (<= 2 (count named))
      (let [lower (first named)
            upper (last named)]
        (when-let [remedy (remedy-by-kind (:kind finding))]
          {:remedy remedy :lower lower :upper upper})))))

(defn- reshape!
  "Carry out one plan. A reorder that will not apply falls back to a fold, which
   removes the boundary instead of moving it — the defect is real either way,
   and jj refusing the reorder is jj saying the layers genuinely depend on each
   other, which is a reason to merge them rather than to give up."
  [cwd base {:keys [remedy lower upper]}]
  (if (= :fold remedy)
    (assoc (layers/fold! cwd base upper lower) :did :fold)
    (let [r (layers/reorder! cwd base upper lower)]
      (if (:ok? r)
        (assoc r :did :reorder)
        (assoc (layers/fold! cwd base upper lower) :did :fold
               :after-reorder-refused (:reason r))))))

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
   rides in :carry, which is the only thing a round hands the next one."
  {:name :reshape
   :run  (fn [ctx]
           (let [{:keys [cwd base dry-run?]} (:config ctx)
                 tried (get-in ctx [:carry :reshaped] #{})
                 stack (session-stack cwd base)
                 todo  (into []
                             (comp (filter #(= :recut (:disposition %)))
                                   (remove #(contains? tried (:handle %)))
                                   (keep (fn [f]
                                           (when-let [p (reshape-plan stack f)]
                                             (assoc p :finding f)))))
                             (:findings ctx))]
             (if (or dry-run? (empty? todo))
               ctx
               ;; One per round. A second reshape would be planned against a
               ;; stack the first one just rewrote, and the labels it resolved
               ;; are already stale.
               (let [{:keys [finding] :as plan} (first todo)
                     result (reshape! cwd base plan)]
                 (layers/restore-top! cwd (session-stack cwd base))
                 (-> ctx
                     (update-in [:carry :reshaped] (fnil conj #{}) (:handle finding))
                     (assoc :reshape (merge {:handle (:handle finding)
                                             :title  (:title finding)
                                             :lower  (layer-label (:lower plan))
                                             :upper  (layer-label (:upper plan))}
                                            result)))))))})


(defn fix-plan
  "Findings the warden dispositioned :fix, grouped by the layer that OWNS them,
   ordered bottom→top.

   Bottom-up is what makes the fixes cheap rather than what makes them correct:
   landing a fix on a lower layer rewrites every layer above it, so doing the
   lower one first means the upper fixer works against code that will not move
   under it again this round.

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
            by     (group-by #(let [o (:owner-layer %)]
                                (if (contains? known o) o top))
                             to-fix)]
        (into []
              (keep (fn [layer]
                      (when-let [fs (seq (get by (layer-label layer)))]
                        {:label (layer-label layer) :layer layer :findings (vec fs)})))
              stack)))))

(defn layer-fixer-session
  "A stable claude session id per layer, derived from the run's own id so it is
   the same across rounds without being carried in mutable state.

   One session per LAYER, never one across layers: a fixer resumed across layers
   would carry one layer's context into another, which is exactly the boundary
   the stack exists to keep."
  [impl-session-id label]
  (str (java.util.UUID/nameUUIDFromBytes
        (.getBytes (str impl-session-id "|" label) "UTF-8"))))

(defn- fixed-before?
  "Has this layer been fixed in an earlier round? Its fixer resumes only then —
   a fresh layer starts a fresh session."
  [history label]
  (boolean (some (fn [h] (some #(= label (:layer %)) (:fixes h))) history)))

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

(def fix-stage
  "Fixes run only after every finding has an owner, one layer at a time,
   bottom→top.

   Serial because fixers mutate the single working copy — the reviews before
   them could fan out precisely because they do not. Each fix inserts onto its
   own layer and moves that layer's bookmark, so it reaches that layer's PR
   rather than riding up into the one above."
  {:name :fix
   :run  (fn [ctx]
           (if (:dry-run? (:config ctx))
             (assoc ctx :control :stop :status :dry-run)
             (let [{:keys [cwd base run-id budget impl-session-id]} (:config ctx)
                   stack (session-stack cwd base)
                   plan  (fix-plan stack (:findings ctx))]
               (if (empty? plan)
                 (assoc ctx :control :stop :status :fix-noop)
                 (let [ctx'
                       (with-working-copy-restored
                        cwd stack
                        #(reduce
                        (fn [acc {:keys [label layer findings]}]
                          (layers/position-for-fix! cwd layer)
                          (let [{:keys [num-turns]}
                                (agent/launch!
                                 {:run-id run-id :cwd cwd
                                  :first-message (prompts/fix-prompt {:findings findings})
                                  :budget budget
                                  :claude-session-id (layer-fixer-session impl-session-id label)
                                  :resume? (fixed-before? (:history ctx) label)
                                  :err-file (str (fs/path (cstate/run-dir run-id)
                                                          (format "fix-%s-round-%d.err.log"
                                                                  (codex/safe-label label)
                                                                  (or (:iter ctx) 1))))})]
                            (if (or (zero? (or num-turns 0)) (not (working-copy-dirty? cwd)))
                              (do (layers/restore-top! cwd stack) acc)
                              (let [cid (layers/land-fix!
                                         cwd layer
                                         (str "review-loop: iter " (:iter ctx) " fixes"
                                              (when label (str " (" label ")"))))]
                                (layers/restore-top! cwd stack)
                                (update acc :fixes (fnil conj [])
                                        {:layer label :commit cid
                                         :fixed-count (count findings)})))))
                          ctx plan))]
                   (if (empty? (:fixes ctx'))
                     (assoc ctx' :control :stop :status :fix-noop)
                     (update ctx' :history (fnil conj [])
                             {:iter (:iter ctx')
                              :fixes (:fixes ctx')
                              :fixed-count (reduce + 0 (map :fixed-count (:fixes ctx')))
                              :findings (:findings ctx')
                              :warden (:warden ctx')})))))))})
