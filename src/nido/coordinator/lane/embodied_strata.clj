(ns nido.coordinator.lane.embodied-strata
  "The first embodiment: a workstream carried by one seat per stratum of its governing design.

   HIDES that the generation-defining elements it carries are exactly a design's strata — it
   refuses a design that leaves a stratum unmarked or marks anything else, naming each, whether it
   carries every seat or occupies or replaces one — that a seat's session is named by its stratum's
   whole id, and that an occupant is briefed to raise a tension first with the strata its stratum
   rests on or that rest on it. That last is practice in a brief: nothing at the levels below reads
   the :rests-on edges a canvas declares, and none of them treats a stratum as a seat. Written in the seats vocabulary; nothing is written in it."
  (:require
   [clojure.string :as str]
   [nido.coordinator.lane.seats :as lane-seats]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.seats :as seats]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as ws]))

(defn- slug
  "A stratum's whole id, made fit for a session name and told apart from every other id: letters
   lower-case, digits and `-` stand for themselves, and every other character is `_` and a code
   — `_` itself `__`, `.` `_d`, `/` `_s`, an upper-case letter `_u` and itself lower-cased, any
   other `_x<hex>_` — so `canvas.strata/record-status` is `canvas_dstrata_srecord-status`, and
   two strata sharing a last segment name two sessions."
  [stratum-id]
  (apply str (map (fn [c]
                    (cond (re-matches #"[a-z0-9-]" (str c)) c
                          (= \_ c) "__"
                          (= \. c) "_d"
                          (= \/ c) "_s"
                          (Character/isUpperCase (char c)) (str "_u" (str/lower-case (str c)))
                          :else (str "_x" (Integer/toHexString (int c)) "_")))
                  stratum-id)))

(def ^:private practice
  (str "You embody this stratum: the levels below you provide what you are written in, and the "
       "levels above are written in what you provide. Raise a tension first with the strata yours "
       "rests on or that rest on it — the canvas's :rests-on edges name them — before the "
       "conductor. Every layer you write carries a `Stratum: <your seat id>` brief field; review "
       "refuses a layer naming a stratum the governing design does not declare."))

(defn- carried-strata!
  "The current generation's fixed ids, when the strata embodiment can carry them: exactly the
   governing design's strata. Refused, naming each
   offending id, otherwise — and when no design governs or nothing is fixed yet."
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
    fixed))

(defn ^{:malli/schema [:=> [:cat :any] :boolean]}
  refused?
  "Whether `e` is the strata embodiment refusing a workstream (`carried-strata!`), as against an
   occupant whose launch failed."
  [e]
  (contains? #{:no-design :nothing-fixed :not-strata} (:reason (ex-data e))))

(defn ^{:malli/schema [:=> [:cat] :map]}
  embodiment
  "The strata embodiment as the occupancy lane takes it: a seat's session named by its stratum,
   the practice an occupant is briefed with, and its refusal of a workstream its strata do not
   carry (`carried-strata!`) — which the lane runs inside the transaction that reads the
   generation and launches the occupant, so no generation opens between the check and the seat."
  []
  {:seat-name slug :practice practice :refuse carried-strata!})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  carry!
  "Carry the workstream by its strata: occupy each seat no occupant holds in the current
   generation, and start again any whose session's launch failed. Refused, naming each, when the
   generation-defining ids the generation fixes are not exactly its governing design's strata — a
   stratum left unmarked, a non-stratum marked — and when no design governs or nothing is fixed
   yet. Answers {:occupied [{:seat :session} …]}."
  [project ws-id]
  ;; Refused up front for a workstream with no seat to try; each occupation is refused again by
  ;; the embodiment inside its own transaction, against the generation it seats.
  (carried-strata! project ws-id)
  ;; Occupancy decides which seats are held — a session started, or a start in flight — so a
  ;; record a failed launch left is started again rather than counted as held.
  {:occupied (into [] (keep #(try (lane-seats/occupy! project ws-id (embodiment) %)
                                  (catch clojure.lang.ExceptionInfo e
                                    (when-not (= :occupied (:reason (ex-data e))) (throw e)))))
                   (map :id (seats/seats project ws-id)))})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string] :map]}
  occupy!
  "Occupy `seat-id` under the strata embodiment, refused as `carry!` refuses a workstream its
   strata do not carry. Answers {:seat :session}."
  [project ws-id seat-id]
  (lane-seats/occupy! project ws-id (embodiment) seat-id))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string] :map]}
  replace!
  "Replace `seat-id`'s occupant under the strata embodiment, refused as `carry!` refuses a
   workstream its strata do not carry. Answers {:seat :session}."
  [project ws-id seat-id]
  (lane-seats/replace! project ws-id (embodiment) seat-id))
