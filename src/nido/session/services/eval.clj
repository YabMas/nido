(ns nido.session.services.eval
  (:require
   [babashka.process :refer [shell]]
   [nido.core :as core]
   [nido.process :as proc]
   [nido.session.context :as ctx]
   [nido.session.service :as service]))

(defn- eval-on-repl! [nrepl-port timeout-ms form]
  (let [result (shell {:continue true :out :string :err :string}
                      "clj-nrepl-eval"
                      "-p" (str nrepl-port)
                      "--timeout" (str timeout-ms)
                      form)]
    (when-not (zero? (:exit result))
      (throw (ex-info "nREPL evaluation failed"
                      {:port nrepl-port
                       :form form
                       :error (:err result)
                       :output (:out result)})))
    (:out result)))

(defn- wait-for-app-port! [app-port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [next-log-at (System/currentTimeMillis)]
      (cond
        (proc/tcp-open? app-port) true
        (> (System/currentTimeMillis) deadline) false
        :else
        (let [now (System/currentTimeMillis)]
          (when (>= now next-log-at)
            (core/log-step (str "Waiting for app on http://localhost:" app-port "...")))
          (Thread/sleep 250)
          (recur (if (>= now next-log-at)
                   (+ now 5000)
                   next-log-at)))))))

(defmethod service/start-service! :eval
  [service-def session-ctx _opts]
  (let [{:keys [repl-service start-form eval-timeout-ms port-range health-check-timeout-ms]
         :or {eval-timeout-ms 180000 health-check-timeout-ms 45000
              port-range [3100 5100]}} service-def
        project-dir (get-in session-ctx [:session :project-dir])
        ;; Look up REPL port from context via :repl-service reference
        repl-port (get-in session-ctx [(keyword repl-service) :port])
        _ (when-not repl-port
            (throw (ex-info "REPL port not found in context"
                            {:repl-service repl-service
                             :available-keys (keys session-ctx)})))
        ;; Allocate app port
        [low high] port-range
        preferred-port (proc/deterministic-port project-dir low high)
        app-port (proc/find-available-port preferred-port (- high low))
        ;; Build local context for template substitution in forms
        local-ctx (assoc session-ctx :app {:port app-port})
        resolved-start-form (ctx/substitute-value local-ctx start-form)]

    (core/log-step (str "Selected app port " app-port))
    (core/log-step "Starting application via nREPL eval...")
    (eval-on-repl! repl-port eval-timeout-ms resolved-start-form)

    (core/log-step "Eval returned, waiting for HTTP port...")
    (let [app-ready? (wait-for-app-port! app-port health-check-timeout-ms)]
      (when-not app-ready?
        (println "warning: app port did not open before timeout, session may still be starting"))
      (when app-ready?
        (core/log-step (str "App is reachable at http://localhost:" app-port)))
      {:state {:app-port app-port :nrepl-port repl-port}
       :context {:port app-port :url (str "http://localhost:" app-port)}})))

(defmethod service/stop-service! :eval
  [service-def saved-state]
  (let [{:keys [nrepl-port]} saved-state
        stop-form (:stop-form service-def)]
    (when (and stop-form (pos-int? nrepl-port) (proc/tcp-open? nrepl-port))
      (core/log-step "Stopping application via nREPL eval...")
      (try
        (eval-on-repl! nrepl-port 30000 stop-form)
        (catch Exception e
          (println "warning: could not stop app via nREPL:" (ex-message e)))))))

(defmethod service/service-status :eval
  [_service-def saved-state]
  (let [{:keys [app-port]} saved-state]
    {:alive? (and (pos-int? app-port) (proc/tcp-open? app-port))
     :listening? (and (pos-int? app-port) (proc/tcp-open? app-port))
     :port app-port}))
