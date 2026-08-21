(ns nido.coordinator.workstream
  "The workstream: the source-agnostic spine. Owns the append-only log and the
   descriptive workflow stage. Engagement state is NOT stored here — it is a
   projection over this workstream's session records (see nido.coordinator.session).
   See spec docs/superpowers/specs/2026-06-05-workstream-session-model-design.md."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.report :as report]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def ExternalRef
  [:map {:closed true}
   [:adapter keyword?]
   [:id      string?]
   [:page-id {:optional true} [:maybe string?]]
   [:url     {:optional true} [:maybe string?]]
   [:title   {:optional true} [:maybe string?]]])

(def IntakePayload
  "What a queue-mode intake stores so promote can rebuild the triage fire."
  [:map {:closed true}
   [:trigger keyword?]
   [:payload [:map-of keyword? any?]]])

(def Workstream
  [:map {:closed true}
   [:id            string?]
   [:project       keyword?]
   [:external-refs [:vector ExternalRef]]
   ;; Deliberately looser than session/storable-stages, which create! and
   ;; advance-stage! enforce. The vocabulary is closed at the SETTERS, not here,
   ;; so a record that acquired a foreign stage some other way (a hand-edited
   ;; edn, a pre-guard write) stays readable, closable and repairable instead of
   ;; wedging every subsequent write! of it — including the advance-stage! back
   ;; to a legal stage that fixes it. :stage-history is looser still, and stays
   ;; that way: it is the record of what happened, mistakes included.
   [:stage         keyword?]
   [:stage-history [:vector [:map [:at string?] [:stage keyword?]]]]
   ;; :dismissed is the nido-side veto (work/dismiss!). It is a distinct outcome
   ;; rather than :dropped because it is the ONLY carrier of the veto on a
   ;; workstream with no ledger ref — there is no ticket record to stamp, so
   ;; without it the row folds to :done and leaves every board band. Widening is
   ;; backward-compatible: stored records only ever carry :done/:dropped.
   [:closed        [:maybe [:map
                            [:at      string?]
                            [:outcome [:enum :done :dropped :dismissed]]]]]
   [:created-at    string?]
   [:entries       [:vector [:map-of keyword? any?]]]
   [:intake        {:optional true} [:maybe IntakePayload]]
   [:facets        {:optional true} [:map-of keyword? any?]]
   [:findings      {:optional true} [:maybe [:map-of keyword? any?]]]])

(defn validate [w]
  (if (m/validate Workstream w)
    w
    (throw (ex-info "Invalid Workstream record"
                    {:errors (m/explain Workstream w) :workstream w}))))

(defn mint-id
  "ws-YYYYMMDD-<rand6>. Date from clock/now-iso (ISO-8601, first 10 chars =
   YYYY-MM-DD); dashes stripped so the id is a single token."
  []
  (let [date (-> (clock/now-iso) (subs 0 10) (str/replace "-" ""))
        suf  (subs (str (java.util.UUID/randomUUID)) 0 6)]
    (str "ws-" date "-" suf)))

(defn- normalize-legacy-stage
  "Back-compat: the holding-pen stage was renamed :inbox → :incoming. Records
   persisted before the rename still carry :stage :inbox; map them on read so
   every consumer sees the canonical :incoming."
  [w]
  (cond-> w (= :inbox (:stage w)) (assoc :stage :incoming)))

(defn read-ws
  "Read a workstream.edn by project + id. Returns nil if absent. Normalizes the
   legacy :inbox stage to :incoming (see normalize-legacy-stage)."
  [project ws-id]
  (some-> (io/read-edn (cstate/workstream-edn-path project ws-id))
          normalize-legacy-stage))

(defn write!
  "Validate then write a Workstream record (atomic; parent dirs created)."
  [w]
  (validate w)
  (io/write-edn! (cstate/workstream-edn-path (:project w) (:id w)) w)
  w)

(defn- check-stage!
  "Throw unless `stage` is in the closed vocabulary (session/storable-stages).
   The vocabulary has two tiers and only this check spans both, so it is the one
   place a stage the rest of nido cannot act on gets refused. Without it a stage
   outside the set was written happily and then ignored by every projection: the
   workstream simply stopped appearing where you put it."
  [stage where]
  (when-not (contains? session/storable-stages stage)
    (throw (ex-info (str "Unknown workstream stage " (pr-str stage)
                         " — nothing projects it, so the workstream would go missing")
                    {:stage    stage
                     :where    where
                     :storable (vec (sort session/storable-stages))
                     :hint     (str "Board stages: " (vec (sort session/lifecycle-stages))
                                    ". Ticket statuses (:planning/:implementing/…) are a "
                                    "different vocabulary — :implementing is :in-progress here.")}))))

(defn create!
  "Mint an id and persist a fresh workstream at the given stage. `base` may
   carry :external-refs (default []), :intake {:trigger <kw> :payload <map>}
   (default absent), :facets <map> (default absent), and must carry :stage."
  [project {:keys [stage external-refs intake facets]}]
  (check-stage! stage :create!)
  (let [now (clock/now-iso)
        w   (cond-> {:id            (mint-id)
                     :project       project
                     :external-refs (vec (or external-refs []))
                     :stage         stage
                     :stage-history [{:at now :stage stage}]
                     :closed        nil
                     :created-at    now
                     :entries       []}
              intake (assoc :intake intake)
              (seq facets) (assoc :facets facets))]
    (write! w)))

(defn advance-stage!
  "Move a workstream to `new-stage`, appending to :stage-history. No-op (no
   history entry) when already at `new-stage`. Throws if the workstream is
   absent, or if `new-stage` is outside session/storable-stages — checked ahead
   of the no-op, so re-setting a foreign stage is refused rather than quietly
   accepted. Returns the updated record."
  [project ws-id new-stage]
  (check-stage! new-stage :advance-stage!)
  (let [w (or (read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
    (if (= new-stage (:stage w))
      w
      (write! (-> w
                  (assoc :stage new-stage)
                  (update :stage-history conj {:at (clock/now-iso) :stage new-stage}))))))

(defn set-facets!
  "Overwrite a workstream's :facets map. Throws if the workstream is absent.
   Returns the updated record. An empty map removes the key."
  [project ws-id facets]
  (let [w (read-ws project ws-id)]
    (when-not w
      (throw (ex-info "Cannot set facets on absent workstream"
                      {:project project :ws-id ws-id})))
    (write! (if (seq facets) (assoc w :facets facets) (dissoc w :facets)))))

(defn close!
  "Settle a workstream terminally. `outcome` is :done, :dropped or :dismissed.
   No consumer branches on the value — every reader tests :closed for presence
   (engagement, the notion-sync/facets candidate sets) or renders (name outcome);
   the one exception is workstreams-view/workstream-row, which reads :dismissed
   as the board veto. Idempotent write of :closed. Returns the updated record."
  [project ws-id outcome]
  (let [w (or (read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
    (write! (assoc w :closed {:at (clock/now-iso) :outcome outcome}))))

(defn reopen!
  "Un-terminalize a settled workstream: clear :closed, set :stage, and record a
   stage-history entry marked :reopened. No-op write when already open at `stage`.
   Throws if the workstream is absent. Returns the record."
  [project ws-id stage]
  (let [w (or (read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
    (if (and (nil? (:closed w)) (= stage (:stage w)))
      w
      (write! (-> w
                  (assoc :closed nil)
                  (assoc :stage stage)
                  (update :stage-history (fnil conj [])
                          {:at (clock/now-iso) :stage stage :reopened true}))))))

(defn set-findings!
  "Overwrite the workstream's live findings tracker, or remove it when `tracker`
   is nil/empty. Throws if the workstream is absent. Returns the record."
  [project ws-id tracker]
  (let [w (or (read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
    (write! (if (seq tracker) (assoc w :findings tracker) (dissoc w :findings)))))

(defn- check-baseline-ref!
  "A :design record's :baseline names the entry it was judged against. The schema
   sees one record and cannot resolve a :seq, so the check belongs here — the one
   place that holds both the record and the ledger it is joining.

   Rejecting a dangling ref matters more than it looks: the baseline is the whole
   yardstick, and a :seq pointing at nothing reads downstream exactly like one
   pointing at a real record, so the design would appear to have been judged
   against something when it had not been."
  [w kind payload]
  (when (= :design kind)
    (when-let [n (get-in (edn/read-string payload) [:baseline :seq])]
      (when-not (some #(and (= n (:seq %)) (= :baseline (:kind %))) (:entries w))
        (throw (ex-info (str "Design cites baseline entry " n
                             ", which is not a :baseline on this workstream")
                        {:seq n
                         :baselines (->> (:entries w)
                                         (filter #(= :baseline (:kind %)))
                                         (mapv :seq))}))))))

(defn append-entry!
  "Write an immutable entry file under entries/ and record it in :entries.
   `entry` = {:kind <kw> :session <str>?}. Returns the absolute file path."
  [project ws-id entry content]
  (let [w     (or (read-ws project ws-id)
                  (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
        seq-n (inc (count (:entries w)))
        [ext payload] (report/entry-payload (:kind entry) content)
        _     (check-baseline-ref! w (:kind entry) payload)
        fname (format "%04d-%s.%s" seq-n (name (:kind entry)) ext)
        rel   (str "entries/" fname)
        abs   (str (fs/path (cstate/workstream-dir project ws-id) rel))]
    (io/write-text! abs payload)
    (write! (update w :entries (fnil conj [])
                    (assoc entry :seq seq-n :at (clock/now-iso) :file rel)))
    abs))

(defn latest-entry
  "The most recent typed entry of `kind` on this workstream — parsed, validated,
   and stamped with its :seq and :at — or nil. Degrades to nil on a missing,
   unreadable, or schema-failing payload: a reader asking for the design record
   must be able to find none without the caller crashing.

   Validates through report/parse-event, NOT validate-event: an entry is immutable,
   so the question is whether it was valid when written, not whether it would be
   accepted today. Because the failure here is swallowed, getting that backwards
   does not raise — it just makes old records stop existing."
  [project ws-id kind]
  (when-let [w (read-ws project ws-id)]
    (when-let [e (->> (:entries w)
                      (filter #(= kind (:kind %)))
                      (sort-by :seq)
                      last)]
      (let [f (str (fs/path (cstate/workstream-dir project ws-id) (:file e)))]
        (try (-> (report/parse-event kind (io/read-edn f))
                 (assoc :seq (:seq e) :at (:at e)))
             (catch Throwable _ nil))))))

(defn entry-at-seq
  "The typed entry at `seq-n` on this workstream, parsed through the READ contract
   and stamped with :seq/:at — or nil. Same degrade-to-nil contract as
   latest-entry: a reader following a citation must be able to find nothing
   without the caller crashing.

   Exists because a design record cites its baseline by :seq, and 'the latest
   baseline' is not the same thing — a workstream may survey twice, and the
   design was judged against one of them specifically."
  [project ws-id seq-n]
  (when-let [w (read-ws project ws-id)]
    (when-let [e (->> (:entries w) (filter #(= seq-n (:seq %))) first)]
      (let [f (str (fs/path (cstate/workstream-dir project ws-id) (:file e)))]
        (try (-> (report/parse-event (:kind e) (io/read-edn f))
                 (assoc :seq (:seq e) :at (:at e)))
             (catch Throwable _ nil))))))

(defn list-ids
  "Vector of ws-ids under a project's workstreams dir; [] if none."
  [project]
  (let [d (cstate/workstreams-dir project)]
    (if (fs/exists? d)
      (->> (fs/list-dir d) (filter fs/directory?) (mapv #(str (fs/file-name %))))
      [])))

(defn- record-pr-opened!
  "Append the :pr-opened event for a freshly-stamped :github ref, unless this
   workstream already carries one. Stacks stamp one ref per layer but ship once,
   so the FIRST layer's ref is the shipment's mark on the timeline and the rest
   are silent — the per-layer detail already lives in the refs themselves and in
   :implementation-completed's :artifacts.

   Skipped (loudly) without a :url and :title, which PrOpened requires. The ref
   is already written by then: correlation is what the merge poller needs, and it
   must never be lost to a malformed event."
  [project w {:keys [url title]} summary]
  (when-not (some #(= :pr-opened (:kind %)) (:entries w))
    (if (or (str/blank? url) (str/blank? title))
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: github ref on " (:id w)
                       " carries no :url/:title; skipping the :pr-opened ledger event")))
      (append-entry! project (:id w) {:kind :pr-opened}
                     (pr-str (cond-> {:format :pr-opened :url url :title title}
                               (not (str/blank? summary)) (assoc :summary summary)))))))

(defn add-ref!
  "Append an external ref, deduped on (adapter, id). Returns updated record.

   A :github ref carries a second obligation: it is the PR, so stamping it also
   files the :pr-opened event (see record-pr-opened!). The two used to be separate
   steps in the prepare-draft-pr skill, and they diverged exactly as you would
   expect — the ref is load-bearing (the merge poller correlates on it) so it
   always landed, while the ledger event, being merely informative, was dropped on
   more than half the PRs. One fact, one call, nothing left to remember. `opts`
   may carry a :summary — the one part of the event nido cannot synthesize."
  ([project ws-id ref] (add-ref! project ws-id ref nil))
  ([project ws-id ref {:keys [summary]}]
   (let [w (or (read-ws project ws-id)
               (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
         dup? (some #(and (= (:adapter %) (:adapter ref)) (= (:id %) (:id ref)))
                    (:external-refs w))]
     (if dup?
       w
       (let [w' (write! (update w :external-refs (fnil conj []) ref))]
         (when (= :github (:adapter ref))
           (record-pr-opened! project w' ref summary))
         w')))))

(defn delete!
  "Remove a workstream's directory (and everything under it). Idempotent —
   no-op if absent. Used to clean up an orphan minted by a failed spawn."
  [project ws-id]
  (let [d (cstate/workstream-dir project ws-id)]
    (when (fs/exists? d) (fs/delete-tree d))))

(defn find-by-ref
  "Scan a project's workstreams for one carrying an external ref matching
   (adapter, external-id). Returns the workstream record or nil."
  [project adapter external-id]
  (->> (list-ids project)
       (keep #(read-ws project %))
       (some (fn [w]
               (when (some #(and (= adapter (:adapter %)) (= external-id (:id %)))
                           (:external-refs w))
                 w)))))

(defn find-by-ref-id
  "The workstream carrying any external-ref whose :id = `external-id` (adapter-
   agnostic — Notion BR-#### or Slack id), or nil. Used to route a ref-keyed
   ledger append/read to its workstream. O(workstreams)."
  [project external-id]
  (->> (list-ids project)
       (keep #(read-ws project %))
       (some (fn [w] (when (some #(= external-id (:id %)) (:external-refs w)) w)))))

(defn append-to-ref!
  "Append a ledger entry to the workstream carrying `external-id`, found-or-created
   by ref (the spine: intake-triage and pickup-drive share one workstream). A minimal
   workstream is minted when none exists (adapter inferred from the id). Returns the
   entry file path."
  [project external-id entry content]
  (let [w (or (find-by-ref-id project external-id)
              (create! project
                       {:stage :triaging
                        :external-refs [{:adapter (if (str/starts-with? external-id "slack-")
                                                    :slack-message :notion)
                                         :id external-id}]}))]
    (append-entry! project (:id w) entry content)))

(defn engagement
  "Engagement state of a workstream: loads its sessions and projects. Returns
   :idle / :active / :parked-at-gate / :settled. Returns :idle if the workstream
   is absent (read-ws nil → no :closed, no sessions)."
  [project ws-id]
  (let [w (read-ws project ws-id)]
    (session/engagement-state (:closed w) (session/list-sessions project ws-id))))
