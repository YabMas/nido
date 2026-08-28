(ns nido.notion.views
  "Per-project Notion view registry. The Notion REST API does not expose
   view filter definitions; this registry encodes them so triggers can
   refer to a view by keyword and the source applies the right filter.

   Registry path: ~/.nido/projects/<project>/notion-views.edn

   Shape:
     {:database \"<notion-db-id>\"
      :views {:<view-kw> {:filter <notion-filter-map>}
              ...}}

   Filters are the literal body Notion expects under the \"filter\" key in
   a data-source query. No translation layer."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.state :as cstate]
   [nido.platform.io :as io]))

(defn- registry-path [project]
  (str (fs/path (cstate/nido-root) "projects" (name project) "notion-views.edn")))

(defn load-registry [project]
  (let [path (registry-path project)]
    (when-not (fs/exists? path)
      (throw (ex-info (str "Notion views registry missing for project " project
                           ". Create " path " or remove the Notion source from triggers.")
                      {:project project :path path})))
    (io/read-edn path)))

(defn board-views
  "The set of view keywords that feed the board (`:board-views` in the registry),
   or nil when unset. nil means every watched view feeds the board (back-compat)."
  [project]
  (let [path (registry-path project)]
    (when (fs/exists? path)
      (some-> (io/read-edn path) :board-views set))))

(defn board-poll
  "Poll interval for board-views nido polls on its own behalf (`:board-poll` in
   the registry, default 5m). These exist to keep the board's read of Notion
   fresh, not to catch arriving work, so they are deliberately slower than an
   intake source."
  [project]
  (let [path (registry-path project)]
    (or (when (fs/exists? path) (:board-poll (io/read-edn path)))
        "5m")))

(defn resolve-view
  "Returns {:database <id> :filter <map>} for the given (project, view-kw).
   Throws on missing registry or unknown view."
  [project view-kw]
  (let [{:keys [database views]} (load-registry project)]
    (or (when-let [v (get views view-kw)]
          {:database database :filter (:filter v)})
        (throw (ex-info (str "Unknown Notion view " view-kw " for project " project)
                        {:project project :view view-kw :known (keys views)})))))

(defn facet-properties
  "The project's configured facet property display-names (the registry's
   :facets), or [] when the registry or key is absent. Unlike load-registry,
   this never throws — facets are an optional organizational layer."
  [project]
  (try
    (vec (:facets (load-registry project)))
    (catch Exception _ [])))
