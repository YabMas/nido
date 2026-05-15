(ns nido.coordinator.sources
  "Plugin registry for event sources. Sources register themselves at load
   time; the coordinator looks up by :type and calls :start! per distinct
   source-config. See spec §Plugin contract."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.io :as io])
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

(defn- envelope-filename
  "Content-addressed filename for a broadcast envelope. The hash is over
   the broadcast contents only (NOT the wrapping :created-at), so a
   re-emission of the same broadcast lands on the same filename and is
   a filesystem no-op."
  [broadcast]
  (let [canonical (pr-str (into (sorted-map) broadcast))]
    (str (subs (sha1-hex canonical) 0 16) ".edn")))

(defn emit-broadcast!
  "Write a broadcast envelope into the queue dir. The filename is a hash
   of the broadcast contents so re-emission of an identical broadcast is
   an idempotent no-op. Crash-safety: the source writes the envelope FIRST
   then updates its snapshot — if we crash between, the next poll detects
   the same row, attempts to write the same filename (no-op), and updates
   the snapshot cleanly."
  [broadcast]
  (let [env      {:broadcast broadcast :created-at (clock/now-iso)}
        filename (envelope-filename broadcast)]
    (fs/create-dirs (cstate/queue-dir))
    (io/write-edn! (str (fs/path (cstate/queue-dir) filename)) env)))
