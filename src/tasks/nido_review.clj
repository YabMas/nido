(ns tasks.nido-review
  "bb-task entrypoints for the three judgment loops — over a baseline record,
   over a design record, and over a branch diff. All three drive the same engine
   inside a live terminal frontend and persist report.json under the run dir.

   The two record loops share `record-loop-cmd*` and differ in four values: the
   pipeline, the finding identity, what each terminal status asks of the reader,
   and whether there is anything to hand over at the end. The diff loop keeps its
   own command because what it does after the engine stops — the design verdict,
   the ledger event, the queued analysis — has no counterpart before there is
   code.

   See docs/superpowers/specs/2026-06-30-review-tui-frontend-design.md."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.session :as csession]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.review.analysis :as analysis]
   [nido.review.frontend :as frontend]
   [nido.review.record :as record]
   [nido.review.loop :as rloop]
   [nido.review.render :as render]
   [nido.review.stages :as stages]
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

(defn queue-analysis!
  "Queue this run for nido-side analysis. Best-effort, for the same reason
   `append-review-entry!` is: the review is over, and a missing side record must
   not turn a finished review into a failed one.

   Everything the analysis is told about the reviewed branch is a NAME —
   project, session, workstream id. The path is deliberately not passed: the
   analysis runs on nido's side and has no business in the worktree the loop
   just reviewed."
  [cwd final report report-path config ws-id]
  (let [{:keys [project session]} (or (lifecycle/session-from-cwd cwd) {})]
    (analysis/enqueue!
     {:run-id             (:run-id config)
      :report-path        report-path
      :status             (:status final)
      :dry-run?           (:dry-run? config)
      :base               (get-in report [:target :base])
      :rounds             (or (get-in report [:summary :rounds]) 0)
      :findings-fixed     (or (get-in report [:summary :findings-fixed]) 0)
      :findings-remaining (count (verdict/still-open (:findings final)))
      :reviewed-project   project
      :reviewed-session   session
      :reviewed-ws-id     ws-id})))

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
                    ;; No default cap: the loop runs until it converges,
                    ;; escalates, or stops making progress. :max-iters only
                    ;; caps it when the caller explicitly asks for a cap.
                    :max-iters max-iters
                    :dry-run?  (boolean dry-run?)
                    :run-id    run-id
                    :clock     clock}
        report-atom (atom (report/init {:run-id run-id :cwd cwd :base base
                                        :started-at (str (clock))}))
        final  (frontend/with-live-display
                 {:report-atom report-atom :report-path report-path :clock clock}
                 (fn [emit] (rloop/run-loop (assoc config :emit emit))))
        status (:status final)
        ws-id  (append-review-entry! cwd final @report-atom report-path)]
    (println (str "review-loop: " (name status) " · report " report-path))
    (print-verdict! (append-design-verdict! cwd final @report-atom config))
    ;; Last, so the analysis session finds everything this run wrote — the
    ;; report, the :review ledger entry and the design verdict are all on disk
    ;; by the time the envelope exists.
    (queue-analysis! cwd final @report-atom report-path config ws-id)
    status))

(defn loop-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (loop-cmd* opts)))

;; ── Loops over a record, before there is any code ───────────────────────────

(defn- record-loop-title
  "What this loop is judging, for the frame's one title line. The session name
   when there is one — that is what the human running this calls the branch —
   and the workstream id when there is not."
  [cwd kind]
  (let [{:keys [project session]} (or (lifecycle/session-from-cwd cwd) {})
        [p ws-id] (stages/project+ws-from-cwd cwd)]
    (str kind " loop · "
         (cond
           session (str project "/" session)
           ws-id   (str (name (or p "?")) "/" ws-id)
           :else   cwd))))

(def ^:private shared-remedies
  "The ways any record loop can end, and what each one asks of the reader.

   Kept as data rather than a cond, because the one rule this surface has is
   that no two of these may collapse into a shared line: a round that could not
   run and a round that ran and found nothing look identical on a terminal
   unless something insists otherwise."
  {:retreated  "the record was amended below what its own round would check — read the weakenings above before accepting any of it"
   :no-progress "the amender stopped changing anything the judge cares about; the last findings are still open"
   :disputed   "the judge restated a finding the amender objected to twice — neither can settle it, so you do"
   :amend-noop "the amender produced no record — nothing was appended"
   :amend-unreadable "the amender's answer would not parse as EDN"
   :amend-invalid "the ledger refused the amended record"
   :amend-touched-code "a record pass wrote to the working copy; whatever it wrote is still there"
   :dry-run    "nothing was amended"
   :no-workstream "run this from a nido session worktree"
   :codex-failed "the judge did not run — this is NOT a clean result"
   :no-output  "the judge ran and wrote nothing — NOT a clean result"
   :unusable-answer "the judge answered, but not in a form a record accepts"
   :round-crashed "the round threw before it could degrade"})

(defn- record-loop-cmd*
  "Drive a record pipeline through the engine inside the live frame.

   No default cap, for the same reason the diff loop has none: the run ends when
   it converges, escalates, retreats, stalls or fails. `:max-iters` only caps it
   when a caller asks. `:budget` bounds each amender launch — with the iteration
   count uncapped, that per-launch wall clock is the only thing between a hung
   claude and a loop that never returns.

   The final block prints from a `finally`, so a loop that throws still leaves
   its rounds, its weakenings and its objections on screen."
  [{:keys [kind pipeline finding-key remedies epilogue]}
   {:keys [cwd max-iters dry-run? budget]}]
  (let [cwd    (or cwd (lifecycle/worktree-from-cwd) (System/getProperty "user.dir"))
        run-id (str kind "-loop-" (random-uuid))
        clock  #(Instant/now)
        title  (record-loop-title cwd kind)
        report-path (str (fs/path (cstate/run-dir run-id) "report.json"))
        report-atom (atom (report/init {:run-id run-id :cwd cwd :base nil
                                        :started-at (str (clock))}))
        plain  (frontend/plain?)
        emit   (frontend/emit-fn report-atom report-path clock plain)
        final  (try
                 (frontend/with-live-frame
                   {:frame-fn #(render/record-frame @report-atom % {:title title})
                    :clock clock :plain? plain}
                   #(rloop/run-loop {:cwd cwd :run-id run-id
                                     :max-iters max-iters
                                     :dry-run? (boolean dry-run?)
                                     :budget budget
                                     :clock clock :emit emit
                                     :pipeline pipeline
                                     :finding-key finding-key}))
                 (finally
                   (println (render/record-final @report-atom {:title title}))))
        status (:status final)]
    (println (str kind "-loop: " (name status) " · report " report-path))
    (when-let [detail (:amend-error final)]
      (println (str "  " detail)))
    ;; The pipeline's own remedies first, then the ones every record loop shares.
    ;; A lookup FN rather than a map, because a nested loop's terminal status
    ;; comes through prefixed and the set is open by construction.
    (println (str "  → " (or (and remedies (remedies status))
                             (shared-remedies status)
                             (str "unrecognised terminal status: " status))))
    (when epilogue (epilogue final))
    status))

(def ^:private baseline-remedies
  {:accurate "the survey holds against the code"
   :no-record "author the baseline first"
   :nothing-to-check "nothing in the survey is refutable yet"})

(defn baseline-cmd*
  "Verify the workstream's latest baseline against the code, and keep correcting
   it until the code stops refuting it."
  [opts]
  (record-loop-cmd* {:kind "baseline"
                     :pipeline    record/baseline-pipeline
                     :finding-key record/baseline-finding-key
                     :remedies    baseline-remedies}
                    opts))

(def ^:private design-remedies
  {:proceed "nothing derivable blocks it — what is left is the part only you can answer"
   :underivable "a check has no yardstick to derive against, which is not a defect an amender can repair"
   :no-record "author the design first"
   :not-worth-running "the design declares it moves nothing structural, so a decision round would not pay"})

(defn- design-remedy
  "A re-survey that did not hold reports under the nested loop's own terminal
   status, so the outcomes are open-ended by construction. Naming the nested
   status is the whole value of the line — collapsing them to 'the re-survey
   failed' would throw away which loop stopped and why."
  [status]
  (or (design-remedies status)
      (when-let [nested (some->> (name status) (re-find #"^resurvey-(.+)$") second)]
        (str "the baseline loop this round started ended " nested
             " — the premise is still wrong, so nothing here can be decided on it"))))

(defn- design-epilogue
  "What the round could not settle, printed last because it is what the reader
   is actually being handed."
  [final]
  (doseq [{:keys [check note]} (:underivable final)]
    (println (str "  — " (name check) " could not be derived: " note)))
  (when-let [asks (get-in final [:record :asks])]
    (println "\n  FOR YOU TO DECIDE:")
    (println (str "  " asks))))

(defn design-cmd*
  "Decide, against the latest design record, whether this should be executed —
   repairing what is derivable and escalating what is not."
  [opts]
  (record-loop-cmd* {:kind "design"
                     :pipeline    record/design-pipeline
                     :finding-key record/design-finding-key
                     :remedies    design-remedy
                     :epilogue    design-epilogue}
                    opts))

(defn baseline-cmd
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (baseline-cmd* opts)))

(defn design-cmd
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (design-cmd* opts)))
