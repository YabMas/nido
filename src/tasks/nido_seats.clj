(ns tasks.nido-seats
  "bb-task entry points for a workstream's seats: carry it by its strata, occupy or replace one
   seat, start its conductor, record a tension, accord, withdrawal or re-cut, send a seat message,
   and read who occupies what and what was said. Every verb resolves the workstream from the
   session the cwd is in, or from :project with :ws-id; who is writing is never an argument."
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [nido.coordinator.lane.embodied-strata :as embodied]
   [nido.coordinator.lane.seats :as seats]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.task-args :as task-args]
   [nido.review.stages :as stages]))

(defn- here
  "[project ws-id] from :project with :ws-id, else from the session the cwd is in."
  [{:keys [project ws-id]}]
  (if (and project ws-id)
    [(keyword project) (str ws-id)]
    (or (stages/project+ws-from-cwd (System/getProperty "user.dir"))
        (throw (ex-info "No workstream here — run from a session's worktree or session home, or pass :project and :ws-id"
                        {:cwd (System/getProperty "user.dir")})))))

(defn- run* [f args raw-keys]
  (let [[_ opts] (task-args/split-args args raw-keys)]
    (pprint/pprint (f opts))))

(defn- addressee
  "A seat id, or :conductor for the word `conductor`."
  [to]
  (if (= "conductor" (str/replace (str to) #"^:" "")) :conductor (str to)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  carry*
  "Carry the workstream by its strata, occupying every seat no occupant holds."
  [opts]
  (apply embodied/carry! (here opts)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  occupy*
  "Occupy :seat, under the strata embodiment."
  [{:keys [seat] :as opts}]
  (let [[p id] (here opts)] (embodied/occupy! p id (str seat))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  replace*
  "Replace :seat's occupant with a fresh session, briefed afresh from the records."
  [{:keys [seat] :as opts}]
  (let [[p id] (here opts)] (embodied/replace! p id (str seat))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  conductor*
  "Start the workstream's conductor."
  [opts]
  (apply seats/conductor! (here opts)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  record*
  "Record a :tension (:to :about :statement), :accord (:tension :terms), :withdrawal (:tension
   :note) or :recut (:ids :why :tensions), its writer stamped from the session this runs in.
   :about, :ids and :tensions are comma-separated; :tension and each of :tensions a seq."
  [{:keys [kind to about statement tension terms note ids why tensions] :as opts}]
  (let [[p id] (here opts)
        csv    #(when % (mapv str/trim (str/split (str %) #",")))
        cite   #(hash-map :seq (parse-long (str %)))
        k      (keyword (str/replace (str kind) #"^:" ""))]
    (seats/record! p id
                   (case k
                     :tension    {:format :tension :to (addressee to) :about (csv about) :statement (str statement)}
                     :accord     {:format :accord :tension (cite tension) :terms (str terms)}
                     :withdrawal (cond-> {:format :withdrawal :tension (cite tension)} note (assoc :note (str note)))
                     :recut      (cond-> {:format :recut :ids (csv ids) :why (str why)}
                                   tensions (assoc :tensions (mapv cite (csv tensions))))
                     (throw (ex-info "Pass :kind tension | accord | withdrawal | recut" {:kind kind}))))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  send*
  "Send :text to :to — a seat id, or `conductor` — from the seat or conductor this runs in."
  [{:keys [to text] :as opts}]
  (let [[p id] (here opts)] (seats/send! p id {:to (addressee to) :text (str text)})))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  show*
  "The current generation's occupants, and the seat messages of :generation (default: the
   current one)."
  [{:keys [generation] :as opts}]
  (let [[p id] (here opts)
        g      (or (some-> generation str parse-long)
                   (generation/current (:entries (ws/read-ws p id))))]
    {:occupants (seats/occupants p id) :generation g :traffic (seats/traffic p id g)}))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  carry-cmd [& args] (run* carry* args #{}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  occupy-cmd [& args] (run* occupy* args #{:seat}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  replace-cmd [& args] (run* replace* args #{:seat}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  conductor-cmd [& args] (run* conductor* args #{}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  record-cmd [& args]
  (run* record* args #{:to :about :statement :terms :note :ids :why :tensions}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  send-cmd [& args] (run* send* args #{:to :text}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  show-cmd [& args] (run* show* args #{}))
