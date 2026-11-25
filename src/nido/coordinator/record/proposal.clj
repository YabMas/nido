;; src/nido/coordinator/record/proposal.clj
(ns nido.coordinator.record.proposal
  "A proposal, and what has been decided about it and done with it.

   Lives with the records rather than in the vocabulary over them because two
   very differently-placed readers need it. The operations surface reads it
   through `nido.coordinator.work`, which is the facade a surface is meant to
   wrap; the improvement source reads it to decide whether there is anything to
   fire, and a Source may not reach WorkPlane — that edge is not in
   `canvas/bands.clj` and is not one to add, since it would let anything that
   polls for work reach every verb the work plane has.

   Neither is a good reason to derive a proposal twice. What a proposal IS is a
   fact about the ledger's own entries, which is this band's subject; the two
   readers above it disagree about what to DO with one, not about what one is.

   Where the records are. An analysis and a plan are what a run did: records on that run
   (`nido.coordinator.record.records`), the run's id their holder. A decision, a landing and a
   claim's reservation outlive the run they concern, so they are kept in the project's
   improvement journal, each naming its `:holder`, and a claim run's end is a `:closed` record on
   that run. Records from before runs held them stay where they were written — on a workstream
   the fire minted, the workstream their holder — and are read beside the new ones: an address is
   `<holder>/<seq>.<obs>` either way, and the latest record about it wins wherever it is kept.

   Nothing here writes. The verbs that record a decision or a landing stay in
   the work vocabulary, where the surface reaches them."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.set]
   [nido.coordinator.record.records :as records]
   [nido.coordinator.record.workstream :as cws]
   [nido.coordinator.report :as report]))

(def ^{:doc "The name of a project's improvement journal: decisions, landings, reservations."}
  journal-name "improvement")

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  journal-of
  "The records of `project`'s improvement journal, in append order."
  [project]
  (records/of (records/journal project journal-name)))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  first-heading
  "The text of the first markdown heading in `md` (e.g. '# Verdict' -> \"Verdict\"),
   or nil."
  [md]
  (some->> md
           str/split-lines
           (some #(second (re-matches #"#+\s+(.*)" %)))))

(defn ^{:malli/schema [:=> [:cat :any :map] :map]}
  entry->report
  "Render the entry `entry` (an index row) of the workstream record `ledger` as a
   `:format`-tagged gate report. An entry written as EDN is a typed event — read + validated against the schema for its `:kind`
   (report/parse-event — the read contract, which accepts any shape that was
   writable when the entry was written), :seq/:at stamped from the entry (the same
   stamp cws/latest-entry applies); any other entry is markdown
   (:format :markdown). A typed EDN entry that fails to read/validate degrades to a
   :markdown payload of its raw text rather than blanking the pane.

   :seq is load-bearing, not decoration: it is the ledger position a rendered
   gate binds its buttons to, so a click can be checked against the report that
   drew it (option-actions -> choose-option!)."
  [ledger entry]
  (let [text (when ledger (cws/entry-text ledger (:seq entry)))
        edn? (= "edn" (:ext entry))]
    (or (when edn?
          (try (-> (report/parse-event (:kind entry) (edn/read-string text))
                   (assoc :seq (:seq entry) :at (:at entry)))
               (catch Throwable _ nil)))
        (let [md text]
          (cond-> {:format   :markdown
                   :kind     (:kind entry)
                   :seq      (:seq entry)
                   :at       (:at entry)
                   :title    (first-heading md)
                   :markdown md}
            ;; A typed entry that would not read is NOT freeform markdown, and
            ;; rendering it as if it were is the same conflation this codebase
            ;; keeps having to undo: the reader cannot tell a corrupt record from
            ;; one it is simply too old to understand. Unmerged stacks writing
            ;; kinds the running daemon has never heard of is a NORMAL condition
            ;; here — the daemon reads src/ once at startup — so the honest answer
            ;; is the common one, and it says which of the two it is.
            edn? (assoc :degraded
                        {:kind   (:kind entry)
                         :reason (if (contains? report/event-schemas (:kind entry))
                                   :schema-mismatch
                                   :unknown-kind)}))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  active-ledger
  "The workstream's own ledger — the single event store. {:ledger <the record|nil>
   :entries <vector>}, oldest-first; `entry->report` reads an entry's content through :ledger."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    {:ledger w :entries (vec (:entries w))}
    {:ledger nil :entries []}))

(defn- analysis-entries
  "Oldest-first [seq entry-map] for every :review-analysis on this workstream,
   parsed. Reads through the same entry->report every other reader uses, so a
   record too old or too new for this reader degrades the same way it does
   everywhere else rather than blanking the proposal list."
  [project ws-id]
  (let [{:keys [ledger entries]} (active-ledger project ws-id)]
    (->> entries
         (filter #(= :review-analysis (:kind %)))
         (keep (fn [e]
                 (let [r (entry->report ledger e)]
                   (when (= :review-analysis (:format r)) r)))))))

(defn- ws-records
  "Oldest-first parsed entries of `kind` on workstream record `w`."
  [w kind]
  (->> (:entries w)
       (filter #(= kind (:kind %)))
       (keep (fn [e] (let [r (entry->report w e)] (when (= kind (:format r)) r))))))

(defn- latest-by-address
  "{[holder analysis-seq observation] -> record} of `kind`, latest wins: those on the legacy
   workstreams `ws` (their holder the workstream), then the journal's, which are newer.

   Latest because a decision is an append and nothing deletes one: changing your mind is a second
   record, and the one that counts is the one made last. A landing likewise — a proposal can land
   twice, carried in one change and corrected in another, and the row points at the one a reader
   should go read."
  [ws journal kind]
  (as-> {} m
    (reduce (fn [m w] (reduce #(assoc %1 [(:id w) (:analysis-seq %2) (:observation %2)] %2)
                              m (ws-records w kind)))
            m ws)
    (reduce (fn [m r] (if (and (= kind (:kind r)) (:holder r))
                        (assoc m [(:holder r) (:analysis-seq r) (:observation r)] r)
                        m))
            m journal)))

(defn- legacy-holders
  "The workstreams a review analysis was minted on before runs held analyses."
  [pk]
  (filterv (fn [w] (some #(= :review-run (:adapter %)) (:external-refs w)))
           (cws/project-records pk)))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:vector :map]] [:set :string]]}
  declined
  "The address of every proposal of `project` whose latest decision is a decline, as of the
   improvement journal's records `journal` — what a reservation decides from, so the records it
   read are the ones it is ordered after."
  [project journal]
  (let [pk (keyword (name project))]
    (into #{}
          (keep (fn [[[holder s o] d]] (when (= :declined (:verdict d)) (str holder "/" s "." o))))
          (latest-by-address (legacy-holders pk) journal :improvement-decision))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  of-project
  "Every proposal this project's review-loop analyses have made, newest analysis
   first, each with whatever was decided about it.

   A proposal is not a stored thing: it is an observation carrying a :proposal,
   addressed by the entry that contains it and its index in that entry. So this
   derives rather than reads — nothing writes a proposal row, and an analysis
   filed before any of this existed produces rows exactly like one filed today.

   Observations WITHOUT a :proposal are dropped. They are real analysis and
   worth reading, but this surface exists to be decided on and there is nothing
   to decide about an observation that proposes nothing; they stay legible in
   the analysis itself.

   `:ws-id` is the proposal's holder — the run whose record the analysis is, or the workstream an
   analysis from before runs was written on — and with `:analysis-seq` and `:observation` its
   address.

   `:at-seq` is the position a decision about this row must carry — how many records the
   improvement journal held when the row was built, NOT the analysis's own seq. A decision answers
   the decisions as they stand, and these differ the moment anyone else decides.

   `:decision` is what a human ruled and `:landed` is what became of it, and the
   two are separate because approving does not carry anything out. A row with a
   decision and no landing is a commitment nobody has discharged — the state
   this surface used to render identically to a finished one."
  [project]
  (let [pk      (keyword (name project))
        legacy  (legacy-holders pk)
        journal (journal-of pk)
        decided (latest-by-address legacy journal :improvement-decision)
        landed  (latest-by-address legacy journal :improvement-landed)
        at-seq  (count journal)
        held    (concat
                 (for [w legacy
                       :let [ref (some #(when (= :review-run (:adapter %)) %) (:external-refs w))]
                       a (analysis-entries pk (:id w))]
                   {:holder (:id w) :a a :title (:title ref)})
                 (for [r (records/runs pk)
                       a (filter #(= :review-analysis (:kind %)) (:records r))]
                   {:holder (:id r) :a (assoc a :at (get-in r [:run :fired-at]))
                    :title (get-in r [:run :payload :title])}))]
    (->> held
         (mapcat (fn [{:keys [holder a title]}]
                   (for [[i o] (map-indexed vector (:observations a))
                         :when (:proposal o)]
                     {:project      (name pk)
                      :ws-id        holder
                      :analysis-seq (:seq a)
                      :observation  i
                      :at-seq       at-seq
                      :kind         (:kind o)
                      :where        (:where o)
                      :summary      (:summary o)
                      :evidence     (:evidence o)
                      :proposal     (:proposal o)
                      :verdict      (:verdict a)
                      :run-id       (:run-id a)
                      :reviewed     (:reviewed a)
                      :status       (:status a)
                      :rounds       (:rounds a)
                      :at           (:at a)
                      :title        title
                      :decision     (get decided [holder (:seq a) i])
                      :landed       (get landed [holder (:seq a) i])})))
         (sort-by :at)
         reverse
         vec)))


;; ── What the improvement source asks ────────────────────────────────────────

(def improvement-adapter
  "The external-ref adapter an improvement workstream carries.

   It is what makes 'is one already running' answerable without a registry: the
   workstreams ARE the record, `spawn/ensure-workstream!` dedups on the ref, so
   a re-emitted event lands back in the workstream that already holds the
   attempt rather than starting a second one."
  :improvement)

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  address
  "The improvement ref id for one proposal: `<ws-id>/<seq>.<observation>`.

   The proposal's own address, spelled for a ref field. Nothing is minted — the
   triple is stable because ledger entries are immutable, which is the same
   property the operations surface addresses a decision by."
  [{:keys [ws-id analysis-seq observation]}]
  (str ws-id "/" analysis-seq "." observation))

(defn- run-outcome
  "How an improvement run `r` ended, as a close's outcome: what its own `:closed` record says —
   :done once discharged, :vetoed when a decline stopped it, :dropped when it gave up — else, for a
   run over without one, :dropped when its turn completed (it stopped saying nothing, which is
   giving up) and :orphaned when it failed or was cancelled (the run died, not the work). Nil while
   it runs."
  [r]
  (or (some #(when (= :closed (:kind %)) (:outcome %)) (rseq (:records r)))
      (case (:status r) :done :dropped (:failed :cancelled) :orphaned nil)))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  attempts
  "Every improvement attempt in `project` — a run a fire of the improvement sweep started, or a
   workstream one minted before runs — as `{:address :ws-id :open? :outcome}`, `:ws-id` its holder;
   a run's also `:run? true`, its fire's `:title` and `:started-at`.

   `:open?` is a run not yet over, or a workstream with no close: NOT the presence of a failure
   record. That distinction is the whole hold: a session that crashes hard writes no tombstone, so
   a rule keyed on failure would read a dead attempt as no attempt and start a second one on top of
   the first. A run is over once its turn ended, which the core records whatever the agent wrote."
  [project]
  (let [pk (keyword (name project))]
    (into
     (->> (cws/project-records pk)
          (keep (fn [{ws-id :id :as w}]
                  (when-let [ref (some #(when (= improvement-adapter (:adapter %)) %)
                                       (:external-refs w))]
                    (let [closed (cws/closure w)]
                      {:address (:id ref) :ws-id ws-id :open? (nil? closed)
                       :outcome (:outcome closed)}))))
          vec)
     (for [r (records/runs pk)
           :let [p (get-in r [:run :payload])]
           :when (= improvement-adapter (keyword (:adapter p)))]
       {:address (:id p) :ws-id (:id r) :open? (not (:closed? r)) :outcome (run-outcome r)
        :run? true :title (:title p) :started-at (get-in r [:run :fired-at])}))))

(defn ^{:malli/schema [:=> [:cat [:vector :map] [:vector :map]] [:maybe :map]]}
  next-to-implement
  "The one proposal an improvement session should be started for, or nil.

   Pure — `proposals` and `attempts` are read for it — because what it decides
   is a scheduling rule, and a scheduling rule that can only be exercised
   against a live coordinator is one nobody checks.

   Three things make it nil, and only the first is about the work:

   Nothing is owed. A proposal is owed when it is approved and no landing has
   been recorded against it; a decline, or an approval already discharged, owes
   nothing.

   An attempt is already open. This is the one-at-a-time rule, and it lives HERE
   rather than in the trigger's `:max-in-flight`, which cannot express it:
   it counts runs in progress or parked for review (runs/in-progress-count-by-trigger), so a
   run that fails or is halted releases the slot and the next poll starts another
   improvement on top of a branch the last one abandoned. An open workstream is
   released by a close and by nothing else.

   This proposal has been attempted before. A closed attempt that recorded no
   landing is a session that gave up, and re-firing it would spend a budget
   every poll for as long as the ledger stands. It stays visible on the
   operations board as approved-and-not-implemented, which is true, and a human
   re-fires it by hand.

   Oldest first, by the analysis that raised it. A backlog drained newest-first
   would let a busy week starve a proposal indefinitely."
  [proposals attempts]
  (let [tried (into #{} (map :address) attempts)]
    (when-not (some :open? attempts)
      (->> proposals
           (filter (fn [{:keys [decision landed]}]
                     (and (= :approved (:verdict decision)) (nil? landed))))
           (remove #(contains? tried (address %)))
           (sort-by (juxt :at :analysis-seq :observation))
           first))))


;; ── What the sweep asks ─────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :string :int :int] :string]}
  claim-address
  "The improvement ref id for one claim: `<ws-id>/<plan-seq>#<index>`.

   Deliberately the same shape as `address`, with `#` where a proposal has `.`,
   because the two are the same KIND of thing — a position in an immutable
   ledger entry, minted by nobody — and a reader seeing one should be able to
   read the other. The separator differs so the two cannot be confused by a
   string match, which is how `tried` sets and ref lookups compare them."
  [ws-id plan-seq claim-index]
  (str ws-id "/" plan-seq "#" claim-index))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] :map]}
  dispositions-by-address
  "{address -> disposition} across `plans`, latest plan wins.

   Latest for the same reason a decision is: a plan is an append and the ledger
   has no delete, so a later plan reconsidering an address is the one that
   counts. Re-planning is ordinary — a claim refused at reservation returns its
   survivors to the owed set, and tomorrow groups them differently.

   Public because two readers ask different questions of the same join. `owed`
   reads it to drop what a plan has settled; the operations surface reads it to
   say WHY a row is settled, which is the half a proposal's own record cannot
   carry — `:file` and `:no-op` write no landing precisely because nothing
   landed, so a disposed row is indistinguishable from an untouched one unless
   the plan is read beside it."
  [plans]
  (reduce (fn [m plan]
            (reduce (fn [m {:keys [disposition addresses]}]
                      (reduce #(assoc %1 %2 disposition) m addresses))
                    m
                    (:claims plan)))
          {}
          plans))

(def ^:private returned-outcomes
  "The claim closes that return a claim's addresses to the owed set rather than
   counting them as tried: a veto (a decline stopped it) and an orphan (a daemon
   restart killed its Run). Neither is a session giving up on the work."
  #{:vetoed :orphaned})

(defn ^{:malli/schema [:=> [:cat [:vector :map] [:vector :map] [:vector :map]] [:vector :map]]}
  owed
  "The proposals a plan may still claim, oldest analysis first.

   Pure, and over three inputs rather than one, because owedness is a join and
   not a property: a proposal is owed when nothing has settled it, and three
   different kinds of record can settle one.

   A DECISION settles it only by declining. This is the change that replaces
   per-proposal approval with a veto: an approval is no longer required, so an
   undecided proposal is owed exactly as an approved one is, and a decline is
   the only verdict that acts.

   A LANDING settles it, which is unchanged.

   A PLAN settles it by dispositioning it `:file` or `:no-op` — nothing landed,
   and nothing is going to. Those two record no landing precisely because
   nothing landed, so without reading the plans they would be owed forever.

   An ATTEMPT settles it by having been tried: an address covered by a claim
   whose workstream closed carrying no landing is one a session gave up on, and
   re-planning it would spend a budget every day for as long as the ledger
   stands. The exception is a claim closed as VETOED, which is not a session
   giving up — it is the veto working — and its addresses that carry no decline
   of their own were never the reason it stopped. They return to the owed set
   and are grouped again tomorrow. A claim closed as ORPHANED (a restart killed
   its Run) returns its addresses the same way."
  [proposals plans attempts]
  (let [disposed (dispositions-by-address plans)
        tried    (into #{}
                       (comp (remove :open?)
                             (remove #(returned-outcomes (:outcome %)))
                             (mapcat :addresses))
                       attempts)]
    (->> proposals
         (remove #(= :declined (get-in % [:decision :verdict])))
         (remove :landed)
         (remove #(#{:file :no-op} (get disposed (address %))))
         (remove #(contains? tried (address %)))
         (sort-by (juxt :at :analysis-seq :observation))
         vec)))

(defn ^{:malli/schema [:=> [:cat [:vector :map] [:vector :map]] [:maybe :map]]}
  partition-defect
  "Why `claims` is not a partition of `owed`, or nil when it is one.

   Returns what is wrong rather than a boolean, because the only caller is a
   verb that refuses, and a refusal a writer cannot act on costs the same round
   trip as no check at all.

   Two ways to fail and they are different mistakes. `:uncovered` is a proposal
   the plan did not mention — the sweep would silently never carry it. `:unowed`
   is a claim naming an address that is not owed, which means the plan was
   derived against a state that has since moved, or against no state at all.

   Double-booking is NOT checked here: an address in two claims is refused by
   the plan's own schema, which is where a property of the record alone belongs."
  [claims owed]
  (let [want (into #{} (map address) owed)
        got  (into #{} (mapcat :addresses) claims)
        miss (clojure.set/difference want got)
        extra (clojure.set/difference got want)]
    (when (or (seq miss) (seq extra))
      (cond-> {}
        (seq miss)  (assoc :uncovered (vec (sort miss)))
        (seq extra) (assoc :unowed (vec (sort extra)))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  plans-of
  "Every `:improvement-plan` of this project — a plan run's record, or an entry on a ledger from
   before runs — oldest first, each with its holder under `:ws-id` and the `:seq` it was appended
   at.

   Scans every workstream rather than a known planning one, for the reason
   `of-project` scans for analyses: which workstream holds a record is a fact
   about how it arrived, and a reader that has to know it in advance breaks the
   first time a plan is appended from somewhere else — by hand, by a migration,
   by a second project. The ordering is what makes `dispositions-by-address`
   latest-wins meaningful."
  [project]
  (let [pk (keyword (name project))]
    (->> (cws/project-records pk)
         (mapcat (fn [{ws-id :id :as ledger}]
                   (let [entries (:entries ledger)]
                     (->> entries
                          (filter #(= :improvement-plan (:kind %)))
                          (keep (fn [e]
                                  (let [r (entry->report ledger e)]
                                    (when (= :improvement-plan (:format r))
                                      (assoc r :ws-id ws-id)))))))))
         (into (for [r (records/runs pk)
                     plan (filter #(= :improvement-plan (:kind %)) (:records r))]
                 (assoc plan :ws-id (:id r) :at (get-in r [:run :fired-at]))))
         (sort-by :at)
         vec)))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  claim-attempts
  "Every claim the sweep has attempted, as `{:address :ws-id :open? :outcome
   :addresses}`.

   A claim workstream carries the SAME `:improvement` adapter a proposal-level
   attempt carries, and only its id differs — `<ws>/<seq>#<n>` rather than
   `<ws>/<seq>.<obs>`. That is what lets the one-at-a-time hold span its own
   cutover: `attempts` reads by adapter, so a legacy proposal-level workstream
   still open holds the sweep on the day it ships, with no migration and no
   window in which the two are invisible to each other.

   `:addresses` is resolved from the plan the claim belongs to rather than
   stored on the workstream, because the plan is where a claim's membership is
   decided and a second copy could disagree with it."
  [project]
  (let [pk    (keyword (name project))
        plans (into {} (map (juxt (juxt :ws-id :seq) identity)) (plans-of pk))]
    (->> (attempts pk)
         (keep (fn [{:keys [address] :as a}]
                 (when-let [[_ ws-id seq-n idx] (re-matches #"(.+)/(\d+)#(\d+)" (str address))]
                   (let [claim (get-in plans [[ws-id (parse-long seq-n)] :claims (parse-long idx)])]
                     (assoc a :addresses (vec (:addresses claim)))))))
         vec)))
