(ns nido.ui.view-state
  "Parse a dashboard request into the single view-state value every render
   site derives from. Pure given the request map — no IO."
  (:require
   [clojure.string :as str]
   [nido.coordinator.lane.pipeline :as pipeline]))

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
      "operations"  :operations
      :other)))

(defn- selection [ps]
  (when-let [raw (some (fn [[k v]] (when (= k "sel") v)) ps)]
    (let [[project ws-id] (str/split (decode raw) #":" 2)]
      (when (and (seq project) (seq ws-id))
        {:project project :ws-id ws-id}))))

(def tabs
  "The /workstreams surfaces, in display order. The FIRST entry is the default.
   A tab is a BAND selector — which part of the stage spine is on screen (see
   nido.coordinator.work/tab-bands) — NOT a row filter: every row in the tab renders
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

(defn- stage
  "`?stage=design` -> :design: which arc stage the workstream pane has expanded.

   Validated against the arc rather than passed through, because unlike a fold
   this value names a section that must exist: an unknown keyword would expand
   nothing while every stage row still offered to close it. A bad one reads as
   no stage open, which is the pane's resting state."
  [ps]
  (when-let [raw (some (fn [[k v]] (when (= k "stage") v)) ps)]
    (let [k (keyword (decode raw))]
      (when (some #{k} pipeline/arc-stages) k))))

(defn- history?
  "`?history=1` -> true: whether the pane's raw ledger index is expanded. Absent
   is the resting state — the arc above it is the reading a driver wants, and the
   entry-by-entry log is what they open when it is not."
  [ps]
  (boolean (some (fn [[k v]] (and (= k "history") (= "1" v))) ps)))

(defn parse
  "Request map -> view-state:
     {:surface :needs|:workstreams|:operations|:other
      :scope   \"all\"|<project>
      :selection {:project _ :ws-id _}|nil
      :entry   <long>|nil
      :rounds  #{<long>}|nil
      :stage   <arc-stage keyword>|nil
      :history? <boolean>
      :tab     :intake|:active}

   No source/facet filtering: the board shows every origin, and its tabs select
   BANDS rather than rows (see nido.coordinator.work/tab-bands). A legacy ?source= / facet
   bookmark parses cleanly and constrains nothing."
  [{:keys [uri query-string]}]
  (let [ps (pairs query-string)]
    {:surface   (surface uri)
     :scope     (or (some (fn [[k v]] (when (= k "scope") v)) ps) "all")
     :selection (selection ps)
     :entry     (some (fn [[k v]] (when (= k "entry") (parse-long v))) ps)
     :rounds    (rounds ps)
     :stage     (stage ps)
     :history?  (history? ps)
     :tab       (let [t (some (fn [[k v]] (when (= k "tab") (keyword v))) ps)]
                  (if (some #{t} tabs) t default-tab))}))
