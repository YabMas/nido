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
   [clojure.string :as str]
   [nido.coordinator.session :as csession]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.review.analysis :as analysis]
   [nido.review.frontend :as frontend]
   [nido.review.record :as record]
   [nido.review.loop :as rloop]
   [nido.review.render :as render]
   [nido.review.retreat :as retreat]
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

(defn parked-blocker
  "Pure: the halt a run holding parked findings owes a human, or nil.

   A park is the one disposition whose answer is not the loop's to give — it
   means the finding contradicts a named invariant, so the design is in question
   rather than its execution. Until now that ended as a status the task printed
   and a report nobody was told to open; a run that takes hours and finishes
   while nobody is watching has told no one anything.

   The branches are the two the warden could already see, and they are stated as
   what taking each COSTS rather than as their names: a gate answered on a name
   alone is how the wrong branch gets taken by a click. `:options` rather than
   prose because the ledger refuses a choice written as an essay, and rightly —
   an essay can only be answered by typing one back."
  [findings]
  (when-let [parked (seq (filter #(= :park (:disposition %)) findings))]
    (let [titles (str/join "; " (map :title parked))]
      {:format  :blocker
       :summary (str "The review loop is holding " (count parked)
                     (if (= 1 (count parked)) " finding" " findings")
                     " it has no move for: " titles)
       :needs   (str "Each of these says the design is in question rather than its "
                     "execution, so the loop stopped rather than patching it away. "
                     "Does the design stand?")
       :options [{:label "The design stands"
                  :summary "The findings are answered by the design as written."
                  :consequence (str "They are declined on the record and stop being "
                                    "re-raised. If that is wrong, the next round has "
                                    "no way to tell.")}
                 {:label "The design is wrong"
                  :summary "Supersede the design record, then re-run the review."
                  :consequence (str "Everything judged against the old record is "
                                    "judged again, including work already fixed.")}]})))

(defn append-blocker!
  "Append the halt, if there is one. Best-effort for the same reason
   `append-review-entry!` is: a finished review must not become a failure because
   a side record could not be written. Returns the blocker, or nil."
  [cwd final]
  (try
    (when-let [blocker (parked-blocker (:findings final))]
      (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
        (when-let [ws-id (csession/workstream-id-for (keyword project) session)]
          (ws/append-entry! (keyword project) ws-id {:kind :blocker}
                            (pr-str blocker))
          blocker)))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "review-loop: could not append the :blocker — " (ex-message e))))
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

(def default-launch-budget
  "The wall clock every agent launch a review loop makes, when the caller names none.

   A DEFAULT, not a cap on the run: the loops are deliberately uncapped in
   ROUNDS — they end when they converge, escalate, retreat or stall — and this
   bounds one launch inside a round. The two are not substitutes. With rounds
   uncapped, a single hung claude is the one failure the loop cannot detect on
   its own merits, because a round that never returns never reports anything to
   stall on.

   It exists because agent/launch! now refuses an undeclared budget. Before that,
   omitting it here meant every warden, fixer and amender ran with no timer at
   all — which the record loop's own docstring already assumed was impossible,
   calling this per-launch wall clock \"the only thing between a hung claude and a
   loop that never returns.\""
  "30m")

(defn loop-cmd* [{:keys [cwd base max-iters dry-run? budget]}]
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
                    ;; The per-launch wall clock, which is a different thing and
                    ;; DOES default — see default-launch-budget.
                    :budget    (or budget default-launch-budget)
                    :dry-run?  (boolean dry-run?)
                    :run-id    run-id
                    :clock     clock
                    ;; What the engine cannot ask for itself: it never looks
                    ;; inside a finding, so the pipeline that knows what a
                    ;; disposition means is the one that says whether anything
                    ;; is still owed.
                    :open?     (complement stages/settled?)}
        report-atom (atom (report/init {:run-id run-id :cwd cwd :base base
                                        :started-at (str (clock))}))
        final  (frontend/with-live-display
                 {:report-atom report-atom :report-path report-path :clock clock}
                 (fn [emit] (rloop/run-loop (assoc config :emit emit))))
        status (:status final)
        ws-id  (append-review-entry! cwd final @report-atom report-path)]
    (println (str "review-loop: " (name status) " · report " report-path))
    (when-let [b (append-blocker! cwd final)]
      (println (str "review-loop: ⚠ " (:summary b)))
      (println "  → answer it at the workstream gate; the loop has no move for it"))
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
   :no-progress "the amender stopped changing anything the judge cares about — the findings below are what it left"
   ;; See `shared-remedy`: :no-progress has two causes and they ask different
   ;; things of a reader. This is the one where nothing was amended.
   ::still-refuted "the same claims are refuted again after being corrected — the amender did its work and the judge found another way each one is false, so these are not going to be settled by wording"
   :unfixable "everything fixable was fixed; what remains was raised three rounds running and did not move — these are for you"
   :disputed   "the judge restated a finding the amender objected to twice — neither can settle it, so you do"
   :amend-noop "the amender produced no record — nothing was appended"
   :amend-unreadable "the amender's answer would not parse as EDN"
   :amend-invalid "the ledger refused the amended record"
   :amend-touched-code "a record pass wrote to the working copy; whatever it wrote is still there"
   :dry-run    "nothing was amended"
   ;; Only reachable when a caller asked for a cap. The loop has no default one
   ;; — it ends on its own merits — so this is the reader's own bound coming
   ;; back, and saying so is the difference between "it stopped" and "you
   ;; stopped it".
   :max-iters  "the cap you passed was reached — this is not convergence, and the findings below were still open"
   :no-workstream "run this from a nido session — its worktree or its session home"
   :codex-failed "the judge did not run — this is NOT a clean result"
   :no-output  "the judge ran and wrote nothing — NOT a clean result"
   :unusable-answer "the judge answered, but not in a form a record accepts"
   :round-crashed "the round threw before it could degrade"})

(defn- shared-remedy
  "The shared line, plus the one status whose meaning depends on what the round
   actually did.

   :no-progress means the finding set repeated, and with a record loop's
   identity that has two causes. An amender that produced nothing is the one the
   default line describes. The other is an amender that corrected exactly what
   was named, and a judge that came back with a DIFFERENT way the same claim is
   false — measured: two rounds on one survey, shape and composition both
   refuted twice, each time by a distinct counterexample, the record amended
   both times. Telling that reader the amender stopped working is false and
   points them at the wrong thing: what they have is a claim that cannot be made
   true by re-wording, which is a decision about the code."
  [status final]
  ;; From the HISTORY, not the terminal ctx: a run that ends on a judgement
  ;; never reaches an amend stage, so the ctx cannot say whether earlier rounds
  ;; repaired anything and would report every such run as an amender that quit.
  (if (and (= :no-progress status) (some :amended? (:history final)))
    (shared-remedies ::still-refuted)
    (shared-remedies status)))

(defn- finding-name
  "What to call a finding that is only known here by its identity key.

   The key is the pipeline's own value and its shape is its business, not a
   reader's — but every shape it takes names its subject somewhere inside. A
   claim key carries the id as a string; the two closed-vocabulary keys, a
   design check and a survey gap, carry it as the keyword after the tag. The
   tag is skipped: `blocks` and `check` say which KIND of handle this is, and
   printing that instead of `decomposable` names the mechanism rather than the
   thing the run could not resolve."
  [k]
  (let [parts (flatten [k])]
    (or (some #(when (string? %) %) parts)
        (some #(when (keyword? %) (name %)) (rest parts))
        (pr-str k))))

(defn- record-loop-cmd*
  "Drive a record pipeline through the engine inside the live frame.

   No default cap, for the same reason the diff loop has none: the run ends when
   it converges, escalates, retreats, stalls or fails. `:max-iters` only caps it
   when a caller asks. `:budget` bounds each amender launch — with the iteration
   count uncapped, that per-launch wall clock is the only thing between a hung
   claude and a loop that never returns.

   Two working directories, and keeping them apart is the whole of `:code-cwd`.
   `:cwd` anchors the LEDGER — it resolves the session and so the workstream
   whose records this run reads and amends. `:code-cwd` is where the agents
   read, and it defaults to `:cwd` because they are usually the same tree.

   They are not the same tree when a survey describes an area BEFORE a change
   that is already written. A baseline is supposed to be fillable without
   knowing the fix; judged against a worktree that carries the fix, it is told
   its own subject does not exist — the round reports the change's new modules
   as things the survey failed to mention, and an amender asked to repair that
   folds the change INTO the survey it was supposed to be judged against. The
   record then describes the post-change world, and every relation the design
   declares to it is answered against a premise that already contains the
   answer.

   So the revision is an axis of its own, separate from the workstream: point
   `:code-cwd` at a checkout of the base and the survey is judged against the
   area as it was, while the ledger stays where the work is.

   The final block prints from a `finally`, so a loop that throws still leaves
   its rounds, its weakenings and its objections on screen."
  [{:keys [kind pipeline finding-key remedies epilogue]}
   {:keys [cwd code-cwd max-iters dry-run? budget baseline]
    :or   {budget default-launch-budget}}]
  (let [;; Through the home-aware union whether the caller named a directory or
        ;; not. A session home is a place an agent legitimately stands — it is
        ;; where the briefing and the MCP config are, and every other cwd-based
        ;; verb accepts one — but `project+ws-from-cwd` resolves only inside the
        ;; worktree. Passing :cwd explicitly used to skip the union entirely, so
        ;; naming a home that the no-argument form would have accepted failed as
        ;; :no-workstream, advising the caller to go somewhere they already were.
        given  (or cwd (System/getProperty "user.dir"))
        cwd    (or (lifecycle/worktree-from-cwd given) given)
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
                   #(rloop/run-loop (cond-> {:cwd cwd :code-cwd code-cwd
                                             :run-id run-id
                                             :max-iters max-iters
                                             :dry-run? (boolean dry-run?)
                                             :budget budget
                                             :clock clock :emit emit
                                             :pipeline pipeline
                                             ;; Both record pipelines judge in
                                             ;; their first stage. The diff loop
                                             ;; passes none: its last stage does
                                             ;; work rather than reporting, and
                                             ;; nothing has shown the same cost
                                             ;; there.
                                             :judged-after :judge
                                             :finding-key finding-key}
                                      baseline (assoc :baseline baseline))))
                 (finally
                   (println (render/record-final @report-atom {:title title}))))
        status (:status final)]
    (println (str kind "-loop: " (name status) " · report " report-path))
    ;; :amend-error is one of two ways a run explains itself. The other is the
    ;; :detail on a no-verdict outcome, and it was never printed — so a run that
    ;; stopped because the design cites an unverified survey said which status it
    ;; ended in and never said WHICH survey, which is the only part a reader
    ;; needs to act.
    (when-let [detail (or (:amend-error final) (get-in final [:record :detail]))]
      (println (str "  " detail)))
    (doseq [k (:unfixable final)]
      (println (str "  ↯ " (finding-name k)
                    " — raised and re-raised, never resolved")))
    ;; The other way an amendment costs something, and the one the Weakened
    ;; section cannot report: it answers "did the record claim LESS", and a
    ;; record talked out of checkability claims more. A run reported that it had
    ;; given nothing up while its composition went from four sentences to a page.
    (when-let [g (retreat/growth-summary
                  (retreat/growth (get-in final [:carry :as-authored])
                                  (get-in final [:carry :under-repair])))]
      (println "  Grown past checking — a claim this long is one nobody can check:")
      (println g))
    ;; The pipeline's own remedies first, then the ones every record loop shares.
    ;; A lookup FN rather than a map, because a nested loop's terminal status
    ;; comes through prefixed and the set is open by construction.
    (println (str "  → " (or (and remedies (remedies status))
                             (shared-remedy status final)
                             (str "unrecognised terminal status: " status))))
    (when epilogue (epilogue final))
    status))

(def ^:private baseline-remedies
  "Only :sufficient ends a run. :insufficient is a VERDICT and never a status —
   a gap is something an amender can answer, so the round that reports one keeps
   going. It had a line here, which said the loop can end that way; it cannot."
  {:sufficient "the survey holds against the code, and a decision can be made against it"
   :no-record "author the baseline first"
   :nothing-to-check "nothing in the survey is refutable yet"})

(defn- baseline-at
  "The survey named by :seq, or nil for `whichever is newest`.

   A workstream can hold surveys of DIFFERENT areas — a narrow follow-up beside
   the broad one it came out of — and a design cites one of them specifically.
   Without this the only survey reachable from the command line is the newest,
   so the advice a blocked design gives (`verify the survey it cites first`)
   names a command that cannot verify that survey. It runs, it verifies the
   other one, and the design stays blocked with nothing to show for it.

   A :seq naming no entry is refused rather than silently falling back to the
   newest, which would be the same wrong survey with no way to tell."
  [cwd n]
  (when n
    (let [n (long n)
          [project ws-id] (or (stages/project+ws-from-cwd cwd)
                              (throw (ex-info (str "no nido session at " cwd) {})))]
      ;; A readable entry is not enough — an entry of the wrong KIND is the
      ;; likelier typo, since a workstream's baselines and their reviews
      ;; interleave and sit one apart. Handing the loop a review to verify is
      ;; not a smaller mistake than handing it nothing.
      (let [e (ws/entry-at-seq project ws-id n)]
        (or (when (= :baseline (:format e)) e)
            (throw (ex-info (str "entry " n " is not a readable :baseline on this workstream"
                                 (when-let [f (:format e)] (str " — it is a " f)))
                            {:seq n :format (:format e)})))))))

(defn baseline-cmd*
  "Verify a baseline against the code, and keep correcting it until the code
   stops refuting it. :seq names WHICH survey; the default is the newest."
  ;; `seq-n`, not `seq` — destructuring the key by its own name would shadow
  ;; clojure.core/seq for the whole body.
  [{:keys [cwd] seq-n :seq :as opts}]
  (record-loop-cmd* {:kind "baseline"
                     :pipeline    record/baseline-pipeline
                     :finding-key record/baseline-finding-key
                     :remedies    baseline-remedies}
                    (cond-> opts
                      seq-n (assoc :baseline
                                   (baseline-at (or (some-> cwd lifecycle/worktree-from-cwd)
                                                    cwd
                                                    (lifecycle/worktree-from-cwd))
                                                seq-n)))))

(def ^:private design-remedies
  {:proceed "nothing derivable blocks it — what is left is the part only you can answer"
   :underivable "a check has no yardstick to derive against, which is not a defect an amender can repair"
   :premise-unverified "verify the survey it cites first — `bb nido:review:baseline :seq <that entry>` — then decide against it"
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
