(ns nido.platform.config
  (:require [babashka.fs :as fs]
            [nido.platform.core :as core]
            [nido.platform.io :as io]))

(defn ^{:malli/schema [:=> [:cat] :string]}
  projects-file []
  (str (fs/path (core/nido-home) "projects.edn")))

(defn ^{:malli/schema [:=> [:cat] :map]}
  read-projects []
  (or (io/read-edn (projects-file)) {}))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  write-projects! [projects]
  (io/write-edn! (projects-file) projects))
