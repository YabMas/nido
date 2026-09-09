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
   [nido.coordinator.daemon.pid :as daemon-pid]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.review.analysis :as analysis]
   [nido.review.frontend :as frontend]
   [nido.review.layers :as layers]
   [nido.review.record :as record]
   [nido.review.loop :as rloop]
   [nido.review.provenance :as provenance]
   [nido.review.reconcile :as reconcile]
   [nido.review.render :as render]
   [nido.review.retreat :as retreat]
   [nido.review.stages :as stages]
   [nido.review.report :as report]
   [nido.review.verdict :as verdict]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.task-args :as task-args])
  (:import
   [java.time Instant]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  exit-code
  "CLI exit code for a terminal review status. A run that produced no review at
   all is the only failure — whether the review broke or no reviewer could be
   run; escalated is a reported outcome, not an error."
  [status]
  (if (#{:review-failed :reviewer-unavailable} status) 1 0))

(defn- ledger-findings
  "Findings trimmed to what a reader of the workstream needs and nothing that
   only makes sense inside a run. `where` is assembled here because file and
   line are two fields in the report and one fact to a reader.

   `handed` is a `verdict/handed-to-a-fixer` set, and `:handed` is the one field
   here that is not the finding's own: whether a repair for it is sitting
   unverified in the branch. Two remainders read alike in a list of titles and
   ask opposite things of whoever picks them up — one needs checking, the other
   needs doing. Empty for a list where nothing is owed and the question cannot
   arise.

   `:layer` is what makes this list joinable back onto a later run's targets —
   `stages/prior-open` is the reader, and a label is the only identity that
   survives the repair a `:fix` row is asking for. The warden's owner where it
   assigned one, since that is where the repair goes whoever reported it; the
   reviewer that raised it otherwise, which is the best available answer for a
   finding no warden ruled on and still the layer whose reviewer read the code."
  [handed findings]
  (into []
        (map (fn [{:keys [id title file line-start disposition because
                          owner-layer from-layer] :as f}]
               (cond-> {:title (str (or title "(untitled finding)"))}
                 id          (assoc :id (str id))
                 file        (assoc :where (str file (when line-start (str ":" line-start))))
                 disposition (assoc :disposition (keyword disposition))
                 because     (assoc :because (str because))
                 (or owner-layer from-layer) (assoc :layer (str (or owner-layer from-layer)))
                 (verdict/handed? handed f) (assoc :handed true))))
        findings))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  refused-repairs
  "The repairs the stack refused that the run is still holding, one row per
   layer, oldest first.

   Off `:carry` rather than off the terminal ctx. A ctx is rebuilt every round,
   so `:rolled-back` on it is the last round's alone — and the run this was
   written for refused a repair in round 1 and converged in round 2, which is
   precisely the shape where the terminal ctx holds nothing. The carry drops a
   layer whose findings a later round settled, so what is left is the refusals
   still standing over findings still open.

   Trimmed to what the ledger's closed schema admits. The fixer's own account is
   not among it and is on the report's fix phase instead: `:commit` is the
   repair as it stood before `jj op restore` put it back, and `jj show` on it is
   the edit rather than a claim about the edit."
  [final]
  (->> (vals (get-in final [:carry :rolled-back] {}))
       (sort-by (juxt #(or (:since %) 0) #(str (:layer %))))
       (mapv (fn [{:keys [layer since commit conflicted findings]}]
               (cond-> {:conflicted (vec conflicted)
                        :handed (into [] (comp (map :id) (remove nil?) (map str))
                                      findings)}
                 layer       (assoc :layer (str layer))
                 (int? since) (assoc :round since)
                 commit      (assoc :commit (str commit)))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  review-event
  "Pure: build a :review ledger payload from the loop's terminal value `final`
   ({:status :findings :history}) and the folded review `report`
   ({:summary :target}).

   The remaining findings are carried whole, not just counted. The count was the
   entry's only account of what a run left behind, and a count cannot be acted
   on: a run that ends with one parked finding — the case where the loop is
   explicitly asking a human for a decision — recorded that request as the
   integer 1, with the request itself reachable only inside report.json.

   `:fix-attempts` and `:defects-settled` are two different questions: how much
   repair work the run dispatched, and how many defects came off the branch
   because of it. A handle handed out in three rounds is three attempts and at
   most one defect, so the first can be nearly double the second — and only the
   second has a later reviewer's silence behind it.

   `:remaining-handed` says how many of the remaining a fixer already landed a
   repair for that no round checked. It overlaps `:fix-attempts`, and stating the
   overlap is the point: without it a run reads as `1 dispatched · 11 remaining`
   out of eleven findings, and the arithmetic is only wrong until you know one
   finding is in both numbers.

   `:targets-reviewed` and `:targets-skipped` say how much of the stack the
   verdict above is a verdict ON. A skipped target was converged in an earlier
   run and not re-opened, so a `clean` over three of eight targets and one over
   all eight are the same status and very different evidence.

   `:remaining-parked` splits the rest of it. A remainder is one of three
   things and they ask opposite things of whoever picks it up: a park is a
   question only a human can answer, a handed finding needs checking, and what
   is left is work the run was cut off before it reached. A run that STOPS on a
   park reported that question in the same integer as the fixes it never got
   to.

   `:kept` is the other half, and it is not part of the remainder at all: a
   declined defect and a deviated claim were DECIDED, so nobody owes anything
   and `:findings-remaining` does not count them. They still have to be written
   down — a decision to ship a known defect is the kind a record exists to hold
   — so they go in their own list, under their own count.

   `:drift` and `:reshaped` are what the run did and had done to it, and the
   entry is where they have to be durable: report.json lives in a run dir that
   is routinely reclaimed, and both facts were reachable only inside it. A
   `workspace-drifted` entry named neither revision, and a run that folded two
   layers reported no work at all about a branch it had rewritten.

   `:standing` is what the terminal warden knew was open and was handing to
   nobody — no finding covers it, so it appears in no other list here, and until
   the warden had a slot for it the only copy was a sentence in a run dir that
   is routinely reclaimed. It is not part of `:findings-remaining`: nothing was
   raised, ruled or dispatched, and counting it would claim the loop had an
   answer it declined to give.

   `:open` also carries what the LAST run left owed that this one never answered,
   each row marked `:inherited`. Without it a run whose reviewers were handed a
   prior obligation and said nothing about it writes an entry holding nothing —
   and that entry is the whole of what the run after gets, so the obligation
   disappears at the first quiet run rather than at the run that settled it.
   Deduped on the finding id, which is derived from file, line and title: a
   defect this run raised again is this run's, and its own accounting decides
   what is owed on it."
  [final report report-path]
  (let [handed   (verdict/handed-to-a-fixer final)
        refused  (refused-repairs final)
        raised   (ledger-findings handed (verdict/open-across-run final))
        seen     (into #{} (keep :id) raised)
        ;; `:handed` does not survive the carry. It claims a repair is sitting in
        ;; the branch that no reviewer has read, and the run writing this entry
        ;; put the row in front of the reviewer of its own layer — so whatever
        ;; is true of it now, unread is not.
        open     (into raised
                       (comp (remove #(contains? seen (:id %)))
                             (map #(-> % (dissoc :handed) (assoc :inherited true))))
                       (stages/unanswered-inherited final))
        kept     (ledger-findings #{} (verdict/kept-across-run final))
        repaired (count (filter :handed open))
        parked   (count (filter #(= :park (:disposition %)) open))
        cover    (report/coverage report)
        ;; Narrowed to what the ledger's closed schema admits. The phase entry
        ;; also carries the warden's handle and the finding's kind, which are
        ;; the report's business — this list exists to say the stack moved.
        reshaped (mapv #(select-keys % [:round :outcome :title :lower :upper :file])
                       (report/applied-reshapes report))
        ;; Through the report's `:reason` rather than off the terminal ctx, so
        ;; the entry, the artifact and the analysis payload are three readings
        ;; of one value — `report/stopped-on` — and cannot disagree about what
        ;; the run left behind.
        standing (get-in report [:reason :standing])]
    (cond-> {:format             :review-report
             :status             (:status final)
             :base               (get-in report [:target :base])
             :base-rev           (get-in report [:target :base-rev])
             :rounds             (or (get-in report [:summary :rounds]) 0)
             :fix-attempts       (or (get-in report [:summary :fix-attempts]) 0)
             :defects-settled    (count (verdict/settled-by-fixing final))
             :findings-remaining (count open)
             :report-path        report-path}
      (seq open)      (assoc :open open)
      (seq kept)      (assoc :kept kept :findings-kept (count kept))
      (pos? repaired) (assoc :remaining-handed repaired)
      (pos? parked)   (assoc :remaining-parked parked)
      ;; Both or neither, and `0 skipped` is worth saying: it is the entry
      ;; asserting the whole stack was read this run, which is exactly what a
      ;; reader cannot otherwise tell from a clean verdict. Only a run that
      ;; resolved no targets at all — it died before the first fan-out — has
      ;; nothing to claim here.
      (pos? (+ (:reviewed cover) (:skipped cover)))
      (assoc :targets-reviewed (:reviewed cover) :targets-skipped (:skipped cover))
      ;; Why no reviewer ran, when that is how the run ended. The status names
      ;; the condition and this is the only durable copy of what to do about it:
      ;; the report lives in a run dir that is routinely gone by the time anyone
      ;; reads the workstream, and the reviewer's own log is gone with it.
      (:unavailable final) (assoc :unavailable (:unavailable final))
      ;; Only a run that ended on a conflicted stack has these, and it is the
      ;; run whose status a reader cannot act on without them: the conflict is
      ;; mid-stack, so `jj resolve --list` reports the branch clean and the ids
      ;; are the only pointer at what to open.
      (seq (:conflicted final)) (assoc :conflicted (vec (:conflicted final)))
      ;; A repair the stack refused is the reason one of the findings above is
      ;; still open, and it is a reason nothing else in the entry states: the
      ;; branch is unchanged, so the counts read exactly like a round no fixer
      ;; was launched for. It has to be durable here for the reason `:drift` and
      ;; `:reshaped` are — the run dir holding report.json is routinely gone by
      ;; the time anyone reads the workstream.
      (seq refused)   (assoc :rolled-back refused)
      (:drift final)  (assoc :drift (:drift final))
      (seq standing)  (assoc :standing (vec standing))
      (seq reshaped)  (assoc :reshaped reshaped))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  append-review-entry!
  "Resolve cwd → session → workstream (the tasks.nido-ship path) and append one :review
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

(defn- verdict-supports
  "What this run's design verdict says about the design record, or nil when there
   is no verdict to read.

   A lookup rather than a judgment: it reads the verdict's own vocabulary and
   nothing else. One that invalidates the record says it should be superseded;
   one that leaves it standing says the findings against it are answered; one
   that leaves it standing AND names a repair says neither, and that third
   answer is the one the gate could not express.

   Whether any of it bears on the question the gate is asking is `gate-question`
   and `gate-options`' business — on a park made on recurrence the verdict is an
   answer to a question nobody asked."
  [v]
  (when v
    (cond (verdict/decision? v) :supersede
          (:needs v)            :repair
          :else                 :stands)))

(defn- recommend
  "Flag a branch as the one the run's own reading supports. Added rather than set
   false on the rest, because `:recommended? false` on a branch reads as a
   judgment against it, and a run with no verdict has made none."
  [branch supported?]
  (cond-> branch supported? (assoc :recommended? true)))

(defn- park-ground
  "Which decision a set of parked findings is asking a human for.

   `:recurrence` when every one of them carries `same-as` — the warden's mark
   that this defect was already fixed in an earlier round and is back, which
   `prompts/disposition-vocabulary` states needs no design record at all: it is
   a fact about the run's own history and the warden is holding it. `:design`
   otherwise, because one finding raised against a named invariant is a design
   question however many recurrences stand beside it, and it is the question no
   other branch can express.

   A run with no design record parks on recurrence and nothing else, so reading
   the ground off the ruling is what keeps the gate from asking whether a record
   that does not exist still stands."
  [parked]
  (if (every? :same-as parked) :recurrence :design))

(defn- parked-detail
  "The parked findings as the gate shows them — each title with the warden's own
   sentence for stopping there.

   `because` is the only text in the run that says what is actually being
   decided; a title says which defect. It reaches report.json and the `:review`
   entry, and both of those are read by someone who already went looking — this
   artifact is the one that arrives."
  [parked]
  (str/join "\n"
            (for [{:keys [title because]} parked]
              (str "- **" (or title "(untitled finding)") "**"
                   (when-not (str/blank? because) (str " — " because))))))

(defn- gate-question
  "The `:needs` — what the human is being asked, in one paragraph.

   The ground fixes the question and the verdict says how much of it is already
   answered. On the design ground the verdict IS that question's answer, so the
   gate states it rather than asking again. On the recurrence ground it answers
   a different one, and only two of its three readings bear on this: a record
   that does not stand says the remedy is a decision, and a repair the verdict
   names is a move neither attempt made. A record that stands says nothing about
   whether a third patch would hold, so it is not mentioned."
  [ground supports verdict]
  (case ground
    :design
    (if-not supports
      (str "Each of these says the design is in question rather than its "
           "execution, so the loop stopped rather than patching it away. "
           "Does the design stand?")
      (str "Each of these says the design is in question rather than its "
           "execution. This run's design verdict answers that — "
           (name (:verdict verdict)) ". "
           (case supports
             :repair    (str "The design stands and the verdict names the "
                             "repair. Is that repair owed here?")
             :stands    (str "The design stands and the verdict names no "
                             "repair. Are these findings declined?")
             :supersede (str "The design does not stand. Is the record "
                             "superseded?"))))

    :recurrence
    (str "Each of these was fixed in an earlier round and came back, so the loop "
         "stopped rather than making a third attempt at it. Is the remedy a "
         "decision rather than another patch?"
         (case supports
           :repair    (str " This run's design verdict — " (name (:verdict verdict))
                           " — names a repair neither attempt made.")
           :supersede (str " This run's design verdict — " (name (:verdict verdict))
                           " — puts the design record itself in question.")
           ""))))

(defn- gate-options
  "The branches, in the order the gate letters them.

   Two from the ground, plus a third when the verdict names a repair — the
   answer neither of the first two can express, since on either ground they are
   `leave it as it is` and `stop patching it`. Its summary is the repair
   verbatim: `option-input` replays a chosen branch in full, so that is how the
   remedy survives the click."
  [ground supports verdict]
  (cond-> (case ground
            :design
            [(recommend
              {:label "The design stands"
               :summary "The findings are answered by the design as written."
               :consequence (str "They are declined on the record and stop being "
                                 "re-raised. If that is wrong, the next round has "
                                 "no way to tell.")}
              (= :stands supports))
             (recommend
              {:label "The design is wrong"
               :summary "Supersede the design record, then re-run the review."
               :consequence (str "Everything judged against the old record is "
                                 "judged again, including work already fixed.")}
              (= :supersede supports))]

            :recurrence
            [{:label "It is still a repair"
              :summary (str "Hand it back with the reason above and let a fixer make "
                            "the third attempt.")
              :consequence (str "Two repairs have already come back. A third differs "
                                "from them only by what the reason above says they "
                                "both missed.")}
             (recommend
              {:label "It is a decision, not a patch"
               :summary (str "Settle what the code should do here, then re-run the "
                             "review against that.")
               :consequence (str "The defect stays on the branch until the decision "
                                 "lands, and the rounds already spent narrowing it "
                                 "bought nothing.")}
              (= :supersede supports))])
    (= :repair supports)
    (conj {:label "Take the repair the verdict names"
           :summary (:needs verdict)
           :consequence (case ground
                          :design     (str "The design record is untouched, so nothing "
                                           "already fixed is judged again — but the "
                                           "findings stay open until the repair lands.")
                          :recurrence (str "It is the one move neither attempt made, so "
                                           "it is a third attempt with something behind "
                                           "it — but the findings stay open until it "
                                           "lands."))
           :recommended? true})))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  parked-blocker
  "Pure: the halt a run holding parked findings owes a human, or nil.

   A park is the one disposition whose answer is not the loop's to give: it is a
   decision rather than a repair. Until now that ended as a status the task
   printed and a report nobody was told to open; a run that takes hours and
   finishes while nobody is watching has told no one anything.

   WHICH decision is `park-ground`'s answer, and the gate is a different question
   on each. A finding raised against a named invariant asks whether the design
   stands; one that two repairs did not settle asks whether the remedy is a
   decision at all, on a run that may well have no design record to stand or
   fall. Asking the first question about the second is how a gate offers to
   supersede a record the run itself recorded as missing.

   The warden's `because` goes in the summary under either question, because it
   is the only sentence in the run that says what is being decided rather than
   which defect is being decided about.

   The branches are stated as what taking each COSTS rather than as their names:
   a gate answered on a name alone is how the wrong branch gets taken by a click.
   `:options` rather than prose because the ledger refuses a choice written as an
   essay, and rightly — an essay can only be answered by typing one back.

   `verdict` is this run's design verdict, or nil when the pass found nothing to
   judge against, was skipped, or produced no answer."
  [findings verdict]
  (when-let [parked (seq (filter #(= :park (:disposition %)) findings))]
    (let [supports (verdict-supports verdict)
          ground   (park-ground parked)]
      {:format  :blocker
       :summary (str "The review loop is holding " (count parked)
                     (if (= 1 (count parked)) " finding" " findings")
                     " it has no move for:\n\n"
                     (parked-detail parked))
       :needs   (gate-question ground supports verdict)
       :options (gate-options ground supports verdict)})))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  append-blocker!
  "Append the halt, if there is one. Best-effort for the same reason
   `append-review-entry!` is: a finished review must not become a failure because
   a side record could not be written. Returns the blocker, or nil."
  [cwd final verdict]
  (try
    (when-let [blocker (parked-blocker (:findings final) verdict)]
      (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
        (when-let [ws-id (csession/workstream-id-for (keyword project) session)]
          (ws/append-entry! (keyword project) ws-id {:kind :blocker}
                            (pr-str blocker))
          blocker)))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "review-loop: could not append the :blocker — " (ex-message e))))
      nil)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  queue-analysis!
  "Queue this run for nido-side analysis. Best-effort, for the same reason
   `append-review-entry!` is: the review is over, and a missing side record must
   not turn a finished review into a failed one.

   Everything the analysis is told about the reviewed branch is a NAME —
   project, session, workstream id. The path is deliberately not passed: the
   analysis runs on nido's side and has no business in the worktree the loop
   just reviewed.

   What the run STOPPED ON is merged in whole, from the same reading the report
   gets. The run dir being gone by the time an analysis opens it is the normal
   end state, so an analysis of an unfixable run had a status, three counts, and
   no way to name the findings the loop had given up on.

   The design verdict is merged the same way and on the same argument, and it is
   the one fact here the run's own status cannot carry: the pass judges the whole
   run, so it answers after the status is fixed. Read back off the report rather
   than passed down from `append-design-verdict!`, because the report is where
   `record-verdict!` has already put it and is the copy that survives a ledger
   that would not take it."
  [cwd final report report-path config ws-id]
  (let [{:keys [project session]} (or (lifecycle/session-from-cwd cwd) {})
        open   (verdict/open-across-run final)
        handed (verdict/handed-to-a-fixer final)
        cover  (report/coverage report)]
    (analysis/enqueue!
     (merge
      {:run-id             (:run-id config)
       :report-path        report-path
       :status             (:status final)
       :dry-run?           (:dry-run? config)
       :base               (get-in report [:target :base])
       :rounds             (or (get-in report [:summary :rounds]) 0)
       :fix-attempts       (or (get-in report [:summary :fix-attempts]) 0)
       :defects-settled    (count (verdict/settled-by-fixing final))
       :findings-remaining (count open)
       :findings-kept      (count (verdict/kept-across-run final))
       :remaining-handed   (count (filter #(verdict/handed? handed %) open))
       :remaining-parked   (count (filter #(= :park (:disposition %)) open))
       :targets-reviewed   (:reviewed cover)
       :targets-skipped    (:skipped cover)
       :reviewed-project   project
       :reviewed-session   session
       :reviewed-ws-id     ws-id}
      (report/stopped-on final)
      (report/verdict-summary report)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  verdict-worth-running?
  "Whether the verdict pass has anything to judge.

   It makes two different kinds of check, and only one of them needs findings.
   Classifying what the review found does; confirming the design's invariants
   held, and testing what it assumed, does not — that pass runs with tools and
   reads the code, so it can answer against a clean review as well as a noisy
   one. Gating the whole pass on findings meant a run that found nothing never
   compared the change to the design it committed to, which is the case where
   the comparison is the only evidence there is.

   So: a design carrying invariants is enough on its own. A review that did not
   happen has nothing to judge — whether it broke or no reviewer could be run —
   and a dry run changed nothing to judge.

   Every status named here is a fact about the RUN. Whether the tree is legible
   is a fact about the WORKING COPY, no status answers it, and `unreadable-tree`
   asks the tree instead."
  [status final design]
  (and (not (#{:review-failed :reviewer-unavailable :dry-run} status))
       (boolean (or (seq (:findings final))
                    (seq (:history final))
                    (seq (:invariants design))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  unreadable-tree
  "Conflict markers in `base..@`: the change ids holding them, or nil for none.

   The verdict pass gives an agent tools and points it at the working copy, so
   committed markers arrive as source: it judges a design against a namespace
   that does not parse, and its answer is appended to the ledger and printed as
   a decision for a human. Asking the tree is the only thing that settles it —
   a status list had `:stack-conflicted` in it and not `:fix-conflicted`, which
   leaves markers by a different route, so what the pass read was decided by
   which files happened to conflict.

   A workspace that cannot be asked reads as legible. `layers/conflicted`
   already takes a non-zero exit for `[]`, so the only thing left to throw is jj
   not running at all — and a branch with no jj holds none of jj's markers.
   Refusing there would cost every plain-git project the run's most valuable
   artifact to guard against a state it cannot be in."
  [cwd base]
  (try (seq (layers/conflicted cwd base))
       (catch Throwable _ nil)))

(defn- refusal-reason
  "Why the ledger would not take a verdict, in one line a reader can act on.

   malli's :explain is the full diagnosis and cannot travel with the verdict —
   a single error embeds the whole branch schema, which is larger than the report
   it would sit in. What identifies the bug is the path and the error type
   together: `[:needs] :malli.core/extra-key` says the parser emits a key the
   write contract does not admit, and that is a defect in nido rather than a bad
   verdict."
  [e]
  (let [errs (:errors (:explain (ex-data e)))]
    (cond-> (ex-message e)
      (seq errs)
      (str " — "
           (str/join ", " (map #(str (pr-str (vec (:in %))) " " (:type %)) errs))))))

(defn- append-verdict-to-ledger!
  "Offer the verdict to this cwd's workstream, and say what happened:
   `{:ledger :appended | :refused | :no-workstream}`, with `:because` on a
   refusal.

   Nothing here throws. A ledger the verdict cannot reach is a fact about the
   ledger, and the verdict itself is unharmed by it — the caller records the
   value either way, so a refusal is diagnosis rather than loss."
  [cwd v]
  (let [{:keys [project session]} (lifecycle/session-from-cwd cwd)
        ws-id (when project (csession/workstream-id-for (keyword project) session))]
    (if-not ws-id
      {:ledger :no-workstream}
      (try
        (ws/append-entry! (keyword project) ws-id {:kind :design-verdict} (pr-str v))
        {:ledger :appended}
        (catch Exception e
          {:ledger :refused :because (refusal-reason e)})))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  append-design-verdict!
  "Run the design verdict and say what became of it, as the outcome map
   `report/with-verdict` folds: `:outcome` (`:answered` / `:no-answer` /
   `:skipped`), the verdict itself when there was one, and where the ledger put
   it. nil when there was nothing to judge against at all.

   Best-effort at the ledger, for the same reason append-review-entry! is: a
   completed review must not turn into a failure because a side record could not
   be written. Best-effort is not the same as untraceable, and this is the pass
   where the difference showed: it costs minutes of an agent reading code with
   tools, and its answer reached exactly one channel that three separate
   conditions could swallow — a cwd belonging to no workstream, a schema that
   refused the value, an agent whose answer carried no verdict. Each left one
   stderr line in a stream nobody keeps. The outcome is returned so it can be
   recorded where the run's other evidence already is.

   A missing design record is the one absence with no outcome to record: report
   init's `:context` already says the run had nothing to judge against, and the
   pass never launches, so nothing was spent and nothing was lost."
  [cwd final report config]
  (try
    ;; Read here to answer `is there anything to judge`; verdict/run! resolves it
    ;; again for the prompt. One extra ledger read, against not handing run! a
    ;; record it is the authority on finding.
    (let [design (stages/discover-design-record cwd)]
      (when (and design (verdict-worth-running? (:status final) final design))
        (if-let [markers (unreadable-tree cwd (:base config))]
          ;; Recorded rather than dropped. Every other reason this pass does not
          ;; run is legible from the status sitting beside it in the report; a
          ;; conflicted tree is legible from nothing the run wrote, so a reader
          ;; would find the field the pass exists to fill simply absent.
          {:outcome :skipped
           :because (str "the branch is holding conflict markers on "
                         (str/join ", " markers)
                         " — resolve them and re-run; the pass reads the worktree"
                         " with tools and would judge the design against them")}
          (if-let [v (verdict/run! {:cwd cwd
                                    :run-id (:run-id config)
                                    :budget (:budget config)
                                    :final final
                                    :report report})]
            (assoc (append-verdict-to-ledger! cwd v) :outcome :answered :verdict v)
            {:outcome :no-answer
             :because (str "the pass ran and its answer carried no verdict"
                           " — the transcript is agent.log in this run dir")}))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "review-loop: design verdict skipped — " (ex-message e))))
      nil)))

(defn- record-verdict!
  "Fold the verdict pass's outcome into report.json, and say on the terminal when
   the verdict never happened or went somewhere other than where it should have.

   The report is written even when the ledger took the verdict. The two records
   answer to different readers — the ledger entry is what someone reading the
   WORKSTREAM gets, the report is what someone reading the RUN gets — and only
   the second is guaranteed to exist, since a review outside a session has no
   ledger to write to at all."
  [outcome report-atom report-path]
  (when outcome
    (report/persist! (swap! report-atom report/with-verdict outcome) report-path)
    (binding [*out* *err*]
      (case (:outcome outcome)
        :no-answer (println (str "review-loop: the design verdict pass returned no verdict — "
                                 (:because outcome)))
        ;; Said here as well as in the report because the reader who most needs
        ;; it is the one sitting in front of a conflicted branch deciding
        ;; whether resolving it buys them anything.
        :skipped   (println (str "review-loop: the design verdict pass did not run — "
                                 (:because outcome)))
        :answered  (when (= :refused (:ledger outcome))
                     (println (str "review-loop: the design verdict was REFUSED by the ledger — "
                                   (:because outcome)))
                     (println (str "  it is in " report-path
                                   " — this is a bug in nido, not a bad verdict")))
        nil))))

(defn- print-verdict!
  "Report the verdict on the terminal. An :invalidated / :standing-challenged
   verdict is a decision for the human, and `nido review:loop` is human-invoked —
   they are sitting in front of this — so it is stated loudly rather than filed
   somewhere they might not look.

   A verdict can now point at two different remedies, and saying the wrong one is
   worse than saying nothing: a broken load-bearing property means the change did
   not do what it said, while a finding classified :baseline means the baseline was
   wrong and the design may be fine. Redesign and re-survey are not the same
   instruction.

   A carried verdict says so on the line under it. Read as a fresh judgment it
   would tell the human the code was looked at again and came back the same,
   which is the one thing a carry does not establish."
  [v]
  (when v
    (println (str "review-loop: design verdict — " (name (:verdict v))))
    (when-let [n (:carried-from v)]
      (println (str "  (carried from entry " n " — nothing this run gave it to revisit)")))
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
  "The wall clock a review loop's agent launches are sized from, when the caller
   names none.

   A DEFAULT, not a cap on the run: the loops are deliberately uncapped in
   ROUNDS — they end when they converge, escalate, retreat or stall — and this
   bounds one launch inside a round. The two are not substitutes. With rounds
   uncapped, a single hung claude is the one failure the loop cannot detect on
   its own merits, because a round that never returns never reports anything to
   stall on.

   Sized from rather than spent by, because one stage does not take it as given:
   the reviewers, the warden and the verdict read and report, while the fixer
   also edits and verifies, and `nido.review.stages/fix-budget` scales this by
   the findings that fixer was handed. This figure is what a report-only launch
   needs, which is the floor the fixer's is built on and not a wall it shares.

   It exists because agent/launch! now refuses an undeclared budget. Before that,
   omitting it here meant every warden, fixer and amender ran with no timer at
   all — which the record loop's own docstring already assumed was impossible,
   calling this per-launch wall clock \"the only thing between a hung claude and a
   loop that never returns.\""
  "30m")

(def ^:private record-loop-kinds
  "The record loops name themselves with a string that reaches the terminal and
   the run id; the claim's vocabulary is keywords. Mapped here rather than
   built with (keyword (str kind \"-round\")), so that adding a loop whose claim
   nobody enumerated fails loudly instead of writing a kind no surface renders."
  {"baseline" :baseline-round
   "design"   :design-round})

(def ^:private kind-labels
  "What each activity is called on a terminal. The claim's :kind is a keyword and
   a person is not reading keywords, so the two are kept apart here rather than
   printing (name kind) at the point of refusal."
  {:diff-review    "a branch review"
   :baseline-round "a baseline round"
   :design-round   "a design round"})

(defn- target-words
  "What a claim says it is judging, in words. Empty of named options is not
   nothing — it is the command's default target — so it says so rather than
   printing an empty map at a person."
  [target]
  (let [named (->> target
                   (remove (comp nil? val))
                   (map (fn [[k v]] (str (name k) " " v)))
                   sort)]
    (if (seq named) (str/join ", " named) "the default")))

(defn ^{:malli/schema [:=> [:cat :any :any [:? :any]] :string]}
  refusal-lines
  "What to tell someone whose workstream is already busy with something else —
   a different round, or the same round of a different thing.

   Pure in the arity that takes `daemon-pid`, and it says what is running rather
   than that something is: `refused` with no subject is the message that sends a
   person to `ps`. The holder's own report path goes on the line because it is
   the thing they will want next.

   The pid is display, and the LAST line is where that distinction bites. A
   mechanical round runs inside the coordinator daemon, which hosts every other
   workstream it is driving, so `kill` there ends all of them — the instruction
   would be the most destructive thing on the screen. Compared against the
   daemon's own pid file rather than guessed at from the kind: a round is
   daemon-hosted because of WHO ran it, and the same stage run by hand is an
   ordinary process a person may end."
  ([mine their] (refusal-lines mine their (daemon-pid/read)))
  ;; `coordinator-pid` rather than `daemon-pid`, which is this namespace's alias
  ;; for the pid file reader — a local of that name reads as a shadow of it.
  ([mine their coordinator-pid]
   (str "refused: this workstream is already running "
        (get kind-labels (:kind their) (str (:kind their)))
        " (pid " (:pid their) ", since " (:started-at their) ").\n"
        (if (= (:kind mine) (:kind their))
          ;; Same round, different subject. Naming both is the whole line: the
          ;; reader asked for one baseline and something else is verifying
          ;; another, which reads as `busy` and is not.
          (str "  it is judging " (target-words (:target their))
               " and you asked for " (target-words (:target mine))
               " — one tree cannot answer both.\n")
          (str "  " (get kind-labels (:kind mine) (str (:kind mine)))
               " would review the same tree from underneath it.\n"))
        (when-let [rp (:report-path their)] (str "  its report: " rp "\n"))
        (if (and coordinator-pid (= (long coordinator-pid) (:pid their)))
          (str "  the coordinator daemon is running it (pid " (:pid their)
               ") — ending that process would take down every other workstream"
               " it is driving, so wait for this one to finish.")
          (str "  end it with `kill " (:pid their) "`, or wait for it to finish.")))))

(defn- orphan-line
  "One dead run, named by where it stopped and by the report a reader opens next."
  [{:keys [run-id in-flight report-path]}]
  (str "  " run-id " stopped in round " (:round in-flight)
       (if-let [ph (:phase in-flight)] (str "'s " ph " phase") ", between phases")
       "\n    " report-path))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :string]]}
  orphans-settled-lines
  "What this run closed on its way in. Nothing when nothing died, which is the
   overwhelming majority of runs.

   Said out loud rather than left in the run dirs because it is the only notice
   the analyses get queued: several sessions can appear on nido's board from one
   `review:loop` invocation, and a reader who was not told why reads them as the
   coordinator having invented work."
  [{:keys [settled]}]
  (if (empty? settled)
    []
    (cons (str "review-loop: " (count settled) " earlier review run"
               (when (not= 1 (count settled)) "s")
               " died on this tree without a terminal status —"
               " closed as `orphaned` and queued for analysis")
          (map orphan-line settled))))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :string]]}
  orphans-refusal-lines
  "Why nothing was reviewed, when a dead run stopped in its fix phase.

   Two refusals, and the difference is whether the dead run's agents are still
   going. One that has gone quiet is history and its report has just been closed
   by `orphans-settled-lines`' own subject, so this fires once and the next
   invocation reviews the branch as it now stands — the reader is being told what
   the branch IS, not asked to do anything. One still writing is not history: its
   fixers outlived the loop, nothing is supervising them, and reviewing now would
   read a tree they are part-way through rewriting."
  [{:keys [settled writing]}]
  (cond
    (seq writing)
    (concat [(str "refused: " (count writing) " review run"
                  (when (not= 1 (count writing)) "s")
                  " died on this tree and the agents they launched are still"
                  " running.")]
            (map orphan-line writing)
            [(str "  nothing supervises them and nothing holds the claim — they are"
                  " still rewriting the branch, and reviewing it now reads it mid-edit.")
             "  → wait for them to finish, or end them, then run this again."])

    (seq (filter reconcile/fixing? settled))
    ["refused: a review run died while repairing this branch."
     (str "  its fixers outlived it and landed whatever they got to, which no round"
          " ever reviewed — the branch is not what anybody signed off.")
     (str "  → nothing was reviewed. Run this again to review the branch as it"
          " now stands.")]

    :else []))

(defn- terminal-status
  "The status a run has ENDED on, read off its own report, or nil while it is
   still running — or when the report cannot be read at all, which is
   indistinguishable from here and asks the same thing of every caller: do not
   treat this run as finished."
  [report-path]
  (let [s (:status (frontend/read-report report-path))]
    (when (and (string? s) (not= "running" s)) (keyword s))))

(defn- holder-outcome
  "The terminal status the run we followed ended on, read off its own report, or
   :detached when the report cannot say.

   A JOINER'S RETURN IS A WORK OUTCOME, not a description of what it did. The
   record loops have an in-process caller — `lane.drive/run-stage!` — that reads
   the returned status through `pipeline/disposition`, so a follower reporting
   that it followed makes a holder's :sufficient into an unrecognised terminal,
   and an unrecognised terminal parks a blocker. The holder already wrote the
   answer by atomic rename; this reads it rather than inventing one.

   :detached only when the report is unreadable or still says `running` — the
   holder died mid-write, or its path was never published. That is a fact about
   the machinery rather than about the work, and `pipeline` classifies it as
   one.

   Whether the run this names was really the claim's holder is the caller's
   business rather than this fn's: a payload can be read while the claim changes
   hands, and `join-or-refuse!` decides that from what holds the claim once
   following stops. See the `superseded?` binding there."
  [report-path]
  (or (terminal-status report-path) :detached))

(def ^:private verb-stages
  "The pipeline stage each of these verbs runs, so a verb can be compared with
   the position that would have chosen for it. Three entries because there are
   three verbs; a fourth would be a fourth entry, and no verb has two stages."
  {:diff-review    :review-implementation
   :baseline-round :verify-baseline
   :design-round   :decide-design})

(defn ^{:malli/schema [:=> [:cat :any :keyword] [:maybe :string]]}
  off-position-line
  "One line when the stage a verb was told to run is not the stage the ledger
   says is due, or nil when they agree or there is nothing to compare against.

   NOT A REFUSAL, and that is the point of it being a line. Every legitimate use
   of these verbs is a person who knows the position and means something else —
   re-judging a superseded record with `:seq`, judging against another tree with
   `:code-cwd`. Refusing would take the override away at the moment it is most
   wanted; saying nothing leaves the caller unable to tell an override from a
   mistake, which is what a verb naming its own stage has always done."
  [cwd kind]
  (when-let [stage (verb-stages kind)]
    (when-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
      (let [due (:stage (:next (pipeline/of project ws-id)))]
        (when (and due (not= due stage))
          (str "note: this workstream is owed " (name due) ", not " (name stage)
               " — running it because you named it."
               " `bb nido:attach` runs whatever the ledger chooses."))))))

(defn ^{:malli/schema [:=> [:cat :map :any :ProjectName :WorkstreamId] :any]}
  join-or-refuse!
  "What a run does when the workstream is already claimed: JOIN the holder when
   it is doing the same work, refuse when it is doing something else.

   The split is about what the second caller wanted. Asking for the review that
   is already running means you want to see it — so you are shown it, live, and
   Ctrl-C leaves it running. Asking for DIFFERENT work means you want something
   the holder is not doing, and running it anyway would put two agents on one
   tree; that is the case where a person has to choose, so it is handed back to
   them rather than guessed at.

   SAME WORK IS KIND AND TARGET, and the target half is not a refinement. A
   baseline round names which baseline with :seq and which tree with :code-cwd,
   a diff review names its :base — so a joiner that compared kinds alone would
   attach to a round verifying a different entry, watch it finish, and report a
   review of the thing it asked about that nobody performed.

   `mine` is what the caller asked for: {:kind :target :render-fn}. `render-fn`
   is the frame it would have painted, and it is the right one to follow with
   precisely because a join happens only when the kinds agree — a baseline round
   joined by a baseline round is painted by the record frame, not by a diff
   review's.

   Returns the holder's own terminal status after a join, or :refused."
  [{:keys [kind target render-fn] :as mine} their project ws-id]
  (if (and (= kind (:kind their)) (= target (:target their)))
    (do
      (println (str "attached to " (:run-id their) " (pid " (:pid their)
                    ", since " (:started-at their) ")"
                    " — Ctrl-C detaches, the run keeps going"))
      (frontend/follow!
       (cond-> {:report-path (:report-path their)
                ;; The lock is still the liveness — but the question is whether
                ;; THIS holder has it, not whether anybody does. A follower that
                ;; asked the workstream-wide question would keep painting a
                ;; finished run's frozen report for as long as its replacement
                ;; ran, and call it live. Reading the claim answers both at once.
                :stop? #(not= (:run-id their)
                              (:run-id (activity/read-live project ws-id)))}
         render-fn (assoc :render-fn render-fn)))
      ;; WHY following stopped is what says whether the run we watched was the
      ;; one that held the claim. Two things end a follow, and only one of them
      ;; leaves a verdict this invocation may report:
      ;;
      ;;   the claim is free      — the holder we followed finished and let go,
      ;;                            so its report is the answer to this request.
      ;;   another run holds it   — the payload we attached to was the PREVIOUS
      ;;                            holder's, read while the claim changed hands
      ;;                            and before the replacement published. That
      ;;                            run reached its verdict before this request
      ;;                            existed, and adopting it would report a
      ;;                            review nobody asked for.
      ;;
      ;; Asked here rather than at attach time because a terminal report cannot
      ;; answer it: a run finalizes its report and then holds the claim through
      ;; its post-processing, so `already finished` is the normal state of a
      ;; holder that is still working.
      (let [superseded? (when-let [now (activity/read-live project ws-id)]
                          (not= (:run-id their) (:run-id now)))
            status      (if superseded? :detached (holder-outcome (:report-path their)))]
        (println (str "detached — " (:run-id their)
                      (cond
                        superseded?          " was replaced while this watched it"
                        (= :detached status) " is no longer running, and left no terminal status"
                        :else                (str " ended " (name status)))))
        status))
    (do (binding [*out* *err*] (println (refusal-lines mine their)))
        :refused)))

(def ^:private unconfirmed-holder-attempts
  "How many times to take the claim again when the holder we lost to cannot be
   confirmed. A hair, because every one of these windows is a read or two wide
   and closes on its own: either the workstream is free now and the retry simply
   runs, or somebody is holding it, has published, and the retry is handed their
   claim."
  3)

(def ^:private unconfirmed-holder-wait-ms 100)

(def ^:private unconfirmed-holder-lines
  "What to say when the claim is held by something we cannot pin down: the
   holder took the lock and has not published yet, ended between our failed
   attempt and our read, or handed the claim on while we read it. Rare, and
   printed only after retrying — but it has to be printed, because the
   alternative was a review command that reviewed nothing, joined nobody, said
   nothing and exited 0."
  (str "refused: this workstream is claimed, but the holder could not be"
       " confirmed — it ended, handed the claim on, or had not yet said what it"
       " was running.\n"
       "  nothing was reviewed — run it again."))

(defn- confirmed-holder
  "`their` when it is still the claim's holder and its run has not already
   ended, or nil when nothing may be decided on it.

   `read-live` has ONE accepted window, and it is the one that matters here: a
   payload read while the claim changes hands is the PREVIOUS holder's, one
   activity stale. Every decision downstream is identity-sensitive — a join
   adopts that run's terminal status as the answer to a request it predates, and
   a refusal names its pid as the process to end — so the payload is confirmed
   rather than trusted.

   Read again and require the SAME run. That catches a replacement which has
   already published its own claim, which is the shape that can actually mislead
   a caller: it would join or name a run that no longer owns anything.

   A FINISHED REPORT IS NOT A DEAD HOLDER, and requiring otherwise was wrong. A
   run finalizes its report and then keeps the claim through its post-processing
   — the diff loop's design verdict launches an agent and can hold it for
   minutes — so `report is terminal` rejected a holder that was working, and the
   second caller was told to kill a run doing exactly what it should. The window
   it did catch, where a replacement holds the lock and has not published so both
   reads answer with the run that ended, is self-correcting: a follower stops on
   the same identity test the moment the replacement publishes, and the cost is
   one frame of a finished report rather than a refusal a person has to act on.

   Unconfirmed is not refused. It is `ask again`, which is what the caller's
   loop does, and it is the same fail-toward-absent direction the reader
   protocol already takes: under-report a live claim for one poll rather than
   act on a dead one."
  [their project ws-id]
  (when (and their
             (= (:run-id their) (:run-id (activity/read-live project ws-id))))
    their))

(defn ^{:malli/schema [:=> [:cat :map [:=> [:cat] :any]] :any]}
  claiming
  "Run `f` holding this workstream's activity claim, or hand the caller to
   whoever already has it.

   A review OUTSIDE a nido session takes no claim and simply runs, which is the
   same tolerance `run-context` already reports: there is no workstream to be the
   singleton of, so there is nothing to exclude and nothing for a second caller
   to join. Losing the claim is not a failure of the run — it is the run finding
   out that the work is already being done — so the caller gets :detached or
   :refused rather than an exception.

   `:target` says WHAT this run is judging, in the caller's own terms, and it is
   published with the claim because a second caller cannot otherwise tell the
   round it asked for from another round of the same kind.

   A refusal is read from `refused?` and never from the holder it carries. The
   two come apart: a claim can be lost to a holder that exits before its payload
   is read, and a refusal with nothing in it is the one result that must not be
   mistaken for `f` having run — nothing did. A holder that cannot be confirmed
   is the same shape of answer: take the claim again rather than decide anything
   about a run that may already be over."
  [{:keys [cwd kind target run-id report-path render-fn]} f]
  (if-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
    (loop [attempts unconfirmed-holder-attempts]
      (let [res (activity/with-claim project ws-id
                  {:kind kind :target target
                   :run-id run-id :report-path report-path}
                  f)]
        (if-not (activity/refused? res)
          res
          (if-let [their (confirmed-holder (activity/refused res) project ws-id)]
            (join-or-refuse! {:kind kind :target target :render-fn render-fn}
                             their project ws-id)
            (if (pos? attempts)
              (do (Thread/sleep unconfirmed-holder-wait-ms)
                  (recur (dec attempts)))
              (do (binding [*out* *err*] (println unconfirmed-holder-lines))
                  :refused))))))
    (f)))

(defn ^{:malli/schema [:=> [:cat :Path] :any]}
  run-context
  "What this run can and cannot reach, as {:has [..] :missing [..]}.

   A review outside a nido session still runs, and should — but it runs WITHOUT
   the convergence cache, the ledger, the design record and the project stance,
   and every one of those absences changes what the loop does. It skips nothing,
   judges against no invariants, writes no entry, and reports exactly as a full
   run does. The loss was recorded nowhere at all, so a thin run and a complete
   one produced indistinguishable reports and only the second was trustworthy."
  [cwd]
  (let [ws      (stages/project+ws-from-cwd cwd)
        design  (when ws (stages/discover-design-record cwd))
        stance  (when ws (stages/read-stance (first ws)))
        checks  [["workstream ledger" (boolean ws)]
                 ["convergence cache" (boolean ws)]
                 ["design record"     (boolean design)]
                 ["project stance"    (boolean stance)]]]
    {:has     (mapv first (filter second checks))
     :missing (mapv first (remove second checks))}))

(def diff-remedies
  "The ways a diff run can end, and what each one asks of the reader.

   `nido.review.loop/engine-statuses` and `nido.review.stages/stage-statuses`
   are together the diff loop's whole terminal vocabulary, and every member of
   them needs a line here: a status with none arrives as the bare word this map
   exists to replace. Neither of those sets can see this one — they are declared
   two bands down — so a test is what holds the three together.

   Data rather than a cond, for the reason `shared-remedies` is: no two of these
   may collapse onto a shared line, and what separates them is what a reader
   does next rather than how the run felt. `:clean`, `:nothing-to-review` and
   `:review-failed` all end a run reporting no finding, and they mean a verdict,
   an empty diff and no verdict at all."
  {;; ── the run reached the end of what it can do ──
   :converged "the warden stopped with nothing still owed — every finding was fixed, closed or declined"
   :clean "a reviewer read the diff and reported nothing"
   :dry-run "nothing was fixed"

   ;; ── it stopped holding something only you can settle ──
   :unresolved "the warden stopped the round with findings still owed — no fixer was asked for them"
   :escalated "a finding contradicts an invariant the design names, so what is in question is the design rather than its execution"
   :unfixable "raised round after round and never moved, or a park that has stood too long — the loop has nothing further to offer them"
   :no-progress "the round changed nothing the reviewers can see — the findings it left are what you get"
   :fix-declined "every fixer read what it was handed and said no — the findings stand, with their reasons beside them in the report"
   :fix-timed-out "a fixer was killed on its budget with the tree untouched — nothing was decided, so the findings stand for want of time and the run wants more room rather than a re-run"
   :fix-unrouted "no finding reached a layer a fixer can touch — what is in question is the routing, not any repair"
   :fix-rolled-back "every repair was refused by the rebase and put back, so the branch is exactly what was reviewed — a re-run earns the same refusal; what is in question is the layer order"
   :max-iters "the cap you passed was reached — this is not convergence, and the findings were still open"

   ;; ── the branch is holding conflict markers ──
   ;; Named apart because they say different things about what put the markers
   ;; there, and one of them costs a round's work: on :fix-conflicted the
   ;; repairs below the conflict have landed and the fixers above it never ran.
   :fix-conflicted "a repair conflicted the layers above it and would not roll back, so the branch is holding markers — resolve them and re-run; the repairs that did land are kept"
   :stack-conflicted "the branch was already holding markers before a reviewer read a line — resolve them and re-run; nothing was reviewed"

   ;; ── nothing was learned about the branch ──
   :nothing-to-review "every diff was empty — nothing was read, so this is not a clean bill; check the base you passed"
   :review-failed "the reviewer broke — this is not a clean bill, and the branch is unjudged"
   :reviewer-unavailable "the reviewer could not be run at all; nothing was reviewed and the branch is unjudged"
   :warden-indeterminate "the warden returned no decision, so nothing was attributed and no repair was attempted — re-run"
   :workspace-drifted "the working copy moved after the reviewers read it, so no repair could land on the tree they judged — re-run"})

(defn ^{:malli/schema [:=> [:cat :map :map :string] [:sequential :string]]}
  outcome-lines
  "What a finished run says on the terminal: the status, the particulars only
   this run holds, and the sentence saying what the status asks of you.

   Returned rather than printed so the sentences can be asserted on — this is
   the whole of what an operator gets when the run is over, and the report it
   points at is a JSON file in a run dir.

   `final` is the terminal ctx and `report` is the whole run; both are needed
   because a ctx is rebuilt every round, so anything a middle round did is
   remembered by the report alone."
  [final report report-path]
  (let [status (:status final)
        {:keys [unavailable conflicted drift]} final
        refused  (refused-repairs final)
        reshaped (report/applied-reshapes report)]
    (cond-> [(str "review-loop: " (name status) " · report " report-path)]
      ;; A reviewer that refused said what it wants — credits, a login, an hour
      ;; to come back at — and a reader standing here has nowhere else to have
      ;; got that from.
      unavailable
      (conj (str "  " (:message unavailable)))

      ;; The ids `jj resolve` takes. The conflict is mid-stack, so
      ;; `jj resolve --list` answers that the branch is clean and these are the
      ;; only pointer at what to open.
      (seq conflicted)
      (conj (str "  conflicted: " (str/join ", " conflicted)))

      ;; The repairs that were written and put back. On :fix-rolled-back the
      ;; remedy line below says the branch is unchanged and a re-run earns the
      ;; same refusal — which layer collided with which is what turns that into
      ;; something to do, and it is what a reorder would have to be aimed at.
      (seq refused)
      (conj (str "  refused: "
                 (str/join ", "
                           (for [{:keys [layer conflicted]} refused]
                             (str (or layer "the branch")
                                  (when (seq conflicted)
                                    (str " (conflicted "
                                         (str/join ", " conflicted) ")")))))))

      ;; The two revisions the refusal is ABOUT. The remedy line below says the
      ;; tree moved; without these the operator cannot tell a rebase they did
      ;; themselves from one another session made, which decides whether a
      ;; re-run is the whole answer.
      drift
      (conj (str "  reviewed at " (:reviewed-at drift)
                 ", tree now at " (or (:now drift) "a revision jj would not name")))

      ;; The loop rewrote the branch. Said here because the status is about the
      ;; REVIEW and this is about the code the operator is standing in — a run
      ;; that folded two layers otherwise ends on a line about findings, with
      ;; the fold recorded in a JSON file nobody was told to open.
      (seq reshaped)
      (conj (str "  reshaped: "
                 (str/join ", "
                           (for [{:keys [outcome lower upper round]} reshaped]
                             (str outcome
                                  (when (and lower upper) (str " " lower "…" upper))
                                  " (round " round ")")))))

      ;; The layers a fixer was owed and never launched for. On an abort the
      ;; fix phase is otherwise indistinguishable from a round with no work in
      ;; it — same empty fix list, same silence.
      ;; `nil` is the plan's own name for a branch with no layers, which is most
      ;; sessions — printed bare it is an empty gap where a name should be.
      (seq (:unattempted final))
      (conj (str "  never attempted: "
                 (str/join ", " (map #(or (:layer %) "the branch")
                                     (:unattempted final)))))

      :always
      (conj (str "  → " (or (diff-remedies status)
                            (str "unrecognised terminal status: " status)))))))

(defn- review-branch!
  "Drive the loop over the branch and record what it found, returning the
   terminal status.

   Split from `loop-cmd*` for the same reason `record-loop-body` is split from
   its command: what happens once the claim is held is a story of its own, and
   the command in front of it is now two decisions rather than one — take the
   claim, and settle what died on this tree before anything reads it."
  [{:keys [cwd config clock report-atom report-path]}]
  (let [final  (frontend/with-live-display
                 {:report-atom report-atom :report-path report-path :clock clock}
                 (fn [emit] (rloop/run-loop (assoc config :emit emit))))
        status (:status final)
        ws-id  (append-review-entry! cwd final @report-atom report-path)]
    ;; The status names the condition and `diff-remedies` says what it asks
    ;; of whoever ran this. A coordinator-driven round says it a second
    ;; time, through the lane's disposition and a gate entry; a round a
    ;; person ran themselves says it here or nowhere.
    (run! println (outcome-lines final @report-atom report-path))
    ;; A side record that fails invisibly is how a whole class of run came
    ;; to leave no ledger entry at all: the ledger's status enum did not
    ;; admit :unfixable, every append on that status was refused, and the
    ;; refusal went to a stderr stream nobody keeps. nil here means the
    ;; workstream holds nothing about this run, whether because there is no
    ;; workstream or because it would not take the entry — and either way
    ;; the report is the only copy.
    (when-not ws-id
      (println (str "review-loop: ⚠ no :review entry reached a ledger"
                    " — this run is recorded in " report-path " alone")))
    ;; The verdict BEFORE the halt, and the order is the whole point twice over.
    ;; It is the answer to the question the halt asks, so a halt filed first is a
    ;; gate offering to decline findings the run already knew how to repair. And
    ;; a :design-verdict is a :design-stage entry: appended after the halt it is
    ;; the work having moved on, which is exactly what pipeline/unanswered-blocker
    ;; reads it as, so the halt reached no gate at all.
    ;;
    ;; The halt survives a verdict pass that fails, because that pass catches its
    ;; own exceptions and answers nil — the only loss is the third branch.
    (let [outcome (append-design-verdict! cwd final @report-atom config)]
      (record-verdict! outcome report-atom report-path)
      (print-verdict! (:verdict outcome))
      (when-let [b (append-blocker! cwd final (:verdict outcome))]
        (println (str "review-loop: ⚠ " (:summary b)))
        (println "  → answer it at the workstream gate; the loop has no move for it")))
    ;; Last, so the analysis session finds everything this run wrote — the
    ;; report, the :review ledger entry and the design verdict are all on
    ;; disk by the time the envelope exists. The verdict is also IN the
    ;; envelope, read back off the report `record-verdict!` just folded it
    ;; into, so moving this line above that one would publish a headline
    ;; that silently drops it.
    (queue-analysis! cwd final @report-atom report-path config ws-id)
    status))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  loop-cmd* [{:keys [cwd base max-iters dry-run? budget]}]
  (let [;; Through the home-aware resolution WHETHER OR NOT a cwd was named. A
        ;; session home is a place an agent legitimately stands, and passing one
        ;; explicitly used to skip worktree-from-cwd entirely — so the run
        ;; resolved no workstream, took the claimless fallback, and two
        ;; invocations given the same home both reviewed the same worktree with
        ;; neither able to see the other. The record loops already resolve this
        ;; way; the diff loop did not.
        given      (or cwd (System/getProperty "user.dir"))
        cwd        (or (lifecycle/worktree-from-cwd given) given)
        base       (or base "main")
        run-id     (str "review-" (random-uuid))
        clock      #(Instant/now)
        report-path (str (fs/path (cstate/run-dir run-id) "report.json"))
        context    (run-context cwd)
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
                    :open?     (complement stages/settled?)
                    ;; Also what the engine cannot ask for itself: whether the
                    ;; round moved the code. Without it a repeated finding set
                    ;; is read as a stall, and a defect class the fixers are
                    ;; still narrowing repeats its handles by construction — one
                    ;; run ended that way on the round after it landed two
                    ;; repairs, throwing away a ruling that named two untried
                    ;; remedies.
                    :changed?  stages/round-changed?
                    ;; A repair is aimed at a layer, so a finding the warden
                    ;; re-attributes has not been attempted where it now points.
                    ;; Without this the give-up counter reads three attempts at
                    ;; the wrong layer as three failures and stops the run on
                    ;; the round that first aimed it correctly.
                    :attempt-key (rloop/default-attempt-key rloop/default-finding-key)
                    ;; And a park aims no repair anywhere, so the round it was
                    ;; ruled in is not an attempt at all. Without this the
                    ;; counter gives up on a defect the loop tried once and then
                    ;; put to a human, ahead of the rule that governs a standing
                    ;; park and outside the one that decides which parks stop a
                    ;; run.
                    :attempted?  stages/repair-attempted?
                    ;; The warden is the stage that judges; everything after it
                    ;; repairs. A run whose terminal condition is already
                    ;; decided must not go on to spend fixer launches and land
                    ;; commits that no round will ever review — which is exactly
                    ;; what the last round of an :unfixable run was doing.
                    :judged-after :warden}
        report-atom (atom (report/init {:run-id run-id :cwd cwd :base base
                                        :started-at (str (clock))
                                        :context context
                                        :machinery (provenance/loaded-from)}))
        _ (when (seq (:missing context))
            (println (str "review-loop: running WITHOUT "
                          (str/join ", " (:missing context))
                          " — this run cannot skip converged layers, judge"
                          " against a design, or record what it found")))
        _ (some-> (off-position-line cwd :diff-review) println)]
    ;; Everything from here runs under the claim, which is what makes a second
    ;; invocation join this run instead of reviewing the same tree from
    ;; underneath it. A caller that could not take it never reaches the engine.
    (claiming
     {:cwd cwd :kind :diff-review :run-id run-id :report-path report-path
      ;; WHAT this reviews, so a second invocation against another base is
      ;; refused rather than attached to a review of a different diff.
      :target {:base base}}
     (fn []
       ;; Under the claim and before a reviewer reads a line, which is the only
       ;; moment either half of this is sound: holding the claim is what makes
       ;; every other `running` report on this tree a dead process, and a
       ;; refusal after the reviewers have run is one that already paid for
       ;; itself. A review outside a nido session takes no claim, has excluded
       ;; nothing, and settles nothing — see `reconcile/orphans`.
       (let [orphaned (reconcile/settle! {:cwd cwd :run-id run-id})]
         (run! println (orphans-settled-lines orphaned))
         (if (:proceed? orphaned)
           (review-branch! {:cwd cwd :config config :clock clock
                            :report-atom report-atom :report-path report-path})
           (do (binding [*out* *err*]
                 (run! println (orphans-refusal-lines orphaned)))
               :refused)))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  loop-cmd [& args]
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
   false — measured: two rounds on one baseline, shape and composition both
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
   design check and a baseline gap, carry it as the keyword after the tag. The
   tag is skipped: `blocks` and `check` say which KIND of handle this is, and
   printing that instead of `decomposable` names the mechanism rather than the
   thing the run could not resolve."
  [k]
  (let [parts (flatten [k])]
    (or (some #(when (string? %) %) parts)
        (some #(when (keyword? %) (name %)) (rest parts))
        (pr-str k))))

(defn- record-loop-body
  "One record round, from the engine to the last printed line. Split out of
   `record-loop-cmd*` only so the claim can wrap it — everything here is what the
   command always did."
  [{:keys [cwd code-cwd kind run-id clock title report-path report-atom
           plain emit pipeline finding-key max-iters dry-run? budget baseline
           remedies epilogue]}]
  (let [final  (try
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
    ;; stopped because the design cites an unverified baseline said which status it
    ;; ended in and never said WHICH baseline, which is the only part a reader
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

   They are not the same tree when a baseline describes an area BEFORE a change
   that is already written. A baseline is supposed to be fillable without
   knowing the fix; judged against a worktree that carries the fix, it is told
   its own subject does not exist — the round reports the change's new modules
   as things the baseline failed to mention, and an amender asked to repair that
   folds the change INTO the baseline it was supposed to be judged against. The
   record then describes the post-change world, and every relation the design
   declares to it is answered against a premise that already contains the
   answer.

   So the revision is an axis of its own, separate from the workstream: point
   `:code-cwd` at a checkout of the base and the baseline is judged against the
   area as it was, while the ledger stays where the work is.

   The final block prints from a `finally`, so a loop that throws still leaves
   its rounds, its weakenings and its objections on screen."
  [{:keys [kind pipeline finding-key remedies epilogue]}
   ;; `seq-n`, not `seq` — see baseline-cmd*. Read here only to publish it as
   ;; the claim's target; which entry it names is baseline-at's business.
   {:keys [cwd code-cwd max-iters dry-run? budget baseline] seq-n :seq
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
                                        :started-at (str (clock))
                                        :machinery (provenance/loaded-from)}))
        plain  (frontend/plain?)
        emit   (frontend/emit-fn report-atom report-path clock plain)]
    (some-> (off-position-line cwd (record-loop-kinds kind)) println)
    ;; Under the claim from here, exactly as the diff loop is: two record rounds
    ;; amending one workstream's ledger at once would each judge a record the
    ;; other is rewriting.
    (claiming
     {:cwd cwd
      :kind (or (record-loop-kinds kind)
                (throw (ex-info "Record loop has no activity kind"
                                {:kind kind :known (vec (keys record-loop-kinds))})))
      :run-id run-id :report-path report-path
      ;; The two axes a record round is aimed along: WHICH record, and which
      ;; tree it is judged against. A round of the same kind on either other
      ;; value is different work, and joining it would report a verification of
      ;; the entry nobody verified.
      ;; The EFFECTIVE tree, which is what the pipeline judges with — a round
      ;; given no :code-cwd and one given its own worktree do identical work, and
      ;; publishing the raw value made them look like different targets, so the
      ;; second was refused as other work rather than joined.
      :target {:seq seq-n :code-cwd (or code-cwd cwd)}
      ;; The frame this loop paints for itself, so an invocation that finds the
      ;; round already running watches it in the record frame rather than
      ;; through a diff review's header.
      :render-fn (fn [report now] (render/record-frame report now {:title title}))}
     (fn []
       (record-loop-body
        {:cwd cwd :code-cwd code-cwd :kind kind :run-id run-id :clock clock
         :title title :report-path report-path :report-atom report-atom
         :plain plain :emit emit :pipeline pipeline :finding-key finding-key
         :max-iters max-iters :dry-run? dry-run? :budget budget
         :baseline baseline :remedies remedies :epilogue epilogue})))))

(def ^:private baseline-remedies
  "Only :sufficient ends a run. :insufficient is a VERDICT and never a status —
   a gap is something an amender can answer, so the round that reports one keeps
   going. It had a line here, which said the loop can end that way; it cannot."
  {:sufficient "the baseline holds against the code, and a decision can be made against it"
   :no-record "author the baseline first"
   :nothing-to-check "nothing in the baseline is refutable yet"})

(defn- baseline-at
  "The baseline named by :seq, or nil for `whichever is newest`.

   A workstream can hold baselines of DIFFERENT areas — a narrow follow-up beside
   the broad one it came out of — and a design cites one of them specifically.
   Without this the only baseline reachable from the command line is the newest,
   so the advice a blocked design gives (`verify the baseline it cites first`)
   names a command that cannot verify that baseline. It runs, it verifies the
   other one, and the design stays blocked with nothing to show for it.

   A :seq naming no entry is refused rather than silently falling back to the
   newest, which would be the same wrong baseline with no way to tell."
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

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  baseline-cmd*
  "Verify a baseline against the code, and keep correcting it until the code
   stops refuting it. :seq names WHICH baseline; the default is the newest."
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
   :premise-unverified "verify the baseline it cites first — `bb nido:review:baseline :seq <that entry>` — then decide against it"
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

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  design-cmd*
  "Decide, against the latest design record, whether this should be executed —
   repairing what is derivable and escalating what is not."
  [opts]
  (record-loop-cmd* {:kind "design"
                     :pipeline    record/design-pipeline
                     :finding-key record/design-finding-key
                     :remedies    design-remedy
                     :epilogue    design-epilogue}
                    opts))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  baseline-cmd
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (baseline-cmd* opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  design-cmd
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (design-cmd* opts)))
