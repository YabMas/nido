(ns nido.coordinator.record.tickets
  "Per-ticket status in nido. One directory per Notion ticket, keyed by
   BR-#### (Notion's unique-id property). Holds machine-readable triage
   status (meta.edn) — :status, :disposition, gate/promote decisions. Entry
   writes AND reads (triage reports, findings, …) live on the workstream's
   ledger (nido.coordinator.record.workstream/append-to-ref! / :entries), not here —
   see the ledger-unification design; `latest-triage-report` routes to the
   workstream by the BR-#### ref. See spec
   docs/superpowers/specs/2026-06-04-triage-record-store-design.md."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as cws]
   [nido.platform.io :as io]))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] :Path]}
  ticket-dir [project br-id]
  (str (fs/path (cstate/nido-root) "projects" (name project) "tickets" br-id)))

(defn- meta-path [project br-id]
  (str (fs/path (ticket-dir project br-id) "meta.edn")))

(defn- blank-br?
  "A nil or blank br-id means the run has no resolvable ticket (e.g. an
   event-payload that predates the :id field). Ticket ops no-op for such a run
   rather than NPEing in fs/path on a nil path segment."
  [br-id]
  (or (nil? br-id) (str/blank? br-id)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] [:maybe :Ticket]]}
  read-meta
  "Read a ticket's meta.edn, or nil if absent. Nil-safe for a nil/blank br-id
   (e.g. a triage run whose event-payload predates the :id field): such a run
   has no ticket record, so this returns nil rather than NPEing in fs/path."
  [project br-id]
  (when-not (blank-br? br-id)
    (io/read-edn (meta-path project br-id))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :TicketId]]}
  list-ids
  "Vector of ticket ids (dir names, e.g. BR-####) under a project's tickets
   dir; [] if the dir is absent."
  [project]
  (let [d (str (fs/path (cstate/nido-root) "projects" (name project) "tickets"))]
    (if (fs/exists? d)
      (->> (fs/list-dir d) (filter fs/directory?) (mapv #(str (fs/file-name %))))
      [])))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId :Ticket] [:maybe :Ticket]]}
  write-meta!
  "Persist a ticket's meta.edn wholesale. No-op (returns nil) for a nil/blank
   br-id — a run with no ticket has nothing to write.

   Every mutation of an EXISTING record goes through update-meta! instead; this
   is for writing a record whose content does not depend on what is already
   there, and so has nothing to lose to a concurrent writer."
  [project br-id m]
  (when-not (blank-br? br-id)
    (io/write-edn! (meta-path project br-id) m)
    m))

(defn- ^{:malli/schema [:=> [:cat :ProjectName :TicketId [:=> [:cat :any] :any]]
                       [:maybe :Ticket]]}
  update-meta!
  "Read a ticket's meta, apply `f` to it (nil when there is no record yet), and
   write the result back — as one locked operation, sharing the lock
   `io/update-edn!` would take on the same file. `f` returning nil writes
   nothing, which is how clear-status! declines to conjure a record for a ticket
   that never had one.

   Written out rather than delegated to `io/update-edn!` for exactly that
   branch: an update that may decline to write is not a function from the old
   value to the new one, and moving the decision outside the lock is the race
   this exists to prevent.

   The daemon reconciling a terminal Run and a human running `bb nido:ticket:*`
   are separate processes writing this file, and :status is what the pre-spawn
   gate reads — a lost update here is a ticket that re-triages when it should
   not, or one that never does."
  [project br-id f]
  (when-not (blank-br? br-id)
    (let [path (meta-path project br-id)]
      (io/with-file-lock
        (io/lock-path-for path)
        (fn []
          (when-let [m' (f (io/read-edn path))]
            (io/write-edn! path m')
            m'))))))

(def ^:private open-run-states
  "Run states in which a run still owns its ticket: working, or parked for a person's review."
  #{:queued :preprocessing :running :awaiting-review})

(def ^:private investigating-skills
  "The skills whose run investigates a ticket and leaves a verdict on it."
  #{:triage-bug :triage-slack :investigate-bug :add-video-provider})

(def ^:private triage-kinds
  "The entry kinds a ticket's triage leaves: the report it arrived as, the verdict on it, a
   person's acceptance, and an account of either. Any other record of work means the ticket is
   being worked past its triage."
  #{:ticket :issue :triage :triage-accepted :proposed-ticket :note})

(defn ^{:malli/schema [:=> [:cat :ProjectName :any] [:maybe :keyword]]}
  status-of
  "Where triage of the ticket whose workstream is `w` stands, read off that workstream's ledger
   and its runs — nothing stored says it. The first that holds:
     :dismissed      — its standing close is a dismissal
     :planning       — a plan run is open on it, or a person's choice to put it in
                       progress still holds (`standing/chosen-stage`)
     :implementing   — it is closed otherwise, or records work past its triage
     :triaged        — a person accepted its newest verdict
     :investigating  — a run investigating it is open
     :awaiting-input — a verdict waits for a person
     nil             — none of these: never triaged, or a triage that ended without a verdict,
                       which is what makes a ticket re-triable.
   A run is open from its minting until it ends, parked for review included.

   Nothing recorded before a dismissal that was later restored counts, verdict or work
   (`standing/restored-at`): restoring a dismissed ticket returns it to triage as it was before
   anyone looked at it.

   `except`, a run id, is left out of the runs read: the status the ticket would have without
   that run, which is how a parked run asks whether its ticket still needs it."
  ([project w] (status-of project w nil))
  ([project w except]
  (when w
    (let [ws-id  (:id w)
          open   (into #{} (comp (filter #(and (open-run-states (:state %)) (not= except (:id %))))
                                 (map :skill))
                       (runs/runs-of project ws-id))
          closed (standing/closure project ws-id w)
          v      (standing/verdict project ws-id w)]
      (cond
        (= :dismissed (:outcome closed))                            :dismissed
        (or (open :plan-bug)
            (= :in-progress (standing/chosen-stage project ws-id w)))   :planning
        (or closed
            (some #(not (or (triage-kinds (:kind %)) (cws/status-kinds (:kind %))
                            (<= (:seq %) (standing/restored-at project ws-id w))))
                  (:entries w)))                                    :implementing
        (:accepted? v)                                              :triaged
        (some investigating-skills open)                            :investigating
        v                                                           :awaiting-input)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] [:maybe :keyword]]}
  status
  "`status-of` the workstream carrying `br-id` as a ref, or nil when none does. O(workstreams)."
  [project br-id]
  (when-not (blank-br? br-id)
    (status-of project (cws/find-by-ref-id project br-id))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId :map] :Ticket]}
  open!
  "Create (or refresh the descriptive fields of) a ticket record and set status
   :investigating. `base` carries {:notion-page-id :url :title :opened-by
   :notion-last-edited-at}. Idempotent on re-open: preserves :entries."
  [project br-id base]
  (update-meta! project br-id
                (fn [prior]
                  (merge {:entries []}
                         prior
                         {:br-id br-id :status :investigating}
                         (select-keys base [:notion-page-id :url :title
                                            :opened-by :notion-last-edited-at])))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId :map] :Ticket]}
  hold-for-gate!
  "Open (or refresh) a ticket record held by an unaccepted Slack proposal: status
   :awaiting-input, opened by :slack-proposal. gate-decision reads :awaiting-input as
   owned, so no triage spawns for the ticket while a person has yet to accept the proposal,
   and `held-by-gate?` lets pickup refuse it. Accepting the proposal claims the ticket
   :planning, which ends the hold. `base` is as for open!."
  [project br-id base]
  (update-meta! project br-id
                (fn [prior]
                  (merge {:entries []}
                         prior
                         {:br-id br-id :status :awaiting-input :opened-by :slack-proposal}
                         (select-keys base [:notion-page-id :url :title])))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] :boolean]}
  held-by-gate?
  "Whether the ticket is held by an unaccepted Slack proposal (see hold-for-gate!)."
  [project br-id]
  (let [m (read-meta project br-id)]
    (and (= :awaiting-input (:status m)) (= :slack-proposal (:opened-by m)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId :keyword :any] [:maybe :Ticket]]}
  mark!
  "Set one field on an EXISTING ticket record; a ticket with no record is left without one."
  [project br-id k v]
  (update-meta! project br-id #(when % (assoc % k v))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId :keyword] [:maybe :Ticket]]}
  unmark!
  "Drop one field from an existing ticket record."
  [project br-id k]
  (update-meta! project br-id #(when % (dissoc % k))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId :keyword] :Ticket]}
  set-status! [project br-id new-status]
  (update-meta! project br-id #(assoc % :status new-status)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId :keyword :any] :Ticket]}
  complete!
  "Terminal completion of a triage verdict: accept the newest verdict on the ticket's workstream
   — a :triage-accepted citing it, or citing nothing when the ledger holds no verdict, so the
   ticket is accepted as reported — unless an acceptance already stands; then set status
   :triaged, disposition, triaged-at. A ticket no workstream carries gets one by its ref.
   (Off-radar tickets use dismiss!, not complete! — :skipped retired.)"
  [project br-id new-status disposition]
  (when-not (blank-br? br-id)
    (let [w (cws/find-or-mint-by-ref! project br-id)
          v (standing/verdict project (:id w) w)]
      (when-not (:accepted? v)
        (cws/append-entry! project (:id w) {:kind :triage-accepted}
                           (pr-str (cond-> {:format :triage-accepted}
                                     (:seq v) (assoc :triage-seq (:seq v))))))))
  (update-meta! project br-id
                (fn [m]
                  (assoc m :status new-status
                           :disposition disposition
                           :triaged-at (clock/now-iso)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] [:maybe :Ticket]]}
  clear-status!
  "Make the ticket re-triable: drop :status (the gate then returns :spawn)."
  [project br-id]
  (update-meta! project br-id #(when % (dissoc % :status))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] :Ticket]}
  dismiss!
  "Take a ticket off the triage radar: settle the workstream carrying it :dismissed — minting one
   by its ref when none does, so a never-triaged ticket can be dismissed — and set status
   :dismissed. The gate then skips it (no auto-re-triage). A workstream already standing
   dismissed is left as it is. No-op for a nil/blank br-id."
  [project br-id]
  (when-not (blank-br? br-id)
    (let [w (cws/find-or-mint-by-ref! project br-id)]
      (when-not (= :dismissed (:outcome (standing/closure project (:id w) w)))
        (cws/close! project (:id w) :dismissed nil :person))))
  (update-meta! project br-id
                #(assoc (or % {:br-id br-id :entries []}) :status :dismissed)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] [:maybe :map]]}
  latest-triage-report
  "The latest workstream-ledger entry (workstream resolved by the BR-#### ref)
   parsed as a TriageReport map, or nil when the latest entry isn't an `.edn`
   (e.g. a legacy markdown report or a non-triage entry)."
  [project br-id]
  (when-let [w (cws/find-by-ref-id project br-id)]
    (when-let [e (cws/newest-record w)]
      (when (str/ends-with? (str (:file e)) ".edn")
        (io/read-edn (str (fs/path (cstate/workstream-dir project (:id w)) (:file e))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] :map]}
  gate-decision
  "Decide the coordinator pre-spawn action from the ticket's derived `status`:
     :skip-completed — terminally handled (:triaged | :dismissed)
     :skip-active    — a run owns it, its verdict waits for a person, or it is being
                       worked past triage (:investigating | :awaiting-input | :planning |
                       :implementing)
     :spawn          — never triaged, or a triage that ended without a verdict
   so a reconcile-mode re-emit never re-triages something already handled or underway."
  [project br-id]
  (case (status project br-id)
    (:triaged :dismissed)            :skip-completed
    (:investigating :awaiting-input
     :planning :implementing)        :skip-active
    :spawn))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] :map]}
  promote-decision
  "Decide whether a ticket may be promoted to a planning Run, from its derived
   `status`:
     :promote        — status :triaged (the only promotable state)
     :skip-active    — a plan Run already owns it (:planning)
     :skip-completed — dismissed (off-radar, nothing to promote)
     :skip-no-record — never triaged
     :skip-untriaged — mid-triage (:investigating / :awaiting-input) or worked
                       past it (:implementing)"
  [project br-id]
  (case (status project br-id)
    :triaged   :promote
    :planning  :skip-active
    :dismissed :skip-completed
    nil        :skip-no-record
    :skip-untriaged))
