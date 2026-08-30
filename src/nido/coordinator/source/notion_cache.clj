(ns nido.coordinator.source.notion-cache
  "Read model over the :notion-view intake-source snapshots. The source poller
   (sources/notion) persists a per-page {:status :priority :ball-ids :title :br}
   map under :pages in each source snapshot; this ns parses the Notion Priority
   select and merges the per-project view snapshots into one page-id → facts
   lookup the board reads. Read-only; no Notion I/O."
  (:require
   [nido.coordinator.source.state :as sstate]
   [nido.notion.views :as views]))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] :int]}
  parse-priority-rank
  "Leading integer of a Notion Priority select label → int (lower = more
   important), or nil. Handles both ASCII '-' and en-dash '–' separators:
   '0 – Release Blocker' → 0, '1 - Must' → 1. nil/blank/no-leading-digit → nil."
  [s]
  (when-let [m (and s (re-find #"^\s*(\d+)" (str s)))]
    (parse-long (second m))))

(defn ^{:malli/schema [:=> [:cat :any] :map]}
  pages-snapshot
  "Build the :pages map {page-id → {:status :priority :ball-ids :title :br}} from a
   vector of normalised Notion pages (client/normalise-page output). :status is the
   promoted Status string (or nil), :priority the parsed Priority rank (or nil),
   :ball-ids the set of Ball Holder people ids (empty when unset), :title the page
   title (or nil), :br the promoted \"ID\" unique_id (e.g. \"BR-4659\", or nil)."
  [pages]
  (into {}
        (map (fn [p]
               [(:page-id p)
                {:status   (:status p)
                 :priority (parse-priority-rank (:priority p))
                 :ball-ids (set (map :id (:people (:ball-holder p))))
                 :title    (:title p)
                 :br       (:id p)}]))
        pages))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :map]}
  project-page-facts
  "Merge the :pages maps of the project's :notion-view snapshots into one
   page-id → {:status :priority :ball-ids :title :br} lookup. Restricted to the
   registry's :board-views when set (else every watched view). Empty map when
   the project has no matching snapshots."
  [project]
  (let [pk    (keyword (name project))
        board (views/board-views project)]
    (->> (sstate/list-state-hashes)
         (keep sstate/read-state)
         (filter #(and (= :notion-view (:type %))
                       (= pk (some-> (get-in % [:source-config :project]) name keyword))
                       (or (nil? board)
                           (contains? board (get-in % [:source-config :view])))))
         (map :pages)
         (reduce merge {}))))
