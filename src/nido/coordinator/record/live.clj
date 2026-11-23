(ns nido.coordinator.record.live
  "Which records can still change, held in memory by the running coordinator so its passes visit
   those and not every record nido has ever kept.

   A workstream is a SUBJECT while it can change, or a decision on another subject reads it,
   without anything writing to it: it is open and activated, it is an open drain or a slice an
   open drain started, a session record under it has a home that still stands or names a
   conversation Claude Code lists, or an unfinished Run names it. Anything else changes only when
   something writes to it, and every write nido makes moves a stamp the census reads — an
   identity or an entry the core commits moves the ledger's stamp, seat-messages.edn is renamed
   into the workstream's directory, a session record into its session's directory, a Run's record into its run's
   directory, a hook event appended to its conversation's log, a first event creating that log in
   the events directory. So a pass over the subjects and what the last census found MOVED — a
   drain counting as moved when a slice it started did — misses nothing a pass over everything
   would act on.

   The picture is derived only: one full read at `start!`, corrected by every `census!`, never
   written from, gone with the process. A process that has not started it — every task, every
   test that does not — is answered from disk, as the record layer answers."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.session-events :as events]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.io :as io]
   [nido.session.state :as session-state]))

(def ^:private finished-states
  "Run states after which a Run changes only by being written."
  #{:done :failed :dry-run-would-fire})

(def ^:private hot-window-ms
  "A conversation whose log was written this recently is stat'ed every census: its process may
   write again any moment, listed or not."
  (* 10 60 1000))

(def ^:private full-census-ms
  "How often a census stats every workstream, session and run directory, not only the subjects'.
   A write to a non-subject is acted on within this."
  10000)

(def ^:private log-scan-ms
  "How often a census lists every event log with its modification time. A log not written within
   `hot-window-ms` is otherwise only stat'ed here, so its next write is seen within this."
  30000)

(defonce ^:private !picture (atom nil))

(declare logs-written-since)

(defn- stamp [path] (io/file-stamp (str path)))

(defn- dir-names [path]
  (let [f (fs/file (str path))]
    (if (.isDirectory f) (vec (sort (.list f))) [])))

;; ---------------------------------------------------------------------------
;; One workstream
;; ---------------------------------------------------------------------------

(defn- ws-stamp
  "What moves whenever anything a pass reads of the workstream is written: its ledger and identity
   as the core holds them (`ws/ledger-stamp`), its directory (records renamed into it), its
   sessions directory (a session added) and each session's directory."
  [project ws-id]
  (let [sdir (cstate/ws-sessions-dir project ws-id)
        wdir (cstate/workstream-dir project ws-id)]
    [(stamp wdir)
     (ws/ledger-stamp project ws-id)
     (stamp sdir)
     (mapv #(stamp (fs/path sdir %)) (dir-names sdir))]))

(defn- conversation-of [record] (get-in record [:claude :session-id]))

(defn- read-ws-facts
  "What deciding subjecthood needs of one workstream, read from disk."
  [project ws-id]
  (let [w        (ws/read-ws project ws-id)
        records  (session/list-sessions project ws-id)]
    {:stamp         (ws-stamp project ws-id)
     ;; A close writes no :deactivated: the activation it ended no longer counts.
     :activated?    (boolean (and w (nil? (ws/closure w)) (= :activated (ws/activation w))))
     ;; An open drain is kept whatever its sessions are doing, and decides its wakes from the
     ;; slices it started — whose landing writes only the slice.
     :slices        (when (and w (nil? (ws/closure w)) (drain/drain? w))
                      (into [] (keep :ws-id) (drain/slices w)))
     :drain-of      (when w (drain/drain-of w))
     :homes?        (boolean (some #(and (:name %)
                                         (fs/exists? (session-state/session-home-dir (name project) (:name %))))
                                   records))
     :conversations (into #{} (keep conversation-of) records)}))

;; ---------------------------------------------------------------------------
;; Event logs and Runs
;; ---------------------------------------------------------------------------

(defn- events-dir [] (fs/parent (cstate/session-events-path "_")))

(defn- log-id [file-name] (str/replace file-name #"\.edn$" ""))

(defn- scan-logs
  "Every event log's modification time, by conversation id."
  []
  (let [d (fs/file (str (events-dir)))]
    (if-not (.isDirectory d)
      {}
      (into {} (for [^java.io.File f (.listFiles d)
                     :let [n (.getName f)]
                     :when (str/ends-with? n ".edn")]
                 [(log-id n) (.lastModified f)])))))

(defn- log-mtime [id]
  (let [f (fs/file (str (cstate/session-events-path id)))]
    (when (.exists f) (.lastModified f))))

(defn- read-run-facts [run-id]
  (let [r (runs/read-run run-id)]
    {:stamp (stamp (cstate/run-dir run-id))
     ;; A directory with no record (a Run that never got one written, or whose record was
     ;; cleared) names nothing that can change: it counts as finished until one is written.
     :finished? (or (nil? r) (contains? finished-states (:state r)))
     :ws (when (and (:project r) (:workstream-id r)) [(:project r) (:workstream-id r)])}))

;; ---------------------------------------------------------------------------
;; The picture
;; ---------------------------------------------------------------------------

(defn- subject? [{:keys [activated? slices homes? conversations]} listed run-ws]
  (or activated? (some? slices) homes? run-ws
      (boolean (some #(contains? listed %) conversations))))

(defn- with-subjects
  "The picture with :subjects recomputed from its facts."
  [{:keys [ws runs listed] :as p} _now-ms]
  (let [run-ws (into #{} (keep (fn [[_ r]] (when-not (:finished? r) (:ws r)))) runs)
        listed (or listed #{})
        own    (into #{} (keep (fn [[k facts]] (when (subject? facts listed (run-ws k)) k))) ws)
        ;; The slices an open drain started: its wakes read their ledgers.
        slices (for [[[project _] {:keys [slices]}] ws, id slices] [project id])]
    (assoc p :subjects (into own slices))))

(defn- project-ids [project] (vec (ws/list-ids project)))

(defn- build [projects now-ms]
  (let [ids  (into {} (map (fn [p] [p (project-ids p)])) projects)
        wsf  (into {} (for [[p xs] ids, id xs] [[p id] (read-ws-facts p id)]))
        rids (runs/list-run-ids)
        logs (scan-logs)]
    (with-subjects
      {:projects     (into {} (map (fn [p] [p {:stamp (stamp (cstate/workstreams-dir p)) :ids (ids p)}])) projects)
       :ws           wsf
       :runs         (into {} (map (fn [rid] [rid (read-run-facts rid)])) rids)
       :runs-stamp   (stamp (cstate/runs-dir))
       :logs         logs
       :hot          (into #{} (keep (fn [[id m]] (when (< (- now-ms m) hot-window-ms) id))) logs)
       :events-stamp (stamp (events-dir))
       :full-at      now-ms
       :logs-at      now-ms
       ;; Everything counts as moved for the first pass: what happened while no daemon ran is
       ;; acted on once, as a pass over every record would.
       :moved        {:ws (set (keys wsf)) :runs (set rids) :logs #{}}
       :fresh?       true}
      now-ms)))

(defn ^{:malli/schema [:=> [:cat [:sequential :ProjectName]] :any]}
  start!
  "Read every workstream, session record, Run and event log of `projects` once, and answer this
   process from the picture from then on. Every workstream and Run counts as moved until the
   first `census!`, so the first pass after a start visits them all."
  [projects]
  (reset! !picture (assoc (build projects (System/currentTimeMillis)) :project-list (vec projects)))
  (events/use-index! logs-written-since)
  nil)

(defn ^{:malli/schema [:=> [:cat] :any]}
  stop!
  "Forget the picture; this process is answered from disk again."
  []
  (reset! !picture nil)
  (events/use-index! nil)
  nil)

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  started?
  "Whether this process holds a picture."
  []
  (some? @!picture))

(defn- census-ws
  "The workstream keys whose stamps moved or that are new, given which keys to stat."
  [p keys-to-stat]
  (into #{} (filter (fn [[project id :as k]]
                      (not= (get-in p [:ws k :stamp]) (ws-stamp project id))))
        keys-to-stat))

(defn- census* [p now-ms]
  (let [full?      (>= (- now-ms (:full-at p)) full-census-ms)
        scan?      (>= (- now-ms (:logs-at p)) log-scan-ms)
        ;; New workstreams: a project's directory moves when one is created.
        projects   (reduce (fn [acc proj]
                             (let [s (stamp (cstate/workstreams-dir proj))]
                               (if (= s (get-in p [:projects proj :stamp]))
                                 acc
                                 (assoc acc proj {:stamp s :ids (project-ids proj)}))))
                           (:projects p) (:project-list p))
        new-keys   (for [[proj {:keys [ids]}] projects
                         :when (not= ids (get-in p [:projects proj :ids]))
                         id ids
                         :let [k [proj id]]
                         :when (not (contains? (:ws p) k))]
                     k)
        to-stat    (if full? (keys (:ws p)) (:subjects p))
        moved-ws   (into (set new-keys) (census-ws p to-stat))
        ws'        (reduce (fn [m [proj id :as k]] (assoc m k (read-ws-facts proj id))) (:ws p) moved-ws)
        ;; Runs: new ones move the runs directory; unfinished ones are stat'ed every census.
        runs-stamp (stamp (cstate/runs-dir))
        rids       (if (= runs-stamp (:runs-stamp p)) (keys (:runs p)) (runs/list-run-ids))
        new-rids   (remove #(contains? (:runs p) %) rids)
        stat-rids  (if full? (keys (:runs p)) (keep (fn [[id r]] (when-not (:finished? r) id)) (:runs p)))
        moved-runs (into (set new-rids)
                         (filter #(not= (get-in p [:runs % :stamp]) (stamp (cstate/run-dir %)))) stat-rids)
        runs'      (reduce #(assoc %1 %2 (read-run-facts %2)) (:runs p) moved-runs)
        ;; Event logs: the live conversations' — listed, or written lately — every census; every
        ;; one when the directory moved (a conversation's first event creates its log) and at the
        ;; scan's period. Only what was stat'ed is compared: forty thousand logs is the cost this
        ;; census exists to avoid.
        ev-stamp   (stamp (events-dir))
        scan?      (or scan? (not= ev-stamp (:events-stamp p)))
        fresh      (if scan?
                     (scan-logs)
                     (into {} (keep (fn [id] (when-let [m (log-mtime id)] [id m])))
                           (into (or (:hot p) #{}) (:listed p))))
        moved-logs (into #{} (keep (fn [[id m]] (when (not= m (get-in p [:logs id])) id))) fresh)
        logs       (if scan? fresh (merge (:logs p) fresh))
        hot        (if scan?
                     (into #{} (keep (fn [[id m]] (when (< (- now-ms m) hot-window-ms) id))) logs)
                     (into (or (:hot p) #{}) moved-logs))
        ;; A workstream whose session's conversation was written moved too: the pass reads it.
        moved-ws   (if (seq moved-logs)
                     (let [by-conv (into {} (for [[k f] ws', c (:conversations f)] [c k]))]
                       (into moved-ws (keep by-conv) moved-logs))
                     moved-ws)
        ;; So did one a moved Run names.
        moved-ws   (into moved-ws (keep #(:ws (get runs' %)) moved-runs))
        ;; And the drain a moved slice belongs to: its wakes decide from the slice.
        moved-ws   (into moved-ws (keep (fn [[project _ :as k]]
                                          (when-let [d (get-in ws' [k :drain-of])] [project d])))
                         moved-ws)]
    (->
      (assoc p :projects projects :ws ws' :runs runs' :runs-stamp runs-stamp
               :logs logs :hot hot :events-stamp ev-stamp
               :full-at (if full? now-ms (:full-at p))
               :logs-at (if scan? now-ms (:logs-at p))
               :moved (cond->> {:ws moved-ws :runs moved-runs :logs moved-logs}
                        ;; The first census after a start keeps start's everything-moved, which no
                        ;; pass has acted on yet.
                        (:fresh? p) (merge-with into (:moved p))))
      (dissoc :fresh?)
      ;; Subjecthood changes only with what moved, or as a log goes cold — which the full
      ;; census, every few seconds, catches.
      (cond-> (or full? (seq moved-ws) (seq moved-runs)) (with-subjects now-ms)))))

(defn ^{:malli/schema [:=> [:cat] [:maybe :map]]}
  census!
  "Stat what can show change and re-read what moved: the subjects' workstreams, unfinished Runs
   and recently written logs every call; every workstream, session and run directory every
   `full-census-ms`; every log every `log-scan-ms`; and anything new. Answers what moved, as
   {:ws #{[project ws-id]} :runs #{run-id} :logs #{conversation-id}} — empty sets when nothing did —
   or nil when the picture was not started. The moved set holds until the next census."
  []
  (when @!picture
    (:moved (swap! !picture census* (System/currentTimeMillis)))))

(defn ^{:malli/schema [:=> [:cat [:set :string]] :any]}
  listed!
  "Tell the picture which conversations Claude Code lists — the agent listing a conduct pass
   reads anyway. A workstream whose session record names one is a subject: a listed
   conversation can be woken or sent to whether or not its home still stands. Nothing while
   the picture is not started."
  [conversation-ids]
  (when @!picture
    (swap! !picture (fn [p] (if (= conversation-ids (:listed p))
                              p
                              (with-subjects (assoc p :listed conversation-ids) (System/currentTimeMillis))))))
  nil)

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  moved?
  "Whether the last census found anything moved; true while the picture is not started."
  []
  (if-let [p @!picture]
    (boolean (some seq (vals (:moved p))))
    true))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :string]]}
  subjects
  "The ids of `project`'s workstreams a pass visits, in `ws/list-ids` order: the subjects and the
   workstreams the last census found moved. Every workstream of the project while the picture
   is not started, or for a project registered after it was."
  [project]
  (let [p @!picture]
    (if (and p (contains? (:projects p) project))
      (let [want (into (:subjects p) (get-in p [:moved :ws]))]
        (filterv #(contains? want [project %]) (get-in p [:projects project :ids])))
      (vec (ws/list-ids project)))))

(defn ^{:malli/schema [:=> [:cat] [:vector :RunId]]}
  unfinished-run-ids
  "The ids of the Runs not yet done, failed or dry-run, and of any the last census found moved;
   every Run's id while the picture is not started."
  []
  (if-let [p @!picture]
    (vec (sort (into (get-in p [:moved :runs])
                     (keep (fn [[id r]] (when-not (:finished? r) id)) (:runs p)))))
    (runs/list-run-ids)))

(defn ^{:malli/schema [:=> [:cat :int] [:maybe [:sequential :string]]]}
  logs-written-since
  "The conversation ids whose event logs the picture holds as written at or after `since-ms`, or
   nil while the picture is not started (the caller then lists the directory itself)."
  [since-ms]
  (when-let [p @!picture]
    (vec (keep (fn [[id m]] (when (>= m since-ms) id)) (:logs p)))))
