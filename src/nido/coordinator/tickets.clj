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

(defn read-meta [project br-id]
  (io/read-edn (meta-path project br-id)))

(defn write-meta! [project br-id m]
  (io/write-edn! (meta-path project br-id) m)
  m)

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
  "Terminal completion: set status (:triaged | :skipped), disposition, triaged-at."
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

(defn append-entry!
  "Write a new immutable entry file under entries/ and record it in meta :entries.
   `entry` = {:kind <kw> :session <str> :run-id <str>}. Returns the file path."
  [project br-id entry content]
  (let [m       (read-meta project br-id)
        seq-n   (inc (count (:entries m)))
        fname   (format "%04d-%s.md" seq-n (name (:kind entry)))
        rel     (str "entries/" fname)
        abs     (str (fs/path (ticket-dir project br-id) rel))]
    (io/write-text! abs content)
    (write-meta! project br-id
                 (update m :entries (fnil conj [])
                         (assoc entry :seq seq-n :at (clock/now-iso) :file rel)))
    abs))

(defn gate-decision
  "Decide the coordinator pre-spawn action by reading the ticket's meta status
   from disk:
     :skip-completed — already triaged/skipped
     :skip-active    — a session already owns it (:investigating / :awaiting-input)
     :spawn          — no record, or status cleared (re-triable)"
  [project br-id]
  (case (status project br-id)
    (:triaged :skipped)              :skip-completed
    (:investigating :awaiting-input) :skip-active
    :spawn))

(defn on-run-terminal!
  "Reconcile a ticket's meta when its triage Run reaches a terminal/parked
   coordinator state. No-op for non-triage runs and for tickets with no record.
   - run ended :awaiting-review  → leave (session parked at :awaiting-input)
   - meta already :triaged/:skipped → leave (skill wrote the disposition)
   - otherwise (e.g. :failed with stale :investigating) → clear → re-triable."
  [run run-state]
  (when (= :triage-bug (:skill run))
    (let [project (:project run)
          br-id   (some-> run :event-payload :id)]
      (when (and br-id (not (str/blank? br-id)))
        (when-let [m (read-meta project br-id)]
          (cond
            (= :awaiting-review run-state)     nil
            (#{:triaged :skipped} (:status m)) nil
            :else (clear-status! project br-id)))))))
