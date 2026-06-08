(ns nido.coordinator.spawn
  "Live-path spawn orchestration: turn a routed fire request into a workstream
   (found-or-created, deduped on external ref) + an authoritative autonomous
   session, then the legacy run that the execution machinery drives. The run
   carries :workstream-id back to its session (strangler boundary — see
   docs/superpowers/specs/2026-06-08-live-path-cutover-design.md)."
  (:require
   [clojure.string :as str]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.coordinator.workstream :as ws]))

(defn external-ref
  "Notion external ref derived from an event payload, or nil when the payload
   carries no usable id. Optional fields included when present."
  [payload]
  (let [id (:id payload)]
    (when (and id (not (str/blank? id)))
      (cond-> {:adapter :notion :id id}
        (:title payload)   (assoc :title (:title payload))
        (:url payload)     (assoc :url (:url payload))
        (:page-id payload) (assoc :page-id (:page-id payload))))))

(defn ensure-workstream!
  "Find-or-create the workstream for this fire. With a derivable Notion ref,
   dedups via find-by-ref; otherwise mints a fresh ref-less workstream.

   The find-then-create is not atomic, but the coordinator processes envelopes
   single-threaded within a tick (tick! drains the queue via a sequential
   doseq, and process-envelope! routes sequentially), so two fires for the same
   ref can't race here."
  [project payload stage]
  (if-let [ref (external-ref payload)]
    (or (ws/find-by-ref project :notion (:id ref))
        (ws/create! project {:stage stage :external-refs [ref]}))
    (ws/create! project {:stage stage :external-refs []})))

(defn- weight-of [session-profile]
  (if (= :lite session-profile) :light :heavy))

(defn autonomy-from
  "Build a session :autonomy map from a freshly-created run, seeded at :queued."
  [run]
  (let [now (clock/now-iso)]
    {:skill             (:skill run)
     :first-message     (:first-message run)
     :agent             (or (:agent run) :claude)
     :claude-session-id (:claude-session-id run)
     :trigger           (:trigger run)
     :limits            (or (:limits run) {})
     :priority          (or (:priority run) 0)
     :uncapped?         (boolean (:uncapped? run))
     :on-promote        (:on-promote run)
     :phase             :queued
     :phase-history     [{:at now :phase :queued}]
     :error             nil}))

(defn create-session-for-run!
  "Persist the authoritative autonomous session for a run under `ws-id`."
  [run ws-id]
  (session/create! (:project run) ws-id
                   {:name     (:session-name run)
                    :weight   (weight-of (:session-profile run))
                    :autonomy (autonomy-from run)}))

(defn- initial-stage
  "Per-trigger :workstream-stage, defaulting to :triaging."
  [routed]
  (or (-> routed :trigger :workstream-stage) :triaging))

(defn spawn-records!
  "Orchestrate the live spawn: ensure workstream → create run (linked) → create
   session. Returns the run (carrying :workstream-id) for the executor to submit.
   Atomic w.r.t. the workstream: if run/session creation throws after we MINTED a
   new workstream (not a deduped pre-existing one) and it has no sessions, the
   orphan is deleted before re-throwing, so a failed spawn never leaves a
   session-less workstream behind."
  [routed meta]
  (let [project (:project routed)
        ref     (external-ref (:payload routed))
        pre     (when ref (ws/find-by-ref project :notion (:id ref)))
        w       (ensure-workstream! project (:payload routed) (initial-stage routed))
        minted? (nil? pre)]
    (try
      (let [run (runs/create-run! (assoc routed :workstream-id (:id w)) meta)]
        (create-session-for-run! run (:id w))
        run)
      (catch Throwable t
        (when (and minted? (empty? (session/list-sessions project (:id w))))
          (ws/delete! project (:id w)))
        (throw t)))))
