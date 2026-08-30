(ns nido.coordinator.lane.ship
  "Merge lane — the daemon side of `nido ship`. A :ship envelope advances the
   workstream to :shipping and submits a serial :merge Run that drives the branch
   home with /drive-home headless in the EXISTING session-home. Outcome is read
   from the workstream ledger fingerprint (see drive-home/SKILL.md §7 / halt).
   Spec: docs/superpowers/specs/2026-06-30-local-merge-queue-design.md."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.status-file :as status-file]
   [nido.coordinator.record.workstream :as ws]
   [nido.session.state :as session-state])
  (:import [java.util UUID]))

(def merge-budget
  "Runaway-only wall-clock backstop for a merge Run. A brian CI cycle is
   ~20-30m and drive-home may run two (mechanical fix → re-CI), so real work
   stays well under this; the ceiling exists only to reap a genuinely wedged
   drive-home (the merge lane is cap-1, so a stuck one blocks the queue until
   this fires). SIGTERM→SIGKILL-bounded."
  "8h")

(defn- ws-br
  "BR-#### for a workstream from its :notion external ref, or nil (scratch)."
  [w]
  (some #(when (= :notion (:adapter %)) (:id %)) (:external-refs w)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] :Run]}
  create-merge-run!
  "Build + persist a :queued :merge Run reusing the existing session-home.
   No new worktree/session is created — drive-home-blocking! launches into the
   session named `session-name`."
  [project ws-id session-name]
  (let [w      (ws/read-ws project ws-id)
        br     (ws-br w)
        suf    (subs (str (UUID/randomUUID)) 0 8)
        date   (subs (clock/now-iso) 0 10)
        run-id (str date "-" (name project) "-merge-" suf)
        run    {:id                run-id
                :project           project
                :trigger           :merge
                :source            {:type :ship}
                :event-payload     (cond-> {} (and br (not (str/blank? br))) (assoc :id br))
                :skill             :drive-home
                :first-message     "/drive-home"
                :agent             :claude
                :session-name      session-name
                :workstream-id     ws-id
                :claude-session-id nil
                :limits            {:budget merge-budget :max-failures 3}
                :priority          0
                :session-profile   :full
                ;; What the daemon selects this Run's body by. It used to be
                ;; recognised by its :trigger, which meant the one lane that
                ;; needed a different body was named twice — here and in the
                ;; daemon's branch.
                :mode              :merge
                :uncapped?         true
                :state             :queued
                :state-history     [{:at (clock/now-iso) :state :queued}]
                :artifacts         []
                :error             nil}]
    (fs/create-dirs (cstate/run-dir run-id))
    (fs/create-dirs (cstate/run-artifacts-dir run-id))
    (runs/write-run! run)))

(def ^:private ship-blocking-states
  "Merge-Run states that block a re-ship: actively in flight. A :queued run does
   NOT block (pending pool); a parked :awaiting-review run does NOT block — that
   is exactly the blocked branch the human is re-shipping after a fix."
  #{:preprocessing :running})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :boolean]}
  merge-run-in-flight?
  "True if a :merge Run actively in flight already owns this workstream.
   :awaiting-review (blocked/parked) does NOT count — the user must be able
   to re-ship after fixing a blocker."
  [project ws-id]
  (->> (runs/list-run-ids)
       (keep runs/read-run)
       (some #(and (= :merge (:trigger %))
                   (= ws-id (:workstream-id %))
                   (= (name project) (name (:project %)))
                   (contains? ship-blocking-states (:state %))))
       boolean))

(defn- record-ship!
  "Append the :ship-submitted event marking this shipment's start. Best-effort —
   a ledger failure must not cost the ship — but it runs BEFORE executor/submit!
   for a reason beyond the timeline: classify-outcome fingerprints the ledger's
   LAST entry, so this is what stops a re-ship after a halt from inheriting the
   previous attempt's :implementation-completed and reading as merged."
  [project ws-id session run-id]
  (try
    (ws/append-entry! project ws-id {:kind :ship-submitted :session session :run-id run-id}
                      (pr-str {:format :ship-submitted :session session}))
    (catch Throwable t
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: ship — ledger append failed for " ws-id " — " (.getMessage t)))))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :any]]}
  handle-ship!
  "Process a :ship envelope. Idempotent: no-op (nil) if a merge Run is already
   in flight for this workstream. Otherwise advance the workstream to :shipping,
   create the merge Run, mark the shipment on the ledger, and submit it to the
   serial :merge lane."
  [{:keys [project session ws-id]}]
  (let [project (keyword project)
        ws-id   (or ws-id (session/workstream-id-for project session))]
    (cond
      (nil? ws-id)
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: ship — no workstream for " (name project) "/" session))
        nil)

      (merge-run-in-flight? project ws-id)
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "INFO: ship — merge already in flight for " (name project) "/" ws-id "; skip"))
        nil)

      :else
      (do
        (ws/advance-stage! project ws-id :shipping)
        (let [run (create-merge-run! project ws-id session)]
          (record-ship! project ws-id session (:id run))
          (executor/submit! (:id run) (:priority run) true :merge 1)
          run)))))

(defn ^{:malli/schema [:=> [:cat] :map]}
  merge-lane-summary
  "Counts for the coordinator status line: {:driving n :queued n :blocked n}
   over all :merge Runs (running/preprocessing, queued, awaiting-review)."
  []
  (->> (runs/list-run-ids)
       (keep runs/read-run)
       (filter #(= :merge (:trigger %)))
       (reduce (fn [m r]
                 (case (:state r)
                   (:running :preprocessing) (update m :driving inc)
                   :queued                   (update m :queued inc)
                   :awaiting-review          (update m :blocked inc)
                   m))
               {:driving 0 :queued 0 :blocked 0})))

(defn- agent-no-op?
  "Exit 0 with zero turns = the agent did nothing (e.g. \"Unknown command\")."
  [{:keys [exit-code num-turns]}]
  (and (= 0 exit-code) (some? num-turns) (zero? num-turns)))

(defn- latest-ledger-kind
  "The :kind of the workstream ledger's most recent entry (resolved by the
   BR-#### ref), or nil."
  [project br]
  (when (and br (not (str/blank? br)))
    (:kind (last (:entries (ws/find-by-ref-id project br))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :any :RunId :any] :keyword]}
  classify-outcome
  "Decide a merge Run's outcome. See Interfaces for precedence."
  [project br run-id result]
  (cond
    (:spawn-error result)  :blocked
    (:timed-out? result)   :blocked
    (agent-no-op? result)  :blocked
    :else
    (case (latest-ledger-kind project br)
      :implementation-completed :awaiting-merge
      :blocker                  :blocked
      ;; No progress fingerprint — including the :ship-submitted this run's own
      ;; handle-ship! wrote, which is what the ledger reads as when drive-home
      ;; halts without filing anything. Fall back to run-status, else fail-safe.
      (case (:phase (status-file/read-status run-id))
        :complete :awaiting-merge
        :blocked))))

(defn ^{:malli/schema [:=> [:cat :RunId] :any]}
  drive-home-blocking!
  "Executor on-spawn body for a :merge Run. Runs /drive-home headless in the
   EXISTING session-home (cwd = session-home, where bb nido:run + composed
   .claude/skills resolve), then classifies the outcome:
     :awaiting-merge → :done   (PR on GitHub's queue; lane slot freed)
     :blocked        → :awaiting-review (session parked + :error; gate inbox)
   Never tears down the session — the human owns it."
  [run-id]
  (runs/transition! run-id :running)                     ; mirrors session phase → :running
  (let [run0 (runs/read-run run-id)
        sid  (str (UUID/randomUUID))
        run  (runs/write-run! (assoc run0 :claude-session-id sid))   ; persist before launch; not mirrored to session
        project      (:project run)
        ws-id        (:workstream-id run)
        session-name (:session-name run)
        br           (some-> run :event-payload :id)
        home         (session-state/session-home-dir (name project) session-name)
        result (if-not (fs/exists? home)
                 {:spawn-error true :detail (str "session-home absent: " home)}
                 (try
                   (let [lc (runs/launch-context run)]
                     (agent/launch! {:run-id            run-id
                                     :cwd               home          ; session-home, NOT worktree
                                     :first-message     "/drive-home"
                                     :system-prompt     (str (:briefing lc) "\n\n" (:run-paths lc))
                                     :mcp-config        (:mcp-config lc)
                                     :add-dirs          (:add-dirs lc)
                                     :budget            (-> run :limits :budget)
                                     :claude-session-id sid}))
                   (catch Throwable t {:spawn-error true :detail (.getMessage t)})))
        outcome (classify-outcome project br run-id result)]
    (case outcome
      :awaiting-merge
      (runs/transition! run-id :done)                    ; mirrors session phase → :done

      :blocked
      (do
        (runs/transition! run-id :awaiting-review)       ; mirrors session phase → :parked
        (let [note (cond
                     (:spawn-error result) (str "drive-home could not start: " (:detail result))
                     (:timed-out? result)  (str "drive-home exceeded its " (-> run :limits :budget) " budget")
                     :else                 "drive-home halted — fix the blocker in the worktree, then `nido ship` again")]
          (try
            (session/set-error! project ws-id session-name
                                {:at (clock/now-iso) :reason :ship-blocked :message note})
            (catch Throwable _ nil)))))                  ; best-effort (human/non-autonomous session)
    outcome))
