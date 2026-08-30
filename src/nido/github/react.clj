(ns nido.github.react
  "Pure helpers for the GitHub-merge Notion reaction.")

(defn ^{:malli/schema [:=> [:cat :any :string] :any]}
  people-without
  "Notion people-property value with `user-id` removed, preserving any other
   people. `people-prop` is the page's current property value (or nil).
   Returns {:people [{:id <id>} ...]} suitable for update-page-properties!."
  [people-prop user-id]
  {:people (->> (:people people-prop)
                (keep :id)
                (remove #(= % user-id))
                (mapv (fn [id] {:id id})))})
