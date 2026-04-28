(ns nido.process
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]))

(defn process-alive? [pid]
  (zero? (:exit (shell {:continue true :out :string :err :string}
                       "kill" "-0" (str pid)))))

(defn stop-process! [pid]
  (when (process-alive? pid)
    (shell {:continue true} "kill" (str pid))
    (loop [attempt 0]
      (cond
        (not (process-alive? pid)) :stopped
        (>= attempt 20) (do (shell {:continue true} "kill" "-9" (str pid)) :killed)
        :else (do (Thread/sleep 100) (recur (inc attempt)))))))

(defn- kill-pg!
  "Run `kill -<sig> -- -<pgid>`, returning the exit code. Stderr is
   captured (not inherited) so the kernel's EPERM chatter for a vanished
   pgid doesn't reach the user's terminal — that condition is normal at
   teardown and is reported via the return value instead."
  [signal pgid]
  (:exit (shell {:continue true :out :string :err :string}
                "kill" (str "-" signal) "--" (str "-" pgid))))

(defn stop-process-group!
  "SIGTERM the whole process group pgid, escalating to SIGKILL after a
   grace period. Returns:
     :stopped — leader exited cleanly after SIGTERM
     :killed  — SIGKILL was required and signalled successfully
     :gone    — kill returned non-zero (Darwin returns EPERM when no
                member of the group can be signalled — typical when the
                pgid leader is already a zombie or every survivor has
                re-`setpgid`'d out). Treat as already-stopped: looping
                in this state would just spin against a dead group while
                bb itself stays blocked.
     nil      — leader pid was not alive on entry."
  [pgid]
  (let [leader-pid pgid]
    (when (process-alive? leader-pid)
      (if-not (zero? (kill-pg! "TERM" pgid))
        :gone
        (loop [attempt 0]
          (cond
            (not (process-alive? leader-pid)) :stopped
            (>= attempt 20)
            (if (zero? (kill-pg! "KILL" pgid)) :killed :gone)
            :else (do (Thread/sleep 100) (recur (inc attempt)))))))))

(defn port-free? [port]
  (try
    (with-open [socket (java.net.ServerSocket.)]
      (.setReuseAddress socket false)
      (.bind socket (java.net.InetSocketAddress. "127.0.0.1" port))
      true)
    (catch Exception _
      false)))

(defn tcp-open? [port]
  (try
    (with-open [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "127.0.0.1" port) 500)
      true)
    (catch Exception _
      false)))

(defn deterministic-port
  "Compute a deterministic port from a seed string within [low, high)."
  [seed low high]
  (let [h (-> seed hash long (bit-and 0x7fffffff))]
    (+ low (mod h (- high low)))))

(defn find-available-port [preferred-port max-attempts]
  (loop [port preferred-port
         attempts 0]
    (cond
      (port-free? port) port
      (>= attempts max-attempts)
      (throw (ex-info "Could not find free port"
                      {:preferred-port preferred-port :attempts max-attempts}))
      :else (recur (inc port) (inc attempts)))))

(defn quoted [s]
  (str "'" (str/replace s "'" "'\"'\"'") "'"))

(defn log-tail [path lines]
  (if-not (fs/exists? path)
    ""
    (->> (slurp path)
         str/split-lines
         (take-last lines)
         (str/join "\n"))))

(defn rss-bytes
  "Resident set size of a pid in bytes, via `ps -o rss= -p <pid>`. The
   `ps` column is KiB on Darwin and Linux, multiplied here by 1024.
   Returns nil if the pid is missing or ps fails."
  [pid]
  (when (pos-int? pid)
    (let [{:keys [exit out]} (shell {:continue true :out :string :err :string}
                                    "ps" "-o" "rss=" "-p" (str pid))]
      (when (zero? exit)
        (some-> out str/trim parse-long (* 1024))))))

(defn human-bytes
  "Format a byte count as a short human-readable string (e.g. \"1.8 GB\").
   Returns \"—\" when v is nil."
  [v]
  (if (nil? v)
    "—"
    (let [units ["B" "KB" "MB" "GB" "TB"]]
      (loop [n (double v) us units]
        (if (or (< n 1024.0) (= 1 (count us)))
          (format "%.1f %s" n (first us))
          (recur (/ n 1024.0) (rest us)))))))
