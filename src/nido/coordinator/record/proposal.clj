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
                     {:address (:id ref) :ws-id ws-id :open? (nil? (:closed w))}))))
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
