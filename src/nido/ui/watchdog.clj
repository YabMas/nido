(ns nido.ui.watchdog
  "Background thread that fully stops idle sessions to free memory.
   'Idle' is determined by TCP sampling: the watchdog counts
   ESTABLISHED connections on each running app's port; if zero for
   longer than idle-timeout-ms, the entire session (app + JVM + any
   isolated PG) is torn down via lifecycle/stop!. Wake is user-driven:
   idle-stopped sessions stay down until the next `session:init`."
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

(defn- established-connections?
  "True if `port` currently has at least one ESTABLISHED TCP connection.
   Returns false on any error (fail-open: don't stop an app on probe failure)."
  [port]
  (try
    (let [{:keys [exit out]}
          (shell {:continue true :out :string :err :string}
                 "lsof" "-nP" (str "-iTCP:" port) "-sTCP:ESTABLISHED" "-t")]
      (and (zero? exit)
           (some-> out str/trim seq)))
    (catch Exception _ false)))

(defn- idle-timeout-ms
  "Per-project idle timeout from session.edn :watchdog :idle-timeout-ms, or
   the default."
  [project-name]
  (let [cfg (engine/load-session-edn project-name)]
    (or (get-in cfg [:watchdog :idle-timeout-ms])
        default-idle-timeout-ms)))

(defn- running-apps
  "Return a seq of {:project-name :instance-id :session-name :app-port} for
   every registered project's currently-listening apps."
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
       :app-port port})))

(defn- tick!
  "One watchdog pass. Updates last-seen-ms for ports with traffic; stops
   apps whose last-seen is older than the project's idle-timeout-ms."
  [last-seen-atom]
  (let [now (System/currentTimeMillis)]
    (doseq [{:keys [project-name instance-id session-name app-port]} (running-apps)]
      (if (established-connections? app-port)
        (swap! last-seen-atom assoc instance-id now)
        (let [last (or (get @last-seen-atom instance-id) now)
              idle-ms (- now last)
              timeout (idle-timeout-ms project-name)]
          (swap! last-seen-atom (fn [m] (update m instance-id #(or % now))))
          (when (>= idle-ms timeout)
            (core/log-step (str "[watchdog] session idle for "
                                (quot idle-ms 1000) "s (> "
                                (quot timeout 1000) "s) — stopping " instance-id))
            (try
              (lifecycle/stop! session-name {:project project-name})
              (swap! last-seen-atom dissoc instance-id)
              (catch Exception e
                (println "[watchdog] stop failed for" instance-id ":" (ex-message e))))))))))

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
    (println (str "[nido] Watchdog started (tick=" tick-ms "ms, idle-timeout per project via session.edn :watchdog)"))
    t))

(defn stop! []
  (when-let [t @thread-atom]
    (.interrupt ^Thread t)
    (reset! thread-atom nil)
    (println "[nido] Watchdog stopped")))
