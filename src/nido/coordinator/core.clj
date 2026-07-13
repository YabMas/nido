(ns nido.coordinator.core
  "Coordinator main loop. Foreground only in Stage 1a.

   See spec §The coordinator daemon."
  (:refer-clojure :exclude [run!])
  (:require
   [babashka.fs :as fs]
   [clojure.set :as set]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.anomaly :as anomaly]
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.events :as events]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.heartbeat :as heartbeat]
   [nido.coordinator.notify :as notify]
   [nido.coordinator.pid :as pid]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.reconcile :as reconcile]
   [nido.coordinator.review :as review]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.coordinator.spawn :as spawn]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.sources.notion :as nsource]
   [nido.coordinator.sources.slack :as slack-source]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.status-file :as status-file]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.github-merge :as github-merge]
   [nido.coordinator.github-issue-intake :as github-issue-intake]
   [nido.coordinator.notion-sync :as notion-sync]
   [nido.coordinator.intake :as intake]
   [nido.coordinator.ship :as ship]
   [nido.github.config :as gh-config]
   [nido.coordinator.triggers :as triggers]
   [nido.core :as nido-core]
   [nido.project :as project]
   [nido.reclaim :as reclaim]
   [nido.session.profiles :as profiles]
   [nido.ui.server :as ui-server]))

(def ^:private defaults
  {:poll-ms             1000
   :global-parallel-cap 2
   :executor            {:shutdown-grace-ms 5000}
   ;; Periodic disk hygiene: reclaim orphaned per-session state dirs (PGDATA
   ;; clones left behind by kill -9 / host crash / a rolled-back spawn). Runs
   ;; once per :reclaim-interval-ms (and on the first tick after startup, since
   ;; the throttle clock starts at 0). :reclaim-min-age-ms is the grace window
   ;; that protects a session still booting — its dir exists before the registry
   ;; entry is written, so a young orphan may actually be a live boot in flight.
   :reclaim-interval-ms (* 60 60 1000)   ; hourly
   :reclaim-min-age-ms  (* 60 60 1000)   ; only orphans idle ≥ 1h
   ;; Queue hygiene: drop un-promoted Slack inbox entries after 3 days. Swept at
   ;; most once per :inbox-sweep-interval-ms (and on the first tick after start,
   ;; since the throttle clock starts at 0).
   :inbox-expiry-ms         (* 3 24 60 60 1000)  ; 3 days
   :inbox-sweep-interval-ms (* 30 60 1000)        ; sweep at most every 30 min
   :system-prompt       "You are running inside a nido auto-triggered session. The user is not present yet."
   ;; Pre-orientation burst for a promoted ticket (the /continue-ticket leg).
   ;; Unlike triage, the human WILL own this exact session — they'll resume this
   ;; conversation — so this prompt is about parking cleanly for them, not about
   ;; artifacts/_run-status.edn (plan-bug derives Run state from the ticket).
   :plan-system-prompt  "You are pre-orienting a nido impl session unattended: the human who owns this session is not here yet but will resume THIS conversation shortly. Run /continue-ticket to pick up where the pipeline left off, then do clear, low-risk implementation work autonomously as the skill directs. The moment you reach something that needs a human decision — a product/design call, genuine ambiguity, or a risky/destructive change — STOP and leave a concise summary of what you did, where things stand, and exactly what you need from the user; do not guess. Do not touch Notion (nido already set the ticket's status when this session was provisioned)."
   ;; GitHub-issue promote leg. Like :plan-system-prompt but there's no ledger to
   ;; continue from — the first message IS the issue body, so no /continue-ticket
   ;; and no Notion.
   :plan-issue-system-prompt  "You are pre-orienting a nido impl session unattended: the human who owns this session is not here yet but will resume THIS conversation shortly. Your first message is the GitHub issue to implement. Do clear, low-risk implementation work autonomously toward a draft PR. The moment you reach something that needs a human decision — a product/design call, genuine ambiguity, or a risky/destructive change — STOP and leave a concise summary of what you did, where things stand, and exactly what you need; do not guess."
   :dashboard           {:enabled? true :port 8800}})

(def ^:private anomaly-thresholds
  ;; spawn-threshold is a RUNAWAY rate brake, not a concurrency cap (that's
  ;; :global-parallel-cap + per-trigger :max-in-flight). Reconcile-mode triage
  ;; sources (triggers.edn :reconcile? true) re-emit their whole live backlog
  ;; each poll, so a legitimate catch-up briefly spawns faster than the old
  ;; diff-only trickle. 10/min still trips a true runaway (e.g. a self-firing
  ;; misconfig) while tolerating a bounded reconcile catch-up.
  {:spawn-window-ms 60000  :spawn-threshold 10
   :fail-window-ms  300000 :fail-threshold 3})

(defonce ^:private !detector (atom (anomaly/empty-detector)))

(defonce ^:private !source-instances (atom {}))

;; Last wall-clock ms an auto-reclaim sweep ran. Starts at 0 so the first tick
;; after a daemon (re)start sweeps immediately, then throttles to the interval.
(defonce ^:private !last-reclaim-ms (atom 0))

;; Last wall-clock ms an inbox-expiry sweep ran. Starts at 0 so the first tick
;; after a (re)start sweeps immediately, then throttles to the interval.
(defonce ^:private !last-inbox-sweep-ms (atom 0))

;; Per-project last GitHub-merge poll wall-clock ms. Starts empty so the first
;; tick after (re)start polls each configured project immediately, then throttles
;; to that project's github.edn :poll interval.
(defonce ^:private !last-github-poll-ms (atom {}))

(defonce ^:private !last-github-issue-poll-ms (atom {}))

;; Per-project last Notion-sync poll wall-clock ms. Empty ⇒ first tick after a
;; (re)start reconciles each configured project immediately, then throttles to
;; that project's notion-sync.edn :poll interval.
(defonce ^:private !last-notion-sync-ms (atom {}))

;; Resolved dashboard port for the running daemon (nil when disabled). Recorded
;; in the heartbeat so `status` can report + probe the right port.
(defonce ^:private !dashboard-port (atom nil))

(defn dashboard-config
  "Resolve {:enabled? :port} for the in-process dashboard from run! opts over
   `defaults`. `:no-dashboard true` disables it; `:dashboard-port` overrides the
   port."
  [{:keys [dashboard-port no-dashboard]}]
  (let [d (:dashboard defaults)]
    {:enabled? (boolean (and (:enabled? d) (not no-dashboard)))
     :port     (or dashboard-port (:port d))}))

(defn dashboard-status-line
  "Format the `status` Dashboard line for a resolved port + reachability."
  [port reachable?]
  (format "Dashboard:   http://localhost:%s (%s)"
          port (if reachable? "reachable" "not reachable")))

(defn- maybe-reclaim!
  "Throttled disk-hygiene sweep: at most once per :reclaim-interval-ms, delete
   orphaned per-session state dirs older than :reclaim-min-age-ms. Never throws
   into the tick loop — a reclaim failure must not stall the coordinator."
  [now-ms]
  (when (>= (- now-ms @!last-reclaim-ms) (:reclaim-interval-ms defaults))
    (reset! !last-reclaim-ms now-ms)
    (try
      (let [deleted (reclaim/reclaim-orphans! {:min-age-ms (:reclaim-min-age-ms defaults)
                                               :now-ms     now-ms})]
        (when (seq deleted)
          (println (str "nido coordinator: auto-reclaimed " (count deleted)
                        " orphan state dir(s): "
                        (str/join ", " (map first deleted))))))
      (catch Throwable t
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "WARN: auto-reclaim threw — " (ex-message t))))))))

(defn- registered-projects []
  ;; nido.project/list-projects returns {<string-name> {:directory ...}}.
  ;; Envelopes (and load-all-triggers' returned map) use keyword project names,
  ;; so coerce here to a vector of keywords.
  (mapv keyword (keys (project/list-projects))))

(defn- load-all-triggers
  "Returns {:brian [triggers] :foo [triggers]}."
  []
  (->> (registered-projects)
       (into {} (map (fn [p] [p (triggers/load-for-project p)])))))

(defn- maybe-expire-inbox!
  "Throttled queue hygiene: at most once per :inbox-sweep-interval-ms, close
   (:dropped) any still-open :incoming workstream older than :inbox-expiry-ms across
   all registered projects. Never throws into the tick loop."
  [now-ms]
  (when (>= (- now-ms @!last-inbox-sweep-ms) (:inbox-sweep-interval-ms defaults))
    (reset! !last-inbox-sweep-ms now-ms)
    (try
      (doseq [project (registered-projects)]
        (let [expired (intake/expire-stale! project (:inbox-expiry-ms defaults) now-ms)]
          (when (seq expired)
            (println (str "nido coordinator: expired " (count expired)
                          " stale inbox entry(ies) in " (name project) ": "
                          (str/join ", " expired))))))
      (catch Throwable t
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "WARN: inbox expiry threw — " (ex-message t))))))))

(defn- parse-duration-ms [s]
  ;; Minimal duration parser: "30s" "5m" "1h"
  (let [[_ n u] (re-matches #"(\d+)\s*([smh])" s)]
    (when n
      (* (parse-long n)
         (case u "s" 1000 "m" 60000 "h" 3600000)))))

(defn- maybe-poll-github-merges!
  "Throttled GitHub-merge poll, per project with a github.edn. At most once per
   that project's :poll interval (default 5m). Never throws into the tick loop."
  [now-ms]
  (doseq [project (registered-projects)
          :let [cfg (try (gh-config/load-config project)
                         (catch Throwable t
                           (binding [*err* *err*]
                             (.println ^java.io.PrintWriter *err*
                                       (str "WARN: github.edn load failed for " project
                                            " — " (ex-message t))))
                           nil))]
          :when cfg
          :let [interval (or (parse-duration-ms (or (:poll cfg) "5m")) 300000)
                last-ms  (get @!last-github-poll-ms project)]
          ;; Never polled ⇒ due immediately (first tick after restart). Otherwise
          ;; throttle to the project's :poll interval.
          :when (or (nil? last-ms) (>= (- now-ms last-ms) interval))]
    (swap! !last-github-poll-ms assoc project now-ms)
    (try
      (github-merge/poll-and-react! project cfg)
      (catch Throwable t
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "WARN: github-merge poll threw for " project " — " (ex-message t))))))))

(defn- maybe-poll-github-issues!
  "Throttled GitHub-issue intake, per project whose github.edn carries an
   :issues block (and :enabled is not false). At most once per the project's
   :poll interval (default 5m). Never throws into the tick loop."
  [now-ms]
  (doseq [project (registered-projects)
          :let [cfg (try (gh-config/load-config project)
                         (catch Throwable t
                           (binding [*err* *err*]
                             (.println ^java.io.PrintWriter *err*
                                       (str "WARN: github.edn load failed for " project
                                            " — " (ex-message t))))
                           nil))]
          :when (and cfg (:issues cfg) (not (false? (:enabled (:issues cfg)))))
          :let [interval (or (parse-duration-ms (or (:poll cfg) "5m")) 300000)
                last-ms  (get @!last-github-issue-poll-ms project)]
          ;; Never polled ⇒ due immediately (first tick after restart). Otherwise
          ;; throttle to the project's :poll interval.
          :when (or (nil? last-ms) (>= (- now-ms last-ms) interval))]
    (swap! !last-github-issue-poll-ms assoc project now-ms)
    (try
      (github-issue-intake/poll-and-reconcile! project cfg)
      (catch Throwable t
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "WARN: github-issue poll threw for " project " — " (ex-message t))))))))

(defn- maybe-poll-notion-sync!
  "Throttled Notion→workstream reconcile, per project with a notion-sync.edn. At
   most once per that config's :poll interval (default 10m). Never throws into the
   tick loop."
  [now-ms]
  (doseq [project (registered-projects)
          :let [cfg (try (notion-sync/load-config project)
                         (catch Throwable t
                           (binding [*err* *err*]
                             (.println ^java.io.PrintWriter *err*
                                       (str "WARN: notion-sync.edn load failed for " project
                                            " — " (ex-message t))))
                           nil))]
          :when cfg
          :let [interval (or (parse-duration-ms (or (:poll cfg) "10m")) 600000)
                last-ms  (get @!last-notion-sync-ms project)]
          :when (or (nil? last-ms) (>= (- now-ms last-ms) interval))]
    (swap! !last-notion-sync-ms assoc project now-ms)
    (try
      (notion-sync/poll-and-react! project cfg)
      (catch Throwable t
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "WARN: notion-sync poll threw for " project " — " (ex-message t))))))))

(defn- discover-source-configs
  "Walk loaded triggers and return distinct source-configs whose type is
   registered. Filters out :manual (the queue dir handles that).
   Merges :project from the trigger's owning project into each source-config
   so plugins that resolve per-project registry files (e.g. notion-views.edn)
   know which project they are serving."
  [triggers-by-project]
  (->> (for [[project triggers] triggers-by-project, t triggers
             :let [src (assoc (:source t) :project project)]
             :when (and (not= :manual (:type src))
                        (sources/lookup (:type src)))]
         src)
       distinct))

(defn- start-source! [source-config]
  (let [hash    (sources/config-hash source-config)
        plugin  (sources/lookup (:type source-config))
        handle  ((:start! plugin) source-config sources/emit-broadcast!)
        poll-ms (or (parse-duration-ms (str (:poll source-config))) 300000)]
    (swap! !source-instances assoc hash
           (assoc handle
                  :source-config  source-config
                  :poll-ms        poll-ms
                  :last-polled-ms 0))))

(defn- stop-source! [config-hash]
  (when-let [{:keys [stop!]} (get @!source-instances config-hash)]
    (try (stop!) (catch Exception _ nil)))
  (swap! !source-instances dissoc config-hash))

(defn- reconcile-sources!
  "Start sources that should be running, stop sources that no longer have
   a referencing trigger."
  [triggers-by-project]
  (let [desired-configs (discover-source-configs triggers-by-project)
        desired         (set (map sources/config-hash desired-configs))
        current         (set (keys @!source-instances))]
    (doseq [hash (set/difference current desired)]
      (stop-source! hash))
    (doseq [sc desired-configs
            :when (not (contains? current (sources/config-hash sc)))]
      (start-source! sc))))

(defn- agent-no-op?
  "True when claude exited cleanly (exit 0) but its final `result` event reports
   zero turns — the agent did literally no work. The canonical case is claude
   rejecting the launch with \"Unknown command: /<skill>\" (exit 0, is_error
   false, num_turns 0). Such a run must be :failed, not :done: a :done run
   silently frees its trigger's in-flight slot, so a whole backlog drains and
   the :max-in-flight cap becomes a no-op. Marking it :failed instead feeds the
   breaker, which trips after :max-failures and halts the cascade.

   Guards on (some? num-turns) so a launch! result that never observed a
   `result` event (num-turns nil — e.g. killed mid-stream) is NOT mistaken for
   a no-op; those fall through to the existing exit-code/timeout handling."
  [result]
  (and (= 0 (:exit-code result))            ; nil-safe: spawn-error result has no :exit-code
       (some? (:num-turns result))
       (zero? (:num-turns result))))

(defn- provision-blocked?
  "True when a provision-only (:plan-bug / :plan-github-issue) run's launch failed
   in a way that should PARK the workstream as a blocked gate — surfacing the
   blocker to the human — rather than mark the Run :failed. A :failed provision Run
   runs tickets/on-run-terminal!, which reverts the ticket :planning→:triaged and
   bounces the board back to :ready, SILENTLY hiding the failure (the canonical
   case: a shared-PG Flyway checksum mismatch blocks the session app boot, so the
   promote appears to undo itself minutes later). Parking mirrors the merge lane's
   :blocked handling (ship/drive-home-blocking!): the ticket stays :planning (board
   stays :in-progress), the session parks, and set-error! carries the blocker into
   the gate inbox. Scoped to provision-only runs so the triage path's breaker-
   engaging :failed handling (the '36 sessions' no-op guard) is untouched."
  [provision-only? result]
  (boolean
   (and provision-only?
        (or (:spawn-error result) (:timed-out? result) (agent-no-op? result)))))

(defn- skill-in-claude-dir?
  "True if `skill-name` resolves under a `.claude` dir as either a skill
   (skills/<name>/) or a slash command (commands/<name>.md)."
  [claude-dir skill-name]
  (or (fs/exists? (str (fs/path claude-dir "skills" skill-name)))
      (fs/exists? (str (fs/path claude-dir "commands" (str skill-name ".md"))))))

(defn- skill-resolvable?
  "True if the Run's skill is something claude can run in the spawned session,
   mirroring the launcher's composed `.claude` resolution order:
     - nido's native harness skills (nido/.claude/skills/<skill>) — injected into
       EVERY session's composed .claude/skills (compose-claude-dir!), so they
       resolve regardless of the target project's checked-out branch;
     - the user's ~/.claude;
     - for :symlink (:lite) profiles, the target checkout's .claude.

   The gate only bites a :symlink profile whose skill is nowhere above — exactly
   the '36 sessions' failure: a :lite triage run launched `/triage-bug` against a
   checkout whose branch no longer carried it, claude answered 'Unknown command',
   and the run silently completed. For non-symlink profiles whose skill isn't
   nido-native/user-global the worktree isn't built yet, so we can't cheaply
   check — fail OPEN. Any resolution error also fails open."
  [run]
  (try
    (let [skill-name  (name (:skill run))
          nido-claude (str (fs/path (nido-core/nido-source-dir) ".claude"))
          user-claude (str (fs/path (System/getProperty "user.home") ".claude"))]
      (or (skill-in-claude-dir? nido-claude skill-name)
          (skill-in-claude-dir? user-claude skill-name)
          (let [profile (profiles/resolve-profile (:project run) (:session-profile run))
                target  (-> profile :worktree :target)]
            (if (and (= :symlink (-> profile :worktree :strategy)) target)
              (skill-in-claude-dir? (str (fs/path target ".claude")) skill-name)
              true))))
    (catch Throwable _ true)))

(defn- issue-impl-brief
  "First message for a promoted GitHub issue's provision-only session: the issue
   itself as the brief (no slash command — there's no ledger to continue from)."
  [{:keys [title url body]}]
  (str "Implement this GitHub issue, then open a draft PR when the work is ready.\n\n"
       "# " title "\n" url "\n\n" body))

(defn- persist-claude-session-id!
  "Persist the captured claude conversation id onto run.edn AND mirror it onto
   the run's authoritative session, so resume can read it off either record.
   The session mirror is best-effort: a human session (no autonomy facet) or a
   missing session will throw from set-claude-session-id!, which is caught and
   logged to stderr — it never re-fails the run."
  [run session-id]
  (let [r (assoc run :claude-session-id session-id)]
    (runs/write-run! r)
    (when-let [ws-id (:workstream-id r)]
      (try (session/set-claude-session-id! (:project r) ws-id (:session-name r) session-id)
           (catch Exception e
             (binding [*err* *err*]
               (.println ^java.io.PrintWriter *err*
                         (str "nido coordinator: claude-session-id mirror failed for "
                              (:session-name r) " — " (ex-message e)))))))
    r))

(defn- run-blocking!
  "Drive a single :queued Run to terminal/awaiting-review state.
   Called inside an executor-spawned future (one per slot). The name
   reflects that this fn blocks its thread for the duration of the run;
   it is no longer called directly from process-envelope! (which now
   submits to the executor instead)."
  [run-id]
  (runs/transition! run-id :running)
  (let [;; Generate the claude session id up front and persist it to run.edn
        ;; BEFORE launch, so a restart mid-session (or any interruption) never
        ;; strands the Run without a resumable id — the resume shim reads this.
        ;; Passed to claude as --session-id so the transcript uses it.
        ;; :plan-bug is the "promote" leg: promote brings up the :full session,
        ;; flips Notion → "In progress", then launches /continue-ticket headlessly
        ;; so the session is already oriented (triage findings picked up, clear
        ;; low-risk work underway) and parked by the time the human arrives. The
        ;; human enters the session and the shim `--resume`s this same conversation.
        ;; The session-id is generated + persisted up front so the headless launch
        ;; uses `--session-id` and later entries `--resume` the same conversation.
        ;; plan-bug bypasses only the skill-resolvable? gate (/continue-ticket is a
        ;; harness-injected skill, always present regardless of checkout).
        ;; (`:plan-bug` is the internal trigger id; the command is /continue-ticket.)
        provision-only? (#{:plan-bug :plan-github-issue} (:skill (runs/read-run run-id)))
        session-id   (str (java.util.UUID/randomUUID))
        run          (persist-claude-session-id! (runs/read-run run-id) session-id)
        ;; Spawn the session + launch claude. If EITHER throws (e.g. session
        ;; build / worktree creation fails), don't let it escape — that would
        ;; leave the Run stuck :running forever (a zombie that permanently
        ;; consumes its trigger's in-flight slot, since nothing transitions it
        ;; terminal and the resolution sweep only handles :awaiting-review).
        ;; Catch and fall through to the :failed terminal path below.
        ;;
        ;; Claude is launched with the WORKTREE as cwd (session-home dissolution
        ;; step 2): nido context — briefing, postgres MCP, harness skills — is
        ;; injected via flags (--append-system-prompt / --mcp-config / --add-dir)
        ;; rather than read from the home. claude registers its transcript by cwd,
        ;; so new runs key to the worktree; a session parked before this change
        ;; carries a home-keyed transcript and must be re-opened in the terminal.
        ;; Pre-spawn gate: if the skill can't resolve in the target checkout
        ;; (a :lite session symlinked to a branch that no longer carries it),
        ;; fail fast WITHOUT building a session — no doomed spawn, and the
        ;; breaker still trips. See skill-resolvable?.
        result       (cond
                       ;; Promote leg: bring the :full session up, flip Notion,
                       ;; then launch /continue-ticket headlessly so the session is
                       ;; oriented + clear work underway by the time the human
                       ;; arrives (they `--resume` this conversation via the shim).
                       ;; next-state (see the cond below): the Notion leg derives
                       ;; from the resulting ticket status (:implementing → :done,
                       ;; slot freed; still :planning → :awaiting-review). The GitHub
                       ;; leg has no ticket, so it goes straight to :done. In both
                       ;; cases teardown is a no-op so the session survives for the human.
                       provision-only?
                       (try
                         (runs/spawn-session-for-run! run)
                         (notify/on-plan-spawn! run)        ; Notion nudge; no-op for the GitHub leg
                         ;; Notion leg → /continue-ticket (NOT the run's "/plan-bug …"
                         ;; first-message; see note above). GitHub leg → the issue body
                         ;; itself as the brief (no ledger to continue from).
                         (let [github? (= :plan-github-issue (:skill run))
                               lc      (runs/launch-context run)
                               base-sp (if github?
                                         (:plan-issue-system-prompt defaults)
                                         (:plan-system-prompt defaults))]
                           (agent/launch! {:run-id            run-id
                                           :cwd               (:cwd lc)
                                           :first-message     (if github?
                                                                (issue-impl-brief (:event-payload run))
                                                                "/continue-ticket")
                                           :system-prompt     (str base-sp "\n\n" (:briefing lc)
                                                                   "\n\n" (:run-paths lc))
                                           :mcp-config        (:mcp-config lc)
                                           :add-dirs          (:add-dirs lc)
                                           :budget            (-> run :limits :budget)
                                           :claude-session-id session-id}))
                         (catch Throwable t
                           {:spawn-error true :detail (.getMessage t)}))

                       (not (skill-resolvable? run))
                       {:skill-unavailable true :skill (:skill run)}

                       :else
                       (try
                         (runs/spawn-session-for-run! run)
                         (let [lc (runs/launch-context run)]
                           (agent/launch! {:run-id            run-id
                                           :cwd               (:cwd lc)
                                           :first-message     (:first-message run)
                                           :system-prompt     (str (:system-prompt defaults) "\n\n"
                                                                   (:briefing lc) "\n\n" (:run-paths lc))
                                           :mcp-config        (:mcp-config lc)
                                           :add-dirs          (:add-dirs lc)
                                           :budget            (-> run :limits :budget)
                                           :claude-session-id session-id}))
                         (catch Throwable t
                           {:spawn-error true :detail (.getMessage t)})))
        next-state (cond
                     ;; Provision-only failures park a BLOCKED gate instead of
                     ;; :failed — a :failed here reverts the ticket :planning→:triaged
                     ;; (bounces the board back to :ready) and hides the blocker.
                     ;; See provision-blocked?.
                     (provision-blocked? provision-only? result) :awaiting-review
                     (:skill-unavailable result) :failed
                     (:spawn-error result) :failed
                     (:timed-out? result) :failed
                     ;; Exit 0 but the agent did nothing (e.g. "Unknown command:
                     ;; /<skill>") — treat as failure so the breaker engages and
                     ;; the in-flight slot isn't silently freed (see agent-no-op?).
                     (agent-no-op? result) :failed
                     (zero? (:exit-code result))
                     (cond
                       ;; GitHub provision-only leg: no ticket to derive from. The
                       ;; burst parked the session for the human, so the run is :done
                       ;; (frees the trigger's slot) and the session OUTLIVES it (the
                       ;; teardown no-op in runs/teardown-session-for-run!).
                       (= :plan-github-issue (:skill run)) :done
                       (#{:triage-bug :plan-bug} (:skill run))
                       (review/run-state-from-ticket
                         (tickets/status (:project run) (some-> run :event-payload :id)))
                       :else
                       (status-file/derive-state-after-exit
                         (status-file/read-status run-id)))
                     :else :failed)]
    ;; (session-id was already persisted up front, above)
    (runs/transition! run-id next-state)
    (when (= :failed next-state)
      (let [r (runs/read-run run-id)]
        (runs/write-run! (assoc r :error (cond-> {:exit-code (:exit-code result)}
                                           (:spawn-error result)
                                           (assoc :reason :spawn-failed
                                                  :detail (:detail result))
                                           (:timed-out? result)
                                           (assoc :reason :timeout
                                                  :budget (-> r :limits :budget))
                                           (agent-no-op? result)
                                           (assoc :reason :agent-no-op
                                                  :detail (:result-text result))
                                           (:skill-unavailable result)
                                           (assoc :reason :skill-unavailable
                                                  :detail (str "skill /" (name (:skill result))
                                                               " did not resolve in the session checkout")))))))
    ;; Provision-only spawn/timeout/no-op parked as a BLOCKED gate: surface the
    ;; blocker on the session so the gate inbox shows WHY (mirrors the merge
    ;; lane), and leave the ticket :planning (board stays :in-progress) rather
    ;; than reverting to :ready. Best-effort — a missing/human session no-ops.
    (when (provision-blocked? provision-only? result)
      (let [note (cond
                   (:spawn-error result) (str "Promote could not start the session: "
                                              (:detail result))
                   (:timed-out? result)  (str "/continue-ticket exceeded its "
                                              (-> run :limits :budget) " budget")
                   :else                 "/continue-ticket did no work (unknown command?)")]
        (try
          (session/set-error! (:project run) (:workstream-id run) (:session-name run)
                              {:at (clock/now-iso) :reason :promote-blocked :message note})
          (catch Throwable _ nil))))
    ;; Keep the ticket record honest on terminal exit (spec §Lifecycle):
    ;; clears a stale :investigating after an abnormal exit, leaves completed
    ;; dispositions and parked :awaiting-input drafts alone.
    (tickets/on-run-terminal! (runs/read-run run-id) next-state)
    ;; Breaker update on terminal state. Default max-failures is 3; the
    ;; trigger's :limits.max-failures (snapshotted onto the Run at create
    ;; time) overrides this.
    (let [project      (:project run)
          trigger-name (:trigger run)
          max-failures (or (-> run :limits :max-failures) 3)]
      (case next-state
        :failed          (do (swap! !detector anomaly/record-failure (clock/now-iso))
                             (breakers/record-failure! project trigger-name max-failures))
        :done            (breakers/record-success! project trigger-name)
        :awaiting-review (breakers/record-success! project trigger-name)
        nil))
    ;; Reclaim the session once the Run is resolved-terminal. :awaiting-review
    ;; keeps its session up (the human's review surface); only terminal states
    ;; tear down. Without this every completed/failed Run leaks its session
    ;; (PG + JVM + ports + CLI list entry) — the cap is honored but sessions
    ;; pile up unboundedly.
    (when (contains? #{:done :failed :halted} next-state)
      (runs/teardown-session-for-run! run))))

(defn- process-envelope! [envelope triggers-by-project]
  (doseq [routed (events/route envelope triggers-by-project)]
    (cond
      (:error routed)
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: dropping envelope — " (pr-str routed))))

      (breakers/tripped? (:project routed) (-> routed :trigger :name))
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: trigger breaker open — skipping "
                       (name (:project routed)) "/"
                       (name (-> routed :trigger :name)))))

      ;; Dry-run short-circuit (spec §Dry-run mode): record the would-fire
      ;; as a terminal Run, but never spawn a session. Anomaly detector
      ;; still sees the spawn so a chatty mis-configured trigger trips it.
      ;; Breakers stay untouched — neither success nor failure of the skill.
      (-> routed :trigger :dry-run?)
      (let [run (runs/create-run! routed
                                  {:fired-at (clock/now-iso)
                                   :fired-by (System/getenv "USER")})]
        (swap! !detector anomaly/record-spawn (clock/now-iso))
        (runs/transition! (:id run) :dry-run-would-fire))

      ;; Queue-mode intake (spec §Intake → queue): a trigger with :intake :queue
      ;; parks the report as a session-less :incoming workstream for a human to
      ;; promote/dismiss, instead of auto-spawning. No run, no session, and no
      ;; anomaly-detector bump (nothing was spawned). Placed before the triage
      ;; gate so queue mode never consults ticket state.
      (= :queue (-> routed :trigger :intake))
      (intake/enqueue-inbox! routed)

      ;; Triage pre-spawn gate (spec §Coordinator pre-spawn gate): a triage
      ;; event whose ticket is already completed, or already owned by a live
      ;; session, is dropped without creating a Run. BR-#### rides in the
      ;; event-payload (:id) — no network. Non-triage triggers are unaffected.
      (and (= :triage-bug (-> routed :trigger :skill))
           (when-let [br (-> routed :payload :id)]
             (not= :spawn (tickets/gate-decision (:project routed) br))))
      (let [br       (-> routed :payload :id)
            decision (tickets/gate-decision (:project routed) br)]
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "INFO: triage gate skip — " (name (:project routed)) "/"
                         br " (" (name decision) ")"))))

      ;; Reconcile dedup (spec §Coordinator pre-spawn gate): a re-emit whose ref
      ;; already has an in-flight session for this trigger (queued/preprocessing/
      ;; running/parked) is dropped without minting a duplicate run. This closes
      ;; the :queued-pileup gap the ticket-status gate above misses — a merely-
      ;; :queued triage writes no ledger status, so without this a reconcile
      ;; source re-queues the same ticket every poll (thousands of phantom
      ;; :queued runs). No spawn ⇒ no anomaly bump.
      (spawn/ref-has-pending-session? routed)
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "INFO: reconcile dedup — " (name (:project routed)) "/"
                       (-> routed :payload :id) " already has an in-flight "
                       (name (-> routed :trigger :name)) " session")))

      :else
      (do
        (spawn/spawn-and-submit! routed {:fired-at (clock/now-iso)
                                         :fired-by (System/getenv "USER")})
        (swap! !detector anomaly/record-spawn (clock/now-iso))))))

(defn- dispatch-envelope!
  "Route one drained envelope. A :ship envelope (from `nido ship`) goes to the
   merge-lane handler; everything else is a source event for trigger-matching."
  [env triggers-by-project]
  (if (= :ship (:type env))
    (ship/handle-ship! env)
    (process-envelope! env triggers-by-project)))

(defn tick!
  "One iteration of the main loop. Public for testability."
  []
  (let [triggers-by-project (load-all-triggers)
        halt-info           (halt/read-halt-info)]
    (if halt-info
      (heartbeat/write! {:status       :halted
                         :halted-by    (:source halt-info)
                         :halt-note    (:note halt-info)
                         :slots-in-use 0
                         :dashboard-port @!dashboard-port})
      (do
        (heartbeat/write! {:status :running :slots-in-use 0 :dashboard-port @!dashboard-port})
        (reconcile-sources! triggers-by-project)
        ;; Drain queue first — this consumes envelopes emitted on the PREVIOUS
        ;; tick by source polls. Keeps each tick's unit of work small.
        (doseq [env (queue/drain!)]
          (dispatch-envelope! env triggers-by-project))
        ;; Reap finished executor futures and promote waiting Runs into
        ;; free slots. on-spawn dispatches :merge Runs to drive-home-blocking!
        ;; and everything else to run-blocking!.
        (review/sweep-resolved!)
        (let [on-spawn (fn [rid]
                         (if (= :merge (:trigger (runs/read-run rid)))
                           (ship/drive-home-blocking! rid)
                           (run-blocking! rid)))
              ;; :merge Runs reuse an existing session whose autonomy :trigger is
              ;; NOT :merge, so session-based gating can't see them — count :merge
              ;; from Run state instead (no double-count: no session carries :merge).
              in-flight (-> (reduce (fn [m p] (merge-with + m (session/gating-count-by-trigger p)))
                                    {} (registered-projects))
                            (assoc :merge (get (runs/in-progress-count-by-trigger) :merge 0)))]
          (executor/tick! on-spawn in-flight))
        ;; Then poll due sources. Their emissions land in the queue and
        ;; will be picked up next tick.
        (let [now-ms (System/currentTimeMillis)]
          (doseq [[hash inst] @!source-instances
                  :when (>= (- now-ms (:last-polled-ms inst)) (:poll-ms inst))]
            (try
              ((:poll! inst))
              (catch Exception e
                (binding [*err* *err*]
                  (.println ^java.io.PrintWriter *err*
                            (str "WARN: source " hash " poll! threw — " (ex-message e))))))
            (swap! !source-instances assoc-in [hash :last-polled-ms] now-ms))
          ;; Periodic disk hygiene (throttled to :reclaim-interval-ms).
          (maybe-reclaim! now-ms)
          (maybe-expire-inbox! now-ms)
          (maybe-poll-github-merges! now-ms)
          (maybe-poll-github-issues! now-ms)
          (maybe-poll-notion-sync! now-ms))
        ;; After draining + polling, check anomaly thresholds.
        (when-let [trip (anomaly/check @!detector anomaly-thresholds)]
          (halt/halt! {:source  :auto
                       :reason  (:trip trip)
                       :details trip
                       :note    (str "auto-halt: " (name (:trip trip))
                                     " count=" (:count trip))}))))))

(defn- install-shutdown-hook! []
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread.
      (fn []
        (try (ui-server/stop!) (catch Exception _ nil))
        (doseq [hash (keys @!source-instances)]
          (stop-source! hash))
        (try (heartbeat/write! {:status :stopped :slots-in-use 0})
             (catch Exception _ nil))
        (try (pid/delete!)
             (catch Exception _ nil))))))

(defn shutdown-grace-ms
  "Return the grace period (in ms) for daemon shutdown (SIGTERM to SIGKILL)."
  []
  (-> defaults :executor :shutdown-grace-ms))

(defn- resubmit-queued!
  "After reconcile, re-add surviving :queued runs to the executor queue so the
   in-memory queue rehydrates across a restart."
  [triggers-by-project]
  (let [cap-of (into {}
                     (for [[_ ts] triggers-by-project, t ts]
                       [(:name t) (:max-in-flight t)]))]
    (doseq [rid (runs/list-run-ids)
            :let [r (runs/read-run rid)]
            :when (and r (= :queued (:state r)))]
      (executor/submit! (:id r) (:priority r) (:uncapped? r)
                        (:trigger r) (get cap-of (:trigger r))))))

(defn run!
  "Start the foreground loop. Blocks until interrupted.
   Also installs the daemon lifecycle: writes coordinator.pid, runs the
   crash-recovery reconcile pass, starts the in-process dashboard, and
   registers a JVM shutdown hook."
  [& {:keys [poll-ms] :as opts :or {poll-ms (:poll-ms defaults)}}]
  (cstate/ensure-dirs!)
  (println "nido coordinator: starting (poll" poll-ms "ms)")
  (reconcile/reconcile!)
  (resubmit-queued! (load-all-triggers))
  (let [{:keys [enabled? port]} (dashboard-config opts)]
    (reset! !dashboard-port (when enabled? port))
    (when enabled?
      (try (ui-server/start! {:port port})
           (catch Throwable t
             (reset! !dashboard-port nil)
             (binding [*err* *err*]
               (.println ^java.io.PrintWriter *err*
                         (str "WARN: dashboard failed to start — " (ex-message t))))))))
  (pid/write! (long (.pid (java.lang.ProcessHandle/current))))
  (install-shutdown-hook!)
  (heartbeat/write! {:status :running :slots-in-use 0 :dashboard-port @!dashboard-port})
  (executor/configure! {:global-cap (:global-parallel-cap defaults)})
  (nsource/register!)                                 ; register Notion source plugin
  (slack-source/register!)                            ; register Slack source plugin
  (loop []
    (tick!)
    (Thread/sleep poll-ms)
    (recur)))
