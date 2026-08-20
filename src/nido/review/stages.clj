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
   [nido.review.codex :as codex]
   [nido.review.layers :as layers]
   [nido.review.prompts :as prompts]
   [nido.session.lifecycle :as lifecycle]
   [nido.vsdd.jj :as jj]))

(def ^:private fenced-json-re #"(?s)```json\s*(\{.*?\})\s*```")

(defn parse-arbiter-decision
  "Last fenced ```json block in `text` -> decision map. Unparseable -> indeterminate."
  [text]
  (let [block (when (string? text) (last (re-seq fenced-json-re text)))]
    (if-let [body (second block)]
      (try
        (let [m (json/parse-string body true)
              d (keyword (:decision m))]
          (if (contains? #{:continue :stop :escalate} d)
            {:decision d :reason (:reason m) :fix-findings (:fix_findings m)}
            {:decision :indeterminate :reason (str "unknown decision: " (:decision m))}))
        (catch Exception e
          {:decision :indeterminate :reason (str "unparseable: " (ex-message e))}))
      {:decision :indeterminate :reason "no json decision block"})))

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

(def review-stage
  "Every layer and the whole stack, reviewed in one round.

   Reviews are read-only and independent — that independence is exactly what a
   layer's `Out of scope` buys — so they fan out in parallel. Nothing here
   touches the working copy, and file content is read at each target's own
   revision, so concurrent reviews cannot see each other's state."
  {:name :review
   :run  (fn [ctx]
           (let [{:keys [cwd base run-id]} (:config ctx)
                 targets (review-targets cwd base)
                 results (in-parallel
                          max-concurrent-reviews
                          (map (fn [t]
                                 #(assoc (codex/review!
                                          {:cwd cwd :run-id run-id :iter (:iter ctx)
                                           :from (:from t) :to (:to t)
                                           :label (:label t) :brief (:brief t)})
                                         :target t))
                               targets))
                 whole    (or (first (filter #(:stack? (:target %)) results))
                              (first results))
                 findings (collect-findings results)]
             (if (empty? findings)
               (assoc ctx :findings [] :reviews results :control :stop :status :clean)
               (assoc ctx
                      :findings findings
                      :reviews results
                      :overall-correctness (:overall-correctness whole)
                      :base-rev (:base-rev whole)
                      :manifest (:manifest whole)))))})

(defn project+ws-from-cwd
  "Resolve cwd → [project ws-id] via the session, or nil. The same path
   tasks.nido-review/append-review-entry! takes to find where to write."
  [cwd]
  (try
    (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
      (when-let [ws-id (csession/workstream-id-for (keyword project) session)]
        [(keyword project) ws-id]))
    (catch Throwable _ nil)))

(defn discover-design-record
  "This workstream's latest :design record, or nil.

   Replaces a glob for the newest `docs/superpowers/specs/*-design.md`, which
   picked a file by filename order — in a project with a specs directory that is
   almost never the design of the change under review. The yardstick has to be the
   design *this* change committed to, and the ledger is where that lives."
  [cwd]
  (when-let [[project ws-id] (project+ws-from-cwd cwd)]
    (ws/latest-entry project ws-id :design)))

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

(def arbiter-stage
  {:name :arbiter
   :run  (fn [ctx]
           (let [{:keys [cwd run-id budget]} (:config ctx)
                 prompt (prompts/arbiter-prompt
                         {:findings (:findings ctx)
                          :history (mapv #(dissoc % :findings) (:history ctx))
                          :design  (discover-design-record cwd)
                          :stance  (read-stance (first (project+ws-from-cwd cwd)))})
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
               (assoc ctx :arbiter decision :control (:decision decision)))))})

(defn working-copy-dirty?
  "True when jj reports working-copy changes in cwd."
  [cwd]
  (not (str/blank? (:out (jj/jj! cwd "diff" "--git")))))

(defn- select-findings
  [findings idxs]
  (if (seq idxs) (into [] (keep #(nth findings % nil)) idxs) findings))

(def fix-stage
  "Fixes land INSIDE the stack, on the layer that owns them.

   The target is the top layer — the flat review covers the whole stack, so with
   no per-finding attribution yet the highest layer is the only defensible
   owner, and it is where a composition defect actually first exists.

   Committing at `@` (what this did before there were layers) put the fix ABOVE
   the top layer's bookmark, outside every layer's `<lower>..<bookmark>` fold
   range — so `/squash` never folded it and no PR ever saw it."
  {:name :fix
   :run  (fn [ctx]
           (if (:dry-run? (:config ctx))
             (assoc ctx :control :stop :status :dry-run)
             (let [{:keys [cwd base run-id budget impl-session-id]} (:config ctx)
                   stack   (session-stack cwd base)
                   target  (last stack)
                   _       (layers/position-for-fix! cwd target)
                   resume? (boolean (seq (:history ctx)))
                   to-fix (select-findings (:findings ctx)
                                           (-> ctx :arbiter :fix-findings))
                   {:keys [num-turns]}
                   (agent/launch! {:run-id run-id :cwd cwd
                                   :first-message (prompts/fix-prompt {:findings to-fix})
                                   :budget budget
                                   :claude-session-id impl-session-id
                                   :resume? resume?
                                   :err-file (str (fs/path (cstate/run-dir run-id) "agent.err.log"))})]
               (if (or (zero? (or num-turns 0)) (not (working-copy-dirty? cwd)))
                 (do (layers/restore-top! cwd stack)
                     (assoc ctx :control :stop :status :fix-noop))
                 (let [msg (str "review-loop: iter " (:iter ctx) " fixes")
                       cid (layers/land-fix! cwd target msg)]
                   (layers/restore-top! cwd stack)
                   (update ctx :history (fnil conj [])
                           {:iter (:iter ctx) :commit cid :fixed-count (count to-fix)
                            :layer (:bookmark target)
                            :findings (:findings ctx) :arbiter (:arbiter ctx)}))))))})

