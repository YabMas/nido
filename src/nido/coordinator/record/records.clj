(ns nido.coordinator.record.records
  "Records the core holds for a run and in a journal: one polymorphic shape, `{:kind k …}`, whose
   kinds are the ledger's own event kinds — a record of kind k holds exactly what a ledger entry of
   kind k holds, `:kind` in the place of the entry's `:format`. So the one schema registry there is
   (`nido.coordinator.report/event-schemas`) checks both, a reader renders a record as it renders
   an entry, and nothing grows a field per kind.

   A run holds what it did, appended while its unit is open; its key is the turn's
   `NIDO_CORE_RUN`. A journal holds what outlives any one run, named per project. Every append is
   checked against the kind's write schema here, before the core sees it, and names the record seq
   its author read: a journal append that decides from what it read retries on a stale refusal by
   reading and deciding again, so two authors racing are ordered by the core's writer.

   Every record carries `:at`, when its author appended it, beside the core's `:seq` — both are
   the store's, never the kind's, so neither is in its schema."
  (:require
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.report :as report]
   [nido.core.client :as client]))

(defn ^{:malli/schema [:=> [:cat :map] :map]} ->event
  "Record `r` as the ledger event of its kind: `:format` for `:kind`, without its `:seq` and `:at`."
  [r]
  (-> r (dissoc :kind :seq :at) (assoc :format (:kind r))))

(defn ^{:malli/schema [:=> [:cat :map] :map]} ->record
  "Ledger event `e` (`:format`-tagged, as written) as a record."
  [e]
  (-> e (dissoc :format) (assoc :kind (:format e))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]} parse
  "Record `r` read against its kind's read contract, as `report/parse-event` reads an entry, with
   its `:seq` and `:at`; nil when it does not read — a record of a kind this reader is too old or too
   new for. `:at` is absent on a record appended before records carried it."
  [r]
  (try (some-> (report/parse-event (:kind r) (->event r)) ->record (assoc :seq (:seq r))
               (cond-> (:at r) (assoc :at (:at r))))
       (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat :map] :map]} checked
  "Record `r` when it satisfies its kind's write contract; throws `report/validate-event`'s
   explanation otherwise."
  [r]
  (report/validate-event (:kind r) (->event r))
  r)

(defn- stamped [r] (assoc (checked r) :at (clock/now-iso)))

(defn ^{:malli/schema [:=> [:cat :any :string] :string]} journal
  "The core key of `project`'s journal named `jname`."
  [project jname]
  (client/journal-key project jname))

(defn ^{:malli/schema [:=> [:cat :string] [:vector :map]]} of
  "The records of the run or journal `k`, in append order, each parsed (`parse`); those that do not
   read are left out. Throws when the core is unreachable."
  [k]
  (into [] (keep parse) (client/value! (client/records k) :records)))

(defn ^{:malli/schema [:=> [:cat :any] [:vector :map]]} runs
  "Every run of `project`, each its core summary (`:run` its identity, `:status`, `:closed?`) with
   its records parsed under `:records`, its id under `:id` and its core key under `:key`. Throws
   when the core is unreachable."
  [project]
  (->> (client/value! (client/runs project) :runs)
       (mapv (fn [[id sm]] (assoc sm :id id :key (client/run-key project id)
                                  :records (into [] (keep parse) (:records sm)))))))

(defn- outcome! [o]
  (when (client/unavailable? o) (client/value! o :records))
  o)

(defn ^{:malli/schema [:=> [:cat :string :map] :int]} append-to-run!
  "Append record `r` to run `run-key`, answering its seq. Throws when it fails its kind's schema,
   when the run is over (`:run-is-over` — its unit finished, and what it did is closed), or when
   another append keeps landing first."
  [run-key r]
  (let [r (stamped r)]
   (loop [tries 5]
    (let [n (count (client/value! (client/records run-key) :records))
          o (outcome! (client/append-record! run-key (str "rec/" run-key "/" (random-uuid)) r n))]
      (cond
        (:events o) (inc n)
        (and (= :stale (get-in o [:refused :reason])) (> tries 1)) (recur (dec tries))
        :else (throw (ex-info (str "run record refused: " (pr-str (:refused o (:error o))))
                              {:run run-key :record (:kind r) :outcome o})))))))

(defn ^{:malli/schema [:=> [:cat :string :any] :any]} decide-and-append!
  "Append to journal `k` what `decide` makes of the journal's records as they stand, ordered against
   every other author. `decide` is called with the parsed records and answers
   `{:append r :then x}` — append `r`, then answer `x` — or `{:answer x}` to append nothing. When
   another author appended between the read and the append, the journal is read and `decide` asked
   again: what it decided from is never what it did not see. Throws when `r` fails its kind's
   schema or the core refuses otherwise."
  [k decide]
  (loop [tries 8]
    (let [raw (client/value! (client/records k) :records)
          d   (decide (into [] (keep parse) raw))]
      (if-not (contains? d :append)
        (:answer d)
        (let [o (outcome! (client/append-record! k (str "rec/" k "/" (random-uuid))
                                                 (stamped (:append d)) (count raw)))]
          (cond
            (:events o) (:then d)
            (and (= :stale (get-in o [:refused :reason])) (> tries 1)) (recur (dec tries))
            :else (throw (ex-info (str "journal record refused: " (pr-str (:refused o (:error o))))
                                  {:journal k :record (:kind (:append d)) :outcome o}))))))))
