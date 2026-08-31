(ns nido.platform.project
  (:require [babashka.fs :as fs]
            [nido.platform.config :as config]
            [nido.platform.core :as core]))

(defn ^{:malli/schema [:=> [:cat :string :string] :map]}
  add!
  "Register a project. Creates project definition dir under ~/.nido/projects/<name>/."
  [name directory]
  (let [directory (str (fs/absolutize (fs/path directory)))
        entry {:directory directory}]
    (when-not (fs/exists? directory)
      (throw (ex-info "Project directory does not exist" {:directory directory})))
    (config/update-projects! #(assoc % name entry))
    ;; Create project definitions dir
    (let [project-dir (str (fs/path (core/nido-home) "projects" name))]
      (fs/create-dirs project-dir))
    (core/log-step (str "Added project '" name "' -> " directory))
    entry))

(defn ^{:malli/schema [:=> [:cat] :map]}
  list-projects
  "Return the projects map."
  []
  (config/read-projects))

(defn ^{:malli/schema [:=> [:cat :string] :boolean]}
  remove!
  "Unregister a project. Does not delete definitions."
  [name]
  (if (contains? (config/read-projects) name)
    (do
      (config/update-projects! #(dissoc % name))
      (core/log-step (str "Removed project '" name "'"))
      true)
    (do
      (core/log-step (str "Project '" name "' not found"))
      false)))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :map]]}
  get-project
  "Get a project entry by name, or nil."
  [name]
  (get (config/read-projects) name))
