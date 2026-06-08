(ns nido.coordinator.session
  "A work-episode against a workstream. Carries substrate state (live/archived)
   and weight (light/heavy), plus an OPTIONAL autonomy facet — the old Run,
   demoted to a field. nil autonomy ⇒ a human session.
   See spec docs/superpowers/specs/2026-06-05-workstream-session-model-design.md."
  (:require
   [babashka.fs :as fs]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def phases
  #{:queued :preprocessing :running :parked :done :failed :halted})

(def Autonomy
  [:map {:closed true}
   [:skill             keyword?]
   [:first-message     string?]
   [:agent             keyword?]
   [:claude-session-id [:maybe string?]]
   [:trigger           keyword?]
   [:limits            [:map-of keyword? any?]]
   [:priority          int?]
   [:uncapped?         boolean?]
   [:on-promote        [:maybe [:map-of keyword? any?]]]
   [:phase             (into [:enum] phases)]
   [:phase-history     [:vector [:map [:at string?] [:phase (into [:enum] phases)]]]]
   [:error             [:maybe [:map-of keyword? any?]]]])

(def Session
  [:map {:closed true}
   [:name              string?]
   [:workstream-id     string?]
   [:project           keyword?]
   [:weight            [:enum :light :heavy]]
   [:substrate         [:enum :live :archived]]
   [:substrate-history [:vector [:map [:at string?] [:substrate [:enum :live :archived]]]]]
   [:autonomy          [:maybe Autonomy]]
   [:created-at        string?]])

(defn validate [s]
  (if (m/validate Session s)
    s
    (throw (ex-info "Invalid Session record"
                    {:errors (m/explain Session s) :session s}))))

(defn read-session
  "Read a session.edn by project + ws-id + name. Returns nil if absent."
  [project ws-id session-name]
  (io/read-edn (cstate/session-edn-path project ws-id session-name)))

(defn write! [s]
  (validate s)
  (io/write-edn! (cstate/session-edn-path (:project s) (:workstream-id s) (:name s)) s)
  s)

(defn create!
  "Persist a fresh :live session. `opts` carries :name, :weight, and :autonomy
   (nil for human sessions, or a full Autonomy map). Reads via (:name opts)
   rather than destructuring to avoid shadowing clojure.core/name."
  [project ws-id opts]
  (let [now (clock/now-iso)]
    (write! {:name              (:name opts)
             :workstream-id     ws-id
             :project           project
             :weight            (:weight opts)
             :substrate         :live
             :substrate-history [{:at now :substrate :live}]
             :autonomy          (:autonomy opts)
             :created-at        now})))

(defn list-sessions
  "Seq of session records under one workstream's sessions/ dir."
  [project ws-id]
  (let [d (cstate/ws-sessions-dir project ws-id)]
    (if (fs/exists? d)
      (->> (fs/list-dir d)
           (filter fs/directory?)
           (keep #(read-session project ws-id (str (fs/file-name %)))))
      [])))

(defn live?       [s] (= :live (:substrate s)))
(defn autonomous? [s] (some? (:autonomy s)))
(defn parked?     [s] (and (live? s) (= :parked (get-in s [:autonomy :phase]))))

(defn- load! [project ws-id session-name]
  (or (read-session project ws-id session-name)
      (throw (ex-info "Session not found"
                      {:project project :ws-id ws-id :session session-name}))))

(defn archive!
  "Flip a session to :archived, appending substrate-history. Idempotent (no
   duplicate history entry when already archived). Returns updated record."
  [project ws-id session-name]
  (let [s (load! project ws-id session-name)]
    (if (= :archived (:substrate s))
      s
      (write! (-> s
                  (assoc :substrate :archived)
                  (update :substrate-history conj
                          {:at (clock/now-iso) :substrate :archived}))))))

(defn set-phase!
  "Move an autonomous session's burst phase, appending phase-history. Throws if
   the session has no autonomy facet (a human session has no phase). Returns
   updated record."
  [project ws-id session-name new-phase]
  (let [s (load! project ws-id session-name)]
    (when-not (autonomous? s)
      (throw (ex-info "Cannot set phase on a human session"
                      {:project project :ws-id ws-id :session session-name})))
    (write! (-> s
                (assoc-in [:autonomy :phase] new-phase)
                (update-in [:autonomy :phase-history] conj
                           {:at (clock/now-iso) :phase new-phase})))))

(defn engagement-state
  "Pure projection of a workstream's engagement. `closed` is the workstream's
   :closed value (nil or a map); `sessions` is the seq of its session records.
   Order matters: parked is checked before active because a parked session is
   also live."
  [closed sessions]
  (cond
    (some? closed)            :settled
    (some parked? sessions)   :parked-at-gate
    (some live? sessions)     :active
    :else                     :idle))

(def in-progress-phases
  "Autonomy phases that occupy a trigger's in-flight budget: spawned and not yet
   terminal or parked. :queued is the pending pool and does NOT count; :parked is
   a human gate, not in-flight."
  #{:preprocessing :running})

(defn- list-ws-ids [project]
  (let [d (cstate/workstreams-dir project)]
    (if (fs/exists? d)
      (->> (fs/list-dir d) (filter fs/directory?) (mapv #(str (fs/file-name %))))
      [])))

(def gating-phases
  "Autonomy phases that occupy a trigger's in-flight budget FOR SCHEDULING.
   Includes :parked to preserve the legacy run-based backpressure (a session
   awaiting human review still holds the trigger's slot). Distinct from
   in-progress-phases, which is 'actively executing' work."
  #{:preprocessing :running :parked})

(defn count-by-trigger
  "Map {trigger-kw → count} of LIVE autonomous sessions whose phase is in
   `phase-set`, grouped by autonomy :trigger, across all of `project`'s
   workstreams. Scans disk; restart-safe."
  [project phase-set]
  (->> (list-ws-ids project)
       (mapcat #(list-sessions project %))
       (filter live?)
       (filter autonomous?)
       (filter #(contains? phase-set (get-in % [:autonomy :phase])))
       (reduce (fn [m s] (update m (get-in s [:autonomy :trigger]) (fnil inc 0))) {})))

(defn in-flight-by-trigger
  "Active-work count per trigger (preprocessing+running). Distinct from
   scheduling backpressure (see gating-count-by-trigger)."
  [project]
  (count-by-trigger project in-progress-phases))

(defn gating-count-by-trigger
  "Scheduling backpressure count per trigger (preprocessing+running+parked).
   The scheduler reads this to enforce per-trigger :max-in-flight — the session
   analogue of runs/in-progress-count-by-trigger."
  [project]
  (count-by-trigger project gating-phases))
