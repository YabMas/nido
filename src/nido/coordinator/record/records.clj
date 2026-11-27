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
   the store's, never the kind's, so neither is in its schema. So is `:from`, which only an
   archived record carries: what autonomous work recorded before runs held it, on a workstream the
   fire minted, is kept in an archive journal of that workstream's own, each record as it was
   written with `:from {:holder <that workstream> :seq <its seq there> :ref <the ref the fire
   minted it by>}`. A holder is a run or such a workstream, and `holders` reads both as one shape."
  (:require
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.report :as report]
   [nido.core.client :as client]))

(defn ^{:malli/schema [:=> [:cat :map] :map]} ->event
  "Record `r` as the ledger event of its kind: `:format` for `:kind`, without its `:seq`, `:at` and
   `:from`."
  [r]
  (-> r (dissoc :kind :seq :at :from) (assoc :format (:kind r))))

(defn ^{:malli/schema [:=> [:cat :map] :map]} ->record
  "Ledger event `e` (`:format`-tagged, as written) as a record."
  [e]
  (-> e (dissoc :format) (assoc :kind (:format e))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]} parse
  "Record `r` read against its kind's read contract, as `report/parse-event` reads an entry, with
   its `:seq`, `:at` and `:from`; nil when it does not read — a record of a kind this reader is too
   old or too new for. `:at` is absent on a record appended before records carried it."
  [r]
  (try (some-> (report/parse-event (:kind r) (->event r)) ->record (assoc :seq (:seq r))
               (cond-> (:at r) (assoc :at (:at r))
                       (:from r) (assoc :from (:from r))))
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

(def ^:private ^:dynamic *request-read*
  "Atom of what one request read has read — `[core project]` → a delay of that project's runs —
   for the extent of one `with-request-read` call, or nil outside one.

   Scoped, never global: runs are live, and a runs value outliving the request it was read for
   is a page that shows a decision made in another tab only once something invalidates it.
   Nothing live is kept between requests."
  nil)

(defn ^{:malli/schema [:=> [:cat :any] :any]} with-request-read
  "Call `f` with one request read open across it: every `runs` (and so every `holders`) of a
   project inside it answers from one read of that project's runs, so the derivations of one
   render — proposals, improvement holds, session recovery — see one moment of them. Nested
   calls share the outer read. Open it around a read-only render: a run appended inside it is not
   seen by a later read inside it."
  [f]
  (if *request-read*
    (f)
    (binding [*request-read* (atom {})] (f))))

(defn- runs-read [project]
  (->> (client/value! (client/runs project) :runs)
       (mapv (fn [[id sm]] (assoc sm :id id :key (client/run-key project id)
                                  :records (into [] (keep parse) (:records sm)))))))

(defn- once-in
  "Deref the delay atom `a` holds at `k`, putting one there that calls `read` when there is none,
   so concurrent asks read once. A read that throws is not kept: the next ask reads again."
  [a k read]
  (let [d (get (swap! a update k #(or % (delay (read)))) k)]
    (try @d
         (catch Throwable t
           (swap! a (fn [m] (if (identical? d (get m k)) (dissoc m k) m)))
           (throw t)))))

(defn ^{:malli/schema [:=> [:cat :any] [:vector :map]]} runs
  "Every run of `project`, each its core summary (`:run` its identity, `:status`, `:closed?`) with
   its records parsed under `:records`, its id under `:id` and its core key under `:key`. Inside
   `with-request-read`, the one read of them that request made. Throws when the core is
   unreachable."
  [project]
  (if-let [a *request-read*]
    (once-in a [(client/core-identity) (name project)] #(runs-read project))
    (runs-read project)))

(def ^{:doc "What precedes the holder in the name of an archive journal: `archive/<holder>`."}
  archive-name "archive")

(defn- archive-of [project holder] (journal project (str archive-name "/" holder)))

(defn- read-archive
  "The holders `project`'s archive journals keep, in one read, each read as a run over: its records
   under their seq on the workstream that held them, `:run` the ref it was minted by as its
   payload and its first record's time as its fire's. Oldest first."
  [project]
  (->> (client/value! (client/states (str (journal project archive-name) "/")) :states)
       vals
       (keep (fn [st]
               (let [rs (into [] (keep parse) (:records st))]
                 (when-let [{:keys [holder ref]} (:from (first rs))]
                   {:id holder :archived? true :status :done :closed? true
                    :run {:payload ref :fired-at (:at (first rs))}
                    :records (mapv #(-> % (assoc :seq (get-in % [:from :seq])) (dissoc :from)) rs)}))))
       (sort-by (juxt #(str (get-in % [:run :fired-at])) :id))
       vec))

(defonce ^:private held-archives
  (atom {}))

(defn- archived-holders
  "`read-archive` for `project`, asked of each core at most once per process: nothing but
   `archive!` writes an archive, and no runtime path calls it, so what was read stands until this
   process writes one — which drops what it holds for that project. Held per core, so a process
   that speaks to another core (a test's home) never answers from this one's."
  [project]
  (once-in held-archives [(client/core-identity) (name project)] #(read-archive project)))

(defn- drop-held-archive! [project]
  (swap! held-archives (fn [m] (into {} (remove #(= (name project) (second (key %)))) m))))

(defn ^{:malli/schema [:=> [:cat :any] [:vector :map]]} holders
  "Everything that holds autonomous work's records in `project`, as `runs` reads a run: the
   archived holders (`archive!`), each over and with no `:key`, then every run. A reader asking
   what that work did asks this, and never which of the two held it. The archive is read once per
   process and core, the runs as `runs` reads them — once per request inside `with-request-read`.
   Throws when the core is unreachable."
  [project]
  (into (archived-holders project) (runs project)))

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

(defn ^{:malli/schema [:=> [:cat :any [:sequential :map]] :int]} archive!
  "Append `rs` — records as their holders wrote them, each with `:at` and `:from {:holder :seq
   :ref}`, each holder's in its order — to their holders' archive journals in `project`. Each is
   checked against its kind's read contract, not its write one: it was writable when it was
   written. Re-runnable: a holder's records its journal already holds, in the same order, are not
   appended again. Answers how many it appended; throws before appending anything when a record
   does not read, and on the first append the core refuses or a journal that disagrees with `rs`."
  [project rs]
  (doseq [r rs]
    (when-not (and (:from r) (:at r) (parse r))
      (throw (ex-info "an archived record must read and say where it was written" {:record r}))))
  ;; What this process holds of the archive is dropped on both sides of the write: a read made
  ;; while it was being written may hold it half-written.
  (drop-held-archive! project)
  (try
    (reduce
     (fn [total [holder hrs]]
       (let [k    (archive-of project holder)
             held (client/value! (client/records k) :records)]
         (when-not (= (map #(get-in % [:from :seq]) held)
                      (map #(get-in % [:from :seq]) (take (count held) hrs)))
           (throw (ex-info "an archive journal holds records other than these" {:journal k})))
         (+ total
            (reduce (fn [n r]
                      (let [o (outcome! (client/append-record! k (str "rec/" k "/" (get-in r [:from :seq])) r n))]
                        (if (:events o)
                          (inc n)
                          (throw (ex-info (str "archive record refused: " (pr-str (:refused o (:error o))))
                                          {:journal k :from (:from r) :outcome o})))))
                    (count held) (drop (count held) hrs))
            (- (count held)))))
     0
     (map (juxt #(get-in (first %) [:from :holder]) identity)
          (partition-by #(get-in % [:from :holder]) rs)))
    (finally (drop-held-archive! project))))
