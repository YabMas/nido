(ns nido.coordinator.watchdog
  "Background thread that fully stops idle sessions to free memory.
   A session counts as 'active' when ANY of the following holds:
     - the app port has at least one ESTABLISHED TCP connection
       (browser / dev-server traffic),
     - the nREPL port has at least one ESTABLISHED TCP connection
       (an editor or REPL client attached),
     - the worktree has filesystem edits inside the lookback window
       (raw editing / scripting, including agents that don't keep a
       socket open),
     - some process anywhere on the host has its cwd inside the
       worktree or session-home (an attached agent / shell / test
       runner — catches read/think phases that produce no writes).
   If none of these signals fire for longer than idle-timeout-ms, the
   entire session (app + JVM + any isolated PG) is torn down via
   lifecycle/down!. Wake is user-driven: idle-stopped sessions stay
   down until the next `session:up`.

   Owned by the coordinator daemon (see nido.coordinator.core/run!)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.config :as config]
   [nido.core :as core]
   [nido.process :as proc]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]))

(def ^:private default-idle-timeout-ms (* 30 60 1000))  ; 30 min
(def ^:private default-tick-ms 30000)                    ; 30s
(def ^:private default-fs-lookback-min 2)                ; covers one tick + jitter
(def ^:private default-fs-denylist
  ;; Directory *names* (not full paths) that `find -prune` will skip when
  ;; sampling worktree mtime. Keeps the scan cheap and ignores churn that
  ;; isn't user activity (VCS internals, build caches, dependency trees).
  [".git" ".jj" "target" "node_modules" ".shadow-cljs"
   ".cpcache" ".clj-kondo" ".lsp" "out" "dist" "build"])

(defn established-connections?
  "True if `port` currently has at least one ESTABLISHED TCP connection.
   Returns false on nil port or any error (fail-open: don't stop an app
   on probe failure)."
  [port]
  (if (nil? port)
    false
    (try
      (let [{:keys [exit out]}
            (shell {:continue true :out :string :err :string}
                   "lsof" "-nP" (str "-iTCP:" port) "-sTCP:ESTABLISHED" "-t")]
        (boolean (and (zero? exit) (some-> out str/trim seq))))
      (catch Exception _ false))))

(defn recent-fs-change?
  "True if any file in `worktree` (outside the denylisted dir names) has
   an mtime within the last `lookback-min` minutes. Short-circuits on
   the first match via `-quit`. Fail-open on error."
  [worktree denylist lookback-min]
  (if-not (and worktree (fs/exists? worktree))
    false
    (try
      (let [name-clauses (->> denylist
                              (map (fn [d] ["-name" d]))
                              (interpose ["-o"])
                              (apply concat))
            args (concat ["find" (str worktree)
                          "-type" "d" "("]
                         name-clauses
                         [")" "-prune" "-o"
                          "-type" "f" "-mmin" (str "-" lookback-min)
                          "-print" "-quit"])
            {:keys [exit out]} (apply shell {:continue true
                                             :out :string
                                             :err :string}
                                      args)]
        (boolean (and (zero? exit) (some-> out str/trim seq))))
      (catch Exception _ false))))

(defn cwd-snapshot
  "Run lsof once and return the cwd paths of every process on the host.
   Single-shot per tick (callers thread the snapshot through), so all
   sessions in this tick share the same probe — cheaper than one lsof
   per session. Fail-open: nil on error so a flaky lsof never causes
   false stops."
  []
  (try
    (let [{:keys [exit out]}
          (shell {:continue true :out :string :err :string}
                 "lsof" "-F" "n" "-d" "cwd")]
      (when (zero? exit)
        (->> (str/split-lines out)
             (filter #(str/starts-with? % "n"))
             (map #(subs % 1))
             (filterv seq))))
    (catch Exception _ nil)))

(defn- with-trailing-slash [^String s]
  (if (or (str/blank? s) (str/ends-with? s "/")) s (str s "/")))

(defn- canonical-prefix
  "Canonicalize a directory path (resolving symlinks) and append a trailing
   slash so prefix checks treat it as a directory boundary. Returns nil on
   anything unparseable so callers can skip it without crashing."
  [path]
  (when path
    (try
      (-> (fs/canonicalize path {:nofollow-links false}) str with-trailing-slash)
      (catch Exception _ nil))))

(defn process-cwd-inside?
  "True if any cwd in `snapshot` lies inside one of `dirs`. Compares on the
   canonicalized form so symlinks (e.g. session-home/worktree → real
   worktree) don't cause false negatives."
  [snapshot dirs]
  (when (seq snapshot)
    (let [prefixes (->> dirs (keep canonical-prefix) distinct)]
      (boolean
       (when (seq prefixes)
         (some (fn [cwd]
                 (when (seq cwd)
                   (let [cwd+ (with-trailing-slash cwd)]
                     (some #(str/starts-with? cwd+ %) prefixes))))
               snapshot))))))

(defn signals
  "Compute the four activity signals for one session. Returns a map so
   callers can both decide active? and emit a structured kill report."
  [{:keys [app-port nrepl-port worktree session-home
           fs-denylist fs-lookback-min cwd-snap]}]
  {:app-port-conn?  (established-connections? app-port)
   :nrepl-conn?     (established-connections? nrepl-port)
   :recent-fs?      (recent-fs-change? worktree fs-denylist fs-lookback-min)
   :proc-cwd?       (process-cwd-inside? cwd-snap [worktree session-home])})

(defn any-signal? [sigs]
  (boolean (some sigs [:app-port-conn? :nrepl-conn? :recent-fs? :proc-cwd?])))

(defn session-active?
  "Back-compat wrapper for callers without a cwd snapshot (tests, REPL).
   Production tick! computes signals directly via `signals`."
  [m]
  (any-signal? (signals (assoc m :cwd-snap (cwd-snapshot)))))

(defn- watchdog-config
  "Per-project watchdog tunables from session.edn :watchdog, with defaults."
  [project-name]
  (let [cfg (engine/load-session-edn project-name)
        w   (:watchdog cfg)]
    {:idle-timeout-ms (or (:idle-timeout-ms w) default-idle-timeout-ms)
     :fs-denylist     (or (:fs-denylist w) default-fs-denylist)
     :fs-lookback-min (or (:fs-lookback-min w) default-fs-lookback-min)}))

(defn- running-apps
  "Return a seq of session descriptors for every registered project's
   currently-listening apps. Includes the worktree path and the nREPL
   port so the watchdog can sample all activity signals."
  []
  (let [projects (config/read-projects)
        registry (state/read-registry)]
    (for [[project-name {:keys [directory]}] projects
          :let [base (try (lifecycle/worktrees-dir project-name directory)
                          (catch Exception _ nil))]
          :when (and base (fs/exists? base))
          wt-dir (fs/list-dir base)
          :when (fs/directory? wt-dir)
          :let [wt-path (str wt-dir)
                entry (get registry wt-path)
                port (:app-port entry)
                session-name (str (fs/file-name wt-dir))]
          :when (and entry port (proc/tcp-open? port))]
      {:project-name project-name
       :instance-id (:instance-id entry)
       :session-name session-name
       :worktree wt-path
       :session-home (state/session-home-dir project-name session-name)
       :app-port port
       :nrepl-port (:nrepl-port entry)})))

(defn- tick!
  "One watchdog pass. Updates last-seen-ms for sessions showing any
   activity signal; stops sessions whose last-seen is older than the
   project's idle-timeout-ms. Takes a single cwd snapshot per tick so
   all sessions share one lsof invocation."
  [last-seen-atom]
  (let [now      (System/currentTimeMillis)
        cwd-snap (cwd-snapshot)]
    (doseq [{:keys [project-name instance-id session-name
                    worktree session-home
                    app-port nrepl-port]} (running-apps)]
      (let [{:keys [idle-timeout-ms fs-denylist fs-lookback-min]}
            (watchdog-config project-name)
            sigs (signals {:app-port        app-port
                           :nrepl-port      nrepl-port
                           :worktree        worktree
                           :session-home    session-home
                           :fs-denylist     fs-denylist
                           :fs-lookback-min fs-lookback-min
                           :cwd-snap        cwd-snap})]
        (if (any-signal? sigs)
          (swap! last-seen-atom assoc instance-id now)
          (let [last (or (get @last-seen-atom instance-id) now)
                idle-ms (- now last)]
            (swap! last-seen-atom (fn [m] (update m instance-id #(or % now))))
            (when (>= idle-ms idle-timeout-ms)
              (core/log-step
               (str "[watchdog] session idle for "
                    (quot idle-ms 1000) "s (> "
                    (quot idle-timeout-ms 1000) "s) — signals "
                    (pr-str sigs) " — stopping " instance-id))
              (try
                (lifecycle/down! session-name {:project project-name})
                (swap! last-seen-atom dissoc instance-id)
                (catch Exception e
                  (println "[watchdog] stop failed for" instance-id ":" (ex-message e)))))))))))

(defonce ^:private thread-atom (atom nil))

(defn start!
  "Start the watchdog thread. Safe to call repeatedly — replaces any
   existing thread."
  [{:keys [tick-ms] :or {tick-ms default-tick-ms}}]
  (when-let [old @thread-atom]
    (reset! thread-atom nil)
    (.interrupt ^Thread old))
  (let [last-seen (atom {})
        running? (atom true)
        t (Thread.
           ^Runnable (fn []
                       (while @running?
                         (try (tick! last-seen)
                              (catch InterruptedException _
                                (reset! running? false))
                              (catch Exception e
                                (println "[watchdog] tick error:" (ex-message e))))
                         (try (Thread/sleep tick-ms)
                              (catch InterruptedException _
                                (reset! running? false)))))
           "nido-watchdog")]
    (.setDaemon t true)
    (.start t)
    (reset! thread-atom t)
    (println (str "[nido] Watchdog started (tick=" tick-ms "ms, signals: app-port + nrepl-port + worktree-fs + process-cwd;"
                  " idle-timeout per project via session.edn :watchdog)"))
    t))

(defn stop! []
  (when-let [t @thread-atom]
    (.interrupt ^Thread t)
    (reset! thread-atom nil)
    (println "[nido] Watchdog stopped")))
