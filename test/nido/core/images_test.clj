(ns nido.core.images-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [nido.core.images :as images])
  (:import (java.security MessageDigest)
           (java.util Base64)))

(defn- image [^bytes bs]
  {:mime "image/png" :data (.encodeToString (Base64/getEncoder) bs)
   :digest (apply str (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") bs)))})

(deftest an-image-is-kept-by-its-digest-and-read-back
  (let [root (str (fs/create-temp-dir))
        img (image (.getBytes "not really a png"))]
    (is (= (:digest img) (images/put! root img)))
    (is (= img (images/fetch root (:digest img))))
    (is (= (:digest img) (images/put! root img)) "kept twice is kept once")
    (is (nil? (images/fetch root (apply str (repeat 64 "0")))) "none kept by that digest")
    (is (nil? (images/fetch root "../../etc/passwd")) "a digest names a file under the root, nothing else")
    (let [d1 (apply str (repeat 64 "1"))]
      (is (= {:refused :digest-mismatch :digest d1} (images/put! root (assoc img :digest d1)))
          "bytes that do not hash to the digest are refused")
      (is (nil? (images/fetch root d1)) "and nothing is kept"))))
