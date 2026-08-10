(ns nido.io
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]))

(defn read-edn
  "Read an EDN file, returning nil if it doesn't exist."
  [path]
  (when (fs/exists? path)
    (edn/read-string (slurp path))))

(defn write-edn!
  "Atomically write EDN to path: writes to a unique temp file then atomically
  renames it into place. Creates parent dirs as needed. Protects readers from
  observing torn writes — the file is always either the previous good content or
  the new good content, never partial. Using a unique temp name per call makes
  concurrent writes to the same path safe from rename-races."
  [path data]
  (when-let [parent (fs/parent path)]
    (fs/create-dirs parent))
  (let [path-s (str path)
        tmp    (str path-s "." (java.util.UUID/randomUUID) ".tmp")]
    (spit tmp (str (pr-str data) "\n"))
    (fs/move tmp path-s {:replace-existing true})))

(defn read-json
  "Read a JSON file into keyword-keyed maps, returning nil if it doesn't exist.
  Throws on malformed JSON — callers that read files they don't own should
  guard."
  [path]
  (when (fs/exists? path)
    (json/parse-string (slurp (str path)) keyword)))

(defn write-json!
  "Write data as JSON to path, creating parent dirs."
  [path data]
  (when-let [parent (fs/parent path)]
    (fs/create-dirs parent))
  (spit path (json/generate-string data {:pretty true})))

(defn read-text
  "Read a text file, returning nil if it doesn't exist."
  [path]
  (when (fs/exists? path)
    (slurp path)))

(defn write-text!
  "Write text to path, creating parent dirs."
  [path text]
  (when-let [parent (fs/parent path)]
    (fs/create-dirs parent))
  (spit path text))
