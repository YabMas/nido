(ns nido.session.front
  "The front of an on-demand JVM: one detached process per runtime that holds the session's nREPL
   and app ports while no JVM runs behind them, and starts the JVM when a client first sends it a
   byte.

   It hides whether a JVM runs. A connection is accepted and read; one that closes having sent
   nothing — a port probe, `tcp-open?` — starts nothing. The first byte starts the runtime's
   on-demand group once, under the runtime's lifecycle lock: the JVM, its nREPL on a filesystem
   socket, then each app on it, by its start form over that socket. What the client sent meanwhile
   is held, and relayed in order once its target answers — the nREPL as soon as the JVM's socket
   does, an app port once its app does — and the replies come back unchanged, so the client speaks
   to the front as it would to the JVM. A client that gives up first sees its call fail; the start
   goes on.

   Once no byte has passed through the nREPL port for the declared idle period and no connection
   is open through any port, the group is stopped — the app's stop form, then the JVM — and
   nothing else: the runtime, its ports and every other service stay, and the next byte starts
   the JVM again.

   Every start is recorded in the project's cold-start log (`nido.session.cold-starts`), and one
   that fails is kept under ~/.nido/failures/ as a failed session start, the connections it held
   closed.

   The front lives with its runtime: it exits when the service's stop removes its config file,
   when the runtime record it was started for goes, or when that record names another front."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.process :as proc]
   [nido.session.cold-starts :as cold-starts]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.services.eval :as eval-svc]
   [nido.session.services.process :as process]
   [nido.session.state :as state])
  (:import
   [java.net InetSocketAddress ServerSocket Socket UnixDomainSocketAddress]
   [java.nio ByteBuffer]
   [java.nio.channels SocketChannel]))

(def ^:private tick-ms 2000)

(def ^:private unowned-limit-ms
  "How long a front waits for the runtime it was started for to be recorded as running it — longer
   than any runtime start, since the record is written only after every service is up."
  (* 20 60 1000))

;; ---------------------------------------------------------------------------
;; Runtime facts the front keeps in step with its JVM
;; ---------------------------------------------------------------------------

(defn- port-file [{:keys [project-dir port-file]}]
  (when (and project-dir port-file) (str (fs/path project-dir port-file))))

(defn- assert-port-file!
  "Make the runtime's `.nrepl-port` name the front's port. The JVM's nREPL writes the file too —
   empty, since it listens on a socket — and removes it at exit, so this is asserted again on
   every tick rather than once."
  [cfg]
  (when-let [f (port-file cfg)]
    (let [want (str (:nrepl-port cfg))]
      (when (and (fs/exists? (fs/parent f))
                 (not= want (try (str/trim (slurp f)) (catch Exception _ nil))))
        (try (spit f want) (catch Exception _ nil))))))

(defn- note-jvm!
  "Record that the runtime's on-demand JVM runs as `pid`, or that none does (nil): in the runtime
   record, and on every registry entry of a session on it, which the fleet charges memory by."
  [{:keys [instance-id service]} pid]
  ;; A read-modify-write under the lock `state/write-session!` writes the record under: other
  ;; nido processes write it (a join, a stop), and one's write landing between this read and this
  ;; write would be lost. A record already saying so is not rewritten.
  (let [path (state/session-state-file instance-id)]
    (io/with-file-lock (io/lock-path-for path)
      #(when-let [s (state/read-session instance-id)]
         (let [s' (-> s
                      (assoc-in [:service-states service :jvm-pid] pid)
                      (assoc-in [:context (keyword service) :pid] pid))]
           (when (not= s s') (io/write-edn! path s'))))))
  (state/update-runtime-entries! instance-id
                                 #(if pid (assoc % :repl-pid pid) (dissoc % :repl-pid))))

(defn- app-services
  "Each app service on this JVM, with the state its runtime saved for it."
  [{:keys [apps]} session]
  (for [{:keys [service]} apps
        :let [d (some #(when (= service (:name %)) %) (:service-defs session))
              s (get (:service-states session) service)]
        :when (and d s)]
    [d s]))

(defn- session-name [cfg session]
  (or (:name session) (first (vals (state/members session))) (:instance-id cfg)))

;; ---------------------------------------------------------------------------
;; The on-demand group: one start, one stop, under the runtime's lock
;; ---------------------------------------------------------------------------

(defn- now-ms [] (System/currentTimeMillis))

(defn- record-start!
  "Append the start that ended at `ended-at` — the instant its triggering port was ready, or the
   instant it failed — to the project's cold-start log, and mark it recorded, so a start is
   recorded once. Called at that instant, so every field reads as it was then. A failure to write
   it is logged, never thrown, so it cannot take the front down."
  [front outcome cause ended-at]
  (swap! front assoc-in [:start :recorded?] true)
  (let [{:keys [cfg start]} @front
        session (state/read-session (:instance-id cfg))]
    (try
      (cold-starts/record!
       (cond-> {:project      (:project-name cfg)
                :session      (session-name cfg session)
                :instance-id  (:instance-id cfg)
                :port         (:port start)
                :requested-at (:requested-at start)
                :ended-at     ended-at
                ;; Read from the connection that triggered the start, however long the start
                ;; waited to begin: its own record of having ended is the one source of it.
                :outcome      (if (and (= :ready outcome) @(get-in start [:first :gave-up?]))
                                :ready-after-give-up
                                outcome)
                ;; No start through the front is admitted against the fleet's memory budget.
                :admission    :bypassed}
         cause (assoc :cause cause)))
      (catch Exception e
        (core/log-step (str "warning: could not record the cold start: " (ex-message e)))))))

(defn- keep-failure!
  "Keep failed start `e` under ~/.nido/failures/, as a failed session start whose origin is the
   on-demand trigger, so it reaches session recovery."
  [front e]
  (let [{:keys [cfg start]} @front
        session (state/read-session (:instance-id cfg))]
    (failure/record! {:verb              :on-demand
                      :session           (session-name cfg session)
                      :project           (:project-name cfg)
                      :instance-id       (:instance-id cfg)
                      :opts              {}
                      :worktree-existed? true
                      :origin            {:kind :on-demand :port (:port start)}}
                     e)))

(declare readiness)

(defn- jvm-runs?
  "Whether the JVM the front spawned as `pid` still runs — the one reading every decision to keep,
   clear, charge or trust a pid derives from. A pid names whatever process holds it now, so the
   process under it must have started when the spawn's did (`:jvm-started`, read at the spawn); a
   later start is another process given the recycled number. Where the OS will not say when either
   started, it is read as running while something holds the pid, as nothing better can be told."
  [front pid]
  (boolean
   (and pid (proc/process-alive? pid)
        (let [spawned (when (= pid (:jvm-pid @front)) (:jvm-started @front))
              now     (proc/process-started pid)]
          (or (not (int? spawned)) (not (int? now)) (= spawned now))))))

(defn- settle-jvm!
  "Read the front's JVM state from the JVM itself, after any attempt to start or stop it. The pid
   is the process's: recorded on the runtime and registry while `pid` runs, whatever answers,
   and cleared once it does not. What the front reads is the nREPL's: the group is running — the
   idle clock restarted — only while the JVM's own socket answers, and otherwise absent, so the
   next byte starts it again (which retires a JVM that runs and does not answer). The one place a
   stop or a failed start sets what the front and the fleet read, so neither can say a JVM is gone
   that a stop which threw left running, nor keep a pid that is gone, nor release a client to a
   JVM that does not answer.

   A running group's readiness is each listener's own target's: the nREPL's is its socket's, ready
   since it answers; an app's is its own bound port's, ready when that answers. An app whose port does not
   — stopped by the stop that failed, or never started by the start that did — is started again
   under the runtime's lock, and what its port holds is released only once it answers. Returns
   whether the JVM runs; never throws."
  [front pid]
  (let [{:keys [cfg]} @front
        alive?   (jvm-runs? front pid)
        answers? (and alive? (eval-svc/jvm-answers? {:socket (:socket cfg)}))]
    (try (note-jvm! cfg (when alive? pid)) (catch Exception _ nil))
    (if answers?
      (let [ready   (readiness cfg)
            session (try (state/read-session (:instance-id cfg)) (catch Exception _ nil))
            down    (into {} (for [[d s] (app-services cfg session)
                                   :when (not (proc/tcp-open? (:bind-port s)))]
                               [(:name d) [d s]]))]
        (doseq [[k p] ready :when (not (contains? down k))] (deliver p {:ok true}))
        (swap! front assoc :phase :running :jvm-pid pid :ready ready :last-traffic (now-ms))
        (doseq [[k [d s]] down]
          (future
            (deliver (get ready k)
                     (try (lifecycle/with-runtime-lock (:instance-id cfg) :on-demand-app-restart
                            (fn []
                              (if (eval-svc/start-app! d s (:context session))
                                {:ok true}
                                {:error (ex-info (str "The app " (name k) " did not open port "
                                                      (:bind-port s) " after its start form") {})})))
                          (catch Throwable e
                            (core/log-step (str "warning: restarting the app " (name k) ": " (ex-message e)))
                            {:error e}))))))
      (swap! front assoc :phase :absent :jvm-pid (when alive? pid)))
    alive?))

(defn- fail-start!
  "Everything a failed start owes, as one outcome: undo what it made on the runtime — the JVM, if
   it came up, and its pid — keep the failure under ~/.nido/failures/, record the start failed
   unless its triggering port was already ready (recorded then), and settle what the front reads
   from whether a JVM still runs (`settle-jvm!`). Run
   holding the runtime's lock, so no verb sees a half-made start or ends the front before the
   evidence is written; `rollback?` false only when that lock was never taken, so no JVM was
   started. Never throws."
  [front e rollback?]
  (let [{:keys [cfg]} @front]
    (core/log-step (str "On-demand start failed: " (ex-message e)))
    (when rollback?
      (try (process/stop-jvm! cfg) (catch Exception e2 (core/log-step (str "warning: " (ex-message e2))))))
    (try (keep-failure! front e)
         (catch Exception e2 (core/log-step (str "warning: could not keep the failed start: " (ex-message e2)))))
    ;; What failed after the triggering port was ready — an app, for an nREPL-triggered start —
    ;; is kept above all the same.
    (when-not (get-in @front [:start :recorded?])
      (record-start! front :failed (or (ex-message e) (str (class e))) (core/now-iso)))
    ;; The JVM this start spawned: the front's, or the one a start that threw names.
    (settle-jvm! front (or (:jvm-pid @front) (:pid (ex-data e))))))

(defn- listener-key
  "What a listener's readiness is kept under: :repl for the nREPL, the app's service name for an
   app port — so each port waits for its own target alone."
  [l]
  (if (= :repl (:kind l)) :repl (:service l)))

(defn- readiness
  "One promise per listener of front config `cfg`, under its `listener-key`."
  [cfg]
  (into {:repl (promise)} (map (fn [a] [(listener-key (assoc a :kind :app)) (promise)])) (:apps cfg)))

(defn- ready!
  "Deliver the readiness kept under `key`, and when it is the listener that triggered the start,
   record the start ready at this instant."
  [front key]
  (let [at (core/now-iso)
        {:keys [ready start]} @front]
    (some-> (get ready key) (deliver {:ok true}))
    (when (= key (:key start))
      (record-start! front :ready nil at))))

(defn- run-start!
  "Start the runtime's on-demand group — the JVM, then each app on it — delivering each port's
   readiness as it comes, and record the start however it ends. A start that fails is settled
   (`fail-start!`) under the same hold of the runtime's lock it was made under; only telling the
   waiting connections is left until after it."
  [front]
  (let [{:keys [cfg ready]} @front
        {:keys [instance-id]} cfg
        handled? (atom false)]
    (try
      (lifecycle/with-runtime-lock instance-id :on-demand-start
        (fn []
          (try
            (let [session (or (state/read-session instance-id)
                              (throw (ex-info (str "Runtime " instance-id " is not up") {:instance-id instance-id})))
                  ;; The pid is the front's and the registry's from the moment it is spawned, not
                  ;; once it answers; a start that fails settles it from whether it still runs.
                  _       (process/start-jvm!
                           (assoc cfg :on-spawn (fn [pid]
                                                  (swap! front assoc :jvm-pid pid
                                                         :jvm-started (proc/process-started pid))
                                                  (note-jvm! cfg pid))))]
              (assert-port-file! cfg)
              (ready! front :repl)
              (doseq [[d s] (app-services cfg session)]
                (when-not (eval-svc/start-app! d (assoc s :fresh-jvm? true) (:context session))
                  (throw (ex-info (str "The app " (name (:name d)) " did not open port " (:bind-port s)
                                       " after its start form")
                                  {:service (:name d) :bind-port (:bind-port s)})))
                (ready! front (:name d)))
              (swap! front assoc :phase :running :last-traffic (now-ms)))
            (catch Throwable e
              (reset! handled? true)
              (fail-start! front e true)
              (throw e)))))
      (catch Throwable e
        (when-not @handled? (fail-start! front e false))
        ;; A listener already ready keeps its answer: a promise is delivered once.
        (doseq [p (vals ready)] (deliver p {:error e}))))))

(declare forget-dead-jvm!)

(defn- start-of
  "The start a connection triggers, as its record will read: every field from the connection —
   its port, its listener, and the instant its first byte was read (`:first-byte-at`, stamped in
   `serve!`) — so however long it waited before the start began (a stop to finish, a dead JVM to
   be forgotten) is part of the start's time."
  [conn]
  {:requested-at (:first-byte-at conn) :port (:kind conn) :key (:key conn)
   :first conn})

(defn- demand!
  "The readiness connection `conn` waits on — its listener's (`listener-key`): a promise delivered
   {:ok true} once that listener's target answers, or {:error e}. Starts the group when none runs
   — once, for however many connections ask — and waits out a stop in progress before starting it
   again. A JVM that exited since it was read as running is forgotten first (`forget-dead-jvm!`)."
  [front conn]
  ;; A JVM read as running that has exited since the last tick is forgotten first, under the
  ;; runtime's lock, so this connection waits for a new start rather than being handed the
  ;; readiness of a JVM that is gone.
  (forget-dead-jvm! front)
  (locking front
    (case (:phase @front)
      (:running :starting) (get-in @front [:ready (:key conn)])
      :absent  (let [ready (readiness (:cfg @front))]
                 (swap! front assoc :phase :starting :ready ready
                        :start (start-of conn))
                 (future (run-start! front))
                 (get ready (:key conn)))
      :stopping (let [p (promise) stopped (:stopped @front)]
                  (future (deref stopped) (deliver p @(demand! front conn)))
                  p))))

(defn- idle?
  [{:keys [phase conns last-traffic cfg]} now]
  (and (= :running phase) (zero? conns) (>= (- now last-traffic) (:idle-ms cfg))))

(defn- forget-dead-jvm!
  "When the JVM the front reads as running has exited on its own, clear its record and read it as
   absent, so the next byte starts it again rather than being relayed to a socket nothing listens
   on; and when a pid the front kept while reading the JVM as absent has exited, clear it. Under
   the runtime's lock, and only while the front still reads that same JVM so, so no start made
   meanwhile is undone."
  [front]
  (let [{:keys [phase jvm-pid cfg]} @front]
    (when (and (= :running phase) jvm-pid (not (jvm-runs? front jvm-pid)))
      (core/log-step "The JVM exited on its own — the next call starts it again")
      (try
        (lifecycle/with-runtime-lock (:instance-id cfg) :on-demand-jvm-exited
          (fn []
            (when (locking front
                    (let [f @front]
                      (when (and (= :running (:phase f)) (= jvm-pid (:jvm-pid f)))
                        (swap! front assoc :phase :stopping :stopped (promise))
                        true)))
              (try (process/stop-jvm! cfg)
                   (finally
                     (let [stopped (:stopped @front)]
                       (settle-jvm! front jvm-pid)
                       (deliver stopped true)))))))
        (catch Throwable e (core/log-step (str "warning: " (ex-message e)))))))
  ;; A pid kept while the front reads the JVM as absent — one a failed start or stop left running
  ;; — is cleared once its process is gone, so the fleet stops charging it and a reused pid is
  ;; never charged to this runtime.
  (let [{:keys [phase jvm-pid cfg]} @front]
    (when (and (= :absent phase) jvm-pid (not (jvm-runs? front jvm-pid)))
      (try
        (lifecycle/with-runtime-lock (:instance-id cfg) :on-demand-jvm-exited
          (fn []
            (when (locking front
                    (let [f @front] (and (= :absent (:phase f)) (= jvm-pid (:jvm-pid f)))))
              (settle-jvm! front jvm-pid))))
        (catch Throwable e (core/log-step (str "warning: " (ex-message e))))))))

(defn- stop-idle!
  "Stop the group when it is idle (`idle?`): each app's stop form over the JVM's socket, then the
   JVM. Nothing else of the runtime is touched."
  [front]
  (when-let [stopped (locking front
                       (when (idle? @front (now-ms))
                         (let [p (promise)]
                           (swap! front assoc :phase :stopping :stopped p)
                           p)))]
    (let [{:keys [cfg jvm-pid]} @front]
      (core/log-step (str "No nREPL traffic for " (quot (:idle-ms cfg) 1000) "s and no connection open — stopping the JVM"))
      (try
        (lifecycle/with-runtime-lock (:instance-id cfg) :on-demand-idle-stop
          (fn []
            (when-let [session (state/read-session (:instance-id cfg))]
              (doseq [[d s] (app-services cfg session)]
                (try (eval-svc/stop-app! d s) (catch Exception _ nil))))
            (try (process/stop-jvm! cfg)
                 ;; Under the lock, so no start reads the group between the stop and this.
                 (finally (settle-jvm! front jvm-pid)))))
        (catch Throwable e
          (core/log-step (str "warning: idle stop: " (ex-message e)))
          ;; A lock never taken stopped nothing; one that threw before the stop, likewise.
          (when (= :stopping (:phase @front)) (settle-jvm! front jvm-pid)))
        (finally
          (deliver stopped true))))))

;; ---------------------------------------------------------------------------
;; The relay
;; ---------------------------------------------------------------------------

(defn- connect-inner
  "Open a connection to what listener `l` fronts: the JVM's nREPL socket, or the port an app
   binds. Returns {:read :write :shutdown-output :close} over it."
  [cfg l]
  (if (= :repl (:kind l))
    (let [ch (SocketChannel/open (UnixDomainSocketAddress/of (str (:socket cfg))))]
      {:read            (fn [^bytes b] (.read ch (ByteBuffer/wrap b)))
       :write           (fn [^bytes b n] (let [bb (ByteBuffer/wrap b 0 n)]
                                           (while (.hasRemaining bb) (.write ch bb))))
       :shutdown-output #(.shutdownOutput ch)
       :close           #(.close ch)})
    (let [s (Socket.)]
      (.connect s (InetSocketAddress. "127.0.0.1" (int (:bind-port l))) 5000)
      (let [in (.getInputStream s) out (.getOutputStream s)]
        {:read            (fn [^bytes b] (.read in b))
         :write           (fn [^bytes b n] (.write out b 0 (int n)) (.flush out))
         :shutdown-output #(.shutdownOutput s)
         :close           #(.close s)}))))

(defn- traffic! [front kind]
  (when (= :repl kind) (swap! front assoc :last-traffic (now-ms))))

(def ^:private held-limit
  "The most a connection holds while its target is not ready. Past it the client is not read
   until the target is connected, so a client sending faster than the start meets the
   backpressure a direct connection gives it, not a front whose heap it can fill."
  (* 1024 1024))

(defn- gave-up!
  "Mark connection `conn` as one whose client gave up: it ended — an end of sending, a reset, any
   failure — before its target was connected. Marked on the connection, not on a start, so a start
   it triggers later (one deferred behind a stop) reads it as well as one already running."
  [conn]
  (reset! (:gave-up? conn) true))

(defn- pump-client!
  "Client to JVM: what the client sends is held until `inner` is connected, written through
   after — held up to `held-limit`, past which the client is not read until `released` (the
   target connected, or the connection closed). The client's end of sending is passed on as the end of sending to `inner` — at once when
   it is connected, else once it is (`client-eof?`) — and is never the end of the connection, which
   a client may half-close and go on reading its reply from; `end!` says this direction is done.
   An end before `inner` is connected is read as the start's first client giving up, when it is
   the connection that started it: a half-close cannot be told from a close."
  [front conn ^java.io.InputStream in held inner released client-eof? end! close!]
  (try
    (let [b (byte-array 65536)]
      (loop []
        (when (and (nil? @inner) (>= (locking held (.size ^java.io.ByteArrayOutputStream held)) held-limit))
          (deref released))
        (let [n (.read in b)]
          (if (neg? n)
            (do (locking held
                  (if-let [i @inner]
                    ((:shutdown-output i))
                    (do (reset! client-eof? true)
                        (gave-up! conn))))
                (end!))
            (do (traffic! front (:kind conn))
                (locking held
                  (if-let [i @inner]
                    ((:write i) b n)
                    (.write ^java.io.ByteArrayOutputStream held b 0 (int n))))
                (recur))))))
    (catch Exception _ (close!))))

(defn- pump-inner!
  "JVM to client, unchanged. The JVM's end of sending is passed on as the end of sending to the
   client, and `end!` says this direction is done."
  [front conn i ^Socket client end! close!]
  (try
    (let [out (.getOutputStream client)
          b   (byte-array 65536)]
      (loop []
        (let [n ((:read i) b)]
          (if (neg? n)
            (do (.shutdownOutput client) (end!))
            (do (traffic! front (:kind conn))
                (.write out b 0 (int n))
                (.flush out)
                (recur))))))
    (catch Exception _ (close!))))

(defn- serve!
  "Relay one accepted client connection on listener `l`. It stays open — and counted among the
   front's open connections — until both directions have ended, or either fails."
  [front l ^Socket client]
  (let [conn        {:kind (:kind l) :key (listener-key l) :id (Object.) :gave-up? (atom false)}
        in          (.getInputStream client)
        held        (java.io.ByteArrayOutputStream.)
        inner       (atom nil)
        client-eof? (atom false)
        released    (promise)
        closed?     (atom false)
        close!      (fn []
                      (deliver released true)
                      (when (compare-and-set! closed? false true)
                        (try (.close client) (catch Exception _ nil))
                        (if-let [i @inner]
                          (try ((:close i)) (catch Exception _ nil))
                          ;; Ended before its target was connected: its client gave up.
                          (gave-up! conn))
                        (swap! front update :conns dec)))
        ended       (atom 0)
        end!        #(when (= 2 (swap! ended inc)) (close!))]
    (swap! front update :conns inc)
    (try
      (let [buf (byte-array 65536)
            n   (.read in buf)]
        (if (neg? n)
          (close!)
          (let [conn (assoc conn :first-byte-at (core/now-iso))]
            (traffic! front (:kind conn))
            (.write held buf 0 n)
            (let [ready (demand! front conn)]
              (future (pump-client! front conn in held inner released client-eof? end! close!))
              (let [r @ready]
                (if (or (:error r) @closed?)
                  (close!)
                  (let [i (connect-inner (:cfg @front) l)]
                    (locking held
                      ;; Published, then closure read — `close!` sets closure, then reads this — so
                      ;; of a close and this attach, whichever comes second closes the inner
                      ;; connection, before any byte is written to it.
                      (reset! inner i)
                      (when @closed?
                        (try ((:close i)) (catch Exception _ nil))
                        (throw (ex-info "closed while its target was connected" {})))
                      ;; Replies are read from before the held bytes are written, as a direct
                      ;; connection would: a target that answers before it has read everything
                      ;; sent it would otherwise block on its reply while the flush blocks on it.
                      (future (pump-inner! front conn i client end! close!))
                      (let [bs (.toByteArray held)] ((:write i) bs (count bs)))
                      (when @client-eof? ((:shutdown-output i))))
                    (deliver released true))))))))
      (catch Exception _ (close!)))))

(defn- listen!
  "Bind listener `l`'s port: the nREPL on loopback, as nREPL binds by default; an app on every
   address, as an app server does."
  ^ServerSocket [l]
  (doto (ServerSocket.)
    (.setReuseAddress true)
    (.bind (if (= :repl (:kind l))
             (InetSocketAddress. "127.0.0.1" (int (:port l)))
             (InetSocketAddress. (int (:port l))))
           128)))

;; ---------------------------------------------------------------------------
;; Lifetime
;; ---------------------------------------------------------------------------

(defn- exit!
  "Stop the JVM this front started, if one runs, and exit: no JVM outlives its front. The stop is
   made under the runtime's lock, so it waits out a start in progress rather than interleaving
   with it; when the lock cannot be had in its wait, the JVM is stopped all the same, since a JVM
   left behind would outlive its front."
  [front why]
  (core/log-step (str "Front exiting: " why))
  (let [{:keys [cfg]} @front
        stop! #(try (process/stop-jvm! cfg) (catch Exception _ nil))]
    (try (lifecycle/with-runtime-lock (:instance-id cfg) :on-demand-exit stop!)
         (catch Throwable e
           (core/log-step (str "warning: front exit without the runtime's lock: " (ex-message e)))
           (stop!))))
  (System/exit 0))

(defn- watch!
  "Every tick: exit when the front's runtime is gone, keep `.nrepl-port` naming the front, and
   stop the group when it has gone idle."
  [front instance-id svc-name]
  (let [own   (.pid (java.lang.ProcessHandle/current))
        born  (now-ms)
        cfg-f (state/front-config-file instance-id svc-name)]
    (loop [owned? false]
      (Thread/sleep tick-ms)
      (let [session (state/read-session instance-id)
            pid     (get-in session [:service-states (:service (:cfg @front)) :pid])
            mine?   (= own pid)]
        (cond
          (not (fs/exists? cfg-f))                     (exit! front "its service was stopped")
          (and owned? (nil? session))                  (exit! front "its runtime is gone")
          (and owned? (some? pid) (not mine?))         (exit! front "its runtime names another front")
          (and (not owned?) (not mine?)
               (> (- (now-ms) born) unowned-limit-ms)) (exit! front "its runtime never recorded it")
          :else
          (do (assert-port-file! (:cfg @front))
              (try (forget-dead-jvm! front)
                   (stop-idle! front)
                   (catch Throwable e (core/log-step (str "warning: " (ex-message e)))))
              (recur (or owned? mine?))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  main
  "Run the front of runtime `instance-id`'s on-demand :process service `svc-name`, as its config
   file says, until its runtime goes. The `nido:session:front` task; the :process service starts
   it."
  [[instance-id svc-name]]
  (let [cfg       (io/read-edn (state/front-config-file instance-id svc-name))
        _         (when-not cfg (throw (ex-info "No front config" {:instance-id instance-id :service svc-name})))
        listeners (cons {:kind :repl :port (:nrepl-port cfg)}
                        (map #(assoc % :kind :app) (:apps cfg)))
        front     (atom {:cfg cfg :phase :absent :conns 0 :last-traffic (now-ms)})
        servers   (mapv (fn [l] [l (listen! l)]) listeners)]
    (core/log-step (str "Front of " instance-id " holds "
                        (str/join ", " (map (fn [[l _]] (str (name (:kind l)) " " (:port l))) servers))))
    (assert-port-file! cfg)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (when-let [f (port-file cfg)]
                                   (when (= (str (:nrepl-port cfg))
                                            (try (str/trim (slurp f)) (catch Exception _ nil)))
                                     (fs/delete-if-exists f))))))
    (doseq [[l ^ServerSocket ss] servers]
      (future
        (loop []
          (let [c (.accept ss)]
            (future (serve! front l c))
            (recur)))))
    (watch! front instance-id svc-name)))
