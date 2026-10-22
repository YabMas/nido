;; src/nido/review/probe.clj
(ns nido.review.probe
  "Re-judge recorded record rounds under prompt variants, on matched conditions, to measure what a
   change to a judge's prompt does before it lands.

   `replay` answers what a stopping-rule change does, because a loop's stops are code and the
   recorded rounds are their inputs. A prompt change cannot be replayed: its effect is on the
   judge, and only judging again shows it. Every record round's prompt and schema are on disk
   verbatim (`<label>-prompt.txt`, `<label>-schema.json`), so a variant is a text edit to a prompt
   a real round was shown, and a probe is that edit judged against the same code as the unedited
   prompt.

   Matched conditions are the point. The code a round originally read has usually moved on, so a
   probe does not compare a variant with the recorded verdict. Each round's code is frozen once —
   a jj workspace at the worktree's current commit, through `tree/with-reading!` — and the control
   and every variant are judged against that one tree, by the same reviewer. The control is judged
   more than once, because a judge is stochastic: a variant has an effect only where it moves
   further from the control than the control moves from itself (`summary`'s noise row). A run a
   stand-in answered (codex out of quota, claude in its place) is kept but marked, and left out of
   the comparison — it measures the judge, not the prompt.

   What is measured is read off the raw answers (`metrics`): the recommendation, each check's
   status, the findings and which check each falls under, what was confirmed and left unchecked,
   and time and tokens. Whether a finding is about the design or about implementation detail is a
   judgement, made by a grader that reads every run's findings for a round together, unlabelled
   and shuffled, so it cannot tell which variant produced which (`grade!`).

   Writes only under `~/.nido/probes/<probe-id>/`; never appends to a ledger."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.record.state :as cstate]
   [nido.review.codex :as codex]
   [nido.review.prompt-shape :as shape]
   [nido.review.tree :as tree]
   [nido.vsdd.jj :as jj])
  (:import
   [java.util.concurrent Executors TimeUnit]))

;; ── Variants: a text edit to a recorded prompt ─────────────────────────────────

(defn- stance-tail
  "What a stance section carries after the stance itself — the level reminder a baseline prompt
   closes on — which dropping or replacing the stance keeps."
  [text]
  (when-let [i (str/index-of text "\n---\n")] (subs text i)))

(defn- edit-section
  "`prompt` with each section headed `heading` passed through `f` (text -> text or nil to drop)."
  [prompt heading f]
  (->> (shape/sections prompt)
       (keep (fn [{h :heading text :text}]
               (if (str/starts-with? h heading) (f text) text)))
       (str/join "\n")))

(defn- add-check
  "`schema` (JSON text) offering `check` beside the checks it already offers."
  [schema check]
  (let [s (json/parse-string schema true)]
    (json/generate-string
     (-> s
         (update-in [:properties :checks :items :properties :check :enum]
                    #(vec (distinct (conj (vec %) check))))
         (update-in [:properties :findings :items :properties :check :enum]
                    #(let [v (vec (remove #{""} %))] (conj (vec (distinct (conj v check))) "")))))))

(defn ^{:malli/schema [:=> [:cat [:map [:prompt :string] [:schema :string]] :map]
                     [:map [:prompt :string] [:schema :string]]]}
  apply-variant
  "A round's `{:prompt :schema}` under `variant`, whose `:edits` are applied in order:

     {:drop <heading>}                   every section so headed removed
     {:replace <heading> :text <t>}      every section so headed replaced by `t`
     {:prepend <t>}                      `t` before the prompt's first heading
     {:insert-before <marker> :text <t>} `t` before the first `marker`; the edit fails without one
     {:substitute <old> :text <new>}      the first `old` replaced by `new`
     {:add-check <name>}                 the schema offers `name` as a check

   A stance section dropped or replaced keeps the level reminder after it (`stance-tail`). An edit
   that cannot be made throws: a variant that silently judged the control's prompt would measure
   nothing and report it as an effect."
  [{:keys [prompt schema]} variant]
  (reduce
   (fn [{:keys [prompt schema] :as r} e]
     (cond
       (:drop e)
       (let [p (edit-section prompt (:drop e) #(some-> (stance-tail %) (subs 1)))]
         (when (= p prompt) (throw (ex-info (str "no section " (:drop e)) {:edit e})))
         (assoc r :prompt p))

       (:replace e)
       (let [p (edit-section prompt (:replace e) #(str (str/trimr (:text e)) "\n" (stance-tail %)))]
         (when (= p prompt) (throw (ex-info (str "no section " (:replace e)) {:edit e})))
         (assoc r :prompt p))

       (:prepend e)
       (let [secs (shape/sections prompt)
             [pre rest-secs] (if (= "(preamble)" (:heading (first secs))) [[(first secs)] (rest secs)] [[] secs])]
         (assoc r :prompt (str/join "\n" (concat (map :text pre) [(str/trimr (:prepend e))] (map :text rest-secs)))))

       (:insert-before e)
       (let [i (str/index-of prompt (:insert-before e))]
         (when-not i (throw (ex-info (str "no marker " (:insert-before e)) {:edit e})))
         (assoc r :prompt (str (subs prompt 0 i) (:text e) (subs prompt i))))

       (:substitute e)
       (let [i (str/index-of prompt (:substitute e))]
         (when-not i (throw (ex-info (str "no text " (:substitute e)) {:edit e})))
         (assoc r :prompt (str (subs prompt 0 i) (:text e) (subs prompt (+ i (count (:substitute e)))))))

       (:add-check e)
       (assoc r :schema (add-check schema (:add-check e)))

       :else (throw (ex-info "unknown edit" {:edit e}))))
   {:prompt prompt :schema schema}
   (:edits variant)))

;; ── What an answer says ───────────────────────────────────────────────────────

(defn- tokens-used
  "The token count codex prints after `tokens used`, or claude's usage on its result line; nil when
   the log has neither."
  [log]
  (or (some->> (re-seq #"(?m)^tokens used\s*\n([\d,]+)" log) last second (re-find #"[\d,]+")
               (#(str/replace % "," "")) parse-long)
      (some->> (str/split-lines log) (filter #(str/includes? % "\"type\":\"result\"")) last
               (#(try (json/parse-string % true) (catch Exception _ nil))) :usage
               ((juxt :input_tokens :output_tokens :cache_read_input_tokens :cache_creation_input_tokens))
               (remove nil?) seq (reduce +))))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] [:maybe :map]]}
  metrics
  "What a design-decision or baseline-review answer (its JSON text) says, as the probe compares it:
   `{:recommend :checks {check status} :findings [{:id :check :claim-id :claim}] :confirmed n
   :unchecked n}`; nil for an answer that is not JSON."
  [answer]
  (when-let [a (try (json/parse-string answer true) (catch Exception _ nil))]
    {:recommend (or (:recommend a) (:verdict a))
     :checks    (into (sorted-map) (map (juxt :check :status)) (:checks a))
     :findings  (vec (map-indexed (fn [i f] {:id i :check (:check f) :claim-id (:claim-id f)
                                             :claim (:claim f)})
                                  (:findings a)))
     :confirmed (count (:confirmed a))
     :unchecked (count (:unchecked a))}))

;; ── Running ───────────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat] :string]}
  probes-dir
  "Where probes are written."
  []
  (str (fs/path (cstate/nido-root) "probes")))

(defn- worktree-commit
  "The commit `worktree`'s @ was at when jj last snapshotted it, read without snapshotting: the
   worktree is someone's live session."
  [worktree]
  (let [{:keys [exit out]} (jj/jj! worktree "--ignore-working-copy" "log" "-r" "@" "--no-graph"
                                   "-T" "commit_id")]
    (when (zero? (long exit)) (str/trim out))))

(defn- run-one!
  "Judge `job` — one round under one variant, one repetition — in `cwd`, writing under `dir`."
  [{:keys [reviewer]} cwd {:keys [prompt schema dir]}]
  (fs/create-dirs dir)
  (let [schema-path (str (fs/path dir "schema.json"))
        out-path    (str (fs/path dir "out.json"))
        log-path    (str (fs/path dir "judge.log"))
        _           (spit schema-path schema)
        _           (spit (str (fs/path dir "prompt.txt")) prompt)
        t0          (System/currentTimeMillis)
        res         (try (codex/run-reviewer! {:reviewer reviewer :cwd cwd :schema-path schema-path
                                               :out-path out-path :log-path log-path :prompt prompt})
                         (catch Throwable t {:exit -1 :error (ex-message t)}))
        answer      (when (fs/exists? out-path) (slurp out-path))
        ran-log     (or (:log-path res) log-path)
        result      {:exit      (:exit res)
                     :error     (:error res)
                     :judged-by (:judged-by res)
                     :stand-in? (boolean (get-in res [:judged-by :instead-of]))
                     :minutes   (/ (- (System/currentTimeMillis) t0) 60000.0)
                     :tokens    (when (fs/exists? ran-log) (tokens-used (slurp ran-log)))
                     :metrics   (metrics answer)}]
    (spit (str (fs/path dir "result.edn")) (pr-str result))
    result))

(defn- in-parallel
  "Each of `thunks` run with at most `n` at once; their answers in order."
  [n thunks]
  (let [pool (Executors/newFixedThreadPool (max 1 n))]
    (try
      (let [fs (mapv #(.submit pool ^Callable %) thunks)]
        (mapv #(.get ^java.util.concurrent.Future %) fs))
      (finally (.shutdown pool) (.awaitTermination pool 1 TimeUnit/MINUTES)))))

(defn ^{:malli/schema [:=> [:cat :map :int :map] [:sequential :map]]}
  jobs
  "Every (round × variant × repetition) of `plan` for round `i`, with its prompt and schema built —
   what `probe!` judges, and what a dry run shows without judging."
  [{:keys [variants reps probe-dir]} i {:keys [prompt schema] :as rd}]
  (let [base {:prompt (slurp prompt) :schema (slurp schema)}]
    (for [{vname :name :as v} variants
          rep (range (get reps (keyword vname) (get reps :default 1)))]
      (merge (apply-variant base v)
             {:round i :round-file prompt :variant vname :rep rep :worktree (:worktree rd)
              :dir (str (fs/path probe-dir (str "round-" i) (str vname "-" rep)))}))))

(defn ^{:malli/schema [:=> [:cat :map] [:vector :map]]}
  probe!
  "Run `plan` and answer every job's result, each beside its round, variant and repetition. Each
   round's worktree is frozen once at its current commit (`tree/with-reading!`), all of its jobs
   are judged against that tree, and the tree is removed after. `plan`:

     {:probe-id <str> :reviewer :codex :parallel 4
      :reps     {:control 2 :default 1}
      :rounds   [{:prompt <path to a recorded *-prompt.txt> :schema <its *-schema.json>
                  :worktree <the reviewed worktree>} …]
      :variants [{:name \"control\" :edits []} {:name \"no-stance\" :edits [{:drop \"PROJECT STANCE\"}]} …]}"
  [{:keys [probe-id parallel rounds] :as plan}]
  (let [probe-dir (str (fs/path (probes-dir) probe-id))
        plan      (assoc plan :probe-dir probe-dir)]
    (fs/create-dirs probe-dir)
    (spit (str (fs/path probe-dir "plan.edn")) (pr-str (dissoc plan :probe-dir)))
    (vec
     (mapcat
      (fn [i rd]
        (let [js     (jobs plan i rd)
              commit (worktree-commit (:worktree rd))
              _      (when-not commit (throw (ex-info (str "no commit for " (:worktree rd)) {:round rd})))
              tree   (str (fs/path probe-dir (str "round-" i) "tree"))]
          (spit (str (fs/path probe-dir (str "round-" i ".edn")))
                (pr-str (assoc rd :commit commit)))
          (tree/with-reading! (:worktree rd) {:rev commit :overlay []}
                              (str "probe-" probe-id "-" i) tree
                              (fn [cwd]
                                (in-parallel (or parallel 4)
                                             (for [j js]
                                               #(merge (select-keys j [:round :variant :rep :dir])
                                                       (run-one! plan cwd j))))))))
      (range) rounds))))

;; ── Grading: is a finding about the design, or about its implementation? ─────

(def ^:private grade-schema
  {:type "object" :additionalProperties false :required ["labels"]
   :properties
   {:labels {:type "array"
             :items {:type "object" :additionalProperties false :required ["id" "level" "why"]
                     :properties {:id    {:type "string"}
                                  :level {:type "string" :enum ["design" "implementation"]}
                                  :why   {:type "string"}}}}}})

(defn- grade-prompt [findings]
  (str "You are grading review findings, not reviewing anything yourself. Each finding below was\n"
       "made by a judge reviewing a DESIGN RECORD before any code for it was written.\n\n"
       "Label each one by the level it argues at:\n"
       "  design         — about what the design commits to: a boundary and what it hides, the\n"
       "                   shape of the data and the states it admits, a level and its\n"
       "                   vocabulary, whether the goal needs this much, a claim's truth.\n"
       "  implementation — about how it would be coded: a line's behaviour, a name, a missing\n"
       "                   guard, an API detail a later code review would own.\n"
       "A finding that cites code is still `design` when its argument is about the commitment.\n"
       "Give each label its finding's id and one short reason. Do not read any files.\n\n"
       (str/join "\n\n" (for [{:keys [gid claim check]} findings]
                          (str "[" gid "] (" (if (str/blank? (str check)) "no check" check) ") " claim)))
       "\n"))

(defn ^{:malli/schema [:=> [:cat :string [:vector :map] [:? :map]] [:vector :map]]}
  grade!
  "`results` with each finding labelled :design or :implementation. One grader call per round reads
   that round's findings from every run together, shuffled under opaque ids, so no label can follow
   the variant that produced it."
  ([probe-id results] (grade! probe-id results {}))
  ([probe-id results {:keys [reviewer] :or {reviewer :codex}}]
   (let [dir    (fs/path (probes-dir) probe-id "grade")
         labels (into {}
                      (for [[round rs] (group-by :round results)
                            :let [fs (shuffle (for [r rs f (get-in r [:metrics :findings])]
                                                (assoc f :gid (str (random-uuid)) :at [(:variant r) (:rep r) (:id f)])))]
                            :when (seq fs)
                            :let [rd     (fs/path dir (str "round-" round))
                                  _      (fs/create-dirs rd)
                                  sp     (str (fs/path rd "schema.json"))
                                  op     (str (fs/path rd "out.json"))
                                  _      (spit sp (json/generate-string grade-schema))
                                  _      (codex/run-reviewer! {:reviewer reviewer :cwd (str rd) :schema-path sp
                                                               :out-path op :log-path (str (fs/path rd "grade.log"))
                                                               :prompt (grade-prompt fs)})
                                  by-gid (into {} (map (juxt :gid :at)) fs)
                                  out    (try (json/parse-string (slurp op) true) (catch Exception _ nil))]
                            {:keys [id level]} (:labels out)
                            :let [at (by-gid id)] :when at]
                        [[round at] (keyword level)]))]
     (spit (str (fs/path dir "labels.edn")) (pr-str labels))
     (mapv (fn [r]
             (update-in r [:metrics :findings]
                        (partial mapv #(assoc % :level (labels [(:round r) [(:variant r) (:rep r) (:id %)]])))))
           results))))

;; ── Comparing ─────────────────────────────────────────────────────────────────

(defn- agreement
  "How far run `b` agrees with run `a`: whether they recommend the same, and the share of the checks
   either ruled on that both ruled the same."
  [a b]
  (let [ca (get-in a [:metrics :checks]) cb (get-in b [:metrics :checks])
        ks (into (set (keys ca)) (keys cb))]
    {:same-recommend? (= (get-in a [:metrics :recommend]) (get-in b [:metrics :recommend]))
     :check-agreement (when (seq ks) (/ (count (filter #(= (ca %) (cb %)) ks)) (double (count ks))))}))

(defn- mean [xs] (let [xs (remove nil? xs)] (when (seq xs) (/ (reduce + xs) (double (count xs))))))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  summary
  "One row per variant, over the runs a stand-in did not answer: its runs, how often it agreed with
   the round's first control run on the recommendation and on each check, its mean findings, the
   share of graded findings at the design level, and its mean minutes and tokens. The `control`
   row's agreement is its later repetitions against its first — the noise: a variant's effect
   smaller than it is no effect."
  [results]
  (let [ok      (filter #(and (:metrics %) (not (:stand-in? %))) results)
        anchor  (into {} (for [r ok :when (and (= "control" (:variant r)) (zero? (:rep r)))] [(:round r) r]))
        row     (fn [label rs compared]
                  (let [agr (keep #(when-let [a (anchor (:round %))] (agreement a %)) compared)
                        fs  (mapcat #(get-in % [:metrics :findings]) rs)
                        lv  (keep :level fs)]
                    {:variant         label
                     :runs            (count rs)
                     :same-recommend  (mean (map #(if (:same-recommend? %) 1.0 0.0) agr))
                     :check-agreement (mean (map :check-agreement agr))
                     :findings        (mean (map #(count (get-in % [:metrics :findings])) rs))
                     :design-level    (when (seq lv) (/ (count (filter #{:design} lv)) (double (count lv))))
                     :minutes         (mean (map :minutes rs))
                     :tokens          (mean (map :tokens rs))}))]
    (into [(let [cs (filter #(= "control" (:variant %)) ok)]
             (row "control" cs (filter #(pos? (:rep %)) cs)))]
          (for [[v rs] (sort-by key (group-by :variant ok)) :when (not= "control" v)]
            (row v rs rs)))))
