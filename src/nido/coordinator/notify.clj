(ns nido.coordinator.notify
  "Best-effort outbound notifications on Run lifecycle events.

   Currently one event: a plan Run spawning flips its ticket's Notion status
   per the Run's snapshotted :on-promote config (promote → \"In progress\").
   Every path is best-effort — any failure logs a warning and returns nil so it
   can never strand the planning Run."
  (:require
   [nido.notion.client :as notion]))

(defn- warn [msg]
  (binding [*err* *err*]
    (.println ^java.io.PrintWriter *err* (str "WARN: " msg))))

(defn on-plan-spawn!
  "Flip the ticket's Notion status when its plan Run spawns. Reads
   {:notion-status <s> :property <s?>} from (:on-promote run) and the page id
   from (-> run :event-payload :notion-page-id). No-op when either is absent."
  [run]
  (let [{:keys [notion-status property]} (:on-promote run)
        page-id (some-> run :event-payload :notion-page-id)]
    (when (and notion-status page-id)
      (try
        (if-let [token (notion/keychain-token)]
          (let [res (notion/update-page-status! page-id (or property "Status")
                                                notion-status token)]
            (when (:error res)
              (warn (str "notify: Notion status write failed for " (:id run)
                         " — " (pr-str res)))))
          (warn (str "notify: no Notion token; skipping status write for " (:id run))))
        (catch Throwable t
          (warn (str "notify: Notion status write threw for " (:id run)
                     " — " (.getMessage t)))))
      nil)))
