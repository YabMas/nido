(ns nido.coordinator.runs
  "Canonical Run record: schema, read/write, state machine.

   See spec §Runs."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.triggers :as triggers]
   [nido.io :as io]
   [nido.session.lifecycle :as session-lifecycle]
   [nido.session.state :as session-state]))

(def states
  "Permitted Run states. See spec §Runs / Lifecycle."
  #{:queued :preprocessing :running :awaiting-review :done :failed :halted :dry-run-would-fire})

(def state->phase
  "Run state → session autonomy phase. The single source of truth for mirroring
   a run onto its authoritative session (used by transition!/reconcile and by
   the migrate one-shot). :awaiting-review parks (human gate)."
  {:queued             :queued
   :preprocessing      :preprocessing
   :running            :running
   :awaiting-review    :parked
   :done               :done
   :failed             :failed
   :halted             :halted
   :dry-run-would-fire :failed})

(def Run
  [:map {:closed true}
   [:id                string?]
   [:project           keyword?]
   [:trigger           keyword?]
   [:source            [:map [:type keyword?]]]
   [:event-payload     [:map-of keyword? any?]]
   [:skill             keyword?]
   [:first-message     string?]
   [:agent             keyword?]
   [:session-name      string?]
   [:workstream-id     {:optional true} [:maybe string?]]
   [:claude-session-id [:maybe string?]]
   [:limits            [:map-of keyword? any?]]
   [:priority          int?]
   [:session-profile   keyword?]
   [:on-promote        {:optional true} [:maybe [:map-of keyword? any?]]]
   [:uncapped?         boolean?]
   [:state             (into [:enum] states)]
   [:state-history     [:vector [:map
                                 [:at    string?]
                                 [:state (into [:enum] states)]]]]
   [:artifacts         [:vector [:map
                                 [:path string?]
                                 [:written-at string?]]]]
   [:error             [:maybe [:map-of keyword? any?]]]])

(defn validate
  "Returns the run or throws ex-info with humanized errors."
  [run]
  (if (m/validate Run run)
    run
    (throw (ex-info "Invalid Run record"
                    {:errors (m/explain Run run)
                     :run    run}))))

(defn read-run
  "Read a run.edn by id. Returns nil if absent.
   Normalizes legacy records: backfills :priority 0 when the key is absent
   so that pre-Plan-A on-disk Runs pass the closed schema on write-back."
  [run-id]
  (let [path (cstate/run-edn-path run-id)]
    (when (fs/exists? path)
      (-> (io/read-edn path)
          (update :priority        #(if (int? %) % 0))
          (update :session-profile #(if (keyword? %) % :full))
          (update :uncapped?       #(if (boolean? %) % false))))))

(defn write-run!
  "Validate then write a Run record. Parent dir must already exist."
  [run]
  (validate run)
  (io/write-edn! (cstate/run-edn-path (:id run)) run)
  run)

(def in-progress-states
  "Run states that occupy a trigger's in-flight budget: promoted and not yet
   terminal. :queued is the pending pool and deliberately does NOT count."
  #{:preprocessing :running :awaiting-review})

(defn list-run-ids
  "Vector of run-ids (directory names) under the runs dir; [] if none."
  []
  (let [d (cstate/runs-dir)]
    (if (fs/exists? d)
      (->> (fs/list-dir d)
           (filter fs/directory?)
           (mapv #(str (fs/file-name %))))
      [])))

(defn in-progress-count-by-trigger
  "Map {trigger-kw → count} of runs in an in-progress state, grouped by
   :trigger. Scans the runs dir. The scheduler reads this to enforce per-trigger
   :max-in-flight caps; recomputed each tick so it is restart-safe."
  []
  (->> (list-run-ids)
       (keep read-run)
       (filter #(contains? in-progress-states (:state %)))
       (reduce (fn [m r] (update m (:trigger r) (fnil inc 0))) {})))

(def allowed-transitions
  "Map of from-state → set of to-states.
   See spec §Runs / Lifecycle. Terminal states have no entries.
   :preprocessing is an optional phase between :queued and :running, used
   by triggers with a :preprocess config. Triggers without :preprocess skip
   directly :queued → :running (both remain valid)."
  {:queued          #{:preprocessing :running :failed :halted :dry-run-would-fire}
   :preprocessing   #{:running :failed :halted}
   :running         #{:awaiting-review :done :failed :halted}
   :awaiting-review #{:running :done :failed :halted}})

(defn valid-transition?
  "True iff `from` → `to` is in `allowed-transitions`. Terminal states
   have no entry and so reject every transition."
  [from to]
  (contains? (get allowed-transitions from #{}) to))

(declare mirror-run-phase!)

(defn transition!
  "Atomically update a Run's state with history. Throws ex-info if the
   run is absent or the transition is invalid. Returns the updated Run."
  [run-id new-state]
  (let [run  (or (read-run run-id)
                 (throw (ex-info "Run not found" {:run-id run-id})))
        from (:state run)]
    (when-not (valid-transition? from new-state)
      (throw (ex-info "Invalid transition"
                      {:run-id run-id :from from :to new-state})))
    (let [updated (-> run
                      (assoc :state new-state)
                      (update :state-history conj
                              {:at (clock/now-iso) :state new-state}))]
      (write-run! updated)
      (mirror-run-phase! updated)
      updated)))

(defn mirror-run-phase!
  "Best-effort mirror of a run's state onto its authoritative session's autonomy
   phase. No-op when the run has no :workstream-id (legacy / dry-run / test runs).
   Never throws — a missing or human session is logged to stderr and swallowed so
   it can't re-fail a transition or reconcile pass."
  [run]
  (when-let [ws-id (:workstream-id run)]
    (when-let [phase (state->phase (:state run))]
      (try
        (session/set-phase! (:project run) ws-id (:session-name run) phase)
        (catch Exception e
          (binding [*err* *err*]
            (.println ^java.io.PrintWriter *err*
                      (str "nido coordinator: phase mirror failed for "
                           (:session-name run) " → " phase " — " (ex-message e)))))))))

(defn- new-run-parts
  "Returns {:run-id ... :session-name ... :suffix ...} so callers don't have
   to re-derive the session-name from the run-id by string surgery."
  [project trigger-name]
  ;; clock/now-iso is ISO-8601 (YYYY-MM-DDTHH:...Z); first 10 chars = date.
  (let [date (subs (clock/now-iso) 0 10)
        suf  (subs (str (java.util.UUID/randomUUID)) 0 8)]
    {:run-id       (str date "-" (name project) "-" (name trigger-name) "-" suf)
     :session-name (str "run-" (name project) "-" (name trigger-name) "-" suf)
     :suffix       suf}))

(def ^:private session-title-slug-max 40)

(defn- slugify
  "Lower-case `s` into a `-`-delimited alphanumeric slug. When longer than `n`,
   cap it near `n` on a word boundary (no mid-word cut; falls back to a hard cut
   for a single over-long token). No leading/trailing dashes."
  [s n]
  (let [base (-> (str s)
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (if (<= (count base) n)
      base
      (let [cut         (subs base 0 n)
            at-boundary (str/replace cut #"-[^-]*$" "")]
        (if (seq at-boundary) at-boundary cut)))))

(defn- ticket-session-name
  "When the trigger sets :session-name-prefix and the payload carries an :id
   (e.g. a BR-#### from promote), build a stable, recognizable session-name:
   `<prefix><br-slug>[-<title-slug>]` — e.g. \"impl-br-4659-firefox-loading\".
   Falls back to the random per-run name when there's no prefix or no :id."
  [trigger payload random-name]
  (if-let [prefix (:session-name-prefix trigger)]
    (if-let [id (:id payload)]
      (let [title (some-> (:title payload) (slugify session-title-slug-max))]
        (str prefix (slugify id session-title-slug-max)
             (when (seq title) (str "-" title))))
      random-name)
    random-name))

(defn create-run!
  "Build a :queued Run record from a fire request and persist run.edn.
   `meta` carries source-call metadata: {:fired-at <iso> :fired-by <str>}."
  [{:keys [project trigger payload priority session-profile uncapped? workstream-id]} meta]
  (let [{:keys [run-id session-name]} (new-run-parts project (:name trigger))
        session-name (ticket-session-name trigger payload session-name)
        ;; First message format per spec §Agent launch: "/<skill> <interpolated-payload>".
        ;; The trigger's :payload holds just the skill args; the framework prepends "/<skill> ".
        message (str "/" (name (:skill trigger)) " "
                     (triggers/render-payload (:payload trigger) payload))
        run     {:id              run-id
                 :project         project
                 :trigger         (:name trigger)
                 ;; Preserve all source config keys (e.g. :database, :view for :notion-view)
                 ;; so the Run record stays useful for debugging non-:manual sources later.
                 :source          (merge (:source trigger) meta)
                 :event-payload   payload
                 :skill           (:skill trigger)
                 :first-message   message
                 :agent           (or (:agent trigger) :claude)
                 :session-name    session-name
                 :workstream-id   workstream-id
                 :claude-session-id nil
                 :limits          (or (:limits trigger) {:budget "30m" :max-failures 3})
                 :priority        (if (int? priority) priority 0)
                 :session-profile (or session-profile :full)
                 :on-promote      (:on-promote trigger)
                 :uncapped?       (boolean uncapped?)
                 :state           :queued
                 :state-history   [{:at (clock/now-iso) :state :queued}]
                 :artifacts       []
                 :error           nil}]
    (fs/create-dirs (cstate/run-dir run-id))
    (fs/create-dirs (cstate/run-artifacts-dir run-id))
    (write-run! run)))

(defn spawn-session-for-run!
  "Bring up a session for the given Run, marked :owned-by-run. The launcher
   picks up :owned-by-run in the session-edn and writes the resume shim +
   run-link via nido.coordinator.shim. After session-up, also writes the
   reverse `<run-dir>/session-home` symlink the coordinator uses to locate
   the worktree (per spec §Runs / Identity & storage). Returns whatever
   session-lifecycle/up! returns."
  [run]
  (let [{:keys [project session-name id session-profile]} run
        result       (session-lifecycle/up! session-name
                                            {:project         project
                                             :owned-by-run    id
                                             :session-profile session-profile})
        session-home (session-state/session-home-dir (name project) session-name)
        link-path    (cstate/run-session-home-link id)]
    (when (fs/exists? link-path) (fs/delete link-path))
    (fs/create-sym-link link-path session-home)
    result))

(defn teardown-session-for-run!
  "Reclaim the session a Run spawned, once the Run reaches a resolved-terminal
   state (:done / :failed / :halted). Stops PG + JVM + app, removes the registry
   entry (so the session leaves the CLI/TUI list), removes the worktree (a
   symlink-only removal for :lite sessions — the shared checkout is never
   touched) and drops the per-session state-dir. Also removes the cosmetic
   session-home dir + the run's session-home link.

   Deliberately NOT called for :awaiting-review — that session is the human's
   review surface and must stay up. The run dir (artifacts, agent.log, run.edn)
   under ~/.nido/runs is never touched, so a failed/done run stays inspectable.

   NO-OP for :plan-bug runs: a provision-only impl session is HANDED to the
   human at provision — it's their workspace, not a coordinator-owned ephemeral
   surface. The run goes :done (to free the trigger's slot) the moment
   /continue-ticket sets the ticket :implementing, but the session must OUTLIVE
   the run; reclaiming it would delete the worktree out from under the person
   working in it. The human brings it down explicitly (bb nido:session:down).

   Best-effort: a missing/already-gone session logs and returns nil rather than
   throwing — teardown must never re-fail a run that already reached terminal."
  [run]
  (when-not (= :plan-bug (:skill run))
   (let [{:keys [project session-name id]} run]
    (try
      (session-lifecycle/destroy! session-name {:project project})
      (catch Exception e
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "nido coordinator: teardown destroy! failed for "
                         session-name " — " (ex-message e))))))
    (try
      (let [home (session-state/session-home-dir (name project) session-name)]
        (when (fs/exists? home) (fs/delete-tree home)))
      (when-let [link (cstate/run-session-home-link id)]
        (when (fs/exists? link) (fs/delete link)))
      (catch Exception e
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "nido coordinator: teardown home-cleanup failed for "
                         session-name " — " (ex-message e))))))
    nil)))
