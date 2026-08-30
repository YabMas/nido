(ns nido.coordinator.record.workstream
  "The workstream: the source-agnostic spine. Owns the append-only log and the
   descriptive workflow stage. Engagement state is NOT stored here — it is a
   projection over this workstream's session records (see nido.coordinator.record.session).
   See spec docs/superpowers/specs/2026-06-05-workstream-session-model-design.md."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.report :as report]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

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

(defn- read-entry-at
  "Parse the entry at `seq-n` on the workstream record `w`, through the READ
   contract, stamped with :seq/:at — or nil when absent or unparseable. Degrades
   to nil for the same reason latest-entry does: a reader following a citation
   has to be able to find nothing without the caller crashing."
  [w seq-n]
  (when-let [e (->> (:entries w) (filter #(= seq-n (:seq %))) first)]
    (let [f (str (fs/path (cstate/workstream-dir (:project w) (:id w)) (:file e)))]
      (try (-> (report/parse-event (:kind e) (io/read-edn f))
               (assoc :seq (:seq e) :at (:at e)))
           (catch Throwable _ nil)))))

(defn- check-routes-total!
  "Every health observation on the cited `baseline` is routed exactly once by
   `record`, none is invented, and none the baseline marked invisibly incomplete is
   spun out.

   This is what makes 'nothing is lost and nothing is smuggled' a property rather
   than an intention. The baseline observes and does not route; the design routes
   and cannot observe; so the only place the two can be reconciled is where both
   are in hand, which is here.

   The spin-out veto is the sharp one. Invisible incompleteness — a half-applied
   invariant, a rule with silent exceptions — is exactly what must not be
   deferred, and a veto that depends on remembering to be principled fires when
   you are fresh and not when you are tired. The baseline flags it as an is-claim
   about the code; the ledger refuses the deferral.

   An unparseable baseline yields no observations and so checks vacuously — the
   same degrade direction read-entry-at takes, and the right one: a record that
   can no longer be read must not block the workstream from moving."
  [baseline record]
  (let [observed (into #{} (map :id) (:health baseline))
        routed   (mapv :health-id (:routes record))
        vetoed   (into #{} (comp (filter :invisibly-incomplete?) (map :id))
                       (:health baseline))]
    (when-let [unknown (seq (remove observed routed))]
      (throw (ex-info (str "Design routes health observation(s) the cited baseline "
                           "does not record: " (str/join ", " (sort unknown)))
                      {:unknown (vec (sort unknown)) :observed (vec (sort observed))})))
    (when-let [dupes (seq (for [[id n] (frequencies routed) :when (> n 1)] id))]
      (throw (ex-info (str "Design routes health observation(s) more than once: "
                           (str/join ", " (sort dupes)))
                      {:duplicated (vec (sort dupes))})))
    (when-let [missing (seq (remove (set routed) observed))]
      (throw (ex-info (str "Design leaves health observation(s) unrouted: "
                           (str/join ", " (sort missing))
                           " — route each to :fix-here, :spin-out, :declined or "
                           ":constrains")
                      {:unrouted (vec (sort missing))})))
    (when-let [spun (seq (for [r (:routes record)
                               :when (and (= :spin-out (:to r))
                                          (vetoed (:health-id r)))]
                           (:health-id r)))]
      (throw (ex-info (str "Design spins out health observation(s) the baseline "
                           "marked invisibly incomplete: " (str/join ", " (sort spun))
                           " — deferring these leaves the branch untrue")
                      {:vetoed (vec (sort spun))})))))

(defn- check-baseline-citation!
  "A :design record's :baseline names the entry it was judged against, its
   :routes answer that entry's health observations, and its :intent names what
   the task is for. The schema sees one record and can resolve none of them, so
   the check belongs here — the one place that holds both the record and the
   ledger it is joining.

   The intent citation accepts two kinds: an :intent entry, or a :triage entry
   for a workstream whose intent was already written down when the ticket was
   triaged. Anything else is refused, so a design can never cite a review, a
   blocker or a baseline as the thing it is for.

   Rejecting a dangling ref matters more than it looks: the baseline is the whole
   yardstick, and a :seq pointing at nothing reads downstream exactly like one
   pointing at a real record, so the design would appear to have been judged
   against something when it had not been."
  [w kind payload]
  (when (= :design kind)
    (let [record (edn/read-string payload)]
      (when-let [n (get-in record [:baseline :seq])]
        (when-not (some #(and (= n (:seq %)) (= :baseline (:kind %))) (:entries w))
          (throw (ex-info (str "Design cites baseline entry " n
                               ", which is not a :baseline on this workstream")
                          {:seq n
                           :baselines (->> (:entries w)
                                           (filter #(= :baseline (:kind %)))
                                           (mapv :seq))})))
        (check-routes-total! (read-entry-at w n) record))
      (when-let [n (get-in record [:intent :seq])]
        (let [e (->> (:entries w) (filter #(= n (:seq %))) first)]
          (when-not (#{:intent :triage} (:kind e))
            (throw (ex-info (str "Design cites intent entry " n
                                 ", which is neither an :intent nor a :triage "
                                 "entry on this workstream")
                            {:seq n
                             :kind (:kind e)
                             :citable (->> (:entries w)
                                           (filter #(#{:intent :triage} (:kind %)))
                                           (mapv (juxt :seq :kind)))}))))))))

(defn- cites!
  "`record`'s citation at `path` names an entry of one of `kinds`, or nothing.

   The rule `check-baseline-citation!` already applies to a design's :baseline
   and :intent, factored out because `standing` walks four more edges and each
   one has to be resolvable for the same reason: a :seq pointing at nothing
   reads downstream exactly like one pointing at a real record, so a retracted
   premise reached through a dangling edge would answer `no retraction found`
   rather than `this edge is broken`.

   Only the edges standing follows. The sequence references it never reads —
   :blocker-seq among them — are left exactly as they are: validating every
   number in the ledger is a different change, and one this design turned down."
  [w record path kinds what]
  (when-let [n (get-in record path)]
    (let [e (->> (:entries w) (filter #(= n (:seq %))) first)]
      (when-not (contains? kinds (:kind e))
        (throw (ex-info (str what " cites entry " n ", which is "
                             (if e (str "a " (:kind e)) "not on this workstream")
                             " — expected " (str/join " or " (sort (map str kinds))))
                        {:seq n :kind (:kind e)
                         :citable (->> (:entries w)
                                       (filter #(contains? kinds (:kind %)))
                                       (mapv (juxt :seq :kind)))}))))))

(defn- check-standing-citations!
  "Every edge `standing` walks resolves to an entry of the kind it expects.

   Four of them, and they arrived with this change: a :retraction's target and
   a :design-approved's design are new kinds entirely, while a design's and a
   baseline's :supersedes were both writable and neither was ever checked. That
   last pair is why this exists at all — :supersedes was the one citation in
   the ledger nothing had an opinion about, recorded in the baseline this change
   was designed against as an invisibly-incomplete health observation."
  [w kind payload]
  (when (#{:retraction :design-approved :design :baseline} kind)
    (let [r (edn/read-string payload)]
      (case kind
        :retraction      (cites! w r [:retracts :seq]
                                 #{:baseline :design :intent :triage}
                                 "Retraction")
        :design-approved (cites! w r [:design :seq] #{:design} "Approval")
        :design          (cites! w r [:supersedes :seq] #{:design}
                                 "Design :supersedes")
        :baseline        (cites! w r [:supersedes :seq] #{:baseline}
                                 "Baseline :supersedes")))))

(defn- check-seam-phase-ref!
  "A seam that says a phase closes it names that phase by its :claim. Malli sees
   the seam and the phase list in the same record but cannot express \"this string
   appears in that vector\", so the check lands beside the other cross-record one.

   It also does the work of a rule the schema deliberately does not encode: on a
   record with NO phase plan there is nothing for the claim to match, so a seam
   promising a phase will close it is refused rather than left standing as a
   commitment no plan schedules. That is the whole difference between a seam and
   a defect — the defect is the one nobody wrote down — and a seam pointing at a
   phase that does not exist reads downstream exactly like one pointing at a real
   phase, which is the same failure mode as a dangling :baseline :seq."
  [kind payload]
  (when (= :design kind)
    (let [r      (edn/read-string payload)
          claims (set (map :claim (:phases r)))
          orphan (->> (:seams r)
                      (filter #(= :phase (:closed-by %)))
                      (remove #(contains? claims (:phase %))))]
      (when (seq orphan)
        (throw (ex-info (str "Seam names a closing phase that is not in :phases: "
                             (str/join "; " (map :phase orphan)))
                        {:orphan-seams (mapv :what orphan)
                         :named        (mapv :phase orphan)
                         :phases       (vec claims)}))))))

(defn append-lock-path
  "The lock that serialises appends to one workstream. Per workstream rather than
   global: two ledgers have no sequence to contend over, and a global lock would
   queue every writer in nido behind whichever one is slowest."
  [project ws-id]
  (str (fs/path (cstate/workstream-dir project ws-id) ".append.lock")))

(defn append-entry!
  "Write an immutable entry file under entries/ and record it in :entries.
   `entry` = {:kind <kw> :session <str>?}. Returns the absolute file path.

   SERIALISED, and the whole read-derive-write has to be inside the lock rather
   than any one write of it. :seq is derived from the index count and the
   filename from the :seq, so two writers reading the same index both compute the
   same number, both write `entries/000N-…` — the second over the first — and
   both then write an index claiming N entries. The surviving payload is decided
   by write order, one append is lost outright, and the ledger looks consistent
   afterwards: the count matches, and nothing records that anything went missing.
   That is not a torn write, which write-edn!'s temp-and-rename already prevents;
   it is a lost update, which nothing prevented.

   Nothing raced before this because a human drove the stages one at a time.
   :seq is the identity every citation in the ledger is keyed on, so an
   unattended driver appending beside a session agent is exactly the condition
   under which `it does not in fact race` stops being a property of the system
   and starts being a property of the operator."
  [project ws-id entry content]
  (io/with-file-lock
    (append-lock-path project ws-id)
    (fn []
      (let [w     (or (read-ws project ws-id)
                      (throw (ex-info "Workstream not found"
                                      {:project project :ws-id ws-id})))
            seq-n (inc (count (:entries w)))
            [ext payload] (report/entry-payload (:kind entry) content)
            _     (check-baseline-citation! w (:kind entry) payload)
            _     (check-standing-citations! w (:kind entry) payload)
            _     (check-seam-phase-ref! (:kind entry) payload)
            fname (format "%04d-%s.%s" seq-n (name (:kind entry)) ext)
            rel   (str "entries/" fname)
            abs   (str (fs/path (cstate/workstream-dir project ws-id) rel))]
        (io/write-text! abs payload)
        (write! (update w :entries (fnil conj [])
                        (assoc entry :seq seq-n :at (clock/now-iso) :file rel)))
        abs))))

(defn append-entry-at!
  "Append only if `expected-seq` is still the ledger's latest entry. Returns the
   absolute path on success, or {:refused :stale :latest <seq>} without writing.

   The compare and the write are ONE operation because they share the append
   lock — which is the whole point, and the reason this is a ledger operation
   rather than something a caller assembles. A caller that reads the latest seq,
   compares it, and then calls `append-entry!` has a window between the two: two
   clicks answering the same question can both read the same latest, both find
   it current, and both append. Serialising the writes does not help, because
   what is being protected is not the file — it is the claim that this answer
   was given to THIS question.

   `expected-seq` is the position a human was looking at when they answered, so
   it is a fact about a past reading that nothing here can reconstruct; it has
   to be carried in. nil is refused rather than treated as `don't care`, so a
   caller that forgets to thread the position fails closed instead of appending
   a decision to whatever the ledger happens to hold now."
  [project ws-id expected-seq entry content]
  (io/with-file-lock
    (append-lock-path project ws-id)
    (fn []
      (let [w      (or (read-ws project ws-id)
                       (throw (ex-info "Workstream not found"
                                       {:project project :ws-id ws-id})))
            latest (count (:entries w))]
        (if (or (nil? expected-seq) (not= expected-seq latest))
          {:refused :stale :latest latest}
          ;; Re-entering append-entry! would deadlock on the lock this holds, so
          ;; the write is the same steps inline. They are few, and the
          ;; alternative — a lock that counts its holders — buys a shared body at
          ;; the price of the one property this whole function exists for.
          (let [seq-n (inc latest)
                [ext payload] (report/entry-payload (:kind entry) content)
                _     (check-baseline-citation! w (:kind entry) payload)
                _     (check-standing-citations! w (:kind entry) payload)
                _     (check-seam-phase-ref! (:kind entry) payload)
                fname (format "%04d-%s.%s" seq-n (name (:kind entry)) ext)
                rel   (str "entries/" fname)
                abs   (str (fs/path (cstate/workstream-dir project ws-id) rel))]
            (io/write-text! abs payload)
            (write! (update w :entries (fnil conj [])
                            (assoc entry :seq seq-n :at (clock/now-iso) :file rel)))
            abs))))))

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

(defn unstamp
  "Strip what `latest-entry` and `entry-at-seq` ADD on the way out.

   :seq and :at are the reader's, not the author's — an entry does not carry
   them on disk, and the write schemas are closed, so a record that has been read
   cannot be written back without this. That matters the moment anything amends a
   record it was shown: a faithful amender returns every field it was given, and
   the ledger refuses the result.

   Removing them is not discarding an author's work. Nothing may author them, so
   there is nothing here to lose."
  [entry]
  (dissoc entry :seq :at))

(defn entries-of
  "Every typed entry of `kind` on this workstream, oldest first, each parsed
   through the READ contract and stamped with :seq/:at. Entries that no longer
   parse are dropped, on the same degrade-to-nil contract latest-entry has.

   `latest-entry` answers `the current one`, which is what almost every reader
   wants. This answers `was one ever written about THIS record` — the question a
   precondition asks, where the newest entry of a kind need not be the one about
   the record in hand. A workstream can hold several baselines and several reviews
   of them, and the review that matters is the one naming the baseline you are
   standing on."
  [project ws-id kind]
  (if-let [w (read-ws project ws-id)]
    (into []
          (keep #(read-entry-at w (:seq %)))
          (->> (:entries w) (filter #(= kind (:kind %))) (sort-by :seq)))
    []))

(defn entry-at-seq
  "The typed entry at `seq-n` on this workstream, parsed through the READ contract
   and stamped with :seq/:at — or nil. Same degrade-to-nil contract as
   latest-entry: a reader following a citation must be able to find nothing
   without the caller crashing.

   Exists because a design record cites its baseline by :seq, and 'the latest
   baseline' is not the same thing — a workstream may baseline twice, and the
   design was judged against one of them specifically."
  [project ws-id seq-n]
  (when-let [w (read-ws project ws-id)]
    (read-entry-at w seq-n)))

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
