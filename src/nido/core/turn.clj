(ns nido.core.turn
  "The Turn: one bounded stretch of agent work, and the executor port it runs through.

   An executor is a map of three functions, and nothing above this namespace names a provider:
     :launch    (fn [run] -> {:argv [...] :env {...} :files {abs-path content}})
                what to start for `run`; `run` is {:turn-dir :run-dir :run :spec :resolution
                :resolved-call}, and a run past the first resumes the turn with :resolution
     :outcome   (fn [run-dir] -> outcome or nil)
                the one outcome a finished run's output carries, or nil when it carries none
     :attach    (fn [turn-dir] -> argv)
                what a person runs to enter the turn's session
   An outcome is {:outcome :completed|:asked|:failed|:cancelled ...}; an asked one carries
   :call {:id ...}. `outcome` is where executor output is parsed: anything that is not one of
   the four is :failed.

   The runner starts what the store says should run, after the commit that says so:
     - a turn :running whose current run has no directory is launched, and only then
     - a turn the store cancelled (a hold's takeover, an operator ask concerning its unit) whose
       run still runs is cancelled
   and reports each run's end as a :record-outcome command keyed by workstream, turn and run, so
   a report the writer already holds is answered, not recorded twice.

   A run's directory is its handle: <root>/<ws>/<turn>/run-<n>, fixed by the store before launch,
   so a daemon that starts later can always find it. The run is started detached in a session of
   its own by a wrapper that holds the directory's lock for the run's whole life and writes its
   pid. Its end is an event: the process exit for a run this daemon started, and the lock coming
   free for one it took back. Nothing polls.

   Take-back, at start, for each turn the store records running:
     - no run directory: the run never started, recorded :failed {:reason :never-started}
     - lock held: the run still runs; it is watched as if this daemon had started it
     - lock free: the run ended while no daemon held it, recorded with its output's outcome, or
       :failed with its actions unknown when the output carries none
   It starts nothing again.

   Every run has a deadline (the spec's :deadline-ms, else the runner's default). A run past it is
   cancelled and recorded :cancelled {:reason :deadline}."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.core.domain :as d])
  (:import [java.io File]
           [java.lang ProcessHandle]
           [java.net URLEncoder]
           [java.nio.channels FileChannel OverlappingFileLockException]
           [java.nio.charset StandardCharsets]
           [java.nio.file OpenOption StandardOpenOption]
           [java.util.concurrent LinkedBlockingQueue ScheduledExecutorService ScheduledThreadPoolExecutor TimeUnit]))

;; ── the port: what an outcome may be ─────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :any] :map]} parse-outcome
  "`x`, an executor's report, as one of the four outcomes; anything else is :failed, saying why."
  [x]
  (let [o (:outcome x)]
    (cond
      (and (= :asked o) (string? (get-in x [:call :id])))     (select-keys x [:outcome :call])
      (= :asked o)                                             {:outcome :failed :detail {:reason :ask-without-call}}
      (#{:completed :failed :cancelled} o)                     (select-keys x [:outcome :detail])
      :else {:outcome :failed :detail {:reason :not-an-outcome :reported (pr-str x)}})))

;; ── handles ──────────────────────────────────────────────────────────────────

(defn- path-part [s] (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn ^{:malli/schema [:=> [:cat :string :string :string] :string]} turn-dir
  "The directory of turn `turn` of workstream `ws` under `root`."
  [root ws turn]
  (str (io/file root (path-part ws) (path-part turn))))

(defn ^{:malli/schema [:=> [:cat :string :int] :string]} run-dir
  "The directory of run `n` of the turn whose directory is `tdir`: the run's handle."
  [tdir n]
  (str (io/file tdir (str "run-" n))))

(def ^:private wrapper
  "Becomes the run: a session of its own, so it outlives the daemon and a cancel reaches all of it;
   the run's lock, held for its whole life because a POSIX record lock survives exec and the
   descriptor is inheritable; its pid; then exec. Its stdout and stderr are the run's out and err."
  (str "import fcntl,os,sys\n"
       "os.setsid()\n"
       "fd=os.open(sys.argv[1],os.O_RDWR|os.O_CREAT,0o644)\n"
       "fcntl.lockf(fd,fcntl.LOCK_EX|fcntl.LOCK_NB,1,0)\n"
       "os.set_inheritable(fd,True)\n"
       "open(sys.argv[2]+'.tmp','w').write(str(os.getpid()))\n"
       "os.rename(sys.argv[2]+'.tmp',sys.argv[2])\n"
       "os.execvp(sys.argv[3],sys.argv[3:])\n"))

(defn- lock-file [rdir] (io/file rdir "lock"))
(defn- pid-file [rdir] (io/file rdir "pid"))
(defn- launched-file [rdir] (io/file rdir "launched"))

(defn- lock-free?
  "Whether no process holds the run's lock: true once the run has ended."
  [rdir]
  (with-open [ch (FileChannel/open (.toPath ^File (lock-file rdir))
                                   (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    (try (if-let [l (.tryLock ch 0 1 false)] (do (.release l) true) false)
         (catch OverlappingFileLockException _ false))))

(defn- await-lock-free!
  "Block until the run holding `rdir`'s lock has ended."
  [rdir]
  (with-open [ch (FileChannel/open (.toPath ^File (lock-file rdir))
                                   (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    (.release (.lock ch 0 1 false))))

(defn- run-pid [rdir]
  (some-> (let [f (pid-file rdir)] (when (.exists f) (slurp f))) str/trim parse-long))

(defn- kill-run!
  "End the run in `rdir` and everything it started. Answers whether there was a process to end."
  [rdir]
  (if-let [ph (some-> (run-pid rdir) ProcessHandle/of (.orElse nil))]
    (do (doseq [c (iterator-seq (.iterator (.descendants ^ProcessHandle ph)))] (.destroyForcibly ^ProcessHandle c))
        (.destroyForcibly ^ProcessHandle ph)
        true)
    false))

;; ── the runner ───────────────────────────────────────────────────────────────

(defn- report! [what ^Throwable t]
  (binding [*out* *err*] (println "nido.core.turn:" what "-" (.getMessage t))))

(defn- outcome-key [ws turn run] (str "outcome/" ws "/" turn "/" run))

(defn- submit-outcome!
  [{:keys [submit]} ws turn run outcome]
  (submit {:key (outcome-key ws turn run) :ws ws
           :command (merge {:command :record-outcome :turn turn :run run} outcome)}))

(def ^:private stderr-tail-chars 1500)

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :string]]} stderr-tail
  "The last of what the run in `rdir` wrote to its stderr — its last lines, at most
   `stderr-tail-chars` — or nil when it wrote nothing. What a run that died says about why."
  [rdir]
  (let [f (io/file rdir "err")]
    (when (.exists f)
      (let [s (str/trim (try (slurp f) (catch Exception _ "")))]
        (when-not (str/blank? s)
          (let [lines (remove str/blank? (str/split-lines s))
                tail  (str/join "\n" (take-last 12 lines))]
            (if (> (count tail) stderr-tail-chars)
              (subs tail (- (count tail) stderr-tail-chars))
              tail)))))))

(defn- finished-outcome
  "The outcome of the run in `rdir`, which has ended: its output's, or :failed with its actions
   unknown when the output carries none — saying, under :stderr, the last its run wrote to stderr,
   which is the only word on why an agent that crashed died."
  [executor rdir]
  (if-let [o (try ((:outcome executor) rdir)
                  (catch Throwable t (report! (str "reading " rdir) t) nil))]
    (parse-outcome o)
    {:outcome :failed
     :detail (cond-> {:reason :ended-without-outcome}
               (stderr-tail rdir) (assoc :stderr (stderr-tail rdir)))
     :actions-unknown? true}))

(defn- on-end!
  "The run [ws turn run] has ended: stop its deadline, and report its outcome."
  [{:keys [live] :as runner} executor ws turn run rdir]
  (let [{:keys [deadline cancelled]} (get @live [ws turn run])]
    (some-> deadline (.cancel false))
    (swap! live dissoc [ws turn run])
    (submit-outcome! runner ws turn run
                     (case cancelled
                       :deadline {:outcome :cancelled :detail {:reason :deadline}}
                       (finished-outcome executor rdir)))))

(defn- watch!
  "Watch the run [ws turn run] in `rdir` until it ends, from `process` when this daemon started it,
   else from its lock; arm its deadline for what remains of `deadline-ms`."
  [{:keys [live ^ScheduledExecutorService timer] :as runner} executor ws turn run rdir ^Process process deadline-ms]
  (let [launched (or (some-> (let [f (launched-file rdir)] (when (.exists f) (slurp f))) str/trim parse-long)
                     (System/currentTimeMillis))
        remaining (max 0 (- (+ launched deadline-ms) (System/currentTimeMillis)))
        dl (.schedule timer ^Runnable (fn []
                                        (swap! live assoc-in [[ws turn run] :cancelled] :deadline)
                                        (kill-run! rdir))
                      (long remaining) TimeUnit/MILLISECONDS)]
    (swap! live assoc [ws turn run] {:deadline dl :rdir rdir})
    (doto (Thread. ^Runnable (fn []
                               (try (if process (.waitFor process) (await-lock-free! rdir))
                                    (on-end! runner executor ws turn run rdir)
                                    (catch Throwable t (report! (str "watching " rdir) t))))
                   (str "nido-turn-" turn "-" run))
      (.setDaemon true)
      (.start))))

(defn- executor-of [{:keys [executors]} t]
  (get executors (get-in t [:spec :executor])))

(defn- launch!
  "Start run `run` of turn `t` of stream `ws`, a run's stream when `run-stream?`. The run's
   directory is made first, so a directory that is absent proves the run was never started."
  [{:keys [root default-deadline-ms] :as runner} ws t run-stream?]
  (let [{turn :id run :run spec :spec} t
        executor (executor-of runner t)
        tdir (turn-dir root ws turn)
        rdir (run-dir tdir run)]
    (if-not executor
      (submit-outcome! runner ws turn run {:outcome :failed :detail {:reason :no-such-executor
                                                                     :executor (:executor spec)}})
      (let [_ (.mkdirs (io/file rdir))
            {:keys [argv env files]} ((:launch executor) {:turn-dir tdir :run-dir rdir :run run :spec spec
                                                          :resolution (:resolution t)
                                                          :resolved-call (:resolved-call t)})]
        (doseq [[path content] files] (io/make-parents (io/file path)) (spit (io/file path) content))
        (spit (launched-file rdir) (str (System/currentTimeMillis)))
        (let [pb (doto (ProcessBuilder. ^java.util.List (into ["python3" "-c" wrapper
                                                               (str (lock-file rdir)) (str (pid-file rdir))]
                                                              argv))
                   (.directory (io/file (or (:cwd spec) tdir)))
                   (.redirectInput (java.lang.ProcessBuilder$Redirect/from (io/file "/dev/null")))
                   (.redirectOutput (io/file rdir "out"))
                   (.redirectError (io/file rdir "err")))
              ;; Which turn this is, for what runs inside it — a commit gate judging a person's
              ;; act — whatever the executor: no executor needs to know to say it. A run's turn is
              ;; told its run, never a workstream: it has none to write to.
              _ (.putAll (.environment pb) (update-keys (merge env (cond-> {(if run-stream? "NIDO_CORE_RUN" "NIDO_CORE_WS") ws
                                                                            "NIDO_CORE_TURN" (:id t)}
                                                                     (:unit t) (assoc "NIDO_CORE_UNIT" (:unit t))))
                                                        str))
              p (.start pb)]
          (watch! runner executor ws (:id t) run rdir p (or (:deadline-ms spec) default-deadline-ms)))))))

(defn- converge!
  "Start or cancel what workstream `ws`'s committed `state` says, that this runner has not."
  [{:keys [root live] :as runner} ws state]
  (doseq [t (vals (:turns state))
          :let [rdir (run-dir (turn-dir root ws (:id t)) (or (:run t) 1))]]
    (cond
      (and (= :running (:status t)) (not (.exists (io/file rdir))))
      (launch! runner ws t (some? (:run state)))

      (and (= :cancelled (:status t)) (:ended-by t) (contains? @live [ws (:id t) (:run t)]))
      (do (swap! live assoc-in [[ws (:id t) (:run t)] :cancelled] (:ended-by t))
          (kill-run! rdir)))))

(defn ^{:malli/schema [:=> [:cat :map] :map]} start
  "Start a runner. Options:
     :root                 directory the turns' runs live under
     :executors            {executor-kw executor}
     :submit               (fn [envelope]) a command to the writer (`nido.core.writer/submit!`)
     :default-deadline-ms  a run's deadline when its spec names none (default 2 hours)
   Feed it committed changes with `changed!`; take back what a previous daemon left with
   `take-back!`."
  [{:keys [root executors submit default-deadline-ms] :or {default-deadline-ms (* 2 60 60 1000)}}]
  (let [runner {:root root :executors executors :submit submit
                :default-deadline-ms default-deadline-ms
                :live (atom {})
                :queue (LinkedBlockingQueue.)
                :timer (doto (ScheduledThreadPoolExecutor.
                              1 (reify java.util.concurrent.ThreadFactory
                                  (newThread [_ r] (doto (Thread. r "nido-turn-deadlines") (.setDaemon true)))))
                         (.setRemoveOnCancelPolicy true))}
        t (doto (Thread. ^Runnable (fn []
                                     (loop []
                                       (let [x (.take ^LinkedBlockingQueue (:queue runner))]
                                         (when-not (= ::stop x)
                                           (doseq [{:keys [ws state]} x]
                                             (try (converge! runner ws state)
                                                  (catch Throwable e (report! (str "converging " ws) e))))
                                           (recur)))))
                         "nido-turn-runner")
            (.setDaemon true) (.start))]
    (assoc runner :thread t)))

(defn ^{:malli/schema [:=> [:cat :map [:sequential :map]] :nil]} changed!
  "Hand the runner a commit's changes [{:ws :state} ...]. Never blocks: the runner converges on
   them on its own thread, so this may be the writer's publish."
  [runner changes]
  (.put ^LinkedBlockingQueue (:queue runner) (vec changes))
  nil)

(defn ^{:malli/schema [:=> [:cat :map :string :map] :nil]} take-back!
  "Take back every turn workstream `ws`'s committed `state` records running, as a starting daemon
   does: watch a run that still runs, report one that ended while no daemon held it, and report a
   run that never started as failed. Starts nothing."
  [{:keys [root default-deadline-ms] :as runner} ws state]
  (doseq [t (vals (:turns state))
          :when (= :running (:status t))
          :let [{turn :id run :run} t
                rdir (run-dir (turn-dir root ws turn) run)
                executor (executor-of runner t)]]
    (cond
      (or (nil? executor) (not (.exists (launched-file rdir))))
      ;; The directory is claimed first, so no change converged before this outcome commits can
      ;; start the run after all.
      (do (.mkdirs (io/file rdir))
          (submit-outcome! runner ws turn run {:outcome :failed :detail {:reason :never-started}}))

      (lock-free? rdir)
      (submit-outcome! runner ws turn run (finished-outcome executor rdir))

      :else
      (watch! runner executor ws turn run rdir nil (or (get-in t [:spec :deadline-ms]) default-deadline-ms))))
  nil)

(defn ^{:malli/schema [:=> [:cat :map :string :map] [:maybe [:sequential :string]]]} attach-argv
  "What a person runs to enter turn `turn` of `ws`'s session, or nil when its executor has none."
  [{:keys [root executors]} ws {turn :id :as t}]
  (when-let [attach (:attach (get executors (get-in t [:spec :executor])))]
    (attach (turn-dir root ws turn))))

(defn ^{:malli/schema [:=> [:cat :map] :nil]} stop!
  "Stop converging. Runs keep running; the daemon that starts next takes them back."
  [runner]
  (.put ^LinkedBlockingQueue (:queue runner) ::stop)
  (.shutdownNow ^ScheduledExecutorService (:timer runner))
  nil)
