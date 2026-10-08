(ns nido.coordinator.lane.pickup
  "Pickup: turn a pasted Notion URL / page-id / BR-#### into the ticket ref, so a
   human can hand nido a backlog item to drive. Front-end over the :plan-bug leg."
  (:require
   [clojure.string :as str]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]
   [nido.notion.client :as client]
   [nido.notion.views :as views]))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  extract-page-id
  "The page id of a Notion URL / uuid, returned dashed; nil if none. See
   `client/extract-page-id`."
  [s]
  (client/extract-page-id s))

(defn- normalise [page]
  (let [n (client/normalise-page page)]
    {:id      (:id n)
     :page-id (:page-id n)
     :url     (:url n)
     :title   (:title n)}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :NotionToken] :map]}
  resolve-ref
  "Resolve `input` (Notion URL / page-id / BR-####) → {:id :page-id :url :title},
   or {:error <kw>}. `token` is a Notion integration token."
  [project input token]
  (if (str/blank? token)
    {:error :no-token}
    (if-let [pid (extract-page-id input)]
      (let [page (client/retrieve-page pid token)]
        (if (:error page)
          {:error (:error page)}
          (let [ref (normalise page)]
            (if (str/blank? (:id ref))
              {:error :not-a-ticket}
              ref))))
      (if-let [[_ n] (re-matches #"(?i)(?:BR-)?(\d+)" (str/trim (str input)))]
        (try
          (let [{:keys [database]} (views/load-registry project)
                ds     (client/resolve-data-source-id database token)
                resp   (client/data-source-query ds token
                         {:filter {:property "ID" :unique_id {:equals (parse-long n)}}})
                page   (first (:results resp))]
            (cond
              (:error resp)  {:error (:error resp)}
              (some? page)   (normalise page)
              :else          {:error :not-found}))
          (catch Exception _e
            {:error :notion-error}))
        {:error :unrecognized-input}))))

;; The leg every pickup fires. Named so the UI can ask whether THIS trigger's
;; breaker is open before promising the user their ticket will be driven —
;; without either side hardcoding the answer and drifting from the other.
(def trigger :plan-bug)

(def ^:private driving-statuses
  "Ticket statuses that already mean nido is driving the ticket. Re-driving one
   must not regress :implementing back to :planning — pickup is idempotent by
   design (paste the same URL twice, get the same session)."
  #{:planning :implementing})

(defn- claim!
  "Mark the ticket as work in flight, the claim promote! makes when it hands the
   same :plan-bug leg a triaged ticket — minus the triage gate, which is exactly
   what pickup exists to bypass.

   Load-bearing, not bookkeeping. The board reads this status: without it
   session/derive-stage sees no status at all and projects the ticket into the
   :triage band (an un-triaged report), so a ticket you explicitly asked nido to
   drive shows up in the Intake queue rather than under Active. Worse, the run
   ends the same way — review/run-state-from-ticket maps a status-less exit to
   :done instead of parking the provisioned impl session at :awaiting-review, so
   the leg reads as finished the moment it is launched.

   Leaves an already-driving ticket alone; otherwise opens (or refreshes) the
   record so the drive has one even when the ticket was never triaged in nido."
  [project {:keys [id page-id url title]}]
  (when-not (contains? driving-statuses (tickets/status project id))
    (tickets/open! project id {:notion-page-id page-id :url url :title title
                               :opened-by :pickup})
    (tickets/set-status! project id :planning)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :NotionToken] :map]}
  pickup!
  "Resolve `input` and, on success, claim the ticket as in-flight and enqueue the
   :plan-bug envelope to drive it (the daemon find-or-creates the workstream by its
   Notion ref → the shared Phase-B ledger). Reports whether an existing workstream
   ledger will be continued (`:continuing?` + `:ws-id`) so the caller can say which
   path the daemon will take.
   Returns {:decision :driving :ref … :continuing? … :ws-id … :queued …} or
   {:decision :unresolved :error …}.

   The payload carries the page id under BOTH keys on purpose: notify/on-plan-spawn!
   reads :notion-page-id, spawn/external-ref reads :page-id. Emitting only the
   former left a freshly-minted workstream ref without a page id, and a Notion ref
   with no page id can never be matched against the board's page cache — so the
   workstream stayed blind to its own ticket's Notion status for good."
  [project input token]
  (let [r (resolve-ref project input token)]
    (if (:error r)
      {:decision :unresolved :error (:error r)}
      (let [existing (ws/find-by-ref-id project (:id r))]
        (claim! project r)
        {:decision    :driving
         :ref         r
         :continuing? (some? existing)
         :ws-id       (:id existing)
         :queued      (queue/enqueue!
                        {:target  {:project (keyword (name project)) :trigger trigger}
                         :payload {:id (:id r) :page-id (:page-id r)
                                   :notion-page-id (:page-id r)
                                   :url (:url r) :title (:title r)}})}))))
