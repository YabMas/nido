(ns nido.project
  (:require [babashka.fs :as fs]
            [nido.config :as config]
            [nido.core :as core]))

(defn add!
  "Register a project. Creates project definition dir under ~/.nido/projects/<name>/."
  [name directory]
  (let [directory (str (fs/absolutize (fs/path directory)))
        projects (config/read-projects)
        entry {:directory directory}]
    (when-not (fs/exists? directory)
      (throw (ex-info "Project directory does not exist" {:directory directory})))
    (config/write-projects! (assoc projects name entry))
    ;; Create project definitions dir
    (let [project-dir (str (fs/path (core/nido-home) "projects" name))]
      (fs/create-dirs project-dir))
    (core/log-step (str "Added project '" name "' -> " directory))
    entry))

(defn list-projects
  "Return the projects map."
  []
  (config/read-projects))

(defn remove!
  "Unregister a project. Does not delete definitions."
  [name]
  (let [projects (config/read-projects)]
    (if (contains? projects name)
      (do
        (config/write-projects! (dissoc projects name))
        (core/log-step (str "Removed project '" name "'"))
        true)
      (do
        (core/log-step (str "Project '" name "' not found"))
        false))))

(defn get-project
  "Get a project entry by name, or nil."
  [name]
  (get (config/read-projects) name))
