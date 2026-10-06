(ns nido.review.analysis-test
  (:require
   [clojure.string :as str]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.source.queue :as queue]
   [nido.review.analysis :as analysis]))

(def a-run
  {:run-id "review-abc" :report-path "/r/report.json" :status :converged
   :base "main" :rounds 3 :fix-attempts 5 :defects-settled 3 :findings-remaining 1
   :targets-reviewed 3 :targets-skipped 5
   :reviewed-project :brian :reviewed-session "fix/thing" :reviewed-ws-id "ws-1"})

(deftest the-external-ref-names-its-own-adapter
  ;; spawn/external-ref defaults the adapter to :notion. Left to the default,
  ;; every analysis would mint a workstream claiming a Notion identity it has
  ;; not got.
  (let [p (analysis/payload a-run)]
    (is (= :review-run (:adapter p)))
    (is (= "review-abc" (:id p)))))

(deftest the-payload-carries-the-counts-not-just-a-path-to-them
  ;; The run dir is reclaimable; being reclaimed is its normal end state. What
  ;; the payload states itself is what survives that.
  (let [p (analysis/payload a-run)]
    (is (= "converged" (:status p)))
    (is (= 3 (:rounds p)))
    (is (= 5 (:fix-attempts p)))
    (is (= 3 (:defects-settled p))
        "what the run removed travels beside what it dispatched — the two are
         different sizes, and an analysis grading the loop needs both")
    (is (= 1 (:findings-remaining p)))
    (is (= "/r/report.json" (:report-path p)))))

(deftest the-payload-says-how-much-of-the-stack-was-read
  ;; A status is a status of something. A `clean` over three targets out of
  ;; eight and one over all eight reached the analysis identically, and the
  ;; report that would have said which is in a run dir routinely reclaimed.
  (let [p (analysis/payload a-run)]
    (is (= 3 (:targets-reviewed p)))
    (is (= 5 (:targets-skipped p))))
  (let [p (analysis/payload (assoc a-run :targets-reviewed 8 :targets-skipped 0))]
    (is (= 0 (:targets-skipped p))
        "carried at zero: it is the payload asserting the whole stack was read,
         which is the claim that cannot be made by saying nothing")))

(deftest the-payload-names-what-the-run-stopped-on
  ;; The run dir is reclaimable and being reclaimed is its normal end state, so
  ;; an analysis asked whether the loop stopped for the right reason had a
  ;; status, three counts and no way to name the findings it stopped over.
  (let [p (analysis/payload
           (assoc a-run
                  :status :unfixable :findings-remaining 2 :remaining-parked 1
                  :unfixable ["09a09d87"]
                  :parked [{:handle "09a09d87" :since 3 :title "the source-row seam"
                            :owner-layer "source-row-variants"}]))]
    (is (= ["09a09d87"] (:unfixable p)))
    (is (= "the source-row seam" (:title (first (:parked p)))))
    (is (= 1 (:remaining-parked p))
        "one of the two remaining is a question for a human; the counts alone
         make it look like unfinished work")))

(deftest the-payload-names-what-the-warden-left-standing
  ;; The reader that needs it most and can least get it. An analysis grading
  ;; whether the loop stopped for the right reason is asked exactly about the
  ;; items no count mentions — and it is the reader whose run dir is gone.
  (let [p (analysis/payload
           (assoc a-run :standing [{:what "bb format is red on seven blocks"
                                    :why-no-finding "not a fixer's work"}]))]
    (is (= ["bb format is red on seven blocks"] (mapv :what (:standing p))))))

(deftest the-payload-says-why-no-reviewer-could-be-run
  ;; The headline is the analysis session's whole briefing. With the status
  ;; alone, the session recovered the cause by tailing a reviewer log.
  (let [u {:signal :usage-limit :retry-at "Sep 15th, 2026 8:35 AM"
           :message "You've hit your usage limit. Try again at Sep 15th, 2026 8:35 AM."}
        p (analysis/payload (assoc a-run :status :reviewer-unavailable :unavailable u))]
    (is (= u (:unavailable p)))
    (is (str/includes? (:headline p) (:message u))
        "in the one line the session is briefed with, not only in a key it has to know to read")))

(deftest a-run-that-stopped-on-nothing-says-nothing
  ;; Carried only when there is something to carry, like :remaining-handed —
  ;; a converged run's payload should not assert an empty handover.
  (let [p (analysis/payload a-run)]
    (is (not (contains? p :unfixable)))
    (is (not (contains? p :parked)))
    (is (not (contains? p :standing)))
    (is (not (contains? p :unavailable)))
    (is (not (str/includes? (:headline p) "unavailable")))
    (is (not (contains? p :remaining-parked)))))

(deftest the-reviewed-branch-is-named-never-located
  (let [p (analysis/payload a-run)]
    (is (= "brian" (:reviewed-project p)))
    (is (= "fix/thing" (:reviewed-session p)))
    (is (= "ws-1" (:reviewed-ws-id p)))
    (is (not-any? #(str/includes? (str %) "worktree") (vals p))
        "no path into the reviewed worktree reaches the analysis")))

(deftest the-payload-counts-decisions-apart-from-the-remainder
  ;; A declined defect is not owed to anyone, so it is out of the remainder —
  ;; but a run that declines its way to `converged` and one that fixes its way
  ;; there are the same status, and the analysis is what has to tell them apart.
  (let [p (analysis/payload (assoc a-run :findings-remaining 1 :findings-kept 2))]
    (is (= 1 (:findings-remaining p)))
    (is (= 2 (:findings-kept p))))
  (is (= 0 (:findings-kept (analysis/payload a-run)))
      "carried at zero, unlike the optional counts beside it: a trigger template
       renders a missing value as the empty string, so an omitted key briefs the
       analysis as ` · kept` rather than as `0 kept`"))

(deftest the-title-carries-the-parks-and-nothing-else
  ;; The title is what a human reads off the board without opening anything, and
  ;; a park is the one count that is asking them for something.
  (let [p (analysis/payload (assoc a-run :status :unfixable
                                   :findings-remaining 3 :remaining-parked 1
                                   :remaining-handed 1 :findings-kept 2))]
    (is (str/includes? (:title p) "1 parked"))
    (is (not (str/includes? (:title p) "kept"))))
  (is (not (str/includes? (:title (analysis/payload a-run)) "parked"))
      "a run waiting on nobody says so by saying nothing"))

(deftest the-title-says-which-run-this-was
  (let [p (analysis/payload a-run)]
    (is (str/includes? (:title p) "converged"))
    (is (str/includes? (:title p) "fix/thing"))
    (is (str/includes? (:title p) "3 rounds"))))

(deftest an-owed-verification-is-on-the-open-side-of-the-headline
  ;; One run published `0 still open · 1 kept` over a verdict whose :needs said
  ;; an invariant was never verified on Linux.
  (let [p (analysis/payload (assoc a-run :design-verdict "sound" :verdict-unverified 1))]
    (is (str/includes? (:headline p) "still open · 1 verification owed · ")
        "evidence somebody owes is open work, and reads as such before `kept`")
    (is (= 1 (:verdict-unverified p))))
  (let [p (analysis/payload (assoc a-run :design-verdict "sound"))]
    (is (not (str/includes? (:headline p) "owed")))
    (is (not (contains? p :verdict-unverified)))))

(deftest a-defect-the-verdict-found-is-on-the-open-side-of-the-headline
  ;; `2 kept` was published over two :unraised rows nobody ruled on, and over a
  ;; carried verdict the same two were `kept` in two runs' headlines.
  (let [p (analysis/payload (assoc a-run :design-verdict "sound" :verdict-unraised 2))]
    (is (str/includes? (:headline p) "still open · 2 unraised by the verdict · 0 kept")
        "nobody decided to ship them, so they read as open work, before `kept`")
    (is (= 2 (:verdict-unraised p))))
  (let [p (analysis/payload (assoc a-run :design-verdict "sound"))]
    (is (not (str/includes? (:headline p) "unraised")))
    (is (not (contains? p :verdict-unraised)))))

(deftest the-payload-carries-what-the-design-judge-decided
  ;; The pass judges the whole run, so it answers after the status is fixed and
  ;; nothing the loop published knows what it said. One run reached the analysis
  ;; as `converged · 0 still open` over a verdict of `strained` naming two
  ;; implementation defects nobody had been dispatched at.
  (let [p (analysis/payload (assoc a-run :design-verdict "strained"
                                   :verdict-implementation 2))]
    (is (= "strained" (:design-verdict p)))
    (is (= 2 (:verdict-implementation p))))
  (let [p (analysis/payload (assoc a-run :design-verdict "sound"))]
    (is (= 0 (:verdict-implementation p))
        "the count travels with the verdict or not at all: a `sound` verdict over
         no implementation findings is the sentence that says the run is done, and
         it cannot be said by leaving the number out"))
  (let [p (analysis/payload a-run)]
    (is (not (contains? p :design-verdict)))
    (is (not (contains? p :verdict-implementation))
        "a run whose ledger holds no design record got no verdict, and must not
         reach the analysis carrying a zero that reads like one")))

(deftest the-headline-says-whether-a-judge-read-this-runs-code
  ;; `1 kept` over a carried verdict had to be worked out from file lists and
  ;; the cache's hash history: the headline never said the verdict was carried.
  (let [h (:headline (analysis/payload (assoc a-run :design-verdict "strained"
                                              :design-carried-from 18)))]
    (is (str/includes? h "Design: strained, carried from entry 18")))
  (let [h (:headline (analysis/payload (assoc a-run :design-verdict "sound")))]
    (is (str/includes? h "Design: sound"))
    (is (not (str/includes? h "carried from entry")))))

(deftest the-title-says-when-the-judge-disagreed-with-the-status
  (is (str/includes? (:title (analysis/payload (assoc a-run :design-verdict "strained")))
                     "design strained"))
  (is (not (str/includes? (:title (analysis/payload (assoc a-run :design-verdict "sound")))
                          "design"))
      "a `sound` verdict agrees with the status, and the title is read by someone
       scanning for the runs where the two came apart"))

(deftest the-title-says-when-the-workstream-holds-no-record-of-the-run
  ;; An unrecorded run is invisible from the workstream while its successor
  ;; inherits the open list of the run BEFORE it — so every count the analysis
  ;; is given describes a judgement nothing downstream will ever read. Two runs
  ;; were graded on that inheritance before anything published the fact.
  (let [p (analysis/payload
           (assoc a-run :review-entry
                  {:ledger "refused" :ws-id "ws-1"
                   :because "Invalid event review — [:status] :malli.core/invalid-type"}))]
    (is (str/includes? (:title p) "not recorded"))
    (is (str/includes? (:headline p) "invalid-type")
        "the headline is the whole of the analysis session's briefing")
    (is (str/includes? (:headline p) "ws-1"))
    (is (= "refused" (get-in p [:review-entry :ledger]))))
  ;; The same answer as a keyword, which is what it is in the process that
  ;; folded it — `report/verdict-summary`'s reason, applied to this field.
  (is (str/includes? (:title (analysis/payload
                              (assoc a-run :review-entry {:ledger :no-design})))
                     "not recorded")))

(deftest a-run-that-reached-a-ledger-or-had-none-is-not-titled-unrecorded
  (is (not (str/includes? (:title (analysis/payload
                                   (assoc a-run :review-entry
                                          {:ledger "appended" :ws-id "ws-1"})))
                          "not recorded")))
  (is (not (str/includes? (:title (analysis/payload
                                   (assoc a-run :review-entry {:ledger "no-workstream"})))
                          "not recorded"))
      "a review outside a session had no ledger to reach, which is not a run the
       ledger lost — the Reviewed: line already says there is no workstream")
  (is (not (str/includes? (:title (analysis/payload a-run)) "not recorded"))
      "an orphan never got as far as offering an entry, so it carries no answer"))

(deftest the-payload-says-which-phase-an-orphan-died-in
  ;; The one fact separating a harmless orphan from a dangerous one. A run killed
  ;; while its fixers were rewriting the branch left a tree nobody vouched for; a
  ;; run killed while a reviewer was reading left it exactly as it found it.
  (let [p (analysis/payload (assoc a-run :status :orphaned :rounds 1
                                   :in-flight {:round 1 :phase "fix"}))]
    (is (= "fix" (:died-in p)))
    (is (str/includes? (:title p) "died in fix")
        "the title is where the two are told apart, and until it said so both
         were filed under the identical one"))
  (let [p (analysis/payload (assoc a-run :status :orphaned :rounds 1
                                   :in-flight {:round 1 :phase "review"}))]
    (is (str/includes? (:title p) "died in review")))
  (let [p (analysis/payload a-run)]
    (is (not (contains? p :died-in))
        "a run that closed its own rounds on a judgement stopped in no phase —
         `:in-flight` is the reconciler's reading of a report that never ended")))

(deftest a-run-the-loop-closed-on-a-crash-is-titled-as-one
  ;; review-1e4b6342 was not an orphan: its fix stage threw and the loop closed
  ;; the run cleanly on it. The title read like a run that had judged the branch,
  ;; over a fix phase that had crashed under three fixers.
  (let [p (analysis/payload (assoc a-run :status :stack-unmovable :rounds 1
                                   :errored {:round 1 :phase "fix"
                                             :message "could not read what the fixer wrote"}))]
    (is (= "fix" (:died-in p)))
    (is (str/includes? (:title p) "died in fix")
        "the same words an orphan killed mid-rewrite gets, because the branch was
         left the same way")))

(deftest a-run-with-no-session-still-builds-a-payload
  ;; The loop runs anywhere `jj` does, including a checkout nido never
  ;; provisioned. It has a run to analyse either way.
  (let [p (analysis/payload {:run-id "r" :status :clean})]
    (is (= "r" (:id p)))
    (is (nil? (:reviewed-session p)))
    (is (str/includes? (:title p) "0 rounds"))))

(defn- ended
  "A run that reached `status`, having read a target. The gate takes the run map
   the enqueue site holds, and everything but the status is beside the point for
   a run that finished."
  [status]
  {:status status :dry-run? false :targets-reviewed 1})

(deftest a-dry-run-is-not-analysed
  ;; It drove the stages without letting a fixer touch anything, so it says how
  ;; the loop behaves under a flag rather than how it behaves.
  (is (not (analysis/worth-analysing? (assoc (ended :converged) :dry-run? true) true))))

(deftest a-run-that-left-no-report-is-not-analysed
  ;; Queueing is cheap; what it queues is a worktree and an hour of budget for a
  ;; session whose first act is to open the report.
  (is (not (analysis/worth-analysing? (ended :converged) false))))

(deftest a-failed-review-is-analysed
  ;; It is the outcome nobody reads a report for, which is what makes it the one
  ;; most worth reading — and it still writes one.
  (is (analysis/worth-analysing? (ended :review-failed) true))
  (is (analysis/worth-analysing? (ended :no-progress) true))
  (is (analysis/worth-analysing? (ended :escalated) true)))

(deftest an-unavailable-reviewer-is-analysed-only-when-something-answered
  ;; Refused at the door on every target, the run is a vendor quota, not loop
  ;; behaviour — six of them in a week each bought a worktree and an hour of Opus.
  (is (not (analysis/worth-analysing? (assoc (ended :reviewer-unavailable) :targets-reviewed 0) true)))
  (is (not (analysis/worth-analysing? {:status "reviewer-unavailable" :dry-run? false} true))
      "a run that resolved no target carries no count at all")
  ;; But one that read targets before its reviewer ran out holds rulings and a
  ;; P1 at confidence 1.0, and excluding the status would have dropped it.
  (is (analysis/worth-analysing? (ended :reviewer-unavailable) true)))

(deftest a-clean-run-that-only-carried-its-verdict-is-not-analysed
  ;; review-a652021d skipped all four targets on unchanged patch hashes and
  ;; carried the verdict from entry 93: its whole report was the previous run's,
  ;; already analysed, and it still bought a session to say so.
  (let [carried (assoc (ended :clean) :targets-reviewed 0 :design-carried-from 93)]
    (is (not (analysis/worth-analysing? carried true)))
    (is (not (analysis/worth-analysing? (assoc carried :status "clean") true))
        "the status arrives as the string the report was persisted with")
    (is (analysis/worth-analysing? (assoc carried :targets-reviewed 1) true)
        "a target read is reviewer behaviour of its own")
    (is (analysis/worth-analysing? (dissoc carried :design-carried-from) true)
        "a verdict judged afresh is a reading of this run's code")
    (is (analysis/worth-analysing? (assoc carried :status :unresolved) true)
        "a status other than clean says the run ended somewhere the carry did not decide")))

(deftest a-run-with-no-terminal-status-is-not-analysed
  (is (not (analysis/worth-analysing? (ended nil) true))))

(deftest a-run-that-reviewed-nothing-is-not-analysed
  ;; Every target came back with a blank manifest, so no reviewer read a line.
  ;; There is no loop behaviour in the run to say anything about, and left in it
  ;; would provision a worktree and an hour of budget on every empty-diff review.
  (is (not (analysis/worth-analysing? (ended :nothing-to-review) true)))
  ;; The status survives a round-trip through the report as a string, which is
  ;; the shape the enqueue site actually reads it in.
  (is (not (analysis/worth-analysing? (ended "nothing-to-review") true))))

(deftest a-run-that-stopped-on-a-conflicted-stack-is-not-analysed
  ;; The run exists to stop in a second rather than spend six agents on a branch
  ;; it cannot read; queueing a session with an hour of budget to say so would
  ;; give the saving straight back. What it found is a fact about the branch and
  ;; reaches a human through the ledger entry and the lane's escalation.
  (is (not (analysis/worth-analysing? (ended :stack-conflicted) true)))
  (is (not (analysis/worth-analysing? (ended "stack-conflicted") true)))
  ;; The status the FIX stage produces is a different case: the loop ran, judged
  ;; and repaired, and how it got there is exactly what an analysis reads.
  (is (analysis/worth-analysing? (ended :fix-conflicted) true)))

(deftest an-orphan-that-read-nothing-is-not-analysed
  ;; The measured waste this gate exists for: two runs on one tree lived 2.6s
  ;; and 6.7s, neither reaching a reviewer's first token, and each bought a
  ;; worktree and an hour of Opus. `:orphaned` is stamped from outside, so it
  ;; arrives as the string the report was persisted with.
  (is (not (analysis/worth-analysing?
            {:status "orphaned" :targets-reviewed 0
             :in-flight {:round 1 :phase "review"}}
            true)))
  (is (not (analysis/worth-analysing?
            {:status "orphaned" :targets-reviewed 0}
            true))
      "a run that died before its first phase reaches this with no in-flight
       phase at all, and is the same nothing to read"))

(deftest an-orphan-that-read-a-target-is-analysed
  ;; It got far enough to produce loop behaviour, and how a run that was working
  ;; came to stop is what an analysis is for.
  (is (analysis/worth-analysing?
       {:status "orphaned" :targets-reviewed 1
        :in-flight {:round 1 :phase "warden"}}
       true)))

(deftest an-orphan-that-died-repairing-the-branch-is-analysed-at-any-count
  ;; Its fixers outlived it and kept rewriting a branch nobody was supervising.
  ;; Coverage is beside the point on that one: it is the run in the whole record
  ;; most worth reading, and gating it on a count would drop exactly it.
  (is (analysis/worth-analysing?
       {:status "orphaned" :targets-reviewed 0
        :in-flight {:round 1 :phase "fix"}}
       true)))

(defn- with-report
  "a-run pointed at a report file that actually exists — the enqueue gate now
   requires one."
  [run f]
  (let [tmp (fs/create-temp-dir)
        rp  (str (fs/path tmp "report.json"))]
    (try (spit rp "{}") (f (assoc run :report-path rp))
         (finally (fs/delete-tree tmp)))))

(deftest enqueue-writes-nothing-for-a-run-whose-report-is-gone
  (let [called (atom false)]
    (with-redefs [queue/enqueue! (fn [_] (reset! called true) "/q/1")]
      (is (nil? (analysis/enqueue! (assoc a-run :report-path "/definitely/not/here.json"))))
      (is (false? @called)))))

(deftest enqueue-returns-nil-rather-than-throwing-when-the-queue-is-unwritable
  ;; A review that finished is finished. A side record that could not be
  ;; written must never turn it into a failure.
  (with-redefs [queue/enqueue!
                (fn [_] (throw (ex-info "disk full" {})))]
    (with-report a-run #(is (nil? (analysis/enqueue! (assoc % :dry-run? false)))))))

(deftest enqueue-writes-one-envelope-aimed-at-nidos-own-trigger
  (let [seen (atom nil)]
    (with-redefs [queue/enqueue! (fn [e] (reset! seen e) "/q/1.edn")]
      (with-report a-run #(is (= "/q/1.edn" (analysis/enqueue! %))))
      (is (= {:project :nido :trigger :review-analysis} (:target @seen))
          "routing never depends on which project was reviewed")
      (is (= "review-abc" (get-in @seen [:payload :id]))))))

(deftest a-dry-run-writes-no-envelope-at-all
  (let [called (atom false)]
    (with-redefs [queue/enqueue! (fn [_] (reset! called true) "/q/1")]
      (with-report a-run #(is (nil? (analysis/enqueue! (assoc % :dry-run? true)))))
      (is (false? @called)))))

;; ── record runs ─────────────────────────────────────────────────────────────
;; A baseline or design loop is handed over through the same gate. What decides whether it is worth
;; reading is whether any round launched a judge — never the status it ended on.

(def a-design-run
  {:loop :design :run-id "design-loop-1" :report-path "/r/report.json" :status :cleared
   :rounds 2 :judged 2 :amended 1 :weakened 3 :disputed 0 :record-seq 14
   :still-broken [:stratified]
   :reviewed-project :nido :reviewed-session "record-round-analysis" :reviewed-ws-id "ws-9"})

(deftest a-record-run-is-analysed-once-a-round-judged
  (is (analysis/worth-analysing? a-design-run true))
  (is (analysis/worth-analysing? (assoc a-design-run :status :premise-unverified) true)
      "a status reached after rounds that judged is not a run that judged nothing")
  (is (not (analysis/worth-analysing? (assoc a-design-run :judged 0) true))
      "no round launched a judge, so there is no loop behaviour to read")
  (is (not (analysis/worth-analysing? (assoc a-design-run :judged 0 :status :cleared) true))
      "whatever status it ended in"))

(deftest a-record-run-that-lost-its-reviewer-is-judged-by-what-it-judged
  ;; design-loop-ea82cce0 met a vendor refusal six seconds in and judged nothing;
  ;; `judges-launched` does not count the refused round, so it reaches here at 0.
  (is (not (analysis/worth-analysing? (assoc a-design-run :status :reviewer-unavailable :judged 0) true)))
  (is (analysis/worth-analysing? (assoc a-design-run :status :reviewer-unavailable :judged 1) true)
      "a record run carries no target count, so the diff loop's reading of one
       would drop a run that judged in round one and lost its reviewer in round two"))

(deftest a-record-runs-envelope-says-what-the-run-did
  (let [p (analysis/payload a-design-run)]
    (is (= :review-run (:adapter p)))
    (is (= "design-loop-1" (:id p)))
    (is (= "design" (:loop p)))
    (is (= "design-loop cleared · record-round-analysis · 2 rounds · stratified still broken" (:title p)))
    (is (str/includes? (:headline p) "2 rounds, 2 judged · 1 amended · 3 weakenings · 0 disputed"))
    (is (str/includes? (:headline p) "Record: the design at entry 14 · broken at the end: stratified"))
    (is (str/includes? (:headline p) "bb nido:review:figures :project nido :run-id design-loop-1")
        "without :project the command answers that no ledger names the run, for any project but nido")
    (is (not (contains? p :fix-attempts)) "a record run dispatches no repairs to count")))

(deftest a-record-run-whose-decision-reached-no-ledger-says-so
  ;; The figures command in the headline counts from the ledger, so an unrecorded decision makes
  ;; it disagree with the run; the headline is the analysis session's whole briefing.
  (let [h (:headline (analysis/payload (assoc a-design-run :status :unrecorded
                                              :unrecorded {:would-have-ended :asked :ledger "nido/ws-1"})))]
    (is (str/includes? h "Status: unrecorded"))
    (is (str/includes? h "Unrecorded: the run would have ended asked, but its decision reached no ledger (nido/ws-1)"))))

(deftest a-record-run-judged-by-a-stand-in-says-so
  (let [p (analysis/payload (assoc a-design-run
                                   :stood-in [{:reviewer :claude :instead-of :codex :readings 2}]))]
    (is (str/includes? (:headline p) "Stood in: claude for codex on 2 readings")
        "the amender's own model judging its amendments must not read like an independent judge")
    (is (= [{:reviewer :claude :instead-of :codex :readings 2}] (:stood-in p))))
  (is (not (str/includes? (:headline (analysis/payload a-design-run)) "Stood in"))))

(deftest a-record-run-that-ended-on-a-refused-amendment-says-where-it-is
  ;; `0 amended` read the same for an amender that did nothing and one whose
  ;; complete answer the ledger refused — and only the second left work to recover.
  (let [h (:headline (analysis/payload (assoc a-design-run :status :amend-invalid :amended 0
                                              :unappended "/r/design-amend-round-2.edn")))]
    (is (str/includes? h "0 amended · 1 refused by the ledger · "))
    (is (str/includes? h "Refused amendment, not appended: /r/design-amend-round-2.edn")))
  (is (not (str/includes? (:headline (analysis/payload a-design-run)) "refused"))))

(deftest a-record-run-names-a-claim-spent-across-runs
  ;; A capped run headlined "1 rounds, 0 amended" over a claim refuted for the fifth time running;
  ;; the hand amender between runs saw a new defect and wrote the sixth rewording.
  (let [h (:headline (analysis/payload (assoc a-design-run :status :max-iters
                                              :spent {"course-store" 5 "writers-order" 11})))]
    (is (str/includes? h "Spent: course-store refuted 5 readings running, writers-order refuted 11 readings running"))
    (is (str/includes? h "weaken, withdraw or decide, not reword again")))
  (is (not (str/includes? (:headline (analysis/payload a-design-run)) "Spent:"))))

(deftest a-cleared-design-run-carries-its-ask-into-the-headline
  ;; A cleared run stops nobody, and its headline read as five counters — an all-clear — while its
  ;; decision held a live product question.
  (is (str/includes? (:headline (analysis/payload (assoc a-design-run :asks "is that trade worth taking now?")))
                     "Asked of a person: is that trade worth taking now?\n"))
  (is (not (str/includes? (:headline (analysis/payload a-design-run)) "Asked of a person"))
      "a baseline run, or one that ended before any decision, asks nothing"))

(deftest a-diff-runs-envelope-keeps-its-fields-and-gains-a-headline
  (let [p (analysis/payload a-run)]
    (is (= (str "Status: converged · 3 rounds · 3 defects settled (5 repairs dispatched) · 1 still open · 0 kept\n"
                "Coverage: 3 targets read this run, 5 carried from an earlier run\n"
                "Reviewed: brian / fix/thing (base main)")
           (:headline p))
        "the line the template used to assemble, rendered here instead")
    (doseq [k [:fix-attempts :defects-settled :findings-remaining :findings-kept
               :targets-reviewed :targets-skipped :base :reviewed-project :reviewed-session]]
      (is (contains? p k) (str "a template that still names " k " renders a diff run as before")))))

(deftest the-headline-says-how-many-settled-defects-the-loop-made
  ;; Two of six settled defects in one run were leaks the loop's own fixers had
  ;; opened, and the headline counted them with the ones the branch arrived with.
  (let [p (analysis/payload (assoc a-run :defects-introduced 2))]
    (is (str/includes? (:headline p)
                       "3 defects settled (2 made by the loop's own repairs; 5 repairs dispatched)"))
    (is (= 2 (:defects-introduced p))))
  (is (str/includes? (:headline (analysis/payload a-run)) "3 defects settled (5 repairs dispatched)")
      "nothing attributed is said as nothing, not as a zero"))

(deftest a-settled-run-publishes-a-count-it-could-not-make-as-unknown
  ;; A run killed holding a P1 was headlined `0 still open`. Zero and unknown
  ;; are opposite instructions to whoever reads the board.
  (let [p (analysis/payload {:run-id "review-x" :report-path "/r" :status "orphaned"
                             :rounds 1 :fix-attempts nil :defects-settled 0
                             :findings-remaining 3 :findings-kept 0
                             :in-flight {:round 1 :phase "fix"}})]
    (is (str/includes? (:headline p) "(unknown repairs dispatched)"))
    (is (str/includes? (:headline p) "3 still open")
        "what the settled entry derived is published as the fact it is")
    (is (= "unknown" (:fix-attempts p))))
  (is (= 0 (:fix-attempts (analysis/payload (assoc a-run :fix-attempts nil))))
      "a finished run's absent count is nothing to report, as it always was"))

(deftest a-settled-run-a-later-record-replaced-is-not-called-a-memory-gap
  (let [h (:headline (analysis/payload {:run-id "review-x" :report-path "/r" :status "orphaned"
                                        :rounds 1 :reviewed-ws-id "ws-1"
                                        :review-entry {:ledger "superseded"}}))]
    (is (str/includes? h "had already written its own record, which stands"))
    (is (not (str/includes? h "inherits the one before it")))))

(deftest the-headline-says-when-the-machinery-was-stale
  ;; Nine analyses in one day re-filed defects main had already fixed, because
  ;; the run's own report said which revision ran and not that it was behind.
  (let [stale {:root "/n/src" :rev "abc" :lacks ["5969d23d Hand a refused amendment back"]}]
    (doseq [run [(assoc a-run :machinery stale)
                 (assoc a-run :loop :design :machinery stale)]]
      (is (str/includes? (:headline (analysis/payload run)) "5969d23d")
          "the headline is the analysis session's whole briefing, so the lag has to be in it"))
    (is (not (str/includes? (:headline (analysis/payload (assoc a-run :machinery {:root "/n/src" :rev "abc" :lacks []})))
                            "warning"))
        "a copy current with main on the loop is not worth a line")))

(deftest a-capped-record-run-says-in-its-headline-what-the-cap-fell-between
  ;; 'max-iters · 3 rounds, 3 judged · 1 amended' was the whole headline of a run whose last round
  ;; had overturned a sufficient verdict and left two findings unamended; the analysis read it as
  ;; an ordinary cap.
  (let [h (:headline (analysis/payload (assoc a-design-run :status :max-iters
                                              :cap "round 3 was the second reading of round 2's sufficient verdict and overturned it, 2 findings open"
                                              :amend-prompt "/runs/r/amend-prompt-round-3.md")))]
    (is (str/includes? h "Cap: round 3 was the second reading"))
    (is (str/includes? h "Amend prompt the run did not reach: /runs/r/amend-prompt-round-3.md")))
  (is (not (str/includes? (:headline (analysis/payload a-design-run)) "Cap:"))))
