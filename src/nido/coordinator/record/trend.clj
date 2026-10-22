;; src/nido/coordinator/record/trend.clj
(ns nido.coordinator.record.trend
  "How the review loops converge across runs, read off every run's report.json.

   One run's analysis says what that run did; nothing said what the loops do. Between 2026-09-01
   and 2026-10-22 the improvement sweep landed some 226 changes to the review machinery on per-run
   evidence, and over the same weeks the design loop went from almost never handing a run to a
   person to handing over seven runs in ten, while diff and baseline runs took two to four times
   as long. No surface showed it: each landing answered its own analysis, and the only place the
   regression was visible was the aggregate nobody computed. This computes it — for a person
   (`bb nido:review:trend`) and for the sweep, which holds itself while it reads a regression
   (`regression`).

   A run's outcome is one of three classes, and the class is the whole judgement made here:
   :converged — the loop finished its job on its own; :handed — it stopped holding something a
   person has to settle; :failed — it did not run to an outcome at all (interrupted, a judge that
   would not launch). :failed is left out of the rates: an outage says nothing about convergence."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.record.state :as cstate])
  (:import
   [java.time Duration Instant LocalDate]))

(def ^:private converged
  #{"converged" "clean" "proceed" "sufficient" "cleared"})

(def ^:private handed
  #{"asked" "escalated" "disputed" "unruled" "unfixable" "no-progress" "stalled" "unresolved"
    "max-iters" "owed-second-reading" "underivable" "nothing-to-amend" "retreated"
    "premise-unverified" "fix-declined" "fix-unrouted" "fix-rolled-back" "fix-conflicted"})

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] :keyword]}
  outcome
  "A run status's class: :converged, :handed or :failed."
  [status]
  (cond (converged status) :converged
        (handed status)    :handed
        :else              :failed))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :keyword]]}
  kind-of
  "Which loop a run id belongs to, or nil for a run that is not a review loop."
  [run-id]
  (condp #(str/starts-with? %2 %1) run-id
    "design-loop-"   :design
    "baseline-loop-" :baseline
    "review-"        :diff
    nil))

(defn- minutes [from to]
  (try (/ (.toMinutes (Duration/between (Instant/parse from) (Instant/parse to))) 1.0)
       (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  figures
  "One run's figures off its report, or nil for a report that is not a finished review loop."
  [report]
  (let [run-id (str (get report "run-id"))
        kind   (kind-of run-id)
        start  (get report "started-at")
        status (get report "status")]
    (when (and kind start (not= "running" status) (not= "dry-run" status))
      {:run-id  run-id
       :kind    kind
       :day     (subs start 0 10)
       :status  status
       :outcome (outcome status)
       :rounds  (count (get report "rounds"))
       :minutes (some->> (get report "ended-at") (minutes start))})))

(defn ^{:malli/schema [:=> [:cat :int] [:vector :map]]}
  read-recent!
  "The figures of every review-loop run started in the last `days` days.

   Narrowed on the run dir's modification time before any report is parsed: a long run's report
   is megabytes, and the sweep asks this every poll."
  [days]
  (let [cutoff (- (System/currentTimeMillis) (* (inc days) 86400000))
        since  (str (.minusDays (LocalDate/now) days))]
    (into []
          (comp (filter #(kind-of (str (fs/file-name %))))
                (filter #(> (.toMillis (fs/last-modified-time %)) cutoff))
                (map #(fs/path % "report.json"))
                (filter fs/exists?)
                (keep #(try (figures (json/parse-string (slurp (str %))))
                            (catch Exception _ nil)))
                (filter #(>= (compare (:day %) since) 0)))
          (when (fs/exists? (cstate/runs-dir)) (fs/list-dir (cstate/runs-dir))))))

(defn- median [xs]
  (let [xs (vec (sort (remove nil? xs))) n (count xs)]
    (when (pos? n)
      (if (odd? n) (nth xs (quot n 2)) (/ (+ (nth xs (dec (quot n 2))) (nth xs (quot n 2))) 2.0)))))

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] :map]}
  summary
  "What a set of runs of one kind did: how many, their median rounds and minutes, and the share
   of those that ran to an outcome that converged or were handed to a person."
  [runs]
  (let [decided (remove #(= :failed (:outcome %)) runs)
        n       (count decided)
        share   #(when (pos? n) (/ (count (filter (comp #{%} :outcome) decided)) (double n)))]
    {:runs           (count runs)
     :decided        n
     :median-rounds  (median (map :rounds decided))
     :median-minutes (median (map :minutes decided))
     :converged      (share :converged)
     :handed         (share :handed)}))

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] [:map-of :keyword [:map-of :string :map]]]}
  weekly
  "`summary` per kind per ISO week (keyed by the week's Monday)."
  [runs]
  (let [monday #(str (let [d (LocalDate/parse %)] (.minusDays d (dec (.getValue (.getDayOfWeek d))))))]
    (into (sorted-map)
          (for [[kind rs] (group-by :kind runs)]
            [kind (into (sorted-map)
                        (for [[w ws] (group-by (comp monday :day) rs)] [w (summary ws)]))]))))

(def ^:private window-days
  "The two windows a regression compares, each this many days. Two weeks, because a week of
   record runs is often one workstream's arc and a single stubborn design swings a week's medians."
  14)

(def ^:private min-runs
  "Fewer decided runs than this in either window and the kind is not judged — a rate over five
   runs is one run's story."
  10)

(defn ^{:malli/schema [:=> [:cat [:sequential :map] :string] [:vector :map]]}
  regression
  "Each way the last `window-days` up to `today` converged worse than the `window-days` before
   them, per kind: a handed share up by ten points or more, or median rounds or minutes up by half
   or more. Empty when nothing regressed or neither window holds `min-runs` decided runs."
  [runs today]
  (let [t      (LocalDate/parse today)
        in     (fn [from to] (fn [{:keys [day]}] (and (pos? (compare day (str from)))
                                                     (<= (compare day (str to)) 0))))
        recent (in (.minusDays t window-days) t)
        prior  (in (.minusDays t (* 2 window-days)) (.minusDays t window-days))]
    (vec
     (for [[kind rs] (sort-by key (group-by :kind runs))
           :let [now    (summary (filter recent rs))
                 before (summary (filter prior rs))]
           :when (and (>= (:decided now) min-runs) (>= (:decided before) min-runs))
           [measure worse?] [[:handed         #(>= (- %2 %1) 0.10)]
                             [:median-rounds  #(and (pos? %1) (>= %2 (* 1.5 %1)))]
                             [:median-minutes #(and (pos? %1) (>= %2 (* 1.5 %1)))]]
           :let [a (measure before) b (measure now)]
           :when (and a b (worse? a b))]
       {:kind kind :measure measure :before a :now b}))))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  regression-line
  "One regression as a sentence."
  [{:keys [kind measure before now]}]
  (let [f (if (= :handed measure) #(format "%.0f%%" (* 100 %)) #(format "%.1f" (double %)))]
    (str (name kind) " " (str/replace (name measure) "-" " ") " " (f before) " → " (f now))))

(defn ^{:malli/schema [:=> [:cat :keyword] :string]}
  release-path
  "Where a person's release of the sweep's convergence hold is kept, for `project`."
  [project]
  (str (fs/path (cstate/nido-root) "projects" (name project) "improvement-release.edn")))

(defn ^{:malli/schema [:=> [:cat :keyword :string] :boolean]}
  released?
  "Whether a person has released the convergence hold through `day` — a release is dated and
   lapses, so a hold waved through once comes back on its own if the regression outlives it."
  [project day]
  (let [{:keys [until]} (try (read-string (slurp (release-path project))) (catch Exception _ nil))]
    (boolean (and (string? until) (<= (compare day until) 0)))))

(defn ^{:malli/schema [:=> [:cat :keyword :string :string] :string]}
  release!
  "Release the hold through `until` (inclusive), saying why. Returns the path written."
  [project until note]
  (let [p (release-path project)]
    (fs/create-dirs (fs/parent p))
    (spit p (pr-str {:until until :note note :at (str (Instant/now))}))
    p))
