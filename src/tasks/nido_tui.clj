(ns tasks.nido-tui
  "Bb task wrapper for the nido TUI.

   The TUI handles all interactive input (lists, modals, text input) while
   charm owns the terminal. Every session/ticket-mutating verb (`:up` `:down`
   `:destroy` `:add` `:promote`) now runs IN-APP (async, spinner) and stays in
   the TUI. The only action that reaches this wrapper is `:enter`,
   which MUST exit the process for the cwd handoff (see below). So this wrapper
   has shrunk to: run-once → if the action is an enter, hand off and exit;
   otherwise (just `:quit`) stop.

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
   [nido.platform.core :as core]
   [nido.ui.tui :as tui]
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

(defn- run-action [action]
  (try
    (case (first action)
      :enter     (let [[_ p s target] action]
                   (session/enter ":project" p s ":cd" (name target))))
    ;; All session/ticket-mutating verbs (:up :down :destroy :add :promote)
    ;; now run in-app (async, with a spinner) and never reach this wrapper.
    ;; Only :enter remains — it MUST exit the process so the parent shell
    ;; wrapper can pick up the cwd handoff.
    (catch Throwable t
      (log-throwable! t (str "action failed: " (pr-str action)))
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "[nido:tui] action failed: " (ex-message t)
                       " (see " (log-file) ")"))))))

(defn run [& _args]
  (try
    ;; No loop: every mutating verb runs in-app now, so run-once returns exactly
    ;; once — either :quit (stop) or an :enter action that is terminal
    ;; (writes ~/.nido/.last-cd; we exit so the parent shell picks up the cwd).
    (let [action (tui/run-once)]
      (cond
        (= :quit action)
        nil

        (and (vector? action) (#{:enter} (first action)))
        (run-action action)

        :else
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "[nido:tui] unexpected action: " (pr-str action))))))
    (catch Throwable t
      (log-throwable! t "tui loop crashed")
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "[nido:tui] crashed: " (ex-message t)
                       " (see " (log-file) ")")))
      (throw t))))
