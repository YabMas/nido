(ns nido.core.turn
  "The Turn: one bounded stretch of agent work, and the executor port it runs through.

   An executor is a map of these functions, and nothing above this namespace names a provider:
     :launch    (fn [run] -> {:argv [...] :env {...} :files {abs-path content}})
                what to start for `run`; `run` is {:turn-dir :run-dir :run :spec :resolution
                :resolved-call}, and a run past the first resumes the turn with :resolution
     :outcome   (fn [run-dir] -> outcome or nil)
                the one outcome a finished run's output carries, or nil when it carries none
     :attach    (fn [turn-dir] -> argv)
                what a person runs to enter the turn's session
     :settled?  optional (fn [run-dir] -> bool)
                whether the run's output is at rest at a final answer, with nothing it waits for
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

   On Linux each run is started in a transient systemd scope of its own (`systemd-run --user
   --scope`) capped at `run-tasks-max` processes, so one run that storms is refused at its own cap
   and nothing beside it is. Where no user systemd manager answers, and on macOS, runs start as
   before, in the core's cgroup. The scope changes nothing else: the run is still the wrapper's pid,
   its lock and its directory, so it is watched, cancelled and taken back the same way.

   A run that failed while the host refused it resources — its err says so, its scope refused it a
   fork, or the cgroups the core is in refused one while it ran — is reported :failed
   {:reason :resource-exhausted}, as is a launch the host refused; the domain retries those later
   rather than counting them.

   Every run has a deadline (the spec's :deadline-ms, else the runner's default). A run past it is
   cancelled and recorded :cancelled {:reason :deadline}.

   A run that has given its final answer is ended, whatever it left running: an agent can hold its
   process open after it is done (a background watcher it never stopped). Every :check-ms the runner
   asks the executor's :settled? of each run whose output changed since it last asked, and ends one
   that has read settled for :settle-ms, whatever is still written to its output meanwhile. Its
   outcome is the one its output carries, as for any run that ends. A run that exits on its own is
   not cleaned up after: what outlives it was detached from it, and may be meant to (a session's
   services, started from a turn).

   Ending a run — at its deadline, by a cancel, or settled — kills it and everything it started,
   trying again while its process cannot be reached yet.
   A run still alive :kill-grace-ms after that is reported stuck, as a :record-stuck command, so
   that what cannot be ended reads as stuck and not as working; its outcome is still recorded when
   it does end."
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
   descriptor is inheritable; its pid; then exec. Its stdout and stderr are the run's out and err.

   Given a refusals path (argv[3], empty for none) it forks the run instead of becoming it, waits,
   and writes there how many forks the cgroup it is in refused — the run's own scope, which goes
   when its last process does, so this is the one moment its count can still be read."
  (str "import fcntl,os,sys\n"
       "os.setsid()\n"
       "fd=os.open(sys.argv[1],os.O_RDWR|os.O_CREAT,0o644)\n"
       "fcntl.lockf(fd,fcntl.LOCK_EX|fcntl.LOCK_NB,1,0)\n"
       "os.set_inheritable(fd,True)\n"
       "open(sys.argv[2]+'.tmp','w').write(str(os.getpid()))\n"
       "os.rename(sys.argv[2]+'.tmp',sys.argv[2])\n"
       "if not sys.argv[3]: os.execvp(sys.argv[4],sys.argv[4:])\n"
       "c=os.fork()\n"
       "if c==0:\n"
       " try: os.execvp(sys.argv[4],sys.argv[4:])\n"
       " except BaseException as e: os.write(2,(str(e)+'\\n').encode()); os._exit(127)\n"
       "s=os.waitpid(c,0)[1]\n"
       "try:\n"
       " g=[l[3:].strip() for l in open('/proc/self/cgroup') if l.startswith('0::')][0]\n"
       " n=[int(l.split()[1]) for l in open('/sys/fs/cgroup'+g+'/pids.events') if l.startswith('max ')][0]\n"
       " open(sys.argv[3]+'.tmp','w').write(str(n))\n"
       " os.rename(sys.argv[3]+'.tmp',sys.argv[3])\n"
       "except Exception: pass\n"
       "e=os.waitstatus_to_exitcode(s)\n"
       "os._exit(e if e>=0 else 128-e)\n"))

(defn- lock-file [rdir] (io/file rdir "lock"))
(defn- pid-file [rdir] (io/file rdir "pid"))
(defn- launched-file [rdir] (io/file rdir "launched"))
(defn- refusals-file [rdir] (io/file rdir "pid-refusals"))
(defn- refusals-before-file
  "Where the forks refused at the core's cgroups just before the run's launch are kept, so that a
   daemon taking the run back counts from its launch, not from when it started watching."
  [rdir]
  (io/file rdir "pid-refusals-before"))
(defn- ended-by-file [rdir] (io/file rdir "ended-by"))

(declare report!)

(defn- read-kept
  "What run file `f` keeps, read by `parse` from its trimmed text, or nil when it keeps nothing or
   cannot be read: an unreadable file is one run's state unknown, never a failure of whatever
   reads it, so recovery and outcomes go on for that run and every other."
  [^File f parse]
  (when (.exists f)
    (try (some-> (slurp f) str/trim not-empty parse)
         (catch Throwable t (report! (str "reading " f) t) nil))))

(defn- ended-by
  "Why the runner ended run `k` ([ws turn run]) in `rdir` (`end-run!`), or nil when it did not end
   it: the reason it decided, held in `live` the moment it decides, else the one kept in the run's
   directory, which survives the daemon."
  [{:keys [live]} k rdir]
  (or (get-in @live [k :ended-by])
      (let [f (ended-by-file rdir)]
        (read-kept f keyword))))

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
  (read-kept (pid-file rdir) parse-long))

(defn- kill-run!
  "End the run in `rdir` and everything it started. Answers whether there was a process to end."
  [rdir]
  (if-let [ph (some-> (run-pid rdir) ProcessHandle/of (.orElse nil))]
    ;; The tree is read first and its root ended first, so no descendant is started past the kill.
    (let [ds (vec (iterator-seq (.iterator (.descendants ^ProcessHandle ph))))]
      (.destroyForcibly ^ProcessHandle ph)
      (doseq [^ProcessHandle c ds] (.destroyForcibly c))
      true)
    false))

;; ── resource exhaustion ──────────────────────────────────────────────────────

(def ^:private exhaustion-signature
  "What a process says when the host refused it a process or thread: errno 11 under its names, and
   the JVM's, Bun's and bash's words for it."
  #"EAGAIN|Resource temporarily unavailable|error: 11\b|errno 11\b|thread limit|pthread_create|unable to create native thread|fork: retry|Cannot fork")

(defn- ^{:malli/schema [:=> [:cat [:maybe :string]] :boolean]} exhaustion-message?
  "Whether `s`, a run's stderr or a launch error, says the host refused a process or thread."
  [s]
  (boolean (and s (re-find exhaustion-signature s))))

(defn- pids-events-max
  "The `max` count in `dir`'s `file` (pids.events or pids.events.local), or nil when there is none."
  [^File dir file]
  (let [f (io/file dir file)]
    (when (.exists f)
      (some #(some-> (re-find #"^max (\d+)" %) second parse-long) (str/split-lines (slurp f))))))

(defn- level-refusals
  "The forks refused at the cgroup `dir` by its own limit, counted once. A kernel with
   pids.events.local keeps pids.events hierarchical — a level's `max` there includes every refusal
   below it, a sibling run's scope's included — so the local count is the level's own; an older
   kernel has no local file, and its pids.events counts the forks refused inside the level alone."
  [^File dir]
  (if (.exists (io/file dir "pids.events.local"))
    (pids-events-max dir "pids.events.local")
    (pids-events-max dir "pids.events")))

(defn ^{:malli/schema [:=> [:cat :string :string] [:maybe :int]]} pid-refusals-above
  "How many forks the cgroup v2 pids controller has refused at the cgroup `cgroup` (a path as
   /proc/<pid>/cgroup names it) under the hierarchy mounted at `root`, and at each of its ancestors,
   each refusal counted at the one level whose limit refused it; nil when no level has a count.
   These are the limits a run in that cgroup or beside it under the same slices can be refused by.
   A refusal at a sibling's own cap — another run's scope — is not among them."
  [root cgroup]
  (let [root (io/file root)]
    (loop [^File dir (io/file root (str/replace cgroup #"^/+" "")), total nil]
      (let [total (if-let [n (level-refusals dir)] (+ (or total 0) n) total)]
        (if (or (= dir root) (nil? (.getParentFile dir)))
          total
          (recur (.getParentFile dir) total))))))

(defn- ^{:malli/schema [:=> [:cat] [:maybe :int]]} cgroup-pid-refusals
  "`pid-refusals-above` this process's cgroup; nil where there is no cgroup v2 (not Linux). It
   rising while a run ran says the host refused that run, or something beside it, a process: an
   unscoped run shares the core's service cgroup, and a scoped one the slices above it."
  []
  (try
    (when-let [line (some #(when (str/starts-with? % "0::") %)
                          (str/split-lines (slurp "/proc/self/cgroup")))]
      (pid-refusals-above "/sys/fs/cgroup" (subs line 3)))
    (catch Exception _ nil)))

(defn- run-err
  "The run's stderr, all of it: a refusal may come after any amount of output."
  [rdir]
  (let [f (io/file rdir "err")]
    (when (.exists f) (slurp f))))

(defn- run-refusals-before
  "The count `refusals-before-file` kept for the run in `rdir`, or nil when none was kept."
  [rdir]
  (let [f (refusals-before-file rdir)]
    (when (.exists f) (some-> (slurp f) str/trim parse-long))))

(defn- refusal-excerpt
  "The lines of `err` that carry the refusal signature, at most 2000 characters of them: what a
   reader of the outcome needs, without carrying a whole stderr into the store."
  [err]
  (let [s (str/join "\n" (filter #(re-find exhaustion-signature %) (str/split-lines err)))]
    (subs s 0 (min 2000 (count s)))))

(defn- run-refusals
  "How many forks the run in `rdir` was refused at its own scope's cap, as its wrapper wrote it, or
   nil when it wrote none (an unscoped run) or none were refused."
  [rdir]
  (let [f (refusals-file rdir)]
    (when (.exists f)
      (some-> (slurp f) str/trim parse-long (#(when (pos? %) %))))))

(defn- refused-count
  "Forks refused while a run ran: what its own scope refused it, and what the cgroups the core is in
   refused from `before` to `after`; nil when neither moved."
  [own before after]
  (let [shared (when (and before after (> after before)) (- after before))]
    (when (or own shared) (+ (or own 0) (or shared 0)))))

(defn ^{:malli/schema [:=> [:cat :map [:maybe :string] [:maybe :int] :int] :map]} classify-exhaustion
  "`outcome`, a finished run's, as `:resource-exhausted` when it failed and the host refused it
   resources: its `err` says so, or `refused` forks were refused it while it ran — at its own
   scope's cap, or in the cgroups beside it. A run that completed or asked stays as it was, whatever
   the host did beside it. `now` is when it was seen, from which the conductor's backoff is counted."
  [outcome err refused now]
  (let [refused (when (and refused (pos? refused)) refused)]
    (if (and (= :failed (:outcome outcome))
             (or refused (exhaustion-message? err)))
      (cond-> (assoc outcome :detail (cond-> {:reason :resource-exhausted :at now}
                                       (:detail outcome) (assoc :was (:detail outcome))
                                       refused           (assoc :pid-refusals refused)
                                       (exhaustion-message? err) (assoc :message (refusal-excerpt err)))))
      outcome)))

;; ── a scope per run ──────────────────────────────────────────────────────────

(def run-tasks-max
  "The processes and threads one run may hold at once, set as its own scope's `TasksMax`.

   Per run, so that a run which storms is refused at its own cap — recorded :resource-exhausted and
   retried — while every other run, and the core, go on forking. One cap on the core's service
   shared by every run is what failed on 2026-11-25: one storm at 96,330 refused the core and every
   seat beside it. 8,000 is far above what a working seat holds (an agent, its tool shells, and the
   threads of a JVM it starts), and far below the host's threads-max, so a few runs at their cap at
   once leave the host forking."
  8000)

(defn- scope-unit
  "The transient scope's unit name for the run in `rdir`: a digest of the run's directory and
   nothing of its turn id, which is a free string — `<unit> first` has a space — and no unit name
   may carry every character a turn id can."
  [rdir]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str rdir) StandardCharsets/UTF_8))]
    (str "nido-run-" (apply str (map #(format "%02x" (bit-and % 0xff)) (take 8 digest))) ".scope")))

(defn ^{:malli/schema [:=> [:cat [:maybe :map] :string :string :int] [:sequential :string]]} scope-argv
  "What to run in front of the wrapper so the run in `rdir` (turn `turn`, run `run`) is in a scope
   of its own with `scope`'s `:tasks-max`; empty without a scope. The command after it reaches the
   run verbatim: systemd-run otherwise expands `$NAME` and `$$` in it, rewriting any argument — an
   agent's prompt — that holds a `$`."
  [scope rdir _turn _run]
  (if-let [{:keys [systemd-run tasks-max]} scope]
    [systemd-run "--user" "--scope" "--quiet" "--collect" "--expand-environment=no"
     (str "--unit=" (scope-unit rdir))
     "-p" (str "TasksMax=" tasks-max) "--"]
    []))

(defn ^{:malli/schema [:=> [:cat [:maybe :string] :int] [:maybe :map]]} probe-scope
  "A scope for runs — {:systemd-run :tasks-max} — when `systemd-run` (a path, or nil for none on
   PATH) can start one under a user systemd manager; nil when it cannot, and runs start as they
   did before scopes: in the core's own cgroup, under its limits. Asked once, by starting a scope
   that runs `true`, since a binary on PATH proves no manager answers — with every flag a run's
   scope is started with, so a systemd too old to take one (`--expand-environment`, systemd 254)
   starts runs unscoped rather than failing each."
  [systemd-run tasks-max]
  (when systemd-run
    (try
      (let [p (-> (ProcessBuilder. ^java.util.List [systemd-run "--user" "--scope" "--quiet" "--collect"
                                                   "--expand-environment=no" "-p" (str "TasksMax=" tasks-max) "--" "true"])
                  (.redirectErrorStream true)
                  (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                  (.start))]
        (if (and (.waitFor p 10 TimeUnit/SECONDS) (zero? (.exitValue p)))
          {:systemd-run systemd-run :tasks-max tasks-max}
          (do (.destroyForcibly p) nil)))
      (catch Exception _ nil))))

(defn- default-scope
  "A scope per run on Linux, where a user systemd manager can give one; nil elsewhere (macOS keeps
   every run in the core's own process group)."
  []
  (when (str/starts-with? (str/lower-case (System/getProperty "os.name")) "linux")
    (probe-scope (some (fn [d] (let [f (io/file d "systemd-run")] (when (.canExecute f) (str f))))
                       (str/split (or (System/getenv "PATH") "") (re-pattern File/pathSeparator)))
                 run-tasks-max)))

;; ── the runner ───────────────────────────────────────────────────────────────

(defn- report! [what ^Throwable t]
  (binding [*out* *err*] (println "nido.core.turn:" what "-" (.getMessage t))))

(defn- outcome-key [ws turn run] (str "outcome/" ws "/" turn "/" run))

(defn- submit-stuck!
  "Report that run [ws turn run], which the runner ended because of `why`, is still alive."
  [{:keys [submit]} ws turn run why]
  (submit {:key (str "stuck/" ws "/" turn "/" run) :ws ws
           :command {:command :record-stuck :turn turn :run run :reason why}}))

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

(defn- ended-outcome
  "The outcome of run `k` in `rdir`, which has ended, by why the runner ended it (`ended-by`): a
   deadline cancels it, and otherwise it is its output's, read as :resource-exhausted when it
   failed and the host refused it (`classify-exhaustion`) — `refused` forks while it ran, or its
   err saying so — whether it exited on its own or the runner ended it."
  [runner executor k rdir refused]
  (case (ended-by runner k rdir)
    :deadline {:outcome :cancelled :detail {:reason :deadline}}
    (classify-exhaustion (finished-outcome executor rdir) (run-err rdir) refused
                         (System/currentTimeMillis))))

(defn- on-end!
  "The run [ws turn run] has ended: stop its deadline, and report its outcome."
  [{:keys [live refusals] :as runner} executor ws turn run rdir]
  (let [{:keys [deadline]} (get @live [ws turn run])
        before (or (run-refusals-before rdir) (get-in @live [[ws turn run] :refusals-before]))
        ;; Read while the run is still live, so a reason decided in memory is seen.
        outcome (ended-outcome runner executor [ws turn run] rdir
                               (refused-count (run-refusals rdir) before (refusals)))]
    (some-> deadline (.cancel false))
    (swap! live dissoc [ws turn run])
    (submit-outcome! runner ws turn run outcome)))

(defn- kill-until-delivered!
  "Kill live run `k` in `rdir` and everything it started. While no process can be reached — its
   pid not yet published — try again every :kill-retry-ms for as long as the run is live, so an
   end decided before the run could be reached still reaches it."
  [{:keys [live ^ScheduledExecutorService timer kill-retry-ms] :as runner} k rdir]
  (when (and (contains? @live k) (not (kill-run! rdir)))
    (.schedule timer ^Runnable (fn [] (try (kill-until-delivered! runner k rdir)
                                           (catch Throwable t (report! (str "ending " k) t))))
               (long kill-retry-ms) TimeUnit/MILLISECONDS)))

(defn- end-run!
  "End the live run [ws turn run] because of `why` (:deadline, :settled, or the reason the store
   cancelled it): kill it and everything it started, and report it stuck if it is still alive
   :kill-grace-ms later. Its outcome is reported when its process ends (`on-end!`). Ending a run
   already being ended does nothing."
  [{:keys [live ^ScheduledExecutorService timer kill-grace-ms] :as runner} ws turn run why]
  (let [k [ws turn run]
        [old _] (swap-vals! live #(if (and (contains? % k) (not (get-in % [k :ended-by])))
                                    (assoc-in % [k :ended-by] why)
                                    %))]
    (when (and (contains? old k) (not (get-in old [k :ended-by])))
      ;; Kept before the kill, so its exit reads it; a failure to keep it never spares the run.
      (try (spit (ended-by-file (get-in old [k :rdir])) (name why))
           (catch Throwable t (report! (str "keeping why " k " ended") t)))
      (kill-until-delivered! runner k (get-in old [k :rdir]))
      (.schedule timer ^Runnable (fn []
                                   (when (contains? @live k)
                                     (submit-stuck! runner ws turn run why)))
                 (long kill-grace-ms) TimeUnit/MILLISECONDS))))

(defn- watch!
  "Watch the run [ws turn run] in `rdir` until it ends, from `process` when this daemon started it,
   else from its lock; arm its deadline for what remains of `deadline-ms`. A run the runner had
   already ended (`ended-by`) is ended again for the same reason, so it is reported stuck while it
   lives on and its outcome is the one that reason gives."
  [{:keys [live refusals ^ScheduledExecutorService timer] :as runner} executor ws turn run rdir ^Process process deadline-ms]
  (let [launched (or (read-kept (launched-file rdir) parse-long)
                     (System/currentTimeMillis))
        remaining (max 0 (- (+ launched deadline-ms) (System/currentTimeMillis)))]
    ;; Registered before its deadline is armed: a deadline already past fires at once, and ending a
    ;; run that is not registered does nothing. Refusals are counted from the count its launch kept
    ;; in its directory; :refusals-before stands in only for a run launched before that count was
    ;; kept.
    (swap! live assoc [ws turn run] {:rdir rdir :executor executor :refusals-before (refusals)})
    (some->> (ended-by runner [ws turn run] rdir) (end-run! runner ws turn run))
    (let [dl (.schedule timer ^Runnable (fn [] (end-run! runner ws turn run :deadline))
                        (long remaining) TimeUnit/MILLISECONDS)]
      (swap! live assoc-in [[ws turn run] :deadline] dl))
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
  [{:keys [root default-deadline-ms scope refusals] :as runner} ws t run-stream?]
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
        (when-let [n (refusals)] (spit (refusals-before-file rdir) (str n)))
        (let [pb (doto (ProcessBuilder. ^java.util.List (-> (scope-argv scope rdir turn run)
                                                            (into ["python3" "-c" wrapper
                                                                   (str (lock-file rdir)) (str (pid-file rdir))
                                                                   (if scope (str (refusals-file rdir)) "")])
                                                            (into argv)))
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
              ;; A start the host refuses — the core's own fork, under the same limit as every
              ;; run — is that run's outcome. Unreported, the turn would stay running with a
              ;; directory, which nothing launches again, until the core restarted.
              p (try (.start pb)
                     (catch java.io.IOException e
                       (submit-outcome! runner ws (:id t) run
                                        (classify-exhaustion
                                         {:outcome :failed :detail {:reason :launch-failed :message (.getMessage e)}}
                                         (.getMessage e)
                                         (refused-count nil (run-refusals-before rdir) (refusals))
                                         (System/currentTimeMillis)))
                       nil))]
          (when p
            (watch! runner executor ws (:id t) run rdir p (or (:deadline-ms spec) default-deadline-ms))))))))

(defn- converge!
  "Start or cancel what workstream `ws`'s committed `state` says, that this runner has not."
  [{:keys [root live] :as runner} ws state]
  (doseq [t (vals (:turns state))
          :let [rdir (run-dir (turn-dir root ws (:id t)) (or (:run t) 1))]]
    (cond
      (and (= :running (:status t)) (not (.exists (io/file rdir))))
      (launch! runner ws t (some? (:run state)))

      (and (= :cancelled (:status t)) (:ended-by t) (contains? @live [ws (:id t) (:run t)]))
      (end-run! runner ws (:id t) (:run t) (:ended-by t)))))

(defn- settle!
  "End every live run that has given its final answer: its executor has read it settled at every
   check for :settle-ms. The window runs from the first settled reading, not from the output's last
   change, so a child still writing to the run's output after the answer cannot hold it open.
   Output is read again only once it changes."
  [{:keys [live settle-ms] :as runner}]
  (let [now (System/currentTimeMillis)]
    (doseq [[[ws turn run :as k] {:keys [rdir executor ended-by read-at settled-since]}] @live
            :let [settled? (:settled? executor)
                  out (io/file rdir "out")
                  modified (.lastModified out)]
            :when (and settled? (not ended-by) (.exists out))]
      (let [since (if (= read-at modified)
                    settled-since
                    (when (try (settled? rdir) (catch Throwable t (report! (str "settling " rdir) t) false))
                      (or settled-since now)))]
        (if (and since (<= settle-ms (- now since)))
          (end-run! runner ws turn run :settled)
          (swap! live #(cond-> % (contains? % k)
                         (update k assoc :read-at modified :settled-since since))))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]} start
  "Start a runner. Options:
     :root                 directory the turns' runs live under
     :executors            {executor-kw executor}
     :submit               (fn [envelope]) a command to the writer (`nido.core.writer/submit!`)
     :default-deadline-ms  a run's deadline when its spec names none (default 2 hours)
     :refusals             (fn [] -> int or nil) forks the host has refused so far
                           (default `cgroup-pid-refusals`); a failed run during which it rose is
                           reported :resource-exhausted
     :scope                {:systemd-run path :tasks-max n} to start each run in a transient scope
                           of its own under that cap, or nil to start runs in the core's cgroup;
                           absent, `probe-scope` with `run-tasks-max` on Linux, nil elsewhere
     :settle-ms            how long a run reads settled before it is ended
                           (default 2 minutes)
     :check-ms             how often runs are checked for that (default 15 seconds)
     :kill-grace-ms        how long a run the runner ended may live on before it is reported
                           stuck (default 30 seconds)
     :kill-retry-ms        how often a run being ended is tried again while no process of it can
                           be reached yet (default 200 ms)
   Feed it committed changes with `changed!`; take back what a previous daemon left with
   `take-back!`."
  [{:keys [root executors submit default-deadline-ms refusals settle-ms check-ms kill-grace-ms kill-retry-ms]
    :as opts
    :or {default-deadline-ms (* 2 60 60 1000) refusals cgroup-pid-refusals
         settle-ms (* 2 60 1000) check-ms 15000 kill-grace-ms 30000 kill-retry-ms 200}}]
  (let [runner {:root root :executors executors :submit submit
                :default-deadline-ms default-deadline-ms
                :refusals refusals
                :scope (if (contains? opts :scope)
                         (:scope opts)
                         (let [sc (default-scope)]
                           (binding [*out* *err*]
                             (println "nido.core.turn:" (if sc
                                                          (str "each run starts in a scope of its own, TasksMax=" (:tasks-max sc))
                                                          "no user systemd scope; runs start in the core's cgroup")))
                           sc))
                :settle-ms settle-ms :kill-grace-ms kill-grace-ms :kill-retry-ms kill-retry-ms
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
    (.scheduleWithFixedDelay ^ScheduledExecutorService (:timer runner)
                             ^Runnable (fn [] (try (settle! runner)
                                                   (catch Throwable e (report! "settling" e))))
                             (long check-ms) (long check-ms) TimeUnit/MILLISECONDS)
    (assoc runner :thread t)))

(defn ^{:malli/schema [:=> [:cat :map [:sequential :map]] :nil]} changed!
  "Hand the runner a commit's changes [{:ws :state} ...]. Never blocks: the runner converges on
   them on its own thread, so this may be the writer's publish."
  [runner changes]
  (.put ^LinkedBlockingQueue (:queue runner) (vec changes))
  nil)

(defn ^{:malli/schema [:=> [:cat :map :string :map] :nil]} take-back!
  "Take back every turn of workstream `ws`'s committed `state` whose run the core awaits
   (`d/run-awaited?`), as a starting daemon does: watch a run that still runs, report one that
   ended while no daemon held it, and report a run that never started as failed. A run of a turn
   the store cancelled that still runs is watched and ended again. Starts nothing."
  [{:keys [root default-deadline-ms] :as runner} ws state]
  (doseq [t (vals (:turns state))
          :when (d/run-awaited? t)
          :let [{turn :id run :run} t
                rdir (run-dir (turn-dir root ws turn) run)
                executor (executor-of runner t)]]
    ;; Each run on its own: what one run's directory keeps going wrong is that run's, and every
    ;; other is still taken back.
    (try
     (cond
       (= :cancelled (:status t))
       (when (and executor (.exists (launched-file rdir)))
         (if (lock-free? rdir)
           ;; Its exit is recorded, which clears a stuck report.
           (submit-outcome! runner ws turn run (ended-outcome runner executor [ws turn run] rdir (run-refusals rdir)))
           (do (watch! runner executor ws turn run rdir nil (or (get-in t [:spec :deadline-ms]) default-deadline-ms))
               (end-run! runner ws turn run (:ended-by t)))))

       (or (nil? executor) (not (.exists (launched-file rdir))))
       ;; The directory is claimed first, so no change converged before this outcome commits can
       ;; start the run after all.
       (do (.mkdirs (io/file rdir))
           (submit-outcome! runner ws turn run {:outcome :failed :detail {:reason :never-started}}))

       ;; Ended while no daemon held it: nothing counted the core's cgroups at its end, so only its
       ;; err and its own scope's count can say the host refused it.
       (lock-free? rdir)
       (submit-outcome! runner ws turn run (ended-outcome runner executor [ws turn run] rdir (run-refusals rdir)))

       :else
       (watch! runner executor ws turn run rdir nil (or (get-in t [:spec :deadline-ms]) default-deadline-ms)))
     (catch Throwable e (report! (str "taking back " ws " " turn " run " run) e))))
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
