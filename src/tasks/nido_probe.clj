(ns tasks.nido-probe
  "Bb task entry points for probing a judge-prompt change on recorded rounds (`nido.review.probe`).

   Conventions: kwargs are `:keyword value`."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.task-args :as task-args]
   [nido.review.probe :as probe]
   [nido.review.prompt-shape :as shape]
   [nido.review.record :as record]
   [nido.coordinator.record.workstream :as ws]))

(def ^:private builtin-variants
  "Variants a plan may name by keyword."
  {:control     {:name "control" :edits []}
   :no-stance   {:name "no-stance" :edits [{:drop "PROJECT STANCE"}]}
   :data-shaped {:name  "data-shaped"
                 :edits [{:substitute "DERIVE THESE FOUR." :text "DERIVE THESE."}
                         {:insert-before "\nThen recommend:" :text record/data-shaped-check}
                         {:add-check "data_shaped"}]}})

(defn- resolve-variant
  "A plan's variant: a built-in by keyword, or a map whose edits may name a `:file` to read their
   text from (`:prepend` from it when the edit has no other key)."
  [v]
  (if (keyword? v)
    (or (builtin-variants v) (throw (ex-info (str "no built-in variant " v) {:variant v})))
    (update v :edits (partial mapv (fn [e]
                                     (if-let [f (:file e)]
                                       (let [t (slurp (str (fs/expand-home f)))]
                                         (if (= #{:file} (set (keys e)))
                                           {:prepend t}
                                           (-> e (dissoc :file) (assoc :text t))))
                                       e))))))

(defn- worktree-of [run-dir]
  (let [r (try (json/parse-string (slurp (str (fs/path run-dir "report.json"))) true) (catch Exception _ nil))
        w (or (get-in r [:target :code-cwd]) (get-in r [:target :cwd]))]
    (when (and w (not (str/includes? w "/.nido/runs/")) (fs/directory? w)) w)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  sample-cmd
  "bb nido:review:probe:sample [:per-worktree 2] [:days 14] — a plan skeleton over the newest
   recorded design rounds: up to :per-worktree first rounds per reviewed worktree, each from a
   different run, shown the stance and not yet the data-shaped check. Printed as EDN to edit."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        per      (or (some-> (:per-worktree opts) str parse-long) 2)
        days     (or (some-> (:days opts) str parse-long) 14)
        cutoff   (- (System/currentTimeMillis) (* days 86400000))
        cands    (for [d (fs/list-dir (cstate/runs-dir))
                       :when (str/starts-with? (str (fs/file-name d)) "design-loop-")
                       :let [p (fs/path d "design-decision-round-1-prompt.txt")]
                       :when (and (fs/exists? p) (> (.toMillis (fs/last-modified-time p)) cutoff))
                       :let [text (slurp (str p)) w (worktree-of d)]
                       :when (and w (str/includes? text "PROJECT STANCE")
                                  (not (str/includes? text "data-shaped       —")))]
                   {:prompt (str p) :schema (str (fs/path d "design-decision-round-1-schema.json"))
                    :worktree w :at (.toMillis (fs/last-modified-time p))})
        rounds   (->> (group-by :worktree cands)
                      (mapcat (fn [[_ cs]] (take per (sort-by (comp - :at) cs))))
                      (sort-by (comp - :at))
                      (mapv #(dissoc % :at)))]
    (pp/pprint {:probe-id (str "stance-" (subs (str (random-uuid)) 0 8))
                :reviewer :codex
                :parallel 4
                :reps     {:control 2 :default 1}
                :variants [:control :no-stance
                           {:name "short-stance" :edits [{:drop "PROJECT STANCE"}
                                                         {:file "<path to the short lens>"}]}
                           :data-shaped]
                :rounds   rounds})))

(def ^:private hot-markers
  "What a design prompt closes on after its hot-area section, in order (`record/design-prompt`):
   the section goes before the first of these a recorded prompt carries, and before its closing
   level reminder — the LAST `---`, since a stance may carry one of its own — when it has none."
  ["\nQUESTIONED BY THE LAST AMENDMENT" "\nOBSERVED BY RUNNING COMMANDS" "\nDISPUTED —"])

(defn- hot-edit
  "The edit putting `section` where today's design prompt puts it in recorded `prompt`."
  [prompt section]
  (if-let [m (->> hot-markers (filter #(str/index-of prompt %)) (sort-by #(str/index-of prompt %)) first)]
    {:insert-before m :text section}
    {:insert-before-last "\n\n---\n" :text section}))

(defn- ruled-ids
  "The claims decision `d` ruled on — confirmed, owed, unruled, unchecked or named by a finding:
   the claims its round checked."
  [d]
  (into #{} (remove str/blank?)
        (concat (:confirmed d) (:owed d) (:unruled d) (map :id (:unchecked d))
                (mapcat #(or (seq (:claim-ids %)) [(:claim-id %)]) (:findings d)))))

(defn- decisions-by-run
  "Every design decision on every ledger that names its run, `{run-id [{:project :ws-id :decision}]}`
   oldest first."
  []
  (let [root (fs/path (cstate/nido-root) "projects")]
    (->> (for [p  (when (fs/exists? root) (fs/list-dir root))
               :let [project (keyword (str (fs/file-name p)))]
               id (try (ws/list-ids project) (catch Exception _ []))
               d  (ws/entries-of project id :design-decision)
               :when (:run-id d)]
           {:project project :ws-id id :decision d})
         (sort-by (comp :seq :decision))
         (group-by (comp :run-id :decision)))))

(defn- hot-round
  "Recorded round `prompt` of run `run-id` as a probe round with its hot-area section, or nil when
   the ledger as it stood before the round's decision holds no hot area the round checked. The
   round's decision is the one of `run-decisions` its prompt's design entry and its place among the
   run's rounds on that entry name."
  [run-decisions [k prompt-path] rounds-on-entry]
  (let [text  (slurp (str prompt-path))
        entry (some-> (re-find #"THE DESIGN — entry (\d+)" text) second parse-long)
        n     (count (take-while #(not= k %) (get rounds-on-entry entry)))
        {:keys [project ws-id decision]} (nth (filterv #(= entry (get-in % [:decision :design-seq])) run-decisions) n nil)]
    (when decision
      (let [design  (ws/entry-at-seq project ws-id entry)
            entries (filterv #(< (:seq %) (:seq decision)) (record/lineage-entries project ws-id design))
            section (when design
                      (record/hot-area-section (record/hot-areas design entries) (ruled-ids decision)))]
        (when section
          {:text section :ws-id ws-id :decision-seq (:seq decision)})))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  hot-sample-cmd
  "bb nido:review:probe:hot [:per-workstream 2] [:days 14] — a probe plan over recorded design
   rounds that, by the ledger as it stood at each, checked a claim in a hot area: up to
   :per-workstream newest rounds per workstream, each round carrying its own hot-area section for
   the `hot-areas` variant, whose schema carries step_back. Printed as EDN to edit."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        per      (or (some-> (:per-workstream opts) str parse-long) 2)
        days     (or (some-> (:days opts) str parse-long) 14)
        cutoff   (- (System/currentTimeMillis) (* days 86400000))
        by-run   (decisions-by-run)
        rounds   (for [d (fs/list-dir (cstate/runs-dir))
                       :let [run-id (str (fs/file-name d))]
                       :when (and (str/starts-with? run-id "design-loop-") (get by-run run-id))
                       :let [w (worktree-of d)
                             ps (->> (fs/glob d "design-decision-round-*-prompt.txt")
                                     (keep #(when-let [[_ k] (re-matches #"design-decision-round-(\d+)-prompt\.txt"
                                                                         (str (fs/file-name %)))]
                                              [(parse-long k) %]))
                                     (filter #(> (.toMillis (fs/last-modified-time (second %))) cutoff))
                                     (sort-by first))
                             on-entry (reduce (fn [m [k p]]
                                                (let [e (some-> (re-find #"THE DESIGN — entry (\d+)" (slurp (str p)))
                                                                second parse-long)]
                                                  (update m e (fnil conj []) k)))
                                              {} ps)]
                       :when w
                       [k p] ps
                       :let [h (hot-round (get by-run run-id) [k p] on-entry)]
                       :when h]
                   (assoc h :prompt (str p) :at (.toMillis (fs/last-modified-time p))
                          :schema (str/replace (str p) #"-prompt\.txt$" "-schema.json")
                          :worktree w))
        picked   (->> (group-by :ws-id rounds)
                      (mapcat (fn [[_ rs]] (take per (sort-by (comp - :at) rs))))
                      (sort-by (comp - :at))
                      (mapv (fn [{:keys [prompt schema worktree text ws-id decision-seq]}]
                              {:prompt prompt :schema schema :worktree worktree
                               :ws-id ws-id :decision-seq decision-seq
                               :edits {"hot-areas" [(hot-edit (slurp prompt) text)]}})))]
    (pp/pprint {:probe-id (str "hot-" (subs (str (random-uuid)) 0 8))
                :reviewer :codex
                :parallel 4
                :reps     {:control 2 :default 1}
                :variants [:control
                           {:name "hot-areas"
                            :edits [{:add-property "step_back" :from "review/design_decision_schema.json"}]}]
                :rounds   picked})))

(defn- pct [x] (if x (format "%4.0f%%" (* 100 x)) "    -"))
(defn- num* [x] (if x (format "%6.1f" (double x)) "     -"))

(defn- print-summary [rows]
  (println (format "%-14s %4s %8s %8s %8s %8s %8s %7s %9s  %s"
                   "variant" "runs" "same rec" "checks" "findings" "design" "stepped" "min" "tokens" "recommends"))
  (doseq [r rows]
    (println (format "%-14s %4d %8s %8s %8s %8s %8s %7s %9s  %s"
                     (:variant r) (:runs r) (pct (:same-recommend r)) (pct (:check-agreement r))
                     (num* (:findings r)) (pct (:design-level r)) (pct (:stepped-back r)) (num* (:minutes r))
                     (if (:tokens r) (format "%9.0f" (:tokens r)) "-")
                     (str/join " " (for [[k n] (sort-by key (:recommends r))] (str k "=" n)))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  probe-cmd
  "bb nido:review:probe :plan <plan.edn> [:grade false] [:dry-run true] — judge every round of the plan under every
   variant against one frozen tree per round, grade the findings blind, and print one row per
   variant. Results go to ~/.nido/probes/<probe-id>/results.edn. Spends a judge run per round ×
   variant × repetition: size the plan before running it. :dry-run true builds every prompt and
   prints its size, stance and checks, judging nothing."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        plan     (-> (edn/read-string (slurp (str (:plan opts))))
                     (update :variants (partial mapv resolve-variant)))
        _        (when (= "true" (str (:dry-run opts)))
                   (doseq [[i rd] (map-indexed vector (:rounds plan))
                           j (probe/jobs (assoc plan :probe-dir "/dry-run") i rd)
                           :when (zero? (:rep j))
                           :let [sh (shape/shape (:prompt j))]]
                     (println (format "round %d  %-14s %6.1fK  stance %5s  checks %s"
                                      i (:variant j) (/ (:bytes sh) 1024.0)
                                      (if-let [b (get-in sh [:stance :bytes])] (format "%.1fK" (/ b 1024.0)) "-")
                                      (str/join "," (:checks sh)))))
                   (System/exit 0))
        results  (probe/probe! plan)
        results  (if (= "false" (str (:grade opts))) results (probe/grade! (:probe-id plan) results))
        out      (str (fs/path (probe/probes-dir) (:probe-id plan) "results.edn"))]
    (spit out (pr-str results))
    (let [stand-ins (count (filter :stand-in? results))
          failed    (count (remove :metrics results))]
      (when (pos? stand-ins) (println stand-ins "run(s) answered by a stand-in, left out below."))
      (when (pos? failed) (println failed "run(s) gave no readable answer, left out below.")))
    (print-summary (probe/summary results))
    (println "\nresults:" out)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  summary-cmd
  "bb nido:review:probe:summary :probe-id <id> — the summary of a probe already run."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (print-summary (probe/summary (edn/read-string
                                   (slurp (str (fs/path (probe/probes-dir) (str (:probe-id opts))
                                                        "results.edn"))))))))
