(ns tasks.nido-help)

(def ^:private groups
  [{:title "Setup"
    :tasks [["nido:init"               "Create ~/.nido/ skeleton directory structure"]
            ["nido:project:add"        "<name> <directory> — register a project"]
            ["nido:project:list"       "List registered projects"]
            ["nido:project:remove"     "<name> — unregister a project"]]}

   {:title "Sessions  (all take :project <p> <session>)"
    :tasks [["nido:session:up"         "Worktree (if missing) + start PG/JVM/app — idempotent"]
            ["nido:session:down"       "Stop the session; worktree + state preserved"]
            ["nido:session:reset"      "Nuclear: down + drop PGDATA + re-clone template + up"]
            ["nido:session:destroy"    "Down + remove the worktree"]
            ["nido:session:status"     "Show session status"]
            ["nido:session:list"       ":project <p> — list sessions for a project"]]}

   {:title "Template Postgres  (per-project, long-lived clone source)"
    :tasks [["nido:template:pg:init"    "Initialize template cluster (empty schema)"]
            ["nido:template:pg:refresh" "Refresh from a fresh dump via :refresh-steps"]
            ["nido:template:pg:status"  "Show template cluster status"]
            ["nido:template:pg:destroy" "Delete the template cluster"]]}

   {:title "Reclaim"
    :tasks [["nido:reclaim"             "List orphan state dirs (:force? true to delete)"]]}

   {:title "Bench"
    :tasks [["nido:bench:memory"        ":project <name> [:levers [kw...]] [:session <name>]"]
            ["nido:bench:memory:levers" "List known bench levers"]]}

   {:title "VSDD"
    :tasks [["nido:vsdd:run"      ":project-dir <path> :module-path <path>"]
            ["nido:vsdd:resume"   ":project-dir <path> :run-id <id>"]
            ["nido:vsdd:analyze"  ":project-dir <path> :run-id <id> [:model opus]"]
            ["nido:vsdd:sweep"    ":project-dir <path> — run across all changed modules"]]}

   {:title "UI"
    :tasks [["nido:ui" "[:port 8800] — start the nido dashboard"]]}

])

(def ^:private examples
  ["bb nido:project:add brian ~/Code/brian"
   "bb nido:template:pg:init :project brian"
   "bb nido:session:up :project brian feature-x"
   "bb nido:session:reset :project brian feature-x"
   "bb nido:session:up :project brian foo :jvm-heap-max 1500m"
   "bb nido:session:list :project brian"])

(defn- pad [s n]
  (let [s (str s)]
    (str s (apply str (repeat (max 0 (- n (count s))) \space)))))

(defn show
  "Print a curated, grouped overview of nido tasks."
  [& _args]
  (let [width (->> groups
                   (mapcat :tasks)
                   (map (comp count first))
                   (apply max 0))]
    (println "nido — agent orchestrator for dev sessions")
    (println)
    (println "Usage: bb <task> [args...]    (run `bb tasks` for the flat list)")
    (doseq [{:keys [title tasks]} groups]
      (println)
      (println title)
      (doseq [[name doc] tasks]
        (println (str "  " (pad name (+ width 2)) doc))))
    (println)
    (println "Examples")
    (doseq [ex examples]
      (println (str "  " ex)))
    (println)
    (println "More: see CLAUDE.md for session lifecycle, PG topology, and JVM tuning.")))
