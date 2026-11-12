(ns nido.coordinator.lane.pickup
  "Pickup: turn a pasted Notion URL / page-id / BR-#### into the ticket ref, so a
   human can hand nido a backlog item to drive, and start it (`lane.start`)."
  (:require
   [clojure.string :as str]
   [nido.coordinator.lane.spawn :as spawn]
   [nido.coordinator.lane.start :as start]
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :map]}
  claim-and-drive!
  "Start the resolved ticket `r` ({:id :page-id :url :title}) as a person's: open (or refresh)
   its ticket record so it has one even when it was never triaged in nido, find or mint its
   workstream by its Notion ref, and hand that to `start/start-ticket!` — its workstream
   activated, its impl session brought up. Reports whether an existing workstream is continued.

   Returns {:decision :driving :ref … :continuing? … :ws-id … :session …}, or {:decision
   :unresolved :error <the start's refusal> :ref …}.

   Checks no gate: a caller reaching this has already decided the ticket may be driven, and
   bypassing triage is what pickup exists for."
  [project {:keys [id page-id url title] :as r}]
  (let [existing (ws/find-by-ref-id project id)]
    (tickets/open! project id {:notion-page-id page-id :url url :title title :opened-by :pickup})
    (let [w   (or existing (spawn/ensure-workstream! project {:id id :page-id page-id :url url :title title}))
          res (start/start-ticket! project {:ws-id (:id w)
                                            :ref {:adapter :notion :id id :page-id page-id
                                                  :url url :title title}})]
      (if (= :started (:decision res))
        {:decision    :driving
         :ref         r
         :continuing? (some? existing)
         :ws-id       (:ws-id res)
         :session     (:session res)}
        {:decision :unresolved :error (:decision res) :ref r}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :NotionToken] :map]}
  pickup!
  "Resolve `input` and, on success, claim-and-drive! it. Returns claim-and-drive!'s
   {:decision :driving …}, or {:decision :unresolved :error …} — :awaiting-gate when an
   unaccepted Slack proposal holds the ticket: its technical analysis waits in the gate, and
   only accepting it there starts the drive."
  [project input token]
  (let [r (resolve-ref project input token)]
    (cond
      (:error r)
      {:decision :unresolved :error (:error r)}

      (tickets/held-by-gate? project (:id r))
      {:decision :unresolved :error :awaiting-gate :ref r}

      :else
      (claim-and-drive! project r))))
