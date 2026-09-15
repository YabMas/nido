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
   [clojure.string :as str]
   [nido.coordinator.record.standing :as standing]
   [nido.design.check :as design]
   [tasks.nido-design :as nido-design]
   [nido.coordinator.record.workstream :as cws]
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
    (if-let [design (cws/latest-entry project ws-id :design)]
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
      (do (println "land:check ok · this workstream's design record holds nothing to stand on")
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

(defn- squashed
  "`s` with every run of whitespace one space. A docstring wraps where a record's statement does
   not, and where a line breaks says nothing about what the claim is."
  [s]
  (str/trim (str/replace (str s) #"\s+" " ")))

(defn- claim-rows
  [listing]
  (filter #(= "Claim" (name (:sort %))) (:elements listing)))

(defn- declared-claims
  "The Claim instances `listing` holds, by id — a Claim's instance name — each as its statement
   and the set of subjects its `:about` names. Keying by name drops nothing only because the
   declaration's own law holds a Claim's name unique; a declaration breaking it is refused by the
   structure check, which a landing always asks beside this one."
  [listing]
  (into {}
        (map (fn [{nm :name doc :doc refs :refs}]
               [nm {:statement (squashed doc) :about (set (:about refs))}]))
        (claim-rows listing)))

(defn- judged-claims
  "The claims `design` states, in the shape `declared-claims` reads them. Empty for a design from
   before the shared model, which states none."
  [design]
  (into {}
        (map (fn [{:keys [id statement about]}]
               [id {:statement (squashed statement) :about (set about)}]))
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
    (if-let [dir (nido-design/materialize-spec-dirs! worktree "main" spec-dirs)]
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
          design    (when ws-id (cws/latest-entry p ws-id :design))
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

        (let [declared (declared-claims head)]
          (cond
            (and (empty? declared) (empty? judged))
            (do (println "land:check ok · no claim is declared or judged here") 0)

            :else
            (let [base (if (seq declared) (base-listing project worktree) {:status :unmodelled})]
              (if (= :undecidable (:status base))
                (unreadable-claims (:error base))
                (let [diffs (claim-differences judged declared (declared-claims base))]
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
    (apply max (mapv #(% cwd) [standing-check structure-check claims-check]))))

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
   once however often this runs."
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
            design (cws/latest-entry project ws-id :design)
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

          (not (:cleared? st))
          (unrecorded (str "the workstream's newest design" (when design (str " at entry " (:seq design)))
                           " does not stand")
                      "bb nido:land:check says why; repair that, then run this again")

          :else
          (let [title  (str/trim (or (:out (jj/jj! cwd "log" "-r" tip "--no-graph" "-T" "description.first_line()")) ""))
                slug   (origin-slug cwd)
                landing {:format :merged
                         :commit tip
                         :url    (if slug (str "https://github.com/" slug "/commit/" tip) tip)
                         :title  title
                         :design {:seq (:seq design)}}]
            (try
              (when-not (:closed (cws/read-ws project ws-id))
                (cws/close! project ws-id :done))
              (let [result (cws/append-entry-once! project ws-id {:kind :merged} (pr-str landing)
                                                   #(= tip (:commit %)))]
                (println (str "land:record ok · " ws-id " is closed, and its landing at "
                              (subs tip 0 (min 12 (count tip))) " is "
                              (cond (:appended result) "recorded"
                                    (:indexed result)  "recorded, completing an interrupted run"
                                    :else              "already on the ledger")))
                0)
              (catch Exception e
                (unrecorded (str "a write failed: " (ex-message e))
                            "repair what it names, then run this again — a re-run completes what this one left undone")))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  record-cmd
  "bb entry point: exits non-zero when the landing is not recorded, so the recipe's step stops there."
  [& args]
  (let [code (apply record args)]
    (when-not (zero? code) (System/exit code))))
