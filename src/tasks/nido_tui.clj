(ns tasks.nido-tui
  "Bb task wrapper for the nido TUI.

   The TUI handles all interactive input (lists, modals, text input) while
   charm owns the terminal. Action keys queue an action and quit charm;
   this wrapper runs the matching `nido:session:*` verb and either
   re-enters the TUI (`:up`, `:down`, `:destroy`, `:add`) or exits cleanly
   (`:enter` — see below).

   `:enter` doesn't spawn a subshell. bb cannot change its parent shell's
   cwd, so the action writes the session-home path to
   `~/.nido/.last-cd` and exits; a tiny zsh function (documented in
   nido's CLAUDE.md) reads the file and `cd`s the user there. This
   replaces an earlier shell-spawn approach that died inside the JVM
   under JLine post-charm — the parent-shell handoff is both more
   robust and what the user actually wanted (no nested shell).

   Errors that escape the action handler — or the charm event loop —
   would otherwise flash by for a single frame before bb exits, which
   is unrecoverable when the host terminal closes the tab on process
   exit. Anything thrown is appended to `~/.nido/tui.log` (with stack
   trace) so it can be read post-mortem.

   Usage:
     bb nido:tui"
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [nido.core :as core]
   [nido.session.lifecycle :as lifecycle]
   [nido.tui :as tui]
   [tasks.nido-session :as session])
  (:import
   [java.io PrintWriter StringWriter]))

(defn- log-file []
  (str (fs/path (core/nido-home) "tui.log")))

(defn- log-throwable! [^Throwable t context]
  (let [sw (StringWriter.)
        pw (PrintWriter. sw)]
    (.printStackTrace t pw)
    (.flush pw)
    (let [path (log-file)]
      (try
        (fs/create-dirs (fs/parent path))
        (spit path
              (str (java.time.Instant/now) " " context "\n" sw "\n")
              :append true)
        (catch Exception _ nil)))))

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
    (catch Throwable t
      (log-throwable! t (str "action failed: " (pr-str action)))
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "[nido:tui] action failed: " (ex-message t)
                       " (see " (log-file) ")"))))))

(defn run [& _args]
  (try
    (loop []
      (let [action (tui/run-once)]
        (cond
          (= :quit action)
          nil

          ;; :enter is terminal — the parent shell wrapper picks up
          ;; ~/.nido/.last-cd after we exit. Looping back into charm
          ;; here would erase the handoff signal.
          (and (vector? action) (= :enter (first action)))
          (run-action action)

          (vector? action)
          (do (run-action action)
              (recur))

          :else
          (binding [*err* *err*]
            (.println ^java.io.PrintWriter *err*
                      (str "[nido:tui] unexpected action: " (pr-str action)))))))
    (catch Throwable t
      (log-throwable! t "tui loop crashed")
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "[nido:tui] crashed: " (ex-message t)
                       " (see " (log-file) ")")))
      (throw t))))
