(ns nido.coordinator.record.workstream
  "The workstream: the source-agnostic spine. Owns the append-only log and the
   descriptive workflow stage. Engagement state is NOT stored here — it is a
   projection over this workstream's session records (see nido.coordinator.record.session).
   See spec docs/superpowers/specs/2026-06-05-workstream-session-model-design.md.

   STORED IN THE CORE. A workstream's identity and its ledger are core state, written only as
   core commands (`nido.core.client`) and read back from the core: the identity and the index
   rows from the workstream's committed state, an entry's content by its seq. An append names
   the seq the ledger stood at when its checks were made, and the core refuses it once anything
   was appended since, so no entry is numbered twice or written over. The per-workstream append
   lock still serialises nido's own processes, because several callers decide from one reading
   and append more than once before letting go (`appending!`, `holding-ledger`)."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.entry :as entry]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.phase :as phase]
   [nido.coordinator.report :as report]
   [nido.coordinator.report.model :as report-model]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.core.provision :as provision]
   [nido.core.client :as client]
   [nido.platform.core :as core]
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

(def status-kinds
  "Entry kinds that record a decision about a workstream's status — settled, reopened, put at a
   stage, findings resolved, born a one-off, the pipeline activated, a Notion status seen, a
   conductor's answer to a hand-back — rather than a record of the work. They are the ledger's copy of what used to be overwritten in place.

   Kept apart because the work's own records are what everything asking `what happened last`
   means: a close appended after a completed implementation must not become the answer to
   `how did the merge run end`."
  #{:closed :reopened :stage-set :findings-resolved :scratch :activated :deactivated :paused :resumed :notion-status
    :conduct-decision :drain :slice})

(defn ^{:malli/schema [:=> [:cat :Workstream] [:maybe :map]]}
  newest-record
  "The index row of the newest entry that records the work rather than its status, or nil."
  [w]
  (last (remove #(status-kinds (:kind %)) (:entries w))))

(def ^:dynamic ^:private *under-lock*
  "True while this thread holds a workstream's append lock: every read under it is asked of the
   core, never taken from a sweep's snapshot, so a decision made under the lock is made from the
   ledger the append will join."
  false)

(defonce ^:private swept
  (atom {}))

(def ^:private sweep-ms
  "How long a workstream's state read in a sweep of its project (`list-ids`) answers `read-ws`
   outside the append lock. A sweep reads thousands of workstreams in one request rather than one
   each; a reader that needs the ledger as it stands now takes the lock."
  2000)

(defn- sweep-key [project ws-id]
  [(str (core/nido-root)) (client/ws-key project ws-id)])

(def ^:dynamic ^:private *pass*
  "While a pass over projects runs (`over-project`), an atom of what their reads answered:
   {:read {[root project] [ws-id …]} :states {[root key] state}}. Every `read-ws` in the pass's
   extent of a workstream it read answers from it, outside the append lock, however long the
   pass takes. Nil outside a pass."
  nil)

(defn- forget!
  "Drop what a sweep or a pass held of workstream `ws-id`, so this process reads its own writes."
  [project ws-id]
  (swap! swept dissoc (sweep-key project ws-id))
  (when-let [p *pass*] (swap! p update :states dissoc (sweep-key project ws-id))))

(defn- core-state
  "Workstream `ws-id`'s committed state in the core."
  [project ws-id]
  (let [k    (sweep-key project ws-id)
        pass (when-not *under-lock* (some-> *pass* deref :states))
        hit  (when-not *under-lock* (get @swept k))]
    (cond
      (contains? pass k)                                                     (get pass k)
      (and hit (< (- (System/currentTimeMillis) (:at hit)) sweep-ms))        (:state hit)
      :else (client/value! (client/state (client/ws-key project ws-id)) :state))))

(defn- record-of
  "The workstream record a committed core `state` holds for `ws-id`, or nil when it holds none."
  [ws-id state]
  (when-let [i (when-not (:discarded? state) (:identity state))]
    (-> i (assoc :id ws-id :entries (vec (:ledger state))))))

(defn ^{:malli/schema [:=> [:cat :WorkstreamId :map] [:maybe :Workstream]]}
  record-of-state
  "The workstream record committed core `state` holds for `ws-id` — what `read-ws` answers for
   it — or nil when it holds none. For a reader handed states by the core in bulk."
  [ws-id state]
  (record-of ws-id state))

(defn ^{:malli/schema [:=> [:cat [:? [:maybe [:map-of :string :string]]]] [:maybe [:tuple :keyword :string]]]}
  turn-ws
  "The workstream the core turn this process runs in works on, as [project ws-id], or nil outside
   every turn — the core names it \"<project>/<ws-id>\" in NIDO_CORE_WS, and every process a turn
   starts inherits it. `env` is the environment to read (default the process's). The one way a
   process finds its own workstream: a working copy names none, since the core provisions a
   unit's session without one."
  ([] (turn-ws nil))
  ([env]
   (when-let [k (if env (get env "NIDO_CORE_WS") (System/getenv "NIDO_CORE_WS"))]
     (let [i (.indexOf ^String k "/")]
       (when (pos? i) [(keyword (subs k 0 i)) (subs k (inc i))])))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :Workstream]]}
  read-ws
  "The workstream `ws-id` of `project` as the core holds it — its identity and its ledger's index
   rows — or nil when the core holds no such workstream."
  [project ws-id]
  (record-of ws-id (core-state project ws-id)))

(declare read-project closure)

(defn- unit-sessions
  "The sessions of committed core `state`'s units, as `sessions` answers them."
  [state]
  (->> (vals (:units state))
       (sort-by :id)
       (mapv (fn [{:keys [id status session]}]
               {:name   (provision/session-name {:unit id :cwd (:cwd session)})
                :unit   id
                :open?  (= :open status)
                :status (:status session :none)
                :cwd    (:cwd session)}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:vector :UnitSession]]}
  sessions
  "The sessions of workstream `ws-id`: one per unit the core holds for it, in unit order, whatever
   became of the unit. `:status` is where the core's provisioning has its session — `:ready` is up
   — and `:open?` whether its unit is. Empty for a workstream the core holds none of. A session
   no unit has, a person's own, is no workstream's."
  [project ws-id]
  (if-let [st (core-state project ws-id)]
    (if (:discarded? st) [] (unit-sessions st))
    []))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:vector :WorkstreamUnit]]}
  units
  "The units the core holds for workstream `ws-id`, in unit order, whatever became of each: the
   trigger whose fire opened it (absent for one a person or the conductor opened), whether it is
   open, and whether a landing is granted on it — granted stays true until it merges, a landing
   that failed included. Empty for a workstream the core holds none of."
  [project ws-id]
  (let [st (core-state project ws-id)]
    (if (or (nil? st) (:discarded? st))
      []
      (->> (vals (:units st))
           (sort-by :id)
           (mapv (fn [{:keys [id trigger status landing?]}]
                   (cond-> {:id id :open? (= :open status) :landing? (boolean landing?)}
                     trigger (assoc :trigger trigger))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName] [:maybe :WorkstreamId]]}
  of-session
  "The open workstream of `project` one of whose units has the session named `session-name`, or
   nil when none does — a person's own session included. Open as the core says and with no
   closure on its ledger, read in one request: a closed workstream's sessions are torn down, and
   no verb works on one through them."
  [project session-name]
  (->> (read-project project {:open? true})
       (some (fn [[id st]] (when (and (some #(= session-name (:name %)) (unit-sessions st))
                                      (not (closure (record-of id st))))
                             id)))))

(defn- create-command!
  "Send `command` on workstream `ws-id` under `key`, and throw unless the core admits it."
  [project ws-id key command]
  (let [k (client/ws-key project ws-id)
        o (client/command! k key command)]
    (forget! project ws-id)
    (when (client/unavailable? o) (client/value! o :events))
    (when-not (contains? o :events)
      (throw (ex-info (str "The core refused " (name (:command command)) " on " ws-id ": "
                           (pr-str (or (:refused o) (:error o))))
                      {:ws-id ws-id :command (:command command) :outcome o})))
    o))

(defn- revise!
  "Revise the identity of workstream `ws-id` with `changes` (a nil value removes the key), and
   return the record. Throws if the workstream is absent."
  [project ws-id changes what]
  (when-not (read-ws project ws-id)
    (throw (ex-info (str "Cannot " what " on absent workstream") {:project project :ws-id ws-id})))
  (create-command! project ws-id (str "identity/" (client/ws-key project ws-id) "/" (random-uuid))
                   {:command :revise-identity :changes changes})
  (validate (read-ws project ws-id)))

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

(declare append-entry!)

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :Workstream]}
  create!
  "Mint an id and persist a fresh workstream. `base` may carry :external-refs (default []),
   :intake {:trigger <kw> :payload <map>} (default absent) and :facets <map> (default absent).
   Where it is placed, chosen to be or settled is what its ledger comes to record.

   `:activated? true` births it with nido's recipe running — its first entry an :activated by
   :nido. Every workstream nido starts runs the recipe, so every birth path passes it but the
   one-off a person starts by hand (`scratch`), which is born plain; without it the ledger starts
   empty."
  [project {:keys [external-refs intake facets activated?]}]
  (let [w (validate (cond-> {:id            (mint-id)
                             :project       project
                             :external-refs (vec (or external-refs []))
                             :created-at    (clock/now-iso)
                             :entries       []}
                      intake       (assoc :intake intake)
                      (seq facets) (assoc :facets facets)))]
    (create-command! project (:id w) (str "create/" (client/ws-key project (:id w)))
                     {:command :create-workstream :identity (dissoc w :id :entries)})
    ;; The directory its sessions, Runs and locks are kept in. Which workstreams exist is the
    ;; core's to say (`list-ids`), not this directory's.
    (fs/create-dirs (cstate/workstream-dir project (:id w)))
    (if activated?
      (do (append-entry! project (:id w) {:kind :activated} (pr-str {:format :activated :by :nido}))
          (read-ws project (:id w)))
      w)))

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
  (revise! project ws-id {:facets (not-empty facets)} "set facets"))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string :string] :Workstream]}
  set-title!
  "Overwrite a workstream's generated :title with `text`, generated from `basis`.
   Throws if the workstream is absent. Returns the updated record."
  [project ws-id text basis]
  (revise! project ws-id {:title {:text text :basis basis}} "set title"))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :Workstream]}
  clear-title!
  "Remove a workstream's generated :title. Throws if the workstream is absent.
   Returns the updated record."
  [project ws-id]
  (revise! project ws-id {:title nil} "clear title"))

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

(defn ^{:malli/schema [:=> [:cat :any] [:maybe [:enum :activated :deactivated]]]}
  activation
  "The kind of the newest :activated or :deactivated entry in `w`'s ledger — whether a person's
   last word on nido's recipe was to run it or to stop it — or nil when neither was ever said."
  [w]
  (->> (:entries w) (filter #(#{:activated :deactivated} (:kind %))) last :kind))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:maybe :string]] :Workstream]}
  activate!
  "Append the person's :activated entry — the grant that nido's recipe runs on the workstream —
   naming `session` when it was given from one. Appends nothing while the recipe already runs; a
   deactivated workstream is activated again. Throws if the workstream is absent or closed.
   Returns the record."
  [project ws-id session]
  (let [w (or (read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
    (when (closure w)
      (throw (ex-info (str "Workstream " ws-id " is closed: there is nothing to activate")
                      {:reason :closed :ws-id ws-id}))))
  (record-status! project ws-id
                  (fn [w] (when-not (= :activated (activation w))
                            (cond-> {:format :activated :by :person} session (assoc :session session))))))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :map]]}
  pause
  "The person's standing pause on `w`: its newest :paused entry's index row when no :resumed came
   after it, else nil. A pause is a person's deliberate choice not to push the workstream forward
   for now — never something nido infers."
  [w]
  (let [p (->> (:entries w) (filter #(#{:paused :resumed} (:kind %))) last)]
    (when (= :paused (:kind p)) p)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:maybe :string]] :Workstream]}
  pause!
  "Append the person's :paused entry, with `note` saying why when given. Appends nothing while a
   pause already stands. Throws if the workstream is absent or closed. Returns the record."
  [project ws-id note]
  (let [w (or (read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
    (when (closure w)
      (throw (ex-info (str "Workstream " ws-id " is closed: there is nothing to pause")
                      {:reason :closed :ws-id ws-id}))))
  (record-status! project ws-id
                  (fn [w] (when-not (pause w)
                            (cond-> {:format :paused :by :person}
                              (not (str/blank? note)) (assoc :note note))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :Workstream]}
  resume!
  "Append the person's :resumed entry, lifting the standing pause. Appends nothing when none
   stands. Throws if the workstream is absent. Returns the record."
  [project ws-id]
  (when-not (read-ws project ws-id)
    (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
  (record-status! project ws-id
                  (fn [w] (when (pause w) {:format :resumed :by :person}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:maybe :string]] :Workstream]}
  deactivate!
  "Append the person's :deactivated entry — nido's recipe stops running on the workstream: no
   conductor, nothing owed, and its designs no longer gate its landing. Appends nothing unless the
   recipe runs. Throws if the workstream is absent. Returns the record."
  [project ws-id session]
  (when-not (read-ws project ws-id)
    (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
  (record-status! project ws-id
                  (fn [w] (when (= :activated (activation w))
                            (cond-> {:format :deactivated :by :person} session (assoc :session session))))))

(defn ^{:malli/schema [:=> [:cat :any :int] [:maybe :string]]}
  entry-text
  "The content of the entry at `seq-n` on the workstream record `w`, as it was written, or nil —
   `nido.coordinator.record.entry/text`."
  [w seq-n]
  (entry/text w seq-n))

(defn- read-entry-at
  "Parse the entry at `seq-n` on the workstream record `w`, through the READ
   contract, stamped with :seq/:at — or nil when absent or unparseable. Degrades
   to nil for the same reason latest-entry does: a reader following a citation
   has to be able to find nothing without the caller crashing."
  [w seq-n]
  (entry/parsed w seq-n))

(defn- span-of
  "The index rows of `kind` in the current generation's span of `w`, in ledger order — the one
   place the ledger's own checks and readings take designs and their decisions from. A retired
   generation's are neither the design a check is about nor evidence for or against it."
  [w kind]
  (filter #(= kind (:kind %)) (generation/current-span (:entries w))))

(def ^:private closure-kinds
  "The kinds whose newest says whether a workstream stands settled (`closure`)."
  [:closed :reopened :phase-gate])

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :map]]}
  closure
  "How the ledger says the workstream `w` stands settled: the newest :closed entry, unless a
   :reopened or a :phase-gate came after it — then nil, since opening the next phase is a reopen
   by definition. Nil for a workstream that does not exist.

   The newest close is read by its :seq, and when it cannot be read the answer is still closed —
   `{:seq n :unreadable? true}`, with no outcome — never an older close's payload, which would
   report an outcome the workstream has since moved past."
  [w]
  (let [newest (last (filter #(some #{(:kind %)} closure-kinds) (:entries w)))]
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

   A :retraction's target and a :design-cleared's design are
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
  (when (#{:retraction :design-cleared :design :baseline :intent} kind)
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
   landing and every earlier one stays in the ledger for ever.

   Except on a workstream a person deactivated: work done after the recipe was taken off was
   made under no design, so it cites none — a citation it does supply must still resolve."
  [w kind payload]
  (when (contains? trail-kinds kind)
    (let [designs (span-of w :design)
          r       (edn/read-string payload)]
      (when (and (seq designs) (nil? (get-in r [:design :seq]))
                 (not= :deactivated (activation w)))
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
   decision naming its design lets it proceed.

   Asked of entries this lock can see and never of `standing`, which reads this
   namespace. The third part — nothing blocked the design when it was cleared —
   IS standing's answer, read by the writer at a position it hands
   `append-entry-at!` — never stores — and that compare is what keeps the
   reading true at the write. That is why `append-entry!`
   refuses the kind outright: every reader that admits a design on a clearance
   naming it trusts this record, so one written with no round behind it is work
   nobody judged.

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
                      " and does not proceed"))))))

(defn- check-implementation-approved!
  "Refuse an implementation record on a workstream whose live design nothing
   cleared.

   A `:design-cleared` is a round having found the design implementable. A
   `:design-approved` — a person's grant, which ledgers from before every design
   was cleared by its round still hold — satisfies this floor too. A design
   carrying neither has had nothing say it may be built.

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
   its design — but a clamp only decides what the position SAYS. Nothing
   consults it before the next entry, so the arc ran on past it four times and
   shipped.

   The LIVE design is the latest one, so a design superseded after it was
   cleared needs clearing again — which is the case worth catching rather than
   an awkward edge. Redesigning is how a rejected commitment gets replaced, and
   a clearance that carried over to whatever replaced it would admit the one
   thing no round judged.

   This is a NECESSARY condition and not `standing`, which can refuse for four
   further reasons and which the pipeline already asks before it will say a
   workstream is owed an implementation at all. It cannot be asked here:
   `standing` reads this namespace, so asking it back would be a cycle. What
   this adds is the floor underneath that — the pipeline governs what nido
   TELLS a session to do, and a person who implements anyway can still be
   refused the record that would carry the arc past the round that never cleared it."
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
                               " — its decision round must proceed on it,"
                               " or it must be superseded")
                          {:design    (:seq design)
                           :granted   (vec (sort granted))
                           :cleared   (vec (sort cleared))
                           :ws-id     (:id w)})))))))

(defn- refuse-unguarded-clearance!
  "A `:design-cleared` goes through `append-entry-at!` and nowhere else.

   A clearance is an answer read at a position, and only the guarded writer can
   say that position has not moved since. Admitted here it would be a clearance
   compared against nothing — and the implementation floor above trusts one."
  [kind]
  (when (= :design-cleared kind)
    (throw (ex-info "A :design-cleared is appended only through append-entry-at!"
                    {:kind kind}))))

(def ^:private stamped-kinds
  "Kinds whose writer the appending verb stamps from where it runs: the talk of the seats and the
   conductor, a re-cut proposal, and the conductor's decision on a hand-back. Appended only
   through `append-stamped!` or `appending!`, which the verb that stamps them calls."
  #{:tension :accord :withdrawal :recut :conduct-decision})

(defn- refuse-unguarded-gate!
  "A `:phase-gate` goes through `open-phase!`, a `:generation` through `open-generation!`, a
   `:design-decision` through `append-decision!`, and a stamped kind through `append-stamped!` —
   none through a generic append.

   A decision is the judgement a decision round reached, and a clearance trusts any proceeding one
   on the ledger; taken from a generic append, a decision anyone wrote would admit a design no
   round judged.

   A gate opens the next phase and a generation the next cut of the design, and whether either
   may is a question about the workstream that only the writer asking it under the lock can
   answer. Admitted through a generic append it would move the plan with no one asking. A stamped
   kind's writer is stamped by the verb that appends it; a generic append would take it on the
   writer's word."
  [kind]
  (case kind
    :design-decision (throw (ex-info "A :design-decision is appended only through append-decision!, which a decision round calls"
                                     {:kind kind}))
    :phase-gate (throw (ex-info "A :phase-gate is appended only through open-phase!" {:kind kind}))
    :generation (throw (ex-info "A :generation is appended only through open-generation!" {:kind kind}))
    (cond
      (contains? stamped-kinds kind)
      (throw (ex-info (str "A " kind " is appended only through append-stamped! or appending!, its writer stamped"
                           " by the verb that calls it")
                      {:kind kind}))
      (contains? report/drain-kinds kind)
      (throw (ex-info (str "A " kind " is a drain's record, appended only through nido.coordinator.record.drain")
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
  "A `:design-cleared` records a round that ran, over a design on the ledger, at
   the position it was compared at.

   The decision is on the ledger under the lock, so it is asked rather than
   trusted to the writer: a `:design-decision` naming the design that
   `report/proceeds?`. The shared predicate rather than the recorded
   `:recommend`, because this is one more reader of the one clearance answer:
   testing the recommendation refused an advisory-only round that the clearance
   writer had accepted, leaving a design with nothing that could clear it. Not
   `standing` — it
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
                             ", which cannot be read")
                        {:design n :ws-id (:id w)}))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :Path]}
  append-lock-path
  "The lock that serialises appends to one workstream — `cstate/append-lock-path`."
  [project ws-id]
  (cstate/append-lock-path project ws-id))

(defn- locked
  "Answer `f` holding workstream `ws-id`'s append lock, every read under it asked of the core."
  [project ws-id f]
  (io/with-file-lock (append-lock-path project ws-id)
    (fn [] (binding [*under-lock* true] (f)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :int]}
  latest-seq
  "The seq of the newest entry on workstream `ws-id`'s ledger as the core holds it now, or 0."
  [project ws-id]
  (or (:seq (peek (vec (:entries (binding [*under-lock* true] (read-ws project ws-id)))))) 0))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  ledger-stamp
  "A value that moves whenever workstream `ws-id`'s ledger or identity does: its newest seq and
   its identity. For a cache keyed on what changed, so it is not itself a read of the record."
  [project ws-id]
  (when-let [w (record-of ws-id (core-state project ws-id))]
    [(:seq (peek (:entries w))) (hash (dissoc w :entries))]))

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
  "The index row for an entry, short of its :seq, which the core numbers it with: what `entry`
   carried, plus the ledger's own stamps, plus the design it names.

   `:under` MIRRORS the record's `:design :seq` onto the index, and is the one
   field here that comes out of the payload. It is a citation the author wrote,
   copied the way `:kind` already is — not a conclusion, which is what the index
   may never hold. It is mirrored because `reentry/trail-standing` reads the
   index and parses nothing, deliberately: that is what keeps the re-entry clamp
   affordable on a board computing a position per rendered row, and an
   attribution only readable by parsing every trail entry would have cost exactly
   that. Absent on rows written before the citation existed, where
   `reentry/generation` is still the answer."
  [entry payload ext]
  (let [gate?    (= :phase-gate (:kind entry))
        retract? (= :retraction (:kind entry))
        body     (when (or gate? retract? (contains? trail-kinds (:kind entry))) (edn/read-string payload))
        under    (get-in body [:design :seq])]
    (cond-> (assoc entry :at (clock/now-iso) :ext ext)
      under (assoc :under under)
      ;; A gate's phase is mirrored for the same reason: `record.phase/progress`
      ;; reads the index it is handed and parses nothing.
      gate? (assoc :opens (:opens body))
      (and gate? (:phase body)) (assoc :phase (:phase body))
      ;; And a retraction's target, which `record.generation` reads to tell a retraction that
      ;; belongs to no generation — of a baseline or an intent — from one that does.
      retract? (assoc :retracts (get-in body [:retracts :seq])))))

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

(defn- check-drain-closed-done!
  "A drain is closed done only on its observed exit: a :closed :done joins a drain's ledger only
   when an :exit-observed stands newer than every :closed and :reopened on it — which lane-drain's
   close appends just before it. Any other close of a drain to :done, a generic close among them,
   is refused (:exit-not-observed)."
  [w kind payload]
  (when (and (= :closed kind)
             (some #(= :drain (:kind %)) (:entries w))
             (= :done (:outcome (edn/read-string payload))))
    (let [newest (fn [ks] (or (:seq (last (filter #(ks (:kind %)) (:entries w)))) 0))]
      (when-not (> (newest #{:exit-observed}) (newest #{:closed :reopened}))
        (throw (ex-info (str (:id w) " is a drain: it is closed done only by its close, on its observed exit")
                        {:reason :exit-not-observed :ws-id (:id w)}))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :any] :any]}
  stands-landed?
  "Whether workstream `ws-id` of `project` stands landed: its ledger holds a :merged, or it is
   closed done. The one landed reading of a slice — the append's check, source-drain's `landed?`
   and how a slice's landing Run settles all read it."
  [project ws-id]
  (when-let [w (read-ws project ws-id)]
    (boolean (or (some #(= :merged (:kind %)) (:entries w))
                 (= :done (:outcome (closure w)))))))

(defn- slice-version-here
  "The recipe version slice `w` runs under: its newest :slice-rebased's, else its birth's."
  [w birth]
  (or (:recipe (some->> (last (filter #(= :slice-rebased (:kind %)) (:entries w))) :seq (read-entry-at w)))
      (:recipe birth)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  slice-review-passed?
  "Whether slice `w`'s NEWEST :review citing the recipe version it runs under (`{:recipe {:seq v}}`)
   passed — its status :clean or :converged. A newer review that ended unresolved withdraws an
   older pass; one that cites no version, a designless one included, judged it against nothing.
   The one reading of a slice's readiness: the pipeline's position, lane-drain's landing and every
   landing's record read it."
  [w]
  (when-let [birth (some->> (first (filter #(= :slice (:kind %)) (:entries w))) :seq (read-entry-at w))]
    (let [v (slice-version-here w birth)]
      (boolean (#{:clean :converged}
                (:status (last (filter #(= v (get-in % [:recipe :seq]))
                                       (keep #(read-entry-at w (:seq %))
                                             (filter #(= :review (:kind %)) (:entries w)))))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :any] :any]}
  slice-unlandable
  "Why slice `w` of `project` may not land yet, or nil when it may (nil for a workstream that is no
   slice): {:reason :rests-unlanded :rests-on [plan …]} while a plan its plan rests on (`:rests-on`,
   on its drain) was not started as a slice that stands landed, else {:reason :not-ready} until
   `slice-review-passed?` — each with :message. The one predicate of a slice being landable: the
   append of its landing, lane-drain's ask for one and the pre-push landing gate all read it."
  [project w]
  (when-let [birth (some->> (first (filter #(= :slice (:kind %)) (:entries w))) :seq (read-entry-at w))]
    (let [d        (some->> (:drain birth) (read-ws project))
          plan     (some->> (:plan birth) (read-entry-at d))
          started  (into {} (keep #(when (= :slice-started (:kind %))
                                     (let [e (read-entry-at d (:seq %))] [(:plan e) (:ws-id e)])))
                         (:entries d))
          unlanded (vec (remove #(some->> (started %) (stands-landed? project)) (:rests-on plan)))]
      (cond
        (seq unlanded)
        {:reason :rests-unlanded :rests-on unlanded
         :message (str (:id w) " rests on plans not yet landed: " (str/join ", " unlanded))}
        (not (slice-review-passed? w))
        {:reason :not-ready
         :message (str (:id w) " has not passed its newest review under recipe version "
                       (slice-version-here w birth) ", the version it runs under")}))))

(defn- check-slice-landing!
  "A drain's slice is landed only when `slice-unlandable` answers nil: a :merged, or a :closed
   :done, joins a slice's ledger only then — whichever path records the landing, `nido:land:record`
   and a generic close among them."
  [project w kind payload]
  (when (or (= :merged kind)
            (and (= :closed kind) (= :done (:outcome (edn/read-string payload)))))
    (when-let [why (slice-unlandable project w)]
      (throw (ex-info (:message why) (-> why (dissoc :message) (assoc :ws-id (:id w))))))))

(defn- check-qa-citations!
  "A QA plan cites the :design it plans, and a QA walk the :qa-plan it walks — a :product plan,
   with exactly one result for every step id of it. A plan with no product surface has no steps to
   walk, and a walk missing a step, or naming one twice or one the plan lacks, did not walk the
   plan it cites. Whether a walk's screenshots are kept is the writing verb's to check: their bytes
   are no ledger's."
  [w kind payload]
  (when (#{:qa-plan :qa-walk} kind)
    (let [r (edn/read-string payload)]
      (case kind
        :qa-plan (do (when-not (get-in r [:design :seq])
                       (throw (ex-info "A QA plan cites the design it plans, as {:design {:seq N}}" {})))
                     (cites! w r [:design :seq] #{:design} "QA plan :design"))
        :qa-walk (let [n    (get-in r [:plan :seq])
                       _    (cites! w r [:plan :seq] #{:qa-plan} "QA walk :plan")
                       plan (read-entry-at w n)]
                   (when-not plan
                     (throw (ex-info (str "QA walk cites plan " n ", which cannot be read") {:seq n})))
                   (when-not (= :product (:surface plan))
                     (throw (ex-info (str "QA walk cites plan " n ", whose surface is "
                                          (pr-str (:surface plan)) " — only a :product plan is walked")
                                     {:seq n :surface (:surface plan)})))
                   (let [want  (mapv :id (:steps plan))
                         got   (mapv :step (:results r))
                         twice (keep (fn [[s c]] (when (> c 1) s)) (frequencies got))
                         miss  (remove (set got) want)
                         extra (remove (set want) got)]
                     (when (or (seq twice) (seq miss) (seq extra))
                       (throw (ex-info (str "A QA walk gives exactly one result for every step of plan " n
                                            (when (seq miss) (str "; missing: " (str/join ", " miss)))
                                            (when (seq twice) (str "; given twice: " (str/join ", " twice)))
                                            (when (seq extra) (str "; not in the plan: " (str/join ", " extra))))
                                       {:seq n :missing (vec miss) :twice (vec twice) :extra (vec extra)})))))))))

(defn- check-append!
  "Every refusal an append makes of `payload` joining `w`, in the order `append-entry!` asks them.
   Shared by the two writes that give an entry its index row — a new entry, and one an interrupted
   append left on disk — so neither indexes an entry past a check the other would have made."
  [project w kind payload]
  (refuse-unguarded-clearance! kind)
  (refuse-design-between-phases! w kind)
  (check-baseline-citation! w kind payload)
  (check-standing-citations! w kind payload)
  (check-qa-citations! w kind payload)
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
  (check-stamped-citations! w kind payload)
  (check-drain-closed-done! w kind payload)
  (check-slice-landing! project w kind payload))

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

(defn- commit-entry!
  "Commit the entry `row` with `payload` to workstream `ws-id`, appended after the ledger `w` was
   read as, and answer the seq the core committed it at. The core refuses it as stale when anything was appended since
   `w` was read — which the append lock should make impossible — and that refusal is thrown, so
   no entry is ever numbered from a ledger it was not checked against."
  [project ws-id w row payload]
  (let [after (or (:seq (peek (vec (:entries w)))) 0)
        k     (client/ws-key project ws-id)
        o     (client/command! k (str "append/" k "/" (random-uuid))
                               {:command :append-entry :after after :row row :content payload})]
    (forget! project ws-id)
    (when (client/unavailable? o) (client/value! o :events))
    (if (contains? o :events)
      (:seq (:row (first (:events o))))
      (throw (ex-info (str "The core refused the " (name (:kind row)) " append on " ws-id ": "
                           (pr-str (or (:refused o) (:error o))))
                      {:ws-id ws-id :kind (:kind row) :outcome o})))))

(defn- append-locked!
  "The append, for a caller already holding the append lock over `w`: make every check, then
   commit the entry to the core after the ledger `w` was read as. Returns the entry's seq.
   An entry implies no change to the record beside its index row: whatever it decides, the ledger
   is where it is read from."
  [project ws-id w entry content]
  (let [[ext payload] (report/entry-payload (:kind entry) content)]
    (check-append! project w (:kind entry) payload)
    (commit-entry! project ws-id w (index-row entry payload ext) payload)))

(defn- record-status!
  "Append the status entry `entry-of` makes of the record, or nothing when it answers nil because
   there is nothing to record. Under the append lock, so the decision and its entry are one step:
   two closes, or a close and a reopen, cannot both decide from the same reading."
  [project ws-id entry-of]
  (locked project ws-id
    (fn []
      (let [w (or (read-ws project ws-id)
                  (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
        (if-let [r (entry-of w)]
          (do (append-locked! project ws-id w {:kind (:format r)} (pr-str r))
              (read-ws project ws-id))
          w)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map :string] :int]}
  append-entry!
  "Append an immutable entry to the workstream's ledger in the core, after the checks every
   append runs. `entry` = {:kind <kw> :session <str>? :amended-by <run-id>?}, kept as the entry's
   index row; :amended-by is for a review loop's amender alone (`amended-seqs`). Returns the
   seq the core committed the entry at — the one answer to which entry this append wrote, since
   the ledger's newest entry, reread once the lock is released, may be another writer's.

   The core numbers the entry, one past the seq this append read, and refuses it when the
   ledger has moved since — so two authors can never both be given the same :seq, the identity
   every citation in the ledger is keyed on. The checks read the ledger and other workstreams,
   so the read and the checks run under the workstream's lock, which keeps nido's own writers
   from deciding against a ledger that another of them is about to move.

   A :design-cleared is refused here: it records a reading of the ledger at a
   position, so it is written only through `append-entry-at!`."
  [project ws-id entry content]
  (when (= :design-cleared (:kind entry))
    (throw (ex-info "A :design-cleared is written at a position — through append-entry-at!"
                    {:kind (:kind entry) :ws-id ws-id})))
  (refuse-unguarded-gate! (:kind entry))
  (locked project ws-id
    (fn []
      (append-locked! project ws-id
                      (or (read-ws project ws-id)
                          (throw (ex-info "Workstream not found"
                                          {:project project :ws-id ws-id})))
                      entry content))))

(defn- refuse-judging-a-cleared-design!
  "No decision joins a design something already let be built — a round's :design-cleared or a
   grant an older ledger holds, naming it in the current generation. Its review is over: what
   is built on it is judged by the diff review, and a design to be revisited is a superseding
   design, which a round of its own decides. Judged again, it could be sent back while the arc
   builds on it."
  [w design-seq]
  (when-let [e (some (fn [kind]
                       (some #(when (= design-seq (get-in (read-entry-at w (:seq %)) [:design :seq])) %)
                             (span-of w kind)))
                     [:design-cleared :design-approved])]
    (throw (ex-info (str "The design at entry " design-seq " was let be built at entry " (:seq e)
                         " — write a superseding design to have it decided again")
                    {:ws-id (:id w) :design design-seq :cleared-by (:seq e)}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string] :int]}
  append-decision!
  "Append `content`, a `:design-decision`, as every other append's checks allow. The one writer of
   the kind, which a decision round alone calls: every generic append refuses it
   (`refuse-unguarded-gate!`). Returns the seq the core committed it at."
  [project ws-id content]
  (let [decision (edn/read-string content)]
    (when-not (= :design-decision (:format decision))
      (throw (ex-info "append-decision! takes a :design-decision" {:ws-id ws-id})))
    (locked project ws-id
      (fn []
        (let [w (or (read-ws project ws-id)
                    (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
          (refuse-judging-a-cleared-design! w (:design-seq decision))
          (append-locked! project ws-id w {:kind :design-decision} content))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :int :map :string] [:or :int :map]]}
  append-entry-at!
  "Append only if `expected-seq` is still the ledger's latest entry. Returns the
   seq the core committed it at on success, or {:refused :stale :latest <seq>} without writing.

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
  (locked project ws-id
    (fn []
      (let [w      (or (read-ws project ws-id)
                       (throw (ex-info "Workstream not found"
                                       {:project project :ws-id ws-id})))
            latest (count (:entries w))]
        (if (or (nil? expected-seq) (not= expected-seq latest))
          {:refused :stale :latest latest}
          ;; Re-entering append-entry! would deadlock on the lock this holds, so
          ;; the checks are made inline before the same commit.
          (let [[ext payload] (report/entry-payload (:kind entry) content)
                _     (refuse-design-between-phases! w (:kind entry))
                _     (check-baseline-citation! w (:kind entry) payload)
                _     (check-standing-citations! w (:kind entry) payload)
                _     (check-qa-citations! w (:kind entry) payload)
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
                row   (index-row entry payload ext)]
            (commit-entry! project ws-id w row payload)))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map :string :any] :map]}
  append-entry-once!
  "Append an entry of `entry`'s kind only when no entry of that kind is already the one `same?`
   identifies. Returns `{:appended seq}` for a new entry, or `{:existing seq}` when one already
   matches, so a retry never writes a second copy.

   `same?` is the caller's identity for the record: a predicate over an entry parsed through the
   read contract, so only a kind whose entries are EDN can ever match. Asked under the append
   lock."
  [project ws-id entry content same?]
  (refuse-unguarded-gate! (:kind entry))
  (locked project ws-id
    (fn []
      (let [w       (or (read-ws project ws-id)
                        (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
            kind    (:kind entry)
            match?  (fn [row]
                      (try (boolean (same? (report/parse-event kind (edn/read-string (entry-text w (:seq row))))))
                           (catch Throwable _ false)))
            indexed (->> (:entries w) (filter #(= kind (:kind %))) (filter match?) first)]
        (if indexed
          {:existing (:seq indexed)}
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
  (locked project ws-id
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
  (locked project ws-id
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:or :map [:=> [:cat :Workstream] :map]]] :int]}
  append-stamped!
  "Append a `:tension`, `:accord`, `:withdrawal`, `:recut` or `:conduct-decision` whose `:by` the caller stamped from
   where it runs. The ledger cannot tell where that was; the one caller is the verb that can, and
   the generic appends refuse these kinds so nothing else takes a writer on its word — `appending!`
   alone takes them on the same terms. Every other check an append makes holds. `payload` is the entry, or a function of the workstream read under
   the append lock answering it — so a writer stamped from that read is stamped against the same
   generation the entry joins, which no generation can open between. Returns the seq the core committed it at."
  [project ws-id payload]
  (locked project ws-id
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:=> [:cat :map] :any]] :any]}
  appending!
  "Answer `f` of `{:w :check! :append!}` under the workstream's append lock — `w` the workstream
   read under it; `(check! entry content)` throwing exactly what appending it now would throw and
   writing nothing; `(append! entry content)` appending it against the ledger as the last append
   left it, as `append-entry!` does — or, for a stamped kind, as `append-stamped!` does, its writer
   stamped by the caller — answering the absolute path. So the caller checks every entry it means
   to write before it writes any, writes several, and acts on each once it is taken, as one step
   that no other append, no generation's opening and no design approval falls between. Neither
   takes the lock again: `f` must append through `append!` alone."
  [project ws-id f]
  (locked project ws-id
    (fn []
      (let [read   #(or (read-ws project ws-id)
                        (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
            guard! (fn [{:keys [kind]}]
                     (when (= :design-cleared kind)
                       (throw (ex-info "A :design-cleared is written at a position — through append-entry-at!"
                                       {:kind kind :ws-id ws-id})))
                     (when-not (contains? stamped-kinds kind) (refuse-unguarded-gate! kind)))]
        (f {:w       (read)
            :check!  (fn [entry content]
                       (guard! entry)
                       (let [[_ payload] (report/entry-payload (:kind entry) content)]
                         (check-append! project (read) (:kind entry) payload)
                         nil))
            :append! (fn [entry content]
                       (guard! entry)
                       (append-locked! project ws-id (read) entry content))})))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:=> [:cat :map] :any]] :any]}
  appending-drain!
  "Answer `f` of `{:w :append!}` under the workstream's append lock, as `appending!` does, for a
   drain's records: `(append! payload)` appends one entry whose kind is a drain's, against the
   ledger as the last append left it, every check an append makes holding, and answers its seq.
   The one way a drain kind is written — called by `nido.coordinator.record.drain` alone, which
   checks what its kinds cite before it appends, under this same lock."
  [project ws-id f]
  (locked project ws-id
    (fn []
      (let [read #(or (read-ws project ws-id)
                      (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
        (f {:w       (read)
            :append! (fn [payload]
                       (let [kind (:format payload)]
                         (when-not (contains? report/drain-kinds kind)
                           (throw (ex-info (str "appending-drain! takes a drain's kinds, not " kind)
                                           {:kind kind})))
                         (let [w (read)]
                           (append-locked! project ws-id w {:kind kind} (pr-str payload))
                           (:seq (last (:entries (read)))))))})))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:=> [:cat :Workstream] :any]] :any]}
  holding-ledger
  "Answer `f` of the workstream read under its append lock — the lock every append, and a
   generation's opening, holds — so what `f` decides from that read stays true until it returns.
   `f` must not append: the lock is not reentrant."
  [project ws-id f]
  (locked project ws-id
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
      (read-entry-at w (:seq e)))))

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
   parse, or hold no content, are dropped, on the same degrade-to-nil contract
   latest-entry has — so a reader counting them against the index sees each as missing.

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


(def ^:private grant-spent-by
  "The ledger kind whose append spends each grantable act (report/grantable-kinds): :intent is
   spent by the goal amendment the seat appends; :merge by the landing's :merged — whoever
   records it, the merge poller included — so one grant is one landing, however many times a
   seat must enqueue before a queue takes it."
  {:intent :intent
   :merge  :merged})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:sequential [:map [:kind :keyword] [:seq :int]]]]}
  unspent-grants
  "The grants a person gave the sessions of workstream `ws-id` that are still unspent, oldest
   first, each as its act (:kind) and the :seq of the entry that gave it: the :grants of its
   :blocker-answered and :grant entries that are the person's own acts — not an answer nido
   recorded for an act (:act), an entry nido wrote (:by :nido), or a grant a design round carried
   forward (:carried) — each unspent until an entry of its act's spending kind follows it. No
   text is read for a grant. The one reading of a grant's spending: the conductor's refusal and
   instruction and the pipeline's position all read it here."
  [project ws-id]
  (->> (for [a (mapcat #(entries-of project ws-id %) [:blocker-answered :grant])
             :when (and (not (:act a)) (not= :nido (:by a)) (not (:carried a)))
             k (:grants a)
             :let [spent-by (grant-spent-by k)]
             :when (and spent-by
                        (not-any? #(> (:seq %) (:seq a)) (entries-of project ws-id spent-by)))]
         {:kind k :seq (:seq a)})
       (sort-by :seq)
       vec))

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
  "The ids of every workstream of `project` the core holds, in id order; [] if none. Reads them
   all in one request, and each read answers `read-ws` for a moment after (`sweep-ms`), so a
   sweep over the ids does not ask the core once per workstream."
  [project]
  (let [prefix (client/ws-key project "")
        sts    (client/value! (client/states prefix) :states)
        now    (System/currentTimeMillis)
        root   (str (core/nido-root))]
    (swap! swept into (for [[k st] sts] [[root k] {:state st :at now}]))
    (->> sts
         (keep (fn [[k st]] (when (record-of k st) (subs k (count prefix)))))
         sort
         vec)))

(defn- read-project
  "Every workstream of `project` the core holds, read in one request (`client/project-states`)
   with the content of each one's newest closure entry: [[ws-id state] …] in id order, the
   discarded left out. Each state is kept as `list-ids` keeps it and each closure entry primed
   (`entry/prime!`), so neither `read-ws` nor `closure` asks the core again for them. `opts` may
   ask for the open ones only (`:open? true`, closed and discarded as the core's summary says)."
  [project opts]
  (let [reply  (client/project-states project (assoc (select-keys opts [:open?]) :newest-of closure-kinds))
        sts    (client/value! reply :states)
        prefix (client/ws-key project "")
        now    (System/currentTimeMillis)
        root   (str (core/nido-root))
        pairs  (into [] (keep (fn [[k st]] (when (record-of k st) [(subs k (count prefix)) st])))
                     (sort-by key sts))]
    (swap! swept into (for [[k st] sts] [[root k] {:state st :at now}]))
    (doseq [[k contents] (:contents reply)
            :let [id (subs k (count prefix))]]
      (entry/prime! {:project project :id id} contents))
    pairs))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:? :map]] [:vector :Workstream]]}
  project-records
  "Every workstream record of `project` the core holds — what `read-ws` answers for each — in id
   order, read in one request. `{:open? true}` keeps those the core's summary says are neither
   closed nor discarded. Each read answers `read-ws` for a moment after (`sweep-ms`), and every
   `closure` read is fetched with it; a pass longer than that runs in `over-project`."
  ([project] (project-records project {}))
  ([project opts]
   (if-let [ids (when-not (:open? opts)
                  (some-> *pass* deref (get-in [:read [(str (core/nido-root)) project]])))]
     (let [sts (:states @*pass*)]
       (vec (keep #(let [k (sweep-key project %)] (when (contains? sts k) (record-of % (get sts k))))
                  ids)))
     (mapv (fn [[id st]] (record-of id st)) (read-project project opts)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName fn?] :any]}
  over-project
  "Call `(f records)` with every workstream record of `project` read in one request
   (`project-records`), and answer what it answers. Every `read-ws` of one of them in `f`'s extent
   — however deep, and on any thread conveying the binding — answers from that one read, outside
   the append lock, which reads the core as ever; this process's own writes are read back. Nested,
   a project already read is not read again."
  [project f]
  (let [root (str (core/nido-root))
        take! (fn [p]
                (let [pairs  (read-project project {})
                      prefix (client/ws-key project "")]
                  (swap! p (fn [m] (-> m
                                       (assoc-in [:read [root project]] (mapv first pairs))
                                       (update :states into (for [[id st] pairs]
                                                              [[root (str prefix id)] st])))))))]
    (if-let [p *pass*]
      (do (when-not (get-in @p [:read [root project]]) (take! p))
          (f (project-records project)))
      (let [p (atom {:read {} :states {}})]
        (binding [*pass* p]
          (take! p)
          (f (project-records project)))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector [:tuple :WorkstreamId :any]]]}
  ledger-stamps
  "[[ws-id stamp] …] in id order for every workstream of `project` the core holds, read off its
   summaries in one request: a value that moves whenever its ledger, identity or coordination
   does — its newest seq, and the rest of its summary. For a cache keyed on what changed over a
   whole project."
  [project]
  (let [prefix (client/ws-key project "")]
    (->> (client/value! (client/summaries project) :summaries)
         (remove (fn [[_ sm]] (:discarded? sm)))
         (sort-by key)
         (mapv (fn [[k sm]] [(subs k (count prefix)) [(:last-seq sm) (hash (dissoc sm :last-seq :last-at))]])))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  pass-state
  "The committed state of workstream `ws-id` the enclosing `over-project` read, or nil outside
   one or for a workstream it did not read — for a reader of the core's coordination of many
   workstreams that would otherwise read every state again."
  [project ws-id]
  (some-> *pass* deref :states (get (sweep-key project ws-id))))

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
       (let [w' (revise! project ws-id {:external-refs (conj (vec (:external-refs w)) ref)} "add a ref")]
         (when (= :github (:adapter ref))
           (record-pr-opened! project w' ref opts))
         w')))))

(def ^:private legacy-ledger-files
  "The names under a workstream's directory that hold its legacy ledger, which nothing writes."
  #{"workstream.edn" "entries" "entries.meta"})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  delete!
  "Discard a workstream — an orphan minted by a failed spawn — so no reader finds it again, and
   remove what its directory holds beside the legacy ledger files (its sessions and Runs).
   Idempotent — no-op if absent. The core keeps what was recorded of it. An open unit or a turn
   still running on it stops the discard — a merged or retracted unit does not: the
   core refuses, the refusal is thrown, and nothing is removed. The legacy files — workstream.edn, entries/ and entries.meta/
   — are the import's source and are never touched."
  [project ws-id]
  (when (read-ws project ws-id)
    (create-command! project ws-id (str "discard/" (client/ws-key project ws-id))
                     {:command :discard-workstream}))
  (let [d (cstate/workstream-dir project ws-id)]
    (when (fs/exists? d)
      (doseq [f (fs/list-dir d)
              :when (not (legacy-ledger-files (str (fs/file-name f))))]
        (fs/delete-tree f))
      (when (empty? (fs/list-dir d)) (fs/delete d)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :keyword :string] [:maybe :Workstream]]}
  find-by-ref
  "Scan a project's workstreams for one carrying an external ref matching
   (adapter, external-id). Returns the workstream record or nil."
  [project adapter external-id]
  (->> (project-records project)
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
  (->> (project-records project)
       (some (fn [w] (when (some #(= external-id (:id %)) (:external-refs w)) w)))))

(defn ^{:malli/schema [:=> [:cat :any :string] [:maybe :map]]}
  own-pr
  "The :github ref of workstream `w` naming pull request `pr`, or nil: what nido knows as the
   workstream's own PR. `pr` is `owner/repo#n`, compared without regard to case as GitHub compares
   slugs, or a bare `n`, which names a PR only when exactly one of the refs has that number — a
   number two repositories share names neither."
  [w pr]
  (let [refs (filter #(= :github (:adapter %)) (:external-refs w))
        pr   (str/trim (str pr))]
    (if (re-matches #"\d+" pr)
      (let [hits (filter #(= pr (second (re-find #"#(\d+)$" (str (:id %))))) refs)]
        (when (= 1 (count hits)) (first hits)))
      (some #(when (= (str/lower-case pr) (str/lower-case (str (:id %)))) %) refs))))

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
                                 :id external-id}]
                :activated?    true})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :map :string] [:maybe :int]]}
  append-to-ref!
  "Append a ledger entry to the workstream carrying `external-id`, found or minted by
   `find-or-mint-by-ref!`. Returns the seq the core committed it at."
  [project external-id entry content]
  (append-entry! project (:id (find-or-mint-by-ref! project external-id)) entry content))

