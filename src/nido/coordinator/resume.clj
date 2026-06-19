(ns nido.coordinator.resume
  "Re-engage a PARKED autonomous session with the human's input by relaunching
   its recorded claude conversation as one bounded headless turn
   (`claude --resume <id> -p \"<input>\"`). The session phase is driven
   :parked -> :running -> :parked directly (the gate inbox reads the session phase,
   not a Run state — so this works whether the owning Run is :awaiting-review or
   already terminal). This is the :reply resolver behind nido.work/resolve-gate!."
  (:require
   [nido.coordinator.agent :as agent]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]))

(defn- parked-session
  "The (first) parked autonomous session under a workstream, or nil."
  [project ws-id]
  (->> (session/list-sessions project ws-id)
       (filter session/parked?)
       first))

(defn- run-turn!
  "Synchronous body for one resume turn. Re-provisions the session-home first if it
   was reclaimed (runs/ensure-session-home! — the transcript survives keyed by the
   home path, so re-provision at the same path re-anchors it). Then launches one
   bounded `claude --resume` turn, records the outcome on the session (`:error`
   cleared on success / set on failure, logged to *err* for operators), and
   re-parks for re-review regardless."
  [project ws-id session-name run input]
  (try
    (runs/ensure-session-home! run)
    (agent/launch! {:run-id            (:id run)
                    :cwd               (cstate/run-session-home-link (:id run))
                    :first-message     input
                    :claude-session-id (:claude-session-id run)
                    :resume?           true
                    :budget            (-> run :limits :budget)})
    (session/set-error! project ws-id session-name nil)
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
   no parked session (:not-parked) or no recoverable conversation (:no-claude-session)."
  [project ws-id input]
  (let [s (parked-session project ws-id)]
    (when-not s
      (throw (ex-info "No parked session to resume"
                      {:reason :not-parked :project project :ws-id ws-id})))
    (let [run (runs/find-for-session project ws-id (:name s))]
      (when-not (and run (:claude-session-id run))
        (session/set-error! project ws-id (:name s)
                            {:at      (clock/now-iso)
                             :reason  :no-claude-session
                             :message "No resumable conversation — open the session in the terminal"})
        (throw (ex-info "No resumable conversation — open the session in the terminal"
                        {:reason :no-claude-session :project project :ws-id ws-id})))
      (session/set-phase! project ws-id (:name s) :running)
      (future (run-turn! project ws-id (:name s) run input))
      {:resumed (:name s)})))
