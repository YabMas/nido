(ns nido.coordinator.ship
  "Merge lane — the daemon side of `nido ship`. A :ship envelope advances the
   workstream to :shipping and submits a serial :merge Run that drives the branch
   home with /drive-home headless in the EXISTING session-home. Outcome is read
   from the ticket-ledger fingerprint (see drive-home/SKILL.md §5d / halt).
   Spec: docs/superpowers/specs/2026-06-30-local-merge-queue-design.md."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.status-file :as status-file]
   [nido.coordinator.workstream :as ws])
  (:import [java.util UUID]))

(def merge-budget
  "Generous wall-clock for a merge Run: a brian CI cycle is ~20-30m and
   drive-home may run two (mechanical fix → re-CI). SIGTERM→SIGKILL-bounded."
  "90m")

(defn- ws-br
  "BR-#### for a workstream from its :notion external ref, or nil (scratch)."
  [w]
  (some #(when (= :notion (:adapter %)) (:id %)) (:external-refs w)))

(defn create-merge-run!
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
                :uncapped?         true
                :state             :queued
                :state-history     [{:at (clock/now-iso) :state :queued}]
                :artifacts         []
                :error             nil}]
    (fs/create-dirs (cstate/run-dir run-id))
    (fs/create-dirs (cstate/run-artifacts-dir run-id))
    (runs/write-run! run)))

(defn merge-run-in-flight?
  "True if a :merge Run in an in-progress state already owns this workstream."
  [project ws-id]
  (->> (runs/list-run-ids)
       (keep runs/read-run)
       (some #(and (= :merge (:trigger %))
                   (= ws-id (:workstream-id %))
                   (= (name project) (name (:project %)))
                   (contains? runs/in-progress-states (:state %))))
       boolean))

(defn handle-ship!
  "Process a :ship envelope. Idempotent: no-op (nil) if a merge Run is already
   in flight for this workstream. Otherwise advance the workstream to :shipping,
   create the merge Run, and submit it to the serial :merge lane."
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
          (executor/submit! (:id run) (:priority run) true :merge 1)
          run)))))

(defn- agent-no-op?
  "Exit 0 with zero turns = the agent did nothing (e.g. \"Unknown command\")."
  [{:keys [exit-code num-turns]}]
  (and (= 0 exit-code) (some? num-turns) (zero? num-turns)))

(defn- latest-ledger-kind
  "The :kind of the ticket ledger's most recent entry, or nil."
  [project br]
  (when (and br (not (str/blank? br)))
    (:kind (last (:entries (tickets/read-meta project br))))))

(defn classify-outcome
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
      ;; no/ambiguous ledger fingerprint → run-status fallback, else fail-safe
      (case (:phase (status-file/read-status run-id))
        :complete :awaiting-merge
        :blocked))))
