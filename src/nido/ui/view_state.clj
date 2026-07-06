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
      :source  :all|:notion|:github|:slack|:scratch
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
     :source    (or (some (fn [[k v]] (when (= k "source") (keyword v))) ps) :all)
     :facets    (into {} (for [[k v] ps :when (not (reserved k))]
                           (let [dv (decode v)]
                             [(keyword k) (if (= dv "unclassified") :unclassified dv)])))
     :selection (selection ps)
     :entry     (some (fn [[k v]] (when (= k "entry") (parse-long v))) ps)}))
