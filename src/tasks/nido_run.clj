(ns tasks.nido-run
  "Bb task: run a project-declared :project-commands entry inside a session's
   worktree. The generic 'adopt a target-project command' surface.

   Usage:
     bb nido:run :project <p> <session> <command-ref>

   Example (brian adopts its CI as :ci in session.edn):
     bb nido:run :project brian feat-x ci

   The command runs in the session's worktree with live output, and the task
   exits with the command's own exit code. A ref the project hasn't declared
   fails, naming the available commands. Only projects whose session.edn
   declares a matching :project-commands entry can run it — nothing
   brian-specific lives here.

   The command ref is a bare positional word (`ci`), coerced to a keyword. Do
   not pass it as `:ci` — a leading colon makes the CLI parser treat it as a
   kwarg key."
  (:require
   [clojure.string :as str]
   [nido.run :as run]
   [nido.platform.task-args :as task-args]))

(defn- require-project [opts]
  (or (some-> (:project opts) name)
      (throw (ex-info "Missing :project <name>"
                      {:hint "Pass :project <project-name> — the name used in `bb nido:project:add`."}))))

(defn run
  "Run the named project-command in a session worktree and exit with its code."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project    (require-project opts)
        [session ref-str] pos]
    (when-not (and session ref-str)
      (throw (ex-info "Usage: bb nido:run :project <p> <session> <command-ref>"
                      {:positionals pos
                       :hint "e.g. bb nido:run :project brian feat-x ci"})))
    (when (> (count pos) 2)
      (throw (ex-info "Too many positional args; expected <session> <command-ref>"
                      {:positionals pos})))
    (let [ref    (keyword (str/replace (str ref-str) #"^:" ""))
          result (run/run-command-in-session! project session ref)]
      (System/exit (int (:exit result))))))
