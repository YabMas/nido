(ns tasks.nido-findings
  "bb task entry points for the staging findings round."
  (:require
   [clojure.pprint :as pprint]
   [nido.coordinator.findings :as findings]
   [nido.io :as io]
   [nido.task-args :as task-args]))

(defn- project-kw [opts] (keyword (or (:project opts) "brian")))

(defn file-cmd
  "bb nido:findings:file :project <p> :ws <ws-id> :file <edn-path> [:session <s>]
   The EDN file is {:items [{:summary :severity (:id) (:area)} …] :staging-ref? :note?}."
  [& args]
  (let [[_ o] (task-args/split-args args)
        data  (io/read-edn (str (:file o)))
        res   (findings/file! (project-kw o) (str (:ws o))
                              (assoc data :session (:session o)))]
    (println "filed findings round" (:round res) "→ reopened" (:ws o))
    (pprint/pprint res)))

(defn resolve-cmd
  "bb nido:findings:resolve :project <p> :ws <ws-id> :items [f1 f3] :by <ref>"
  [& args]
  (let [[_ o] (task-args/split-args args)
        ids   (mapv name (:items o))
        t     (findings/resolve! (project-kw o) (str (:ws o)) ids (str (:by o)))]
    (println "resolved" (vec ids) "· open remaining:" (count (:open t)))))
