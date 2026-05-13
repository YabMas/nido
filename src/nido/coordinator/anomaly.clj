(ns nido.coordinator.anomaly
  "Rate-based anomaly detection for runaway-spawn or fail-loop conditions.
   In-memory only; resets on daemon restart. See spec §Safety brakes /
   Anomaly auto-halt."
  (:require
   [nido.coordinator.clock :as clock]))

(defn empty-detector []
  {:spawns []      ; vector of ISO timestamps
   :failures []})

(defn record-spawn [det iso-ts]
  (update det :spawns conj iso-ts))

(defn record-failure [det iso-ts]
  (update det :failures conj iso-ts))

(defn- ms-between [from-iso to-iso]
  (try
    (- (.toEpochMilli (java.time.Instant/parse to-iso))
       (.toEpochMilli (java.time.Instant/parse from-iso)))
    (catch Exception _ 0)))

(defn- within? [event-iso now-iso window-ms]
  (let [delta (ms-between event-iso now-iso)]
    (and (>= delta 0) (<= delta window-ms))))

(defn check
  "Return {:trip :spawn-burst | :fail-burst :count <n>} when a threshold
   is exceeded; nil otherwise."
  [det {:keys [spawn-window-ms spawn-threshold fail-window-ms fail-threshold]}]
  (let [now    (clock/now-iso)
        spawns (count (filter #(within? % now spawn-window-ms) (:spawns det)))
        fails  (count (filter #(within? % now fail-window-ms)  (:failures det)))]
    (cond
      (>= spawns spawn-threshold) {:trip :spawn-burst :count spawns}
      (>= fails  fail-threshold)  {:trip :fail-burst  :count fails}
      :else                       nil)))
