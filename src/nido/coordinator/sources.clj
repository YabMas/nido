(ns nido.coordinator.sources
  "Plugin registry for event sources. Sources register themselves at load
   time; the coordinator looks up by :type and calls :start! per distinct
   source-config. See spec §Plugin contract."
  (:require [clojure.string :as str])
  (:import (java.security MessageDigest)))

(defonce ^:private !registry (atom {}))

(defn register-source!
  "Register a source plugin. Idempotent (a re-registration replaces the
   previous entry — useful for REPL development)."
  [{:keys [type schema events start!] :as src}]
  (assert (keyword? type)        "source :type must be a keyword")
  (assert (some? schema)         "source :schema is required")
  (assert (some? events)         "source :events is required")
  (assert (fn? start!)           "source :start! must be a function")
  (swap! !registry assoc type (select-keys src [:schema :events :start!]))
  type)

(defn lookup [type] (get @!registry type))

(defn- sha1-hex [^String s]
  (let [md (MessageDigest/getInstance "SHA-1")
        bs (.digest md (.getBytes s "UTF-8"))]
    (str/join (map #(format "%02x" %) bs))))

(defn config-hash
  "Stable 12-hex-char hash of a source-config map. :type is stripped before
   hashing so the hash identifies the source-instance, not the source type."
  [source-config]
  (let [stripped  (dissoc source-config :type)
        canonical (pr-str (into (sorted-map) stripped))]
    (subs (sha1-hex canonical) 0 12)))
