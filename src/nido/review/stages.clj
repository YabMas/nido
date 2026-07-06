;; src/nido/review/stages.clj
(ns nido.review.stages
  "The review loop's three stages (review/judge/fix) as {:name :run} maps,
   plus the judge-decision parser. Stages only transform the iteration
   context; the engine owns flow."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.state :as cstate]
   [nido.review.codex :as codex]
   [nido.review.prompts :as prompts]
   [nido.vsdd.jj :as jj]))

(def ^:private fenced-json-re #"(?s)```json\s*(\{.*?\})\s*```")

(defn parse-judge-decision
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

(def review-stage
  {:name :review
   :run  (fn [ctx]
           (let [{:keys [cwd base run-id]} (:config ctx)
                 res (codex/review! {:cwd cwd :base base :run-id run-id :iter (:iter ctx)})]
             (if (= :clean (:status res))
               (assoc ctx :findings [] :control :stop :status :clean)
               (assoc ctx
                      :findings (:findings res)
                      :overall-correctness (:overall-correctness res)
                      :base-rev (:base-rev res)
                      :manifest (:manifest res)))))})

(defn discover-design-doc
  "Newest docs/superpowers/specs/*-design.md under cwd, or nil."
  [cwd]
  (let [dir (fs/path cwd "docs" "superpowers" "specs")]
    (when (fs/exists? dir)
      (some->> (fs/glob dir "*-design.md")
               seq
               (sort-by #(str (fs/file-name %)))
               last
               str))))

(def ^:private design-doc-char-cap 12000)

(defn- read-design-doc
  "Slurp a design-doc path, capped so it can't blow up the judge prompt.
   nil path -> nil. Read failures degrade to nil rather than aborting the
   (headless) review run — a missing design doc is recoverable, a crash isn't."
  [path]
  (when path
    (try
      (let [s (slurp path)]
        (if (> (count s) design-doc-char-cap)
          (str (subs s 0 design-doc-char-cap) "\n\n…[design doc truncated]")
          s))
      (catch java.io.IOException _ nil))))

(def judge-stage
  {:name :judge
   :run  (fn [ctx]
           (let [{:keys [cwd run-id budget]} (:config ctx)
                 prompt (prompts/judge-prompt
                         {:findings (:findings ctx)
                          :history (mapv #(dissoc % :findings) (:history ctx))
                          :design-doc-content (read-design-doc (discover-design-doc cwd))})
                 {:keys [num-turns result-error? result-text]}
                 (agent/launch! {:run-id run-id :cwd cwd
                                 :first-message prompt :budget budget
                                 :tools ""
                                 :err-file (str (fs/path (cstate/run-dir run-id) "agent.err.log"))})
                 decision (parse-judge-decision result-text)]
             (if (or (zero? (or num-turns 0)) result-error?
                     (= :indeterminate (:decision decision)))
               (assoc ctx :judge decision :control :stop
                      :status :judge-indeterminate)
               (assoc ctx :judge decision :control (:decision decision)))))})

(defn working-copy-dirty?
  "True when jj reports working-copy changes in cwd."
  [cwd]
  (not (str/blank? (:out (jj/jj! cwd "diff" "--git")))))

(defn- select-findings
  [findings idxs]
  (if (seq idxs) (into [] (keep #(nth findings % nil)) idxs) findings))

(def fix-stage
  {:name :fix
   :run  (fn [ctx]
           (if (:dry-run? (:config ctx))
             (assoc ctx :control :stop :status :dry-run)
             (let [{:keys [cwd run-id budget impl-session-id]} (:config ctx)
                   resume? (boolean (seq (:history ctx)))
                   to-fix (select-findings (:findings ctx)
                                           (-> ctx :judge :fix-findings))
                   {:keys [num-turns]}
                   (agent/launch! {:run-id run-id :cwd cwd
                                   :first-message (prompts/fix-prompt {:findings to-fix})
                                   :budget budget
                                   :claude-session-id impl-session-id
                                   :resume? resume?
                                   :err-file (str (fs/path (cstate/run-dir run-id) "agent.err.log"))})]
               (if (or (zero? (or num-turns 0)) (not (working-copy-dirty? cwd)))
                 (assoc ctx :control :stop :status :fix-noop)
                 (let [msg (str "review-loop: iter " (:iter ctx) " fixes")
                       _   (jj/jj! cwd "commit" "-m" msg)
                       cid (:out (jj/jj! cwd "log" "-r" "@-" "-T" "commit_id" "--no-graph"))]
                   (update ctx :history (fnil conj [])
                           {:iter (:iter ctx) :commit cid :fixed-count (count to-fix)
                            :findings (:findings ctx) :judge (:judge ctx)}))))))})

