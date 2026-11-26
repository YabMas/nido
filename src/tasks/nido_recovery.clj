(ns tasks.nido-recovery
  "bb nido:recovery:* — the verbs a recovery session works through: read what
   failed, restore it, land a nido fix.

   The land verb is the gate, not advice. A recovery lands on nido's main with
   nobody watching, so the verb runs the tests and the landing check itself, at
   the exact tip it pushes, and refuses for a run whose diagnosis does not name a
   nido defect — a skill that skipped a step cannot land around it.

   Restore and land act for the recovery run calling them: `:run <id>`, else the
   run the calling turn runs in (`NIDO_CORE_RUN`)."
  (:require
   [clojure.string :as str]
   [nido.coordinator.lane.restore :as restore]
   [nido.coordinator.record.records :as records]
   [nido.coordinator.source.start-failures :as start-failures]
   [nido.platform.task-args :as task-args]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.jj :as jj]
   [tasks.nido-land :as nido-land]))

(defn- run-key-of
  "The core key of the recovery run a verb acts for, or nil when nothing names one."
  [opts]
  (or (some->> (:run opts) str (str "run:nido/"))
      (not-empty (System/getenv "NIDO_CORE_RUN"))))

(defn- cwd-of [opts]
  (let [given (or (some-> (:cwd opts) str) (System/getProperty "user.dir"))]
    (or (lifecycle/worktree-from-cwd given) given)))

(defn- indent [s prefix]
  (->> (str/split-lines (str s)) (map #(str prefix %)) (str/join "\n")))

(defn- print-failure [f owed?]
  (println (str "\n── " (:id f) "  " (:project f) "/" (:session f)
                "  " (name (or (:verb f) :up))
                "  cause " (failure/cause f)
                (when-not owed? "  (settled)")))
  (println (str "   at      " (:at f)))
  (println (str "   origin  " (pr-str (dissoc (:origin f) :input))
                (when (-> f :origin :input) "  (carries a reply)")))
  (println (str "   opts    " (pr-str (dissoc (:opts f) :profile))))
  (doseq [{:keys [class message data]} (:error f)]
    (println (str "   error   " class ": " message))
    (when (seq data) (println (indent (pr-str data) "           "))))
  (doseq [[path text] (:logs f)]
    (println (str "   log     " path))
    (println (indent (->> (str/split-lines text) (take-last 40) (str/join "\n")) "     │ "))))

(defn- print-recoveries [recs cause]
  (let [mine (filter #(= cause (:cause %)) recs)]
    (println (str "\nEarlier recoveries of cause " cause ": " (count mine)
                  "  (" (start-failures/failed-in-a-row (start-failures/pacing-runs (vec recs) cause))
                  " ended in a row leaving it owed)"))
    (doseq [{:keys [ws-id closed runs named settled entries]} mine]
      (println (str "  run " ws-id "  "
                    (if closed (str "ended " (name (:outcome closed)) " " (:at closed)) "running")
                    "  " (name (:state (first runs)))
                    "  named " (count named) "  settled " (count settled)))
      (when-let [d (last (:session-diagnosis entries))]
        (println (str "    diagnosis: " (name (:verdict d)) " — " (:cause d))))
      (when-let [b (last (:blocker entries))]
        (println (str "    stopped for a person: " (:needs b (:summary b))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  failures
  "bb nido:recovery:failures [:cause <key>] [:all true]
   Kept session-start failures with their evidence — the owed ones, or all of
   them — and, for a cause, the recoveries that already tried it."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        cause    (some-> (:cause opts) str)
        recs     (start-failures/recoveries :nido)
        ;; Settled records are opened only when asked for: the trail is never
        ;; pruned and each record carries its log tails.
        all      (if (:all opts) (failure/failures) (start-failures/undischarged-failures recs))
        owed-ids (set (map :id (start-failures/owed all recs)))
        shown    (cond->> all
                   (not (:all opts)) (filter #(owed-ids (:id %)))
                   cause             (filter #(= cause (failure/cause %))))]
    (println (str (count shown) " failure" (when (not= 1 (count shown)) "s")
                  (if (:all opts) " kept" " owed a recovery")
                  (when cause (str " for cause " cause))))
    (doseq [f shown] (print-failure f (owed-ids (:id f))))
    (doseq [f (filter #(= :session-failure (-> % :origin :source-type)) shown)]
      (println (str "\n" (:id f) " is the start of a recovery Run: excluded from recovery.")))
    (when cause (print-recoveries recs cause))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :int]}
  restore
  "bb nido:recovery:restore [:run <id>] — restore the owed failures of this
   recovery run's cause, and record what became of each on the run. Exits 1 on a
   refusal or when a failure was not restored."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        run-key  (run-key-of opts)]
    (if-not run-key
      (do (println "restore REFUSED · no recovery run: pass :run <id>, or call it from the run's own turn")
          1)
      (try
        (let [{:keys [outcomes settled?]} (restore/restore! :nido run-key)]
          (if (empty? outcomes)
            (println "restore · nothing owed for this cause")
            (doseq [{:keys [failure outcome continuation failure-left note]} outcomes]
              (println (str "restore · " failure " " (name outcome)
                            (when continuation (str " → " (pr-str continuation)))
                            (when failure-left (str " · its attempt kept " failure-left))
                            (when note (str " · " note))))))
          (println (if settled?
                     "restore · settled: nothing of this cause is owed any more"
                     "restore · something of this cause is still owed"))
          (if (some #(= :not-restored (:outcome %)) outcomes) 1 0))
        (catch clojure.lang.ExceptionInfo e
          (println (str "restore REFUSED · " (ex-message e)))
          1)))))

(defn- commit-url
  "Where a landed commit can be read: its page on GitHub when origin is a GitHub
   remote, else the commit id itself."
  [cwd commit]
  (let [{:keys [exit out]} (jj/jj! cwd "git" "remote" "list")
        slug (when (zero? exit)
               (some->> (str/split-lines (or out ""))
                        (some #(when (str/starts-with? % "origin ") (second (str/split % #"\s+"))))
                        (re-find #"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$")
                        second))]
    (if slug (str "https://github.com/" slug "/commit/" commit) commit)))

(defn- refused [why way-out]
  (println (str "recovery:land REFUSED · " why))
  (println (str "\nHow to clear it:\n  " way-out))
  1)

(defn ^{:malli/schema [:=> [:cat [:* :any]] :int]}
  land
  "bb nido:recovery:land [:run <id>] — land this recovery's nido fix. Refuses
   unless the run's diagnosis names a nido defect and the worktree is nido's; then
   lands through `nido-land/gate!` — rebase onto origin's main, bb nido:test and
   bb nido:land:check at that tip, push — and records the :merged on the run once."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        cwd      (cwd-of opts)
        session  (lifecycle/session-from-cwd cwd)
        run-key  (run-key-of opts)
        recorded (when run-key (records/of run-key))
        diagnosis (last (filter #(= :session-diagnosis (:kind %)) recorded))]
    (cond
      (not= "nido" (some-> (:project session) name))
      (refused (str cwd " is not a nido worktree — this verb lands only nido's own repository")
               "a project defect is a pull request in that project, never a landing here")

      (nil? run-key)
      (refused "no recovery run" "pass :run <id>, or call it from the recovery run's own turn")

      (not= :nido-defect (:verdict diagnosis))
      (refused (str "the run's diagnosis is "
                    (if diagnosis (name (:verdict diagnosis)) "missing")
                    ", not a nido defect")
               "record a :session-diagnosis naming a nido defect, with its evidence, before landing")

      :else
      (let [{:keys [landed way-out] why :refused} (nido-land/gate! cwd)]
        (if why
          (refused why way-out)
          (let [title  (:out (jj/jj! cwd "log" "-r" landed "--no-graph" "-T" "description.first_line()"))
                fresh? (not-any? #(and (= :merged (:kind %)) (= landed (:commit %))) (records/of run-key))]
            (when fresh?
              (records/append-to-run! run-key {:kind   :merged
                                               :commit landed
                                               :url    (commit-url cwd landed)
                                               :title  (str/trim (str title))}))
            (println (str "recovery:land ok · " (subs landed 0 12) " is on origin's main"
                          (when fresh? ", and its landing is recorded")))
            0))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  restore-cmd [& args] (System/exit (apply restore args)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  land-cmd [& args] (System/exit (apply land args)))
