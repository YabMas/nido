(ns nido.coordinator.lane.resume
  "Re-engage an autonomous session waiting on a person with their input by relaunching its
   recorded claude conversation as one bounded headless turn
   (`claude --resume <id> -p \"<input>\"`). Which session waits is `waiting-session`'s answer —
   run records, run locks, the executor and status — so this works whether the owning Run is
   :awaiting-review or the workstream is held by a driver's blocker. This is the :reply resolver
   behind nido.coordinator.work/resolve-gate!."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.session.commit-gate :as commit-gate]
   [nido.session.state :as session-state]))

(defn- turn-key
  "What a reply turn into session `s` is queued under: its owning run, or the session itself when
   no run owns it."
  [s run]
  (or (:id run) (str "session-" (:name s))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :Session]]}
  waiting-session
  "The first autonomous session under a workstream that waits on a person's reply, or nil: not
   archived, no agent of its owning run running and no reply to it already queued, and either its
   run parked for review or the workstream's status owing a person its next move — a driver's
   blocker, which parks no run. Read off run records, run locks, the executor and status; no
   stored phase says it."
  [project ws-id]
  (let [owed-person? (delay (= :person (:owed-by (pipeline/status project ws-id))))]
    (->> (session/list-sessions project ws-id)
         (filter #(and (session/autonomous? %) (session/live? %)))
         (filter (fn [s]
                   (let [run (runs/find-for-session project ws-id (:name s))]
                     (and (not (and run (runs/live? (:id run))))
                          (not (executor/turn-pending? (turn-key s run)))
                          (or (= :awaiting-review (:state run)) @owed-person?)))))
         first)))

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

(defn ^{:malli/schema [:=> [:cat :any :Path :Path] :Path]}
  resume-cwd
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
   re-parks for re-review regardless — stopping the session's services again
   (runs/stop-session-for-parked-run!) once it is parked.

   `s` is the parked session map; `run` is the run record (may be nil for sessions
   not backed by a run.edn). Identity (`:claude-session-id`, `:budget`) is resolved
   from `s` first, with `run` as the fallback. The run is used only for the
   execution substrate (`ensure-session-home!` / `launch-context`), guarded for nil."
  [project ws-id session-name s run input]
  (try
    (when run
      (runs/ensure-session-home! run {:kind    :resume
                                      :project project
                                      :ws-id   ws-id
                                      :session session-name
                                      :input   input}))
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
                                 ;; The gate comes from the gate, not from the Run: a parked
                                 ;; conversation whose Run is gone carries it all the same.
                                 :settings          (commit-gate/settings-json)
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
      (session/set-phase! project ws-id session-name :parked)
      ;; Parked again, so stopped again: otherwise every session a reply ever
      ;; reached would keep its JVM for good. The next reply re-provisions it
      ;; through ensure-session-home!, above.
      (when run (runs/stop-session-for-parked-run! run)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string] :any]}
  resume!
  "Re-engage a parked session under `ws-id` with `input`. Flips the session to
   :running synchronously, then hands one turn to the executor, which runs it when
   a slot frees. A session whose conversation is a background session (a :claude
   facet) instead takes `input` as its next instruction through conduct, queued
   when that turn is admitted, and answers :via :substrate; an instruction conduct
   refuses parks the session again with the refusal as its error.

   Returns {:resumed <session-name> :queued? <bool>}, and :unaccounted? true in
   the one case where the turn ran outside the executor because nothing in this
   process ticks it. Throws ex-info (with :reason) when there is no parked session
   (:not-parked) or no recoverable conversation (:no-claude-session).

   The turn is no longer immediate, and that is the point rather than a
   regression: it waits for a slot exactly as a spawn does. The caller could not
   observe the work finishing before this either — it was already a background
   thread — so what changed is when it starts, not what the caller may assume.

   The claude-session-id and limits are resolved from the parked session first
   (`:autonomy :claude-session-id` / `:autonomy :limits`), with the run.edn as a
   fallback for legacy parked sessions. The run is used only for the execution
   substrate (ensure-session-home! / launch-context). Throws :no-claude-session
   only when BOTH the session and the run lack an id."
  [project ws-id input]
  (let [s (waiting-session project ws-id)]
    (when-not s
      (throw (ex-info "No parked session to resume"
                      {:reason :not-parked :project project :ws-id ws-id})))
    ;; A conversation that is a background session takes the reply as its next
    ;; instruction: conduct gives it when the session is ready, and the session's
    ;; own Stop re-parks it. No --print turn continues it. It is queued through
    ;; the executor like any turn, so the reply is admitted under the global cap
    ;; before conduct may give it — its trigger's in-flight slot the parked session
    ;; already holds (see executor/submit-turn!); once given, the session holds its
    ;; global slot while the substrate reports it working.
    (when (:dispatching s)
      ;; A start in flight: conduct names the session once a listing shows it, and there is
      ;; no facet for a reply to be queued on before then.
      (session/set-error! project ws-id (:name s)
                          {:at      (clock/now-iso)
                           :reason  :session-unnamed
                           :message "The session nido started has no id from Claude Code yet — reply again once it does"})
      (throw (ex-info "The session nido started has no id from Claude Code yet"
                      {:reason :session-unnamed :project project :ws-id ws-id})))
    (if (:claude s)
      (let [run  (runs/find-for-session project ws-id (:name s))
            body #(try (conduct/instruct! project ws-id (:name s) input)
                       (catch Throwable t
                         (session/set-error! project ws-id (:name s)
                                             {:at      (clock/now-iso)
                                              :reason  (or (:reason (ex-data t)) :resume-failed)
                                              :message (ex-message t)})
                         (session/set-phase! project ws-id (:name s) :parked)))]
        (session/set-error! project ws-id (:name s) nil)
        (session/set-phase! project ws-id (:name s) :running)
        (if (executor/driven?)
          (do (executor/submit-turn!
               {:run-id        (or (:id run) (str "session-" (:name s)))
                :turn          (str (random-uuid))
                :trigger       (:trigger run)
                :body          body})
              {:resumed (:name s) :queued? true :via :substrate})
          (do (body)
              {:resumed (:name s) :queued? false :unaccounted? true :via :substrate})))
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
      ;; Through the executor, so this turn takes a slot like every other agent
      ;; launch. It used to run on a bare `future` beside it, which meant the cap
      ;; bounded the sessions nido had STARTED rather than the agents it was
      ;; running — and a workstream could hold as many concurrent claudes as
      ;; there were people clicking Reply.
      ;;
      ;; Keyed per turn, because a Run is spawned once and resumed any number of
      ;; times; keyed on the run-id alone submit!'s idempotence would swallow
      ;; every reply after the first.
      (let [turn (str (random-uuid))
            body #(run-turn! project ws-id (:name s) s run input)]
        (if (executor/driven?)
          (do (executor/submit-turn!
               {:run-id        (turn-key s run)
                :turn          turn
                :trigger       (:trigger run)
                :body          body})
              {:resumed (:name s) :queued? true})
          ;; Nobody ticks the executor in this process — a standalone `bb nido:ui`
          ;; rather than the daemon — so queueing would park the turn forever and
          ;; say nothing. Run it, and SAY that it was not slot-accounted rather
          ;; than letting the caller assume it was.
          (do (future (body))
              {:resumed (:name s) :queued? false :unaccounted? true})))))))
