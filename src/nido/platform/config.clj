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

(defn ^{:malli/schema [:=> [:cat [:=> [:cat :any] :any]] :any]}
  update-projects!
  "Apply `f` to the projects map and write the result, as one locked operation.
   How every mutation of the registry goes, so that a read and a write of it are
   never two separate steps a slow or abandoned process can be interrupted
   between."
  [f]
  (io/update-edn! (projects-file) (fn [m] (f (or m {})))))
