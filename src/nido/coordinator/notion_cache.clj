(ns nido.coordinator.notion-cache
  "Read model over the :notion-view intake-source snapshots. The source poller
   (sources/notion) persists a per-page {:status :priority :ball-ids} map under
   :pages in each source snapshot; this ns parses the Notion Priority select and
   merges the per-project view snapshots into one page-id → facts lookup the
   board reads. Read-only; no Notion I/O."
  (:require
   [nido.coordinator.sources.state :as sstate]))

(defn parse-priority-rank
  "Leading integer of a Notion Priority select label → int (lower = more
   important), or nil. Handles both ASCII '-' and en-dash '–' separators:
   '0 – Release Blocker' → 0, '1 - Must' → 1. nil/blank/no-leading-digit → nil."
  [s]
  (when-let [m (and s (re-find #"^\s*(\d+)" (str s)))]
    (parse-long (second m))))

(defn pages-snapshot
  "Build the :pages map {page-id → {:status :priority :ball-ids}} from a vector
   of normalised Notion pages (client/normalise-page output). :status is the
   promoted Status string (or nil), :priority the parsed Priority rank (or nil),
   :ball-ids the set of Ball Holder people ids (empty when unset)."
  [pages]
  (into {}
        (map (fn [p]
               [(:page-id p)
                {:status   (:status p)
                 :priority (parse-priority-rank (:priority p))
                 :ball-ids (set (map :id (:people (:ball-holder p))))}]))
        pages))
