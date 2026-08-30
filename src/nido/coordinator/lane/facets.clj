(ns nido.coordinator.lane.facets
  "Classification facets: durable Notion classifiers (App Domain, Type) stored
   on a workstream so the board can slice an origin into composable sub-queues.
   See spec docs/superpowers/specs/2026-06-24-classification-facet-sub-queues-design.md."
  (:require
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.view.workstreams :as wsv]
   [nido.notion.client :as notion]
   [nido.notion.views :as views]))

(defn ^{:malli/schema [:=> [:cat :any :map] :map]}
  select-facets
  "Build a :facets map from a normalised page/payload, keeping only the
   configured properties. `facet-props` is a vector of Notion display-names
   (e.g. [\"App Domain\" \"Type\"]); each is kebab-keyed (matching normalise-page)
   and read from `normalised`. Absent / nil / empty-collection values are
   dropped, so a ticket with no value for a property simply omits that key."
  [facet-props normalised]
  (reduce (fn [acc prop]
            (let [k (notion/normalise-property-name prop)
                  v (get normalised k)]
              (if (or (nil? v) (and (coll? v) (empty? v)))
                acc
                (assoc acc k v))))
          {}
          facet-props))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:? :NotionToken]] :any]}
  refresh-ws!
  "Re-read the workstream's :notion page and rewrite its :facets from the
   current property values. No-op (nil) for a ref-less / non-Notion workstream
   or a page-read error. 3-arg form injects the token; 2-arg reads the keychain."
  ([project ws-id] (refresh-ws! project ws-id (notion/keychain-token)))
  ([project ws-id token]
   (when-let [w (ws/read-ws project ws-id)]
     (when-let [ref (wsv/notion-ref w)]
       (let [page (notion/retrieve-page (:page-id ref) token)]
         (when-not (:error page)
           (let [facets (select-facets (views/facet-properties project)
                                       (notion/normalise-page page))]
             (ws/set-facets! project ws-id facets))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] :any]}
  refresh-for-ticket!
  "Resolve the workstream carrying Notion ref `br-id` and refresh its facets.
   No-op when no such workstream (e.g. a Slack-sourced ticket)."
  [project br-id]
  (when-let [w (ws/find-by-ref project :notion br-id)]
    (refresh-ws! project (:id w))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  refresh-project!
  "Refresh facets for every open Notion-ref workstream in `project`. Returns the
   count refreshed. Reads the keychain token once."
  [project]
  (let [token (notion/keychain-token)]
    (->> (ws/list-ids project)
         (keep #(ws/read-ws project %))
         (filter #(and (nil? (:closed %)) (wsv/notion-ref %)))
         (map #(refresh-ws! project (:id %) token))
         (filter some?)
         count)))
