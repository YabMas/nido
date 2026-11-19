(ns nido.coordinator.daemon.restart
  "The latest landing: a restart owed onto its commit, or the commit the daemon runs.

   The landing owes; the daemon pays. A landing is often the one process that cannot restart the
   daemon — it runs inside it (the dashboard's Publish), or runs are live — so it records the debt
   here and the daemon pays it on its tick by exiting for its supervisor to relaunch it onto the checkout
   that holds the landing. The daemon that comes up records that it runs it.

   `landing` is the one place the file's shape becomes trusted. `waits` is the one reading of
   whether a debt is due, derived from the system each time it is asked and never stored, so the
   landing's account, the daemon's act and what a surface shows cannot disagree."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.daemon.supervisor :as sup]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.process :as process])
  (:import
   (java.nio.channels FileChannel)
   (java.nio.file Paths StandardOpenOption)))

(def ^:private landing-schema
  (m/schema
   [:or
    [:map {:closed true} [:state [:= :owed]] [:commit :string] [:owed-at :string] [:by-pid :int]]
    [:map {:closed true} [:state [:= :running]] [:commit :string] [:owed-at :string]
     [:since :string] [:pid :int]]]))

(defn ^{:malli/schema [:=> [:cat] [:or [:map [:landing :Landing]] [:map [:none :boolean]] [:map [:unreadable :string]]]]}
  landing
  "The latest landing as the file holds it: {:landing <Landing>}; {:none true} when there is no
   file; {:unreadable <why>} when there is one that does not parse to a Landing. Unreadable is
   never answered as none — a debt nobody can read is still a debt somebody owes."
  []
  (let [path (cstate/restart-owed-path)]
    (if-not (fs/exists? path)
      {:none true}
      (try
        (let [v (edn/read-string (slurp path))]
          (if (m/validate landing-schema v)
            {:landing v}
            {:unreadable (str path " does not hold a landing: " (pr-str v))}))
        (catch Exception e
          {:unreadable (str path " could not be read: " (ex-message e))})))))

(defn- with-lock
  "Run `f` holding the exclusive lock on the record's lock file. The landing and the daemon are
   different processes, so the lock is the operating system's, not a monitor. Closing the channel
   is what releases it: babashka does not admit every method on the lock itself."
  [f]
  (let [path (str (cstate/restart-owed-path) ".lock")]
    (fs/create-dirs (fs/parent path))
    (with-open [ch (FileChannel/open (Paths/get path (into-array String []))
                                     (into-array StandardOpenOption [StandardOpenOption/CREATE
                                                                    StandardOpenOption/WRITE]))]
      (.lock ch)
      (f))))

(defn- write!
  "Write `v` whole: to a sibling, then moved over the record, so a reader never sees half of it."
  [v]
  (let [path (cstate/restart-owed-path)
        tmp  (str path ".tmp")]
    (spit tmp (pr-str v))
    (fs/move tmp path {:replace-existing true :atomic-move true})
    v))

(defn ^{:malli/schema [:=> [:cat :string :int] [:or [:map [:landing :Landing]] [:map [:failed :string]]]]}
  owe!
  "Record the latest landing as owed onto `commit` by the landing process `by-pid`, replacing
   whatever was recorded. {:landing <Landing>} as written, or {:failed <why>}."
  [commit by-pid]
  (try
    (with-lock #(hash-map :landing (write! {:state :owed :commit commit :owed-at (clock/now-iso)
                                            :by-pid by-pid})))
    (catch Exception e {:failed (str "the owed restart could not be written: " (ex-message e))})))

(defn ^{:malli/schema [:=> [:cat :Landing :int] [:or [:map [:landing :Landing]] [:map [:moved :boolean]] [:map [:failed :string]]]]}
  running!
  "Record `owed` as running in the daemon `pid`, since now — only while the record is still that
   owed landing (same commit and owed-at), so a landing owed meanwhile is never overwritten.
   {:landing <Landing>} as written; {:moved true}, writing nothing, when the record is no longer
   `owed`; {:failed <why>} when the write fails."
  [owed pid]
  (try
    (with-lock
      (fn []
        (let [{current :landing} (landing)]
          (if (and (= :owed (:state current))
                   (= (select-keys current [:commit :owed-at]) (select-keys owed [:commit :owed-at])))
            {:landing (write! {:state :running :commit (:commit owed) :owed-at (:owed-at owed)
                               :since (clock/now-iso) :pid pid})}
            {:moved true}))))
    (catch Exception e {:failed (str "the running landing could not be written: " (ex-message e))})))

(defn- checkout
  "The checkout the supervised daemon loads its code from, or nil when its service is not installed."
  []
  (sup/checkout))

(defn- holds?
  "Whether `dir`'s working-copy commit descends from `commit`. Reads the repository without
   snapshotting the working copy: the daemon's checkout is not this process's to touch."
  [dir commit]
  (let [{:keys [exit out]} (p/sh {:dir dir} "jj" "--ignore-working-copy" "log" "--no-graph"
                                 "-r" (str commit " & ::@") "-T" "commit_id")]
    (and (zero? exit) (not (str/blank? out)))))

(defn- started-at
  "When process `pid` started, or nil when it is gone or unreadable."
  [pid]
  (some-> (java.lang.ProcessHandle/of (long pid)) (.orElse nil) .info .startInstant (.orElse nil)))

(defn- daemon-pid []
  (let [f (cstate/pid-path)]
    (when (fs/exists? f) (parse-long (str/trim (slurp f))))))

(defn- background-session?
  "Whether `run`'s conversation is a background session, which outlives a restart and whose own
   Stop settles the Run. A :mechanical Run never is: the daemon runs its stage in-process, on the
   workstream's session, whatever conversation that session also holds."
  [run]
  (boolean
   (and (not= :mechanical (:mode run))
        (:workstream-id run) (:session-name run)
        (when-let [s (session/read-session (:project run) (:workstream-id run) (:session-name run))]
          (or (session/claude-facet s) (:dispatching s))))))

(defn- busy-runs
  "Runs whose agent is alive under the daemon, which a restart would cut: those live? reports
   live — an agent holding its launch lock — and those whose record says running and that the
   daemon executes itself rather than a background session, since a Run that takes no launch lock
   is invisible to live?. A background-session Run's record holds nothing."
  []
  (filterv (fn [id]
             (or (runs/live? id)
                 (let [run (runs/read-run id)]
                   (and (= :running (:state run)) (not (background-session? run))))))
           (runs/list-run-ids)))

(defn ^{:malli/schema [:=> [:cat :Landing] [:vector :string]]}
  waits
  "What `owed` still waits for, in words, read from what any process can see when asked: runs
   whose agent is alive under the daemon (see busy-runs), the owing landing still running, the
   daemon's checkout not holding the commit, the daemon not running under its supervisor, or the running
   daemon having started after the debt — which settles it rather than pays it. Empty when the
   daemon should restart now."
  [{:keys [commit owed-at by-pid]}]
  (let [busy  (busy-runs)
        dir   (checkout)
        dpid  (daemon-pid)
        start (when (and dpid (process/process-alive? dpid)) (started-at dpid))]
    (cond-> []
      (seq busy)
      (conj (str (count busy) " run(s) to finish: " (str/join ", " (take 5 busy))
                 (when (> (count busy) 5) ", …")))

      (process/process-alive? by-pid)
      (conj (str "the landing that owed it (pid " by-pid ") to finish"))

      (and dir (not (holds? dir commit)))
      (conj (str dir " to hold " commit))

      (not (:running? (sup/status)))
      (conj "the coordinator to be brought up under its supervisor — it starts on the landing")

      (and start (.isAfter ^java.time.Instant start (java.time.Instant/parse owed-at)))
      (conj "nothing: the running coordinator started after it and settles it"))))
