(ns tasks.nido-review
  "bb-task entrypoint for the codex review loop. Drives the engine inside a live
   terminal frontend (spinner + per-round ledger); persists report.json under
   the run dir. See docs/superpowers/specs/2026-06-30-review-tui-frontend-design.md."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.session :as csession]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [clojure.string :as str]
   [nido.review.frontend :as frontend]
   [nido.review.record :as record]
   [nido.review.loop :as rloop]
   [nido.review.report :as report]
   [nido.review.verdict :as verdict]
   [nido.session.lifecycle :as lifecycle]
   [nido.task-args :as task-args])
  (:import
   [java.time Instant]))

(defn exit-code
  "CLI exit code for a terminal review status. review-failed is the only
   failure; escalated is a reported outcome, not an error."
  [status]
  (if (= :review-failed status) 1 0))

(defn review-event
  "Pure: build a :review ledger payload from the loop's terminal value `final`
   ({:status :findings}) and the folded review `report` ({:summary :target})."
  [final report report-path]
  {:format             :review-report
   :status             (:status final)
   :base               (get-in report [:target :base])
   :base-rev           (get-in report [:target :base-rev])
   :rounds             (or (get-in report [:summary :rounds]) 0)
   :findings-fixed     (or (get-in report [:summary :findings-fixed]) 0)
   :findings-remaining (count (verdict/still-open (:findings final)))
   :report-path        report-path})

(defn append-review-entry!
  "Resolve cwd → session → workstream (the nido.ship path) and append one :review
   entry. Best-effort: a ledger-write failure must never turn a completed review
   into a failure exit — visibility is a side record, not part of the review. No-op
   returning nil when cwd maps to no workstream or the append fails. Returns ws-id."
  [cwd final report report-path]
  (try
    (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
      (when-let [ws-id (csession/workstream-id-for (keyword project) session)]
        (ws/append-entry! (keyword project) ws-id {:kind :review}
                          (pr-str (review-event final report report-path)))
        ws-id))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "review-loop: could not append :review ledger event — "
                      (ex-message e))))
      nil)))

(defn verdict-worth-running?
  "The verdict pass judges findings against the design, so it needs findings.
   A failed review has nothing to judge; a dry run made no changes; a review that
   never surfaced anything has no evidence either way — and paying for an agent to
   conclude nothing would make the verdict noise rather than signal."
  [status final]
  (and (not (#{:review-failed :dry-run} status))
       (boolean (or (seq (:findings final)) (seq (:history final))))))

(defn append-design-verdict!
  "Run the design verdict and append it as a ledger event. Best-effort throughout,
   for the same reason append-review-entry! is: a completed review must not turn
   into a failure because a side record could not be written. Returns the verdict
   map, or nil when it did not run or produced no answer."
  [cwd final report config]
  (try
    (when (verdict-worth-running? (:status final) final)
      (when-let [v (verdict/run! {:cwd cwd
                                  :run-id (:run-id config)
                                  :budget (:budget config)
                                  :final final
                                  :report report})]
        (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
          (when-let [ws-id (csession/workstream-id-for (keyword project) session)]
            (ws/append-entry! (keyword project) ws-id {:kind :design-verdict}
                              (pr-str v))))
        v))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "review-loop: design verdict skipped — " (ex-message e))))
      nil)))

(defn- print-verdict!
  "Report the verdict on the terminal. An :invalidated / :standing-challenged
   verdict is a decision for the human, and `nido review:loop` is human-invoked —
   they are sitting in front of this — so it is stated loudly rather than filed
   somewhere they might not look.

   A verdict can now point at two different remedies, and saying the wrong one is
   worse than saying nothing: a broken load-bearing property means the change did
   not do what it said, while a finding classified :baseline means the survey was
   wrong and the design may be fine. Redesign and re-survey are not the same
   instruction."
  [v]
  (when v
    (println (str "review-loop: design verdict — " (name (:verdict v))))
    (println (str "  " (:reason v)))
    (when-let [broken (seq (:load-bearing-broken v))]
      (println "  ⚠ load-bearing properties broken without being declared:")
      (doseq [{:keys [invariant finding]} broken]
        (println (str "      " invariant))
        (println (str "        by: " finding)))
      (println "  → the change is not what the design said it was: either declare
      the break (:relation :revisit, naming it in :breaks) or undo it"))
    (when (some #(= :baseline (:as %)) (:findings-classified v))
      (println "  ⚠ a finding says the BASELINE was wrong, not the design")
      (println "  → re-survey the area; the design may be sound on a bad premise"))
    (when (verdict/decision? v)
      (println "  ⚠ this is a decision, not a fix — the design itself is in question")
      (when-let [n (:needs v)] (println (str "  needs: " n)))
      (println "  → supersede the design record (/design §5) or accept it explicitly"))))

(defn loop-cmd* [{:keys [cwd base max-iters dry-run?]}]
  (let [cwd        (or cwd
                       (lifecycle/worktree-from-cwd)
                       (System/getProperty "user.dir"))
        base       (or base "main")
        run-id     (str "review-" (random-uuid))
        clock      #(Instant/now)
        report-path (str (fs/path (cstate/run-dir run-id) "report.json"))
        config     {:cwd cwd :base base
                    :max-iters (or max-iters 5)
                    :dry-run?  (boolean dry-run?)
                    :run-id    run-id
                    :clock     clock}
        report-atom (atom (report/init {:run-id run-id :cwd cwd :base base
                                        :started-at (str (clock))}))
        final  (frontend/with-live-display
                 {:report-atom report-atom :report-path report-path :clock clock}
                 (fn [emit] (rloop/run-loop (assoc config :emit emit))))
        status (:status final)]
    (append-review-entry! cwd final @report-atom report-path)
    (println (str "review-loop: " (name status) " · report " report-path))
    (print-verdict! (append-design-verdict! cwd final @report-atom config))
    status))

(defn loop-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (loop-cmd* opts)))

;; ── Rounds over a record, before there is any code ──────────────────────────

(defn- print-record-round!
  "Report a record round on the terminal. Both are human-invoked, so the outcome
   is stated here rather than filed somewhere nobody looks — and the two
   non-accurate baseline verdicts and the three non-proceed recommendations each
   point at a DIFFERENT remedy, so the remedy is named rather than implied."
  [record]
  (if-let [outcome (:outcome record)]
    ;; Never one line for every way a round can produce nothing. A round that
    ;; could not run is not a round that found nothing to say, and reading the
    ;; second as the first is the one mistake a judgment surface must not invite.
    (do (println (str "no judgment recorded — " (name outcome)))
        (println (str "  " (:detail record)))
        (println (str "  → " (case outcome
                               :no-workstream    "run this from a nido session worktree"
                               :no-record        "author the record first"
                               :nothing-to-check "nothing in the survey is refutable yet"
                               :not-worth-running "the records say a round would not pay here"
                               :codex-failed     "the judge did not run — this is NOT a clean result"
                               :no-output        "the judge ran and wrote nothing — NOT a clean result"
                               :round-crashed    "the round threw before it could degrade"
                               :unusable-answer  "the judge answered, but not in a form a record accepts"
                               "unrecognised outcome"))))
    (case (:format record)
      :baseline-review
      (do (println (str "baseline review: " (name (:verdict record))))
          (println (str "  " (:reason record)))
          (doseq [{:keys [cites claim]} (:findings record)]
            (println (str "  ✗ " (str/join "; " cites)))
            (println (str "      " claim)))
          (when (not= :accurate (:verdict record))
            (println "  → re-survey; the design may be sound on a bad premise")))

      :design-decision
      (do (println (str "design decision: " (name (:recommend record))))
          (println (str "  " (:reason record)))
          (doseq [{:keys [check held? note]} (:checks record)]
            (println (str "  " (if held? "✓" "✗") " " (name check) " — " note)))
          (doseq [{:keys [cites claim]} (:findings record)]
            (println (str "  ✗ " (str/join "; " cites)))
            (println (str "      " claim)))
          (println (str "  → " (case (:recommend record)
                                 :proceed  "nothing derivable blocks it"
                                 :amend    "amend the record"
                                 :recut    "the decomposition does not hold — recut the layers"
                                 :resurvey "re-survey; the premise is wrong, not the commitment")))
          (println "\n  FOR YOU TO DECIDE:")
          (println (str "  " (:asks record))))

      (println (str "recorded " (name (:format record)))))))

(defn- record-round-cmd*
  [round {:keys [cwd goals]}]
  (let [cwd    (or cwd (lifecycle/worktree-from-cwd) (System/getProperty "user.dir"))
        run-id (str (name round) "-" (random-uuid))
        record (case round
                 :baseline-review (record/baseline-review! {:cwd cwd :run-id run-id})
                 :design-decision (record/design-decision! {:cwd cwd :run-id run-id
                                                            :goals goals}))]
    (record/append! cwd record)
    (print-record-round! record)
    record))

(defn baseline-cmd
  "Verify the workstream's latest baseline against the code."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (record-round-cmd* :baseline-review opts)))

(defn design-cmd
  "Run the pre-implementation decision round over the latest design record."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (record-round-cmd* :design-decision opts)))
