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
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.phase :as phase]
   [nido.coordinator.report :as report]
   [nido.coordinator.report.model :as report-model]
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
   [:created-at    string?]
   [:entries       [:vector [:map-of keyword? any?]]]
   [:intake        {:optional true} [:maybe IntakePayload]]
   [:facets        {:optional true} [:map-of keyword? any?]]
   ;; The board's display name for the work, generated rather than authored.
   ;; :basis is the text it was generated FROM, so a reader can tell a title
   ;; that still describes the work from one whose source has since moved.
   [:title         {:optional true} [:map {:closed true}
                                     [:text  string?]
                                     [:basis string?]]]])

(defn ^{:malli/schema [:=> [:cat :Workstream] :Workstream]}
  validate [w]
  (if (m/validate Workstream w)
    w
    (throw (ex-info "Invalid Workstream record"
                    {:errors (m/explain Workstream w) :workstream w}))))

(defn ^{:malli/schema [:=> [:cat] :WorkstreamId]}
  mint-id
  "ws-YYYYMMDD-<rand6>. Date from clock/now-iso (ISO-8601, first 10 chars =
   YYYY-MM-DD); dashes stripped so the id is a single token."
  []
  (let [date (-> (clock/now-iso) (subs 0 10) (str/replace "-" ""))
        suf  (subs (str (java.util.UUID/randomUUID)) 0 6)]
    (str "ws-" date "-" suf)))

(def ^:private stored-status-keys
  "Fields a workstream record carried before its ledger was the only place its status lived — a
   stage, its history, a close, a findings tracker. The ledger records each as entries now
   (`status-kinds`), and the status backfill turned every one these held into an entry."
  #{:stage :stage-history :closed :findings})

(defn- shed
  "`w` without the stored status fields a record written before the ledger was the only place
   its status lived still carries, so no reader can reach them and any rewrite drops them."
  [w]
  (apply dissoc w stored-status-keys))

(def status-kinds
  "Entry kinds that record a decision about a workstream's status — settled, reopened, put at a
   stage, findings resolved, born a one-off, a Notion status seen — rather than a record of the
   work. They are the ledger's copy of what used to be overwritten in place.

   Kept apart because the work's own records are what everything asking `what happened last`
   means: a close appended after a completed implementation must not become the answer to
   `how did the merge run end`."
  #{:closed :reopened :stage-set :findings-resolved :scratch :notion-status})

(defn ^{:malli/schema [:=> [:cat :Workstream] [:maybe :map]]}
  newest-record
  "The index row of the newest entry that records the work rather than its status, or nil."
  [w]
  (last (remove #(status-kinds (:kind %)) (:entries w))))

(declare rebuilt-index reconciled-index)

(defn- with-retraction-targets
  "`rows` with the target of every retraction row that does not mirror one read from its entry
   file. A row written before retractions mirrored their target holds none, and
   `record.generation` reads the target off the row to tell a retraction belonging to no
   generation from one that does; without it that retraction would be placed in a generation.
   A file that does not parse leaves its row as it was."
  [project ws-id rows]
  (let [dir (cstate/workstream-dir project ws-id)]
    (mapv (fn [row]
            (if (and (= :retraction (:kind row)) (not (contains? row :retracts)) (:file row))
              (let [body (try (edn/read-string (slurp (str (fs/path dir (:file row)))))
                              (catch Exception _ nil))]
                (cond-> row (map? body) (assoc :retracts (get-in body [:retracts :seq]))))
              row))
          rows)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :Workstream]]}
  read-ws
  "Read a workstream.edn by project + id. Returns nil if absent. Stored status fields a record
   written before the ledger held them still carries are left out (`shed`).

   A record whose index is MISSING reads the index entries/ rebuilds. One that has an index reads
   it as entries/ holds it (`reconciled-index`) — an entry an append wrote and died before
   indexing included. Every retraction row carries its target (`with-retraction-targets`)."
  [project ws-id]
  (some-> (io/read-edn-cached (cstate/workstream-edn-path project ws-id))
          shed
          (as-> w (assoc w :entries (with-retraction-targets
                                      project ws-id
                                      (if (contains? w :entries)
                                        (reconciled-index project ws-id w)
                                        (rebuilt-index project ws-id w)))))))

(defn ^{:malli/schema [:=> [:cat :Workstream] :Workstream]}
  write!
  "Validate then write a Workstream record (atomic; parent dirs created), without any stored
   status field it still carried (`shed`)."
  [w]
  (let [w (validate (shed w))]
    (io/write-edn! (cstate/workstream-edn-path (:project w) (:id w)) w)
    w))

(defn- check-stage!
  "Throw unless `stage` is in the closed vocabulary (session/storable-stages).
   The vocabulary has two tiers and only this check spans both, so it is the one
   place a stage the rest of nido cannot act on gets refused. Without it a stage
   outside the set would be recorded and then ignored: the workstream would simply
   stop appearing where you put it."
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :Workstream]}
  create!
  "Mint an id and persist a fresh workstream. `base` may carry :external-refs (default []),
   :intake {:trigger <kw> :payload <map>} (default absent) and :facets <map> (default absent).
   It holds no status: where it is placed, chosen to be or settled is what its ledger comes to
   record."
  [project {:keys [external-refs intake facets]}]
  (let [w (cond-> {:id            (mint-id)
                   :project       project
                   :external-refs (vec (or external-refs []))
                   :created-at    (clock/now-iso)
                   :entries       []}
            intake       (assoc :intake intake)
            (seq facets) (assoc :facets facets))]
    (write! w)))

(declare record-status! closure newest-choice)

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :keyword [:? [:enum :person :nido :backfill]]] :Workstream]}
  advance-stage!
  "Choose `new-stage` for a workstream: append a :stage-set entry naming it. `by` is who decided
   it — a person, or nido itself (the default). No-op when the newest choice already names
   `new-stage` (`newest-choice`) — except that a person's choice is recorded even then: it is a
   decision, and one made after work moved past the earlier choice puts the work back. Throws if
   the workstream is absent, or if `new-stage` is outside session/storable-stages — checked ahead
   of the no-op, so re-choosing a foreign stage is refused rather than quietly accepted. Returns
   the record."
  ([project ws-id new-stage] (advance-stage! project ws-id new-stage :nido))
  ([project ws-id new-stage by]
   (check-stage! new-stage :advance-stage!)
   (when-not (read-ws project ws-id)
     (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
   (record-status! project ws-id
                   (fn [w] (when (or (= :person by) (not= new-stage (:stage (newest-choice w))))
                             {:format :stage-set :stage new-stage :by by})))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :Workstream]}
  set-facets!
  "Overwrite a workstream's :facets map. Throws if the workstream is absent.
   Returns the updated record. An empty map removes the key."
  [project ws-id facets]
  (let [w (read-ws project ws-id)]
    (when-not w
      (throw (ex-info "Cannot set facets on absent workstream"
                      {:project project :ws-id ws-id})))
    (write! (if (seq facets) (assoc w :facets facets) (dissoc w :facets)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string :string] :Workstream]}
  set-title!
  "Overwrite a workstream's generated :title with `text`, generated from `basis`.
   Throws if the workstream is absent. Returns the updated record."
  [project ws-id text basis]
  (let [w (read-ws project ws-id)]
    (when-not w
      (throw (ex-info "Cannot set title on absent workstream"
                      {:project project :ws-id ws-id})))
    (write! (assoc w :title {:text text :basis basis}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :Workstream]}
  clear-title!
  "Remove a workstream's generated :title. Throws if the workstream is absent.
   Returns the updated record."
  [project ws-id]
  (let [w (read-ws project ws-id)]
    (when-not w
      (throw (ex-info "Cannot clear title on absent workstream"
                      {:project project :ws-id ws-id})))
    (write! (dissoc w :title))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :keyword] :Workstream]}
  close!
  "Settle a workstream: append a :closed entry naming `outcome`. What each outcome means to its
   readers:

     :done, :dropped  finished, or given up on.
     :dismissed       the nido-side veto (work/dismiss!) — a ticket nobody should triage again,
                      and the board's Dismissed band.
     :vetoed          the improvement sweep's: a claim stopped by a decline it did not earn, so
                      the addresses carrying no decline of their own return to the owed set
                      (`proposal/owed`) rather than counting as tried.
     :orphaned        the improvement sweep's too: the Run working the claim ended without
                      closing it, and the coordinator closed it, since a close is the only thing
                      that releases the sweep. Read like :vetoed, kept apart so a reader can tell
                      a decline from a death.
     :between-phases  a landing of a phase that is not the plan's last — closed, because nothing
                      is in flight until a person has watched the running system; not :done,
                      because the plan is not. `open-phase!` is its way forward.

   `design-seq`, given with :between-phases, names the design whose plan the landing paused: it
   governs the workstream until its gate opens (`generation/current-design`). `by`, when given, is who settled
   it. Every call appends, so a workstream closed twice holds two closes and the newer outcome
   stands (`closure`). Returns the record."
  ([project ws-id outcome] (close! project ws-id outcome nil))
  ([project ws-id outcome design-seq] (close! project ws-id outcome design-seq nil))
  ([project ws-id outcome design-seq by]
   (when-not (read-ws project ws-id)
     (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
   (record-status! project ws-id
                   (fn [_] (cond-> {:format :closed :outcome outcome}
                             design-seq (assoc :design {:seq design-seq})
                             by         (assoc :by by))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :keyword] :Workstream]}
  reopen!
  "Un-settle a workstream at `stage`: append a :reopened entry naming it — the close it follows
   no longer stands (`closure`), and `stage` is the stage chosen for it (`newest-choice`). No-op
   when it is already open with `stage` the newest choice. Throws if the workstream is absent.
   Returns the record."
  ([project ws-id stage] (reopen! project ws-id stage nil))
  ([project ws-id stage by]
   (when-not (read-ws project ws-id)
     (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
   (record-status! project ws-id
                   (fn [w] (when-not (and (nil? (closure w)) (= stage (:stage (newest-choice w))))
                             (cond-> {:format :reopened :stage stage} by (assoc :by by)))))))

(def ^:private parsed-entries
  "Entry file → its parsed payload, keyed on [path kind file-stamp].

   An entry is written once and never rewritten, yet every reader re-parsed it:
   one `pipeline/of` on a 236-entry ledger parsed 526 files (~180ms), and the
   board asks it for every drawn row on every refresh. The file's stamp is in the
   key anyway, so a file that did change is re-read rather than trusted.

   Bounded, because the daemon lives for days and the ledgers run to ~100MB of
   EDN between them: past `parsed-entry-cap` the cache is dropped whole and
   refills from what is actually being read."
  (atom {}))

(def ^:private parsed-entry-cap 4096)

(defn- parse-entry-file
  "`report/parse-event` of the entry file `f` as `kind`, through `parsed-entries`.
   Throws as an uncached read would — a failure is never cached."
  [kind f]
  (let [k [f kind (io/file-stamp f)]]
    (or (get @parsed-entries k)
        (let [v (report/parse-event kind (io/read-edn f))]
          (swap! parsed-entries #(assoc (if (>= (count %) parsed-entry-cap) {} %) k v))
          v))))

(defn- read-entry-at
  "Parse the entry at `seq-n` on the workstream record `w`, through the READ
   contract, stamped with :seq/:at — or nil when absent or unparseable. Degrades
   to nil for the same reason latest-entry does: a reader following a citation
   has to be able to find nothing without the caller crashing."
  [w seq-n]
  (when-let [e (->> (:entries w) (filter #(= seq-n (:seq %))) first)]
    (let [f (str (fs/path (cstate/workstream-dir (:project w) (:id w)) (:file e)))]
      (try (-> (parse-entry-file (:kind e) f)
               (assoc :seq (:seq e) :at (:at e)))
           (catch Throwable _ nil)))))

(defn- span-of
  "The index rows of `kind` in the current generation's span of `w`, in ledger order — the one
   place the ledger's own checks and readings take designs and their decisions from. A retired
   generation's are neither the design a check is about nor evidence for or against it."
  [w kind]
  (filter #(= kind (:kind %)) (generation/current-span (:entries w))))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :map]]}
  closure
  "How the ledger says the workstream `w` stands settled: the newest :closed entry, unless a
   :reopened or a :phase-gate came after it — then nil, since opening the next phase is a reopen
   by definition. Nil for a workstream that does not exist.

   The newest close is read by its :seq, and when it cannot be read the answer is still closed —
   `{:seq n :unreadable? true}`, with no outcome — never an older close's payload, which would
   report an outcome the workstream has since moved past."
  [w]
  (let [newest (last (filter #(#{:closed :reopened :phase-gate} (:kind %)) (:entries w)))]
    (when (= :closed (:kind newest))
      (or (read-entry-at w (:seq newest))
          {:seq (:seq newest) :unreadable? true}))))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :map]]}
  newest-choice
  "The newest stage choice on `w` — its :stage-set or :reopened — as `{:seq n :stage s}`, or nil
   when none was made. Read by its :seq: one whose payload no longer reads chooses nothing
   (:stage nil), rather than an older choice it replaced."
  [w]
  (when-let [move (last (filter #(#{:stage-set :reopened} (:kind %)) (:entries w)))]
    {:seq (:seq move) :stage (:stage (read-entry-at w (:seq move)))}))

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

(defn- check-modules-described!
  "A design adding a module to the baseline it cites says what that module hides and what the rest
   may assume of it, and one adding a stratum says what it provides and reads its level. A module the baseline describes the design names by id alone, and keeps that
   description; one it adds has none to keep, and a child forked from the design would take it
   into a baseline that lists a module hiding nothing. Records from before the shared model are
   left to the readers that refuse them."
  [baseline record]
  (when (and (contains? baseline :model) (contains? record :model))
    (when-let [bare (seq (report-model/undescribed-modules (:model baseline) (:model record)))]
      (throw (ex-info (str "Design adds " (str/join ", " bare) " to baseline entry " (:seq baseline)
                           " without saying what each hides and what the rest may assume of it")
                      {:seq (:seq baseline) :modules (vec bare)})))
    (when-let [bare (seq (report-model/undescribed-strata (:model baseline) (:model record)))]
      (throw (ex-info (str "Design adds " (str/join ", " bare) " to baseline entry " (:seq baseline)
                           " without saying what each provides and reading its level")
                      {:seq (:seq baseline) :strata (vec bare)})))))

(defn- check-breaks-restated!
  "A design that lists an element of its baseline under :baseline :breaks says what that element
   becomes — states it anew, or removes it. A claim it lists is taken out of its effective model by
   saying so (`report-model/effective`), but an element's new description is the design's to give:
   left alone, a child forked from the design describes it as it was. An entry naming no element
   the baseline holds is left to the round that judges :breaks. Records from before the shared
   model are left to the readers that refuse them."
  [baseline record]
  (when (and (contains? baseline :model) (contains? record :model))
    (when-let [kept (seq (report-model/unrestated-elements
                          (:model baseline) (:model record) (report/broken-ids record)))]
      (throw (ex-info (str "Design lists " (str/join ", " kept) " under :baseline :breaks but leaves"
                           " each as baseline entry " (:seq baseline) " describes it — state each as"
                           " it is after the change, or list it under :model :removed")
                      {:seq (:seq baseline) :unrestated (vec kept)})))))

(defn- check-baseline-citation!
  "A :design record's :baseline names the entry it was judged against, its
   :routes answer that entry's health observations, and its :intent names what
   the task is for. The schema sees one record and can resolve none of them, so
   the check belongs here — the one place that holds both the record and the
   ledger it is joining.

   The intent citation accepts ONE kind. A triage report is not a goal: it
   proposes candidate directions — `report/Direction` calls each one a branch a
   human picks BETWEEN — so one report stands for several possible units, and
   reading it as one unit's goal collapses a fan-out into a scalar. It belongs to
   the workstream, which is what holds the units a triage gives rise to.

   Anything else is refused too, so a design can never cite a review, a blocker or
   a baseline as the thing it is for.

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
        (let [baseline (read-entry-at w n)]
          (check-routes-total! baseline record)
          (check-modules-described! baseline record)
          (check-breaks-restated! baseline record)))
      (when-let [n (get-in record [:intent :seq])]
        (let [e (->> (:entries w) (filter #(= n (:seq %))) first)]
          (when-not (= :intent (:kind e))
            (throw (ex-info (str "Design cites intent entry " n
                                 ", which is not an :intent entry on this "
                                 "workstream")
                            {:seq n
                             :kind (:kind e)
                             :citable (->> (:entries w)
                                           (filter #(= :intent (:kind %)))
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

(def ^:private retractable-kinds
  "The kinds a :retraction may name: those a later record's :supersedes can name."
  #{:baseline :design :intent})

(defn- check-standing-citations!
  "Every edge `standing` walks resolves to an entry of the kind it expects.

   Six of them. A :retraction's target and a :design-approved's design are
   kinds of their own; a design's and a baseline's :supersedes were both
   writable and neither was ever checked — that pair is why this exists at all,
   :supersedes being the one citation in the ledger nothing had an opinion
   about. A baseline's :intent is the fifth, and it accepts what a design's
   does: an :intent entry and nothing else. A derived baseline's :fork is the
   sixth, and names the :fork entry on its own ledger.

   A :retraction reaches exactly the kinds a :supersedes can name, because a
   later record superseding the retracted one is the only thing that answers a
   retraction. A kind nothing supersedes — a :triage — could be retracted but
   never answered, and the workstream would be held at the retraction for good."
  [w kind payload]
  (when (#{:retraction :design-approved :design-cleared :design :baseline :intent} kind)
    (let [r (edn/read-string payload)]
      (case kind
        :retraction      (let [n  (get-in r [:retracts :seq])
                               ek (->> (:entries w) (filter #(= n (:seq %))) first :kind)]
                           (when (and ek (not (contains? retractable-kinds ek)))
                             (throw (ex-info (str "Retraction cites entry " n ", a " ek
                                                  ", which nothing can supersede — so a"
                                                  " retraction of it could never be answered."
                                                  " Retractable: "
                                                  (str/join " or " (sort (map str retractable-kinds))))
                                             {:seq n :kind ek})))
                           (cites! w r [:retracts :seq] retractable-kinds "Retraction"))
        :design-approved (cites! w r [:design :seq] #{:design} "Approval")
        :design          (cites! w r [:supersedes :seq] #{:design}
                                 "Design :supersedes")
        :baseline        (do (cites! w r [:supersedes :seq] #{:baseline}
                                     "Baseline :supersedes")
                             (cites! w r [:intent :seq] #{:intent}
                                     "Baseline :intent")
                             (cites! w r [:fork :seq] #{:fork}
                                     "Baseline :fork"))
        :intent          (cites! w r [:supersedes :seq] #{:intent}
                                 "Intent :supersedes")
        :design-cleared  (cites! w r [:design :seq] #{:design} "Clearance")))))

(defn- check-fork-citation!
  "A :fork names a parent that is another workstream, whose ledger holds a :baseline and a :design
   at the seqs it cites, each holding a model a child's can be derived from — and a workstream is
   forked once.

   The only citation in the ledger that crosses workstreams, so the only one resolved against
   another ledger. It names immutable entries, which is why resolving it once, here, is enough:
   nothing appended to the parent afterwards changes what the entries at those numbers are. Once,
   because a unit has one origin, and a second :fork would leave its lineage two answers to give."
  [project w kind payload]
  (when (= :fork kind)
    (let [{{:keys [ws-id baseline design]} :parent} (edn/read-string payload)
          parent  (read-ws project ws-id)
          kind-at (fn [n] (->> (:entries parent) (filter #(= n (:seq %))) first :kind))
          refuse  (fn [what n expected]
                    (throw (ex-info (str "Fork cites " what " entry " n " on " ws-id ", which is "
                                         (if-let [k (kind-at n)] (str "a " k) "not on it")
                                         " — expected a " expected)
                                    {:ws-id ws-id :seq n :kind (kind-at n)})))]
      (cond
        (= ws-id (:id w))
        (throw (ex-info (str "A fork's parent is another workstream, and " ws-id " is this one")
                        {:ws-id ws-id}))

        (nil? parent)
        (throw (ex-info (str "Fork names parent workstream " ws-id ", which does not exist")
                        {:ws-id ws-id}))

        (not= :baseline (kind-at (:seq baseline))) (refuse "baseline" (:seq baseline) :baseline)
        (not= :design (kind-at (:seq design)))     (refuse "design" (:seq design) :design)

        (some #(= :fork (:kind %)) (:entries w))
        (throw (ex-info (str "Workstream " (:id w) " is already a fork — a unit has one origin")
                        {:ws-id (:id w)})))
      (doseq [[what {n :seq}] [["baseline" baseline] ["design" design]]]
        (when-let [era (report-model/predates (read-entry-at parent n))]
          (throw (ex-info (str "Fork cites the " what " at entry " n " on " ws-id ", written before "
                               (if (= :shared-model era) "the shared model" "a role named its players")
                               " — it holds no model a child's can be derived from")
                          {:ws-id ws-id :seq n :predates era})))))))

(defn- check-merge-citation!
  "A design carrying :merges is the combination it says it is. The child it names is a fork of this
   workstream; it supersedes this workstream's current design and cites that design's baseline; and
   its effective model is the three-way combination, by id, of the fork's base, the current design
   and the child's design — appended only while no conflict stands.

   Held here as well as by the merge verb, so that a merge appending nothing else is true of the
   ledger rather than of one path to it. The laws of the combined declaration stay the verb's: they
   need a working copy and fukan, and an append has neither."
  [project w kind payload]
  (when (= :design kind)
    (let [r (edn/read-string payload)]
      (when-let [{child-ws :ws-id {n :seq} :design} (:merges r)]
        (let [refuse  (fn [msg] (throw (ex-info msg {:merges (:merges r)})))
              child   (or (read-ws project child-ws)
                          (refuse (str "The merged design names child workstream " child-ws
                                       ", which does not exist")))
              forked  (some->> (:entries child) (filter #(= :fork (:kind %))) first :seq
                               (read-entry-at child))
              _       (when-not (= (:id w) (get-in forked [:parent :ws-id]))
                        (refuse (str child-ws " is no fork of " (:id w)
                                     ", so it is no child to merge here")))
              current (some->> (generation/current-design w) (read-entry-at w))
              base-g  (generation/generation-of (:entries w) (get-in forked [:parent :design :seq]))
              _       (when-not (= base-g (generation/current (:entries w)))
                        (refuse (str child-ws " was forked from the design at entry "
                                     (get-in forked [:parent :design :seq]) ", of generation " base-g
                                     " — it cannot merge into generation "
                                     (generation/current (:entries w)) " of " (:id w))))
              [fb fd pd cd]
              [(read-entry-at w (get-in forked [:parent :baseline :seq]))
               (read-entry-at w (get-in forked [:parent :design :seq]))
               current
               (read-entry-at child n)]
              ;; Every record the combination reads, named by entry. A role recorded before
              ;; membership was authored leaves a claim about it relying on players nothing states,
              ;; so a removal the other side made of one could not be read as a conflict.
              _       (doseq [[record ledger at what]
                              [[fb w (get-in forked [:parent :baseline :seq]) "the fork's base baseline"]
                               [fd w (get-in forked [:parent :design :seq]) "the fork's base design"]
                               [pd w (:seq pd) "this workstream's current design"]
                               [(some->> (get-in pd [:baseline :seq]) (read-entry-at w)) w
                                (get-in pd [:baseline :seq]) "the baseline that design cites"]
                               [cd child n "the child's design"]
                               [(some->> (get-in cd [:baseline :seq]) (read-entry-at child)) child
                                (get-in cd [:baseline :seq]) "the baseline the child's design cites"]]]
                        (when (report-model/predates record)
                          (refuse (str "A merged design combines records written in the shared model, each"
                                       " role naming its players — and " what ", at entry " at " on "
                                       (:id ledger) ", is not, or cannot be read"))))
              _       (when-not (= :design (:format cd))
                        (refuse (str "The merged design names entry " n " on " child-ws
                                     ", which is not a design")))
              laid    (fn [ledger design]
                        (report-model/effective
                         (:model (read-entry-at ledger (get-in design [:baseline :seq])))
                         (:model design) (report/broken-ids design)))
              {:keys [model conflicts]} (report-model/combine
                                         (report-model/effective (:model fb) (:model fd)
                                                                 (report/broken-ids fd))
                                         (laid w pd) (laid child cd))
              keyed   (fn [m] [(into {} (map (juxt :id identity)) (:elements m))
                               (into {} (map (juxt :id identity)) (:claims m))])]
          (cond
            (not= (get-in r [:supersedes :seq]) (:seq pd))
            (refuse (str "A merged design supersedes this workstream's current design, entry " (:seq pd)))

            (not= (get-in r [:baseline :seq]) (get-in pd [:baseline :seq]))
            (refuse (str "A merged design cites the baseline the design it supersedes cites, entry "
                         (get-in pd [:baseline :seq])))

            (seq conflicts)
            (refuse (str "A merged design is appended only while no conflict stands: "
                         (str/join "; " (for [{:keys [kind names]} conflicts]
                                          (str (name kind) " " (str/join " · " names))))))

            (not= (keyed model) (keyed (laid w r)))
            (refuse (str "The merged design's effective model is not the combination, by id, of the"
                         " fork's base, this workstream's current design and the child's design"))))))))

(defn- superseded-goals
  "Every :intent :seq that a later :intent says it replaces.

   Read off the INDEX-resolved entries rather than a walk, because one hop is
   all this question needs: a chain is built one append at a time and every link
   was refused unless its target was live, so a superseded tip can only be
   reached by a citation written before the replacement existed — and that
   citation is exactly what the sequence guard in `standing` unseats on the read."
  [w]
  (into #{} (keep #(get-in (read-entry-at w (:seq %)) [:supersedes :seq]))
        (filter #(= :intent (:kind %)) (:entries w))))

(def ^:private rests-on
  "Entry kind → the citations a record of that kind RESTS on, as paths into it.

   A baseline's goal; a design's baseline and goal; and the subject of every
   record that judges a rung of the arc. A :review names its design only where
   it carries one.

   Not every citation. :supersedes names what a record REPLACES, and a baseline
   written because the goal moved supersedes one whose ground moved with it —
   walking that edge would refuse the one append the amendment exists to make.
   The trail's facts — :implementation-completed, :pr-opened, :merged — record
   that something happened rather than that something holds, so they are absent."
  {:baseline        [[:intent :seq]]
   :design          [[:baseline :seq] [:intent :seq]]
   :baseline-review [[:baseline-seq]]
   :design-decision [[:design-seq]]
   :design-verdict  [[:design-seq]]
   :design-approved [[:design :seq]]
   :review          [[:design :seq]]
   :design-cleared  [[:design :seq]]})

(defn- goals-reached
  "Every goal — an :intent, and only an :intent — that `record`, of
   `kind`, rests on through `rests-on`, however many citations away.

   Follows the kind of the entry each citation resolves to, so a design reached
   from an approval is walked as a design. A dangling or unreadable entry reaches
   nothing, which `cites!` has already refused on the edges it checks."
  [w kind record]
  (loop [todo  (vec (keep #(get-in record %) (rests-on kind)))
         seen  #{}
         goals #{}]
    (if-let [n (peek todo)]
      (let [todo (pop todo)
            k    (->> (:entries w) (filter #(= n (:seq %))) first :kind)]
        (cond
          (contains? seen n)       (recur todo seen goals)
          (= :intent k)            (recur todo (conj seen n) (conj goals n))
          :else
          (recur (into todo (keep #(get-in (read-entry-at w n) %) (rests-on k)))
                 (conj seen n) goals)))
      goals)))

(defn- live-goals
  "Every :intent :seq no later intent replaces, oldest first — the goals a new intent may
   supersede."
  [w]
  (let [dead (superseded-goals w)]
    (into [] (comp (filter #(= :intent (:kind %))) (map :seq) (remove dead)) (:entries w))))

(defn- intent-supersessions
  "Each :intent :seq that supersedes another → the :seq it supersedes."
  [w]
  (into {} (keep #(when-let [n (get-in (read-entry-at w (:seq %)) [:supersedes :seq])]
                    [(:seq %) n]))
        (filter #(= :intent (:kind %)) (:entries w))))

(defn- live-tip
  "The live goal of the chain `goal-seq` is on — forward along :supersedes until nothing
   replaces it. Every link was refused unless its target was live, so the chain has one tip;
   `seen` bounds a cycle only a hand-written ledger could hold."
  [w goal-seq]
  (let [by-old (into {} (map (fn [[new old]] [old new])) (intent-supersessions w))]
    (loop [n goal-seq seen #{}]
      (let [nxt (by-old n)]
        (if (or (nil? nxt) (seen nxt)) n (recur nxt (conj seen n)))))))

(declare goal-amendment)

(defn- check-goal-is-live!
  "Nothing a record stands on stands on a goal something has replaced.

   The other half of the amendment rule, and it closes the direction the
   sequence guard cannot. `standing` unseats records that ALREADY EXIST when a
   goal moves, by noticing a replacement appended after them; it says nothing
   about a record written AFTERWARDS. Append the amendment first and a design or
   a survey may still name the goal it replaced — every citation resolves, every
   kind is right, nothing postdates it, so nothing unseats it and it stands on a
   goal nobody holds.

   That is an authoring error rather than a propagation failure, so it is
   refused where authoring errors are refused. The refusal names every record
   still standing on the replaced goal, not only the one refused, and carries
   `goal-amendment`'s reading under :amendment, from which the arc says the
   whole chain owed. Its ex-data's `:reason :goal-superseded` is standing's word
   for the same fact, so a writer that cannot stop to re-author — a review round
   whose goal moved while it read — can tell this refusal from the rest. An
   :intent's own :supersedes is held to the same rule for a different reason: an
   amendment naming an already-replaced tip forks the chain and leaves the walk
   two answers to take.

   However many citations away, which is why this walks `goals-reached` rather
   than reading the record's own goal: a design naming the live goal over a
   baseline scoped for the replaced one, or a review of that baseline, stands on
   the replaced goal exactly as much. A baseline that is itself superseded is not
   refused here — that is a reading `standing` reports, not an authoring error.

   Refused over the WHOLE ledger rather than its recent end, which this rule can
   afford to claim because no intent on any ledger is superseded today — the
   field arrives with this change, so every supersession that will ever exist is
   appended under it."
  [w kind payload]
  (when (or (= :intent kind) (contains? rests-on kind))
    (let [r     (edn/read-string payload)
          goals (if (= :intent kind)
                  (keep identity [(get-in r [:supersedes :seq])])
                  (goals-reached w kind r))]
      (when (seq goals)
        (let [dead (superseded-goals w)]
          (when-let [n (some dead goals)]
            (let [owed (when-not (= :intent kind) (goal-amendment w n))]
              (throw (ex-info (str (str/capitalize (name kind)) " stands on the goal at entry "
                                   n ", which a later intent has replaced"
                                   (if (= :intent kind)
                                     (str " — an amendment naming an already-replaced goal forks the chain;"
                                          " the live goal is entry " (live-tip w n))
                                     (when-let [{:keys [goal baseline baseline-stale? design design-stale?]} owed]
                                       (when (or baseline-stale? design-stale?)
                                         (str "; amending it to entry " goal " still owes a replacement for "
                                              (str/join " and "
                                                        (cond-> []
                                                          baseline-stale? (conj (str "the baseline at entry " baseline))
                                                          design-stale?   (conj (str "the design at entry " design)))))))))
                              (cond-> {:reason :goal-superseded :seq n :kind kind
                                       :superseded (vec (sort dead))}
                                owed (assoc :amendment owed)))))))))))

(defn- check-intent-says-its-goal!
  "An :intent joining a workstream that already states a goal says how it stands to it: it
   :supersedes a live goal, amending it, or is :independent, opening a second unit.

   Only the author knows which, and the ledger cannot tell them apart afterwards — a bare second
   intent roots a unit of its own, and an author who meant an amendment finds out only when the
   next design reaches both roots and is refused, on a different record, while the intent that
   caused it stands. So the question is asked at the one append that can answer it, and the
   refusal names the goal a :supersedes most likely meant: the newest one nothing replaces.
   A retracted goal is still live here, because only a :supersedes answers its retraction.

   The first intent on a workstream carries neither, since there is nothing to stand to, and is
   refused carrying either."
  [w kind payload]
  (when (= :intent kind)
    (let [r     (edn/read-string payload)
          goals (live-goals w)]
      (cond
        (and (empty? goals) (or (:supersedes r) (:independent r)))
        (throw (ex-info (str "This is the workstream's first intent, so there is no goal for it to"
                             " amend (:supersedes) or to stand beside (:independent true) — drop both")
                        {:supersedes  (get-in r [:supersedes :seq])
                         :independent (:independent r)}))

        (and (:supersedes r) (:independent r))
        (throw (ex-info (str "An intent either amends a goal (:supersedes) or opens a unit of its"
                             " own (:independent true), never both")
                        {:supersedes (get-in r [:supersedes :seq])}))

        (and (seq goals) (not (:supersedes r)) (not (:independent r)))
        (let [n (last goals)]
          (throw (ex-info (str "This intent names no goal it replaces, and entry " n
                               " already states this workstream's goal"
                               (when (next goals)
                                 (str " (the live goals are entries " (str/join ", " goals) ")"))
                               ". To amend that goal, add :supersedes {:seq " n " :why \"…\"}."
                               " To open a second unit of work, add :independent true — or fork"
                               " the unit (bb nido:workstream:fork) so it has a workstream of its own")
                          {:live-goals goals :meant n})))))))

(def ^:private trail-kinds
  "The records that say something HAPPENED, each of which names the design it
   happened under. The same four `reentry/trail-kinds` attributes, and the two
   lists have to stay in step: a kind that gains a monotone clause there and no
   citation here is a kind attributed by append order for ever."
  #{:implementation-completed :review :pr-opened :merged})

(defn- check-trail-attribution!
  "A trail record names the design it was made under, whenever there is one to
   name.

   CONDITIONAL ON THE LEDGER, which is why it is here and not in the schema: a
   workstream that holds no design has nothing for the record to cite, and work
   done before any design exists cannot be attributed to one. Calling that a
   violation would be inventing a generation to blame — the same refusal
   `reentry/generation` already makes by answering nil.

   Where a design DOES exist the citation is required, because the alternative is
   what the ledger did before: `:implementation-completed`, `:review`,
   `:pr-opened` and `:merged` cited nothing, so append order was the whole of the
   evidence. That is a fact about how the ledger was written rather than about
   what the records say, and it cannot survive a workstream holding more than one
   unit — `:merged` least of all, since `reopen!` clears `:closed` for the next
   landing and every earlier one stays in the ledger for ever."
  [w kind payload]
  (when (contains? trail-kinds kind)
    (let [designs (span-of w :design)
          r       (edn/read-string payload)]
      (when (and (seq designs) (nil? (get-in r [:design :seq])))
        (throw (ex-info (str "A " (name kind) " must name the design it was made"
                             " under — this workstream holds " (count designs)
                             ", so append order is not evidence")
                        {:kind kind :designs (mapv :seq designs)})))
      ;; Only the REQUIREMENT is conditional on a design existing. A citation
      ;; that is supplied must resolve whatever the ledger holds, or a record on
      ;; a design-less ledger carries an invented :under that `trail-standing`
      ;; later reads as current.
      (cites! w r [:design :seq] #{:design}
              (str (str/capitalize (name kind)) " :design")))))

(def ^:private stands-on
  "Which citation on each kind reaches the record it stands on.

   A record belongs to the unit its citations reach, so this is the whole of the
   walk's knowledge about shape. A kind absent here reaches nothing, and every
   absent kind roots nowhere — a `:triage`, a `:note`, a `:blocker`, a
   `:review-analysis`. They are the WORKSTREAM's rather than any unit's, and
   inventing a root for them would put records in units their authors never
   placed them in.

   THE LANDINGS ARE ABSENT TOO, by a different route to the same answer. A
   `:pr-opened` and a `:merged` NAME the design whose work they carry and stand
   on none of it: `/land` collapses a reviewed stack into a SINGLE PR, so one
   landing may carry work from several units, and a citation that names is not
   one that rests. Walking it here would seat a landing in whichever unit its
   citation happened to reach while the arc reports it as the workstream's — one
   record with two owners. `rests-on` draws the same line between naming and
   resting for the same two kinds."
  {:intent          [[:supersedes :seq]]
   :baseline        [[:intent :seq] [:supersedes :seq]]
   :design          [[:baseline :seq] [:intent :seq] [:supersedes :seq]]
   :baseline-review [[:baseline-seq]]
   :design-decision [[:design-seq]]
   :design-verdict  [[:design-seq]]
   :design-approved [[:design :seq]]
   :design-cleared  [[:design :seq]]
   :implementation-completed [[:design :seq]]
   :review          [[:design :seq]]})

(defn- roots-of
  "Every root this record reaches, as a set. A root is a goal citing no other —
   an :intent that supersedes nothing. `#{}` means the record reaches none.

   The same ONE kind a baseline's and a design's :intent may cite, and the two
   sets have to stay equal. A goal kind the citation admits and this walk does
   not root would make every arc written from it rootless; one this walk roots
   and the citation refuses could never be reached at all.

   ONE WALK WITH THREE ANSWERS — one root, several, or none — rather than a
   rule beside the closure that could disagree with it about the same record.
   ONE is a unit. NONE and SEVERAL are the workstream's: a triage report, a
   note, a blocker, a landing that names a design without standing on it, or a
   record descending from one written before the contract. `check-one-root!`
   is what confines several to that last route.

   Each ledger entry is walked once per `memo`: a design cites its survey and
   supersedes the design before it, which does the same, so the citations share
   most of their paths and an unshared walk re-reads each one once per path
   reaching it — seconds per call on a workstream with a few dozen rounds. The
   memo holds entry seq → roots for ONE snapshot `w`; a caller asking several
   questions of the same `w` passes one memo to all of them. An entry reached
   again while its own walk is in progress is a citation cycle and reaches
   nothing through that path."
  ([w record kind] (roots-of w record kind (volatile! {})))
  ([w record kind memo]
   (let [by-seq (into {} (map (juxt :seq identity)) (:entries w))]
     (letfn [(step [k rec]
               (if (nil? rec)
                 #{}
                 (let [targets (keep #(get-in rec %) (get stands-on k))]
                   (if (and (= :intent k) (empty? targets))
                     ;; A goal citing nothing IS a root, and :intent is the only
                     ;; kind that can be one.
                     #{(:seq rec)}
                     (into #{} (mapcat at) targets)))))
             (at [n]
               (let [known (get @memo n ::unwalked)]
                 (cond
                   (= ::walking known)   #{}
                   (not= ::unwalked known) known
                   :else
                   (if-let [e (by-seq n)]
                     (do (vswap! memo assoc n ::walking)
                         (let [r (step (:kind e) (read-entry-at w n))]
                           (vswap! memo assoc n r)
                           r))
                     #{}))))]
       (step kind record)))))

(defn- roots-at
  "Every root the entry at `seq-n` reaches — `#{}` when no entry is there. `memo`
   as `roots-of` takes it, shared by a caller asking of several entries of one `w`."
  ([w seq-n] (roots-at w seq-n (volatile! {})))
  ([w seq-n memo]
   (if-let [e (->> (:entries w) (filter #(= seq-n (:seq %))) first)]
     (let [known (get @memo seq-n)]
       (if (set? known)
         known
         (let [r (roots-of w (read-entry-at w seq-n) (:kind e) memo)]
           (vswap! memo assoc seq-n r)
           r)))
     #{})))

(defn- check-one-root!
  "A record reaches no more goals than one of its citations already does.

   That is what makes the closure a partition. A design may cite one intent while
   the baseline beneath it was scoped for an unrelated other: every citation
   resolves, every kind is right, and the record joins two units. Nothing else
   can see that, because every other check reads one citation at a time. The one
   record the rule does not ask is an :intent citing nothing, which opens a unit
   rather than joining any.

   INHERITING IS NOT JOINING, and the rule is phrased to admit it. A record
   reaching NONE — a decision on a design from before `:intent` was required —
   and one reaching SEVERAL — anything citing a design that served one goal
   while superseding a design under another, which is how a goal amendment was
   written before an intent could cite what it replaces — reach exactly what
   they cite. Refusing either strands that design mid-arc, unable to receive its
   own decision, review or supersession, and the only record that would unstick
   it is one misstating what the work stands on. Both are in the workstream's
   PRE-CONTRACT REGION, where `unit-of` addresses no unit.

   Neither can be MADE through this boundary, which is what keeps the region a
   boundary rather than a leak: a baseline and a design must cite a goal, and a
   record reaching none or several is admitted only citing one that already
   does, so every such record descends from one written before the contract. A
   kind outside the table — a note, a blocker, a landing — reaches nothing on
   its own account and is never asked, because it belongs to the workstream
   rather than to a unit that could be in doubt."
  [w kind payload]
  (when (contains? stands-on kind)
    (let [record (edn/read-string payload)
          cited  (keep #(get-in record %) (stands-on kind))
          roots  (roots-of w record kind)]
      (when (and (seq cited) (not-any? #(= roots (roots-at w %)) cited))
        (throw (ex-info (str "This " (name kind) " reaches " (count roots)
                             " goals — " (str/join ", " (sort roots))
                             " — more than any record it cites, so it belongs"
                             " to no single unit of work")
                        {:kind kind :roots (vec (sort roots))}))))))

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

(defn- check-clearance-owed!
  "A clearance is admitted only on the evidence that makes it one: the latest
   decision naming its design recommended proceeding, and that design's own
   declarations owe nobody a grant.

   Asked of entries this lock can see and never of `standing`, which reads this
   namespace. The third part — nothing blocked the design when it was cleared —
   IS standing's answer, read by the writer at a position it hands
   `append-entry-at!` — never stores — and that compare is what keeps the
   reading true at the write. That is why `append-entry!`
   refuses the kind outright: every reader that admits a design on a clearance
   naming it trusts this record, so one written with no round behind it, or
   over a design that owes a person the grant, is work nobody judged.

   Fails closed on a decision of the current generation nobody can parse, since the one unread may
   be the latest and may not proceed; a retired generation's decide nothing here."
  [w kind payload]
  (when (= :design-cleared kind)
    (let [n         (get-in (edn/read-string payload) [:design :seq])
          design    (read-entry-at w n)
          decisions (mapv #(read-entry-at w (:seq %)) (span-of w :design-decision))
          decision  (->> decisions (filter #(= n (:design-seq %))) last)
          refuse!   (fn [why] (throw (ex-info (str "Clearance names the design at entry "
                                                   n ", " why)
                                              {:design n :decision (:seq decision)})))]
      (cond
        (or (nil? design) (some nil? decisions))
        (refuse! "and an entry it rests on cannot be read")

        (nil? decision)
        (refuse! "which no decision round has judged")

        (not (report/proceeds? decision))
        (refuse! (str "whose latest decision recommends " (name (:recommend decision))
                      " and does not proceed"))

        (report/owes-a-person? design)
        (refuse! "whose declarations owe a person the grant")))))

(defn- check-implementation-approved!
  "Refuse an implementation record on a workstream whose live design nothing
   cleared — neither a round nor a person.

   TWO ways a design becomes implementable, because a grant is owed only when
   the design says something high-level is at stake. A `:design-cleared` is a
   round having found it implementable without one; a `:design-approved` is a
   person granting it. Either satisfies this floor, and a design carrying
   neither has had nobody and nothing say it may be built.

   ONLY when the workstream HAS a design, and that guard is the whole of the
   rule's aim. Measured across the live ledgers: of the 18 implementation
   records ever written, 7 are on workstreams carrying no design at all —
   scratch work and quick fixes, where the design arc was never in play and
   refusing enforces nothing. A rule without the guard would have refused 13 of
   the 18 and been switched off in a week, which is the failure `standing`
   documents for its own supersession rule. With it, the same rule refuses 6
   records across 4 workstreams, and every one of them is the thing FU-65
   names: a workstream that designed, never got the design granted, and
   recorded an implementation anyway.

   All four of those closed :done and three carry a :merged entry, which is
   what makes this worth a refusal rather than a reading. The projection
   already reports the gap — `lane.reentry` clamps such a workstream back to
   :approve-design, and a test asserts it — but a clamp only decides what the
   position SAYS. Nothing consults it before the next entry, so the arc ran on
   past it four times and shipped.

   The LIVE design is the latest one, so a design superseded after its approval
   needs granting again — which is the case worth catching rather than an
   awkward edge. Redesigning is how a rejected commitment gets replaced, and an
   approval that carried over to whatever replaced it would grant the one thing
   nobody looked at.

   This is a NECESSARY condition and not `standing`, which can refuse for four
   further reasons and which the pipeline already asks before it will say a
   workstream is owed an implementation at all. It cannot be asked here:
   `standing` reads this namespace, so asking it back would be a cycle. What
   this adds is the floor underneath that — the pipeline governs what nido
   TELLS a session to do, and a person who implements anyway can still be
   refused the record that would carry the arc past the grant they never got."
  [w kind payload]
  (when (= :implementation-completed kind)
    ;; The design the record CITES, not the ledger's newest. Since a trail record
    ;; names what it was made under, the two come apart exactly where it matters:
    ;; work completed under a cleared D1 while an unjudged D2 sits above it is a
    ;; fact about D1, so asking D2's clearance would refuse the truth and admit
    ;; the lie. Falls back to the newest for a record citing none, which can only
    ;; be a workstream holding no design at all.
    (when-let [design (let [n (or (get-in (edn/read-string payload) [:design :seq])
                                  (generation/current-design w))]
                        (->> (span-of w :design) (filter #(= n (:seq %))) first))]
      (let [named   (fn [k] (->> (span-of w k)
                                 (keep #(get-in (read-entry-at w (:seq %)) [:design :seq]))
                                 set))
            granted (named :design-approved)
            cleared (named :design-cleared)]
        (when-not (or (contains? granted (:seq design))
                      (contains? cleared (:seq design)))
          (throw (ex-info (str "Nothing clears the live design (entry "
                               (:seq design) ") on " (:id w)
                               " — a round must clear it, or a person grant it,"
                               " or it must be superseded")
                          {:design    (:seq design)
                           :granted   (vec (sort granted))
                           :cleared   (vec (sort cleared))
                           :ws-id     (:id w)})))))))

(defn- refuse-unguarded-clearance!
  "A `:design-cleared` goes through `append-entry-at!` and nowhere else.

   A clearance is an answer read at a position, and only the guarded writer can
   say that position has not moved since. Admitted here it would be a clearance
   compared against nothing — and the implementation floor above trusts one as
   it trusts a grant."
  [kind]
  (when (= :design-cleared kind)
    (throw (ex-info "A :design-cleared is appended only through append-entry-at!"
                    {:kind kind}))))

(def ^:private stamped-kinds
  "Kinds whose writer the appending verb stamps from where it runs: the talk of the seats and the
   conductor, and a re-cut proposal. Appended only through `append-stamped!`, which the verb that
   stamps them calls."
  #{:tension :accord :withdrawal :recut})

(defn- refuse-unguarded-gate!
  "A `:phase-gate` goes through `open-phase!`, a `:generation` through `open-generation!`, and a
   stamped kind through `append-stamped!` — none through a generic append.

   A gate opens the next phase and a generation the next cut of the design, and whether either
   may is a question about the workstream that only the writer asking it under the lock can
   answer. Admitted through a generic append it would move the plan with no one asking. A stamped
   kind's writer is stamped by the verb that appends it; a generic append would take it on the
   writer's word."
  [kind]
  (case kind
    :phase-gate (throw (ex-info "A :phase-gate is appended only through open-phase!" {:kind kind}))
    :generation (throw (ex-info "A :generation is appended only through open-generation!" {:kind kind}))
    (when (contains? stamped-kinds kind)
      (throw (ex-info (str "A " kind " is appended only through append-stamped!, its writer stamped"
                           " by the verb that calls it")
                      {:kind kind})))))

(defn- refuse-design-between-phases!
  "No :design joins a workstream closed :between-phases. Its plan is paused under the
   design its close names, and a design written while it waits would put one gate on
   the board and open another; a newer design takes over after the gate opens."
  [w kind]
  (when (and (= :design kind) (= :between-phases (:outcome (closure w))))
    (throw (ex-info (str (:id w) " is waiting between phases — open the next phase before"
                         " writing a design over it")
                    {:ws-id (:id w) :kind kind}))))

(defn- check-clearance-earned!
  "A `:design-cleared` records a round that ran, over a design that owes nobody,
   at the position it was compared at.

   Both are on the ledger or in hand under the lock, so each is asked rather
   than trusted to the writer: a `:design-decision` naming the design that
   `report/proceeds?`, and the design's own declarations
   (`report/owes-a-person?`). The shared predicate rather than the recorded
   `:recommend`, because this is one more reader of the one clearance answer:
   testing the recommendation refused an advisory-only round that the clearance
   writer and the gate had both accepted, leaving a conforming design with
   nothing that could clear it and no grant to ask for. Not `standing` — it
   reads this namespace, and
   re-deriving it under the lock is the alternative the design turned down.
   The stale refusal is what answers for standing: any append after the
   writer's reading moves the position, so nothing can land in between.

   The POSITION is not among them, and the record carries none. It is
   `append-entry-at!`'s own argument, compared under this same lock, so a
   clearance's place in the ledger IS the snapshot it was written against — a
   field restating it could only ever agree or be wrong.

   Without it a clearance was admitted on its kind alone, and an append supplied
   the decision no round had reached."
  [w kind payload at]
  (when (= :design-cleared kind)
    (let [r        (edn/read-string payload)
          n        (get-in r [:design :seq])
          proceeds (->> (span-of w :design-decision)
                        (map #(read-entry-at w (:seq %)))
                        (some #(and (= n (:design-seq %))
                                    (report/proceeds? %))))
          design   (read-entry-at w n)]
      (cond
        (not proceeds)
        (throw (ex-info (str "Clearance names the design at entry " n
                             ", and no :design-decision on " (:id w)
                             " recommends proceeding on it — a clearance records"
                             " a round, and none reached it")
                        {:design n :ws-id (:id w)}))

        (nil? design)
        (throw (ex-info (str "Clearance names the design at entry " n
                             ", which cannot be read — its declarations are what"
                             " say whether a grant is owed")
                        {:design n :ws-id (:id w)}))

        (report/owes-a-person? design)
        (throw (ex-info (str "Clearance names the design at entry " n
                             ", which declares :challenges or :revisit — it owes"
                             " a person a grant, and no round can clear it")
                        {:design n :ws-id (:id w)}))

))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :Path]}
  append-lock-path
  "The lock that serialises appends to one workstream — `cstate/append-lock-path`."
  [project ws-id]
  (cstate/append-lock-path project ws-id))

(defn- refuse-if-taken!
  "Throw rather than write over an entry that already exists.

   The last line of defence, and it should never fire now that the seq comes off
   the disk: a ledger is append-only by contract, and silently replacing an
   entry breaks every citation keyed on its number. Cheap enough to keep as the
   thing that turns a future derivation bug into a loud failure instead of a
   record that quietly stops being what it was."
  [abs seq-n]
  (when (fs/exists? abs)
    (throw (ex-info "Refusing to overwrite an existing ledger entry"
                    {:seq seq-n :file abs}))))

(def ^:private entry-file-pattern
  "The prefix `append-entry!` gives every entry file: a four-digit :seq, then a
   hyphen. Group 1 is the number.

   Shared by the two readings of entries/ — what the next :seq must clear, and
   which files the index does not name — so a stray file in the directory is
   either an entry to both of them or to neither."
  #"^(\d{4})-")

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :int]]}
  seq-of-path
  "The :seq of the entry file at `path` — what `append-entry!` returns — read off its name, or nil.

   The one answer to which entry an append wrote. Rereading the ledger's newest entry afterwards is
   not: the append lock is released by then, and another writer's entry may be the newest."
  [path]
  (some-> (re-find entry-file-pattern (str (fs/file-name path))) second parse-long))

(defn- entry-filenames
  "The entry filenames under entries/, in :seq order. Empty when the workstream
   has no entries directory yet.

   Name order IS :seq order because the number is zero-padded; unpad it and this
   sorts 0010 before 0009."
  [project ws-id]
  (let [dir (fs/path (cstate/workstream-dir project ws-id) "entries")]
    (if-not (fs/exists? dir)
      []
      (->> (fs/list-dir dir)
           (map fs/file-name)
           (filter #(re-find entry-file-pattern %))
           sort
           vec))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :int]}
  highest-seq-on-disk
  "The largest entry number under entries/, or 0.

   The index is a projection of that directory and can fall behind it — an
   interrupted append writes the payload and dies before `write!`, and every
   later append then computes a seq the count says is free and the disk says is
   taken. One workstream reached 39 files against 37 index rows, which meant two
   design records existed that no reader could see: the review loop judged every
   ruling of a run against a record superseded twice.

   The directory is the ledger. The index is a convenience over it, and where
   they disagree the files win."
  [project ws-id]
  (->> (entry-filenames project ws-id)
       (keep #(some-> (re-find entry-file-pattern %) second parse-long))
       (reduce max 0)))

(defn ^{:malli/schema [:=> [:cat :Workstream :int] [:maybe :int]]}
  unit-of
  "The unit the entry at `seq-n` belongs to, addressed by its root goal's :seq —
   an :intent — or nil when it belongs to the workstream rather than to any
   unit.

   RESOLVED, never stored. The citations already connect a record to the goal it
   descends from, so a field beside them would be a second answer to a question
   the graph settles — and the one that drifts. It is the same refusal `place`,
   `reentry` and `standing` all make about their own readings, for the same
   reason.

   Total over the ledger: every entry answers, and the two honest answers are a
   :seq and nil. Four routes reach nil and they need no rules of their own,
   because none of the four is owed to a unit: a kind that stands on nothing —
   a note, a blocker, a review analysis; a landing, which NAMES the design whose
   work it carries and stands on none of it; a record inheriting rootlessness
   from a design written before the intent citation was required; and a record
   reaching several goals, which `check-one-root!` admits only where it inherits
   them from a record written before an intent could cite what it replaces.
   Each is reported as the workstream's rather than assigned to whichever unit
   happens to surround it, because putting a record in a unit its author never
   placed it in is the error this walk exists to refuse."
  [w seq-n]
  (let [roots (roots-at w seq-n)]
    (when (= 1 (count roots))
      (first roots))))

(defn ^{:malli/schema [:=> [:cat :Workstream :int] [:maybe :map]]}
  goal-amendment
  "How far the amendment of the goal at `goal-seq` has got, or nil when there is none to read:
   `{:goal <the live goal> :replaces <the goal it supersedes>
     :baseline <seq>? :baseline-stale? <bool> :design <seq>? :design-stale? <bool>}`.

   `goal-seq` may name any intent on the amended chain — the goal a record still stands on, or
   the live one — and the answer is about that chain's unit alone, so a workstream holding several
   units is told about THIS amendment. :baseline and :design are the unit's newest of each kind,
   stale while it still reaches a goal the amendment replaced. nil when the goal was never
   amended, or nothing in the unit was ever built on a goal the amendment replaced: then there is
   nothing to redo, only the ordinary arc.

   Citation facts, and nothing about what to do with them — which stages they leave owed, and
   whether a replacement has been judged, are the arc's to say. A replacement counts by the goal
   it cites: the ledger does not require it to :supersedes what it replaces, so this does not ask."
  [w goal-seq]
  (let [supers   (intent-supersessions w)
        goal     (live-tip w goal-seq)
        replaced (loop [n (supers goal) acc #{}]
                   (if (or (nil? n) (acc n)) acc (recur (supers n) (conj acc n))))]
    (when (seq replaced)
      (let [memo    (volatile! {})
            root    (roots-at w goal memo)
            in-unit (fn [kind]
                      (filterv #(= root (roots-at w (:seq %) memo))
                               (if (= :design kind)
                                 (span-of w :design)
                                 (filter #(= kind (:kind %)) (:entries w)))))
            stale?  (fn [kind n] (boolean (some replaced (goals-reached w kind (read-entry-at w n)))))
            bls     (in-unit :baseline)
            dss     (in-unit :design)]
        (when (or (some #(stale? :baseline (:seq %)) bls)
                  (some #(stale? :design (:seq %)) dss))
          (let [b  (:seq (peek bls))
                d  (:seq (peek dss))
                bs (and b (stale? :baseline b))
                ds (and d (stale? :design d))]
            (cond-> {:goal goal :replaces (supers goal)
                     :baseline-stale? (boolean bs) :design-stale? (boolean ds)}
              b (assoc :baseline b)
              d (assoc :design d))))))))

(defn ^{:malli/schema [:=> [:cat :Workstream] :boolean]}
  holds-design?
  "Does the current generation of `w` hold a :design at all? — the span `check-trail-attribution!`
   requires a citation over, so a writer skips exactly where the append would refuse.

   A PRESENCE CHECK, and never a citation source. Every trail record records
   work and cites the design that work named: a PR the one its publisher names,
   a merge the PR's own records, a review the design its rounds judged against.
   When nothing names one, the writer skips the record and says so rather than
   filing it under the newest design — append order is not evidence of which
   design work was done under. What those writers need is only whether a
   citation was owed at all, which is this.

   A boolean on purpose: an answer that cannot be read as `which design` is one
   no writer can fall back to for a citation.

   Off the INDEX, so it parses nothing."
  [w]
  (boolean (seq (span-of w :design))))

(defn- index-row
  "The index row for an entry: what `entry` carried, plus the ledger's own
   stamps, plus the design it names.

   `:under` MIRRORS the record's `:design :seq` onto the index, and is the one
   field here that comes out of the payload. It is a citation the author wrote,
   copied the way `:kind` already is — not a conclusion, which is what the index
   may never hold. It is mirrored because `reentry/trail-standing` reads the
   index and parses nothing, deliberately: that is what keeps the re-entry clamp
   affordable on a board computing a position per rendered row, and an
   attribution only readable by parsing every trail entry would have cost exactly
   that. Absent on rows written before the citation existed, where
   `reentry/generation` is still the answer."
  [entry seq-n payload rel]
  (let [gate?    (= :phase-gate (:kind entry))
        retract? (= :retraction (:kind entry))
        body     (when (or gate? retract? (contains? trail-kinds (:kind entry))) (edn/read-string payload))
        under    (get-in body [:design :seq])]
    (cond-> (assoc entry :seq seq-n :at (clock/now-iso) :file rel)
      under (assoc :under under)
      ;; A gate's phase is mirrored for the same reason: `record.phase/progress`
      ;; reads the index it is handed and parses nothing.
      gate? (assoc :opens (:opens body))
      (and gate? (:phase body)) (assoc :phase (:phase body))
      ;; And a retraction's target, which `record.generation` reads to tell a retraction that
      ;; belongs to no generation — of a baseline or an intent — from one that does.
      retract? (assoc :retracts (get-in body [:retracts :seq])))))

(defn- meta-rel
  "Where the index row of the entry numbered `seq-n` is kept beside the ledger.

   Its own directory rather than a second file under entries/, because entries/ IS the ledger
   and every reading of it — the next :seq, the drift report — takes any numbered file there for
   an entry."
  [seq-n]
  (format "entries.meta/%04d.edn" seq-n))

(defn- write-entry-files!
  "Write an entry's index row beside it, then the entry itself. The row first, so any entry file
   an append wrote has the row its index would have held: :at, :session and :amended-by exist
   nowhere in the payload, and an index rebuilt without them would answer differently."
  [project ws-id row abs payload]
  (io/write-edn! (str (fs/path (cstate/workstream-dir project ws-id) (meta-rel (:seq row)))) row)
  (io/write-text! abs payload))

(defn- implied-row
  "The row an entry file's name implies when nothing kept or held one, with what `index-row`
   mirrors out of the payload — `:under`, a gate's `:opens` and `:phase`, a retraction's `:retracts` — read back out of it, since a
   reader of the index parses nothing. A payload that does not parse mirrors nothing."
  [dir rel seq-n kind]
  (let [gate?    (= :phase-gate kind)
        retract? (= :retraction kind)
        body     (when (or gate? retract? (contains? trail-kinds kind))
                   (try (edn/read-string (slurp (str (fs/path dir rel)))) (catch Exception _ nil)))
        under    (when (map? body) (get-in body [:design :seq]))]
    (cond-> {:kind kind :seq seq-n :file rel}
      under                    (assoc :under under)
      (and gate? (map? body))  (assoc :opens (:opens body))
      (and gate? (map? body) (:phase body)) (assoc :phase (:phase body))
      (and retract? (map? body)) (assoc :retracts (get-in body [:retracts :seq])))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] [:vector :map]]}
  rebuilt-index
  "The :entries index entries/ implies, in :seq order: each file's row as written beside it, else
   the row `w`'s index holds for that :seq — the only copy of an earlier entry's :at and
   :amended-by, which `freeze-index!` keeps beside it — else the row its file name implies
   (`implied-row`). A row kept or held is taken only when it names that file.

   Files a row names go in however they were written, so this is the index of the directory, not
   of the appends that succeeded: a file an interrupted append left is in it."
  [project ws-id w]
  (let [dir    (cstate/workstream-dir project ws-id)
        frozen (into {} (map (juxt :seq identity)) (:entries w))]
    (vec (for [fname (entry-filenames project ws-id)
               :let  [[_ n kind] (re-find #"^(\d{4})-(.+)\.[a-z]+$" fname)
                      seq-n      (parse-long n)
                      side       (io/read-edn (str (fs/path dir (meta-rel seq-n))))]]
           (or (some #(when (= (str "entries/" fname) (:file %)) %) [side (frozen seq-n)])
               (implied-row dir (str "entries/" fname) seq-n (keyword kind)))))))

(defn- reconcile-index
  "`reconciled-index`, computed."
  [project ws-id w]
  (let [dir    (cstate/workstream-dir project ws-id)
        files  (into {} (keep (fn [fname]
                                (when-let [[_ n kind] (re-find #"^(\d{4})-(.+)\.[a-z]+$" fname)]
                                  [(str "entries/" fname) [(parse-long n) (keyword kind)]])))
                     (entry-filenames project ws-id))
        by-seq (into {} (map (fn [[rel [n _]]] [n rel])) files)
        sound? (fn [{:keys [file seq kind]}] (= [seq kind] (files file)))
        side   (fn [n] (io/read-edn (str (fs/path dir (meta-rel n)))))
        ;; A sound row reads as the row kept beside its entry; one with none kept is kept now,
        ;; which is the freeze, while its index still holds it.
        kept   (fn [row]
                 (let [path (str (fs/path dir (meta-rel (:seq row))))
                       s    (io/read-edn path)]
                   (cond
                     (= (:file row) (:file s)) s
                     (fs/exists? path)         row
                     :else                     (do (io/write-edn! path row) row))))
        implied (fn [n]
                  (let [rel (by-seq n)
                        s   (side n)]
                    (if (= rel (:file s))
                      s
                      (let [[seq-n kind] (files rel)] (implied-row dir rel seq-n kind)))))]
    (let [{:keys [rows seen]}
          (reduce (fn [{:keys [seen] :as acc} row]
                    (let [n (:seq row)]
                      (cond
                        (nil? (:file row)) (update acc :rows conj row)
                        (seen n)           acc
                        (sound? row)       (-> acc (update :rows conj (kept row)) (update :seen conj n))
                        (by-seq n)         (-> acc (update :rows conj (implied n)) (update :seen conj n))
                        :else              acc)))
                  {:rows [] :seen #{}}
                  (:entries w))
          ;; An entry file no row names is an append that died after writing it and before its
          ;; row reached the index. It passed every check the append makes before its payload was
          ;; written, and nothing beside its index row was owed with it, so it reads as indexed.
          adopted (for [n (sort (keys by-seq)) :when (not (seen n))] (implied n))]
      (if (seq adopted)
        (vec (sort-by :seq (concat rows adopted)))
        rows))))

(defonce ^:private reconciled (atom {}))

(defn- reconciled-index
  "`w`'s index as entries/ has it. A row naming a file entries/ holds under that name, :seq and
   kind reads as the row kept beside that file, and the row is kept there when none is — so a
   rebuild answers what the index did, :at and :amended-by included, once the index has been read.
   A row naming a file entries/ does not hold so is replaced by the row kept beside the file its
   :seq does name, else the row that file's name implies; dropped when no file carries that :seq,
   as is a second row naming a :seq an earlier one did. A row that names no file is kept as it is.

   A file no row names — an append that died between its entry and its index row — reads as the
   row kept beside it, else the row its name implies, at its :seq: it passed the append's checks
   before it was written, and an entry implies no change to the record besides its row.

   Memoised per workstream on the stamps of its record and of both directories: every write to
   either directory renames a file into it, which moves the directory's stamp. The board reads
   every workstream on every render, and this reads a file per entry."
  [project ws-id w]
  (let [wdir (cstate/workstream-dir project ws-id)
        k    (cstate/workstream-edn-path project ws-id)
        st   [(io/file-stamp k)
              (io/file-stamp (str (fs/path wdir "entries")))
              (io/file-stamp (str (fs/path wdir "entries.meta")))
              (:entries w)]
        hit  (get @reconciled k)]
    (if (= st (:stamp hit))
      (:rows hit)
      (let [rows (reconcile-index project ws-id w)]
        ;; The stamp after the read, since a freeze moves entries.meta's.
        (swap! reconciled assoc k {:stamp (assoc st 2 (io/file-stamp (str (fs/path wdir "entries.meta"))))
                                   :rows  rows})
        rows))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :int]}
  freeze-index!
  "Keep beside every indexed entry the row its index holds, for each that has none. Returns how
   many were written.

   Rows written before entries carried them are the only record of those entries' :at and
   :amended-by; once kept beside the ledger, the index can be deleted and rebuilt with nothing
   lost. Idempotent: a row already kept is never rewritten.

   `read-ws` is what keeps them, as it reads each row against the directory; this reads the
   workstream and counts the rows that were kept by it."
  [project ws-id]
  (io/with-file-lock
    (append-lock-path project ws-id)
    (fn []
      (let [dir     (cstate/workstream-dir project ws-id)
            path    #(str (fs/path dir (meta-rel (:seq %))))
            missing (->> (:entries (io/read-edn (cstate/workstream-edn-path project ws-id)))
                         (filter :file)
                         (remove #(fs/exists? (path %)))
                         vec)]
        (read-ws project ws-id)
        (count (filter #(fs/exists? (path %)) missing))))))


;; ── Generations ──────────────────────────────────────────────────────────────

(defn- cited-seqs
  "Every entry of the ledger of workstream `ws-id` its `record` cites: each `{:seq n}`, and each
   `…-seq`, `:reasks`, `:carried-from` or `:between-phases` number. A citation naming another
   ledger — one carrying another workstream's `:ws-id`, a fork's `:parent`, a merge's `:merges` —
   is not one of this ledger's and is left out; one carrying this workstream's own is."
  [ws-id record]
  (letfn [(walk [x]
            (cond
              (map? x)  (when (= ws-id (:ws-id x ws-id))
                          (mapcat (fn [[k v]]
                                    (cond (#{:parent :merges} k) nil
                                          (and (int? v) (or (#{:seq :reasks :carried-from :between-phases} k)
                                                            (str/ends-with? (name k) "-seq")))
                                          [v]
                                          :else (walk v)))
                                  x))
              (coll? x) (mapcat walk x)))]
    (distinct (walk record))))

(defn- check-generation-citations!
  "A record cites, beyond what belongs to no generation, only entries of its own generation — but
   for three crossings: a `:generation` cites the `:recut` of the generation it closes; a record
   cites the `:generation` that opened its own, which is of its own already; and the first design
   of a generation supersedes the design that governed the one before it.

   Asked only of a ledger a generation has been opened on: on any other every entry is the first
   generation's, and the walk would find nothing to refuse."
  [w kind payload]
  (when (and (generation/opened-by (:entries w)) (contains? report/event-schemas kind))
    (let [entries (:entries w)
          record  (edn/read-string payload)
          cur     (generation/current entries)
          own     (if (= :generation kind) (inc cur) cur)
          opened  (generation/opened-by entries)
          first?  (empty? (span-of w :design))
          allowed (cond-> #{}
                    (= :generation kind)
                    (into (map :seq) (span-of w :recut))
                    (and (= :design kind) first?)
                    (into (keep identity [(generation/closed-design w (:seq opened))])))]
      (doseq [n (cited-seqs (:id w) record)
              :let [g (generation/generation-of entries n)]
              :when (and g (not= g own) (not (allowed n)))]
        (throw (ex-info (str "A " (name kind) " of generation " own " cites entry " n
                             ", of generation " g " — a record cites only its own generation's"
                             " entries, besides what belongs to none")
                        {:kind kind :seq n :generation g :own own}))))))

(defn- check-generation-ids!
  "A design is held to the generation-defining ids its generation fixed: those the `:generation`
   opening it carries, or in a first generation those of the first design marking any element to
   be cleared or approved. Before any is fixed nothing is held."
  [w kind payload]
  (when (= :design kind)
    (when-let [fixed (generation/fixed-ids w)]
      (let [marked (generation/defining-ids (edn/read-string payload))]
        (when-not (= fixed marked)
          (throw (ex-info (str "This generation fixed its generation-defining ids as "
                               (str/join ", " (sort fixed)) ", and the design marks "
                               (if (seq marked) (str/join ", " (sort marked)) "none")
                               " — changing them is a re-cut, which a person opens as a generation")
                          {:fixed (vec (sort fixed)) :marked (vec (sort marked))})))))))

(defn- check-first-design-supersedes!
  "The first design of a re-cut generation supersedes the design that governed the generation it
   closes — which is how that design's claims, rejected alternatives and grants carry forward
   without a new intent or baseline."
  [w kind payload]
  (when (= :design kind)
    (when-let [opened (generation/opened-by (:entries w))]
      (when (empty? (span-of w :design))
        (when-let [closed (generation/closed-design w (:seq opened))]
          (let [n (get-in (edn/read-string payload) [:supersedes :seq])]
            (when-not (= closed n)
              (throw (ex-info (str "The first design of generation "
                                   (generation/current (:entries w)) " supersedes the design that"
                                   " governed the generation before it, entry " closed
                                   (when n (str " — not entry " n)))
                              {:closed closed :supersedes n})))))))))

(defn- check-stamped-citations!
  "An accord and a withdrawal name a tension, and a re-cut the tensions it was raised from."
  [w kind payload]
  (when (#{:accord :withdrawal :recut} kind)
    (let [r (edn/read-string payload)]
      (case kind
        (:accord :withdrawal) (cites! w r [:tension :seq] #{:tension} (str/capitalize (name kind)))
        :recut (doseq [i (range (count (:tensions r)))]
                 (cites! w r [:tensions i :seq] #{:tension} "Re-cut"))))))

(defn- check-append!
  "Every refusal an append makes of `payload` joining `w`, in the order `append-entry!` asks them.
   Shared by the two writes that give an entry its index row — a new entry, and one an interrupted
   append left on disk — so neither indexes an entry past a check the other would have made."
  [project w kind payload]
  (refuse-unguarded-clearance! kind)
  (refuse-design-between-phases! w kind)
  (check-baseline-citation! w kind payload)
  (check-standing-citations! w kind payload)
  (check-fork-citation! project w kind payload)
  (check-merge-citation! project w kind payload)
  (check-intent-says-its-goal! w kind payload)
  (check-goal-is-live! w kind payload)
  (check-trail-attribution! w kind payload)
  (check-one-root! w kind payload)
  (check-seam-phase-ref! kind payload)
  (check-implementation-approved! w kind payload)
  (check-generation-citations! w kind payload)
  (check-generation-ids! w kind payload)
  (check-first-design-supersedes! w kind payload)
  (check-stamped-citations! w kind payload))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map :string] :nil]}
  check-entry
  "Throw exactly what `append-entry!` would throw for `content` joining this ledger now, and write
   nothing. Nil when the append would be taken.

   Against the ledger as it is read, under no lock, so it answers for this moment only: an append
   landing between the check and the write can still change the answer. It is for an author
   checking a record before handing it over, never a guard in place of the append's own."
  [project ws-id entry content]
  (refuse-unguarded-gate! (:kind entry))
  (let [w (or (read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
        [_ payload] (report/entry-payload (:kind entry) content)]
    (check-append! project w (:kind entry) payload)
    nil))

(defn- append-locked!
  "The append, for a caller already holding the append lock over `w`: number the entry off the
   disk, make every check, write the payload and then its index row. Returns the absolute path.
   An entry implies no change to the record beside its index row: whatever it decides, the ledger
   is where it is read from."
  [project ws-id w entry content]
    (let [;; From the DISK, not from the index count. The two agree until an
          ;; append writes its payload and dies before updating the index, and
          ;; from then on every append computes a number the index says is
          ;; free and the directory says is taken — overwriting a real entry
          ;; and leaving the ledger looking consistent.
          seq-n (inc (max (count (:entries w))
                          (highest-seq-on-disk project ws-id)))
          [ext payload] (report/entry-payload (:kind entry) content)
          _     (check-append! project w (:kind entry) payload)
          fname (format "%04d-%s.%s" seq-n (name (:kind entry)) ext)
          rel   (str "entries/" fname)
          abs   (str (fs/path (cstate/workstream-dir project ws-id) rel))
          row   (index-row entry seq-n payload rel)]
      (refuse-if-taken! abs seq-n)
      (write-entry-files! project ws-id row abs payload)
      (write! (update w :entries (fnil conj []) row))
      abs))

(defn- record-status!
  "Append the status entry `entry-of` makes of the record, or nothing when it answers nil because
   there is nothing to record. Under the append lock, so the decision and its entry are one step:
   two closes, or a close and a reopen, cannot both decide from the same reading."
  [project ws-id entry-of]
  (io/with-file-lock
    (append-lock-path project ws-id)
    (fn []
      (let [w (or (read-ws project ws-id)
                  (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
        (if-let [r (entry-of w)]
          (do (append-locked! project ws-id w {:kind (:format r)} (pr-str r))
              (read-ws project ws-id))
          w)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map :string] :Path]}
  append-entry!
  "Write an immutable entry file under entries/ and record it in :entries.
   `entry` = {:kind <kw> :session <str>? :amended-by <run-id>?}, kept as the entry's index row;
   :amended-by is for a review loop's amender alone (`amended-seqs`). Returns the absolute file path.

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
   and starts being a property of the operator.

   A :design-cleared is refused here: it records a reading of the ledger at a
   position, so it is written only through `append-entry-at!`."
  [project ws-id entry content]
  (when (= :design-cleared (:kind entry))
    (throw (ex-info "A :design-cleared is written at a position — through append-entry-at!"
                    {:kind (:kind entry) :ws-id ws-id})))
  (refuse-unguarded-gate! (:kind entry))
  (io/with-file-lock
    (append-lock-path project ws-id)
    (fn []
      (append-locked! project ws-id
                      (or (read-ws project ws-id)
                          (throw (ex-info "Workstream not found"
                                          {:project project :ws-id ws-id})))
                      entry content))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :int :map :string] :map]}
  append-entry-at!
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
  (refuse-unguarded-gate! (:kind entry))
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
          (let [seq-n (inc (max latest (highest-seq-on-disk project ws-id)))
                [ext payload] (report/entry-payload (:kind entry) content)
                _     (refuse-design-between-phases! w (:kind entry))
                _     (check-baseline-citation! w (:kind entry) payload)
                _     (check-standing-citations! w (:kind entry) payload)
                _     (check-fork-citation! project w (:kind entry) payload)
                _     (check-merge-citation! project w (:kind entry) payload)
                _     (check-clearance-owed! w (:kind entry) payload)
                _     (check-intent-says-its-goal! w (:kind entry) payload)
                _     (check-goal-is-live! w (:kind entry) payload)
                _     (check-trail-attribution! w (:kind entry) payload)
                _     (check-one-root! w (:kind entry) payload)
                _     (check-seam-phase-ref! (:kind entry) payload)
                _     (check-implementation-approved! w (:kind entry) payload)
                _     (check-generation-citations! w (:kind entry) payload)
                _     (check-generation-ids! w (:kind entry) payload)
                _     (check-first-design-supersedes! w (:kind entry) payload)
                _     (check-clearance-earned! w (:kind entry) payload latest)
                fname (format "%04d-%s.%s" seq-n (name (:kind entry)) ext)
                rel   (str "entries/" fname)
                abs   (str (fs/path (cstate/workstream-dir project ws-id) rel))
                row   (index-row entry seq-n payload rel)]
            (refuse-if-taken! abs seq-n)
            (write-entry-files! project ws-id row abs payload)
            (write! (update w :entries (fnil conj []) row))
            abs))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map :string :any] :map]}
  append-entry-once!
  "Append an entry of `entry`'s kind only when no entry of that kind is already the one `same?`
   identifies. Returns `{:appended path}` for a new entry, or `{:existing path}` when one already
   matches — an append that died before indexing the entry it wrote included, since a read holds
   it (`reconciled-index`), so a retry never writes a second copy.

   `same?` is the caller's identity for the record: a predicate over an entry parsed through the
   read contract, so only a kind whose entries are EDN can ever match. Asked under the append
   lock."
  [project ws-id entry content same?]
  (refuse-unguarded-gate! (:kind entry))
  (io/with-file-lock
    (append-lock-path project ws-id)
    (fn []
      (let [w       (or (read-ws project ws-id)
                        (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
            kind    (:kind entry)
            dir     (cstate/workstream-dir project ws-id)
            abs     #(str (fs/path dir %))
            match?  (fn [rel]
                      (try (boolean (same? (report/parse-event kind (io/read-edn (abs rel)))))
                           (catch Throwable _ false)))
            indexed (->> (:entries w) (filter #(= kind (:kind %))) (map :file) (filter match?) first)]
        (if indexed
          {:existing (abs indexed)}
          {:appended (append-locked! project ws-id w entry content)})))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :Workstream]}
  open-phase!
  "Append `gate` — a `:phase-gate` payload — which reopens the workstream (`closure` stops at a
   gate), the next phase's implementation owed. The kind's one writer.

   Under the append lock, and every precondition read there, because each is a
   fact about the ledger an append could change: the workstream is closed
   :between-phases, `gate`'s `:evidence` is not blank, it cites the design that
   close names, and `record.phase/progress` over that design and these rows
   names `:opens` — and `:phase`, where the gate carries one — as the next phase.
   Whether the landing's :merged was written is
   not asked: the close is the landing's record for the gate, and each landing
   path attempts its :merged before it closes, so none can follow the gate.

   Throws ex-info naming the precondition that failed; writes nothing then.
   Returns the record."
  [project ws-id gate]
  (io/with-file-lock
    (append-lock-path project ws-id)
    (fn []
      (let [w      (or (read-ws project ws-id)
                       (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
            closed (closure w)
            n      (get-in closed [:design :seq])
            design (when n (read-entry-at w n))
            p      (when (= :design (:format design)) (phase/progress design (:entries w)))
            refuse (fn [why data]
                     (throw (ex-info (str "Cannot open a phase on " ws-id ": " why)
                                     (merge {:ws-id ws-id} data))))]
        (cond
          (not= :between-phases (:outcome closed))
          (refuse "it is not settled between phases" {:closed closed})

          (str/blank? (:evidence gate))
          (refuse "the gate carries no evidence" {})

          (nil? p)
          (refuse (str "its close names no phased design on this ledger"
                       (when n (str " (entry " n ")")))
                  {:design n})

          (not= n (get-in gate [:design :seq]))
          (refuse (str "the gate cites entry " (get-in gate [:design :seq])
                       ", and the plan waiting on it is the design at entry " n)
                  {:design n :cites (get-in gate [:design :seq])})

          (not= (:opens gate) (:claim (:next p)))
          (refuse (str "it opens \"" (:opens gate) "\", and the next phase of the plan is "
                       (if-let [c (:claim (:next p))] (str "\"" c "\"") "none"))
                  {:opens (:opens gate) :next (:claim (:next p))})

          (and (:phase gate) (not= (:phase gate) (inc (:current p))))
          (refuse (str "it opens phase " (:phase gate) ", and the next phase of the plan is "
                       (inc (:current p)))
                  {:phase (:phase gate) :next (inc (:current p))}))
        (append-locked! project ws-id w {:kind :phase-gate} (pr-str gate))
        (read-ws project ws-id)))))


(def ^:private falsifications
  "Every kind of entry belonging to a generation that records a claim of its design falsified, and
   how to read its falsifications as `{:claim <id> :by <what falsified it>}` — the one place a
   generation's falsified claims are read from, so a kind recording them is added here or is not
   inherited. A design decision's finding naming a claim (a question put to the person excepted);
   a design verdict's broken invariant or load-bearing property. Not a verdict's unmet invariant:
   the design holds it and the code falls short, so no claim of the design is falsified. Nor a
   baseline review's refutation: a baseline belongs to no generation, and is read in every one."
  {:design-decision (fn [r] (keep (fn [{:keys [claim-id claim for-person]}]
                                    (when (and claim-id (not for-person))
                                      {:claim claim-id :by claim}))
                                  (:findings r)))
   :design-verdict  (fn [r] (map (fn [{:keys [invariant finding]}] {:claim invariant :by finding})
                                 (concat (:invariants-broken r) (:load-bearing-broken r))))})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :Workstream]}
  open-generation!
  "Append `gen` — a `:generation` payload a person gives — opening the next generation of the
   workstream's design. The kind's one writer.

   Under the append lock, and every precondition read there: the workstream is not waiting between
   phases, no review run of it is live (`activity/held?`), and `gen` cites a `:recut` of the
   generation it closes. The tensions that re-cut names as its cause, and the claims the closing
   generation's records found falsified — read through `falsifications`, as each claim's id and
   what falsified it — are carried by value from the ledger, replacing any `gen` holds, so the new
   generation inherits what was raised rather than what its opener retold — and an entry those are
   read from that cannot be read refuses the opening rather than carrying less. Throws naming the
   precondition that failed and writes nothing then. Returns the record."
  [project ws-id gen]
  (io/with-file-lock
    (append-lock-path project ws-id)
    (fn []
      (let [w      (or (read-ws project ws-id)
                       (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
            n      (get-in gen [:recut :seq])
            recut  (some #(when (= n (:seq %)) %) (:entries w))
            cur    (generation/current (:entries w))
            refuse (fn [why data]
                     (throw (ex-info (str "Cannot open a generation on " ws-id ": " why)
                                     (merge {:ws-id ws-id} data))))]
        (cond
          (= :between-phases (:outcome (closure w)))
          (refuse "it is waiting between phases" {})

          ;; A review run reads the ledger as it stood when it began; opening a generation under
          ;; it would leave the run judging a generation that has closed. A claim is taken only
          ;; under this append lock (`activity/with-claim`), so none starts before the append.
          (activity/held? project ws-id)
          (let [run (:run-id (activity/read-live project ws-id))]
            (refuse (str "a review run of it is live" (when run (str " — " run))
                         "; let it finish first")
                    {:run run}))

          (not= :recut (:kind recut))
          (refuse (str "entry " n " is " (if recut (str "a " (:kind recut)) "not on it")
                       " — a generation opens from a :recut")
                  {:recut n})

          (not= cur (generation/generation-of (:entries w) n))
          (refuse (str "the re-cut at entry " n " is of generation "
                       (generation/generation-of (:entries w) n) ", and the one open is " cur)
                  {:recut n :current cur}))
        (let [;; Every value the entry carries is read through this, and an entry it cannot
              ;; read refuses the opening: what it carries is all the new generation inherits of
              ;; the one it closes, so a source silently read as nothing is lost for good.
              carried   (fn [m]
                          (or (read-entry-at w m)
                              (refuse (str "entry " m ", which what it carries is read from, cannot be read")
                                      {:unreadable m})))
              caused    (->> (:tensions (carried n))
                             (map #(select-keys (carried (:seq %)) [:by :to :about :statement]))
                             vec)
              falsified (->> falsifications
                             (mapcat (fn [[kind of]]
                                       (mapcat #(of (carried (:seq %))) (span-of w kind))))
                             distinct
                             vec)]
          (append-locked! project ws-id w {:kind :generation}
                          (pr-str (cond-> (dissoc gen :tensions :falsified)
                                    (seq caused)    (assoc :tensions caused)
                                    (seq falsified) (assoc :falsified falsified)))))
        (read-ws project ws-id)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:or :map [:=> [:cat :Workstream] :map]]] :Path]}
  append-stamped!
  "Append a `:tension`, `:accord`, `:withdrawal` or `:recut` whose `:by` the caller stamped from
   where it runs. The ledger cannot tell where that was; the one caller is the verb that can, and
   the generic appends refuse these kinds so nothing else takes a writer on its word. Every other
   check an append makes holds. `payload` is the entry, or a function of the workstream read under
   the append lock answering it — so a writer stamped from that read is stamped against the same
   generation the entry joins, which no generation can open between. Returns the absolute path."
  [project ws-id payload]
  (io/with-file-lock
    (append-lock-path project ws-id)
    (fn []
      (let [w       (or (read-ws project ws-id)
                        (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
            payload (if (map? payload) payload (payload w))
            kind    (:format payload)]
        (when-not (contains? stamped-kinds kind)
          (throw (ex-info (str "append-stamped! takes " (str/join ", " (sort stamped-kinds))
                               ", not " kind)
                          {:kind kind})))
        (append-locked! project ws-id w {:kind kind} (pr-str payload))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:=> [:cat :Workstream] :any]] :any]}
  holding-ledger
  "Answer `f` of the workstream read under its append lock — the lock every append, and a
   generation's opening, holds — so what `f` decides from that read stays true until it returns.
   `f` must not append: the lock is not reentrant."
  [project ws-id f]
  (io/with-file-lock
    (append-lock-path project ws-id)
    (fn []
      (f (or (read-ws project ws-id)
             (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :keyword] [:maybe :LedgerEntry]]}
  latest-entry
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
        (try (-> (parse-entry-file kind f)
                 (assoc :seq (:seq e) :at (:at e)))
             (catch Throwable _ nil))))))

(defn ^{:malli/schema [:=> [:cat :LedgerEntry] :map]}
  unstamp
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :keyword] [:sequential :LedgerEntry]]}
  entries-of
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :int] [:maybe :LedgerEntry]]}
  entry-at-seq
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

(defn ^{:malli/schema [:=> [:cat :Workstream] [:maybe :string]]}
  newest-goal
  "The :goal of the newest :intent entry on the record `w`, or nil when it states
   none or that entry cannot be parsed."
  [w]
  (some->> (:entries w) (filter #(= :intent (:kind %))) last :seq
           (read-entry-at w) :goal not-empty))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:set :int]]}
  amended-seqs
  "The :seqs of the entries a review loop's amender wrote, read off the index rows `append-entry!`
   stamped `:amended-by <run-id>`. Empty for an absent workstream.

   On the INDEX, never the payload: an amender is handed a record and returns one, so a stamp in
   the payload is one it could copy forward or drop, and a person copying a record forward would
   inherit it. Nobody authors an index row. An entry written before the stamp existed is not in
   this set whoever wrote it."
  [project ws-id]
  (into #{} (keep #(when (:amended-by %) (:seq %))) (:entries (read-ws project ws-id))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :WorkstreamId]]}
  list-ids
  "Vector of ws-ids under a project's workstreams dir; [] if none."
  [project]
  (let [d (cstate/workstreams-dir project)]
    (if (fs/exists? d)
      (->> (fs/list-dir d) (filter fs/directory?) (mapv #(str (fs/file-name %))))
      [])))

(defn- published-design
  "The design the work a PR publishes was done under: what the publisher names,
   else what an implementation record listing this PR among its :artifacts names.
   nil when neither does.

   NEVER the newest design. A PR publishes work already done, and a design
   appended while it was being done is one the work was never made under — citing
   the newest would record D1's work as published under D2, and the merge poller
   copies this citation onto the landing."
  [w {:keys [id url]} design]
  (or design
      (->> (:entries w)
           (filter #(and (:under %) (= :implementation-completed (:kind %))))
           (filter (fn [e]
                     (some #(and (= :pr (:kind %))
                                 (or (and id (= (str/lower-case id)
                                                (some-> (:ref %) str/lower-case)))
                                     (and url (= url (:url %)))))
                           (:artifacts (read-entry-at w (:seq e))))))
           last
           :under)))

(defn- record-pr-opened!
  "Append the :pr-opened event for a freshly-stamped :github ref, unless a
   shipment is already OUTSTANDING — a :pr-opened with no :merged after it.
   Stacks stamp one ref per layer but ship once, so the FIRST layer's ref is
   that shipment's mark on the timeline and the rest are silent — the per-layer
   detail already lives in the refs themselves and in
   :implementation-completed's :artifacts.

   Outstanding rather than \"carries one at all\", because `reopen!` keeps the
   entries: a workstream that landed and came back is on its NEXT shipment, and
   suppressing that one discards the citation the publisher handed us. Nothing
   else holds it — `github-merge/merge-design` reads the shipment's :pr-opened
   when no record of the new PR names a design yet — so the later landing would
   be skipped for naming none, after the workstream had already closed on it.

   Skipped (loudly) without a :url and :title, which PrOpened requires, and on a
   ledger holding a design when nothing names the one the work was done under —
   the append would refuse it, and a guessed citation is the thing it refuses.
   The ref is already written by then: correlation is what the merge poller
   needs, and it must never be lost to a malformed event."
  [project w {:keys [url title] :as ref} {:keys [summary design]}]
  (when-not (= :pr-opened (:kind (last (filter #(#{:pr-opened :merged} (:kind %))
                                               (:entries w)))))
    (let [d    (published-design w ref design)
          skip (cond
                 (or (str/blank? url) (str/blank? title))
                 "carries no :url/:title"
                 (and (nil? d) (holds-design? w))
                 "names no :design, and no implementation record listing it does")]
      (if skip
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "WARN: github ref on " (:id w) " " skip
                         "; skipping the :pr-opened ledger event")))
        (append-entry! project (:id w) {:kind :pr-opened}
                       (pr-str (cond-> {:format :pr-opened :url url :title title}
                                 (not (str/blank? summary)) (assoc :summary summary)
                                 d (assoc :design {:seq d}))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map [:? [:maybe :map]]] :Workstream]}
  add-ref!
  "Append an external ref, deduped on (adapter, id). Returns updated record.

   A :github ref carries a second obligation: it is the PR, so stamping it also
   files the :pr-opened event (see record-pr-opened!). The two used to be separate
   steps in the prepare-draft-pr skill, and they diverged exactly as you would
   expect — the ref is load-bearing (the merge poller correlates on it) so it
   always landed, while the ledger event, being merely informative, was dropped on
   more than half the PRs. One fact, one call, nothing left to remember. `opts`
   may carry the two parts of the event nido cannot synthesize: a :summary, and
   the :design seq the published work was done under."
  ([project ws-id ref] (add-ref! project ws-id ref nil))
  ([project ws-id ref opts]
   (let [w (or (read-ws project ws-id)
               (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
         dup? (some #(and (= (:adapter %) (:adapter ref)) (= (:id %) (:id ref)))
                    (:external-refs w))]
     (if dup?
       w
       (let [w' (write! (update w :external-refs (fnil conj []) ref))]
         (when (= :github (:adapter ref))
           (record-pr-opened! project w' ref opts))
         w')))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  delete!
  "Remove a workstream's directory (and everything under it). Idempotent —
   no-op if absent. Used to clean up an orphan minted by a failed spawn."
  [project ws-id]
  (let [d (cstate/workstream-dir project ws-id)]
    (when (fs/exists? d) (fs/delete-tree d))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :keyword :string] [:maybe :Workstream]]}
  find-by-ref
  "Scan a project's workstreams for one carrying an external ref matching
   (adapter, external-id). Returns the workstream record or nil."
  [project adapter external-id]
  (->> (list-ids project)
       (keep #(read-ws project %))
       (some (fn [w]
               (when (some #(and (= adapter (:adapter %)) (= external-id (:id %)))
                           (:external-refs w))
                 w)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] [:maybe :Workstream]]}
  find-by-ref-id
  "The workstream carrying any external-ref whose :id = `external-id` (adapter-
   agnostic — Notion BR-#### or Slack id), or nil. Used to route a ref-keyed
   ledger append/read to its workstream. O(workstreams)."
  [project external-id]
  (->> (list-ids project)
       (keep #(read-ws project %))
       (some (fn [w] (when (some #(= external-id (:id %)) (:external-refs w)) w)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] :Workstream]}
  find-or-mint-by-ref!
  "The workstream carrying `external-id` as a ref, minting a minimal one when none does — the
   spine every ticket-keyed write shares, so intake-triage and pickup-drive land on one
   workstream. The adapter is inferred from the id: a `slack-` id is a Slack message, anything
   else a Notion ticket."
  [project external-id]
  (or (find-by-ref-id project external-id)
      (create! project
               {:external-refs [{:adapter (if (str/starts-with? external-id "slack-")
                                            :slack-message :notion)
                                 :id external-id}]})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :map :string] [:maybe :Path]]}
  append-to-ref!
  "Append a ledger entry to the workstream carrying `external-id`, found or minted by
   `find-or-mint-by-ref!`. Returns the entry file path."
  [project external-id entry content]
  (append-entry! project (:id (find-or-mint-by-ref! project external-id)) entry content))

