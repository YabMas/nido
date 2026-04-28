(ns nido.ci.healthcheck
  "Healthcheck probe evaluator. Supports :port-ready (TCP connect) and
   :command (exit-0 = success) probes.

   `await-ready!` polls a probe until it reports :consecutive-successes in
   a row or a deadline is hit. The target process is monitored for early
   death so a crashing service fails fast instead of timing out."
  (:require
   [nido.process :as proc]))

(defn- resolve-port [{:keys [port port-from-env]} env]
  (or port
      (when port-from-env
        (some-> (get env port-from-env) parse-long))))

(defn- run-command-probe
  "Run a shell command with the given env overrides; success = exit 0."
  [cmd env]
  (let [pb (ProcessBuilder. ["bash" "-lc" cmd])
        env-map (.environment pb)]
    (doseq [[k v] env]
      (.put env-map (str k) (str v)))
    (.redirectOutput pb (java.io.File. "/dev/null"))
    (.redirectError  pb (java.io.File. "/dev/null"))
    (let [proc (.start pb)
          exit (.waitFor proc)]
      (zero? exit))))

(defn probe-once
  "Evaluate a probe once. Returns true on success, false on failure."
  [{:keys [kind command] :as hc} env]
  (case kind
    :port-ready (boolean (when-let [p (resolve-port hc env)]
                           (proc/tcp-open? p)))
    :command    (if (and (string? command) (seq command))
                  (try (run-command-probe command env)
                       (catch Exception _ false))
                  false)))

(defn await-ready!
  "Poll the probe until success threshold is met, or deadline hits, or the
   target process dies, or an abort-promise is delivered.
   Returns one of :ready :timed-out :process-died :aborted."
  [{:keys [healthcheck env pid abort-promise defaults]}]
  (let [interval (or (:interval-ms healthcheck)
                     (:healthcheck-interval-ms defaults) 1000)
        timeout  (or (:timeout-ms healthcheck)
                     (:healthcheck-timeout-ms defaults) 60000)
        required (or (:consecutive-successes healthcheck)
                     (:consecutive-successes defaults) 2)
        deadline (+ (System/currentTimeMillis) timeout)]
    (loop [successes 0]
      (cond
        (and abort-promise (realized? abort-promise))
        :aborted

        ;; Threshold reached first — a probe success should not be
        ;; invalidated by a racing process-exit check.
        (>= successes required)
        :ready

        (and pid (not (proc/process-alive? pid)))
        :process-died

        (> (System/currentTimeMillis) deadline)
        :timed-out

        :else
        (do (Thread/sleep interval)
            (recur (if (probe-once healthcheck env) (inc successes) 0)))))))
