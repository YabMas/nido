(ns nido.coordinator.pickup
  "Pickup: turn a pasted Notion URL / page-id / BR-#### into the ticket ref, so a
   human can hand nido a backlog item to drive. Front-end over the :plan-bug leg."
  (:require
   [clojure.string :as str]
   [nido.notion.client :as client]
   [nido.notion.views :as views]))

(defn- dash-uuid [hex]
  (str (subs hex 0 8) "-" (subs hex 8 12) "-" (subs hex 12 16) "-"
       (subs hex 16 20) "-" (subs hex 20 32)))

;; Matches a 32-hex run, optionally canonically dashed (8-4-4-4-12), that is
;; NOT itself adjacent to another hex character. A naive "strip all dashes
;; then find any 32-hex run" approach mis-extracts when a URL slug word ends
;; in a hex-looking letter (e.g. ".../Some-Title-<uuid>" — "Title" ends in
;; "e", which is valid hex, so stripping the separating "-" would merge it
;; into the id and shift the match by one character). The lookaround here
;; anchors on the *original* string (before any dash-stripping), where the
;; real boundary — a literal "-" separator, or start/end of string — is
;; still present.
(def ^:private id-pattern
  #"(?i)(?<![0-9a-f])[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}(?![0-9a-f])")

(defn extract-page-id
  "The trailing 32-hex run of a Notion URL / uuid, returned dashed; nil if none."
  [s]
  (when s
    (when-let [matches (seq (re-seq id-pattern (str s)))]
      (-> (last matches) (str/replace "-" "") str/lower-case dash-uuid))))

(defn- normalise [page]
  (let [n (client/normalise-page page)]
    {:id      (:id n)
     :page-id (:page-id n)
     :url     (:url n)
     :title   (:title n)}))

(defn resolve-ref
  "Resolve `input` (Notion URL / page-id / BR-####) → {:id :page-id :url :title},
   or {:error <kw>}. `token` is a Notion integration token."
  [project input token]
  (if-let [pid (extract-page-id input)]
    (let [page (client/retrieve-page pid token)]
      (if (:error page) {:error (:error page)} (normalise page)))
    (if-let [[_ n] (re-matches #"(?i)(?:BR-)?(\d+)" (str/trim (str input)))]
      (try
        (let [{:keys [database]} (views/load-registry project)
              ds     (client/resolve-data-source-id database token)
              resp   (client/data-source-query ds token
                       {:filter {:property "ID" :unique_id {:equals (parse-long n)}}})
              page   (first (:results resp))]
          (cond
            (:error resp)  {:error (:error resp)}
            (some? page)   (normalise page)
            :else          {:error :not-found}))
        (catch Exception _e
          {:error :notion-error}))
      {:error :unrecognized-input})))
