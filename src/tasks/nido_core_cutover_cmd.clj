(ns tasks.nido-core-cutover-cmd
  "`bb nido:core:cutover` — move this nido home to the core, once — and `bb nido:core:restore`, which
   undoes it until the core has started over the handed-over store.

   The cut-over, in order, each step refusing the rest when it cannot be shown done:
     1. snapshot the nido home (`snapshot!`), with the root checkout's commit the old daemon ran;
     2. stop and disable the old coordinator, and wait for its pid to go;
     3. stop every legacy background agent session, then read the agent listing again, under a
        deadline, until it shows none running;
     4. read the hand-over plan from the legacy records (`plan`) and have the JVM commit it
        (`nido.core.cutover`), which marks the home cut over;
     5. install and start the core, import every legacy ledger into it read-only, and start the
        intake host and the dashboard (`nido:core:install`).

   The snapshot leaves out the databases (`unsnapshotted`): the cut-over neither reads nor writes
   them, and a copy of a running cluster's files is not a snapshot of it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]
            [nido.coordinator.daemon.supervisor :as supervisor]
            [nido.coordinator.record.legacy-ledger :as legacy]
            [nido.coordinator.record.session :as session]
            [nido.coordinator.view.workstreams :as wsv]
            [nido.coordinator.work.core :as wcore]
            [nido.core.release :as release]
            [nido.platform.project :as project]
            [nido.session.lifecycle :as lifecycle]
            [tasks.nido-core :as nido-core]))

(def ^:private unsnapshotted
  "The nido home's entries the snapshot leaves out: PostgreSQL clusters, and the core's own
   directory, which the cut-over creates and the restore removes."
  #{"shared" "templates" "test-pg" "core"})

(defn- home [] (release/nido-home))
(defn- snapshot-dir [] (str (home) ".cutover-snapshot"))
(defn- say [& xs] (println (apply str "cutover · " xs)))
(defn- refuse! [& xs] (println (apply str "cutover REFUSED · " xs)) (System/exit 1))

(defn- commit-of [dir]
  (str/trim (:out (p/sh {:dir dir} "jj" "log" "--no-graph" "-r" "@" "-T" "commit_id"))))

(defn- snapshot!
  "Copy the nido home's entries but the databases into the snapshot directory, once: a snapshot
   already there is the home before the first attempt and is kept."
  []
  (let [dir (snapshot-dir) root (supervisor/checkout)]
    (if (fs/exists? (str dir "/snapshot.edn"))
      (say "snapshot kept from the first attempt: " dir)
      (let [entries (vec (for [e (fs/list-dir (home)) :let [n (str (fs/file-name e))]
                               :when (not (unsnapshotted n))]
                           n))]
        (fs/delete-tree dir)
        (fs/create-dirs dir)
        (doseq [n entries]
          (let [r (p/sh "cp" "-a" (str (home) "/" n) (str dir "/" n))]
            (when-not (zero? (:exit r)) (refuse! "copying " n " failed: " (:err r)))))
        (spit (str dir "/snapshot.edn")
              (pr-str {:at (str (java.time.Instant/now)) :entries entries :root root
                       :root-commit (when root (commit-of root))}))
        (say "snapshot of " (count entries) " entries at " dir)))))

(defn- old-pid-live? []
  (let [f (fs/file (home) "coordinator" "coordinator.pid")
        pid (when (fs/exists? f) (parse-long (str/trim (slurp f))))
        h (when pid (java.lang.ProcessHandle/of pid))]
    (boolean (and h (.isPresent h) (.isAlive ^java.lang.ProcessHandle (.get h))))))

(defn- await! [what f ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (f) true
            (> (System/currentTimeMillis) end) (refuse! what)
            :else (do (Thread/sleep 1000) (recur))))))

(defn- stop-old-daemon! []
  (let [r (supervisor/with-service :coordinator supervisor/uninstall!)]
    (say "old coordinator " (if (:ok? r) "stopped and disabled" (str "uninstall said: " (:err r)))))
  (await! "the old coordinator's pid is still live" (complement old-pid-live?) 60000))

(defn- claude! [& argv]
  (let [r (apply p/sh "claude" argv)]
    (when-not (or (zero? (:exit r)) (str/includes? (str (:out r) (:err r)) "No session matching"))
      (throw (ex-info (str "claude " (str/join " " argv) " failed: " (str/trim (str (:err r) (:out r))))
                      {:exit (:exit r)})))
    (:out r)))

(defn- running-background
  "The background sessions Claude Code's agent listing (`claude agents --json --all`) shows a live
   process for, but the one running this verb. Throws when the listing cannot be read."
  []
  (let [me (System/getenv "CLAUDE_CODE_SESSION_ID")]
    (filter #(and (= "background" (:kind %)) (:pid %) (not= me (:sessionId %)))
            (json/parse-string (claude! "agents" "--json" "--all") true))))

(defn- stop-sessions!
  "Stop every running legacy background session, and answer the directories of those stopped
   mid-turn."
  []
  (let [rows (try (running-background) (catch Exception e (refuse! "the agent listing: " (ex-message e))))
        mid (set (for [r rows :when (or (= "busy" (:status r)) (= "working" (:state r)))]
                   (str (fs/canonicalize (:cwd r)))))]
    (doseq [r rows]
      (try (claude! "stop" (:sessionId r))
           (catch Exception e (refuse! "stopping " (:sessionId r) ": " (ex-message e)))))
    (await! "a legacy session is still running"
            #(empty? (try (running-background) (catch Exception _ [:unreadable])))
            120000)
    (say "stopped " (count rows) " legacy session(s), " (count mid) " mid-turn")
    mid))

(defn- session-cwd [project s]
  (try (some-> (:wt-path (lifecycle/session-coords (:name s) {:project (name project)})) str
               (#(when (fs/exists? %) (str (fs/canonicalize %)))))
       (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat [:set :string]] [:sequential :map]]}
  plan
  "The hand-over for each unclosed legacy workstream, as {:ws <ws-key> :commands [...]}: one with
   a session whose worktree is on disk opens its unit over it — the newest such session — and
   records an unknown when that session was stopped mid-turn (its directory is in `mid`); every
   one gets its hand-over item. No session is provisioned and no blocker carried over."
  [mid]
  (for [p (map keyword (keys (project/list-projects)))
        id (legacy/legacy-ids p)
        :let [w (legacy/legacy-record p id)]
        :when (and w (not (legacy/closed? w)))
        :let [label (or (some-> (wsv/label w nil) str not-empty) id)
              s (->> (session/list-sessions p id) (remove :conductor)
                     (keep #(when-let [cwd (session-cwd p %)] (assoc % :cwd cwd)))
                     (sort-by :created-at) last)]]
    {:ws (wcore/ws-key p id)
     :commands (concat
                (when s
                  [{:command :open-unit :unit id :project (name p) :goal label :session-cwd (:cwd s)}])
                (when (and s (mid (:cwd s)))
                  [{:command :record-unknown :unit id :key (str "cutover/" (:name s))}])
                [{:command :hand-over
                  :summary (str "Handed over from the old coordinator: " label "."
                                (if s (str " Its session " (:name s) " is at " (:cwd s)
                                           (when (mid (:cwd s)) ", stopped mid-turn: what it was doing is unknown"))
                                    " It had no session."))}])}))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]} cutover
  "Move this nido home to the core, once. See the namespace."
  [& _]
  (let [ps (release/paths)]
    (when (fs/exists? (:release-file ps))
      (refuse! "the core has already started over this home"))
    (snapshot!)
    (stop-old-daemon!)
    (let [mid (stop-sessions!)
          plan (vec (plan mid))
          f (str (fs/create-temp-file {:prefix "cutover-plan" :suffix ".edn"}))]
      (spit f (pr-str plan))
      (say "handing over " (count plan) " workstream(s)")
      (let [r (p/sh {:dir (str (fs/canonicalize (System/getProperty "user.dir")))}
                    "clojure" "-J--enable-native-access=ALL-UNNAMED" "-M" "-m" "nido.core.cutover" f)
            out (try (edn/read-string (last (str/split-lines (:out r)))) (catch Exception _ nil))]
        (when-not (and (zero? (:exit r)) out) (refuse! "the hand-over: " (:err r)))
        (say "handed over " (:handed out) "; " (count (:refused out)) " command(s) refused")
        (doseq [x (:refused out)] (say "  refused " (pr-str x)))))
    (nido-core/install)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]} restore
  "Put the snapshot back in place of the nido home and start the old coordinator again from the
   root checkout at the commit it ran — refused once the core has started over the handed-over store."
  [& _]
  (let [ps (release/paths) dir (snapshot-dir)
        {:keys [entries root root-commit]} (try (edn/read-string (slurp (str dir "/snapshot.edn")))
                                                (catch Exception _ (refuse! "no snapshot at " dir)))]
    (when (fs/exists? (:release-file ps)) (refuse! "the core has started over the handed-over store"))
    (supervisor/with-service :core supervisor/uninstall!)
    (doseq [n entries]
      (fs/delete-tree (str (home) "/" n))
      (let [r (p/sh "cp" "-a" (str dir "/" n) (str (home) "/" n))]
        (when-not (zero? (:exit r)) (refuse! "restoring " n ": " (:err r)))))
    (fs/delete-tree (:core ps))
    (when (and root root-commit (not= root-commit (commit-of root)))
      (p/sh {:dir root} "jj" "new" root-commit)
      (say "root checkout put back on " (subs root-commit 0 12)))
    (let [r (p/sh {:dir root} "bb" "nido:coordinator:install")]
      (say "old coordinator " (if (zero? (:exit r)) "installed and started" (str "NOT started: " (:err r)))))
    (say "restored " (count entries) " entries from " dir)))
