;; src/nido/review/stages.clj
(ns nido.review.stages
  "The review loop's three stages (review/arbiter/fix) as {:name :run} maps,
   plus the arbiter-decision parser. Stages only transform the iteration
   context; the engine owns flow."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.session :as csession]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.core :as core]
   [nido.review.cache :as cache]
   [nido.review.codex :as codex]
   [nido.review.layers :as layers]
   [nido.review.prompts :as prompts]
   [nido.session.lifecycle :as lifecycle]
   [nido.vsdd.jj :as jj]))

(def ^:private fenced-json-re #"(?s)```json\s*(\{.*?\})\s*```")

(def ^:private dispositions #{:fix :closed :deviation :park})

(defn- ruling
  "One per-finding ruling from the arbiter's JSON. A disposition outside the
   four is read as :fix rather than dropped — the fail-safe direction, and the
   one that keeps \"nothing is dropped\" true of a malformed answer."
  [r]
  (let [d (some-> (:disposition r) keyword)]
    {:id          (:id r)
     :owner-layer (:owner_layer r)
     :disposition (if (contains? dispositions d) d :fix)
     :authority   (:authority r)
     :of          (:of r)
     :because     (:because r)}))

(defn parse-arbiter-decision
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

(defn review-targets
  "What this round reviews: one target per layer, bounded by that layer's brief,
   plus one over the whole stack.

   The whole-stack target is what finds a defect that exists only in the
   COMPOSITION of two layers — something no layer reviewer can see, since each
   one is shown a diff in which the other layer does not appear. It is therefore
   only worth running when there are at least two layers to compose; below that
   it is the same diff twice, and the stack target is the only one."
  [cwd base]
  (let [base-rev (codex/merge-base cwd base)
        stack    (session-stack cwd base)
        whole    {:label "stack" :from base-rev :to "@" :brief nil :stack? true}]
    (if (< (count stack) 2)
      [whole]
      (conj (mapv (fn [r] {:label (or (:slug r) (:bookmark r))
                           :layer r
                           :from  (:from r)
                           :to    (:to r)
                           :brief (layers/brief cwd (:tip r))})
                  (layers/ranges stack base-rev))
            whole))))

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
   layers, which is the arbiter's job."
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
  "The stack's table of contents — one entry per layer: what it claims and which
   files it touches. This is what a warden gets INSTEAD of the other layers'
   diffs, so it can attribute deliberately without re-deriving them."
  [results]
  (into []
        (comp (remove #(:stack? (:target %)))
              (map (fn [{:keys [target manifest]}]
                     {:label (:label target)
                      :claim (get-in target [:brief :claims])
                      :files (vec (remove str/blank?
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
            row      (fn [status t] {:label  (:label t)
                                     :stack? (boolean (:stack? t))
                                     :status status})]
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
                                                     :label (:label t) :brief (:brief t)})
                                                   :target t)]
                                      (announce-target! ctx "reviewed" t
                                                        {:findings (count (:findings r))})
                                      r)))
                               targets))
                 whole    (or (first (filter #(:stack? (:target %)) results))
                              (first results))
                 findings (collect-findings results)]
             (if (empty? findings)
               (assoc ctx :findings [] :reviews results :skipped skipped
                      :control :stop :status :clean)
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
   one. A workstream may survey more than once, and the design committed to a
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

(defn read-stance
  "The project's stance text, from nido's own tree, capped so it can't blow up the
   arbiter prompt. Read from the source dir rather than cwd: the review runs in the
   worktree, and the stance ships with the /design skill in nido's `.claude`, which
   the worktree does not carry. Missing or unreadable degrades to nil — a headless
   review must not die for want of framing."
  [project]
  (when project
    (try
      (let [f (fs/path (core/nido-source-dir) ".claude" "skills" "design"
                       "stances" (str (name project) ".md"))]
        (when (fs/exists? f)
          (let [s (slurp (str f))]
            (if (> (count s) stance-char-cap)
              (str (subs s 0 stance-char-cap) "\n\n…[stance truncated]")
              s))))
      (catch Throwable _ nil))))

(defn parse-dispositions
  "Last fenced ```json block -> the warden's dispositions. An unparseable or
   absent answer is [], which the arbiter reads as \"this layer's warden said
   nothing\" — it still rules on every finding itself, so a mute warden costs
   bounded judgement, never a dropped finding."
  [text]
  (let [block (when (string? text) (last (re-seq fenced-json-re text)))]
    (if-let [body (second block)]
      (try
        (->> (:dispositions (json/parse-string body true))
             (keep (fn [d]
                     (when (:id d)
                       {:id          (:id d)
                        :disposition (some-> (:disposition d) keyword)
                        :authority   (:authority d)
                        :owner-guess (:owner_guess d)
                        :because     (:because d)})))
             vec)
        (catch Exception _ []))
      [])))

(defn- target-for
  [ctx label]
  (some #(when (= label (:label (:target %))) (:target %)) (:reviews ctx)))

(def ^:private max-concurrent-wardens 6)

(def warden-stage
  "One warden per layer that has findings, in parallel.

   A warden is bounded: it holds its own layer's brief and findings, plus the
   stack's table of contents as a MAP. It disposes of what its brief answers and
   escalates the rest — where escalate means \"above my pay grade\", not \"the
   design is in question\". Report-only, like the arbiter, and fresh each round:
   its whole input is in the prompt.

   Findings from the whole-stack pass have no warden — they belong to no single
   layer by construction, so they go straight to the arbiter."
  {:name :warden
   :run  (fn [ctx]
           (let [{:keys [cwd run-id budget]} (:config ctx)
                 by-layer (group-by :from-layer (:findings ctx))
                 labels   (remove #(or (nil? %) (= "stack" %)) (keys by-layer))]
             (if (empty? labels)
               (assoc ctx :dispositions [])
               (let [results
                     (in-parallel
                      max-concurrent-wardens
                      (map (fn [label]
                             #(let [t (target-for ctx label)
                                    prompt (prompts/warden-prompt
                                            {:layer    label
                                             :findings (get by-layer label)
                                             :toc      (:toc ctx)
                                             :brief    (:brief t)
                                             :answered (cache/answered (:cache ctx)
                                                                       (:patch-hash t))})
                                    {:keys [result-text]}
                                    (agent/launch!
                                     {:run-id run-id :cwd cwd
                                      :first-message prompt :budget budget
                                      :tools ""
                                      :err-file (str (fs/path (cstate/run-dir run-id)
                                                              (format "warden-%s-round-%d.err.log"
                                                                      (codex/safe-label label)
                                                                      (or (:iter ctx) 1))))})]
                                (parse-dispositions result-text)))
                           labels))]
                 (assoc ctx :dispositions (vec (mapcat identity results)))))))})

(defn converged-targets
  "Pure: the targets this round left with nothing to fix, paired with the patch
   they were reviewed at.

   A target converged when no :fix finding is OWNED by it — not when none was
   reported by it. A finding an upper layer reported but a lower one owns leaves
   the upper layer converged, correctly: nothing about it needs changing. The
   whole-stack target converges only when nothing anywhere needs fixing, and any
   fix that does land changes the patch of every layer above it, so its hash
   stops matching on its own."
  [reviews findings]
  (let [owners (into #{} (comp (filter #(= :fix (:disposition %)))
                               (map :owner-layer))
                     findings)]
    (into []
          (comp (map :target)
                (filter (fn [t]
                          (and (:patch-hash t)
                               (if (:stack? t)
                                 (empty? owners)
                                 (not (contains? owners (:label t))))))))
          reviews)))

(defn answered-for
  "What this target reported and the arbiter closed. Carried forward under the
   patch hash so next round's fresh reviewer, reporting the same thing, gets
   answered rather than re-adjudicated."
  [label findings]
  (into []
        (comp (filter #(and (= label (:from-layer %)) (= :closed (:disposition %))))
              (map #(select-keys % [:id :title :authority :because])))
        findings))

(defn record-convergence!
  "Write what converged this round into the workstream's cache.

   This lives in the arbiter stage rather than in a stage of its own because the
   engine short-circuits the moment the arbiter says stop — and a round that
   stops because nothing needs fixing is exactly the round whose convergence is
   worth remembering. Best-effort: a cache that cannot be written costs the next
   run some duplicated review and nothing else."
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

(defn apply-rulings
  "Merge the arbiter's per-finding rulings onto the findings.

   A finding the arbiter did not rule on defaults to :fix. That is the fail-safe
   direction and it is what keeps \"nothing is dropped\" true of a malformed
   answer: an omitted finding is worked on, never silently discarded."
  [findings rulings]
  (let [by-id (into {} (map (juxt :id identity)) rulings)]
    (mapv (fn [f]
            (let [r (get by-id (:id f))]
              (merge f
                     {:owner-layer (:owner-layer r)
                      :disposition (or (:disposition r) :fix)
                      :authority   (:authority r)
                      :of          (:of r)
                      :because     (or (:because r)
                                       (when-not r "the arbiter did not rule on this finding"))})))
          findings)))

(def arbiter-stage
  "The one reader with a view across layers, so attribution is its job.

   Report-only and fully inlined, deliberately: it is the component that decides
   to interrupt a human, and its inputs have to be reconstructable from the
   report afterwards. What genuinely accumulates across rounds is carried as an
   inspectable value (history, dispositions), never as a resumed conversation."
  {:name :arbiter
   :run  (fn [ctx]
           (let [{:keys [cwd run-id budget]} (:config ctx)
                 prompt (prompts/arbiter-prompt
                         {:findings     (:findings ctx)
                          :history      (mapv #(dissoc % :findings) (:history ctx))
                          :design       (discover-design-record cwd)
                          :stance       (read-stance (first (project+ws-from-cwd cwd)))
                          :toc          (:toc ctx)
                          :dispositions (:dispositions ctx)})
                 {:keys [num-turns result-error? result-text]}
                 (agent/launch! {:run-id run-id :cwd cwd
                                 :first-message prompt :budget budget
                                 :tools ""
                                 :err-file (str (fs/path (cstate/run-dir run-id) "agent.err.log"))})
                 decision (parse-arbiter-decision result-text)]
             (if (or (zero? (or num-turns 0)) result-error?
                     (= :indeterminate (:decision decision)))
               (assoc ctx :arbiter decision :control :stop
                      :status :arbiter-indeterminate)
               (let [ctx' (assoc ctx
                                 :arbiter  decision
                                 :findings (apply-rulings (:findings ctx) (:rulings decision))
                                 :control  (:decision decision))]
                 (record-convergence! cwd ctx')
                 ctx'))))})

(defn working-copy-dirty?
  "True when jj reports working-copy changes in cwd."
  [cwd]
  (not (str/blank? (:out (jj/jj! cwd "diff" "--git")))))

(defn layer-label
  [layer]
  (or (:slug layer) (:bookmark layer)))

(defn fix-plan
  "Findings the arbiter dispositioned :fix, grouped by the layer that OWNS them,
   ordered bottom→top.

   Bottom-up is what makes the fixes cheap rather than what makes them correct:
   landing a fix on a lower layer rewrites every layer above it, so doing the
   lower one first means the upper fixer works against code that will not move
   under it again this round.

   A finding whose owner_layer names no layer of this stack falls to the top
   layer — the same assign-to-highest rule the arbiter is told to use for a
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
                       (reduce
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
                        ctx plan)]
                   (if (empty? (:fixes ctx'))
                     (assoc ctx' :control :stop :status :fix-noop)
                     (update ctx' :history (fnil conj [])
                             {:iter (:iter ctx')
                              :fixes (:fixes ctx')
                              :fixed-count (reduce + 0 (map :fixed-count (:fixes ctx')))
                              :findings (:findings ctx')
                              :arbiter (:arbiter ctx')})))))))})
