(ns tasks.nido-session
  "Bb task entry points for the bundled session lifecycle. Every command
   requires `:project <name>` (the project registered via `nido:project:add`)
   and takes a single positional <session-name> (= git branch = worktree leaf).
   Kwargs and the positional may appear in any order.

   Examples:
     bb nido:session:init    :project brian feat-auth
     bb nido:session:init    :project brian feat-auth :base develop
     bb nido:session:init    :project brian fix-bug   :branch existing-branch
     bb nido:session:init    :project brian feat-auth :jvm-heap-max 1500m
     bb nido:session:stop    :project brian feat-auth
     bb nido:session:restart :project brian feat-auth
     bb nido:session:destroy :project brian feat-auth :delete-branch? true
     bb nido:session:status  :project brian feat-auth
     bb nido:session:list    :project brian"
  (:require
   [clojure.edn :as edn]
   [nido.session.lifecycle :as lifecycle]))

(defn- parse-token [tok]
  (try (edn/read-string tok) (catch Exception _ tok)))

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

(defn init
  "Create the named session's worktree (if missing) and start its services."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/init! session opts)))

(defn stop
  "Stop the named session, leaving the worktree in place."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/stop! session opts)))

(defn restart
  "Stop then start the named session."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/restart! session opts)))

(defn destroy
  "Stop the named session and remove its worktree.
   Pass :delete-branch? true to also drop the git branch."
  [& args]
  (let [[pos opts] (split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/destroy! session opts)))

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
