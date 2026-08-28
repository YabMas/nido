(ns nido.platform.config
  (:require [babashka.fs :as fs]
            [nido.platform.core :as core]
            [nido.platform.io :as io]))

(defn projects-file []
  (str (fs/path (core/nido-home) "projects.edn")))

(defn read-projects []
  (or (io/read-edn (projects-file)) {}))

(defn write-projects! [projects]
  (io/write-edn! (projects-file) projects))
