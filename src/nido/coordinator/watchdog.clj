(ns nido.coordinator.watchdog
  "Background thread that fully stops idle sessions to free memory.
   A session counts as 'active' when ANY of the following holds:
     - the app port has at least one ESTABLISHED TCP connection
       (browser / dev-server traffic),
     - the nREPL port has at least one ESTABLISHED TCP connection
       (an editor or REPL client attached),
     - the worktree has filesystem edits inside the lookback window
       (raw editing / scripting, including agents that don't keep a
       socket open).
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

(defn session-active?
  "True if any activity signal fires for this session."
  [{:keys [app-port nrepl-port worktree fs-denylist fs-lookback-min]}]
  (or (established-connections? app-port)
      (established-connections? nrepl-port)
      (recent-fs-change? worktree fs-denylist fs-lookback-min)))

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
                port (:app-port entry)]
          :when (and entry port (proc/tcp-open? port))]
      {:project-name project-name
       :instance-id (:instance-id entry)
       :session-name (str (fs/file-name wt-dir))
       :worktree wt-path
       :app-port port
       :nrepl-port (:nrepl-port entry)})))

(defn- tick!
  "One watchdog pass. Updates last-seen-ms for sessions showing any
   activity signal; stops sessions whose last-seen is older than the
   project's idle-timeout-ms."
  [last-seen-atom]
  (let [now (System/currentTimeMillis)]
    (doseq [{:keys [project-name instance-id session-name
                    worktree app-port nrepl-port]} (running-apps)]
      (let [{:keys [idle-timeout-ms fs-denylist fs-lookback-min]}
            (watchdog-config project-name)]
        (if (session-active? {:app-port        app-port
                              :nrepl-port      nrepl-port
                              :worktree        worktree
                              :fs-denylist     fs-denylist
                              :fs-lookback-min fs-lookback-min})
          (swap! last-seen-atom assoc instance-id now)
          (let [last (or (get @last-seen-atom instance-id) now)
                idle-ms (- now last)]
            (swap! last-seen-atom (fn [m] (update m instance-id #(or % now))))
            (when (>= idle-ms idle-timeout-ms)
              (core/log-step (str "[watchdog] session idle for "
                                  (quot idle-ms 1000) "s (> "
                                  (quot idle-timeout-ms 1000) "s) — stopping "
                                  instance-id))
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
    (println (str "[nido] Watchdog started (tick=" tick-ms "ms, signals: app-port + nrepl-port + worktree-fs;"
                  " idle-timeout per project via session.edn :watchdog)"))
    t))

(defn stop! []
  (when-let [t @thread-atom]
    (.interrupt ^Thread t)
    (reset! thread-atom nil)
    (println "[nido] Watchdog stopped")))
