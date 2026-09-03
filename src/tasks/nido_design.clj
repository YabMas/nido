;; src/tasks/nido_design.clj
(ns tasks.nido-design
  "`bb nido:design:check` — does this worktree's code stand up the project's declared design?
   `bb nido:design:diff`  — what does this branch change about that declaration?

   The terminal-facing half of the same readings the briefing, the review loop and the landing
   gate use. It exists so a human can ask the questions directly and get the answers a machine
   would have got, rather than second, kinder ones."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.design.check :as design]
   [nido.platform.config :as config]
   [nido.platform.task-args :as task-args]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.record.workstream :as cws]
   [nido.session.lifecycle :as lifecycle]
   [nido.vsdd.jj :as jj]))

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
          :unmodelled  (do (println (str "design:check ok · " (design/unmodelled-line project)
                                          " — nothing to check"))
                           0)
          :satisfied   (do (println (str "design:check ok · " project " stands up its declared design"))
                           0)
          :violated    (let [{:keys [body] n :count} (design/refusal result)]
                         (println (str "design:check REFUSED · " n " violation"
                                       (when (not= 1 n) "s") " of " project
                                       "'s declared design\n"))
                         (println body)
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

(defn- workstream-scope
  "The selection the workstream running in `cwd` settled on, or nil.

   The baseline round is the one round that reads the code before deciding which part of the
   declaration governs its area, and it records that decision as `:scope`. Every later session
   on the workstream is briefed with exactly that part — and a diff shown at a different width
   than the briefing answers a question nobody asked.

   nil outside a session, and nil before the workstream is baselined: the round whose job is to
   establish a scope cannot be handed one."
  [cwd]
  (try
    (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
      (when-let [ws-id (csession/workstream-id-for (keyword project) session)]
        (:scope (cws/latest-entry (keyword project) ws-id :baseline))))
    (catch Throwable _ nil)))

(defn- materialize-spec-dirs!
  "Write the spec dirs as they stood at `rev` into a fresh directory, and answer it.

   This is the half `nido.design.check` may not do. Its band depends on Platform alone, and a
   revision is a VCS concept two bands away — so a task, which may reach everything, resolves
   the revision to a tree and hands down a directory.

   File by file rather than a second workspace: a workspace costs a checkout of the whole repo
   and has to be forgotten afterwards, and what is wanted is a few dozen small files. nil when
   the revision names nothing, which is the caller's cue to say so rather than to diff against
   an empty design and report the whole declaration as new."
  [worktree rev spec-dirs]
  (let [{:keys [exit out]} (jj/jj! worktree "file" "list" "-r" rev)]
    (when (zero? (long exit))
      (let [wanted (->> (str/split-lines (str out))
                        (map str/trim)
                        (filter (fn [f] (and (str/ends-with? f ".clj")
                                             (some #(str/starts-with? f (str % "/")) spec-dirs)))))
            dir    (fs/create-temp-dir {:prefix "nido-design-base-"})]
        (doseq [f wanted]
          (let [target (fs/path dir f)]
            (fs/create-dirs (fs/parent target))
            (let [{:keys [exit out]} (jj/jj! worktree "file" "show" "-r" rev f)]
              (when (zero? (long exit)) (spit (str target) out)))))
        (str dir)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  diff
  "Print what this branch changes about the project's declared design. Returns the exit code.

   Zero whatever it finds. This is a READING, not a gate: it answers a question a human asked
   before deciding whether the change is one they want, and a non-zero exit would make it
   unusable in exactly the place it is most useful — beside a `check` that means something
   different by refusing."
  [& args]
  (let [[_ opts] (task-args/split-args args #{:from})
        cwd      (or (:cwd opts) (System/getProperty "user.dir"))
        from     (or (:from opts) "main")]
    (if-let [[project worktree] (coords cwd)]
      ;; `design-of` is asked for the spec dirs to materialize with, not for the answer —
      ;; `design/diff` says :unmodelled for itself, and two paths to one answer is how the
      ;; sentence came to be written twice in this function.
      (if-let [{:keys [spec-dirs select]} (design/design-of project worktree)]
        ;; explicit flag, then the workstream's settled scope, then the project's default.
        ;; The middle one is the point: a session briefed with one part of the declaration is
        ;; shown its change at the same width.
        (let [scope    (or (:scope opts) (workstream-scope cwd) select)
              base-dir (materialize-spec-dirs! worktree from spec-dirs)]
          (if-not base-dir
            (do (println (str "design:diff · could not read " project " at " from
                              " — does that revision exist?"))
                1)
            (try
              (let [{:keys [status diff digest error]}
                    (design/diff project worktree base-dir scope)]
                (case status
                  :unmodelled (println (str "design:diff · " (design/unmodelled-line project)))
                  :unchanged  (println (str "design:diff · this branch changes nothing about "
                                            project "'s declared design (" digest ")"))
                  :changed    (do (println (str "design:diff · what this branch changes about "
                                                project "'s declared design, against " from
                                                (when scope (str ", scoped to " (pr-str scope)))
                                                "\n"))
                                  (println diff)
                                  (println (str "design digest on this branch: " digest))
                                  (println (str "Quote it in the approval — a design reviewed "
                                                "over a working copy\nis carried by no revision, "
                                                "so nothing else names what was approved.")))
                  :undecidable (println (str "design:diff UNDECIDABLE · " error))))
              0
              (finally (fs/delete-tree base-dir)))))
        (do (println (str "design:diff · " (design/unmodelled-line project))) 0))
      (do (println (str "design:diff · " cwd " belongs to no registered project")) 0))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  diff-cmd
  "bb entry point for the diff."
  [& args]
  (let [code (apply diff args)]
    (when-not (zero? code) (System/exit code))))
