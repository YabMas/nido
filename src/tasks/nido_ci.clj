(ns tasks.nido-ci
  "Bb task entry points for local CI. Every command requires `:project
   <name>`; the session positional is optional — runs without one
   default to the project's main directory.

   Usage:
     bb nido:ci:run    :project <p> [<session>] [:only <step-or-vec>] [:profile <kw>]
     bb nido:ci:rerun  :project <p> <session> [:from <run-id>]

   `<session>` is optional. With a positional session name, the run
   uses that running nido session as its source worktree (start one
   with `bb nido:session:init :project <p> <session>`). Without one,
   the run defaults to the project's main directory — useful for
   pre-merge sanity checks or fast iteration without spinning up a
   worktree first. Steps with `:isolated-pg?` / `:isolated-app?` get
   their own resources either way.

   Steps come from the project's ci.edn at
   ~/.nido/projects/<project>/ci.edn. `:profile` selects a named slice
   from ci.edn `:profiles` (defaults to `:default` when ci.edn
   declares profiles). `:only` overrides the profile when present."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [nido.ci.config :as ci-config]
   [nido.ci.lifecycle :as ci-lifecycle]
   [nido.ci.session :as ci-session]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as session-lifecycle]
   [nido.session.state :as state]))

(defn- parse-token [tok]
  (try (edn/read-string tok) (catch Exception _ tok)))

(defn- keyword-token? [tok]
  (and (string? tok) (.startsWith ^String tok ":")))

(defn- split-args
  "Split CLI args into [positionals opts-map]. A token starting with ':'
   is a kwarg key and consumes the next token as its value; every other
   token is a positional. Mirrors tasks.nido-session/split-args."
  [args]
  (loop [xs args, pos [], opts {}]
    (if (empty? xs)
      [pos opts]
      (let [x (first xs)]
        (if (keyword-token? x)
          (let [k (parse-token x)
                v (second xs)]
            (when-not (some? v)
              (throw (ex-info (str "Missing value for " x) {:args args})))
            (recur (drop 2 xs) pos (assoc opts k (parse-token v))))
          (recur (rest xs) (conj pos x) opts))))))

(defn- require-project [opts]
  (or (some-> (:project opts) name)
      (throw (ex-info "Missing :project <name>"
                      {:hint "Pass :project <project-name> — the name used in `bb nido:project:add`."}))))

(defn- require-session-name
  "Optional positional. Returns nil when no session is given so the
   caller can pick the main-mode default. `rerun` keeps requiring one."
  [positionals]
  (case (count positionals)
    0 nil
    1 (str (first positionals))
    (throw (ex-info "Too many positional args; expected at most one session name"
                    {:positionals positionals}))))

(defn- main-session
  "Build a synthetic Session value targeting the project's registered
   main directory. No live nido session is required — used when the
   user runs `bb nido:ci:run :project <p>` with no session positional.

   Steps that opt into `:isolated-pg?` / `:isolated-app?` still get
   their own ephemeral resources; this just gives the run a reasonable
   `:worktree-path` and `:instance-state-dir` for the cloned work-dir
   tree."
  [project-name directory]
  (let [instance-id (str project-name "--main")]
    {:instance-id instance-id
     :project-name project-name
     :session-name "main"
     :worktree-path (str directory)
     :instance-state-dir (state/instance-state-dir instance-id)
     :pg-port nil
     :app-port nil
     :app-port-range-start 3100
     :app-port-range-end 5100}))

(defn- resolve-ci-context [session-name opts]
  (let [[project-name {:keys [directory]}] (session-lifecycle/resolve-project opts)
        config (ci-config/load-config project-name)
        session
        (if (nil? session-name)
          (main-session project-name directory)
          (let [wt-path (or (some-> (:path opts) str)
                            (session-lifecycle/worktree-path project-name directory session-name))
                instance-id (engine/resolve-instance-id wt-path)]
            (ci-session/lookup instance-id)))]
    (fs/create-dirs (:instance-state-dir session))
    {:session session
     :config  config}))

(defn- exit-code-for [run]
  (case (:outcome run)
    :passed      0
    :failed      1
    :errored     2
    :interrupted 130
    1))

(defn- print-summary [run]
  (println (str "ci run " (:run-id run)
                " outcome=" (name (:outcome run))
                " manifest=" (get-in run [:paths :status-manifest-path])))
  (doseq [step-name (:step-names run)]
    (let [sr (get-in run [:step-runs step-name])]
      (println (format "  %-20s %s%s"
                       (name step-name)
                       (if-let [o (:outcome sr)] (name o) "?")
                       (if-let [ec (:command-exit-code sr)]
                         (str " (exit " ec ")") ""))))))

(defn run
  "Execute a Run. With no `:only` and no `:profile`, runs every step
   in ci.edn (subject to step-level :after deps) — unless ci.edn
   declares `:profiles`, in which case the `:default` profile picks
   the steps. `:only :unit` or `:only [:unit :lint]` restricts to a
   subset, overriding any profile. `:profile :full` selects a named
   slice from ci.edn `:profiles`."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session-name (require-session-name pos)
        {:keys [session config]} (resolve-ci-context session-name opts)
        selection (cond-> {}
                    (:only opts)    (assoc :only (:only opts))
                    (:profile opts) (assoc :profile (:profile opts)))
        result (ci-lifecycle/execute-run!
                {:session session :config config :selection selection})]
    (print-summary result)
    (System/exit (exit-code-for result))))

(defn rerun
  "Re-execute the failed/errored/interrupted steps from the most recent
   Run for this session (or `:from <run-id>` for a specific Run).

   Without a positional session, defaults to the same `\"main\"` target
   `run` uses — so `bb nido:ci:rerun :project p` re-runs the last
   main-mode Run."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session-name (require-session-name pos)
        {:keys [session config]} (resolve-ci-context session-name opts)
        rerun-selection (ci-lifecycle/resolve-rerun-selection!
                         session {:from (:from opts)})
        result (ci-lifecycle/execute-run!
                {:session session :config config :selection rerun-selection})]
    (print-summary result)
    (System/exit (exit-code-for result))))
