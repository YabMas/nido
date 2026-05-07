(ns tasks.nido-session
  "Bb task entry points for the bundled session lifecycle. Every command
   requires `:project <name>` (the project registered via `nido:project:add`)
   and takes a single positional <session-name> (= git branch = worktree leaf).
   Kwargs and the positional may appear in any order.

   Surface:
     up       create worktree (if missing) + start PG/JVM/app — idempotent
     down     stop the session, leave worktree + state on disk
     reset    nuclear: down → drop PGDATA → re-clone template → up
     destroy  down + remove worktree
     enter    select session for the parent shell — writes the session-home
              path to ~/.nido/.last-cd; pair with the `nido` shell wrapper
              to actually `cd` (see Nido's CLAUDE.md). Pass :cd worktree to
              land in the code instead of the session-home.
     status   per-session liveness + ports
     list     project-wide overview

   Examples:
     bb nido:session:up      :project brian feat-auth
     bb nido:session:up      :project brian feat-auth :base develop
     bb nido:session:up      :project brian fix-bug   :branch existing-branch
     bb nido:session:up      :project brian feat-auth :jvm-heap-max 1500m
     bb nido:session:down    :project brian feat-auth
     bb nido:session:enter   :project brian feat-auth
     bb nido:session:enter   :project brian feat-auth :cd worktree
     bb nido:session:reset   :project brian feat-auth
     bb nido:session:destroy :project brian feat-auth :delete-branch? true
     bb nido:session:status  :project brian feat-auth
     bb nido:session:list    :project brian"
  (:require
   [clojure.edn :as edn]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]))

(defn- parse-token
  "Parse a CLI token as EDN, with one carve-out: top-level symbols stay as
   their original string. `bb nido:session:up :base origin/main` would
   otherwise produce the symbol `origin/main`, which crashes anything that
   runs a regex or string operation on it. Other shapes (keywords, numbers,
   booleans, vectors, maps) parse as usual — including vectors of symbols
   like `[dev cider/nrepl]`, where downstream code expects them."
  [tok]
  (let [parsed (try (edn/read-string tok) (catch Exception _ tok))]
    (if (symbol? parsed) tok parsed)))

(defn- keyword-token? [tok]
  (and (string? tok) (.startsWith ^String tok ":")))

(defn- split-args
  "Split CLI args into [positionals opts-map]. A token starting with ':' is
   a kwarg key and consumes the next token as its value; every other token
   is a positional. Preserves positional order."
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

(defn- require-session-name [positionals]
  (case (count positionals)
    0 (throw (ex-info "Missing session name (positional)"
                      {:hint "Usage: bb nido:session:<cmd> :project <project> <session>"}))
    1 (str (first positionals))
    (throw (ex-info "Too many positional args; expected one session name"
                    {:positionals positionals}))))

(defn- require-no-positional [positionals]
  (when (seq positionals)
    (throw (ex-info "Unexpected positional args; this command takes only kwargs"
                    {:positionals positionals}))))

(defn up
  "Bring the named session up. Creates the worktree (if missing) + starts
   PG/JVM/app, then prints the session-home path the user can cd into to
   start their preferred agent (claude, codex, …). Idempotent — running
   on a live session refreshes the session-home artifacts but doesn't
   restart services. Kwargs like :base, :branch, :jvm-heap-max flow into
   `up!`."
  [& args]
  (let [[pos opts] (split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/up! session opts)
    (let [home (state/session-home-dir project session)]
      (println)
      (println (str "Session ready: " project "/" session))
      (println (str "  cd " home))
      (println (str "  bb nido:session:enter :project " project " " session)))))

(defn down
  "Stop the named session. Worktree and on-disk state are preserved."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/down! session opts)))

(defn reset
  "Nuclear recovery: bring the session down, drop its PGDATA, then bring
   it back up against a fresh template clone. Use after
   `bb nido:template:pg:refresh` or when a session has wedged into a
   bad state."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/reset! session opts)))

(defn destroy
  "Bring the named session down and remove its worktree.
   Pass :delete-branch? true to also drop the git branch."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/destroy! session opts)))

(defn enter
  "Hand off a cwd to the parent shell via `~/.nido/.last-cd`. Paired with
   a tiny shell function (see Nido's CLAUDE.md → Shell wrapper) the user
   lands in the chosen directory with no nested shell.

   `:cd home` (default) → session-home (CLAUDE.md, .mcp.json live here).
   `:cd worktree`       → the worktree symlink (the actual code).

   Refuses if the session is down, or if `:cd worktree` is requested and
   the worktree symlink is missing."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/enter! session opts)))

(defn status
  "Print status for the named session."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/status session opts)))

(defn list-sessions
  "List every session for a project."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)]
    (require-no-positional pos)
    (lifecycle/list-all opts)))
