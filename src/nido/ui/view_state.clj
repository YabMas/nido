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

(def tabs
  "The /workstreams surfaces, in display order. The FIRST entry is the default.
   A tab is a BAND selector — which part of the stage spine is on screen (see
   nido.work/tab-bands) — NOT a row filter: every row in the tab renders
   whatever its origin. They are nido's two jobs: intake via the various
   streams, and orchestrating work in progress."
  [:intake :active])

(def default-tab
  "The tab /workstreams opens on when none is selected — the first one."
  (first tabs))

(defn- rounds
  "`?rounds=1,3` -> #{1 3}: which review rounds the workstream pane has unfolded
   inside the open ledger entry. Non-numeric members are dropped rather than
   failing the parse — the whole param is a reading position, not an argument, and
   a mangled one should cost the reader a fold, not the page."
  [ps]
  (when-let [raw (some (fn [[k v]] (when (= k "rounds") v)) ps)]
    (not-empty (into #{} (keep parse-long) (str/split (decode raw) #",")))))

(defn parse
  "Request map -> view-state:
     {:surface :needs|:workstreams|:other
      :scope   \"all\"|<project>
      :selection {:project _ :ws-id _}|nil
      :entry   <long>|nil
      :rounds  #{<long>}|nil
      :tab     :intake|:active}

   No source/facet filtering: the board shows every origin, and its tabs select
   BANDS rather than rows (see nido.work/tab-bands). A legacy ?source= / facet
   bookmark parses cleanly and constrains nothing."
  [{:keys [uri query-string]}]
  (let [ps (pairs query-string)]
    {:surface   (surface uri)
     :scope     (or (some (fn [[k v]] (when (= k "scope") v)) ps) "all")
     :selection (selection ps)
     :entry     (some (fn [[k v]] (when (= k "entry") (parse-long v))) ps)
     :rounds    (rounds ps)
     :tab       (let [t (some (fn [[k v]] (when (= k "tab") (keyword v))) ps)]
                  (if (some #{t} tabs) t default-tab))}))
