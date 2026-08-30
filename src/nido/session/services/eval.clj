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
   [nido.platform.core :as core]
   [nido.platform.process :as proc]
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
           "DATABASE STARTUP FAILED" "could not start [#'" "permission denied for schema"])))

(def ^:private flyway-checksum-mismatch-re
  #"(?i)checksum mismatch for migration version (\d+)")

(def ^:private flyway-unresolved-applied-re
  #"(?i)applied migration not resolved locally:\s*(\d+)")

(def ^:private flyway-permission-denied-re
  #"(?i)permission denied for schema (\w+)")

(defn- flyway-divergence-message
  "If `out` carries a Flyway 'shared-cluster diverged' signature, return an
   actionable remediation; else nil. These are the two most common second-order
   session-start failures on the shared Postgres cluster — opposite directions,
   opposite fixes — and surfacing them beats dumping the raw stack trace as an
   opaque \"nREPL evaluation threw\". `project-name` (may be nil) is woven into
   the suggested command when known.

   - checksum mismatch on version N — the shared cluster recorded a DIFFERENT
     migration under N than this branch's file (an earlier session applied its
     own VN). If this branch tracks main, realign the cluster:
     `bb nido:shared:pg:reset`.
   - applied migration not resolved locally: N — the shared cluster is AHEAD of
     this branch (a newer migration was applied to it that this branch lacks).
     This branch is stale; resetting the cluster would only re-break the
     up-to-date sessions. Run this one on a private clone
     (`bb nido:session:isolate`) or rebase the branch on main."
  [out project-name]
  (let [s    (or out "")
        proj (when project-name (str " :project " project-name))]
    (cond
      (re-find flyway-checksum-mismatch-re s)
      (let [version (second (re-find flyway-checksum-mismatch-re s))]
        (str "Database migration failed — Flyway checksum mismatch on migration version "
             version ". The shared Postgres cluster recorded a different V" version
             " than this branch's migration file. If this branch tracks main, realign the "
             "cluster with `bb nido:shared:pg:reset" proj
             "` (re-clones it from the template — shared dev data is reset). "
             "Full Flyway error is in the session eval log."))

      (re-find flyway-unresolved-applied-re s)
      (let [version (second (re-find flyway-unresolved-applied-re s))]
        (str "Database migration failed — the shared Postgres cluster has migrations applied "
             "(from V" version ") that this branch doesn't have, so Flyway rejects the boot. "
             "This branch is behind the shared cluster. Run it on a private clone with "
             "`bb nido:session:isolate" proj " <session>` (its own DB seeded from the template), "
             "or rebase the branch on main to pick up the missing migrations. "
             "Full Flyway error is in the session eval log."))

      (re-find flyway-permission-denied-re s)
      (str "Database migration blocked — this session tried to apply a migration to "
           "the shared cluster, which sessions may not migrate (it is kept at main by "
           "nido). Run it on a private clone: `bb nido:session:isolate" proj " <session>` "
           "(its own DB, seeded from the template, where migrations are allowed). "
           "Full error is in the session eval log.")

      :else nil)))

(defn- first-meaningful-line
  "Pick the most useful single line of eval output to surface as an error msg.
   Prefers a line carrying a recognised failure signature — kept in sync with
   `nrepl-eval-error?` so anything that *trips* detection can also be *named* —
   and otherwise falls back to the last non-blank line, so a detected failure is
   never surfaced as an empty/opaque message. Returns nil only for no output."
  [s]
  (let [lines (->> (str/split-lines (or s ""))
                   (map str/trim)
                   (remove str/blank?))
        signature? (fn [l]
                     (some #(str/includes? l %)
                           ["Execution error" "Syntax error" ":cause"
                            "FATAL ERROR" "DATABASE STARTUP FAILED"
                            "could not start" "permission denied for schema"]))
        match (or (first (filter signature? lines))
                  (last lines))]
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
      (let [detail (or (first-meaningful-line err) (first-meaningful-line out))]
        (throw (ex-info (str "nREPL evaluation failed (shell exit " (:exit result) ")"
                             (when detail (str " — " detail)))
                        {:port nrepl-port
                         :exit (:exit result)
                         :error err
                         :output out
                         :error-msg (or detail err)}))))
    (when (nrepl-eval-error? out)
      (let [project-name (some-> instance-id (str/split #"--") first)
            divergence   (flyway-divergence-message out project-name)
            detail       (first-meaningful-line out)
            ;; Self-describing message: a recognised divergence gives the exact
            ;; remedy; otherwise weave the actual cause into the message itself
            ;; (the TUI failure panel renders only ex-message), never the opaque
            ;; "eval returned exception" alone. Bare generic only when there is
            ;; genuinely no output to quote.
            msg          (or divergence
                             (when detail (str "nREPL evaluation threw — " detail))
                             "nREPL evaluation threw (eval returned exception)")]
        (throw (ex-info msg
                        {:port nrepl-port
                         :output out
                         :error-msg (or divergence detail)}))))
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

(defn ^{:malli/schema [:=> [:cat :map :any :map] :any]}
  start-app!
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

(defn ^{:malli/schema [:=> [:cat :map :any] :any]}
  stop-app!
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
