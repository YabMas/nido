(ns nido.coordinator.facets
  "Classification facets: durable Notion classifiers (App Domain, Type) stored
   on a workstream so the board can slice an origin into composable sub-queues.
   See spec docs/superpowers/specs/2026-06-24-classification-facet-sub-queues-design.md."
  (:require
   [nido.notion.client :as notion]))

(defn select-facets
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
