(ns nido.core.images
  "The images a workstream's chat carries, kept by digest outside the core's events: an event names
   an image by its digest and nothing more, so the event log stays the size of its words however
   many images a chat holds.

   HIDES where and how the bytes are kept: one file per image under a root, named by the hex
   SHA-256 of its bytes, beside one holding its media type. A digest names its bytes, so keeping
   an image twice keeps it once, and nothing is ever rewritten or deleted.

   An image arrives trusted — made by the chat host's `parse-image`, the one place an upload becomes
   an image — and is kept only when its bytes hash to its digest. Loads under bb as well."
  (:require [clojure.java.io :as io])
  (:import (java.nio.file Files StandardCopyOption)
           (java.security MessageDigest)
           (java.util Base64)))

(defn- digest-of ^String [^bytes bs]
  (apply str (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn- digest? [d] (boolean (and (string? d) (re-matches #"[0-9a-f]{64}" d))))

(defn- write-atomically! [^java.io.File f ^bytes bs]
  (let [tmp (io/file (.getParentFile f) (str "." (.getName f) "." (random-uuid) ".tmp"))]
    (io/copy bs tmp)
    (Files/move (.toPath tmp) (.toPath f) (into-array [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))))

(defn ^{:malli/schema [:=> [:cat :string :map] [:or :string [:map [:refused :keyword] [:digest :any]]]]} put!
  "Keep `image` — {:mime :data :digest}, `:data` its bytes in base64 — under `root`, and answer its
   digest, how an event names it. Bytes that do not hash to `:digest` are not kept: the answer is
   then {:refused :digest-mismatch :digest}."
  [root {:keys [mime data digest]}]
  (let [bs (try (.decode (Base64/getDecoder) (str data)) (catch IllegalArgumentException _ nil))]
    (if-not (and bs (digest? digest) (= digest (digest-of bs)))
      {:refused :digest-mismatch :digest digest}
      (let [f (io/file root digest)]
        (when-not (.exists f)
          (io/make-parents f)
          (write-atomically! (io/file root (str digest ".mime")) (.getBytes (str mime) "UTF-8"))
          (write-atomically! f bs))
        digest))))

(defn ^{:malli/schema [:=> [:cat :string :string] [:maybe :map]]} fetch
  "The image kept under `root` by `digest`, as {:mime :data :digest} with `:data` in base64, or nil
   when none is kept by that digest."
  [root digest]
  (when (digest? digest)
    (let [f (io/file root digest) m (io/file root (str digest ".mime"))]
      (when (and (.exists f) (.exists m))
        {:digest digest
         :mime   (slurp m)
         :data   (.encodeToString (Base64/getEncoder) (Files/readAllBytes (.toPath f)))}))))
