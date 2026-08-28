(ns tasks.nido-facets
  "bb task entry points for classification-facet maintenance."
  (:require
   [nido.coordinator.facets :as facets]
   [nido.platform.task-args :as task-args]))

(defn- project-kw [opts] (keyword (or (:project opts) "brian")))

(defn refresh-cmd
  "bb nido:facets:refresh :project <p> [:ws <ws-id>]
   Re-read Notion and rewrite classification facets. With :ws, one workstream;
   otherwise every open Notion-ref workstream in the project. On-demand only —
   never run automatically."
  [& args]
  (let [[_ o]   (task-args/split-args args)
        project (project-kw o)]
    (if-let [ws-id (:ws o)]
      (do (facets/refresh-ws! project (str ws-id))
          (println "refreshed facets for" (str ws-id)))
      (let [n (facets/refresh-project! project)]
        (println "refreshed facets for" n "workstream(s) in" (name project))))))
