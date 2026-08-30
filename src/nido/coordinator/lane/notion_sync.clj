(ns nido.coordinator.lane.notion-sync
  "Coordinator housekeeping: reconcile a project's open workstreams against their
   Notion tickets. Read-only against Notion (Notion is source of truth); mutates
   only nido's own workstream records. Sibling of nido.coordinator.lane.github-merge —
   a throttled, breaker-guarded poll that spawns NO agent session.

   Gated by ~/.nido/projects/<project>/notion-sync.edn (absent ⇒ off)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.source.state :as sstate]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.io :as io]
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

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  sync-action
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

;; Projects already warned about a missing :me — warn once per daemon lifetime,
;; not on every tick (load-config runs each tick, upstream of the poll throttle).
(defonce ^:private !warned-missing-me (atom #{}))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :map]}
  load-config
  "Read + default-merge notion-sync.edn for a project. Returns nil when the file
   is absent (feature off) or when :me is missing (logs a WARN — the poller cannot
   distinguish 'claimed by me' from 'claimed by someone else' without it)."
  [project]
  (let [p (config-path project)]
    (when (fs/exists? p)
      (let [raw (io/read-edn p)]
        (if (str/blank? (str (:me raw)))
          (do (when-not (contains? @!warned-missing-me project)
                (swap! !warned-missing-me conj project)
                (warn (str "notion-sync: notion-sync.edn for " project
                           " is missing :me (my Notion user id); poller disabled for this project")))
              nil)
          (merge {:poll          "10m"
                  :terminal      default-terminal
                  :status->stage default-status->stage
                  :dry-run?      false}
                 raw))))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :string]]}
  page-status
  "Status option name from a retrieved page, or nil."
  [page]
  (get-in page [:properties :Status :status :name]))

(defn- ballholder-people [page]
  (get-in page [:properties (keyword "Ball Holder") :people]))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  page-ballholder-ids
  "Set of Notion user-id strings on the Ball Holder property (empty when unassigned)."
  [page]
  (into #{} (keep :id) (ballholder-people page)))

(defn ^{:malli/schema [:=> [:cat :map :any] [:maybe :string]]}
  page-ballholder-name
  "Display name for the first ball holder that isn't `me` — their :name, else :id,
   else \"someone\"."
  [page me]
  (let [other (first (remove #(= me (:id %)) (ballholder-people page)))]
    (or (:name other) (:id other) "someone")))

(defn ^{:malli/schema [:=> [:cat :Workstream] [:maybe :string]]}
  notion-page-id
  "Page-id of the workstream's :notion external-ref, or nil."
  [ws]
  (some #(when (= :notion (:adapter %)) (:page-id %)) (:external-refs ws)))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :Workstream]]}
  open-notion-workstreams
  "Open (:closed nil) workstreams carrying a :notion page-id."
  [project]
  (->> (ws/list-ids project)
       (keep #(ws/read-ws project %))
       (filter #(and (nil? (:closed %)) (notion-page-id %)))))

(defn- state-key [project] (str "notion-sync-" (name project)))

;; Half-open breaker (mirrors github-merge): once tripped, suppress polling for
;; this long, then allow one probe poll.
(def ^:private breaker-cooldown-s (* 30 60))

(defn- cooldown-elapsed?
  "True if a tripped breaker is due for a half-open probe. Permissive when no
   :breaker-opened-at is recorded, so the poller never stays dark forever."
  [state]
  (if-let [opened (:breaker-opened-at state)]
    (try
      (>= (.toSeconds (java.time.Duration/between
                        (java.time.Instant/parse opened)
                        (java.time.Instant/parse (clock/now-iso))))
          breaker-cooldown-s)
      (catch Exception _ true))
    true))

(defn- action-desc
  "Human/ledger description of a resolved action for workstream `w`."
  [action w status page me]
  (case (if (keyword? action) action (first action))
    :close-done    (str "notion-sync: closed :done — Notion status " status)
    :close-dropped (str "notion-sync: dropped — claimed by " (page-ballholder-name page me))
    :advance       (format "notion-sync: %s → %s — Notion status %s"
                           (name (:stage w)) (name (second action)) status)))

(defn- apply-action!
  "Effect one resolved action. dry? logs the would-do without mutating; otherwise
   mutates the workstream and appends an explanatory ledger entry. :noop → nil."
  [project w action status page me dry?]
  (when (not= action :noop)
    (let [desc (action-desc action w status page me)]
      (if dry?
        (println (str "[dry-run] " (:id w) " · " desc))
        (do
          (case (if (keyword? action) action (first action))
            :close-done    (ws/close! project (:id w) :done)
            :close-dropped (ws/close! project (:id w) :dropped)
            :advance       (ws/advance-stage! project (:id w) (second action)))
          (ws/append-entry! project (:id w) {:kind :note} desc)
          (println (str "notion-sync: " (:id w) " · " desc)))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :any]}
  poll-and-react!
  "One reconcile poll for a project. For each open Notion-linked workstream, read
   its ticket's Status + Ball Holder and apply sync-action. Read-only against
   Notion; fail-safe per workstream. A token-wide :auth failure opens the
   half-open breaker (skips polling until cooldown). Persists state under
   (state-key project) with :type :notion-sync so it shows under coordinator:status
   Sources:. Never throws."
  [project {:keys [me terminal status->stage dry-run?] :as _cfg}]
  (let [k     (state-key project)
        prior (sstate/read-state k)]
    (when-not (and (= :open (:breaker prior)) (not (cooldown-elapsed? prior)))
      (if-let [token (notion/keychain-token)]
        (let [auth-failed? (atom false)]
          (doseq [w (open-notion-workstreams project)
                  :while (not @auth-failed?)]
            (let [page (notion/retrieve-page (notion-page-id w) token)]
              (cond
                (= :auth (:error page)) (reset! auth-failed? true)
                (:error page)           (warn (str "notion-sync: read failed for " (:id w)
                                                   " (" (notion-page-id w) ") — " (:error page)
                                                   "; skipping"))
                :else
                (let [status (page-status page)
                      action (sync-action {:status         status
                                           :ballholder-ids (page-ballholder-ids page)
                                           :me             me
                                           :terminal       terminal
                                           :status->stage  status->stage
                                           :current-stage  (:stage w)})]
                  (apply-action! project w action status page me dry-run?)))))
          (sstate/write-state!
            k (if @auth-failed?
                (merge (or prior {:type :notion-sync :project project})
                       {:breaker :open :breaker-opened-at (clock/now-iso)
                        :last-poll-result {:error :auth} :last-polled-at (clock/now-iso)})
                {:type :notion-sync :project project
                 :breaker nil :last-polled-at (clock/now-iso)})))
        (warn (str "notion-sync: no Notion token; skipping poll for " project))))
    nil))
