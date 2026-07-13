(ns nido.coordinator.resume
  "Re-engage a PARKED autonomous session with the human's input by relaunching
   its recorded claude conversation as one bounded headless turn
   (`claude --resume <id> -p \"<input>\"`). The session phase is driven
   :parked -> :running -> :parked directly (the gate inbox reads the session phase,
   not a Run state — so this works whether the owning Run is :awaiting-review or
   already terminal). This is the :reply resolver behind nido.work/resolve-gate!."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.session.state :as session-state]))

(defn- parked-session
  "The (first) parked autonomous session under a workstream, or nil."
  [project ws-id]
  (->> (session/list-sessions project ws-id)
       (filter session/parked?)
       first))

(defn- transcript-owner?
  "True when claude's transcript for `sid` lives under `cwd`'s project dir. claude
   stores a conversation at ~/.claude/projects/<cwd, with / and . replaced by ->/
   <sid>.jsonl, keyed by the cwd it launched in."
  [cwd sid]
  (boolean
   (and cwd sid
        (fs/exists? (fs/path (System/getProperty "user.home") ".claude" "projects"
                             (str/replace (str cwd) #"[/.]" "-")
                             (str sid ".jsonl"))))))

(defn resume-cwd
  "The cwd to launch `claude --resume <sid>` in so it finds the conversation.
   claude keys a transcript by its launch cwd: a run spawned with cwd=worktree is
   worktree-keyed, but a LEGACY parked session (spawned when cwd was the
   session-home) is home-keyed. Resuming in the wrong cwd yields claude's
   'No conversation found with session ID' and the turn fails — stranding every
   pre-worktree-cwd parked review as un-appliable. Return whichever of `worktree`
   / `home` actually owns the transcript (worktree preferred), else `worktree`
   (new-run default; nil when there is no run substrate)."
  [sid worktree home]
  (cond
    (transcript-owner? worktree sid) worktree
    (transcript-owner? home sid)     home
    :else                            worktree))

(defn- run-turn!
  "Synchronous body for one resume turn. Re-provisions the session-home first if it
   was reclaimed (runs/ensure-session-home! — the transcript survives keyed by the
   home path, so re-provision at the same path re-anchors it). Then launches one
   bounded `claude --resume` turn, records the outcome on the session (`:error`
   cleared on success / set on failure, logged to *err* for operators), and
   re-parks for re-review regardless.

   `s` is the parked session map; `run` is the run record (may be nil for sessions
   not backed by a run.edn). Identity (`:claude-session-id`, `:budget`) is resolved
   from `s` first, with `run` as the fallback. The run is used only for the
   execution substrate (`ensure-session-home!` / `launch-context`), guarded for nil."
  [project ws-id session-name s run input]
  (try
    (when run (runs/ensure-session-home! run))
    (let [lc     (if run (runs/launch-context run) {})
          sid    (or (get-in s [:autonomy :claude-session-id])
                     (:claude-session-id run))
          home   (str (session-state/session-home-dir (name project) session-name))
          budget (or (get-in s [:autonomy :limits :budget])
                     (-> run :limits :budget))
          result (agent/launch! {:run-id            (:id run)
                                 :cwd               (resume-cwd sid (:cwd lc) home)
                                 :first-message     input
                                 :claude-session-id sid
                                 :resume?           true
                                 :mcp-config        (:mcp-config lc)
                                 :add-dirs          (:add-dirs lc)
                                 :budget            budget})]
      ;; launch! SIGTERM-kills on budget overrun but RETURNS normally with
      ;; :timed-out? — the catch never fires. Surface it as a resume error
      ;; (rendered by the gate inbox) instead of clearing to nil, so a killed
      ;; redo reads as a timeout, not a silent re-park showing the stale report.
      (if (:timed-out? result)
        (do
          (binding [*err* *err*]
            (.println ^java.io.PrintWriter *err*
                      (str "nido coordinator: resume turn for " session-name
                           " exceeded its " budget " budget — terminated before it "
                           "could finish; no new report written")))
          (session/set-error! project ws-id session-name
                              {:at      (clock/now-iso)
                               :reason  :budget-exhausted
                               :message (str "Resume turn hit its " budget
                                             " budget and was stopped before it finished — "
                                             "no new report was written. Reply again to retry "
                                             "(raise the trigger's :limits.budget if this recurs).")}))
        (session/set-error! project ws-id session-name nil)))
    (catch Throwable t
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "nido coordinator: resume turn failed for " session-name
                       " — " (ex-message t))))
      (session/set-error! project ws-id session-name
                          {:at      (clock/now-iso)
                           :reason  (or (:reason (ex-data t)) :resume-failed)
                           :message (ex-message t)}))
    (finally
      (session/set-phase! project ws-id session-name :parked))))

(defn resume!
  "Re-engage a parked session under `ws-id` with `input`. Flips the session to
   :running synchronously, then runs one resume turn on a background thread.
   Returns {:resumed <session-name>}; throws ex-info (with :reason) when there is
   no parked session (:not-parked) or no recoverable conversation (:no-claude-session).

   The claude-session-id and limits are resolved from the parked session first
   (`:autonomy :claude-session-id` / `:autonomy :limits`), with the run.edn as a
   fallback for legacy parked sessions. The run is used only for the execution
   substrate (ensure-session-home! / launch-context). Throws :no-claude-session
   only when BOTH the session and the run lack an id."
  [project ws-id input]
  (let [s (parked-session project ws-id)]
    (when-not s
      (throw (ex-info "No parked session to resume"
                      {:reason :not-parked :project project :ws-id ws-id})))
    (let [run (runs/find-for-session project ws-id (:name s))
          sid (or (get-in s [:autonomy :claude-session-id])
                  (:claude-session-id run))]
      (when-not sid
        (session/set-error! project ws-id (:name s)
                            {:at      (clock/now-iso)
                             :reason  :no-claude-session
                             :message "No resumable conversation — open the session in the terminal"})
        (throw (ex-info "No resumable conversation — open the session in the terminal"
                        {:reason :no-claude-session :project project :ws-id ws-id})))
      (session/set-phase! project ws-id (:name s) :running)
      (future (run-turn! project ws-id (:name s) s run input))
      {:resumed (:name s)})))
