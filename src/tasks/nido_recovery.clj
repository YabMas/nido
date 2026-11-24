(ns tasks.nido-recovery
  "bb nido:recovery:* — the verbs a recovery session works through: read what
   failed, restore it, land a nido fix.

   The land verb is the gate, not advice. A recovery lands on nido's main with
   nobody watching, so the verb runs the tests and the landing check itself, at
   the exact tip it pushes, and refuses on a workstream whose diagnosis does not
   name a nido defect — a skill that skipped a step cannot land around it."
  (:require
   [clojure.string :as str]
   [nido.coordinator.lane.restore :as restore]
   [nido.coordinator.record.workstream :as cws]
   [nido.coordinator.source.start-failures :as start-failures]
   [nido.platform.task-args :as task-args]
   [nido.review.stages :as stages]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.jj :as jj]
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

(defn- turn-ws
  "The workstream the core turn this process runs in works on, as [project ws-id], or nil
   outside one. A recovery the core provisions is a unit whose session no workstream record
   holds, so its worktree names no workstream; the core names it in NIDO_CORE_WS instead."
  []
  (when-let [k (System/getenv "NIDO_CORE_WS")]
    (let [i (str/index-of k "/")]
      (when (and i (pos? i)) [(keyword (subs k 0 i)) (subs k (inc i))]))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :int]}
  land
  "bb nido:recovery:land — land this recovery's nido fix. Refuses unless the
   workstream's diagnosis names a nido defect and the worktree is nido's; then lands
   through `nido-land/gate!` — rebase onto origin's main, bb nido:test and
   bb nido:land:check at that tip, push — and records the :merged once. It does
   not close the workstream: restore does, once every failure is back."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        cwd      (cwd-of opts)
        session  (lifecycle/session-from-cwd cwd)
        [project ws-id] (or (stages/project+ws-from-cwd cwd) (turn-ws))
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
      (let [{:keys [landed way-out] why :refused} (nido-land/gate! cwd)]
        (if why
          (refused why way-out)
          (let [title  (:out (jj/jj! cwd "log" "-r" landed "--no-graph" "-T" "description.first_line()"))
                result (cws/append-entry-once!
                        project ws-id {:kind :merged}
                        (pr-str {:format :merged
                                 :commit landed
                                 :url    (commit-url cwd landed)
                                 :title  (str title)})
                        #(= landed (:commit %)))]
            (println (str "recovery:land ok · " (subs landed 0 12) " is on origin's main"
                          (when (:appended result) ", and its landing is recorded")))
            0))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  restore-cmd [& args] (System/exit (apply restore args)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  land-cmd [& args] (System/exit (apply land args)))
