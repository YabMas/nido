(ns nido.notion.views-check
  "Validate a notion-views.edn registry against the live Notion database.

   Walks every view filter, collects the set of referenced property names and
   the set of referenced select/status values, then cross-checks against what
   the database actually exposes. Reports any drift; returns a structured result
   so callers can decide how to surface it."
  (:require
   [clojure.set :as set]
   [nido.notion.client :as client]
   [nido.notion.views :as views]))

(defn- properties-in-filter
  "Walk a Notion filter map, return the set of all referenced property names."
  [f]
  (cond
    (nil? f)         #{}
    (sequential? f)  (apply set/union (map properties-in-filter f))
    (map? f)         (let [child (apply set/union (map properties-in-filter (vals f)))]
                       (if-let [p (:property f)]
                         (conj child p)
                         child))
    :else            #{}))

(defn- select-values-in-filter
  "Walk a Notion filter map, return [[property-name value] ...] for every
   :equals / :does_not_equal under a typed sub-key (select, status, multi_select)."
  [f]
  (cond
    (nil? f) []
    (sequential? f) (mapcat select-values-in-filter f)
    (map? f) (let [direct (when-let [p (:property f)]
                            (for [[_sub-k sub-v] (dissoc f :property)
                                  :when (map? sub-v)
                                  v [(:equals sub-v) (:does_not_equal sub-v)]
                                  :when (string? v)]
                              [p v]))]
               (concat direct (mapcat select-values-in-filter (vals f))))
    :else []))

(defn- prop-options
  "Notion property options can live under :select / :status / :multi_select."
  [prop-def]
  (or (get-in prop-def [:select :options])
      (get-in prop-def [:status :options])
      (get-in prop-def [:multi_select :options])))

(defn- db-prop
  "Lookup a property by its Notion display-name on a parsed database.
   Cheshire keywordises JSON keys, so a property named \"Type\" arrives as
   the keyword :Type. Filters use the string name, so we look up by both."
  [db-props name]
  (or (get db-props name)
      (get db-props (keyword name))))

(defn- db-prop-names
  "All property display-names on the database, as strings — regardless of
   whether cheshire parsed them as keywords or kept them as strings."
  [db-props]
  (set (map #(if (keyword? %) (clojure.core/name %) %) (keys db-props))))

(defn check-registry
  "Validates the notion-views.edn registry for `project` against the live
   Notion data source. (In Notion API 2025-09-03 the property schema lives
   on the data source, not the database.) Returns {:status :ok} or
   {:status :error :errors [{:message ...} ...]}."
  [project token]
  (let [{:keys [database views]} (views/load-registry project)
        ds-id (client/resolve-data-source-id database token)
        ds    (client/retrieve-data-source ds-id token)
        db-props (:properties ds)
        all-filter-props (apply set/union
                                (for [[_ v] views]
                                  (properties-in-filter (:filter v))))
        all-select-pairs (apply concat
                                (for [[_ v] views]
                                  (select-values-in-filter (:filter v))))
        missing-props (remove (db-prop-names db-props) all-filter-props)
        invalid-options
        (for [[prop val] all-select-pairs
              :let [opts (prop-options (db-prop db-props prop))]
              :when (and opts (not (some #(= val (:name %)) opts)))]
          [prop val])
        errors (concat
                 (for [p missing-props]
                   {:message (str "Property '" p "' not found on database " database)})
                 (for [[p v] invalid-options]
                   {:message (str "Property '" p "' has no option '" v "' on database " database)}))]
    (if (seq errors)
      {:status :error :errors (vec errors)}
      {:status :ok})))
