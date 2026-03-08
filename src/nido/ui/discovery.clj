(ns nido.ui.discovery
  "Discovers VSDD runs and other artifacts from registered projects."
  (:require [babashka.fs :as fs]
            [nido.io :as io]
            [nido.project :as project]
            [nido.vsdd.manifest :as manifest]))

(defn list-vsdd-runs
  "List all VSDD runs for a project, sorted by most recent first.
   Returns [{:run-id ... :manifest ...}]."
  [project-dir]
  (let [vsdd-dir (str (fs/path project-dir ".vsdd"))]
    (when (fs/exists? vsdd-dir)
      (->> (fs/list-dir vsdd-dir)
           (filter fs/directory?)
           (keep (fn [run-dir]
                   (let [manifest-file (str (fs/path run-dir "manifest.edn"))
                         raw (io/read-edn manifest-file)]
                     (when raw
                       {:run-id (str (fs/file-name run-dir))
                        :manifest (manifest/check-liveness raw)}))))
           (sort-by :run-id #(compare %2 %1))
           vec))))

(defn load-vsdd-run
  "Load a specific VSDD run manifest for a project."
  [project-dir run-id]
  (let [manifest-file (str (fs/path project-dir ".vsdd" run-id "manifest.edn"))
        raw (io/read-edn manifest-file)]
    (when raw
      (manifest/check-liveness raw))))

(defn load-critic-report
  "Load a critic report EDN file."
  [project-dir run-id module-slug iteration]
  (let [report-file (str (fs/path project-dir ".vsdd" run-id
                                  module-slug
                                  (str "critic-report-" iteration ".edn")))]
    (io/read-edn report-file)))

(defn load-impl-report
  "Load an implementer completion report EDN file."
  [project-dir run-id module-slug iteration]
  (let [report-file (str (fs/path project-dir ".vsdd" run-id
                                  module-slug
                                  (str "impl-report-" iteration ".edn")))]
    (io/read-edn report-file)))

(defn project-context
  "Build context for a project by name. Returns {:name :directory :entry} or nil."
  [project-name]
  (when-let [entry (project/get-project project-name)]
    {:name project-name
     :directory (:directory entry)
     :entry entry}))
