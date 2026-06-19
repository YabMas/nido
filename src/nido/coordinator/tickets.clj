(ns nido.coordinator.tickets
  "Per-ticket work ledger in nido. One directory per Notion ticket, keyed by
   BR-#### (Notion's unique-id property). Holds machine-readable triage state
   (meta.edn) plus an append-only entry log. Triage is the first writer; later
   sessions will append entries. See spec
   docs/superpowers/specs/2026-06-04-triage-record-store-design.md."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn ticket-dir [project br-id]
  (str (fs/path (cstate/nido-root) "projects" (name project) "tickets" br-id)))

(defn- meta-path [project br-id]
  (str (fs/path (ticket-dir project br-id) "meta.edn")))

(defn- blank-br?
  "A nil or blank br-id means the run has no resolvable ticket (e.g. an
   event-payload that predates the :id field). Ticket ops no-op for such a run
   rather than NPEing in fs/path on a nil path segment."
  [br-id]
  (or (nil? br-id) (str/blank? br-id)))

(defn read-meta
  "Read a ticket's meta.edn, or nil if absent. Nil-safe for a nil/blank br-id
   (e.g. a triage run whose event-payload predates the :id field): such a run
   has no ticket record, so this returns nil rather than NPEing in fs/path."
  [project br-id]
  (when-not (blank-br? br-id)
    (io/read-edn (meta-path project br-id))))

(defn write-meta!
  "Persist a ticket's meta.edn. No-op (returns nil) for a nil/blank br-id — a
   run with no ticket has nothing to write. This is the write chokepoint, so
   open!/set-status!/complete! inherit the nil-safety."
  [project br-id m]
  (when-not (blank-br? br-id)
    (io/write-edn! (meta-path project br-id) m)
    m))

(defn status [project br-id]
  (:status (read-meta project br-id)))

(defn open!
  "Create (or refresh the descriptive fields of) a ticket record and set status
   :investigating. `base` carries {:notion-page-id :url :title :opened-by
   :notion-last-edited-at}. Idempotent on re-open: preserves :entries."
  [project br-id base]
  (let [prior (read-meta project br-id)]
    (write-meta! project br-id
                 (merge {:entries []}
                        prior
                        {:br-id br-id :status :investigating}
                        (select-keys base [:notion-page-id :url :title
                                           :opened-by :notion-last-edited-at])))))

(defn set-status! [project br-id new-status]
  (write-meta! project br-id (assoc (read-meta project br-id) :status new-status)))

(defn complete!
  "Terminal completion of a triage verdict: set status :triaged, disposition,
   triaged-at. (Off-radar tickets use dismiss!, not complete! — :skipped retired.)"
  [project br-id new-status disposition]
  (write-meta! project br-id
               (assoc (read-meta project br-id)
                      :status new-status
                      :disposition disposition
                      :triaged-at (clock/now-iso))))

(defn clear-status!
  "Make the ticket re-triable: drop :status (the gate then returns :spawn)."
  [project br-id]
  (when-let [m (read-meta project br-id)]
    (write-meta! project br-id (dissoc m :status))))

(defn dismiss!
  "Take a ticket off the triage radar: set status :dismissed. Creates the record
   if absent so a never-triaged ticket can be dismissed. The gate then skips it
   (no auto-re-triage) and derive-stage projects it out of the triage queue.
   No-op for a nil/blank br-id (inherits write-meta!'s chokepoint nil-safety)."
  [project br-id]
  (write-meta! project br-id
               (assoc (or (read-meta project br-id) {:br-id br-id :entries []})
                      :status :dismissed)))

(defn append-entry!
  "Write a new immutable entry file under entries/ and record it in meta :entries.
   `entry` = {:kind <kw> :session <str> :run-id <str>}. Returns the file path."
  [project br-id entry content]
  (when-not (blank-br? br-id)
    (let [m       (read-meta project br-id)
          seq-n   (inc (count (:entries m)))
          fname   (format "%04d-%s.md" seq-n (name (:kind entry)))
          rel     (str "entries/" fname)
          abs     (str (fs/path (ticket-dir project br-id) rel))]
      (io/write-text! abs content)
      (write-meta! project br-id
                   (update m :entries (fnil conj [])
                           (assoc entry :seq seq-n :at (clock/now-iso) :file rel)))
      abs)))

(defn gate-decision
  "Decide the coordinator pre-spawn action by reading the ticket's meta status
   from disk:
     :skip-completed — terminally handled (:triaged | :dismissed)
     :skip-active    — owned by a session or started elsewhere
                       (:investigating | :awaiting-input | :planning | :implementing)
     :spawn          — no record, or status cleared (re-triable)
   Dismissed/in-progress tickets are skipped so reconcile-mode re-emits never
   re-triage something already handled or started in a different state."
  [project br-id]
  (case (status project br-id)
    (:triaged :dismissed)            :skip-completed
    (:investigating :awaiting-input
     :planning :implementing)        :skip-active
    :spawn))

(defn promote-decision
  "Decide whether a ticket may be promoted to a planning Run, by reading its
   meta status:
     :promote        — status :triaged (the only promotable state)
     :skip-active    — a plan Run already owns it (:planning)
     :skip-completed — dismissed (off-radar, nothing to promote)
     :skip-no-record — never triaged (no record)
     :skip-untriaged — mid-triage (:investigating / :awaiting-input) or any
                       other non-:triaged status"
  [project br-id]
  (case (status project br-id)
    :triaged   :promote
    :planning  :skip-active
    :dismissed :skip-completed
    nil        :skip-no-record
    :skip-untriaged))

(defn on-run-terminal!
  "Reconcile a ticket's meta when its triage/plan Run reaches a terminal or
   parked coordinator state. No-op for other skills and record-less tickets.
   - run ended :awaiting-review        → leave (session parked)
   - meta already :triaged/:dismissed  → leave (terminal disposition recorded)
   - plan Run, ticket already advanced past :planning (e.g. the promote burst
     drove it to :implementing) → leave: ownership handed off, the plan Run's
     termination is not a failure of the work in flight.
   - otherwise (abnormal/stale status):
       :triage-bug → clear   (drop status → re-triable)
       :plan-bug   → :triaged (revert → re-promotable, preserving triage)
                     ONLY while the plan Run still owns the ticket (:planning)."
  [run run-state]
  (let [skill   (:skill run)
        project (:project run)
        br-id   (some-> run :event-payload :id)]
    (when (and (#{:triage-bug :plan-bug} skill) br-id (not (str/blank? br-id)))
      (when-let [m (read-meta project br-id)]
        (cond
          (= :awaiting-review run-state)        nil
          (#{:triaged :dismissed} (:status m))  nil
          (= :plan-bug skill)                (when (= :planning (:status m))
                                               (set-status! project br-id :triaged))
          :else                              (clear-status! project br-id))))))
