(ns tasks.nido-recovery
  "bb nido:recovery:* — the verbs a recovery session works through: read what
   failed, restore it, land a nido fix.

   The land verb is the gate, not advice. A recovery lands on nido's main with
   nobody watching, so the verb runs the tests and the landing check itself, at
   the exact tip it pushes, and refuses on a workstream whose diagnosis does not
   name a nido defect — a skill that skipped a step cannot land around it."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]
   [nido.coordinator.lane.restore :as restore]
   [nido.coordinator.record.workstream :as cws]
   [nido.coordinator.source.start-failures :as start-failures]
   [nido.platform.task-args :as task-args]
   [nido.review.layers :as layers]
   [nido.review.stages :as stages]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]
   [nido.vsdd.jj :as jj]
   [tasks.nido-land :as nido-land]))

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
    (println (str "\nRecovery workstreams for cause " cause ": " (count mine)))
    (doseq [{:keys [ws-id closed runs named settled]} mine]
      (let [failed (->> (reverse runs) (take-while #(= :failed (:state %))) count)]
        (println (str "  " ws-id "  " (if closed (str "closed " (name (:outcome closed)) " " (:at closed)) "open")
                      "  runs " (count runs) (when (pos? failed) (str " (" failed " failed in a row)"))
                      "  named " (count named) "  settled " (count settled)))
        (when-let [d (cws/latest-entry :nido ws-id :session-diagnosis)]
          (println (str "    diagnosis: " (name (:verdict d)) " — " (:cause d))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  failures
  "bb nido:recovery:failures [:cause <key>] [:all true]
   Kept session-start failures with their evidence — the owed ones, or all of
   them — and, for a cause, the recovery workstreams that already tried it."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        cause    (some-> (:cause opts) str)
        recs     (start-failures/recoveries :nido)
        all      (failure/failures)
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
  "bb nido:recovery:restore [:ws-id <id>] — restore the owed failures of this
   recovery workstream's cause. Exits 1 on a refusal or when a failure was not
   restored."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        cwd      (cwd-of opts)
        ws-id    (or (some-> (:ws-id opts) str) (second (stages/project+ws-from-cwd cwd)))]
    (if-not ws-id
      (do (println "restore REFUSED · no recovery workstream: pass :ws-id or run from the recovery worktree")
          1)
      (try
        (let [{:keys [outcomes closed?]} (restore/restore! :nido ws-id)]
          (if (empty? outcomes)
            (println "restore · nothing owed for this cause")
            (doseq [{:keys [failure outcome continuation failure-left note]} outcomes]
              (println (str "restore · " failure " " (name outcome)
                            (when continuation (str " → " (pr-str continuation)))
                            (when failure-left (str " · its attempt kept " failure-left))
                            (when note (str " · " note))))))
          (println (if closed?
                     (str "restore · " ws-id " closed: every failure named on it is restored or withdrawn")
                     (str "restore · " ws-id " stays open")))
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
  "bb nido:recovery:land — land this recovery's nido fix. Refuses unless the
   workstream's diagnosis names a nido defect, the worktree is nido's and its tip
   sits on origin's main, and bb nido:test and bb nido:land:check pass at that
   tip; then fast-forwards origin's main to it and records the :merged once."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        cwd      (cwd-of opts)
        session  (lifecycle/session-from-cwd cwd)
        [project ws-id] (stages/project+ws-from-cwd cwd)
        diagnosis (when ws-id (cws/latest-entry project ws-id :session-diagnosis))]
    (cond
      (not= "nido" (some-> (:project session) name))
      (refused (str cwd " is not a nido worktree — this verb lands only nido's own repository")
               "a project defect is a pull request in that project, never a landing here")

      (nil? ws-id)
      (refused "this worktree belongs to no workstream" "run it from the recovery session's worktree")

      (not= :nido-defect (:verdict diagnosis))
      (refused (str "the workstream's diagnosis is "
                    (if diagnosis (name (:verdict diagnosis)) "missing")
                    ", not a nido defect")
               "append a :session-diagnosis naming a nido defect, with its evidence, before landing")

      :else
      (let [fetched (jj/jj! cwd "git" "fetch")
            tip     (layers/resolve-rev cwd "heads(::@ ~ empty())")
            based?  (when tip (layers/resolve-rev cwd (str "main@origin & ::" tip)))
            landed? (when tip (layers/resolve-rev cwd (str tip " & ::main@origin")))]
        (cond
          (not (zero? (:exit fetched)))
          (refused (str "origin could not be fetched: " (:err fetched)) "check the network, then run it again")

          (nil? tip)
          (refused "the worktree's tip could not be read" "check `jj log` in the worktree")

          landed?
          (refused (str "origin's main already holds " (subs tip 0 12) " — there is nothing of yours to land")
                   "commit the fix on top of main@origin, then run it again")

          (nil? based?)
          (refused (str (subs tip 0 12) " is not on top of origin's main")
                   "jj rebase -d main@origin, then run it again")

          :else
          (let [tested (p/shell {:dir cwd :continue true} "bb" "nido:test")]
            (cond
              (not (zero? (:exit tested)))
              (refused "bb nido:test is red at the tip" "fix what it reports; a red landing is a red main for everyone")

              (not (zero? (nido-land/check ":cwd" cwd)))
              (refused "bb nido:land:check refused the tip" "clear what it names above, then run it again")

              :else
              (let [{:keys [outcome detail]} (lifecycle/advance-remote! cwd "main" tip)]
                (if-not (#{:advanced :already-there} outcome)
                  (refused (str "NOT PUSHED (" (name outcome) ")" (when detail (str ": " detail)))
                           "main probably moved: jj git fetch, jj rebase -d main@origin, run it again")
                  (let [title (:out (jj/jj! cwd "log" "-r" tip "--no-graph" "-T" "description.first_line()"))
                        result (cws/append-entry-once!
                                project ws-id {:kind :merged}
                                (pr-str {:format :merged
                                         :commit tip
                                         :url    (commit-url cwd tip)
                                         :title  (str title)})
                                #(= tip (:commit %)))]
                    (println (str "recovery:land ok · " (subs tip 0 12) " is on origin's main"
                                  (when (:appended result) ", and its landing is recorded")))
                    0))))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  restore-cmd [& args] (System/exit (apply restore args)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  land-cmd [& args] (System/exit (apply land args)))
