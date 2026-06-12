(ns nido.coordinator.notify
  "Best-effort outbound notifications on Run lifecycle events.

   Currently one event: a plan Run spawning updates its ticket's Notion
   properties per the Run's snapshotted :on-promote config — flips the status
   (promote → \"In progress\"), sets the Ball Holder, and adds people to
   Participants. Every path is best-effort — any failure logs a warning and
   returns nil so it can never strand the planning Run."
  (:require
   [nido.notion.client :as notion]))

;; Notion property names on brian's Task Database. Person identities come from
;; config (:on-promote), but the property names that own them are fixed for
;; this DB, so they live here rather than in every trigger.
(def ^:private ball-holder-property "Ball Holder")
(def ^:private participants-property "Participants")

(defn- warn [msg]
  (binding [*err* *err*]
    (.println ^java.io.PrintWriter *err* (str "WARN: " msg))))

(defn- people-ids
  "Notion user ids already present in a people-type property value."
  [prop]
  (keep :id (:people prop)))

(defn- merged-participants
  "Read the page's current Participants and return the people-property value
   that adds `add-ids` without dropping anyone already there. Returns nil (skip
   the write) if the page can't be read, so we never clobber the existing list."
  [page-id add-ids token run]
  (let [page (notion/retrieve-page page-id token)]
    (if (:error page)
      (do (warn (str "notify: couldn't read Participants for " (:id run)
                     " — " (pr-str (:error page)) "; skipping participants"))
          nil)
      (let [existing (people-ids (get-in page [:properties (keyword participants-property)]))
            merged   (distinct (concat existing add-ids))]
        {:people (mapv (fn [id] {:id id}) merged)}))))

(defn on-plan-spawn!
  "Update the ticket's Notion properties when its plan Run spawns. Reads
   {:notion-status <s> :property <s?> :ball-holder <user-id?>
    :add-participants [<user-id> ...]?} from (:on-promote run) and the page id
   from (-> run :event-payload :notion-page-id). No-op when the page id or all
   write directives are absent. Status/Ball Holder replace; Participants is
   additive (merged into whoever's already on the ticket)."
  [run]
  (let [{:keys [notion-status property ball-holder add-participants]} (:on-promote run)
        page-id (some-> run :event-payload :notion-page-id)]
    (when (and page-id (or notion-status ball-holder (seq add-participants)))
      (try
        (if-let [token (notion/keychain-token)]
          (let [props (cond-> {}
                        notion-status (assoc (or property "Status") {:status {:name notion-status}})
                        ball-holder   (assoc ball-holder-property {:people [{:id ball-holder}]}))
                props (if-let [parts (and (seq add-participants)
                                          (merged-participants page-id add-participants token run))]
                        (assoc props participants-property parts)
                        props)
                res   (when (seq props)
                        (notion/update-page-properties! page-id props token))]
            (when (:error res)
              (warn (str "notify: Notion property write failed for " (:id run)
                         " — " (pr-str res)))))
          (warn (str "notify: no Notion token; skipping property write for " (:id run))))
        (catch Throwable t
          (warn (str "notify: Notion property write threw for " (:id run)
                     " — " (.getMessage t)))))
      nil)))
