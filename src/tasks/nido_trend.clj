(ns tasks.nido-trend
  "Bb task entry points for reading how the review loops converge across runs, replaying recorded
   runs through today's stopping rules, and releasing the improvement sweep's convergence hold.

   Conventions: kwargs are `:keyword value`."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.trend :as trend]
   [nido.platform.task-args :as task-args]
   [nido.review.prompt-shape :as shape]
   [nido.review.replay :as replay])
  (:import
   [java.time LocalDate]))

(defn- pct [x] (if x (format "%3.0f%%" (* 100 x)) "   -"))
(defn- num* [x] (if x (format "%5.1f" (double x)) "    -"))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  trend-cmd
  "bb nido:review:trend [:days <n>] — weekly convergence per loop, and whether the sweep's gate
   reads a regression today. Exits 1 on a regression."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        days     (or (some-> (:days opts) str parse-long) 56)
        today    (str (LocalDate/now))
        runs     (trend/read-recent! days)
        worse    (trend/regression runs today)]
    (println (format "%-9s %-10s %5s %7s %6s %6s %9s %7s"
                     "loop" "week of" "runs" "decided" "rounds" "min" "converged" "handed"))
    (doseq [[kind weeks] (trend/weekly runs)
            [w s] weeks]
      (println (format "%-9s %-10s %5d %7d %6s %6s %9s %7s"
                       (name kind) w (:runs s) (:decided s)
                       (num* (:median-rounds s)) (num* (:median-minutes s))
                       (pct (:converged s)) (pct (:handed s)))))
    (println)
    (if (seq worse)
      (do (println "Regressed — the last two weeks against the two before:")
          (doseq [r worse] (println "  " (trend/regression-line r)))
          (when-not (trend/released? :nido today)
            (println "\nThe improvement sweep holds while this stands. `bb nido:improvement:release :until <date> :note <why>` releases it."))
          (System/exit 1))
      (println "No regression across the last two weeks."))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  replay-cmd
  "bb nido:review:replay [:days <n>] — replay every recorded record run through today's engine
   and list the runs it would have ended before their recorded end."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        days     (or (some-> (:days opts) str parse-long) 365)
        rs       (replay/replay-recent! days)
        d        (filterv replay/diverged? rs)
        saved    (reduce + (map #(- (get-in % [:recorded :rounds]) (get-in % [:replayed :rounds])) d))]
    (println (str (count rs) " record run(s) replayed; " (count d) " would end sooner, "
                  saved " round(s) fewer."))
    (doseq [x d] (println "  " (replay/line x)))
    (let [cut (filter #(#{:proceed :sufficient :cleared} (get-in % [:recorded :status])) d)]
      (when (seq cut)
        (println (str "\n" (count cut) " of them ended converged as recorded — read each before"
                      " accepting the rule: " (str/join ", " (map :run-id cut))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  release-cmd
  "bb nido:improvement:release :until <YYYY-MM-DD> :note <why> [:project nido]"
  [& args]
  (let [[_ opts] (task-args/split-args args)
        project  (keyword (str (or (:project opts) "nido")))
        until    (some-> (:until opts) str)
        note     (some-> (:note opts) str)]
    (when-not (and until (re-matches #"\d{4}-\d{2}-\d{2}" until))
      (println "Missing :until <YYYY-MM-DD>") (System/exit 2))
    (when (str/blank? note) (println "Missing :note — say why the hold is released") (System/exit 2))
    (println "released through" until "—" (trend/release! project until note))))

(defn- median [xs]
  (let [xs (vec (sort xs)) n (count xs)]
    (when (pos? n) (nth xs (quot n 2)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  prompts-cmd
  "bb nido:review:prompts [:days <n>] [:kind <design-decision|design-amend|baseline-review|amend>]
   — what record rounds were shown, read off the prompts on disk: per kind and week, the median
   prompt and its largest sections; then each variant (stance and checks asked) with how its runs
   ended."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        days     (or (some-> (:days opts) str parse-long) 56)
        only     (some-> (:kind opts) str)
        ps       (cond->> (shape/read-recent! days) only (filter #(= only (:kind %))))
        monday   #(str (let [d (LocalDate/parse %)] (.minusDays d (dec (.getValue (.getDayOfWeek d))))))]
    (doseq [[kind kps] (sort-by key (group-by :kind ps))]
      (println (str "\n" kind))
      (doseq [[w wps] (sort-by key (group-by (comp monday :day) kps))
              :let [total (reduce + (map (comp :bytes :shape) wps))
                    by    (apply merge-with + (map (comp :sections :shape) wps))
                    top   (take 5 (sort-by (comp - val) by))]]
        (println (format "  %s %4d prompts, median %5.1fK — %s" w (count wps)
                         (/ (median (map (comp :bytes :shape) wps)) 1024.0)
                         (str/join ", " (for [[h b] top] (format "%s %.0f%%" h (* 100.0 (/ b total)))))))))
    (println "\nloop      stance       checks              runs  first      last       rounds  handed")
    (doseq [[[kind v] rs] (sort-by (fn [[[k] rs]] [(name k) (first (sort (map :day rs)))])
                                   (group-by (juxt :kind :variant) (shape/variants ps)))
            :let [s (trend/summary rs) days (sort (map :day rs))]]
      (println (format "  %-8s %-12s %-18s %4d  %s %s %6s %7s"
                       (name kind) (or (:stance v) "no stance")
                       (cond (some #{"data-shaped"} (:checks v)) (str (count (:checks v)) " incl. data-shaped")
                             (seq (:checks v))                    (str (count (:checks v)) " checks")
                             :else                                "-")
                       (count rs) (first days) (last days)
                       (num* (:median-rounds s)) (pct (:handed s)))))))
