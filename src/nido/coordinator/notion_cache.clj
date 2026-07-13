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
