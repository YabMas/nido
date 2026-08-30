(ns nido.ui.dev
  "Dev-environment state + action primitives shared across nido surfaces
   (web dashboard, TUI). Owns the in-flight app-states atom and every
   function that reads or mutates dev-resource state for a session."
  (:require [clojure.string :as str]
            [nido.platform.process :as proc]
            [nido.session.engine :as engine]
            [nido.session.lifecycle :as lifecycle]
            [nido.session.state :as state]
            [nido.coordinator.work :as work]))

;; ---------------------------------------------------------------------------
;; In-flight action tracking
;;
;; The atom records transient/sticky app states (:starting, :stopping, :failed)
;; that the TCP probe alone can't express. Both POST responses and polling
;; fragment refreshes read from here, so a clicked button gets instant
;; feedback AND the feedback persists across the 3s polling cycle until the
;; future completes.
;; ---------------------------------------------------------------------------

(defonce ^:private app-states (atom {}))

(defn ^{:malli/schema [:=> [:cat :any :any [:? :any]] :any]}
  set-app-state!
  "Store the current state for an instance. The atom value is a map
   `{:state :starting|:stopping|:restarting|:failed
     :error-msg <string?>}` so a :failed state can carry the actual
   error message to display in the UI."
  ([instance-id state] (set-app-state! instance-id state nil))
  ([instance-id state error-msg]
   (swap! app-states assoc instance-id
          (cond-> {:state state}
            error-msg (assoc :error-msg error-msg)))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  clear-app-state! [instance-id]
  (swap! app-states dissoc instance-id))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  current-app-state [instance-id]
  (get @app-states instance-id))

(defn ^{:malli/schema [:=> [:cat] :any]}
  pending-resolve-keys
  "The set of gate-resolve keys currently mid-flight — keys shaped
   \"<project>/<ws-id>\" (as written by the UI's gate-resolve!) whose state is
   still :resuming/:resolving. Fed into work/screen as :pending so an optimistic
   'working…' survives until the async resolve settles. A :failed resolve is NOT
   mid-flight — it drops out so the gate re-derives from disk and stays
   retryable (else a failed Apply would strand a permanent 'working…' with no
   action buttons). Filtering on state also excludes slashed-session instance-id
   keys, which never carry :resuming/:resolving."
  []
  (->> @app-states
       (filter (fn [[k v]] (and (string? k) (str/includes? k "/")
                                (#{:resuming :resolving} (:state v)))))
       (map key)
       set))

(defn ^{:malli/schema [:=> [:cat] :any]}
  pending-winddown-keys
  "The \"<project>/<ws-id>\" keys with a :stopping state mid-flight — the web's
   winddown POST writes them; work/screen marks the matching winding-down rows
   :pending? so the 5s poll shows 'stopping…' instead of a re-clickable button."
  []
  (->> @app-states
       (filter (fn [[k v]] (and (string? k) (str/includes? k "/")
                                (= :stopping (:state v)))))
       (map key)
       set))

(defn ^{:malli/schema [:=> [:cat] :map]}
  failed-ws-errors
  "Map of \"<project>/<ws-id>\" -> error message for every workstream-scoped
   action that ended :failed — a bring-down! AND a gate resolve (Apply/Reply),
   which share this key space. Fed into derive-screen so the failure renders on
   the row / gate / pane instead of vanishing silently: nothing else reads
   :failed for ws keys, and `pending-resolve-keys` deliberately drops :failed to
   keep the action retryable, so without this a failed Apply looked like the
   click did nothing at all. Re-clicking sets :resolving/:stopping, which
   overwrites the entry — retry self-clears the error."
  []
  (->> @app-states
       (filter (fn [[k v]] (and (string? k) (str/includes? k "/")
                                (= :failed (:state v)))))
       (map (fn [[k v]] [k (:error-msg v)]))
       (into {})))

(defn ^{:malli/schema [:=> [:cat :any :any :map :any :any] :map]}
  dev-state-for
  "Pure derivation of a session's dev-resource state from real state: the
   registry entry (looked up by worktree path), a TCP probe of its app port,
   and the optimistic app-states. Returns {:state … :url :error-msg}.
   No :none — every session can host resources; :down just means 'not up'."
  [wt-path instance-id registry probe app-state-fn]
  (let [entry      (get registry wt-path)
        port       (:app-port entry)
        live?      (and (pos-int? port) (probe port))
        pending    (app-state-fn instance-id)
        pending-kw (cond (map? pending)     (:state pending)
                         (keyword? pending) pending)]
    (cond
      live?      {:state :running :url (:url entry)}
      pending-kw {:state pending-kw :error-msg (when (map? pending) (:error-msg pending))}
      :else      {:state :down})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :any [:? :map]] :map]}
  session-dev-state
  "Derived dev-resource state for one session of `project`. The 2-arity reads
   the live registry; the 3-arity reuses a pre-read registry so batch callers
   (a whole workstream's sessions) read the registry once, not per session.
   Resolves worktree path + canonical instance-id via lifecycle/session-coords
   (so slash-namespaced names resolve correctly), then derives via dev-state-for."
  ([project session] (session-dev-state project session (state/read-registry)))
  ([project session registry]
   (let [{:keys [wt-path instance-id]} (lifecycle/session-coords session {:project project})]
     (dev-state-for wt-path instance-id registry proc/tcp-open? current-app-state))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :map]}
  ws-session-dev-states
  "Map of session-name → derived dev-resource state for a workstream's sessions.
   Reads the registry once (not once per session) so a pane poll is O(1) registry
   IO + one probe per session."
  [project ws]
  (let [sessions (:sessions ws)]
    (if (empty? sessions)
      {}
      (let [registry (state/read-registry)]
        (into {} (for [{:keys [name]} sessions]
                   [name (session-dev-state project name registry)]))))))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :int]]}
  app-port-for-instance
  "Look up the app port stored in the session state file for this instance,
   so we can probe TCP after a lifecycle action completes."
  [instance-id]
  (some-> (state/read-session instance-id)
          (get-in [:service-states :app :app-port])))

(defn- stop-session-blocking!
  "Down one session and clear its optimistic state. Blocking — callers own the
   thread."
  [session opts instance-id]
  (lifecycle/down! session opts)
  (clear-app-state! instance-id))

(defn ^{:malli/schema [:=> [:cat :ProjectName :any] :any]}
  stop-session!
  "Bring ONE session down on a background thread, tracking optimistic state under
   its canonical instance-id and capturing a failure into it.

   The session-scoped teardown. `work/bring-down!` is the WORKSTREAM-scoped
   fan-out over the same `lifecycle/down!`: it downs every session its workstream
   owns, which is right for a closed workstream's leftovers and wrong for a
   single idle session whose siblings may be in use. Anything acting on one
   session belongs here."
  [project session]
  (let [{:keys [wt-path instance-id]} (lifecycle/session-coords session {:project project})
        profile (engine/read-profile-for-session wt-path)
        opts    (cond-> {:project project} profile (assoc :profile profile))]
    (set-app-state! instance-id :stopping)
    (future
      (try (stop-session-blocking! session opts instance-id)
           (catch Exception e
             (set-app-state! instance-id :failed
                             (or (:error-msg (ex-data e)) (ex-message e))))))
    instance-id))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :keyword] :any]}
  dev-action!
  "Run a DEV-ENVIRONMENT lifecycle action for one session on a background
   thread, tracking optimistic state in app-states (keyed by the canonical
   instance-id). `start` re-hydrates a reclaimed session-home via
   work/ensure-open! before up!, and reuses the session's persisted profile
   (so a :lite session doesn't get all services). `stop` brings it down."
  [project ws-id session action]
  (let [{:keys [wt-path instance-id]} (lifecycle/session-coords session {:project project})
        profile (engine/read-profile-for-session wt-path)
        opts    (cond-> {:project project} profile (assoc :profile profile))
        pending (case action "start" :starting "stop" :stopping "restart" :restarting nil)]
    (when pending (set-app-state! instance-id pending))
    (future
      (try
        (case action
          "start"
          (do (work/ensure-open! project ws-id session)
              (lifecycle/up! session opts)
              (let [port (app-port-for-instance instance-id)]
                (if (and (pos-int? port) (proc/tcp-open? port))
                  (clear-app-state! instance-id)
                  (set-app-state! instance-id :failed
                                  "App did not open its port within the timeout — see eval log"))))
          "stop"
          (stop-session-blocking! session opts instance-id)
          "restart"
          (do (lifecycle/restart! session opts)
              (clear-app-state! instance-id))
          (do (println "[nido ui] unknown dev action:" action)
              (clear-app-state! instance-id)))
        (catch Exception e
          (set-app-state! instance-id :failed
                          (or (:error-msg (ex-data e)) (ex-message e))))))))
