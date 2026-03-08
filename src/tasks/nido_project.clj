(ns tasks.nido-project
  (:require [nido.core :as core]
            [nido.project :as project]))

(defn init
  "Create the ~/.nido/ skeleton directory structure."
  [& _args]
  (core/ensure-nido-home!))

(defn add
  "Register a project: <name> <directory>"
  [& args]
  (when (< (count args) 2)
    (throw (ex-info "Usage: nido:project:add <name> <directory>"
                    {:args args})))
  (let [name (first args)
        directory (second args)]
    (project/add! name directory)))

(defn list-cmd
  "List registered projects."
  [& _args]
  (let [projects (project/list-projects)]
    (if (seq projects)
      (doseq [[name entry] (sort-by key projects)]
        (println (str "  " name))
        (println (str "    directory: " (:directory entry))))
      (println "No projects registered."))))

(defn remove-cmd
  "Unregister a project: <name>"
  [& args]
  (when (empty? args)
    (throw (ex-info "Usage: nido:project:remove <name>" {:args args})))
  (project/remove! (first args)))
