(ns nido.coordinator.lane.embodied-strata
  "The first embodiment: a workstream carried by one seat per stratum of its governing design.

   HIDES that the generation-defining elements it carries are exactly a design's strata — it
   refuses a design that leaves a stratum unmarked or marks anything else, naming each — that a
   seat is named by its stratum's id, and that an occupant is briefed to raise a tension first
   with the strata its stratum rests on or that rest on it. That last is practice in a brief:
   nothing at the levels below reads the :rests-on edges a canvas declares, and none of them
   treats a stratum as a seat. Written in the seats vocabulary; nothing is written in it."
  (:require
   [clojure.string :as str]
   [nido.coordinator.lane.seats :as lane-seats]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.seats :as seats]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as ws]))

(defn- slug
  "A stratum id's last segment, made fit for a session name: `canvas.strata/record-status` →
   `record-status`."
  [stratum-id]
  (-> (last (str/split stratum-id #"/"))
      str/lower-case
      (str/replace #"[^a-z0-9-]+" "-")
      (str/replace #"^-+|-+$" "")))

(def ^:private practice
  (str "You embody this stratum: the levels below you provide what you are written in, and the "
       "levels above are written in what you provide. Raise a tension first with the strata yours "
       "rests on or that rest on it — the canvas's :rests-on edges name them — before the "
       "conductor."))

(defn ^{:malli/schema [:=> [:cat] :map]}
  embodiment
  "The strata embodiment as the occupancy lane takes it: a seat's session named by its stratum,
   and the practice an occupant is briefed with."
  []
  {:seat-name slug :practice practice})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  carry!
  "Carry the workstream by its strata: occupy each seat no occupant holds in the current
   generation. Refused, naming each, when the generation-defining ids the generation fixes are
   not exactly its governing design's strata — a stratum left unmarked, a non-stratum marked —
   and when no design governs or nothing is fixed yet. Answers {:occupied [{:seat :session} …]}."
  [project ws-id]
  (let [design (or (standing/governing-design project ws-id)
                   (throw (ex-info "No design governs this generation yet — nothing to carry"
                                   {:reason :no-design})))
        fixed  (or (generation/fixed-ids (ws/read-ws project ws-id))
                   (throw (ex-info "The generation fixes no generation-defining ids yet — a marking design is cleared or approved first"
                                   {:reason :nothing-fixed})))
        strata (set (:strata design))
        unmarked (sort (remove fixed strata))
        foreign  (sort (remove strata fixed))]
    (when (or (seq unmarked) (seq foreign))
      (throw (ex-info (str "Not carried by its strata:"
                           (when (seq unmarked) (str " stratum unmarked " (str/join ", " unmarked) ";"))
                           (when (seq foreign) (str " marked but no stratum " (str/join ", " foreign) ";")))
                      {:reason :not-strata :unmarked (vec unmarked) :foreign (vec foreign)})))
    (let [held (set (map :seat (lane-seats/occupants project ws-id)))]
      {:occupied (into [] (for [{:keys [id]} (seats/seats project ws-id)
                                :when (not (held id))]
                            (lane-seats/occupy! project ws-id (embodiment) id)))})))
