(ns nido.coordinator.source.filter
  "Trigger filter evaluation: map-equality + set-membership against
   event payloads. See spec §Source: :notion-view / Event-payload schema."
  (:require [clojure.string :as str]))

(defn- lookup
  "Top-level first, then :properties. Missing-everywhere returns ::missing."
  [event k]
  (cond
    (contains? event k)               (get event k)
    (contains? (:properties event) k) (get-in event [:properties k])
    :else                             ::missing))

(defn- key-matches? [event [k v]]
  (let [ev (lookup event k)]
    (cond
      (= ::missing ev)                    false
      (and (map? v) (contains? v :contains))
      (and (string? ev) (str/includes? ev (:contains v)))
      (or (set? v) (vector? v))           (contains? (set v) ev)
      :else                               (= v ev))))

(defn accept?
  "True iff every key in filter-map matches the event payload."
  [filter-map event]
  (every? (partial key-matches? event) filter-map))
