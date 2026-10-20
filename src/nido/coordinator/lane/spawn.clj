(ns nido.coordinator.lane.spawn
  "Live-path spawn orchestration: turn a routed fire request into a workstream
   (found-or-created, deduped on external ref) + the authoritative autonomous
   session (self-sufficient for resume identity), then the execution-driver/
   substrate run. The run carries :workstream-id back to its session (strangler
   boundary — see docs/superpowers/specs/2026-06-08-live-path-cutover-design.md)."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.lane.facets :as facets]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as ws]
   [nido.notion.views :as views]))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  external-ref
  "External ref derived from an event payload, or nil when the payload carries
   no usable id. The adapter comes from the payload (:adapter), defaulting to
   :notion so existing Notion payloads are unchanged. Optional fields are
   included when present (Slack payloads carry no :page-id, so it is omitted)."
  [payload]
  (let [adapter (or (:adapter payload) :notion)
        id      (:id payload)]
    (when (and id (not (str/blank? id)))
      (cond-> {:adapter adapter :id id}
        (:title payload)   (assoc :title (:title payload))
        (:url payload)     (assoc :url (:url payload))
        (:page-id payload) (assoc :page-id (:page-id payload))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map :keyword] :Workstream]}
  ensure-workstream!
  "Find-or-create the workstream for this fire. With a derivable Notion ref,
   dedups via find-by-ref; otherwise mints a fresh ref-less workstream. New
   workstreams are stamped with :facets read from the payload (the configured
   durable classifiers); a deduped pre-existing workstream is left untouched
   (its facets are owned by the triage-completion / bulk refresh paths).

   The find-then-create is not atomic, but the coordinator processes envelopes
   single-threaded within a tick, so two fires for the same ref can't race here."
  [project payload]
  (let [facets (facets/select-facets (views/facet-properties project) payload)]
    (if-let [ref (external-ref payload)]
      (or (ws/find-by-ref project (:adapter ref) (:id ref))
          (ws/create! project {:external-refs [ref] :facets facets}))
      (ws/create! project {:external-refs [] :facets facets}))))

(defn- weight-of [session-profile]
  (if (= :lite session-profile) :light :heavy))

(defn ^{:malli/schema [:=> [:cat :Run] :map]}
  autonomy-from
  "Build a session :autonomy map from a freshly-created run: what the session resumes from and
   is limited by. Where its agent is lives on the run."
  [run]
  {:skill             (:skill run)
   :first-message     (:first-message run)
   :agent             (or (:agent run) :claude)
   :claude-session-id (:claude-session-id run)
   :trigger           (:trigger run)
   :limits            (or (:limits run) {})
   :priority          (or (:priority run) 0)
   :uncapped?         (boolean (:uncapped? run))
   :on-promote        (:on-promote run)
   :error             nil})

(defn ^{:malli/schema [:=> [:cat :Run :WorkstreamId] :Session]}
  create-session-for-run!
  "Persist the authoritative autonomous session for a run under `ws-id`."
  [run ws-id]
  (session/create! (:project run) ws-id
                   {:name     (:session-name run)
                    :weight   (weight-of (:session-profile run))
                    :autonomy (autonomy-from run)}))

(defn- initial-stage
  "Per-trigger :workstream-stage, defaulting to :triaging — a stage that places nothing, so the
   workstream's band is wherever status places it."
  [routed]
  (or (-> routed :trigger :workstream-stage) :triaging))

(defn ^{:malli/schema [:=> [:cat :map :map] :map]}
  spawn-records!
  "Orchestrate the live spawn: ensure workstream → create run (linked) → create
   session. Returns the run (carrying :workstream-id) for the executor to submit.
   Atomic w.r.t. the workstream: if run/session creation throws after we MINTED a
   new workstream (not a deduped pre-existing one) and it has no sessions, the
   orphan is deleted before re-throwing, so a failed spawn never leaves a
   session-less workstream behind.

   A deduped workstream that is closed is reopened at the trigger's stage once
   its session exists. The ref outlives the close: a ticket dismissed at triage
   and later picked up dedups onto that dismissed workstream, and :dismissed is
   the board's veto — the new session would run where no board shows it."
  [routed meta]
  (let [project (:project routed)
        ref     (external-ref (:payload routed))
        pre     (when ref (ws/find-by-ref project (:adapter ref) (:id ref)))
        w       (ensure-workstream! project (:payload routed))
        minted? (nil? pre)]
    (try
      (let [run (runs/create-run! (assoc routed :workstream-id (:id w)) meta)]
        (create-session-for-run! run (:id w))
        ;; A trigger that births its work at a board stage — the described-intent leg, which
        ;; is no triage — records that as nido's choice, which is what places it there.
        (when (and minted? (contains? session/lifecycle-stages (initial-stage routed)))
          (ws/advance-stage! project (:id w) (initial-stage routed)))
        (when (ws/closure w)
          (ws/reopen! project (:id w) (initial-stage routed)))
        run)
      (catch Throwable t
        (when (and minted? (empty? (session/list-sessions project (:id w))))
          (ws/delete! project (:id w)))
        (throw t)))))

(def ^:private pending-states
  "Run states in which a run is still in flight FOR DEDUP: queued included, because the pre-spawn
   gate exists to avoid minting a SECOND run for a ticket whose first is merely queued. A run
   ended :done/:failed/:halted is settled, which keeps its ticket re-triable."
  #{:queued :preprocessing :running :awaiting-review})

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  ref-has-pending-run?
  "True when this fire's external ref already resolves to a workstream with a run of the SAME
   trigger still in flight (see pending-states). The coordinator's pre-spawn gate uses this to
   drop a reconcile re-emit instead of minting a duplicate run — the fix for the :queued-run
   pileup: a merely-queued triage records nothing on the ticket's ledger, so the ticket-status
   gate alone can't dedup it. Ref-less fires (no dedup key) are never suppressed. The run stores
   its trigger as the trigger's :name keyword (runs/create-run!), so that is what is matched."
  [routed]
  (boolean
   (when-let [ref (external-ref (:payload routed))]
     (when-let [w (ws/find-by-ref (:project routed) (:adapter ref) (:id ref))]
       (some #(and (= (-> routed :trigger :name) (:trigger %))
                   (contains? pending-states (:state %)))
             (runs/runs-of (:project routed) (:id w)))))))

(defn ^{:malli/schema [:=> [:cat :map :map] :any]}
  spawn-and-submit!
  "Spawn the records for a routed fire and submit the run to the executor.
   Shared by the coordinator's live spawn branch and promote's inbox→triage
   start. Returns the created run."
  [routed meta]
  (let [run (spawn-records! routed meta)]
    (executor/submit! (:id run) (:priority run) (:uncapped? run)
                      (:trigger run) (-> routed :trigger :max-in-flight))
    run))
