;; src/tasks/nido_commit_gate.clj
(ns tasks.nido-commit-gate
  "bb-task entrypoint: nido's commit gate, run as a Claude Code `PreToolUse`
   hook on Bash. Exit 2 with the reason on stderr blocks the command; 0 lets it
   run. Every judgement is `nido.session.commit-gate/verdict`'s, and so is its
   failing-open contract — this only reads the host's JSON and answers."
  (:require
   [cheshire.core :as json]
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
  (if-let [reason (some-> (hook-input) gate/verdict)]
    (do (binding [*out* *err*] (println reason))
        (System/exit 2))
    (System/exit 0)))
