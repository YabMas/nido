(ns nido.platform.process
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]))

(def ^:private live-children
  "Agent processes this JVM started and is still waiting on.

   nido disables babashka.process's destroy-tree shutdown hook globally, because
   on macOS it parks forever after a child has already exited and leaves every
   `bb nido:*` hanging in shutdown. The cost of that cure is orphans: kill a
   review loop and its judge keeps running, keeps billing, and keeps writing to
   a run directory nothing will read.

   So the children are tracked instead, and one hook stops the ones that are
   still alive. It destroys only what is registered — never a process tree — so
   it cannot reproduce the hang it was written around."
  (atom #{}))

(defn ^{:malli/schema [:=> [:cat] :int]}
  stop-live-children!
  "Stop every registered child that is still running. Returns how many it
   stopped. Called from the shutdown hook, and directly by tests — a reaper that
   only runs at JVM exit is a reaper nothing can check."
  []
  (count
   (filter true?
           (for [^Process p @live-children]
             (try
               (when (.isAlive p)
                 (.destroy p)
                 ;; A judge given no chance to exit leaves a half-written answer
                 ;; file, which the next round would read as this round's.
                 (when-not (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS)
                   (.destroyForcibly p))
                 true)
               (catch Throwable _ nil))))))

(defonce ^:private stop-children-at-exit
  (delay
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop-live-children!))
    true))

(defn ^{:malli/schema [:=> [:cat :any [:=> [:cat] :any]] :any]}
  with-child-registered
  "Run `f` with `proc` registered for shutdown, deregistering however f ends.

   Takes the java.lang.Process rather than babashka's map so both launchers can
   use it — one holds a process map, the other only ever had a shell call."
  [^Process proc f]
  @stop-children-at-exit
  (swap! live-children conj proc)
  (try (f)
       (finally (swap! live-children disj proc))))

(defn ^{:malli/schema [:=> [:cat :int] :boolean]}
  process-alive? [pid]
  (zero? (:exit (shell {:continue true :out :string :err :string}
                       "kill" "-0" (str pid)))))

(defn ^{:malli/schema [:=> [:cat :int] :any]}
  stop-process! [pid]
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

(defn ^{:malli/schema [:=> [:cat :int] :any]}
  stop-process-group!
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

(defn ^{:malli/schema [:=> [:cat :int] :boolean]}
  port-free? [port]
  (try
    (with-open [socket (java.net.ServerSocket.)]
      (.setReuseAddress socket false)
      (.bind socket (java.net.InetSocketAddress. "127.0.0.1" port))
      true)
    (catch Exception _
      false)))

(defn ^{:malli/schema [:=> [:cat :int] :boolean]}
  tcp-open? [port]
  (try
    (with-open [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "127.0.0.1" port) 500)
      true)
    (catch Exception _
      false)))

(defn ^{:malli/schema [:=> [:cat :string :int :int] :int]}
  deterministic-port
  "Compute a deterministic port from a seed string within [low, high)."
  [seed low high]
  (let [h (-> seed hash long (bit-and 0x7fffffff))]
    (+ low (mod h (- high low)))))

(def browser-blocked-ports
  "Ports every mainstream browser refuses to connect to (Chromium's
   kRestrictedPorts; Firefox and Safari block near-identical lists).

   A session's app port landing here is a silent trap: the service binds,
   listens, and answers curl, so `session:status` reports app alive/listening
   and every nido surface calls the session healthy — while the browser says
   ERR_UNSAFE_PORT and nothing can be tested. The failure looks like a dead
   session and isn't one.

   Listed for the whole port space, not just nido's ranges, because
   `find-available-port` walks upward from its preferred port and can leave
   any range it starts in."
  #{1 7 9 11 13 15 17 19 20 21 22 23 25 37 42 43 53 69 77 79 87 95
    101 102 103 104 109 110 111 113 115 117 119 123 135 137 138 139 143
    161 179 389 427 465 512 513 514 515 526 530 531 532 540 548 554 556 563
    587 601 636 989 990 993 995
    1719 1720 1723 2049
    3659      ; apple-sasl
    4045      ; lockd
    4190      ; sieve
    4444      ; nv-video / krb524
    5060 5061 ; sip / sips
    6000      ; X11
    6566      ; sane-port
    6665 6666 6667 6668 6669 6679 6697  ; irc
    10080})   ; amanda

(defn ^{:malli/schema [:=> [:cat :int :int] :int]}
  find-available-port
  "The first port at or above `preferred-port` that is free AND openable by a
   browser. See `browser-blocked-ports` for why free is not sufficient."
  [preferred-port max-attempts]
  (loop [port preferred-port
         attempts 0]
    (cond
      (and (not (contains? browser-blocked-ports port))
           (port-free? port))
      port

      (>= attempts max-attempts)
      (throw (ex-info "Could not find free port"
                      {:preferred-port preferred-port :attempts max-attempts}))

      :else (recur (inc port) (inc attempts)))))

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  quoted [s]
  (str "'" (str/replace s "'" "'\"'\"'") "'"))

(defn ^{:malli/schema [:=> [:cat :string :int] :string]}
  log-tail [path lines]
  (if-not (fs/exists? path)
    ""
    (->> (slurp path)
         str/split-lines
         (take-last lines)
         (str/join "\n"))))

(defn ^{:malli/schema [:=> [:cat :int] [:maybe :int]]}
  rss-bytes
  "Resident set size of a pid in bytes, via `ps -o rss= -p <pid>`. The
   `ps` column is KiB on Darwin and Linux, multiplied here by 1024.
   Returns nil if the pid is missing or ps fails."
  [pid]
  (when (pos-int? pid)
    (let [{:keys [exit out]} (shell {:continue true :out :string :err :string}
                                    "ps" "-o" "rss=" "-p" (str pid))]
      (when (zero? exit)
        (some-> out str/trim parse-long (* 1024))))))

(defn ^{:malli/schema [:=> [:cat] [:maybe :double]]}
  load-average
  "The machine's 1-minute load average, via `sysctl -n vm.loadavg`. Returns nil
   if sysctl is missing or its output does not parse.

   Shelled out rather than read from the JVM: babashka does not expose
   java.lang.management, so the ManagementFactory route the same code would take
   on the JVM is unavailable here.

   The 1-minute figure specifically — a reading meant to be interpreted next to
   an event that just happened, where the 5- and 15-minute averages still carry
   load that had drained before it."
  []
  (let [{:keys [exit out]} (shell {:continue true :out :string :err :string}
                                  "sysctl" "-n" "vm.loadavg")]
    (when (zero? exit)
      ;; `{ 1.83 2.05 2.11 }` — braces and all, so take the first numeric field.
      (some-> (re-find #"\d+\.\d+" (str out)) parse-double))))

(defn ^{:malli/schema [:=> [:cat [:maybe :int]] :string]}
  human-bytes
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
