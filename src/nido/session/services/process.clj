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

(defmethod service/start-service! :process
  [service-def ctx _opts]
  (let [{:keys [command port-file port-timeout-ms]
         svc-name :name
         :or {port-timeout-ms 120000}} service-def
        project-name (get-in ctx [:session :project-name])
        project-dir (get-in ctx [:session :project-dir])
        log-path (state/log-file project-name svc-name)]

    (fs/create-dirs (state/log-dir project-name))

    ;; Delete existing port file
    (when port-file
      (fs/delete-if-exists (str (fs/path project-dir port-file))))

    ;; Start background process
    (let [cmd (str "nohup " command " > "
                   (proc/quoted log-path)
                   " 2>&1 & echo $!")
          result (shell {:continue true :out :string :err :string :dir project-dir}
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
