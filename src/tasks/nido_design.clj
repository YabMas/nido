;; src/tasks/nido_design.clj
(ns tasks.nido-design
  "`bb nido:design:check` — does this worktree's code stand up the project's declared design?

   The terminal-facing half of the same reading the briefing, the review loop and the landing
   gate use. It exists so a human can ask the question directly and get the answer a machine
   would have got, rather than a second, kinder one."
  (:require
   [clojure.string :as str]
   [nido.design.check :as design]
   [nido.platform.config :as config]
   [nido.platform.task-args :as task-args]
   [nido.session.lifecycle :as lifecycle]))

(defn- project-for-dir
  "The registered project whose directory contains `dir` — how a check run in the project's own
   checkout, rather than in one of its session worktrees, still knows whose design it is
   reading. Longest match wins, so a project nested inside another resolves to the inner one."
  [dir]
  (->> (config/read-projects)
       (filter (fn [[_ {:keys [directory]}]] (str/starts-with? (str dir) (str directory))))
       (sort-by (fn [[_ {:keys [directory]}]] (- (count (str directory)))))
       ffirst))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  coords
  "[project worktree] for `cwd`: a nido session's if cwd is inside one, otherwise the project's
   own checkout. Returns nil when cwd belongs to no registered project at all.

   Public because the landing gate asks the same question, and two answers to \"whose design is
   this?\" would eventually disagree about a nested checkout."
  [cwd]
  (if-let [{:keys [project worktree]} (try (lifecycle/session-from-cwd cwd) (catch Throwable _ nil))]
    [project worktree]
    (when-let [p (project-for-dir cwd)] [p cwd])))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  check
  "Report the design status of the worktree at `:cwd` (default: here). Returns the exit code."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        cwd      (or (:cwd opts) (System/getProperty "user.dir"))]
    (if-let [[project worktree] (coords cwd)]
      (let [result (design/check project worktree)]
        (case (:status result)
          :unmodelled  (do (println (str "design:check ok · " project " declares no design — nothing to check"))
                           0)
          :satisfied   (do (println (str "design:check ok · " project " stands up its declared design"))
                           0)
          :violated    (let [n (reduce + (map (comp count :offenders) (:violations result)))]
                         (println (str "design:check REFUSED · " n " violation"
                                       (when (not= 1 n) "s") " of " project "'s declared design\n"))
                         (println (design/violation-text result))
                         (println (str "\nThe design is declared in "
                                       (str/join ", " (:files result))
                                       ".\nEither the code moves, or the declaration does — but one of them is wrong."))
                         1)
          :undecidable (do (println (str "design:check UNDECIDABLE · " (:error result)))
                           (println "\nThis is not a clean bill of health: nobody could tell. Fix the\nchecker before reading anything into a green run.")
                           2)))
      (do (println (str "design:check ok · " cwd " belongs to no registered project — nothing to check"))
          0))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  cmd
  "bb entry point: exits non-zero on a refusal, so a recipe that runs it before the push stops
   there without anyone having to read the output."
  [& args]
  (let [code (apply check args)]
    (when-not (zero? code) (System/exit code))))
