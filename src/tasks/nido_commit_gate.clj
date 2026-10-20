;; src/tasks/nido_commit_gate.clj
(ns tasks.nido-commit-gate
  "bb-task entrypoint: nido's commit gate, run as a Claude Code `PreToolUse`
   hook on Bash. Exit 2 with the reason on stderr blocks the command; 0 lets it
   run. Two judges, each failing open: the project's own convention
   (`nido.session.commit-gate/verdict`), and for a seat's occupant that every
   commit and PR title names its seat and position (`nido.coordinator.lane.seats/
   gate-verdict`). This composes them; it judges nothing itself."
  (:require
   [cheshire.core :as json]
   [nido.coordinator.lane.seats :as seats]
   [nido.session.commit-gate :as gate]))

(defn- hook-input
  "The host's JSON on stdin, or nil when there is none. Only read when there is
   no console, so running the verb by hand in a terminal does not wait on a
   Ctrl-D (see `tasks.nido-boundary/hook-input`)."
  []
  (when (nil? (System/console))
    (try (json/parse-string (slurp *in*) true)
         (catch Exception _ nil))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  gate-cmd [& _args]
  (if-let [reason (when-let [in (hook-input)] (or (gate/verdict in) (seats/gate-verdict in)))]
    (do (binding [*out* *err*] (println reason))
        (System/exit 2))
    (System/exit 0)))
