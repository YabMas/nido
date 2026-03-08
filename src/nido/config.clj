(ns nido.config
  (:require [babashka.fs :as fs]
            [nido.core :as core]
            [nido.io :as io]))

(defn projects-file []
  (str (fs/path (core/nido-home) "projects.edn")))

(defn read-projects []
  (or (io/read-edn (projects-file)) {}))

(defn write-projects! [projects]
  (io/write-edn! (projects-file) projects))
