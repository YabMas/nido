;; src/nido/review/digest.clj
(ns nido.review.digest
  "Content hashing for the review loop.

   Two things in the loop are identified by what they ARE rather than by where
   they sit: a finding (so a disposition can name it, and so the same defect
   seen twice is one finding) and a layer's patch (so a verdict about it
   survives the rebases and folds that rewrite every commit id on the way to
   merge)."
  (:import
   [java.security MessageDigest]))

(defn sha256-hex
  "Lowercase hex SHA-256 of `s`."
  [s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (.getBytes (str s) "UTF-8"))
         (map #(format "%02x" %))
         (apply str))))

(defn short-id
  "The first 8 hex characters of the digest of `s` — enough to name a finding
   unambiguously within one round, short enough to read in a prompt and quote in
   a report."
  [s]
  (subs (sha256-hex s) 0 8))
