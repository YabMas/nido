(ns nido.coordinator.migrate
  "One-shot, idempotent, reversible migration from the legacy run.edn / ticket
   meta.edn records to the workstream / session model. Pure transforms here;
   the disk-scanning driver is run-once! below (added in a later task).
   See spec docs/superpowers/specs/2026-06-05-workstream-session-model-design.md."
  (:require
   [clojure.string :as str]
   [nido.coordinator.clock :as clock]))

;; --- status/state → stage/phase maps -------------------------------------

(def ^:private terminal-ticket->outcome
  "Legacy ticket statuses that mean the workstream is terminally settled."
  {:skipped :dropped})

(def ^:private run-state->phase
  {:queued          :queued
   :preprocessing   :preprocessing
   :running         :running
   :awaiting-review :parked
   :done            :done
   :failed          :failed
   :halted          :halted
   :dry-run-would-fire :failed})

(defn- run-state->substrate
  "A run still parked at :awaiting-review keeps a live substrate; everything
   else (terminal) is archived."
  [state]
  (if (= :awaiting-review state) :live :archived))

(defn- run->substrate-history
  "Reconstruct a faithful substrate-history for a migrated run. A run still at
   :awaiting-review is :live since it started; a terminal run was :live during
   its burst and became :archived when it ended — so it gets two entries rather
   than a single fabricated 'archived since creation' entry."
  [state-history substrate]
  (let [first-at (or (:at (first state-history)) (clock/now-iso))]
    (if (= :live substrate)
      [{:at first-at :substrate :live}]
      [{:at first-at                            :substrate :live}
       {:at (or (:at (last state-history)) first-at) :substrate :archived}])))

;; --- pure transforms ------------------------------------------------------

(defn ticket->workstream
  "Legacy ticket meta → a Workstream record with the given id. Status becomes
   the descriptive stage; :skipped also closes the workstream (:dropped). BR +
   notion fields become a single :notion external ref. Entries carry over."
  [project {:keys [br-id status notion-page-id url title entries triaged-at] :as _meta} ws-id]
  (let [now     (or triaged-at (clock/now-iso))
        outcome (terminal-ticket->outcome status)]
    {:id            ws-id
     :project       project
     :external-refs [(cond-> {:adapter :notion :id br-id}
                       notion-page-id (assoc :page-id notion-page-id)
                       url            (assoc :url url)
                       title          (assoc :title title))]
     :stage         (or status :investigating)
     :stage-history [{:at now :stage (or status :investigating)}]
     :closed        (when outcome {:at now :outcome outcome})
     :created-at    now
     :entries       (vec (or entries []))}))

(defn run->session
  "Legacy run.edn → {:ws-id <str> :session <Session>}. :ws-id is the run's BR
   when present (so the run links to its ticket-derived workstream), else a
   synthetic per-run id (degenerate single-session workstream). :session-profile
   :lite/:full → :weight :light/:heavy; run :state → substrate + autonomy phase.
   Run :source and :artifacts are intentionally NOT carried — :source lives in
   triggers.edn and artifact files survive in the run dir archived under
   _pre-unification/ by the migration driver."
  [{:keys [project trigger event-payload skill first-message agent
           session-name claude-session-id limits priority session-profile
           on-promote uncapped? state state-history error id]}]
  (let [br        (some-> event-payload :id)
        ws-id     (if (and br (not (str/blank? br)))
                    br
                    (str "ws-from-run-" id))
        substrate (run-state->substrate state)
        phases    (mapv (fn [{:keys [at state]}]
                          {:at at :phase (run-state->phase state)})
                        state-history)]
    {:ws-id ws-id
     :session
     {:name              session-name
      :workstream-id     ws-id
      :project           project
      :weight            (if (= :lite session-profile) :light :heavy)
      :substrate         substrate
      :substrate-history (run->substrate-history state-history substrate)
      :autonomy          {:skill             skill
                          :first-message     first-message
                          :agent             (or agent :claude)
                          :claude-session-id claude-session-id
                          :trigger           trigger
                          :limits            (or limits {})
                          :priority          (or priority 0)
                          :uncapped?         (boolean uncapped?)
                          :on-promote        on-promote
                          :phase             (run-state->phase state)
                          :phase-history     phases
                          :error             error}
      :created-at        (or (:at (first state-history)) (clock/now-iso))}}))
