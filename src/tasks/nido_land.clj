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
   [nido.coordinator.daemon.supervisor :as sup]
   [nido.coordinator.daemon.restart :as restart]
   [nido.review.layers :as layers]
   [nido.review.stages :as stages]
   [nido.session.change :as change]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.task-args :as task-args]
   [nido.platform.jj :as jj]))

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
         "  3. bb nido:review:design — until it proceeds, which clears it")

    :premise-retracted
    (str "  The baseline is not merely stale, somebody found it FALSE — read entry "
         seq "\n  for the counterexample before you touch anything.\n"
         "  1. baseline the area again and append a new :baseline (/design §4)\n"
         (when replaced-by
           (str "     (entry " replaced-by " corrects it — start from there)\n"))
         "  2. bb nido:review:baseline — until it answers `sufficient`\n"
         "  3. supersede the design so it cites the corrected baseline\n"
         "  4. bb nido:review:design — until it proceeds, which clears it")

    :design-invalidated
    (str "  The review round read the code against this design and found the\n"
         "  DESIGN wrong, not its execution — entry " seq " says which invariants\n"
         "  it broke and what it needs. The work goes back to the design:\n"
         "  1. write a superseding :design that answers the verdict — raise a\n"
         "     blocker instead when only a person can say which way it goes\n"
         "  2. bb nido:review:design — until it proceeds, which clears it")

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
         "  3. bb nido:review:design — until it proceeds, which clears it")

    :premise-goal-superseded
    (str "  The baseline this design stands on (entry " seq ") was scoped for a\n"
         "  goal that has since been replaced — entry " replaced-by " is the live one.\n"
         "  The survey is owed again before the design is:\n"
         "  1. baseline the area again under the live goal: a new :baseline with\n"
         "     :intent {:seq " replaced-by "} and :supersedes {:seq " seq " :why \"…\"}\n"
         "  2. bb nido:review:baseline — until it answers `sufficient`\n"
         "  3. supersede the design so it cites both, then bb nido:review:design,\n"
         "     until it proceeds")

    :goal-superseded
    (str "  The design serves the goal at entry " seq ", replaced at entry "
         replaced-by " —\n  it serves a goal nobody holds.\n"
         "  1. if the baseline under it was scoped for the same goal, baseline the\n"
         "     area again first — citing :intent {:seq " replaced-by "} and\n"
         "     superseding it — and bb nido:review:baseline until `sufficient`\n"
         "  2. supersede the design so it cites :intent {:seq " replaced-by "}\n"
         "  3. bb nido:review:design — until it proceeds, which clears it")

    :goal-retracted
    (str "  The goal this work serves was found untrue — read entry " seq " for\n"
         "  why before you touch anything, then restate it.\n"
         "  1. append an :intent with :supersedes naming the retracted one\n"
         "  2. baseline the area again under it, citing the new intent, and\n"
         "     bb nido:review:baseline until `sufficient`\n"
         "  3. supersede the design so it cites both, then bb nido:review:design\n"
         "     until it proceeds")

    :design-retracted
    (str "  Somebody found this design untrue — read entry " seq " first.\n"
         "  Write a superseding :design and run bb nido:review:design until it\n"
         "  proceeds. Do not amend the retracted one:\n"
         "  it stays in the ledger.")

    :no-premise
    (str "  This design cites no baseline — it predates the baseline event.\n"
         "  1. baseline the area and append a :baseline (/design §4)\n"
         "  2. bb nido:review:baseline\n"
         "  3. supersede the design so it cites it, then bb nido:review:design\n"
         "     until it proceeds")

    :not-cleared
    (str "  The design stands and nothing has cleared it. Run bb nido:review:design:\n"
         "  a round that proceeds clears it. A round that stops for a person parks a\n"
         "  blocker; answer it, and run the round again.")

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
   by its round, or by a grant an older ledger holds — and the exact baseline it
   cites still live. `:cleared?`, the answer the arc asks, and not `:decided?`,
   which only a grant answers.

   A workstream with NO design passes. Most do not have one — scratch
   workstreams, pickups mid-flight — and a gate that demanded a design of every
   branch would stop the work that has not reached one yet, which is not what
   this is for."
  [_files cwd]
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
                      {:reason :not-cleared :seq (:seq design)})]
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

(defn- coords
  "[project tree] for a check reading its files from `files` on behalf of `cwd`: the project is
   `cwd`'s, and the tree is `files` — which is `cwd` itself unless a landing runs the check over
   the tree it is about to push, a workspace no session or project directory owns."
  [files cwd]
  (when-let [[project worktree] (nido-design/coords cwd)]
    [project (if (= files cwd) worktree files)]))

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
  [files cwd]
  (if-let [[project worktree] (coords files cwd)]
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
  [_files cwd]
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
  [files cwd]
  (if-let [[project worktree] (coords files cwd)]
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
   discover its blockers one push at a time will make one trip per blocker.

   The files checked are `:cwd`'s; the workstream they are held to is `:ws-cwd`'s, which is
   `:cwd` unless a landing names the worktree it lands for while checking the tree it pushes."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        given (or (some-> (:cwd opts) str) (System/getProperty "user.dir"))
        files (or (lifecycle/worktree-from-cwd given) given)
        ws    (if-let [w (some-> (:ws-cwd opts) str)] (or (lifecycle/worktree-from-cwd w) w) files)]
    (apply max (mapv #(% files ws) [standing-check structure-check claims-check slice-check]))))

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
  "Record the landing of `:commit`, else the worktree's tip, once origin's main holds it: close the
   workstream, then append a :merged naming that commit and the design that stands. Returns an exit
   code. `land` names the commit its gate pushed, which is a copy of the worktree's revision rather
   than the revision itself.

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
            tip    (or (some-> (:commit opts) str)
                       (change/one-commit cwd (change/tip-of "@")))
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

(defn- jj-out
  "The trimmed output of a jj command in `dir`, or nil when it exits non-zero."
  [dir & args]
  (let [{:keys [exit out]} (apply jj/jj! dir args)]
    (when (zero? exit) (str/trim (str out)))))

(defn- lines [s] (vec (remove str/blank? (str/split-lines (or s "")))))

(defn- conflicted-paths
  "The paths conflicted in any commit of `revset`, or nil when none is — never nil when jj cannot
   say."
  [dir revset]
  (let [out (jj-out dir "log" "--no-graph" "-r" (str "(" revset ") & conflicts()")
                   "-T" "change_id.short() ++ \"\\n\"")]
    (if (nil? out)
      "the arc, whose conflicts jj could not read"
      (when-let [c (first (lines out))]
        (or (not-empty (jj-out dir "resolve" "--list" "-r" c)) "the arc")))))

(defn- run-tests
  "bb nido:test as a process in `dir`; its exit code."
  [dir]
  (:exit (p/shell {:dir (str dir) :continue true} "bb" "nido:test")))

(defn- run-check
  "bb nido:land:check as a process in `dir`, over `dir`'s files, held to `ws-cwd`'s workstream; its
   exit code."
  [dir ws-cwd]
  (:exit (p/shell {:dir (str dir) :continue true} "bb" "nido:land:check" ":cwd" (str dir) ":ws-cwd" ws-cwd)))

(defn- landing-workspace!
  "A jj workspace of `cwd`'s repository at origin's main, in a directory of its own: {:name :dir},
   or nil when jj would not make one."
  [cwd]
  (let [dir  (str (fs/path (fs/create-temp-dir {:prefix "nido-land-"}) "tree"))
        name (str "nido-land-" (subs (str (random-uuid)) 0 8))
        {:keys [exit]} (jj/jj! cwd "workspace" "add" "--name" name "-r" "main@origin" dir)]
    (when (zero? exit) {:name name :dir dir})))

(defn- forget-workspace!
  "Forget the landing's workspace — jj drops its empty working-copy commit — and delete its
   directory. Best-effort: a landing's outcome is already decided when this runs."
  [cwd {:keys [name dir]}]
  (try (jj/jj! cwd "workspace" "forget" name) (catch Throwable _ nil))
  (try (fs/delete-tree (fs/parent dir)) (catch Throwable _ nil)))

(defn- duplicate!
  "Copy the arc origin's main..`rev` onto origin's main, running jj in `dir`; the change ids of the
   copies, or nil when jj refused or the copies it names are not one for each commit of the arc."
  [dir rev]
  (let [arc (some-> (jj-out dir "log" "--no-graph" "-r" (str "main@origin.." rev) "-T" "change_id ++ \"\\n\"")
                    lines count)
        {:keys [exit out err]} (when arc (jj/jj! dir "duplicate" (str "main@origin.." rev) "-d" "main@origin"))
        copies (when (= 0 exit)
                 (vec (keep #(second (re-find #"^Duplicated \S+ as (\S+) " %))
                            (str/split-lines (str out "\n" err)))))]
    (when (and copies (= arc (count copies))) copies)))

(defn- rejoin!
  "Rebase `cwd`'s arc onto origin's main, dropping each commit main's content empties. Returns the
   paths left conflicted, what kept the rebase from running, or nil."
  [cwd]
  (let [{:keys [exit err]} (jj/jj! cwd "rebase" "-b" "@" "-d" "main@origin" "--skip-emptied")]
    (if (zero? exit)
      (conflicted-paths cwd "main@origin..@")
      (str "jj refused the rebase, so the worktree is where it was: " (str/trim (str err))))))

(defn- land-copies!
  "Test, check and push the copies `copies` of a landing's arc from workspace `ws`, abandoning them
   on any refusal. {:landed <commit>} or {:refused :way-out}."
  [cwd {:keys [dir]} copies]
  (let [;; present(): a copy the gate abandoned as empty no longer resolves, and a refusal after
        ;; that abandons what is left.
        set*      (str/join "|" (map #(str "present(" % ")") copies))
        copy-ids  (fn [revset] (some-> (jj-out dir "log" "--no-graph" "-r" revset "-T" "change_id ++ \"\\n\"") lines))
        kept      (copy-ids (str "(" set* ") ~ empty()"))
        ;; A merge copy with nothing beyond its parents' merge is empty by construction, not
        ;; emptied by main: it stays, or its parents would be left as two heads.
        emptied   (copy-ids (str "(" set* ") & (" change/hollow ")"))
        abandon!  (fn [] (jj/jj! dir "abandon" set*))
        refuse    (fn [r] (abandon!) r)
        ;; Over every copy: a merge whose parents conflict is empty and still carries the conflict.
        conflicts (when (seq kept) (conflicted-paths dir set*))
        unreadable {:refused "jj could not read the copies of the change"
                    :way-out "check `jj log` in the worktree, then run it again"}]
    (cond
      (or (nil? kept) (nil? emptied))
      (refuse unreadable)

      (empty? kept)
      (refuse {:refused "origin's main already holds everything the revision changes — there is nothing to land"
               :way-out "make the change first"})

      conflicts
      (refuse {:refused (str "the change conflicts with origin's main: " conflicts)
               :way-out "jj rebase -b @ -d main@origin in the worktree, resolve the conflicts, then run it again"})

      (and (seq emptied) (not (zero? (:exit (jj/jj! dir "abandon" (str/join "|" emptied))))))
      (refuse unreadable)

      :else
      (let [tips    (copy-ids (str "heads(" set* ")"))
            tip     (change/one-commit dir (str "heads(" set* ")"))
            l       (when (and tip (zero? (:exit (jj/jj! dir "new" tip))))
                      (jj-out dir "log" "--no-graph" "-r" "@-" "-T" "commit_id"))]
        (if (or (nil? l) (not= l tip))
          (refuse {:refused (str "the landing's tree could not be built from the copies"
                                 (when (not= 1 (count tips)) (str ": they have " (count tips) " heads, not one")))
                   :way-out "check `jj log` in the worktree, then run it again"})
          (do
            (step "running bb nido:test at " (short-id l))
            (cond
              (not (zero? (run-tests dir)))
              (refuse {:refused "bb nido:test is red at the tip" :way-out "fix what it reports; a red landing is a red main for everyone"})

              (do (step "running bb nido:land:check") (not (zero? (run-check dir cwd))))
              (refuse {:refused "bb nido:land:check refused the tip" :way-out "clear what it names above, then run it again"})

              :else
              (let [_ (step "pushing main to " (short-id l))
                    {:keys [outcome detail]} (lifecycle/advance-remote! dir "main" l)]
                (if (#{:advanced :already-there} outcome)
                  (do (println (str "land pushed · " l)) (flush)
                      {:landed l})
                  (refuse {:refused (str "origin did not take the push (" (name outcome) ")" (when detail (str ": " detail)))
                           :way-out "main probably moved: run it again, which lands onto the new main"}))))))))))

(declare gate*)

(defn ^{:malli/schema [:=> [:cat :string [:? :string]] :map]}
  gate!
  "Land one revision on origin's main, or refuse: `rev` when the caller names it, else the
   worktree's tip, read once. Fetch, copy the arc origin's main..rev onto origin's main in a jj
   workspace of the landing's own, drop each copy main's content empties, run bb nido:test and
   bb nido:land:check as processes in that workspace, push main to the copy of the tip, print
   `land pushed · <commit>`, and forget the workspace. Returns {:landed <commit id>} or
   {:refused <why> :way-out <what to do>}, printing a `land · ` line as each step starts.

   Refuses before pushing on anything it cannot show: nothing unlanded, a tip with no description,
   a copy that conflicts with main, copies jj cannot read or build into one tip, a red test run, a
   refused check, or a push origin rejects. A refusal abandons the copies; the revision and every
   commit a workspace stands on are left as they were, so an edit made in the worktree while this
   runs is never tested and never pushed.

   Only a landing whose caller named no revision moves the worktree afterwards: once origin's main
   holds the landing it rebases the worktree's arc onto it, dropping what main's content empties,
   so a later landing from it does not carry this one again. It records nothing: what a landing is
   recorded on is the caller's."
  ([cwd] (gate* cwd nil))
  ([cwd rev] (gate* cwd rev)))

(defn- gate*
  [cwd rev]
   ;; The target is read before the first `land ·` line, so nothing written after it reaches the arc.
   (let [named?  (some? rev)
         target  (change/one-commit cwd (or rev (change/tip-of "@")))
         _       (step "fetching origin")
         fetched (jj/jj! cwd "git" "fetch")]
     (cond
       (not (zero? (:exit fetched)))
       {:refused (str "origin could not be fetched: " (:err fetched)) :way-out "check the network, then run it again"}

       (nil? target)
       {:refused (str "the revision to land could not be read" (when named? (str ": " rev)))
        :way-out "check `jj log` in the worktree"}

       (layers/resolve-rev cwd (str target " & ::main@origin"))
       {:refused "origin's main already holds the worktree's tip — there is nothing to land"
        :way-out "make the change first"}

       (str/blank? (jj-out cwd "log" "-r" target "--no-graph" "-T" "description"))
       {:refused "the change has no description, so it would land unnamed"
        :way-out "describe it — jj describe -r <change> -m '<what it does>' — then run it again"}

       :else
       (if-let [ws (landing-workspace! cwd)]
         (try
           (step "copying " (short-id target) " onto origin's main")
           (let [copies (duplicate! (:dir ws) target)
                 result (if (seq copies)
                          (land-copies! cwd ws copies)
                          {:refused "the change could not be copied onto origin's main"
                           :way-out "check `jj log` in the worktree, then run it again"})]
             (if (and (:landed result) (not named?))
               (do (step "bringing the worktree onto origin's main")
                   (if-let [paths (rejoin! cwd)]
                     (do (println (str "land · the worktree is not cleanly on main: " paths))
                         (assoc result :conflicted paths))
                     result))
               result))
           (finally (forget-workspace! cwd ws)))
         {:refused "a workspace for the landing could not be made"
          :way-out "check `jj workspace list` in the worktree, then run it again"}))))

(defn- daemon-checkout
  "The checkout the supervised daemon loads its code from, or nil when its service is not
   installed."
  []
  (sup/checkout))

(def ^:private restart-by-hand
  "jj rebase -b @ -d main@origin in ~/Code/nido, then bb nido:coordinator:restart once no run is live")

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  restart-daemon!
  "Put the coordinator on the code that landed at `landed`: rebase the checkout the supervised daemon
   loads onto origin's main now, then record the landing as owed for the daemon to pay on its own
   tick. Returns {:owed <commit> :waits [<why>]}, {:skipped <why>} or {:failed <why>}, each with the
   way out when there is one.

   Never restarts, starts or signals the daemon: a landing is often the one process that cannot —
   it runs inside the daemon, or runs are live — and the daemon is the one that can see when a
   restart cuts nothing. The rebase cannot wait for that: it kills nothing, and the daemon loads
   whatever the checkout holds when it next starts. A rebase that fails, conflicts or leaves the
   checkout without the landing is undone and owes nothing — a daemon started on a conflicted tree
   loads conflict markers. Undone by reverting the rebase's own operation, never by restoring the
   repository to before it: a restore would also revert whatever another workspace of the shared
   store recorded meanwhile. A debt that cannot be written leaves the rebase in place."
  [landed]
  (if-let [checkout (daemon-checkout)]
    (let [;; The rebase's own operation is found by a mark it carries in its args, never as the op
          ;; log's head: another workspace of the shared store may record an operation after it.
          mark   (str "nido.landing-rebase=" (random-uuid))
          {:keys [exit err]} (jj/jj! checkout "rebase" "-b" "@" "-d" "main@origin" "--config" mark)
          rebase (not-empty (str/trim (str (:out (jj/jj! checkout "op" "log" "--ignore-working-copy" "--no-graph" "-T"
                                                         (str "if(attributes.contains(\"" mark "\") && description.starts_with(\"rebase\"), id ++ \"\\n\")"))))))
          conflicted (:out (jj/jj! checkout "log" "--no-graph" "-r" "(main@origin..@) & conflicts()"
                                   "-T" "change_id.short()"))
          holds?     (layers/resolve-rev checkout (str landed " & ::@"))]
      (if (or (not (zero? exit)) (not (str/blank? conflicted)) (nil? holds?))
        (do (when rebase (jj/jj! checkout "op" "revert" rebase))
            {:failed (str "rebasing " checkout " onto origin's main "
                          (cond (not (zero? exit)) (str "failed: " (str/trim (str err)))
                                (not (str/blank? conflicted)) "conflicts"
                                :else "left it without the landing")
                          " — undone, so no restart is owed")
             :way-out (str "resolve it by hand: " restart-by-hand)})
        (let [{:keys [landing failed]} (restart/owe! landed (.pid (java.lang.ProcessHandle/current)))]
          (if failed
            {:failed (str failed " — the checkout holds the landing, but nothing will restart onto it")
             :way-out "bb nido:coordinator:restart once no run is live"}
            {:owed landed :waits (restart/waits landing)}))))
    {:skipped "the coordinator is not installed under a supervisor, so no restart is owed"
     :way-out "restart it yourself if it is running: bb nido:coordinator:down, then bb nido:coordinator:up"}))

(defn- report-restart!
  "Print the landing's one `land · coordinator …` line and return the exit code it adds: only a
   failure is one. The landing itself stands either way."
  [{:keys [owed waits skipped failed way-out]}]
  (cond
    owed    (do (println (str "land · coordinator restart owed onto " (short-id owed)
                              " · waits for " (str/join "; " waits)))
                0)
    skipped (do (println (str "land · coordinator no restart owed · " skipped "\n  " way-out)) 0)
    :else   (do (println (str "land · coordinator no restart owed · FAILED · " failed "\n  " way-out)) 1)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :int]}
  land
  "bb nido:land — land a revision on nido's main through the gate (`gate!`): `:rev` when given,
   else the worktree's tip read once. Record the commit the gate landed on the worktree's
   workstream (`record`), then owe the coordinator a restart onto it
   (`restart-daemon!`). No option skips that step: a landing the daemon is not put onto changes
   nothing it does. A worktree no workstream owns lands unrecorded, and says so. Returns an exit
   code: non-zero, naming the way out, whenever nothing was pushed, a pushed landing could not be
   recorded, or the restart could not be owed. The step follows any push, recorded or not."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        given (or (some-> (:cwd opts) str) (System/getProperty "user.dir"))
        cwd   (or (lifecycle/worktree-from-cwd given) given)
        {:keys [landed refused way-out]} (if-let [r (some-> (:rev opts) str)] (gate! cwd r) (gate! cwd))
        restart (fn [code]
                  ;; Even when the record failed: main holds the landing, so the daemon owes it too.
                  (max code (report-restart! (restart-daemon! landed))))]
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
      (let [code (record ":cwd" cwd ":commit" landed)]
        (when (zero? code) (println (str "land ok · " (short-id landed) " is on origin's main")))
        (restart code)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  land-cmd
  "bb entry point for `land`."
  [& args]
  (let [code (apply land args)]
    (when-not (zero? code) (System/exit code))))
