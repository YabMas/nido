(ns nido.session.fleet
  "What the live fleet of sessions costs the machine, and which of them nobody
   is driving.

   Two measurements shaped this namespace, and both cut against the obvious
   design. A brian dev JVM costs ~1.5 GB even when idle and fully collected —
   metaspace and code cache dominate and no GC hands them back (a forced full
   GC on a 47h-idle session returned 186 MB and uncommitted no heap at all) —
   so a session is never cheap, only cheaper. And the sessions holding the most
   memory are usually the ones being ACTIVELY driven: on the fleet that
   prompted this, one session in ten was genuinely abandoned, holding 1.8 GB of
   17.4 GB. An idle-reaper would not have prevented the crash it was proposed
   to prevent.

   So the number worth surfacing is the fleet TOTAL against the machine, at the
   one moment a human can act on it — just before another session is added. The
   idle list rides along as context, and stays ADVISORY: nothing here stops or
   kills anything. That is deliberate. A wrong candidate costs a line of text;
   a wrong reap costs a live nREPL and whatever was loaded in it."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.platform.process :as process]
   [nido.session.state :as state]))

;; ── machine ─────────────────────────────────────────────────────────────────

(defn- sh-out
  "Run cmd, returning trimmed stdout, or nil on any failure. Every probe here
   is best-effort: a fleet report that throws because `sysctl` moved is worse
   than one that prints nothing."
  [& cmd]
  (try
    (let [{:keys [exit out]} (apply shell {:continue true :out :string :err :string} cmd)]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

(defn machine-bytes
  "Physical RAM in bytes, or nil when it can't be read."
  []
  (some-> (sh-out "sysctl" "-n" "hw.memsize") parse-long))

(defn in-use-bytes
  "Memory the machine currently has committed: active + wired + compressed.

   This is what Activity Monitor calls \"Memory Used\", and it is the right
   denominator for the budget — the fleet does not compete with an empty
   machine, it competes with Brave, Slack and OrbStack. Free and inactive pages
   are excluded because they are available for the next allocation."
  []
  (when-let [out (sh-out "vm_stat")]
    (let [page  (or (some-> (re-find #"page size of (\d+) bytes" out) second parse-long) 4096)
          pages (fn [label]
                  (or (some-> (re-find (re-pattern (str label ":\\s+(\\d+)")) out)
                              second parse-long)
                      0))]
      (* page (+ (pages "Pages active")
                 (pages "Pages wired down")
                 (pages "Pages occupied by compressor"))))))

;; ── the worktree -> session-home join ───────────────────────────────────────

(defn session-homes
  "Map of worktree path -> session-home path, read from the `worktree` symlink
   that every session home carries.

   This join is load-bearing and must NEVER be reconstructed from the
   instance-id. An instance-id drops the branch prefix — `brian--learning-goals`
   is the session living at `~/.nido/sessions/brian/feat/learning-goals` — so
   deriving a home from an id yields a path that does not exist. Every signal
   keyed on the home then comes back empty and an actively-driven session reads
   as untouched for days. That is not a hypothetical: it is how the first
   version of this measurement labelled a session with a two-minute-old agent
   as idle for 71 hours."
  []
  (let [root (state/sessions-root)]
    (when (fs/exists? root)
      (into {}
            (comp (filter fs/sym-link?)
                  (keep (fn [link]
                          (when-let [target (some-> (fs/read-link link) str)]
                            [target (str (fs/parent link))]))))
            (fs/glob root "*/**/worktree" {:max-depth 5})))))

;; ── activity signals ────────────────────────────────────────────────────────

(def ^:private transcript-root
  "Claude Code writes one JSONL per conversation under a directory named after
   the cwd, with `/` and `.` flattened to `-`."
  (fs/path (fs/home) ".claude" "projects"))

(defn transcripts-available?
  "Whether the agent-transcript signal can be read at all.

   Guarded because its absence is ambiguous in the dangerous direction: if the
   directory is gone or Claude Code changes where it writes, every session
   silently reads as never-driven. A signal that fails toward \"abandoned\" has
   to announce when it is blind."
  []
  (fs/exists? transcript-root))

(defn- encode-path [p]
  (str/replace (str p) #"[/.]" "-"))

(defn- newest-mtime
  "Newest mtime in ms across the JSONL transcripts for `dir`, or nil if that
   cwd has no transcripts."
  [dir]
  (let [d (fs/path transcript-root (encode-path dir))]
    (when (fs/exists? d)
      (->> (fs/glob d "*.jsonl")
           (map #(.toMillis (fs/last-modified-time %)))
           (reduce max 0)
           (#(when (pos? %) %))))))

(defn agent-last-seen
  "Newest agent-transcript mtime across a session's worktree and session-home,
   in ms, or nil when no agent has ever run in either.

   This is the signal the old idle-watchdog lacked, and the reason it misfired.
   Worktree mtime misses an agent that is reading, grepping or thinking, and an
   agent holds no nREPL socket — but Claude Code appends to its transcript once
   per turn, so a working agent touches this file whatever it is doing. On a
   live fleet it separated cleanly: driven sessions read minutes, abandoned
   ones read tens of hours, with nothing in between."
  [worktree home]
  (->> [worktree home]
       (remove nil?)
       (keep newest-mtime)
       (reduce max 0)
       (#(when (pos? %) %))))

(defn- cwd-index
  "One `lsof` pass: every process's cwd, as [pid dir] pairs. Threaded through
   the whole snapshot rather than forked per session — the previous watchdog's
   per-session `lsof` was slow enough to matter on a fleet this size."
  []
  (when-let [out (sh-out "lsof" "-a" "-d" "cwd" "-F" "pn")]
    (loop [lines (str/split-lines out), pid nil, acc []]
      (if-let [l (first lines)]
        (cond
          (str/starts-with? l "p") (recur (rest lines) (subs l 1) acc)
          (str/starts-with? l "n") (recur (rest lines) pid (conj acc [pid (subs l 1)]))
          :else                    (recur (rest lines) pid acc))
        acc))))

(defn- foreign-count
  "Processes sitting in this session's worktree or home that are NOT its own
   services.

   The self-exclusion is essential: a session's repl JVM has its cwd set to the
   worktree, so a naive count is never zero and every session looks driven —
   the same failure as the broken home join, in the opposite direction. Both
   directions produce a confident wrong answer, which is why this stays
   advisory."
  [cwds own-pids worktree home]
  (count (for [[pid dir] cwds
               :when (and pid
                          (not (contains? own-pids pid))
                          (or (str/starts-with? dir worktree)
                              (and home (str/starts-with? dir home))))]
           pid)))

(defn- established-on?
  "Whether any ESTABLISHED TCP connection targets `port`. One pass, shared."
  [socket-lines port]
  (boolean (and port (pos? port)
                (some #(str/includes? % (str ":" port "->")) socket-lines))))

(defn- socket-index []
  (some-> (sh-out "lsof" "-nP" "-iTCP" "-sTCP:ESTABLISHED") str/split-lines))

(defn- rss-index
  "One `ps` pass: pid -> resident bytes. `process/rss-bytes` shells per pid,
   which is fine for one session and wasteful for a whole fleet."
  []
  (when-let [out (sh-out "ps" "-Ao" "pid=,rss=")]
    (into {}
          (keep (fn [line]
                  (let [[pid kb] (str/split (str/trim line) #"\s+")]
                    (when-let [b (some-> kb parse-long)]
                      [pid (* 1024 b)]))))
          (str/split-lines out))))

;; ── the snapshot ────────────────────────────────────────────────────────────

(def stale-agent-ms
  "How long a session must go untouched before it is offered as a candidate.

   24 hours, and the length is the whole point. The watchdog that had to be
   removed used 30 minutes, which is inside the range of ordinary work — a
   meeting, a long read, lunch — so it needed activity detection to be right
   in real time, and no signal is. Over a full day the signals stop flickering:
   on the fleet this was measured against, the gap between the driven cluster
   and the abandoned one was 44 hours wide."
  (* 24 60 60 1000))

(defn candidate?
  "Whether nobody appears to be driving this session.

   `signals` reports which probes actually answered — `{:transcripts? :presence?
   :sockets?}` — and EVERY one must have. That is not symmetry for its own sake.
   A blind probe fails toward candidacy in both directions, and the second
   direction is the dangerous one: a blind transcript merely makes every session
   look never-driven, but a blind `lsof` makes `foreign` zero and `nrepl?` false
   for the entire fleet, which does not withhold a promotion — it DELETES the
   veto. Measured with lsof stubbed out: a session with 25 agent processes
   working in it reported `foreign=0`.

   The shape, which this namespace shipped twice before catching it: a guard
   that reads a POSITIVE signal is disarmed by the very failure that suppresses
   that signal, and disarmed silently. Absence has to block, not clear. The
   probes deliberately require a zero exit rather than salvaging partial output
   for the same reason — half an `lsof` is half a veto."
  [{:keys [foreign nrepl? idle-ms agent-seen-ms]}
   {:keys [transcripts? presence? sockets?]}]
  (boolean (and transcripts? presence? sockets?
                (zero? (or foreign 0))
                (not nrepl?)
                (or (nil? agent-seen-ms)
                    (> (or idle-ms 0) stale-agent-ms)))))

(defn signals-ok?
  "Whether every activity probe answered for this snapshot.

   Snapshot-wide, carried on each row because rows are what travel to callers.
   A surface that reports \"nothing is idle\" needs this: with the probes blind
   there are no candidates either, and the two states must not be told to a
   human in the same words."
  [rows]
  (every? :signals-ok? rows))

(defn snapshot
  "Every live session with what it costs and what has touched it lately.

   Rows: :instance-id :project :session :worktree :home :bytes :foreign
         :nrepl? :agent-seen-ms :idle-ms :candidate?

   Sessions whose repl pid is gone are dropped — a stale registry entry costs
   no memory, and naming it as a candidate would be noise. Each probe runs
   ONCE for the whole fleet and is threaded through; the watchdog this replaces
   forked `lsof` per session, which is slow enough to notice at this size."
  []
  (let [homes   (session-homes)
        cwds    (cwd-index)
        socks   (socket-index)
        rss     (rss-index)
        signals {:transcripts? (transcripts-available?)
                 :presence?    (some? cwds)
                 :sockets?     (some? socks)}
        seen?   (:transcripts? signals)
        now     (System/currentTimeMillis)
        entries (->> (state/read-registry) vals (filter :repl-pid))
        own     (into #{} (map (comp str :repl-pid)) entries)]
    (->> entries
         (filter #(process/process-alive? (:repl-pid %)))
         (map (fn [{:keys [instance-id project-name project-dir repl-pid nrepl-port]}]
                (let [home (get homes project-dir)
                      seen (when seen? (agent-last-seen project-dir home))
                      row  {:instance-id   instance-id
                            :project       project-name
                            :session       (some-> instance-id (str/replace #"^[^-]*--" ""))
                            :worktree      project-dir
                            :home          home
                            :bytes         (get rss (str repl-pid))
                            :foreign       (foreign-count cwds own project-dir home)
                            :nrepl?        (established-on? socks nrepl-port)
                            :agent-seen-ms seen
                            :idle-ms       (when seen (- now seen))}]
                  (assoc row
                         :signals-ok? (every? true? (vals signals))
                         :candidate?  (candidate? row signals)))))
         (sort-by (comp - #(or % 0) :bytes))
         vec)))

(defn totals
  "Fleet arithmetic for a snapshot: what the sessions hold, what the machine
   holds, and the typical cost of one more.

   `:typical` is the median of the same-project rows rather than a constant —
   the spread within one project is wide (1.6–4.1 GB on brian for an identical
   -Xmx2g) and the median self-calibrates as the fleet changes. It is nil
   below two samples, where a median is a guess wearing a number's clothes."
  [rows project]
  (let [same (->> rows (filter #(= project (:project %))) (keep :bytes) sort vec)]
    {:sessions (count rows)
     :fleet    (reduce + 0 (keep :bytes rows))
     :in-use   (in-use-bytes)
     :machine  (machine-bytes)
     :typical  (when (>= (count same) 2)
                 (nth same (quot (count same) 2)))}))

(def budget-fraction
  "Fraction of physical RAM past which booting another session gets a question
   rather than a silent start.

   0.7 leaves the browser, Slack and the container runtime the ~15 GB they
   actually occupy on a 48 GB machine. Above it, the next session is the one
   that pushes the machine into swap — which is where this began: 18 sessions,
   swap grown to 17 GB, and macOS's out-of-application-memory panel naming the
   terminal, because a coalition is charged for everything spawned inside it."
  0.7)

(defn over-budget?
  "Whether booting one more session is projected to cross the budget. False
   whenever the machine facts could not be read — an unreadable probe must not
   manufacture a warning."
  [{:keys [in-use machine typical]}]
  (boolean (and in-use machine (pos? machine)
                (> (+ in-use (or typical 0)) (* budget-fraction machine)))))

(defn candidates
  "Rows nobody appears to be driving, dearest first."
  [rows]
  (->> rows (filter :candidate?) (sort-by (comp - #(or % 0) :bytes)) vec))
