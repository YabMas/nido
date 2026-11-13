;; src/tasks/nido_land.clj
(ns tasks.nido-land
  "The landing gate: refuse a branch whose design does not stand right now.

   Takes the pre-push position beside `bb nido:test`, and for the same reason —
   this repo has no CI and no merge queue, so what runs before the fast-forward
   is the only gate there is. The test suite asks whether the code works. This
   asks whether anyone still believes the premise it was written against.

   It refuses by exit code, not by a paragraph in a recipe. A rule that lives
   only in prose is followed by whoever read the prose."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [nido.coordinator.record.phase :as phase]
   [nido.coordinator.record.standing :as standing]
   [nido.design.check :as design]
   [tasks.nido-design :as nido-design]
   [nido.coordinator.record.workstream :as cws]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.daemon.launchctl :as lc]
   [nido.coordinator.daemon.pid :as pid]
   [nido.review.layers :as layers]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.task-args :as task-args]
   [nido.vsdd.jj :as jj]))

(defn- way-out
  "What to DO about a refusal, as commands rather than advice.

   Every refusal here stops an agent mid-landing, and an agent told only that it
   is blocked will either guess or stop. Each reason has one route back and it
   is short; spelling it at the point of refusal is the difference between a
   gate that teaches the workflow and one that merely enforces it."
  [{:keys [reason seq replaced-by]}]
  (case reason
    :premise-unverified
    (str "  1. bb nido:review:baseline :seq " seq " — until it answers `sufficient`\n"
         (when replaced-by
           (str "     (entry " replaced-by " already corrects it; verify THAT one\n"
                "      and cite it below — this one is not what a design should stand on)\n"))
         "  2. supersede the design so it cites the baseline that held:\n"
         "     a new :design entry with :baseline {:seq <the verified one> …}\n"
         "     and :supersedes {:seq <this design> :why \"…\"}\n"
         "  3. bb nido:review:design\n"
         "  4. Approve it in the gate inbox, unless the round cleared it")

    :premise-retracted
    (str "  The baseline is not merely stale, somebody found it FALSE — read entry "
         seq "\n  for the counterexample before you touch anything.\n"
         "  1. baseline the area again and append a new :baseline (/design §4)\n"
         (when replaced-by
           (str "     (entry " replaced-by " corrects it — start from there)\n"))
         "  2. bb nido:review:baseline — until it answers `sufficient`\n"
         "  3. supersede the design so it cites the corrected baseline\n"
         "  4. bb nido:review:design, then Approve it in the gate inbox unless\n"
         "     the round cleared it")

    :design-invalidated
    (str "  The review round read the code against this design and found the\n"
         "  DESIGN wrong, not its execution — entry " seq " says which invariants\n"
         "  it broke and what it needs. That is a decision, so it does not clear\n"
         "  itself; one of two things has to happen.\n"
         "  1. Redesign — the ordinary answer. Retract the design (the gate's\n"
         "     Redesign button writes it), then write a superseding :design,\n"
         "     bb nido:review:design, and approve it unless the round cleared it.\n"
         "  2. Accept it — if the round is wrong and you can say why. The gate's\n"
         "     Accept button records a second approval against entry " seq ",\n"
         "     which is the ledger saying a person read the invalidation and\n"
         "     granted the design anyway.\n"
         "  Do neither by hand: an approval that does not name the verdict looks\n"
         "  exactly like one made before it.")

    :premise-superseded
    (str "  The baseline this design cites was re-surveyed AFTER the design was\n"
         "  written, so the design stands on a reading nobody holds any more.\n"
         "  Nobody said it was false — this is not a retraction — the ground\n"
         "  simply moved under it.\n"
         (when replaced-by
           (str "  1. bb nido:review:baseline :seq " replaced-by
                " — until it answers `sufficient`\n"))
         "  2. supersede the design so it cites the re-survey:\n"
         "     a new :design entry with :baseline {:seq "
         (or replaced-by "<the new one>") " …}\n"
         "     and :supersedes {:seq <this design> :why \"…\"}\n"
         "  3. bb nido:review:design, then Approve it in the gate inbox unless\n"
         "     the round cleared it")

    :premise-goal-superseded
    (str "  The baseline this design stands on (entry " seq ") was scoped for a\n"
         "  goal that has since been replaced — entry " replaced-by " is the live one.\n"
         "  The survey is owed again before the design is:\n"
         "  1. baseline the area again under the live goal: a new :baseline with\n"
         "     :intent {:seq " replaced-by "} and :supersedes {:seq " seq " :why \"…\"}\n"
         "  2. bb nido:review:baseline — until it answers `sufficient`\n"
         "  3. supersede the design so it cites both, then bb nido:review:design,\n"
         "     and approve it unless the round cleared it")

    :goal-superseded
    (str "  The design serves the goal at entry " seq ", replaced at entry "
         replaced-by " —\n  it serves a goal nobody holds.\n"
         "  1. if the baseline under it was scoped for the same goal, baseline the\n"
         "     area again first — citing :intent {:seq " replaced-by "} and\n"
         "     superseding it — and bb nido:review:baseline until `sufficient`\n"
         "  2. supersede the design so it cites :intent {:seq " replaced-by "}\n"
         "  3. bb nido:review:design, then approve it unless the round cleared it")

    :goal-retracted
    (str "  The goal this work serves was found untrue — read entry " seq " for\n"
         "  why before you touch anything, then restate it.\n"
         "  1. append an :intent with :supersedes naming the retracted one\n"
         "  2. baseline the area again under it, citing the new intent, and\n"
         "     bb nido:review:baseline until `sufficient`\n"
         "  3. supersede the design so it cites both, then bb nido:review:design\n"
         "     and approve it unless the round cleared it")

    :design-retracted
    (str "  Somebody found this design untrue — read entry " seq " first.\n"
         "  Write a superseding :design, run bb nido:review:design, and have it\n"
         "  approved unless the round cleared it. Do not amend the retracted one:\n"
         "  it stays in the ledger.")

    :no-premise
    (str "  This design cites no baseline — it predates the baseline event.\n"
         "  1. baseline the area and append a :baseline (/design §4)\n"
         "  2. bb nido:review:baseline\n"
         "  3. supersede the design so it cites it, then re-decide — and approve\n"
         "     it unless the round cleared it")

    :not-approved
    (str "  The design stands and nothing has cleared it. If no :design-decision\n"
         "  exists yet, run bb nido:review:design first: a round that proceeds on\n"
         "  a design owing nobody a grant clears it. One that declares :challenges\n"
         "  or :revisit owes a person — Approve it in the gate inbox\n"
         "  (http://localhost:8800), which is what records the grant.")

    :unreadable-ledger
    (str "  An entry this depends on will not parse, so standing cannot be\n"
         "  derived — and it fails closed rather than waving the branch through.\n"
         "  Find the unreadable entry under the workstream's entries/ and repair\n"
         "  it; a ledger nobody can read is not a ledger anybody may land on.")

    (str "  No route recorded for " reason " — say so rather than working around it.")))

(defn- landing-design
  "The design a landing of `ws-id` is held to: its governing design, or nil when a person
   deactivated nido's recipe on it — the recipe wrote that design, and stopping the recipe is the
   person's word that its records no longer decide what lands."
  [project ws-id]
  (when-not (some-> (cws/read-ws project ws-id) standing/deactivated?)
    (standing/governing-design project ws-id)))

(defn- standing-check
  "Refuse unless this session's design DECISION stands: not retracted, cleared —
   by a round that owed nobody, or by a person's grant — and the exact baseline
   it cites still live. `:cleared?`, the answer the arc asks, and not
   `:decided?`: a design owing nobody a grant never gets one, so asking for it
   here would refuse every design the round was built to let through.

   A workstream with NO design passes. Most do not have one — scratch
   workstreams, pickups mid-flight — and a gate that demanded a design of every
   branch would stop the work that has not reached one yet, which is not what
   this is for."
  [cwd]
  (if-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
    (if-let [design (landing-design project ws-id)]
      (let [st (standing/of-design project ws-id design)]
        (if (:cleared? st)
          (do (println (str "land:check ok · design at entry " (:seq design)
                            " stands, "
                            (if-let [a (:approved-by st)]
                              (str "approved at entry " a)
                              (str "cleared at entry " (:cleared-by st)))))
              0)
          (let [b (or (:blocked st)
                      {:reason :not-approved :seq (:seq design)})]
            (println (str "land:check REFUSED · " (name (:reason b))))
            (when-let [d (:detail b)] (println (str "  " d)))
            (println "\nHow to clear it:")
            (println (way-out b))
            1)))
      (do (println (if (some-> (cws/read-ws project ws-id) standing/deactivated?)
                     "land:check ok · nido's recipe is deactivated here — no design gates the landing"
                     "land:check ok · this workstream's design record holds nothing to stand on"))
          0))
    (do (println (str "land:check ok · " cwd " is no nido session — nothing to stand on"))
        0)))

(defn- structure-check
  "Refuse unless the CODE still obeys the design the project declared.

   A different question from the one above, and the reason both are here. Standing
   asks whether anyone still believes the premise this branch was written against.

   Both say DESIGN now, and they are still two subjects: the workstream's design
   RECORD, which a human granted, and the project's declared design, which fukan
   checks. This used to say `structure` for the second so the two lines could not
   be confused — a fudge that cost the seam a second vocabulary, since every other
   reading calls the model a design. Naming the record as a record dissolves it,
   and one sentence now serves every reading that has to say a project declares
   none.
   This asks whether the branch left the codebase in the shape the project says it
   has — a `canvas/` model, checked by fukan against the extracted call graph.

   A branch can pass one and fail the other in either direction, so neither
   substitutes for the other, and neither belongs in a recipe: a rule that lives
   only in prose is followed by whoever read the prose."
  [cwd]
  (if-let [[project worktree] (nido-design/coords cwd)]
    (let [result (design/check project worktree)]
      (case (:status result)
        :unmodelled  (do (println (str "land:check ok · " (design/unmodelled-line project)))
                         0)
        :satisfied   (do (println (str "land:check ok · the code obeys " project "'s declared design"))
                         0)
        :violated    (let [{:keys [body] n :count} (design/refusal result)]
                       (println (str "land:check REFUSED · " n " violation"
                                     (when (not= 1 n) "s")
                                     " of " project "'s declared design\n"))
                       (println body)
                       (println "\nHow to clear it:")
                       (println (str "  Landing is where that gets decided rather than inherited.\n"
                                     "  Re-run with bb nido:design:check."))
                       1)
        :undecidable (do (println (str "land:check REFUSED · the design check did not complete: "
                                       (:error result)))
                         (println "\nHow to clear it:")
                         (println (str "  This is not a clean bill of health, it is nobody being able\n"
                                       "  to tell. Fix the checker — bb nido:design:check reproduces\n"
                                       "  it — rather than landing on an answer that was never given."))
                         2)))
    0))

(defn- slice-check
  "Refuse a drain's slice that may not land yet — a slice it rests on has not landed, or its newest
   review under its recipe version did not pass (`cws/slice-unlandable`) — before the push, where
   `nido:land:record`'s append would refuse it only once main held it. Any other workstream passes."
  [cwd]
  (let [[project ws-id] (stages/project+ws-from-cwd cwd)
        why (some->> ws-id (cws/read-ws project) (cws/slice-unlandable project))]
    (if why
      (do (println (str "land:check REFUSED · " (name (:reason why))))
          (println (str "  " (:message why)))
          (println "\nHow to clear it:")
          (println (str "  A slice lands from its own session by `bb nido:drain:land :slice " ws-id "`,\n"
                        "  once every slice it rests on has landed and its newest review passed."))
          1)
      0)))

(defn- judged-claims
  "The claims `design` states, in the shape `design/declared-claims` reads them. Empty for a design
   from before the shared model, which states none."
  [design]
  (into {}
        (map (fn [{:keys [id statement about]}]
               [id {:statement (design/claim-text statement) :about (set about)}]))
        (get-in design [:model :claims])))

(defn- held-to
  "`design` as the landing holds it to the declaration. A merged design writes out its whole
   combination, claims it only carries from a baseline included, so it keeps only the claims its
   parent's design — the one it supersedes — and its child's design state, each read the same way.
   Any other design is held to every claim it states.

   Throws when a design it cites cannot be read: holding a merged design to fewer claims than its
   designs state would let a claim nobody declared land."
  [project ws-id design]
  (if-let [{child-ws :ws-id {n :seq} :design} (:merges design)]
    (let [read   (fn [ledger seq-n what]
                   (or (cws/entry-at-seq project ledger seq-n)
                       (throw (ex-info (str "the " what " at entry " seq-n " on " ledger " could not be read")
                                       {:ws-id ledger :seq seq-n}))))
          stated (fn [ledger d] (get-in (held-to project ledger d) [:model :claims]))
          ids    (into #{} (map :id)
                       (concat (stated ws-id (read ws-id (get-in design [:supersedes :seq])
                                                   "design this merged design supersedes"))
                               (stated child-ws (read child-ws n "child design it merges"))))]
      (update-in design [:model :claims] #(filterv (comp ids :id) %)))
    design))

(defn- base-listing
  "The declared elements as they stood on `main`, or why they could not be read.

   Only the declaration is main's. The source root it is listed against is the worktree's, linked
   in with the rest of what fukan runs from, and nothing here reads what an element pairs with."
  [project worktree]
  (if-let [{:keys [spec-dirs]} (design/design-of project worktree)]
    (if-let [dir (jj/materialize-spec-dirs! worktree "main" spec-dirs)]
      (try
        (design/elements project dir)
        (finally (fs/delete-tree dir)))
      {:status :undecidable :error "main names no revision to read the declared claims at"})
    {:status :unmodelled}))

(defn- claim-differences
  "Every way the claims this branch declares and the claims its design states disagree, one line
   each, or empty.

   A claim this branch declares is one new or changed since `main`. One carried unchanged from an
   earlier landing is another design's and says nothing about this one — but a claim this design
   states is held to the declaration wherever it came from."
  [judged declared base]
  (let [ours (into {} (remove (fn [[id c]] (= c (get base id)))) declared)]
    (vec
     (concat
      (for [[id _] (sort-by key ours) :when (not (contains? judged id))]
        (str id " — declared on this branch, and no design round judged it"))
      (for [[id j] (sort-by key judged)
            :let [d (get declared id)]
            :when (not= d j)]
        (cond
          (nil? d)
          (str id " — the design states it, and the declaration does not")

          (not= (:statement d) (:statement j))
          (str id " — declared with a statement the design does not make")

          :else
          (str id " — declared about " (str/join ", " (sort (:about d)))
               "; the design's is about " (str/join ", " (sort (:about j))))))))))

(defn- unreadable-claims
  [error]
  (println (str "land:check REFUSED · the declared claims could not be read: " error))
  (println "\nHow to clear it:")
  (println (str "  Nobody could tell whether this branch declares its design's claims, which is\n"
                "  not the same as it doing so. bb nido:design:check reproduces a declaration\n"
                "  fukan cannot read."))
  2)

(defn ^{:malli/schema [:=> [:cat :string] :int]}
  claims-check
  "Refuse unless the claims this branch declares are the claims its design states — by id,
   statement and subjects.

   A third question beside the other two. Standing asks whether the design record still holds and
   structure whether the code obeys the declaration; neither asks whether the declaration carries
   what a round judged. A claim declared and never judged is a design nobody decided, and one judged
   and never declared is a design that did not land — both pass the other checks untouched. A merged
   design is asked only for the claims its parent's and child's designs state (`held-to`).

   The base listing is read only when the branch declares a claim, since only then can one be new."
  [cwd]
  (if-let [[project worktree] (nido-design/coords cwd)]
    (let [[p ws-id] (stages/project+ws-from-cwd cwd)
          design    (when ws-id (landing-design p ws-id))
          held      (try {:design (when design (held-to p ws-id design))}
                         (catch clojure.lang.ExceptionInfo e {:error (ex-message e)}))
          judged    (judged-claims (:design held))
          head   (design/elements project worktree)]
      (if-let [error (:error held)]
        (unreadable-claims error)
       (case (:status head)
        :undecidable
        (unreadable-claims (:error head))

        ;; not an empty declaration: a project with no canvas keeps its claims in its records
        ;; alone, and holding them to a declaration it never had would refuse its every landing
        :unmodelled
        (do (println (str "land:check ok · " (design/unmodelled-line project)
                          " — its claims live in its design records alone"))
            0)

        (let [declared (design/declared-claims head)]
          (cond
            (and ws-id (some-> (cws/read-ws p ws-id) standing/deactivated?))
            (do (println "land:check ok · nido's recipe is deactivated here — no design holds its claims") 0)

            (and (empty? declared) (empty? judged))
            (do (println "land:check ok · no claim is declared or judged here") 0)

            :else
            (let [base (if (seq declared) (base-listing project worktree) {:status :unmodelled})]
              (if (= :undecidable (:status base))
                (unreadable-claims (:error base))
                (let [diffs (claim-differences judged declared (design/declared-claims base))]
                  (if (empty? diffs)
                    (do (println (str "land:check ok · the declaration carries the "
                                      (count judged) " claim" (when (not= 1 (count judged)) "s")
                                      " the design states, and no claim it never judged"))
                        0)
                    (do (println (str "land:check REFUSED · the declared claims are not the design's"
                                      (when-let [s (:seq design)] (str " at entry " s))))
                        (doseq [d diffs] (println (str "  " d)))
                        (println "\nHow to clear it:")
                        (println (str "  A claim lives on in canvas/ as a Claim whose name is its id, whose\n"
                                      "  docstring is its statement and whose :about names its subjects. Declare\n"
                                      "  what the design states — or, if the declaration is right, supersede the\n"
                                      "  design so it states that, and have it decided again."))
                        1))))))))))
    0))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  check
  "The landing gate: every question, and a refusal from any is a refusal.

   Every check runs even when an earlier one refuses. An agent that has to
   discover its blockers one push at a time will make one trip per blocker."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        given (or (:cwd opts) (System/getProperty "user.dir"))
        cwd   (or (lifecycle/worktree-from-cwd given) given)]
    (apply max (mapv #(% cwd) [standing-check structure-check claims-check slice-check]))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  cmd
  "bb entry point: exits non-zero on a refusal, so a recipe that runs it before
   the push stops there without anyone having to read the output."
  [& args]
  (let [code (apply check args)]
    (when-not (zero? code) (System/exit code))))

(defn- origin-slug
  "owner/repo of the origin remote, or nil when origin is not a GitHub remote."
  [cwd]
  (let [{:keys [exit out]} (jj/jj! cwd "git" "remote" "list")]
    (when (zero? exit)
      (some->> (str/split-lines (or out ""))
               (some #(when (str/starts-with? % "origin ") (second (str/split % #"\s+"))))
               (re-find #"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$")
               second))))

(defn- unrecorded
  "Print why a landing was not recorded, with its way out, and return the exit code."
  [why way-out]
  (println (str "land:record REFUSED · " why))
  (println "\nHow to clear it:")
  (println (str "  " way-out))
  1)

(defn ^{:malli/schema [:=> [:cat [:* :any]] :int]}
  record
  "Record the landing of the worktree's tip once origin's main holds it: close the workstream, then
   append a :merged naming that commit and the design that stands. Returns an exit code.

   The landing recipe's step after `jj git push -b main`. It records nothing it cannot see landed —
   a tip origin's main does not hold, or a newest design that does not stand — and a re-run
   completes what an earlier run left undone: a closed workstream is not closed again, and the
   append goes through `append-entry-once!` keyed on the commit, so one landing is on the ledger
   once however often this runs.

   A workstream with no design is recorded, and its :merged cites none — `land:check` lets that
   branch land, so refusing to record it would strand a landing already on main.

   The :merged is appended BEFORE the close and only then: best-effort, so a failed append leaves
   no record and the close still happens, and a re-run that finds the workstream already closed
   writes nothing. The close is the landing's record for a phase gate, and appending first is
   what keeps a landing's :merged ahead of the gate that opens the next phase."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        given    (or (:cwd opts) (System/getProperty "user.dir"))
        cwd      (or (lifecycle/worktree-from-cwd given) given)
        [project ws-id] (stages/project+ws-from-cwd cwd)]
    (if-not ws-id
      (unrecorded (str cwd " belongs to no nido session, so there is no workstream to record a landing on")
                  "run it from the worktree of the session whose work landed")
      (let [fetched (jj/jj! cwd "git" "fetch")
            ;; The worktree's own tip: the nearest change at or below @ that carries content, since
            ;; a push leaves an empty working copy on top of what it pushed.
            tip    (layers/resolve-rev cwd "heads(::@ ~ empty())")
            landed (when tip (layers/resolve-rev cwd (str tip " & ::main@origin")))
            design (landing-design project ws-id)
            st     (when design (standing/of-design project ws-id design))]
        (cond
          ;; A stale main@origin would read as a tip nobody pushed, and send the way out to a push
          ;; that already happened.
          (not (zero? (:exit fetched)))
          (unrecorded (str "origin could not be fetched, so whether main holds the tip is unknown: "
                           (str/trim (str (or (:err fetched) (:out fetched)))))
                      "check the origin remote and the network, then run this again")

          (nil? tip)
          (unrecorded "the worktree's tip could not be read" "check `jj log` in the worktree, then run this again")

          (nil? landed)
          (unrecorded (str "origin's main does not hold the worktree's tip " (subs tip 0 (min 12 (count tip))))
                      "push it — jj git push -b main — then run this again")

          (and design (not (:cleared? st)))
          (unrecorded (str "the workstream's newest design" (when design (str " at entry " (:seq design)))
                           " does not stand")
                      "bb nido:land:check says why; repair that, then run this again")

          :else
          (let [title  (str/trim (or (:out (jj/jj! cwd "log" "-r" tip "--no-graph" "-T" "description.first_line()")) ""))
                slug   (origin-slug cwd)
                landing {:format :merged
                         :commit tip
                         :url    (if slug (str "https://github.com/" slug "/commit/" tip) tip)
                         :title  title}]
            (try
              (let [w (cws/read-ws project ws-id)]
                (if (cws/closure w)
                  (do (println (str "land:record ok · " ws-id " was already closed; a landing is recorded"
                                    " only before its close, so nothing was written"))
                      0)
                  (let [result  (try (cws/append-entry-once! project ws-id {:kind :merged}
                                                             (pr-str (cond-> landing design (assoc :design {:seq (:seq design)})))
                                                             #(= tip (:commit %)))
                                     (catch Exception e
                                       (println (str "land:record · the :merged could not be appended ("
                                                     (ex-message e) "); closing anyway"))
                                       nil))
                        outcome (phase/landing-outcome design (:entries (cws/read-ws project ws-id)))]
                    (cws/close! project ws-id outcome (when (= :between-phases outcome) (:seq design)))
                    (println (str "land:record ok · " ws-id " is closed "
                                  (name outcome) ", and its landing at "
                                  (subs tip 0 (min 12 (count tip))) " is "
                                  (cond (:appended result) "recorded"
                                        (:existing result) "already on the ledger"
                                        :else              "not recorded")))
                    0)))
              (catch Exception e
                (unrecorded (str "a write failed: " (ex-message e))
                            "repair what it names, then run this again — a re-run completes what this one left undone")))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  record-cmd
  "bb entry point: exits non-zero when the landing is not recorded, so the recipe's step stops there."
  [& args]
  (let [code (apply record args)]
    (when-not (zero? code) (System/exit code))))

(defn- short-id [rev] (subs rev 0 (min 12 (count rev))))

(defn- step [& parts] (println (apply str "land · " parts)) (flush))

(defn- conflicted-paths
  "The paths conflicted anywhere in the arc between origin's main and @, or nil when none is."
  [cwd]
  (let [{:keys [exit out]} (jj/jj! cwd "log" "--no-graph" "-r" "(main@origin..@) & conflicts()"
                                   "-T" "change_id.short() ++ \"\\n\"")]
    (when (and (zero? exit) (not (str/blank? out)))
      (let [{:keys [out]} (jj/jj! cwd "resolve" "--list" "-r" (first (str/split-lines out)))]
        (or (not-empty (str/trim (str out))) "the arc")))))

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  gate!
  "Land the worktree's arc on origin's main, or refuse: fetch, rebase the arc onto origin's main,
   run bb nido:test and the landing check at the tip that results, and push main to that tip.
   Returns {:landed <commit id>} or {:refused <why> :way-out <what to do>}. Prints a `land · `
   line as each step starts, for whoever is watching the output.

   Refuses before pushing on anything the gate cannot show: nothing unlanded, a tip with no
   description (it would land as an unnamed commit), a rebase that conflicts — which is undone, so
   the worktree is left as it was — a red test run or a refused check. A push origin rejects
   (main moved during the run) is a refusal too; running again rebases onto the new main.

   The rebase is the gate's own because a tip tested anywhere but on origin's main is not the
   commit that lands. It records nothing: what a landing is recorded on is the caller's."
  [cwd]
  (step "fetching origin")
  (let [fetched (jj/jj! cwd "git" "fetch")
        tip     (fn [] (layers/resolve-rev cwd "heads(::@ ~ empty())"))]
    (cond
      (not (zero? (:exit fetched)))
      {:refused (str "origin could not be fetched: " (:err fetched)) :way-out "check the network, then run it again"}

      (nil? (tip))
      {:refused "the worktree's tip could not be read" :way-out "check `jj log` in the worktree"}

      (layers/resolve-rev cwd (str (tip) " & ::main@origin"))
      {:refused "origin's main already holds the worktree's tip — there is nothing to land"
       :way-out "make the change first"}

      (str/blank? (:out (jj/jj! cwd "log" "-r" (tip) "--no-graph" "-T" "description")))
      {:refused "the change has no description, so it would land unnamed"
       :way-out "describe it — jj describe -r <change> -m '<what it does>' — then run it again"}

      :else
      (let [before (:out (jj/jj! cwd "op" "log" "-n" "1" "--no-graph" "-T" "id"))]
        (step "rebasing onto origin's main")
        (jj/jj! cwd "rebase" "-b" "@" "-d" "main@origin")
        (if-let [paths (conflicted-paths cwd)]
          (do (jj/jj! cwd "op" "restore" before)
              {:refused (str "rebasing onto origin's main conflicts: " paths)
               :way-out "jj rebase -b @ -d main@origin, resolve the conflicts, then run it again"})
          (let [landing (tip)]
            (step "running bb nido:test at " (short-id landing))
            (cond
              (not (zero? (:exit (p/shell {:dir cwd :continue true} "bb" "nido:test"))))
              {:refused "bb nido:test is red at the tip" :way-out "fix what it reports; a red landing is a red main for everyone"}

              (do (step "running bb nido:land:check") (not (zero? (check ":cwd" cwd))))
              {:refused "bb nido:land:check refused the tip" :way-out "clear what it names above, then run it again"}

              :else
              (let [_ (step "pushing main to " (short-id landing))
                    {:keys [outcome detail]} (lifecycle/advance-remote! cwd "main" landing)]
                (if (#{:advanced :already-there} outcome)
                  {:landed landing}
                  {:refused (str "origin did not take the push (" (name outcome) ")" (when detail (str ": " detail)))
                   :way-out "main probably moved: run it again, which rebases onto the new main"})))))))))

(defn- daemon-checkout
  "The checkout the launchd daemon loads its code from — the plist's WorkingDirectory — or nil when
   the daemon is not installed under launchd."
  []
  (when (lc/installed?)
    (some->> (slurp (lc/plist-path))
             (re-find #"<key>WorkingDirectory</key>\s*<string>([^<]+)</string>")
             second)))

(defn- live-run-ids
  "Every run whose agent is alive right now. A daemon restart kills each one: its agent is the
   daemon's child."
  []
  (filterv runs/live? (runs/list-run-ids)))

(defn- daemon-ancestor?
  "Whether this process descends from the running daemon — a land the dashboard's Publish or a
   Run started. Restarting from there kills the process doing the restart, mid-landing."
  []
  (when-let [daemon (pid/read)]
    (let [me      (parse-long (str/trim (str (:out (p/sh ["sh" "-c" "echo $PPID"])))))
          parents (into {} (keep (fn [l] (let [[a b] (str/split (str/trim l) #"\s+")]
                                           (when b [(parse-long a) (parse-long b)]))))
                        (str/split-lines (str (:out (p/sh ["ps" "-A" "-o" "pid=,ppid="])))))]
      (boolean (some #{daemon} (take 64 (take-while #(and % (pos? %)) (iterate parents me))))))))

(def ^:private restart-by-hand
  "jj rebase -b @ -d main@origin in ~/Code/nido, then bb nido:coordinator:restart")

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  restart-daemon!
  "Put the coordinator on the code that just landed at `landed`: rebase the checkout the daemon
   loads onto origin's main, then restart the daemon. Returns {:restarted true}, {:skipped <why>}
   or {:failed <why>}, each with the way out when there is one.

   The daemon reads `src/` once at startup, so a landing it is not restarted onto changes nothing
   it does — the fix sits on main while the daemon keeps running the old code. The rebase comes
   FIRST because the daemon loads the root checkout, not the worktree that landed: restarting
   before it restarts onto the pre-landing code.

   Skips rather than kills. A restart SIGTERMs every live run and every process the daemon started,
   so a landing the daemon itself runs (a Run's, the dashboard's Publish) or any live run defers
   the restart to a person. A rebase that
   conflicts, or leaves the checkout without the landing, is undone and restarts nothing — a daemon
   started on a conflicted tree loads conflict markers."
  [landed]
  (let [checkout (daemon-checkout)
        live     (when checkout (live-run-ids))]
    (cond
      (nil? checkout)
      {:skipped "the coordinator is not installed under launchd"
       :way-out "restart it yourself if it is running: bb nido:coordinator:down, then bb nido:coordinator:up"}

      (daemon-ancestor?)
      {:skipped "this landing runs inside the daemon, which a restart would kill along with it"
       :way-out (str "from a terminal: " restart-by-hand)}

      (seq live)
      {:skipped (str (count live) " run(s) are live and a restart would kill them: "
                     (str/join ", " (take 5 live)) (when (> (count live) 5) ", …"))
       :way-out (str "once they finish: " restart-by-hand)}

      :else
      (let [before (str/trim (str (:out (jj/jj! checkout "op" "log" "-n" "1" "--no-graph" "-T" "id"))))
            {:keys [exit err]} (jj/jj! checkout "rebase" "-b" "@" "-d" "main@origin")
            conflicted (:out (jj/jj! checkout "log" "--no-graph" "-r" "(main@origin..@) & conflicts()"
                                     "-T" "change_id.short()"))
            holds?     (layers/resolve-rev checkout (str landed " & ::@"))]
        (if (or (not (zero? exit)) (not (str/blank? conflicted)) (nil? holds?))
          (do (jj/jj! checkout "op" "restore" before)
              {:failed (str "rebasing " checkout " onto origin's main "
                            (cond (not (zero? exit)) (str "failed: " (str/trim (str err)))
                                  (not (str/blank? conflicted)) "conflicts"
                                  :else "left it without the landing")
                            " — undone, and the daemon was not restarted")
               :way-out (str "resolve it by hand: " restart-by-hand)})
          (let [{:keys [exit err]} (if (lc/loaded?) (lc/kickstart!) (lc/bootstrap!))]
            (if (zero? exit)
              {:restarted true}
              {:failed (str "launchctl could not restart the daemon: " (str/trim (str err)))
               :way-out "bb nido:coordinator:restart"})))))))

(defn- report-restart!
  "Print the restart's outcome and return the exit code it adds: only a failure is one. A skip is
   deliberate, and the landing itself stands either way."
  [{:keys [restarted skipped failed way-out]}]
  (cond
    restarted (do (println "land · coordinator restarted onto the landing") 0)
    skipped   (do (println (str "land · coordinator NOT restarted · " skipped
                                "\n  it keeps running the code from before the landing — " way-out))
                  0)
    :else     (do (println (str "land · coordinator restart FAILED · " failed "\n  " way-out)) 1)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :int]}
  land
  "bb nido:land — land the worktree's arc on nido's main through the gate (`gate!`), record the
   landing on the worktree's workstream (`record`), then restart the coordinator onto it
   (`restart-daemon!`; `:restart false` leaves it alone). A worktree no workstream owns lands
   unrecorded, and says so. Returns an exit code: non-zero, naming the way out, whenever nothing was
   pushed, a pushed landing could not be recorded, or the restart failed. The restart follows any
   push, recorded or not."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        given (or (some-> (:cwd opts) str) (System/getProperty "user.dir"))
        cwd   (or (lifecycle/worktree-from-cwd given) given)
        restart? (not (contains? #{false "false"} (:restart opts)))
        {:keys [landed refused way-out]} (gate! cwd)
        restart (fn [code]
                  ;; Even when the record failed: main holds the landing, so the daemon should too.
                  (if restart?
                    (max code (report-restart! (restart-daemon! landed)))
                    code))]
    (cond
      refused
      (do (println (str "land REFUSED · " refused))
          (println (str "\nHow to clear it:\n  " way-out))
          1)

      (nil? (second (stages/project+ws-from-cwd cwd)))
      (do (println (str "land ok · " (short-id landed) " is on origin's main · no workstream owns "
                        cwd ", so no landing is recorded"))
          (restart 0))

      :else
      (let [code (record ":cwd" cwd)]
        (when (zero? code) (println (str "land ok · " (short-id landed) " is on origin's main")))
        (restart code)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  land-cmd
  "bb entry point for `land`."
  [& args]
  (let [code (apply land args)]
    (when-not (zero? code) (System/exit code))))
