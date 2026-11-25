(ns nido.core.store
  "Where committed state lives: one SQLite database, in WAL mode with synchronous=FULL.

   A commit makes a batch durable as one transaction: the events of every command in the batch,
   each command's key with its outcome, and the state those events fold to. That state is stored
   twice. Each workstream's whole state is kept as EDN, for reads. Its turns, units and operator
   asks are also kept as rows, so the store itself can state the invariants as table constraints.
   Both are a cache over the events, and `verify` checks that they equal the fold.

   The constraints refuse an illegal state even when a write bypasses core-domain, such as raw SQL
   or a bug above. They are:
     - a turn is asked exactly when it carries a call
     - a unit has at most one turn running, asked or resolved
     - a workstream's conductor has at most one turn running
     - a held unit has no active turn
     - an open operator ask binds only asked turns, and no call is bound by two open asks
     - an open operator ask binds at least one call, and no unit it concerns (the one it names,
       every unit when it names none, and those whose calls it binds) has a running turn: both
       are checked by the `violations` view at commit, because they span rows written in either
       order

   Each workstream's summary (`nido.core.domain/summary`) is kept beside its state, written in the
   same transaction, so a reader of a whole project reads one small row per workstream, and finds
   the workstreams a person owes a move without reading any other state.

   A workstream's work records are entries on its ledger: each one's index row is part of the
   state, and its content is kept once more in the entries table, by workstream and seq, so an
   entry is read without reading the events. Both are written from the :entry-appended event in
   the transaction that commits it, and an entry is never rewritten.

   One writer only. core-writer holds the connection `open` returns; readers use `open-reader`,
   which never writes and, under WAL, never waits on the writer."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [nido.core.domain :as d])
  (:import [java.sql Connection]))

;; ── schema ───────────────────────────────────────────────────────────────────

(def ^:private schema
  ["CREATE TABLE IF NOT EXISTS events(
      pos     INTEGER PRIMARY KEY AUTOINCREMENT,
      ws      TEXT NOT NULL,
      cmd_key TEXT NOT NULL,
      data    TEXT NOT NULL)"
   "CREATE INDEX IF NOT EXISTS events_by_ws ON events(ws, pos)"
   "CREATE TABLE IF NOT EXISTS commands(
      key     TEXT PRIMARY KEY,
      ws      TEXT NOT NULL,
      outcome TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS workstreams(
      ws    TEXT PRIMARY KEY,
      state TEXT NOT NULL,
      pos   INTEGER NOT NULL)"
   "CREATE INDEX IF NOT EXISTS workstreams_by_pos ON workstreams(pos)"
   "CREATE TABLE IF NOT EXISTS units(
      ws     TEXT NOT NULL,
      id     TEXT NOT NULL,
      status TEXT NOT NULL CHECK (status IN ('open','merged','retracted')),
      held   INTEGER NOT NULL CHECK (held IN (0,1)),
      PRIMARY KEY (ws, id))"
   "CREATE TABLE IF NOT EXISTS turns(
      ws      TEXT NOT NULL,
      id      TEXT NOT NULL,
      unit    TEXT,
      status  TEXT NOT NULL
              CHECK (status IN ('running','asked','resolved','completed','failed','cancelled')),
      call_id TEXT,
      PRIMARY KEY (ws, id),
      CHECK ((status = 'asked') = (call_id IS NOT NULL)))"
   "CREATE UNIQUE INDEX IF NOT EXISTS one_active_turn_per_unit ON turns(ws, unit)
      WHERE unit IS NOT NULL AND status IN ('running','asked','resolved')"
   "CREATE UNIQUE INDEX IF NOT EXISTS one_running_conductor_turn ON turns(ws)
      WHERE unit IS NULL AND status = 'running'"
   "CREATE TRIGGER IF NOT EXISTS held_unit_runs_nothing BEFORE INSERT ON turns
      WHEN NEW.unit IS NOT NULL AND NEW.status IN ('running','asked','resolved')
       AND EXISTS (SELECT 1 FROM units WHERE ws = NEW.ws AND id = NEW.unit AND held = 1)
    BEGIN SELECT RAISE(ABORT, 'held unit has an active turn'); END"
   "CREATE TABLE IF NOT EXISTS operator_asks(
      ws       TEXT NOT NULL,
      id       TEXT NOT NULL,
      status   TEXT NOT NULL CHECK (status IN ('open','answered','withdrawn')),
      concerns TEXT,
      kind     TEXT,
      PRIMARY KEY (ws, id))"
   "CREATE TABLE IF NOT EXISTS ask_binds(
      ws   TEXT NOT NULL,
      ask  TEXT NOT NULL,
      turn TEXT NOT NULL,
      PRIMARY KEY (ws, ask, turn))"
   "CREATE TRIGGER IF NOT EXISTS open_ask_binds_asked_turns BEFORE INSERT ON ask_binds
      WHEN EXISTS (SELECT 1 FROM operator_asks a WHERE a.ws = NEW.ws AND a.id = NEW.ask AND a.status = 'open')
       AND NOT EXISTS (SELECT 1 FROM turns t WHERE t.ws = NEW.ws AND t.id = NEW.turn AND t.status = 'asked')
    BEGIN SELECT RAISE(ABORT, 'open operator ask binds a turn that is not asked'); END"
   "CREATE TRIGGER IF NOT EXISTS call_bound_once BEFORE INSERT ON ask_binds
      WHEN EXISTS (SELECT 1 FROM operator_asks a WHERE a.ws = NEW.ws AND a.id = NEW.ask AND a.status = 'open')
       AND EXISTS (SELECT 1 FROM ask_binds b JOIN operator_asks a ON a.ws = b.ws AND a.id = b.ask
                   WHERE b.ws = NEW.ws AND b.turn = NEW.turn AND a.status = 'open')
    BEGIN SELECT RAISE(ABORT, 'call bound by two open operator asks'); END"
   "CREATE VIEW IF NOT EXISTS violations AS
      SELECT a.ws, a.id AS ask, 'open operator ask binds no call' AS violation
      FROM operator_asks a
      WHERE a.status = 'open' AND a.kind IS NOT 'conductor-stopped'
        AND NOT EXISTS (SELECT 1 FROM ask_binds b WHERE b.ws = a.ws AND b.ask = a.id)
      UNION ALL
      SELECT a.ws, a.id AS ask, 'open operator ask concerns a unit with a running turn' AS violation
      FROM operator_asks a JOIN turns t ON t.ws = a.ws
      WHERE a.status = 'open' AND t.status = 'running' AND t.unit IS NOT NULL
        AND a.kind IS NOT 'conductor-stopped'
        AND (a.concerns IS NULL OR a.concerns = t.unit
             OR EXISTS (SELECT 1 FROM ask_binds b JOIN turns bt ON bt.ws = b.ws AND bt.id = b.turn
                        WHERE b.ws = a.ws AND b.ask = a.id AND bt.unit = t.unit))"
   "CREATE TABLE IF NOT EXISTS entries(
      ws      TEXT NOT NULL,
      seq     INTEGER NOT NULL,
      content TEXT NOT NULL,
      PRIMARY KEY (ws, seq))"
   "CREATE TABLE IF NOT EXISTS summaries(
      ws      TEXT PRIMARY KEY,
      project TEXT,
      asking  INTEGER NOT NULL CHECK (asking IN (0,1)),
      data    TEXT NOT NULL)"
   "CREATE INDEX IF NOT EXISTS summaries_by_project ON summaries(project, asking)"])

(declare backfill-summaries! entry-contents)

(defn- exec! [^Connection c sql] (with-open [st (.createStatement c)] (.execute st sql)))

(defn- connect ^Connection [path]
  (io/make-parents (io/file path))
  (jdbc/get-connection {:dbtype "sqlite" :dbname (str path)}))

(defn ^{:malli/schema [:=> [:cat :string] :map]} open
  "Open the store at `path` for writing, creating it and its schema if they are absent. Exactly one
   writer may hold it; core-writer is that writer."
  [path]
  (let [c (connect path)]
    (exec! c "PRAGMA journal_mode=WAL")
    (exec! c "PRAGMA synchronous=FULL")
    (doseq [s schema] (exec! c s))
    (backfill-summaries! c)
    {:conn c :path (str path)}))

(defn ^{:malli/schema [:=> [:cat :string] :map]} open-reader
  "Open the store at `path` for reading only. Under WAL a reader sees the last committed state and
   never waits on the writer."
  [path]
  (let [c (connect path)]
    (exec! c "PRAGMA query_only=1")
    {:conn c :path (str path)}))

(defn ^{:malli/schema [:=> [:cat :map] :nil]} close
  "Close a store opened by `open` or `open-reader`."
  [store]
  (.close ^Connection (:conn store)))

;; ── rows: the state, restated as tables the constraints read ──────────────────

(defn- rows-of
  "The rows `state` of workstream `ws` is stored as, in the order they must be inserted so each
   trigger sees what it checks against: units, turns, asks, then the calls asks bind."
  [ws state]
  {:units (for [u (vals (:units state))]
            [ws (:id u) (name (:status u)) (if (:held? u) 1 0)])
   :turns (for [t (vals (:turns state))]
            [ws (:id t) (:unit t) (name (:status t)) (get-in t [:call :id])])
   :asks  (for [a (vals (:operator-asks state))]
            [ws (:id a) (name (:status a)) (:concerns a) (some-> (:kind a) name)])
   :binds (for [a (vals (:operator-asks state)), t (:binds a)]
            [ws (:id a) t])})

(def ^:private row-sql
  {:units "INSERT INTO units(ws,id,status,held) VALUES (?,?,?,?)"
   :turns "INSERT INTO turns(ws,id,unit,status,call_id) VALUES (?,?,?,?,?)"
   :asks  "INSERT INTO operator_asks(ws,id,status,concerns,kind) VALUES (?,?,?,?,?)"
   :binds "INSERT INTO ask_binds(ws,ask,turn) VALUES (?,?,?)"})

(defn- replace-rows!
  "Replace workstream `ws`'s rows with those of `state`. The cost is the workstream's own size,
   never the ledger's."
  [c ws state]
  (doseq [t ["ask_binds" "operator_asks" "turns" "units"]]
    (jdbc/execute! c [(str "DELETE FROM " t " WHERE ws = ?") ws]))
  (let [rows (rows-of ws state)]
    (doseq [k [:units :turns :asks :binds], r (get rows k)]
      (jdbc/execute! c (into [(row-sql k)] r)))))

(defn- insert-entries!
  "Keep the content of every entry `events` append to workstream `ws`. An entry already kept
   under its seq refuses the insert, so nothing can rewrite one."
  [c ws events]
  (doseq [{:keys [event row content]} events :when (= :entry-appended event)]
    (jdbc/execute! c ["INSERT INTO entries(ws,seq,content) VALUES (?,?,?)" ws (:seq row) content])))

(defn- project-of
  "The project a workstream key names: what precedes its first slash, or nil."
  [ws]
  (let [i (str/index-of ws "/")] (when (and i (pos? i)) (subs ws 0 i))))

(defn- put-summary!
  "Keep `state`'s summary (`nido.core.domain/summary`) as workstream `ws`'s row of the summaries
   table, under the project its key names."
  [c ws state]
  (let [sm (d/summary state)]
    (jdbc/execute! c ["INSERT INTO summaries(ws,project,asking,data) VALUES (?,?,?,?)
                       ON CONFLICT(ws) DO UPDATE SET project = excluded.project,
                         asking = excluded.asking, data = excluded.data"
                      ws (project-of ws) (if (:asking? sm) 1 0) (pr-str sm)])))

(defn- backfill-summaries!
  "Give every workstream the summaries table holds no row for its summary, from its stored state:
   a store written before the table existed reads whole from the first open."
  [c]
  (let [missing (jdbc/execute! c ["SELECT w.ws, w.state FROM workstreams w
                                   LEFT JOIN summaries s ON s.ws = w.ws WHERE s.ws IS NULL"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (when (seq missing)
      (jdbc/with-transaction [tx c]
        (doseq [{:keys [ws state]} missing]
          (put-summary! tx ws (edn/read-string state)))))))

(defn- violation-rows [c]
  (jdbc/execute! c ["SELECT ws, ask, violation FROM violations"]
                 {:builder-fn rs/as-unqualified-lower-maps}))

;; ── commit ───────────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map [:sequential :map]] [:vector [:maybe :int]]]} commit!
  "Make `batch` durable as one transaction and answer, entry by entry, the position of the
   entry's last event (nil for an entry that yielded none, such as a refusal).

   Each entry of `batch` is {:key :ws :events :outcome :state}: a command's key, the workstream it
   was decided against, the events it yielded, the outcome to answer a repeat of its key with, and
   the state the workstream folds to after it. If any constraint refuses, nothing in the batch is
   written and the exception is rethrown, so a caller is never answered for a batch that did not
   commit."
  [store batch]
  (let [^Connection c (:conn store)]
    (jdbc/with-transaction [tx c]
      (let [last-pos (volatile! 0)
            positions (volatile! [])]
        (doseq [{:keys [key ws events outcome state]} batch]
          (doseq [e events]
            (jdbc/execute! tx ["INSERT INTO events(ws,cmd_key,data) VALUES (?,?,?)" ws key (pr-str e)])
            (vreset! last-pos (:pos (jdbc/execute-one! tx ["SELECT last_insert_rowid() AS pos"]
                                                       {:builder-fn rs/as-unqualified-lower-maps}))))
          (insert-entries! tx ws events)
          (jdbc/execute! tx ["INSERT INTO commands(key,ws,outcome) VALUES (?,?,?)" key ws (pr-str outcome)])
          (when (seq events)
            (jdbc/execute! tx ["INSERT INTO workstreams(ws,state,pos) VALUES (?,?,?)
                                ON CONFLICT(ws) DO UPDATE SET state = excluded.state, pos = excluded.pos"
                               ws (pr-str state) @last-pos])
            (replace-rows! tx ws state)
            (put-summary! tx ws state))
          (vswap! positions conj (when (seq events) @last-pos)))
        (when-let [v (seq (violation-rows tx))]
          (throw (ex-info "commit breaks a store constraint" {:violations (vec v)})))
        @positions))))

;; ── reads ────────────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map :string] [:maybe :any]]} command-outcome
  "The outcome a command with `key` was answered with, or nil if no such command committed."
  [store key]
  (some-> (jdbc/execute-one! (:conn store) ["SELECT outcome FROM commands WHERE key = ?" key]
                             {:builder-fn rs/as-unqualified-lower-maps})
          :outcome edn/read-string))

(defn ^{:malli/schema [:=> [:cat :map :string] :map]} state
  "Workstream `ws`'s committed state; the empty state for a workstream with no events."
  [store ws]
  (or (some-> (jdbc/execute-one! (:conn store) ["SELECT state FROM workstreams WHERE ws = ?" ws]
                                 {:builder-fn rs/as-unqualified-lower-maps})
              :state edn/read-string)
      d/empty-state))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :string]]} workstreams
  "The ids of every workstream with committed events."
  [store]
  (mapv :ws (jdbc/execute! (:conn store) ["SELECT ws FROM workstreams ORDER BY ws"]
                           {:builder-fn rs/as-unqualified-lower-maps})))

(defn ^{:malli/schema [:=> [:cat :map :string] [:map-of :string :map]]} states
  "The committed state of every workstream whose id starts with `prefix`, by id."
  [store prefix]
  (into {}
        (map (fn [{:keys [ws state]}] [ws (edn/read-string state)]))
        (jdbc/execute! (:conn store)
                       ["SELECT ws, state FROM workstreams WHERE substr(ws, 1, ?) = ? ORDER BY ws"
                        (count prefix) prefix]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn ^{:malli/schema [:=> [:cat :map :string] [:map-of :string :map]]} summaries
  "The summary (`nido.core.domain/summary`) of every workstream of `project`, by key — one read of
   a small row per workstream, never a state."
  [store project]
  (into {}
        (map (fn [{:keys [ws data]}] [ws (edn/read-string data)]))
        (jdbc/execute! (:conn store) ["SELECT ws, data FROM summaries WHERE project = ? ORDER BY ws" project]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn ^{:malli/schema [:=> [:cat :map :string] [:map-of :string :map]]} asking
  "The committed state of every workstream of `project` a person owes a move now — an open ask on
   a workstream that is not closed — by key. Reads only those, through the summaries' index."
  [store project]
  (into {}
        (map (fn [{:keys [ws state]}] [ws (edn/read-string state)]))
        (jdbc/execute! (:conn store) ["SELECT w.ws, w.state FROM summaries s JOIN workstreams w ON w.ws = s.ws
                                       WHERE s.project = ? AND s.asking = 1 ORDER BY w.ws" project]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn ^{:malli/schema [:=> [:cat :map :string :map] :map]} project-states
  "Every workstream of `project`, read in one pass over the summaries' index:
   {:states {key state}}, and with `:summaries? true` {:summaries {key summary}} beside it. With
   `:open? true` only those whose summary is neither closed nor discarded, read off the summary so
   no other state is parsed. With `:newest-of [kind …]`, {:contents {key {seq content}}}: the
   content of each returned workstream's newest ledger entry of one of those kinds, for a reader
   whose fold reads that entry of every workstream."
  [store project {:keys [open? summaries? newest-of]}]
  (let [kinds (set newest-of)
        rows  (into []
                    (keep (fn [{:keys [ws state data]}]
                            (let [sm (edn/read-string data)]
                              (when-not (and open? (or (:closed? sm) (:discarded? sm)))
                                [ws (edn/read-string state) sm]))))
                    (jdbc/execute! (:conn store)
                                   ["SELECT w.ws, w.state, s.data FROM summaries s JOIN workstreams w ON w.ws = s.ws
                                     WHERE s.project = ? ORDER BY w.ws" project]
                                   {:builder-fn rs/as-unqualified-lower-maps}))]
    (cond-> {:states (into {} (map (fn [[ws st]] [ws st])) rows)}
      summaries?  (assoc :summaries (into {} (map (fn [[ws _ sm]] [ws sm])) rows))
      (seq kinds) (assoc :contents
                         (into {}
                               (keep (fn [[ws st]]
                                       (when-let [row (last (filter #(kinds (:kind %)) (:ledger st)))]
                                         (let [c (entry-contents store ws [(:seq row)])]
                                           (when (seq c) [ws c])))))
                               rows)))))

(defn ^{:malli/schema [:=> [:cat :map :string [:sequential :int]] [:map-of :int :string]]} entry-contents
  "The content of each entry of workstream `ws` named in `seqs`, by seq; a seq the ledger does not
   hold is left out."
  [store ws seqs]
  (into {}
        (for [n (distinct seqs)
              :let [r (jdbc/execute-one! (:conn store) ["SELECT content FROM entries WHERE ws = ? AND seq = ?" ws n]
                                         {:builder-fn rs/as-unqualified-lower-maps})]
              :when r]
          [n (:content r)])))

(defn ^{:malli/schema [:=> [:cat :map :string] [:sequential :map]]} events
  "Workstream `ws`'s events, in commit order."
  [store ws]
  (mapv (comp edn/read-string :data)
        (jdbc/execute! (:conn store) ["SELECT data FROM events WHERE ws = ? ORDER BY pos" ws]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn ^{:malli/schema [:=> [:cat :map :int] [:sequential :map]]} changed-since
  "Every workstream whose last commit is after position `pos`, each {:ws :pos :state} at its
   last commit, in commit order. Costs the workstreams that changed, never the ledger."
  [store pos]
  (mapv #(update % :state edn/read-string)
        (jdbc/execute! (:conn store) ["SELECT ws, pos, state FROM workstreams WHERE pos > ? ORDER BY pos" pos]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn ^{:malli/schema [:=> [:cat :map] :int]} position
  "The position of the last committed event; 0 for an empty store."
  [store]
  (or (:pos (jdbc/execute-one! (:conn store) ["SELECT max(pos) AS pos FROM events"]
                               {:builder-fn rs/as-unqualified-lower-maps}))
      0))

;; ── verify and rebuild: reading all of history, only when asked ───────────────

(def ^:private row-columns
  {:units ["units"         [:ws :id :status :held]]
   :turns ["turns"         [:ws :id :unit :status :call_id]]
   :asks  ["operator_asks" [:ws :id :status :concerns :kind]]
   :binds ["ask_binds"     [:ws :ask :turn]]})

(defn- stored-rows
  "Workstream `ws`'s rows as the tables hold them, each kind a set of vectors in `rows-of` order."
  [c ws]
  (into {}
        (for [[k [table cols]] row-columns]
          [k (set (map (apply juxt cols)
                       (jdbc/execute! c [(str "SELECT * FROM " table " WHERE ws = ?") ws]
                                      {:builder-fn rs/as-unqualified-lower-maps})))])))

(defn- expected-rows [ws state]
  (update-vals (rows-of ws state) #(set (map vec %))))

(defn- stored-entries [c ws]
  (into {} (map (juxt :seq :content))
        (jdbc/execute! c ["SELECT seq, content FROM entries WHERE ws = ?" ws]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn- stored-summary [c ws]
  (some-> (jdbc/execute-one! c ["SELECT data FROM summaries WHERE ws = ?" ws]
                             {:builder-fn rs/as-unqualified-lower-maps})
          :data edn/read-string))

(defn- expected-entries [events]
  (into {} (for [{:keys [event row content]} events :when (= :entry-appended event)]
             [(:seq row) content])))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]} verify
  "Every way the stored state disagrees with the fold of the ledger, as data; empty when the cache
   is faithful. Reads all of history, so it runs only when explicitly requested (a verify command,
   a test, a migration), never at start."
  [store]
  (let [c (:conn store)]
    (vec
     (for [ws (workstreams store)
           :let [folded (d/fold (events store ws))
                 stored (state store ws)]
           problem (concat
                    (when (not= folded stored) [{:ws ws :problem :state-differs-from-fold}])
                    (when (not= (stored-rows c ws) (expected-rows ws stored))
                      [{:ws ws :problem :rows-differ-from-state}])
                    (when (not= (stored-entries c ws) (expected-entries (events store ws)))
                      [{:ws ws :problem :entries-differ-from-events}])
                    (when (not= (stored-summary c ws) (d/summary stored))
                      [{:ws ws :problem :summary-differs-from-state}])
                    (for [v (d/violations folded)] {:ws ws :problem :violation :violation v}))]
       problem))))

(defn ^{:malli/schema [:=> [:cat :map] :int]} rebuild!
  "Recompute every workstream's stored state, its rows and its entries from its events, in one
   transaction, and answer how many workstreams were rebuilt. This is what a release that changes the fold runs
   before it serves."
  [store]
  (let [c (:conn store) wss (workstreams store)]
    (jdbc/with-transaction [tx c]
      (doseq [ws wss]
        (let [evs (events store ws)
              s   (d/fold evs)]
          (jdbc/execute! tx ["UPDATE workstreams SET state = ? WHERE ws = ?" (pr-str s) ws])
          (replace-rows! tx ws s)
          (put-summary! tx ws s)
          (jdbc/execute! tx ["DELETE FROM entries WHERE ws = ?" ws])
          (insert-entries! tx ws evs))))
    (count wss)))
