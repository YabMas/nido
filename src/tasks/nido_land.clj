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
   [clojure.string :as str]
   [nido.coordinator.standing :as standing]
   [nido.design.check :as design]
   [tasks.nido-design :as nido-design]
   [nido.coordinator.workstream :as cws]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.task-args :as task-args]))

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
         "  4. Approve it in the gate inbox")

    :premise-retracted
    (str "  The baseline is not merely stale, somebody found it FALSE — read entry "
         seq "\n  for the counterexample before you touch anything.\n"
         "  1. baseline the area again and append a new :baseline (/design §4)\n"
         (when replaced-by
           (str "     (entry " replaced-by " corrects it — start from there)\n"))
         "  2. bb nido:review:baseline — until it answers `sufficient`\n"
         "  3. supersede the design so it cites the corrected baseline\n"
         "  4. bb nido:review:design, then Approve it in the gate inbox")

    :design-retracted
    (str "  Somebody found this design untrue — read entry " seq " first.\n"
         "  Write a superseding :design, run bb nido:review:design, and have it\n"
         "  approved. Do not amend the retracted one: it stays in the ledger.")

    :no-premise
    (str "  This design cites no baseline — it predates the baseline event.\n"
         "  1. baseline the area and append a :baseline (/design §4)\n"
         "  2. bb nido:review:baseline\n"
         "  3. supersede the design so it cites it, then re-decide and approve")

    :not-approved
    (str "  The design stands and nobody has granted it. If no :design-decision\n"
         "  exists yet, run bb nido:review:design first; then Approve it in the\n"
         "  gate inbox (http://localhost:8800), which is what records the grant.")

    :unreadable-ledger
    (str "  An entry this depends on will not parse, so standing cannot be\n"
         "  derived — and it fails closed rather than waving the branch through.\n"
         "  Find the unreadable entry under the workstream's entries/ and repair\n"
         "  it; a ledger nobody can read is not a ledger anybody may land on.")

    (str "  No route recorded for " reason " — say so rather than working around it.")))

(defn- standing-check
  "Refuse unless this session's design DECISION stands: not retracted, approved,
   and the exact baseline it cites still live.

   A workstream with NO design passes. Most do not have one — scratch
   workstreams, pickups mid-flight — and a gate that demanded a design of every
   branch would stop the work that has not reached one yet, which is not what
   this is for."
  [cwd]
  (if-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
    (if-let [design (cws/latest-entry project ws-id :design)]
      (let [st (standing/of-design project ws-id design)]
        (if (:decided? st)
          (do (println (str "land:check ok · design at entry " (:seq design)
                            " stands, approved at entry " (:approved-by st)))
              0)
          (let [b (or (:blocked st)
                      {:reason :not-approved :seq (:seq design)})]
            (println (str "land:check REFUSED · " (name (:reason b))))
            (when-let [d (:detail b)] (println (str "  " d)))
            (println "\nHow to clear it:")
            (println (way-out b))
            1)))
      (do (println "land:check ok · this workstream holds no design to stand on")
          0))
    (do (println (str "land:check ok · " cwd " is no nido session — nothing to stand on"))
        0)))

(defn- structure-check
  "Refuse unless the CODE still obeys the structure the project declared.

   A different question from the one above, and the reason both are here. Standing
   asks whether anyone still believes the premise this branch was written against.
   This asks whether the branch left the codebase in the shape the project says it
   has — a `canvas/` model, checked by fukan against the extracted call graph.

   A branch can pass one and fail the other in either direction, so neither
   substitutes for the other, and neither belongs in a recipe: a rule that lives
   only in prose is followed by whoever read the prose."
  [cwd]
  (if-let [[project worktree] (nido-design/coords cwd)]
    (let [result (design/check project worktree)]
      (case (:status result)
        :unmodelled  (do (println (str "land:check ok · " project " declares no structure to check"))
                         0)
        :satisfied   (do (println (str "land:check ok · the code obeys " project "'s declared structure"))
                         0)
        :violated    (let [n (reduce + (map (comp count :offenders) (:violations result)))]
                       (println (str "land:check REFUSED · " n " violation" (when (not= 1 n) "s")
                                     " of " project "'s declared structure\n"))
                       (println (design/violation-text result))
                       (println "\nHow to clear it:")
                       (println (str "  Either the code moves or the declaration does — one of them\n"
                                     "  is wrong, and landing is where that gets decided rather than\n"
                                     "  inherited. The declaration is in "
                                     (str/join ", " (:files (design/design-of project worktree))) ".\n"
                                     "  Re-run with bb nido:design:check."))
                       1)
        :undecidable (do (println (str "land:check REFUSED · the structure check did not complete: "
                                       (:error result)))
                         (println "\nHow to clear it:")
                         (println (str "  This is not a clean bill of health, it is nobody being able\n"
                                       "  to tell. Fix the checker — bb nido:design:check reproduces\n"
                                       "  it — rather than landing on an answer that was never given."))
                         2)))
    0))

(defn check
  "The landing gate: both questions, and a refusal from either is a refusal.

   Every check runs even when an earlier one refuses. An agent that has to
   discover its blockers one push at a time will make one trip per blocker."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        given (or (:cwd opts) (System/getProperty "user.dir"))
        cwd   (or (lifecycle/worktree-from-cwd given) given)]
    (apply max (mapv #(% cwd) [standing-check structure-check]))))

(defn cmd
  "bb entry point: exits non-zero on a refusal, so a recipe that runs it before
   the push stops there without anyone having to read the output."
  [& args]
  (let [code (apply check args)]
    (when-not (zero? code) (System/exit code))))
