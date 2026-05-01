(ns tasks.nido-tui
  "Bb task wrapper for the nido TUI.

   The TUI handles all interactive input itself (lists, modals, text input)
   while charm owns the terminal. Action keys queue an action and quit
   charm; this wrapper then runs the matching `nido:session:*` verb in the
   normal terminal (so claude — and all subprocess output — gets a real
   TTY) and immediately re-enters the TUI on completion. We never read
   from stdin between charm sessions because JLine's wrapping of
   System.in makes that unreliable.

   Usage:
     bb nido:tui"
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [nido.session.lifecycle :as lifecycle]
   [nido.tui :as tui]
   [tasks.nido-session :as session]))

(defn- destroy-and-verify!
  "Run destroy, then re-query and fall back to rm -rf if the worktree
   somehow survived (the underlying `git worktree remove --force` swallows
   errors and we want the TUI to actually reflect the user's intent)."
  [p s]
  (session/destroy ":project" p s)
  (let [{:keys [sessions]} (lifecycle/list-all-data {:project p})
        wt (some #(when (= s (:name %)) (:worktree %)) sessions)]
    (when (and wt (fs/exists? wt))
      (println (str "[nido:tui] worktree still exists; falling back to rm -rf " wt))
      (try (shell {:continue true} "rm" "-rf" wt)
           (catch Exception e
             (println (str "[nido:tui] rm -rf failed: " (ex-message e))))))))

(defn- run-action [action]
  (try
    (case (first action)
      :enter   (let [[_ p s] action] (session/enter ":project" p s))
      :up      (let [[_ p s] action] (session/up      ":project" p s))
      :down    (let [[_ p s] action] (session/down    ":project" p s))
      :destroy (let [[_ p s] action] (destroy-and-verify! p s))
      :add     (let [[_ p s] action] (session/up      ":project" p s)))
    (catch Exception e
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "[nido:tui] action failed: " (ex-message e)))))))

(defn run [& _args]
  (loop []
    (let [action (tui/run-once)]
      (cond
        (= :quit action)
        nil

        (vector? action)
        (do (run-action action)
            (recur))

        :else
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "[nido:tui] unexpected action: " (pr-str action))))))))
