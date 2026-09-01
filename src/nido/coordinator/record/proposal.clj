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

   Nothing here writes. The verbs that record a decision or a landing stay in
   the work vocabulary, where the surface reaches them."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.set]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as cws]
   [nido.coordinator.report :as report]
   [nido.platform.io :as io]))

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
  "Render a ledger entry as a `:format`-tagged gate report. An `.edn` file is a
   typed event — read + validated against the schema for its `:kind`
   (report/parse-event — the read contract, which accepts any shape that was
   writable when the entry was written), :seq/:at stamped from the entry (the same
   stamp cws/latest-entry applies); any other file is markdown
   (:format :markdown). A typed `.edn` that fails to read/validate degrades to a
   :markdown payload of its raw text rather than blanking the pane.

   :seq is load-bearing, not decoration: it is the ledger position a rendered
   gate binds its buttons to, so a click can be checked against the report that
   drew it (option-actions -> choose-option!)."
  [base-dir entry]
  (let [f    (str (fs/path base-dir (:file entry)))
        edn? (str/ends-with? (str (:file entry)) ".edn")]
    (or (when edn?
          (try (-> (report/parse-event (:kind entry) (io/read-edn f))
                   (assoc :seq (:seq entry) :at (:at entry)))
               (catch Throwable _ nil)))
        (let [md (when (fs/exists? f) (slurp f))]
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
  "The workstream's own ledger — the single event store. {:base-dir <string|nil>
   :entries <vector>}, oldest-first."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    {:base-dir (cstate/workstream-dir project ws-id) :entries (vec (:entries w))}
    {:base-dir nil :entries []}))

(defn- analysis-entries
  "Oldest-first [seq entry-map] for every :review-analysis on this workstream,
   parsed. Reads through the same entry->report every other reader uses, so a
   record too old or too new for this reader degrades the same way it does
   everywhere else rather than blanking the proposal list."
  [project ws-id]
  (let [{:keys [base-dir entries]} (active-ledger project ws-id)]
    (->> entries
         (filter #(= :review-analysis (:kind %)))
         (keep (fn [e]
                 (let [r (entry->report base-dir e)]
                   (when (= :review-analysis (:format r)) r)))))))

(defn- decisions-by-address
  "{[analysis-seq observation] -> decision} for this workstream, latest wins.

   Latest rather than first because a decision is an append and the ledger has
   no delete: changing your mind is a second entry, and the one that counts is
   the one made last. Nothing enforces one decision per proposal, and nothing
   should — the record of having changed it is the point."
  [project ws-id]
  (let [{:keys [base-dir entries]} (active-ledger project ws-id)]
    (->> entries
         (filter #(= :improvement-decision (:kind %)))
         (keep (fn [e]
                 (let [r (entry->report base-dir e)]
                   (when (= :improvement-decision (:format r)) r))))
         (reduce (fn [m d] (assoc m [(:analysis-seq d) (:observation d)] d)) {}))))

(defn- landings-by-address
  "{[analysis-seq observation] -> landing} for this workstream, latest wins.

   Latest for a different reason than a decision's: a proposal can land twice —
   carried in one change, then extended or corrected in another — and the row
   should point at the one a reader should go read. The earlier record stays on
   the ledger, which is where the full history belongs."
  [project ws-id]
  (let [{:keys [base-dir entries]} (active-ledger project ws-id)]
    (->> entries
         (filter #(= :improvement-landed (:kind %)))
         (keep (fn [e]
                 (let [r (entry->report base-dir e)]
                   (when (= :improvement-landed (:format r)) r))))
         (reduce (fn [m l] (assoc m [(:analysis-seq l) (:observation l)] l)) {}))))

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

   `:at-seq` is the position a decision about this row must carry — the ledger's
   latest entry at the moment the row was built, NOT the analysis's own seq. A
   decision answers the workstream as it stands, and these differ the moment
   anything else is appended.

   `:decision` is what a human ruled and `:landed` is what became of it, and the
   two are separate because approving does not carry anything out. A row with a
   decision and no landing is a commitment nobody has discharged — the state
   this surface used to render identically to a finished one."
  [project]
  (let [pk (keyword (name project))]
    (->> (cws/list-ids pk)
         (keep (fn [ws-id]
                 (let [w (cws/read-ws pk ws-id)]
                   (when (some #(= :review-run (:adapter %)) (:external-refs w))
                     {:ws-id ws-id :w w}))))
         (mapcat (fn [{:keys [ws-id w]}]
                   (let [decided (decisions-by-address pk ws-id)
                         landed  (landings-by-address pk ws-id)
                         at-seq  (count (:entries w))
                         ref     (some #(when (= :review-run (:adapter %)) %) (:external-refs w))]
                     (for [a (analysis-entries pk ws-id)
                           [i o] (map-indexed vector (:observations a))
                           :when (:proposal o)]
                       {:project      (name pk)
                        :ws-id        ws-id
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
                        :title        (:title ref)
                        :decision     (get decided [(:seq a) i])
                        :landed       (get landed [(:seq a) i])}))))
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

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  attempts
  "Every improvement workstream in `project`, as `{:address :ws-id :open?}`.

   `:open?` is the absence of a close, NOT the presence of a failure record.
   That distinction is the whole hold: a session that crashes hard writes no
   tombstone, so a rule keyed on failure would read a dead attempt as no attempt
   and start a second one on top of the first. A close is written by the
   improvement skill when it has landed, or by a human clearing a wedge."
  [project]
  (let [pk (keyword (name project))]
    (->> (cws/list-ids pk)
         (keep (fn [ws-id]
                 (let [w (cws/read-ws pk ws-id)]
                   (when-let [ref (some #(when (= improvement-adapter (:adapter %)) %)
                                        (:external-refs w))]
                     {:address (:id ref) :ws-id ws-id :open? (nil? (:closed w))
                      :outcome (:outcome (:closed w))}))))
         vec)))

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
   `session/gating-phases` is #{:preprocessing :running :parked}, so a session
   that fails or is halted releases the slot and the next poll starts another
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

(defn- dispositions-by-address
  "{address -> disposition} across `plans`, latest plan wins.

   Latest for the same reason a decision is: a plan is an append and the ledger
   has no delete, so a later plan reconsidering an address is the one that
   counts. Re-planning is ordinary — a claim refused at reservation returns its
   survivors to the owed set, and tomorrow groups them differently."
  [plans]
  (reduce (fn [m plan]
            (reduce (fn [m {:keys [disposition addresses]}]
                      (reduce #(assoc %1 %2 disposition) m addresses))
                    m
                    (:claims plan)))
          {}
          plans))

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
   and are grouped again tomorrow."
  [proposals plans attempts]
  (let [disposed (dispositions-by-address plans)
        tried    (into #{}
                       (comp (remove :open?)
                             (remove #(= :vetoed (:outcome %)))
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
  "Every `:improvement-plan` on this project's ledgers, oldest first, each with
   the `:ws-id` and `:seq` it was appended at.

   Scans every workstream rather than a known planning one, for the reason
   `of-project` scans for analyses: which workstream holds a record is a fact
   about how it arrived, and a reader that has to know it in advance breaks the
   first time a plan is appended from somewhere else — by hand, by a migration,
   by a second project. The ordering is what makes `dispositions-by-address`
   latest-wins meaningful."
  [project]
  (let [pk (keyword (name project))]
    (->> (cws/list-ids pk)
         (mapcat (fn [ws-id]
                   (let [{:keys [base-dir entries]} (active-ledger pk ws-id)]
                     (->> entries
                          (filter #(= :improvement-plan (:kind %)))
                          (keep (fn [e]
                                  (let [r (entry->report base-dir e)]
                                    (when (= :improvement-plan (:format r))
                                      (assoc r :ws-id ws-id)))))))))
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
