(ns nido.ui.view-state
  "Parse a dashboard request into the single view-state value every render
   site derives from. Pure given the request map — no IO."
  (:require [clojure.string :as str]))

(defn- pairs [query-string]
  (when query-string
    (->> (str/split query-string #"&")
         (map #(str/split % #"=" 2))
         (filter #(= 2 (count %))))))

(defn- decode [v] (java.net.URLDecoder/decode v "UTF-8"))

(def sources
  "Workstream origins for the /workstreams source filter, in chip/display order.
   The FIRST entry is the default view. There is deliberately no cross-source
   'All' overview — merging every source across every stage was too noisy to be
   useful, so the page always shows exactly one source."
  [:notion :github :slack :scratch])

(def default-source
  "The source /workstreams opens on when none is selected — the first chip."
  (first sources))

(defn- surface [uri]
  (let [segs (remove str/blank? (str/split (or uri "") #"/"))]
    (case (first segs)
      nil          :needs
      "workstreams" :workstreams
      :other)))

(defn- selection [ps]
  (when-let [raw (some (fn [[k v]] (when (= k "sel") v)) ps)]
    (let [[project ws-id] (str/split (decode raw) #":" 2)]
      (when (and (seq project) (seq ws-id))
        {:project project :ws-id ws-id}))))

(defn parse
  "Request map -> view-state:
     {:surface :needs|:workstreams|:other
      :scope   \"all\"|<project>
      :source  :notion|:github|:slack|:scratch   ; no cross-source :all — see `sources`
      :facets  {kebab-kw value}   ; :unclassified for the \"unclassified\" bucket
      :selection {:project _ :ws-id _}|nil
      :entry   <long>|nil}"
  [{:keys [uri query-string]}]
  (let [ps (pairs query-string)
        ;; `datastar` is Datastar's own signals param, appended to every @get
        ;; poll request — reserve it so a poll's URL doesn't turn into a bogus
        ;; facet filter that matches zero rows and empties the polled list.
        reserved #{"scope" "source" "sel" "entry" "datastar"}]
    {:surface   (surface uri)
     :scope     (or (some (fn [[k v]] (when (= k "scope") v)) ps) "all")
     ;; An unknown/absent source (e.g. a legacy `?source=all` bookmark) falls back
     ;; to the default rather than filtering to an empty list.
     :source    (let [s (some (fn [[k v]] (when (= k "source") (keyword v))) ps)]
                  (if (some #{s} sources) s default-source))
     :facets    (into {} (for [[k v] ps :when (not (reserved k))]
                           (let [dv (decode v)]
                             [(keyword k) (if (= dv "unclassified") :unclassified dv)])))
     :selection (selection ps)
     :entry     (some (fn [[k v]] (when (= k "entry") (parse-long v))) ps)}))
