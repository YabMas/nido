(ns nido.coordinator.notion-sync
  "Coordinator housekeeping: reconcile a project's open workstreams against their
   Notion tickets. Read-only against Notion (Notion is source of truth); mutates
   only nido's own workstream records. Sibling of nido.coordinator.github-merge —
   a throttled, breaker-guarded poll that spawns NO agent session.

   Gated by ~/.nido/projects/<project>/notion-sync.edn (absent ⇒ off)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.sources.state :as sstate]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.io :as io]
   [nido.notion.client :as notion]))

(def default-terminal
  "Notion statuses that terminally close a workstream :done."
  #{"Done" "Not Done"})

(def default-status->stage
  "Non-terminal Notion status → nido spine stage. Review maps to the :done STAGE
   (workstream stays open, so a bounce back to In progress re-syncs)."
  {"Needs verification" :triage
   "Not Started"        :ready
   "On Hold"            :ready
   "In progress"        :in-progress
   "Code Review"        :in-progress
   "Review"             :done})

(defn sync-action
  "Pure decision. Given a ticket's `status` (string|nil), `ballholder-ids` (set of
   Notion user-id strings, possibly empty), `me` (my user-id), the `terminal` set,
   the `status->stage` map, and the workstream's `current-stage`, return the action:
     :close-done                 — terminal status (ownership irrelevant)
     :close-dropped              — non-terminal, claimed by someone ≠ me
     [:advance <stage>]          — mine/unassigned, mapped stage differs from current
     :noop                       — unknown status, or already at the mapped stage
   Precedence is first-match: terminal, then other-claim, then the stage map."
  [{:keys [status ballholder-ids me terminal status->stage current-stage]}]
  (cond
    (contains? terminal status)
    :close-done

    (and (seq ballholder-ids) (not (contains? ballholder-ids me)))
    :close-dropped

    :else
    (let [target (get status->stage status)]
      (cond
        (nil? target)             :noop     ; unknown/absent status
        (= target current-stage)  :noop     ; idempotent
        :else                     [:advance target]))))

(defn- warn [msg]
  (binding [*err* *err*]
    (.println ^java.io.PrintWriter *err* (str "WARN: " msg))))

(defn- config-path [project]
  (str (fs/path (cstate/nido-root) "projects" (name project) "notion-sync.edn")))

(defn load-config
  "Read + default-merge notion-sync.edn for a project. Returns nil when the file
   is absent (feature off) or when :me is missing (logs a WARN — the poller cannot
   distinguish 'claimed by me' from 'claimed by someone else' without it)."
  [project]
  (let [p (config-path project)]
    (when (fs/exists? p)
      (let [raw (io/read-edn p)]
        (if (str/blank? (str (:me raw)))
          (do (warn (str "notion-sync: notion-sync.edn for " project
                         " is missing :me (my Notion user id); poller disabled for this project"))
              nil)
          (merge {:poll          "10m"
                  :terminal      default-terminal
                  :status->stage default-status->stage
                  :dry-run?      false}
                 raw))))))
