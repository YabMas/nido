(ns nido.coordinator.view.drains
  "Each open drain as the /drains page shows it: its phase, recipe version and pending proposals,
   its newest census and the trend of its readings, its plans and slices by state, and its open
   misfits and escalations. Pure read model over the drain's records — NO WRITES.

   A slice's state here is read off the drain's records and the slice's units in the core:
   :started, :cleared once the coordinator cleared it, :landing once a landing is granted on one of
   its units — until it merges, a landing that failed included, which its conductor raises to the
   person — and :landed once it landed."
  (:require
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.report :as report]
   [nido.coordinator.source.drain :as source-drain]))

(defn- phase
  "Where drain `w` stands on the drain recipe, read as the pipeline reads it."
  [w]
  (let [c (drain/newest-census w)]
    (cond
      (ws/closure w)                     :closed
      (seq (drain/pending-proposals w))  :recipe-proposed
      (nil? (drain/version w))           :chartered
      (nil? c)                           :awaiting-census
      (zero? (:remaining c))             :exhausted
      :else                              :draining)))

(defn- slice-state [project w {:keys [ws-id]}]
  (cond
    (source-drain/landed? project ws-id)        :landed
    (some :landing? (ws/units project ws-id))   :landing
    (drain/cleared? w ws-id)                    :cleared
    :else                                       :started))

(defn- open-blockers [project ws-id]
  (let [answered (set (map :blocker-seq (ws/entries-of project ws-id :blocker-answered)))]
    (vec (remove #(answered (:seq %)) (ws/entries-of project ws-id :blocker)))))

(defn- escalation
  "An open escalation as the drain shows it: its seq, its context and what it asks."
  [b]
  (assoc (select-keys b [:seq :summary]) :question (report/blocker-question-text b)))

(defn- escalations
  "The drain's open escalations and those its slices raise on their own ledgers (a failed
   reseat, say), each slice's tagged `:slice` with the workstream it is answered on."
  [project drain-id slices]
  (into (mapv escalation (open-blockers project drain-id))
        (for [{:keys [ws-id]} slices
              b (open-blockers project ws-id)]
          (assoc (escalation b) :slice ws-id))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  drain
  "Drain `drain-id` of `project` as the page shows it, or nil when it is no drain."
  [project drain-id]
  (let [w (ws/read-ws project drain-id)]
    (when (and w (drain/drain? w))
      (let [misfits  (drain/open-misfits w)
            slices   (mapv (fn [s] (assoc s
                                          :state (slice-state project w s)
                                          :sites (:sites (first (filter #(= (:plan s) (:seq %)) (drain/plans w))))
                                          :misfits (count (filter #(= (:ws-id s) (:slice %)) misfits))))
                           (drain/slices w))
            version  (drain/version w)]
        {:project      (name project)
         :ws-id        drain-id
         :charter      (select-keys (drain/charter w) [:exit :established-by :at])
         :phase        (phase w)
         :version      version
         :recipe       (:recipe (drain/recipe w))
         :proposals    (mapv #(select-keys % [:seq :reason :at]) (drain/pending-proposals w))
         :census       (some-> (drain/newest-census w) (select-keys [:seq :remaining :groups :at]))
         :trend        (mapv :remaining (filter #(= version (:recipe %)) (drain/censuses w)))
         :plans        (mapv #(select-keys % [:seq :sites :rests-on :start :state]) (drain/open-plans w))
         :slices       slices
         :by-state     (frequencies (map :state slices))
         :misfits      (mapv #(select-keys % [:seq :slice :site :note]) misfits)
         :residue      (mapv #(select-keys % [:seq :site :disposition]) (drain/residue w))
         :escalations  (escalations project drain-id slices)}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  drains
  "Every open drain of `project`, as `drain` reads it."
  [project]
  (into [] (keep #(drain project %)) (source-drain/open-drains project)))
