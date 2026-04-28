(ns nido.session.services.process
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.core :as core]
   [nido.io :as io]
   [nido.process :as proc]
   [nido.session.service :as service]
   [nido.session.state :as state]))

(defn- wait-for-port-file! [port-file pid timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [next-log-at (System/currentTimeMillis)]
      (cond
        (not (proc/process-alive? pid)) nil
        (> (System/currentTimeMillis) deadline) nil
        :else
        (let [now (System/currentTimeMillis)
              _ (when (>= now next-log-at)
                  (core/log-step "Waiting for port file...")
                  nil)
              port (try
                     (some-> (io/read-edn port-file)
                             str
                             str/trim
                             parse-long)
                     (catch Exception _ nil))]
          (if (pos-int? port)
            port
            (do
              (Thread/sleep 250)
              (recur (if (>= now next-log-at)
                       (+ now 5000)
                       next-log-at)))))))))

(defn- resolve-command
  "Return the shell command string this process service should exec.
   Prefers :command-template (vector of pre-substituted tokens;
   blank/nil tokens dropped) over :command (literal string). Tokens may
   still contain whitespace — they are joined with single spaces; quoting
   is the author's responsibility."
  [service-def]
  (if-let [tmpl (:command-template service-def)]
    (str/join " " (remove (fn [t] (or (nil? t) (and (string? t) (str/blank? t)))) tmpl))
    (:command service-def)))

(defmethod service/start-service! :process
  [service-def ctx _opts]
  (let [{:keys [port-file port-timeout-ms]
         svc-name :name
         :or {port-timeout-ms 120000}} service-def
        command (resolve-command service-def)
        project-name (get-in ctx [:session :project-name])
        project-dir (get-in ctx [:session :project-dir])
        instance-id (or (get-in ctx [:session :instance-id]) project-name)
        log-path (state/log-file instance-id svc-name)]

    (when (str/blank? command)
      (throw (ex-info ":process service missing :command or :command-template"
                      {:service-name svc-name})))

    (fs/create-dirs (state/log-dir instance-id))

    ;; Delete existing port file
    (when port-file
      (fs/delete-if-exists (str (fs/path project-dir port-file))))

    ;; Start background process. :shutdown nil disables the destroy-tree
    ;; JVM shutdown hook babashka.process/shell registers by default — that
    ;; hook hangs the bb process at exit when the spawned bash leaves a
    ;; long-running detached descendant (the JVM here is intentionally
    ;; supposed to outlive bb).
    (let [cmd (str "nohup " command " > "
                   (proc/quoted log-path)
                   " 2>&1 & echo $!")
          result (shell {:continue true :out :string :err :string
                         :dir project-dir :shutdown nil}
                        "bash" "-lc" cmd)
          pid (some-> (:out result) str/trim parse-long)]
      (when-not (zero? (:exit result))
        (throw (ex-info (str "Failed to start " (name svc-name))
                        {:error (:err result)})))
      (when-not (pos-int? pid)
        (throw (ex-info (str "Failed to parse pid for " (name svc-name))
                        {:output (:out result)})))
      (core/log-step (str "Started " (name svc-name) " process (pid " pid ")"))

      ;; Optionally wait for port file
      (let [port (when port-file
                   (let [pf (str (fs/path project-dir port-file))
                         p (wait-for-port-file! pf pid port-timeout-ms)]
                     (when-not p
                       (proc/stop-process! pid)
                       (throw (ex-info (str "Timed out waiting for " port-file)
                                       {:timeout-ms port-timeout-ms
                                        :pid pid
                                        :log-tail (proc/log-tail log-path 20)})))
                     (core/log-step (str (name svc-name) " available on port " p))
                     p))]
        {:state {:pid pid :port port :log-path log-path}
         :context (cond-> {:pid pid}
                    port (assoc :port port))}))))

(defmethod service/stop-service! :process
  [_service-def saved-state]
  (let [{:keys [pid]} saved-state]
    (when (and (pos-int? pid) (proc/process-alive? pid))
      (core/log-step (str "Stopping process (pid " pid ")"))
      (proc/stop-process! pid))))

(defmethod service/service-status :process
  [_service-def saved-state]
  (let [{:keys [pid port]} saved-state]
    {:alive? (and (pos-int? pid) (proc/process-alive? pid))
     :listening? (and (pos-int? port) (proc/tcp-open? port))
     :port port
     :pid pid}))
