(ns nido.session.services.eval
  "App toggle via nREPL eval. The session owns the JVM (via the :process
   service); this service owns the application's mount/start state on top of
   that JVM. Lazy by design: `start-service!` only reserves the port and
   publishes the URL into context — it does NOT boot the app. Use
   `start-app!` / `stop-app!` to toggle the app at runtime (called by
   lifecycle bb tasks and the UI)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.core :as core]
   [nido.process :as proc]
   [nido.session.context :as ctx]
   [nido.session.service :as service]
   [nido.session.state :as state]))

(defn- friendly-host
  "Build a per-instance hostname under *.localhost so each session gets its
   own browser cookie jar and a recognizable URL.
     - main checkout : <project-name>.localhost
     - worktree      : <session-name>.<project-name>.localhost
   `*.localhost` resolves to 127.0.0.1 automatically (RFC 6761) on macOS
   and modern browsers, so no /etc/hosts entry is needed."
  [project-name instance-id]
  (cond
    (and instance-id project-name
         (str/starts-with? instance-id (str project-name "--")))
    (str (subs instance-id (count (str project-name "--")))
         "." project-name ".localhost")

    project-name
    (str project-name ".localhost")

    :else "localhost"))

(defn- append-eval-log!
  "Append one eval's header + captured stdout/stderr to the session's
   eval.log so it's visible in the UI log viewer's `eval` tab."
  [instance-id header out err]
  (when instance-id
    (try
      (let [log-path (state/log-file instance-id :eval)]
        (fs/create-dirs (state/log-dir instance-id))
        (spit log-path
              (str "\n\n=== " (core/now-iso) " " header " ===\n"
                   (when-not (str/blank? out) (str out "\n"))
                   (when-not (str/blank? err) (str "[stderr] " err "\n")))
              :append true))
      (catch Exception _ nil))))

(defn- nrepl-eval-error?
  "Heuristic: clj-nrepl-eval's exit code is 0 even when the eval threw.
   It returns the nREPL session's stdout, which for an exception typically
   contains strings like `Execution error`, `Syntax error`, or an error map
   with `:cause`. Detect those so we can surface a proper failure."
  [out]
  (let [s (or out "")]
    (some #(str/includes? s %)
          ["Execution error" "Syntax error" ":cause" "FATAL ERROR"
           "DATABASE STARTUP FAILED" "could not start [#'"])))

(defn- first-meaningful-line
  "Pick the most useful line of eval output to surface as an error msg."
  [s]
  (let [lines (->> (str/split-lines (or s ""))
                   (map str/trim)
                   (remove str/blank?))
        match (first (filter (fn [l]
                               (some #(str/includes? l %)
                                     ["Execution error" ":cause"
                                      "DATABASE STARTUP FAILED"
                                      "could not start"]))
                             lines))]
    (when match
      (cond-> match (> (count match) 240) (subs 0 240)))))

(defn- eval-on-repl!
  "Send `form` to the nREPL on `nrepl-port`, capturing both stdout and
   stderr. Persists the exchange to the session's eval.log (visible in
   the UI). Throws if the shell itself failed OR if the eval returned
   content that looks like a thrown exception — with the first
   meaningful error line attached as :error-msg so callers can surface
   it on the :failed state."
  [instance-id nrepl-port timeout-ms form]
  (let [result (shell {:continue true :out :string :err :string}
                      "clj-nrepl-eval"
                      "-p" (str nrepl-port)
                      "--timeout" (str timeout-ms)
                      form)
        out (:out result)
        err (:err result)]
    (append-eval-log! instance-id
                      (str "eval on :" nrepl-port
                           " (exit=" (:exit result) ")")
                      out err)
    (when-not (zero? (:exit result))
      (throw (ex-info "nREPL evaluation failed (shell non-zero exit)"
                      {:port nrepl-port
                       :exit (:exit result)
                       :error err
                       :output out
                       :error-msg (or (first-meaningful-line err)
                                      (first-meaningful-line out)
                                      err)})))
    (when (nrepl-eval-error? out)
      (throw (ex-info "nREPL evaluation threw (eval returned exception)"
                      {:port nrepl-port
                       :output out
                       :error-msg (first-meaningful-line out)})))
    out))

(defn- wait-for-app-port! [host app-port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [next-log-at (System/currentTimeMillis)]
      (cond
        (proc/tcp-open? app-port) true
        (> (System/currentTimeMillis) deadline) false
        :else
        (let [now (System/currentTimeMillis)]
          (when (>= now next-log-at)
            (core/log-step (str "Waiting for app on http://" host ":" app-port "...")))
          (Thread/sleep 250)
          (recur (if (>= now next-log-at)
                   (+ now 5000)
                   next-log-at)))))))

;; ---------------------------------------------------------------------------
;; Public: app toggle (called from lifecycle + UI, NOT from start-service!)
;; ---------------------------------------------------------------------------

(defn start-app!
  "Eval the service's :start-form over nREPL and wait for the app's HTTP
   port to open. Idempotent: if the app is already listening, returns
   immediately without re-evaling."
  [service-def saved-state session-ctx]
  (let [{:keys [start-form eval-timeout-ms health-check-timeout-ms]
         :or {eval-timeout-ms 180000 health-check-timeout-ms 180000}} service-def
        {:keys [app-port nrepl-port host]} saved-state]
    (cond
      (not start-form)
      (throw (ex-info "Service has no :start-form" {:service (:name service-def)}))

      (not (and (pos-int? nrepl-port) (proc/tcp-open? nrepl-port)))
      (throw (ex-info "nREPL is not listening — session may not be up"
                      {:nrepl-port nrepl-port}))

      (proc/tcp-open? app-port)
      (do (core/log-step (str "App already listening on " host ":" app-port)) true)

      :else
      (let [instance-id (or (:instance-id saved-state)
                            (get-in session-ctx [:session :instance-id]))
            local-ctx (assoc session-ctx :app {:port app-port})
            resolved-start-form (ctx/substitute-value local-ctx start-form)]
        (core/log-step (str "Starting app on " host ":" app-port " (via nREPL eval)..."))
        (eval-on-repl! instance-id nrepl-port eval-timeout-ms resolved-start-form)
        (let [ready? (wait-for-app-port! host app-port health-check-timeout-ms)]
          (when-not ready?
            (println "warning: app port did not open before timeout, still starting"))
          (when ready?
            (core/log-step (str "App is reachable at http://" host ":" app-port)))
          ready?)))))

(defn stop-app!
  "Eval the service's :stop-form over nREPL to bring the app down while
   leaving the JVM running. Idempotent."
  [service-def saved-state]
  (let [{:keys [stop-form]} service-def
        {:keys [nrepl-port app-port host]} saved-state]
    (cond
      (not stop-form)
      (core/log-step "Service has no :stop-form — skipping app stop")

      (not (and (pos-int? nrepl-port) (proc/tcp-open? nrepl-port)))
      (core/log-step "nREPL not listening — nothing to stop")

      (not (proc/tcp-open? app-port))
      (core/log-step (str "App not listening on " host ":" app-port " — already idle"))

      :else
      (let [instance-id (:instance-id saved-state)]
        (core/log-step (str "Stopping app on " host ":" app-port " (via nREPL eval)..."))
        (try
          (eval-on-repl! instance-id nrepl-port 30000 stop-form)
          (catch Exception e
            (println "warning: could not stop app via nREPL:" (ex-message e))))))))

;; ---------------------------------------------------------------------------
;; Service multimethods
;; ---------------------------------------------------------------------------

(defmethod service/start-service! :eval
  [service-def session-ctx _opts]
  (let [{svc-name :name
         :keys [repl-service port-range]
         :or {port-range [3100 5100]}} service-def
        project-dir (get-in session-ctx [:session :project-dir])
        repl-port (get-in session-ctx [(keyword repl-service) :port])
        _ (when-not repl-port
            (throw (ex-info "REPL port not found in context"
                            {:repl-service repl-service
                             :available-keys (keys session-ctx)})))
        pre-allocated (when svc-name (get-in session-ctx [(keyword svc-name) :port]))
        app-port (or pre-allocated
                     (let [[low high] port-range
                           preferred-port (proc/deterministic-port project-dir low high)]
                       (proc/find-available-port preferred-port (- high low))))
        project-name (get-in session-ctx [:session :project-name])
        instance-id (get-in session-ctx [:session :instance-id])
        host (friendly-host project-name instance-id)
        url (str "http://" host ":" app-port)
        saved-state {:app-port app-port :nrepl-port repl-port :host host
                     :instance-id instance-id}]
    (core/log-step (str "App port reserved on " host ":" app-port " — starting app"))
    ;; Session lifecycle is now single-phase: init always boots the app.
    ;; Any exception here propagates → whole session start fails → engine
    ;; tears down upstream services. That's the desired contract.
    (start-app! service-def saved-state session-ctx)
    {:state saved-state
     :context {:port app-port
               :host host
               :url url}}))

(defmethod service/stop-service! :eval
  [service-def saved-state]
  ;; Called at session teardown. Runs the stop-form if the app happens to
  ;; be running — otherwise a no-op.
  (stop-app! service-def saved-state))

(defmethod service/service-status :eval
  [_service-def saved-state]
  (let [{:keys [app-port]} saved-state
        listening? (and (pos-int? app-port) (proc/tcp-open? app-port))]
    {:alive? listening?
     :listening? listening?
     :port app-port
     :app-state (if listening? :running :idle)}))
