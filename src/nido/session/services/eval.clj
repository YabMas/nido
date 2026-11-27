(ns nido.session.services.eval
  "The application, started and stopped by evaluating the project's declared forms over the
   session's nREPL rather than run as a process of its own. The :process service owns the JVM;
   this service owns the application's mount state inside it.

   `start-service!` reserves the app port and boots the app, so a runtime start brings the app up
   with it — unless the JVM is declared `:on-demand`, when no JVM runs yet: then it only reserves
   the ports, and the :process service's front starts the app after the JVM
   (`nido.session.front`). `stop-service!` stops the app likewise only when its JVM is not on
   demand, and then over the JVM's own socket, never through the front, which would start a JVM
   to stop an app.

   `start-app!` / `stop-app!` reach the nREPL over its TCP port, or over a filesystem socket when
   the saved state names one (an on-demand JVM's)."
  (:require
   [babashka.fs :as fs]
   [bencode.core :as bencode]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.platform.process :as proc]
   [nido.session.context :as ctx]
   [nido.session.service :as service]
   [nido.session.state :as state]))

(defn- friendly-host
  "Build a per-runtime hostname under *.localhost so each runtime gets its
   own browser cookie jar and a recognizable URL.
     - main checkout : <project-name>.localhost
     - otherwise     : <runtime-key>.<project-name>.localhost, a `/` in the key written `-`
   `*.localhost` resolves to 127.0.0.1 automatically (RFC 6761) on macOS
   and modern browsers, so no /etc/hosts entry is needed."
  [project-name runtime-key]
  (cond
    (and runtime-key project-name)
    (str (str/replace runtime-key "/" "-") "." project-name ".localhost")

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
   `output` is both of its streams: the eval's own printing arrives on stdout,
   but a thrown exception arrives on STDERR — `Execution error (X) at …` or
   `Syntax error compiling at …` from a JVM nREPL — while an error the app
   catches and prints itself (`DATABASE STARTUP FAILED`, an error map with
   `:cause`) stays on stdout. Matching signatures rather than any stderr at all
   is deliberate: a healthy boot writes JVM warnings there too."
  [output]
  (let [s (or output "")]
    (some #(str/includes? s %)
          ["Execution error" "Syntax error" ":cause" "FATAL ERROR"
           "DATABASE STARTUP FAILED" "could not start [#'" "permission denied for schema"])))

(def ^:private eval-timeout-signature
  "What clj-nrepl-eval prints when `--timeout` elapses: it sends nREPL an
   :interrupt and exits 0, with this notice on STDOUT. Matching it is the only
   way to tell an interrupted eval from one that returned."
  "Timeout hit, sending nREPL :interrupt")

(defn- eval-timed-out?
  [output]
  (str/includes? (or output "") eval-timeout-signature))

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

(defn- local-bin-dir [] (str (fs/path (fs/home) ".local" "bin")))

(defn- running-bb-dir
  "The directory of the babashka binary running nido, when the platform says."
  []
  (some-> (java.lang.ProcessHandle/current) .info .command (.orElse nil)
          fs/parent str))

(defn- nrepl-eval-command
  "Where `clj-nrepl-eval` is, and the PATH to run it under. It is installed in
   ~/.local/bin (bbin), which a login shell puts on PATH and a bare
   `ssh host '<cmd>'` does not — a session started that way brought its JVM up
   and then could not exec the boot eval. So the lookup falls back to
   ~/.local/bin, and the child's PATH also carries the running bb's directory,
   because the script's `#!/usr/bin/env bb` resolves bb from that PATH.
   In neither place it answers the bare name, so the exec error names it."
  []
  (let [path   (or (System/getenv "PATH") "")
        exe    (or (some-> (fs/which "clj-nrepl-eval") str)
                   (let [f (fs/path (local-bin-dir) "clj-nrepl-eval")]
                     (when (fs/executable? f) (str f)))
                   "clj-nrepl-eval")
        extras (remove (set (str/split path #":"))
                       (distinct (remove nil? [(some-> exe fs/parent str)
                                               (running-bb-dir)])))]
    {:exe  exe
     :path (str/join ":" (cons path extras))}))

(defn- bytes->str [v]
  (if (bytes? v) (String. ^bytes v "UTF-8") (str v)))

(defn- socket-connectable?
  "Whether an nREPL server answers on filesystem socket `path`."
  [path]
  (boolean
   (when (and path (fs/exists? path))
     (try
       (with-open [_ (java.nio.channels.SocketChannel/open
                      (java.net.UnixDomainSocketAddress/of (str path)))]
         true)
       (catch Exception _ false)))))

(defn- eval-over-socket
  "Evaluate `form` on the nREPL at filesystem socket `path`, as clj-nrepl-eval would over TCP:
   {:exit :out :err}, the eval's printed output and values on :out and what it wrote to stderr —
   a thrown exception's `Execution error …` among it — on :err. clj-nrepl-eval speaks TCP only,
   which is why this exists: an on-demand JVM's nREPL listens on no TCP port. When `timeout-ms`
   elapses — the session's clone included — the eval is interrupted, the connection closed and
   :out carries `eval-timeout-signature`, as clj-nrepl-eval reports it."
  [path timeout-ms form]
  (try
    (with-open [ch (java.nio.channels.SocketChannel/open
                    (java.net.UnixDomainSocketAddress/of (str path)))]
      (let [out  (java.io.BufferedOutputStream. (java.nio.channels.Channels/newOutputStream ch))
            in   (java.io.PushbackInputStream. (java.nio.channels.Channels/newInputStream ch))
            send (fn [m] (bencode/write-bencode out m) (.flush out))
            recv (fn [] (bencode/read-bencode in))
            sid  (atom nil)
            outs (StringBuilder.)
            errs (StringBuilder.)
            done (future
                   (send {"op" "clone" "id" "nido-clone"})
                   (let [s (loop [] (let [m (recv)]
                                      (or (some-> (get m "new-session") bytes->str) (recur))))]
                     (send {"op" "eval" "code" form "id" "nido-eval" "session" s})
                     (reset! sid s))
                   (loop []
                     (let [m (recv)]
                       (when-let [v (get m "out")]   (.append outs (bytes->str v)))
                       (when-let [v (get m "value")] (.append outs (str (bytes->str v) "\n")))
                       (when-let [v (get m "err")]   (.append errs (bytes->str v)))
                       (when-let [v (get m "ex")]
                         (when-not (str/includes? (str errs) "Execution error")
                           (.append errs (str "Execution error (" (bytes->str v) ")\n"))))
                       (if (some #(= "done" (bytes->str %)) (get m "status"))
                         :done
                         (recur)))))
            r    (try (deref done timeout-ms ::timeout)
                      (catch java.util.concurrent.ExecutionException e (throw (or (.getCause e) e))))]
        (when (= ::timeout r)
          ;; Only once the eval was sent, so nothing else is writing; a JVM that never answered
          ;; the clone has no eval to interrupt, and the close below ends the wait on it.
          (when-let [s @sid]
            (try (send {"op" "interrupt" "session" s "interrupt-id" "nido-eval" "id" "nido-int"})
                 (catch Exception _ nil)))
          (future-cancel done)
          (.append outs (str "\n" eval-timeout-signature "\n")))
        {:exit 0 :out (str outs) :err (str errs)}))
    (catch Exception e
      {:exit 1 :out "" :err (str "Could not reach nREPL on socket " path ": " (ex-message e))})))

(defn- eval-on-repl!
  "Send `form` to the nREPL `nrepl` names — a TCP port, or a filesystem socket's path — capturing
   both stdout and stderr. Persists the exchange to the session's eval.log (visible in
   the UI). Throws if the shell itself failed, if `timeout-ms` elapsed and
   the eval was interrupted, OR if the eval returned
   content that looks like a thrown exception — with the first
   meaningful error line attached as :error-msg so callers can surface
   it on the :failed state."
  [instance-id project-name nrepl timeout-ms form]
  (let [result (if (string? nrepl)
                 (eval-over-socket nrepl timeout-ms form)
                 (let [{:keys [exe path]} (nrepl-eval-command)]
                   (shell {:continue true :out :string :err :string
                           :extra-env {"PATH" path}}
                          exe
                          "-p" (str nrepl)
                          "--timeout" (str timeout-ms)
                          form)))
        nrepl-port nrepl
        out (:out result)
        err (:err result)
        ;; A thrown exception arrives on stderr. Read stdout alone and a boot
        ;; that threw passes as one that returned, and start-app! waits out
        ;; its health check on an app that will never listen.
        output (str out "\n" err)]
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
    ;; Checked before the error signatures: an interrupt lands wherever the
    ;; boot happened to be — commonly mid-`require` — and leaves the JVM with
    ;; namespaces half loaded. Passed as a success, the session stays up on that
    ;; JVM and the next boot in it fails with a compile error that names code
    ;; which is fine. Thrown, the engine's rollback stops the JVM instead.
    (when (eval-timed-out? output)
      (let [msg (str "nREPL evaluation timed out after " (quot timeout-ms 1000)
                     "s and was interrupted — the JVM may hold half-loaded"
                     " namespaces, so start it fresh rather than evaluating"
                     " in it again")]
        (throw (ex-info msg
                        {:port nrepl-port
                         :timeout-ms timeout-ms
                         :timed-out? true
                         :output out
                         :error err
                         :error-msg msg}))))
    (when (nrepl-eval-error? output)
      (let [divergence   (flyway-divergence-message output project-name)
            detail       (first-meaningful-line output)
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
                         :error err
                         :error-msg (or divergence detail)}))))
    out))

(defn- stale-working-copy?
  "True when `project-dir` is a jj workspace whose working copy is stale:
   another workspace rewrote its commit — a restack of the layers it sits on —
   and its files on disk were never updated. The REPL then loads code that is
   no longer the branch, and the failure it reports is that code's, not the
   branch's.

   Same signature `lifecycle/workspace-stale?` reads, which this namespace
   cannot reach: lifecycle requires the engine, which requires this. Asking
   runs one ordinary jj command in the workspace, so a live one gets the
   snapshot any jj command there would take."
  [project-dir]
  (boolean
   (when (and project-dir (fs/exists? (fs/path project-dir ".jj")))
     (let [r (shell {:continue true :out :string :err :string :dir (str project-dir)}
                    "jj" "log" "-r" "@" "--no-graph" "-T" "\"\"")]
       (and (not (zero? (:exit r)))
            (str/includes? (str (:err r)) "working copy is stale"))))))

(defn- stale-working-copy-note
  "The sentence that goes ahead of an eval failure in a stale workspace. It
   comes FIRST because it changes what the rest means: a Flyway checksum
   mismatch there compares the shared cluster with migration files the branch
   may already have renumbered, so its remedy — a cluster reset — would wipe
   shared data and fix nothing."
  [project-dir]
  (str "This worktree's jj working copy is stale — its files are not the branch"
       " (another workspace rewrote the commits it sits on), and the failure below"
       " may be theirs alone. Run `jj workspace update-stale` in " project-dir
       " and start again; edits made since it went stale are kept as a divergent"
       " copy of the working-copy commit rather than left on disk. "))

(defn- wait-for-app-port! [host app-port timeout-ms]
  (let [deadline (+ (proc/awake-ms) timeout-ms)]
    (loop [next-log-at (proc/awake-ms)]
      (cond
        (proc/tcp-open? app-port) true
        (> (proc/awake-ms) deadline) false
        :else
        (let [now (proc/awake-ms)]
          (when (>= now next-log-at)
            (core/log-step (str "Waiting for app on http://" host ":" app-port "...")))
          (Thread/sleep 250)
          (recur (if (>= now next-log-at)
                   (+ now 5000)
                   next-log-at)))))))

;; ---------------------------------------------------------------------------
;; Public: app toggle (called from lifecycle + UI, NOT from start-service!)
;; ---------------------------------------------------------------------------

(defn- nrepl-of
  "Where the nREPL `saved-state` names is: its filesystem socket when it has one (an on-demand
   JVM's), else its TCP port."
  [{:keys [socket nrepl-port]}]
  (or socket nrepl-port))

(defn- nrepl-answers? [nrepl]
  (if (string? nrepl)
    (socket-connectable? nrepl)
    (and (pos-int? nrepl) (proc/tcp-open? nrepl))))

(defn- bound-port
  "The port the app itself binds: `:bind-port` when its JVM is on demand — the front holds the
   session's app port, `:app-port` — else the session's app port."
  [{:keys [bind-port app-port]}]
  (or bind-port app-port))

(defn ^{:malli/schema [:=> [:cat :map :any :map] :any]}
  start-app!
  "Eval the service's :start-form over nREPL and wait for the port the app binds to open.
   Idempotent: if the app is already listening, returns immediately without re-evaling. The form
   is substituted with `app.port` and `app.bind-port` both naming the port the app binds."
  [service-def saved-state session-ctx]
  (let [{:keys [start-form eval-timeout-ms health-check-timeout-ms]
         :or {eval-timeout-ms 180000 health-check-timeout-ms 180000}} service-def
        {:keys [host]} saved-state
        nrepl    (nrepl-of saved-state)
        app-port (bound-port saved-state)]
    (cond
      (not start-form)
      (throw (ex-info "Service has no :start-form" {:service (:name service-def)}))

      (not (nrepl-answers? nrepl))
      (throw (ex-info "nREPL is not listening — session may not be up"
                      {:nrepl-port nrepl}))

      (proc/tcp-open? app-port)
      (do (core/log-step (str "App already listening on " host ":" app-port)) true)

      :else
      (let [instance-id (or (:instance-id saved-state)
                            (get-in session-ctx [:session :instance-id]))
            local-ctx (assoc session-ctx :app {:port app-port :bind-port app-port})
            resolved-start-form (ctx/substitute-value local-ctx start-form)]
        (core/log-step (str "Starting app on " host ":" app-port " (via nREPL eval)..."))
        (try
          (eval-on-repl! instance-id (get-in session-ctx [:session :project-name])
                         nrepl eval-timeout-ms resolved-start-form)
          (catch clojure.lang.ExceptionInfo e
            (let [project-dir (get-in session-ctx [:session :project-dir])]
              (throw (if (stale-working-copy? project-dir)
                       (let [note (stale-working-copy-note project-dir)]
                         (ex-info (str note (ex-message e))
                                  (-> (ex-data e)
                                      (assoc :stale-working-copy? true)
                                      (update :error-msg #(str note %)))
                                  e))
                       e)))))
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
        {:keys [host]} saved-state
        nrepl    (nrepl-of saved-state)
        app-port (bound-port saved-state)]
    (cond
      (not stop-form)
      (core/log-step "Service has no :stop-form — skipping app stop")

      (not (nrepl-answers? nrepl))
      (core/log-step "nREPL not listening — nothing to stop")

      (not (proc/tcp-open? app-port))
      (core/log-step (str "App not listening on " host ":" app-port " — already idle"))

      :else
      (let [instance-id (:instance-id saved-state)]
        (core/log-step (str "Stopping app on " host ":" app-port " (via nREPL eval)..."))
        (try
          (eval-on-repl! instance-id (:project-name saved-state) nrepl 30000 stop-form)
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
        repl (get session-ctx (keyword repl-service))
        repl-port (:port repl)
        _ (when-not repl-port
            (throw (ex-info "REPL port not found in context"
                            {:repl-service repl-service
                             :available-keys (keys session-ctx)})))
        pre-allocated (when svc-name (get session-ctx (keyword svc-name)))
        app-port (or (:port pre-allocated)
                     (let [[low high] port-range
                           preferred-port (proc/deterministic-port project-dir low high)]
                       (proc/find-available-port preferred-port (- high low))))
        on-demand? (boolean (:on-demand repl))
        project-name (get-in session-ctx [:session :project-name])
        instance-id (get-in session-ctx [:session :instance-id])
        host (friendly-host project-name (get-in session-ctx [:session :runtime-key]))
        url (str "http://" host ":" app-port)
        saved-state (cond-> {:app-port app-port :nrepl-port repl-port :host host
                             :instance-id instance-id :project-name project-name}
                      on-demand? (assoc :on-demand? true
                                        :bind-port (:bind-port pre-allocated)
                                        :socket (:socket repl)))]
    (if on-demand?
      ;; No JVM runs yet: the front starts the app once the JVM it starts answers.
      (core/log-step (str "App port reserved on " host ":" app-port
                          " — the app starts with its on-demand JVM"))
      (do
        (core/log-step (str "App port reserved on " host ":" app-port " — starting app"))
        ;; Any exception here propagates → whole session start fails → engine
        ;; tears down upstream services. That's the desired contract.
        (start-app! service-def saved-state session-ctx)))
    {:state saved-state
     :context (cond-> {:port app-port
                       :bind-port (or (:bind-port saved-state) app-port)
                       :host host
                       :url url}
                on-demand? (assoc :on-demand true))}))

(defmethod service/stop-service! :eval
  ;; Called at session teardown. Runs the stop-form if the app happens to
  ;; be running — otherwise a no-op. An on-demand JVM's app is reached over the JVM's own socket,
  ;; which answers only while the JVM runs, so a stopped JVM is not started to stop its app.
  [service-def saved-state]
  (stop-app! service-def saved-state))

(defmethod service/service-status :eval
  [_service-def saved-state]
  (let [{:keys [app-port]} saved-state
        listening? (and (pos-int? app-port) (proc/tcp-open? app-port))
        running?   (let [p (bound-port saved-state)] (and (pos-int? p) (proc/tcp-open? p)))]
    {:alive? listening?
     :listening? listening?
     :port app-port
     :app-state (if running? :running :idle)}))
