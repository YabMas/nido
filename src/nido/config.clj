(ns nido.config
  (:require [babashka.fs :as fs]
            [nido.core :as core]
            [nido.io :as io]))

(defn config-file []
  (str (fs/path (core/nido-home) "config.edn")))

(defn projects-file []
  (str (fs/path (core/nido-home) "projects.edn")))

(defn read-config []
  (or (io/read-edn (config-file))
      {:default-providers [:claude-code]}))

(defn read-projects []
  (or (io/read-edn (projects-file)) {}))

(defn write-projects! [projects]
  (io/write-edn! (projects-file) projects))
