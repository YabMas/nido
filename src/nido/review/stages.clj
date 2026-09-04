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
   [nido.platform.core :as core]
   [nido.review.cache :as cache]
   [nido.review.codex :as codex]
   [nido.review.conformance :as conformance]
   [nido.review.digest :as digest]
   [nido.review.layers :as layers]
   [nido.review.prompts :as prompts]
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
   the warden stage, over the warden's own head, when a park has stood too long."
  #{:stack-conflicted :nothing-to-review :clean :warden-indeterminate :unfixable
    :dry-run :workspace-drifted :fix-unrouted :fix-conflicted :fix-rolled-back
    :fix-declined})

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
     ;; Whether this finding is one instance of a class the fixer should sweep.
     ;; The warden recognises a recurring family unprompted — it says so in
     ;; `because`, in prose, every time — and had no field to say it in.
     :sweep       (boolean (:sweep r))
     :because     (:because r)}))

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  parse-warden-decision
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

(def ^:private max-concurrent-reviews
  "Reviews are codex processes that spend their life waiting on an API, so they
   are cheap to hold open — but not free, and a wide stack should not open a
   dozen at once."
  6)

(defn ^{:malli/schema [:=> [:cat :int :any] :any]}
  in-parallel
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
   seam reports it, sees the same stack next round, and reports it again — three
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

(defn ^{:malli/schema [:=> [:cat :map :any] :map]}
  to-review
  "Split targets into those this round must review and those already converged
   at exactly this patch. A target with no hash is always reviewed: unknown
   content is reviewed content.

   A skipped target is stamped with the round its convergence was recorded in.
   A skip is the loop declining to look at something, and the report could not
   say on what authority: the row named the layer and nothing else, so `skipped`
   was indistinguishable from a claim the reader had to take on trust. With the
   round and the patch hash on the row, it can be checked against the cache."
  [cache targets]
  (let [skip? (fn [t] (and (:patch-hash t) (cache/converged? cache (:patch-hash t))))]
    {:review  (into [] (remove skip?) targets)
     :skipped (into []
                    (comp (filter skip?)
                          (map (fn [t]
                                 (assoc t :converged-at
                                        (:round (get cache (:patch-hash t)))))))
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

;; Defined below, beside the cache reasoning it belongs with, and called from
;; both stages that can end a round — see its docstring.
(declare record-review!)

(defn- fan-out-reviews
  [ctx]
  (let [{:keys [cwd base run-id]} (:config ctx)
        [project ws-id] (project+ws-from-cwd cwd)
        cached  (if ws-id (cache/read-cache project ws-id) {})
        ;; Pin the top of the reviewed range for the whole round. `@` is
        ;; whatever the working copy currently is, and every stage that resolved
        ;; it again was silently asking about a different tree — a concurrent
        ;; rebase moved it out from under a run and nothing noticed, so the
        ;; round reviewed one state and tried to fix another.
        at      (layers/resolve-rev cwd "@")
        all     (with-patch-hashes
                 cwd (with-composition-memory (review-targets cwd base) (:history ctx)))
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
        ;; The mechanical reviewer joins the fan-out, but not the layer bookkeeping:
        ;; it reports on the worktree rather than on a range, so it is no layer and
        ;; belongs in neither :reviews nor the toc. Its findings are ordinary
        ;; findings from here on — handled, ruled on, owned and fixed like any other.
        conform  (when project
                   {:target   {:label "design"}
                    :findings (conformance/findings project cwd)})
        findings (collect-findings (cond-> (vec results)
                                     (seq (:findings conform)) (conj conform)))]
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
      ;; nothing is owed anywhere and every target reviewed at this patch has
      ;; converged. Recorded here because this branch is terminal: the engine
      ;; stops on :control :stop, so the warden — which is where convergence is
      ;; otherwise written — never runs for a round that starts clean.
      (let [nothing? (and (seq results)
                          (every? #(= :nothing-to-review (:status %)) results))
            ;; A flat branch is reviewed by ONE pass over the whole diff, and
            ;; one pass is a sample rather than a verdict: the round that missed
            ;; a change's only P1 reported one of three pre-existing defects and
            ;; called it clean. A layered stack gets several independent
            ;; reviewers over the same code and does not need this; a 0-layer
            ;; target has nothing to cross-check it, so it earns `clean` by
            ;; producing nothing twice in a row rather than once.
            ;; From this round's own targets, not from ctx: the toc is built
            ;; below and this branch never reaches it. A round with no layer
            ;; target reviewed the branch flat.
            flat?    (empty? (build-toc results))
            first-quiet-round? (and flat? (not nothing?)
                                    (not (:quiet-once (:carry ctx))))
            ctx'     (cond-> (assoc ctx :findings [] :reviews results :skipped skipped
                                    :reviewed-at at
                                    :control (if first-quiet-round? :continue :stop)
                                    :status (cond
                                              nothing?           :nothing-to-review
                                              first-quiet-round? nil
                                              :else              :clean))
                       first-quiet-round?
                       (assoc :carry (assoc (:carry ctx) :quiet-once true))
                       ;; The reviewer's correctness verdict, on the one branch
                       ;; that used to drop it. A terminal clean round is the
                       ;; round whose verdict is most worth keeping — it is the
                       ;; only evidence that anyone looked — and it was the only
                       ;; round the report had none for. Absent for a round that
                       ;; read nothing, because no reviewer reached a verdict.
                       (not nothing?)
                       (assoc :overall-correctness (:overall-correctness whole)))]
        (when-not (or nothing? first-quiet-round?) (record-review! cwd ctx'))
        ctx')
      (assoc ctx
             :findings findings
             :reviews results
             :skipped skipped
             :reviewed-at at
             :cache cached
             :toc (build-toc results)
             :overall-correctness (:overall-correctness whole)
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

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  answered-by-layer
  "What earlier rounds already SETTLED, per layer, for the layers under review.

   Written by `record-review!` and hung off each layer's patch hash, so an
   answer evaporates the moment that layer's content changes. Reading it back
   here closes the loop: the reviewer starts fresh every round and will report a
   settled finding again, which is not new information — without this the same
   finding is re-adjudicated for as long as the layer sits unchanged.

   It reads the targets UNDER REVIEW, which is what makes what is written matter
   as much as what is read. While convergence was the only thing recorded, the
   entries in the cache were exactly the patches `to-review` skips, and this
   looked up precisely their complement: every lookup missed, and the block it
   feeds has never reached a warden. A target that owes something is recorded
   too, and that is the entry a next round comes back to.

   Layers with nothing answered are dropped rather than carried as empty rows:
   the prompt block is evidence, and a layer named with nothing under it reads
   as a layer that was asked and had no answer."
  [ctx]
  (into []
        (keep (fn [{:keys [target]}]
                (when-let [a (seq (cache/answered (:cache ctx) (:patch-hash target)))]
                  {:label (:label target) :answered (vec a)})))
        (:reviews ctx)))


(defn ^{:malli/schema [:=> [:cat :any :any :any] :any]}
  converged-targets
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
  (let [owed   (concat (remove settled? findings) parks)
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
   `rounds` is each round's findings, oldest first. Carried forward under the
   patch hash so next round's fresh reviewer, reporting the same thing, gets
   answered rather than re-adjudicated.

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

(defn ^{:malli/schema [:=> [:cat :Path :map] :any]}
  record-review!
  "Write what this round's review left each target at into the workstream's
   cache: its status, and what the run has settled about it.

   Called from the two stages that can end a round, rather than from a stage of
   its own: the engine short-circuits on `:control :stop`, so anything sequenced
   after the stage that stopped would never run. Those two are the warden, which
   stops once every finding is settled, and the review stage, which stops before
   a warden exists when the round reported nothing at all. The second is the one
   most worth recording and was the one missing — a round that finds nothing is
   the loop's best outcome, and it was the only outcome it forgot, so
   re-reviewing an untouched patch cost a full fan-out every time.

   Safe to call from either because it reads only `:reviews`, `:findings`, the
   round history and the carried parks, all of which are set by then, and
   because `reviewed-statuses` is pure.

   The history plus this round's findings is the run's whole account, and it is
   assembled here rather than in `answered-for` so that function stays pure over
   what it is given. The fix stage appends a round to the history and the warden
   runs before it, so the two never overlap.

   Best-effort — a cache that cannot be written costs the next run some
   duplicated review and nothing else."
  [cwd ctx]
  (when-let [[project ws-id] (project+ws-from-cwd cwd)]
    (let [statuses (reviewed-statuses (:reviews ctx) (:findings ctx)
                                      (vals (get-in ctx [:carry :parks] {})))
          rounds   (conj (mapv :findings (:history ctx)) (vec (:findings ctx)))]
      (when (seq statuses)
        (let [now (str (java.time.Instant/now))
              c   (reduce (fn [c [t status]]
                            (cache/record c (:patch-hash t)
                                          {:status   status
                                           :label    (:label t)
                                           :round    (:iter ctx)
                                           :at       now
                                           :answered (answered-for (:label t) rounds)}))
                          (or (:cache ctx) (cache/read-cache project ws-id))
                          statuses)]
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
                                 :sweep       (boolean (:sweep r))
                                 :because     (or (:because r)
                                                  (when-not r "the warden did not rule on this finding"))})]
              (assoc merged :handle (resolve-handle handles merged))))
          findings)))

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

   THREE DIFFERENT THINGS END A ROUND HERE and they ask different things of
   whoever reads the report: the agent never ran (a 429, a crash, a budget
   spent), it ran and produced nothing, or it answered and the answer would not
   parse. Only the third is a claim about JSON.

   Collapsing them onto the parser's verdict is how a session limit came to be
   reported as `no json decision block` — a complaint about a block that was
   never going to exist, with the 429 in agent.log and nowhere a reader looks.
   `result-error?` was already computed and already right; it was the
   explanation that was thrown away."
  [{:keys [num-turns result-error? result-text]} decision]
  (cond
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
   long enough that the seam is not going to resolve itself and short enough
   that the human hears about it while the branch is still warm."
  4)

(defn ^{:malli/schema [:=> [:cat :any :any :int] :any]}
  carried-parks
  "The open parks this run is holding, each with the round it was first parked
   in — the previous round's carry, updated with this round's rulings.

   A park is by construction never fixed, so no later round raises it again and
   it vanishes from the findings the moment the reviewer stops mentioning it.
   That is why it needs carrying: the warden re-adjudicated the same seam from
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
                                :title (:title f)
                                :because (:because f)}))))
            prior'
            (filter #(= :park (:disposition %)) ruled))))

(defn- run-warden-stage
  [ctx]
  (let [{:keys [cwd run-id budget]} (:config ctx)
        handles (get-in ctx [:carry :handles] {})
        prompt (prompts/warden-prompt
                {:findings (:findings ctx)
                 :seen     (seen-findings (:history ctx))
                 :history  (mapv #(dissoc % :findings) (:history ctx))
                 :design   (discover-design-record cwd)
                 :stance   (read-stance (first (project+ws-from-cwd cwd)))
                 :toc      (:toc ctx)
                 :parked   (vals (get-in ctx [:carry :parks] {}))
                 :answered (answered-by-layer ctx)})
        {:keys [num-turns result-error? result-text] :as launch}
        (agent/launch! {:run-id run-id :cwd cwd
                        :first-message prompt :budget budget
                        :tools ""
                        :err-file (str (fs/path (cstate/run-dir run-id) "agent.err.log"))})
        decision (parse-warden-decision result-text)]
    (if (or (zero? (or num-turns 0)) result-error?
            (= :indeterminate (:decision decision)))
      (assoc ctx :warden (merge decision (warden-failure launch decision))
             :control :stop
             :status :warden-indeterminate)
      (let [ruled (apply-rulings (:findings ctx) (:rulings decision) handles)
            parks (carried-parks (get-in ctx [:carry :parks] {}) ruled (:iter ctx))
            ;; A park is a question put to a human, and a run that keeps fixing
            ;; around one is answering a different question. Once a park has
            ;; stood this long the loop has nothing further to offer it, and
            ;; stopping is the report — even while other findings are still
            ;; fixable, because those fixes are not what the branch is waiting
            ;; on. The warden said exactly this in its own prose and then
            ;; returned :continue, because there was no state between "keep
            ;; fixing" and "escalate".
            stale (seq (for [[k p] parks
                             :when (>= (inc (- (:iter ctx) (:since p)))
                                       park-persists-for)]
                         k))
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
                        :carry (assoc (:carry ctx)
                                      :handles (into handles
                                                     (map (juxt :id :handle))
                                                     ruled)
                                      :parks parks)
                        :control  (:decision decision))
            ctx' (if stale
                   (assoc ctx' :control :stop :status :unfixable
                          :unfixable (vec stale))
                   ctx')]
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

(defn ^{:malli/schema [:=> [:cat :any :Finding] :map]}
  reshape-plan
  "What to do about one finding whose remedy is the stack's shape — or, when
   nothing can be done about it here, which precondition failed.

   The layers a defect spans are read back in STACK order rather than in the
   order the finding happens to list them, so `lower` is the bottom-most named
   layer and `upper` the top-most whatever the reviewer wrote. For an
   order-dependence that pair is the whole instruction: the upper layer
   establishes what the lower one reaches for, so it belongs below it. For a
   seam or a duplication there is no order to correct — the boundary itself is
   the defect — so the two are folded into one.

   A FOLD additionally requires the named layers to be contiguous. A fold does
   not remove one boundary, it removes every boundary between the layers it
   spans, and each unnamed layer in between is absorbed with them — landing
   changes no reviewer implicated under a claim that never covered them. A seam
   reported across layers 2 and 9 of a nine-layer stack is not a request to
   collapse the stack; jj answers it with a conflict on everything in between,
   and the round has spent its one attempt on an operation that could not have
   applied.

   Where a SEAM's span has holes there is a smaller move than the fold: put the
   file the seam runs through in the lower layer, and absorb nothing. That was
   the repair both the reviewer and the warden named on the case this comes from
   — layer 9 rewriting a migration whose checksum layer 1's deploy had already
   recorded — while the stage had only the fold, and so only a refusal. Scoped
   to the seam kind: for a duplication, moving one copy down puts both in one
   layer without removing either, which is not what the finding asked for.

   Where the span has holes and there is no file to move, the right cut is a
   judgement, so this still says so and leaves it.

   Never nil. A stage that cannot act still has to say why: `:refused` with its
   reason is what the phase shows instead of the silence that let one finding be
   raised four rounds running with no record that anything was ever tried."
  [stack finding]
  (let [index  (into {} (map-indexed (fn [i l] [(layer-label l) i])) stack)
        named  (vec (sort (distinct (keep index (:layers finding)))))
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

          (and (= :misplaced-seam (:kind finding)) (not (str/blank? (str (:file finding)))))
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
  #{"refused" "unnamed-layers" "no-remedy" "span-has-holes" "already-attempted"})

(defn ^{:malli/schema [:=> [:cat :any :any :int] :any]}
  park-refused-recuts
  "Add a park for every recut this round could not act on, carrying the
   reshape's own words.

   A recut is withheld from the fixers on purpose — the warden rules it `recut`
   BECAUSE a patch on one side of a bad seam makes the seam permanent — so when
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
            done  (when pick (reshape! cwd base (second (nth plans pick))))]
        (when pick (layers/restore-top! cwd (session-stack cwd base)))
        (let [outcomes (vec (map-indexed
                             (fn [i [f p]]
                               (reshape-outcome
                                f p
                                (cond
                                  (= i pick)
                                  (if (:ok? done)
                                    {:outcome (name (:did done))}
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
            by     (group-by #(let [o (:owner-layer %)]
                                (if (contains? known o) o top))
                             to-fix)]
        (into []
              (keep (fn [layer]
                      (when-let [fs (seq (get by (layer-label layer)))]
                        {:label (layer-label layer) :layer layer :findings (vec fs)})))
              stack)))))

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

(defn- handed-ids
  "The findings a fix row is about, named rather than counted — the warden's
   handle where it assigned one, else the reviewer's id."
  [findings]
  (mapv (fn [f] (or (:handle f) (:id f))) findings))

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

(defn- run-fix-stage
  [ctx]
  (if (:dry-run? (:config ctx))
    (assoc ctx :control :stop :status :dry-run)
    (let [{:keys [cwd base run-id budget impl-session-id]} (:config ctx)
          stack (session-stack cwd base)
          plan  (fix-plan stack (:findings ctx))]
      (cond
        ;; The tree moved between the review and the repair. Every finding this
        ;; round holds was found in a state that is no longer what `@` means, so
        ;; landing fixes now writes them onto code nobody reviewed. Refusing
        ;; names both revisions, which is what makes it actionable instead of
        ;; the `fix-noop` this used to end as.
        (and (:reviewed-at ctx) (not (layers/descends-from? cwd (:reviewed-at ctx))))
        (assoc ctx :control :stop :status :workspace-drifted
               :drift {:reviewed-at (:reviewed-at ctx)
                       :now (layers/resolve-rev cwd "@")})

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
                       {:keys [num-turns result-text]}
                       (agent/launch!
                        {:run-id run-id :cwd cwd
                         :first-message (prompts/fix-prompt
                                         {:findings findings
                                          :layer (assoc (toc-row (:toc ctx) label)
                                                        :label label)})
                         :budget budget
                         :claude-session-id (layer-fixer-session impl-session-id label)
                         :resume? (fixed-before? (:history ctx) label)
                         :err-file (str (fs/path (cstate/run-dir run-id)
                                                 (format "fix-%s-round-%d.err.log"
                                                         (codex/safe-label label)
                                                         (or (:iter ctx) 1))))})]
                   (if (or (zero? (or num-turns 0)) (not (working-copy-dirty? cwd)))
                     ;; The fixer left the tree unchanged. That is a decision it
                     ;; made and explained, and the explanation was the only
                     ;; account of why a round did nothing — discarded here, so
                     ;; a run could end on "no changes" with the reason it
                     ;; declined stated nowhere. It is kept per layer, and
                     ;; distinguishes a fixer that never ran from one that read
                     ;; the finding and refused it.
                     (do (layers/restore-top! cwd stack)
                         (update acc :declined (fnil conj [])
                                 (cond-> {:layer label
                                          :ran? (pos? (or num-turns 0))}
                                   result-text (assoc :reason (str result-text)))))
                     (let [cid (layers/land-fix!
                                cwd layer
                                (str "review-loop: iter " (:iter ctx) " fixes"
                                     (when label (str " (" label ")"))))
                           _   (layers/restore-top! cwd stack)
                           fix {:layer label :commit cid
                                :handed handed
                                :fixed-count (count findings)}
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
                               (update acc :rolled-back (fnil conj [])
                                       {:layer label :handed handed
                                        :conflicted (vec bad)}))))))))
                 ctx (map-indexed vector plan)))
              ctx' (if (seq (:fixes ctx'))
                     (update ctx' :history (fnil conj [])
                             {:iter (:iter ctx')
                              :fixes (:fixes ctx')
                              :fixed-count (reduce + 0 (map :fixed-count (:fixes ctx')))
                              :findings (:findings ctx')
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

            ;; Every repair this round produced was refused by the stack and put
            ;; back, so the tree is exactly what the reviewers already read.
            ;; Distinct from :fix-declined, which is fixers reading the findings
            ;; and saying no: here they said yes and the rebase said no, and the
            ;; two want different things from whoever looks.
            (and (empty? (:fixes ctx')) (seq (:rolled-back ctx')))
            (assoc ctx' :control :stop :status :fix-rolled-back)

            (empty? (:fixes ctx'))
            (assoc ctx' :control :stop :status :fix-declined)

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
