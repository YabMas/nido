(ns tasks.nido-session
  "Bb task entry points for the bundled session lifecycle. Every command
   requires `:project <name>` (the project registered via `nido:project:add`)
   and takes a single positional <session-name> (= git branch = worktree leaf).
   Kwargs and the positional may appear in any order.

   Surface:
     up       create worktree (if missing) + start PG/JVM/app — idempotent
     down     stop the session, leave worktree + state on disk
     reset    nuclear: down → drop PGDATA → re-clone template → up
     destroy  down + remove worktree
     enter    select session for the parent shell — writes the session-home
              path to ~/.nido/.last-cd; pair with the `nido` shell wrapper
              to actually `cd` (see Nido's CLAUDE.md). Pass :cd worktree to
              land in the code instead of the session-home.
     status   per-session liveness + ports
     sync-runtime  bring the runtime's checkout up to date with every member's working copy
     list     project-wide overview
     revive   bring back the sessions a host boot took down, and reopen their conversations

   Examples:
     bb nido:session:up      :project brian feat-auth
     bb nido:session:up      :project brian feat-auth :base develop
     bb nido:session:up      :project brian fix-bug   :branch existing-branch
     bb nido:session:up      :project brian feat-auth :jvm-heap-max 1500m
     bb nido:session:up      :project brian feat-auth :yes true   # skip the budget question
     bb nido:session:down    :project brian feat-auth
     bb nido:session:enter   :project brian feat-auth
     bb nido:session:enter   :project brian feat-auth :cd worktree
     bb nido:session:reset   :project brian feat-auth
     bb nido:session:destroy :project brian feat-auth :delete-branch? true
     bb nido:session:status  :project brian feat-auth
     bb nido:session:list    :project brian"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.record.workstream :as cws]
   [nido.coordinator.record.session-events :as session-events]
   [nido.coordinator.work :as work]
   [nido.platform.core :as core]
   [nido.platform.process :as process]
   [nido.platform.project :as project]
   [nido.platform.task-args :as task-args]
   [nido.session.casualties :as casualties]
   [nido.session.cold-starts :as cold-starts]
   [nido.session.fleet :as fleet]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.revive :as revive]
   [nido.session.state :as state]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  exit!
  "Redefable wrapper around System/exit, so tests can capture the exit code without killing the
   test JVM — the exit-test convention tasks.nido-ticket and tasks.nido-transcribe follow."
  [code]
  (System/exit code))

(defn- require-project [opts]
  (or (some-> (:project opts) name)
      (throw (ex-info "Missing :project <name>"
                      {:hint "Pass :project <project-name> — the name used in `bb nido:project:add`."}))))

(defn- require-session-name [positionals]
  (case (count positionals)
    0 (throw (ex-info "Missing session name (positional)"
                      {:hint "Usage: bb nido:session:<cmd> :project <project> <session>"}))
    1 (str (first positionals))
    (throw (ex-info "Too many positional args; expected one session name"
                    {:positionals positionals}))))

(defn- require-no-positional [positionals]
  (when (seq positionals)
    (throw (ex-info "Unexpected positional args; this command takes only kwargs"
                    {:positionals positionals}))))


;; ── fleet budget pre-flight ─────────────────────────────────────────────────
;;
;; A session costs ~1.5 GB at its floor and 3–4 GB in use, and nothing in nido
;; ever gave that number back to the person deciding to start another one. The
;; fleet that prompted this reached 18 live sessions and took the machine out
;; of memory. This is the one moment the cost is actionable — before the boot,
;; not after — so the whole feature is a question asked here and nowhere else.
;;
;; Deliberately only on this path. `lifecycle/up!` is also reached by the TUI,
;; by `enter :auto-up`, and by the coordinator spawning a Run; those are
;; resumes and headless work, and a prompt in front of the daemon would hang
;; the merge lane. The check is further gated on an interactive terminal, so a
;; scripted `bb nido:session:up` behaves exactly as it always did.

(defn- pct [part whole]
  (if (and part whole (pos? whole)) (str " (" (Math/round (* 100.0 (/ part whole))) "%)") ""))

(defn- idle-str [{:keys [idle-ms]}]
  (if (nil? idle-ms)
    "no agent yet"
    (let [h (quot idle-ms 3600000)]
      (if (>= h 48) (str "idle " (quot h 24) "d") (str "idle " h "h")))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  budget-report
  "The lines shown before a session boots. Pure — takes the facts, returns
   strings — so the wording is testable without a fleet or a terminal."
  [rows {:keys [sessions fleet in-use machine typical] :as totals} project session]
  (let [cands (fleet/candidates rows)]
    (concat
     [(str "Fleet budget: " sessions " live session" (when (not= 1 sessions) "s")
           " holding " (process/human-bytes fleet)
           " · machine " (process/human-bytes in-use) " / " (process/human-bytes machine)
           (pct in-use machine))
      (str "  " project "/" session " would be session #" (inc sessions)
           (when typical (str ", ~" (process/human-bytes typical) " more"))
           " → " (process/human-bytes (+ (or in-use 0) (or typical 0)))
           (pct (+ (or in-use 0) (or typical 0)) machine))]
     (when (seq cands)
       ;; Aligned on the longest name: the list is read to compare sizes, and
       ;; ragged columns make two-decimal gigabytes hard to compare at a glance.
       (let [names (mapv #(str (:project %) "/" (:session %)) cands)
             w     (apply max (map count names))]
         (cons "  Nothing is driving these — down one to make room:"
               (map (fn [c nm]
                      (format (str "    %-" w "s  %8s  %s")
                              nm (process/human-bytes (:bytes c)) (idle-str c)))
                    cands names))))
     (when (and (empty? cands) (seq rows))
       ;; Saying this out loud matters: the instinct is that some session must be
       ;; idle, and usually none is. Concurrency is the constraint, not neglect.
       ["  No idle sessions — every one of them has been touched today."]))))

(defn ^{:malli/schema [:=> [:cat] :any]}
  interactive?
  "Whether a human is on the other end. Public because it and `confirm?` are the
   only two IO seams in the pre-flight — a test redefines them and exercises the
   real decision and the real wording."
  []
  (some? (System/console)))

(defn ^{:malli/schema [:=> [:cat] :any]}
  confirm?
  "Ask, defaulting to no. Any answer but an explicit yes leaves the fleet alone."
  []
  (print "  Continue? [y/N] ")
  (flush)
  (contains? #{"y" "yes"} (some-> (read-line) str/trim str/lower-case)))

(defn- budget-ok?
  "True when the session should boot. Prints the fleet report first, and asks
   before booting one that is projected to cross the budget.

   Probes nothing unless a human is actually there to answer. That guard is not
   only about hangs: the snapshot shells out to `lsof` and `ps`, and every
   programmatic `up` — a test, a script, a resume — would otherwise pay for a
   report nobody reads. A non-interactive caller behaves exactly as it did
   before this existed.

   Returns true for a session that is already live (`up` is idempotent and
   routinely re-run just to refresh session-home artifacts — that costs no
   memory, so it earns no question), and true whenever the probes come back
   unreadable, because a measurement that failed must not block work."
  [project session opts]
  (if (or (:yes opts) (not (interactive?)))
    true
    (try
      (let [rows   (fleet/snapshot)
            live?  (some #(and (= project (:project %)) (= session (:session %))) rows)
            totals (fleet/totals rows project)]
        (cond
          live? true

          (not (fleet/over-budget? totals))
          (do (println (first (budget-report rows totals project session))) true)

          :else
          (do (println)
              (run! println (budget-report rows totals project session))
              (confirm?))))
      (catch Exception e
        (println (str "  (fleet budget unavailable: " (ex-message e) ")"))
        true))))


(defn- up*
  "Bring `session` up and print where it lives. Returns {:home}, or nil when the person declined
   at the budget question."
  [project session opts]
  (if-not (budget-ok? project session opts)
    ;; Declining is an ordinary outcome, not a failure — it prints one line
    ;; and stops, rather than throwing a task error at someone who said no.
    (do (println "Aborted — no session started.") nil)
    (let [_    (lifecycle/up! session opts)
          home (state/session-home-dir project session)]
      (println)
      (println (str "Session ready: " project "/" session))
      (when (false? (:wait opts))
        (println (str "  services are starting in the background — "
                      "bb nido:session:status :project " project " " session)))
      (println (str "  cd " home))
      (println (str "  bb nido:session:enter :project " project " " session))
      {:home home})))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  up
  "Bring the named session up. Creates the worktree (if missing) and the
   session home, prints the session-home path the user can cd into to start
   their preferred agent (claude, codex, …), and leaves PG/JVM/app booting in
   the background. `:wait true` returns only once they are up. Idempotent —
   running on a live session refreshes the session-home artifacts but doesn't
   restart services. Kwargs like :base, :branch, :session-profile, :jvm-heap-max
   flow into `up!`.

   The session is a person's own and no workstream's: it records nothing as work. A workstream's
   session is the core's to provision, so `:ws-id` is refused with the activation that does it.

   Reports what the live fleet already costs before starting anything, and asks
   first when this session is projected to push the machine past the budget.
   `:yes true` skips the question."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (when-let [ws-id (:ws-id opts)]
      (println (str "Refused — a workstream's session is the core's to provision: bb nido:activate :project "
                    project " :ws " ws-id))
      (exit! 1))
    (up* project session (update opts :wait #(if (nil? %) false %)))
    nil))

;; ── revive: bring back what died with the host ──────────────────────────────

(defn- session-name-of
  "The session name a casualty's worktree is registered under — its path relative to its
   project's worktrees dir — or nil when the project is gone or the worktree is not under it."
  [{:keys [project worktree]}]
  (try
    (when-let [dir (:directory (get (project/list-projects) project))]
      (let [rel (str (fs/relativize (lifecycle/worktrees-dir project dir) worktree))]
        (when-not (or (str/blank? rel) (str/starts-with? rel "..")) rel)))
    (catch Exception _ nil)))

(defn- conflicts-of
  "Whether the worktree's working copy holds unresolved conflicts — :none, :conflicted, or
   :unknown when the VCS would not answer. Read-only: jj is asked without snapshotting the
   working copy, so even this probe writes nothing."
  [worktree]
  (try
    (cond
      (fs/exists? (fs/path worktree ".jj"))
      (let [{:keys [exit out]} (p/shell {:dir worktree :out :string :err :string :continue true}
                                        "jj" "--ignore-working-copy" "log" "--no-graph"
                                        "-r" "@ & conflicts()" "-T" "change_id")]
        (cond (not (zero? exit)) :unknown (str/blank? out) :none :else :conflicted))

      (fs/exists? (fs/path worktree ".git"))
      (let [{:keys [exit out]} (p/shell {:dir worktree :out :string :err :string :continue true}
                                        "git" "diff" "--name-only" "--diff-filter=U")]
        (cond (not (zero? exit)) :unknown (str/blank? out) :none :else :conflicted))

      :else :unknown)
    (catch Exception _ :unknown)))

(defn- within? [path dir]
  (let [p (str (fs/normalize path)) d (str (fs/normalize dir))]
    (or (= p d) (str/starts-with? p (str d "/")))))

(defn- newest-conversation
  "The conversation whose last recorded event is newest among those recorded in the session's
   home or within its worktree."
  [conversations {:keys [project worktree]} session]
  (let [home (when session (state/session-home-dir project session))]
    (->> conversations
         (filter #(or (within? (:cwd %) worktree) (and home (within? (:cwd %) home))))
         (sort-by :last-at-ms >)
         first)))

(defn- machine-facts
  "Physical RAM and committed memory, each nil when unreadable, and each project's typical session
   cost in the live fleet."
  []
  (let [rows (try (fleet/snapshot) (catch Exception _ []))]
    {:physical  (fleet/machine-bytes)
     :committed (fleet/in-use-bytes)
     :typical   (into {} (for [p (distinct (keep :project rows))]
                           [p (:typical (fleet/totals rows p))]))}))

(defn- plan-line [{:keys [row facts why estimated-bytes]} session running]
  (let [{:keys [casualty conversation]} facts
        who  (str (:project casualty) "/" (or session (:instance-id casualty)))
        conv (cond (nil? conversation)            "no conversation recorded"
                   (running (:id conversation))   (str "conversation " (:id conversation) " already running")
                   :else                          (str "conversation " (:id conversation)))]
    (str "  " (format "%-18s" (name row)) who
         (case row
           :revive            (str "  ~" (process/human-bytes estimated-bytes) " · " conv)
           :conversation-only (str "  services stay down — "
                                   (if (= :conflicted why) "worktree has unresolved conflicts"
                                       "jj could not say whether the worktree is conflicted")
                                   " · " conv)
           :resumable         (str "  left down — "
                                   (if (= :memory-unknown why) "the memory budget could not be read"
                                       "over the memory budget")
                                   "; bring it back with :only " (or session (:instance-id casualty)))
           :gone              "  worktree is gone"))))

(defn- resume-command
  "The command that reopens conversation `id` where it ran, with permissions skipped as every
   session nido starts runs."
  [id]
  (str "claude --resume " id " --dangerously-skip-permissions"))

(defn- running-conversations
  "Every conversation Claude Code lists with a live process (`claude agents --json --all`), keyed
   by its session id; empty when the listing cannot be read."
  []
  (try (into {} (for [r (json/parse-string (:out (p/sh "claude" "agents" "--json" "--all")) true)
                      :when (:pid r)]
                  [(:sessionId r) r]))
       (catch Exception _ {})))

(defn- reopen!
  "Open `conversation` where it ran: a typed command in a Warp tab at its recorded cwd, or —
   outside Warp — the command and its directory printed for a person to run. A conversation
   already running is left where it is and said to be."
  [session conversation running]
  (cond
    (nil? conversation)
    (println (str "    " session ": no conversation recorded — nothing to reopen"))

    (running (:id conversation))
    (println (str "    " session ": conversation already running in " (:cwd (running (:id conversation)))
                  " — reach it with `claude agents`"))

    (lifecycle/warp?)
    (do (lifecycle/spawn-tab! session {:dir (:cwd conversation) :command (resume-command (:id conversation))})
        (println (str "    " session ": reopened in a new tab")))

    :else
    (println (str "    " session ": cd " (:cwd conversation) " && " (resume-command (:id conversation))))))

(declare on-its-workstream)

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  revive
  "Bring back the workstreams that died with the host: plan from those owed a revive — open, and
   holding a session a person works in — bring the admitted ones up in order on that session, and
   reopen its latest conversation where it ran. A workstream only Runs worked, and a conductor, are
   the daemon's, never revived here. A dry run prints the plan and changes nothing.

     bb nido:session:revive                       # newest first, under the memory budget
     bb nido:session:revive :dry-run true         # the plan, nothing started
     bb nido:session:revive :only [quiz rag-x]    # just these sessions (name or instance id)
     bb nido:session:revive :all true             # every one, whatever the budget
     bb nido:session:revive :dismiss [quiz]       # never offer these again"
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _          (require-no-positional pos)
        names      (fn [k] (some->> (get opts k) (#(if (sequential? %) % [%])) (map str) set))
        {:keys [casualties unreadable]} (work/owed-revivals)
        named      (mapv (fn [c] [c (or (:session c) (session-name-of c))]) casualties)
        pick       (fn [wanted] (filterv (fn [[c s]] (or (wanted s) (wanted (:instance-id c)))) named))]
    (doseq [f unreadable] (println (str "  ! casualty record could not be read: " f)))
    (cond
      (empty? casualties)
      (println "Nothing to revive — no workstream a person works in that died with the host is still down.")

      (names :dismiss)
      (let [hit (pick (names :dismiss))]
        (if (and (seq hit) (casualties/settle! (mapv first hit)))
          (println (str "Dismissed " (str/join ", " (map #(or (second %) (:instance-id (first %))) hit))))
          (do (println "Nothing dismissed — no unsettled casualty by those names.") (exit! 1))))

      :else
      (let [convs   (session-events/conversations)
            running (running-conversations)
            session (into {} (map (fn [[c s]] [(:instance-id c) s])) named)
            only    (when-let [w (names :only)] (set (map (comp :instance-id first) (pick w))))
            facts   (mapv (fn [[c s]]
                            (let [present? (fs/directory? (:worktree c))]
                              {:casualty     c
                               :worktree     (if present? :present :gone)
                               :conflicts    (if present? (conflicts-of (:worktree c)) :unknown)
                               :conversation (newest-conversation convs c s)}))
                          named)
            rows    (revive/plan {:facts facts :machine (machine-facts) :only only
                                  :all? (boolean (:all opts))})
            sname   #(get session (get-in % [:facts :casualty :instance-id]))]
        (println (str "Revive plan — " (count rows) " workstream" (when (not= 1 (count rows)) "s")
                      " that died with the host:"))
        (doseq [r rows] (println (plan-line r (sname r) running)))
        (when-not (:dry-run opts)
          (println)
          (doseq [{:keys [row facts] :as r} rows
                  :when (#{:revive :conversation-only} row)
                  :let [s (sname r)]]
            (if-not s
              (println (str "    " (get-in facts [:casualty :instance-id])
                            ": no session name for its worktree — left down"))
              (try
                (when (= :revive row)
                  (println (str "  ↑ " (get-in facts [:casualty :project]) "/" s))
                  (let [project (get-in facts [:casualty :project])]
                    (lifecycle/up! s (on-its-workstream project s {:project project}))))
                (reopen! s (:conversation facts) running)
                (casualties/settle! [(:casualty facts)])
                (catch Exception e
                  (println (str "    " s ": could not be brought up — " (ex-message e))))))))))
    nil))

(defn- goal-instruction
  "The first instruction of a session started on a goal: the brief verbatim, after what the brief
   cannot say for itself — that the workstream runs nido's recipe, so the goal is recorded as its
   intent, then surveyed and designed, before any code. The workstream then states what it is for
   and what it commits to, to everyone who reads it later, and the core holds the turn's code
   until a design is cleared."
  [brief]
  (str "This workstream was started for the goal below, runs nido's recipe, and nothing is "
       "recorded on it yet. Write what the recipe owes before any code, in order (the /design "
       "skill, §7, has each form): first record the goal as the workstream's :intent, with a "
       "falsifiable :done-when — if the goal cannot carry one, halt on a :blocker saying what is "
       "missing instead of inventing it; then the :baseline, judged by `bb nido:review:baseline`; "
       "then the :design, cleared by `bb nido:review:design`. Only then build the goal.\n\n"
       brief))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  start
  "Start work on a goal: a workstream minted for it and handed to the core, whose unit's session —
   named `<session>` — the core provisions and whose first turn is the brief. Run from anywhere:
   it adopts no session, the one it runs in included. How an exploration becomes nido's work is
   this, with its findings written into the brief.

     bb nido:workstream:start :project <p> <session> (:brief-file <path> | :brief \"…\")

   The turn is first told to write what the recipe owes — the brief's goal as the workstream's
   :intent, then its baseline and design — before any code, then given the brief verbatim. Prints the workstream's id. Exits 1, before anything is written, when a session
   of that name already exists — a start that joined it would hand its brief to a working copy
   it did not make."
  [& args]
  (let [[pos opts] (task-args/split-args args #{:brief-file :brief})
        project    (require-project opts)
        session    (require-session-name pos)
        p          (keyword project)
        brief      (cond (:brief-file opts) (slurp (:brief-file opts))
                         (:brief opts)      (str (:brief opts))
                         :else (throw (ex-info "Missing :brief-file <path> or :brief <text>" {})))]
    (when (str/blank? brief)
      (throw (ex-info "The brief is empty" {})))
    (when (or (cws/of-session p session)
              (fs/exists? (:wt-path (lifecycle/session-coords session {:project project}))))
      (println (str "Refused — a session named " session " already exists."))
      (exit! 1))
    (let [ws-id (work/start-goal! p session (goal-instruction brief))]
      (println (str "Started workstream " ws-id " — the core provisions " project "/" session
                    " and runs the brief as its first turn."))
      ws-id)))

(defn- on-its-workstream
  "`opts` naming, as the runtime key, the open workstream a unit of which has `session`
   (`cws/of-session`) — so a verb that starts its services brings it up on that workstream's
   runtime even while it is down. Unchanged for a person's own session, or when the caller named
   a key."
  [project session opts]
  (if-let [ws-id (and (not (:runtime-key opts))
                      (cws/of-session (keyword project) session))]
    (assoc opts :runtime-key ws-id)
    opts))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  down
  "Stop the named session. Worktree and on-disk state are preserved."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/down! session opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  reset
  "Nuclear recovery: bring the session down, drop its PGDATA, then bring
   it back up against a fresh template clone. Use after
   `bb nido:template:pg:refresh` or when a session has wedged into a
   bad state."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/reset! session (on-its-workstream project session opts))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  destroy
  "Bring the named session down and remove its worktree.
   Pass :delete-branch? true to also drop the git branch."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/destroy! session opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  enter
  "Hand off a cwd to the parent shell via `~/.nido/.last-cd`. Paired with
   a tiny shell function (see Nido's CLAUDE.md → Shell wrapper) the user
   lands in the chosen directory with no nested shell.

   `:cd home` (default) → session-home (CLAUDE.md, .mcp.json live here).
   `:cd worktree`       → the worktree symlink, falling back to the
                          on-disk worktree path when the session-home
                          is gone.
   `:auto-up true`      → bring the session up first (idempotent). The
                          TUI runs screen uses this so `↵` on a
                          downed run transparently resumes.

   Refuses if the session is down and `:auto-up` was not passed, or if
   `:cd worktree` is requested and neither the session-home symlink nor
   the on-disk worktree exists."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project    (require-project opts)
        session    (require-session-name pos)
        opts'      (cond-> (on-its-workstream project session opts)
                     (contains? opts :auto-up) (-> (assoc :auto-up? (:auto-up opts))
                                                   (dissoc :auto-up)))]
    (lifecycle/enter! session opts')))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  status
  "Print status for the named session."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/status session opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  sync-runtime
  "Bring the checkout the runtime of the session the current directory belongs to — its worktree
   or its session home — runs out of up to date with every member's working copy. Run it after
   changing files; the project's reload then reads them. Exits 1, changing nothing, when the
   working copies conflict or no session is found here.
   Usage: bb nido:session:sync"
  [& _]
  (try
    (if-let [dir (lifecycle/sync! (or (lifecycle/worktree-from-cwd) (str (fs/cwd))))]
      (println "Runtime checkout up to date:" dir)
      (println "This runtime runs out of a member's worktree; load your changes with load-file."))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println (ex-message e)))
      (exit! 1))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  list-sessions
  "List every session for a project."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)]
    (require-no-positional pos)
    (lifecycle/list-all opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  cold-starts
  "Print a project's on-demand JVM cold starts, oldest first — one session's when it is named:
   when each was asked for, how it ended, how long it took, read from its two instants, and that
   it was not admitted against the fleet's memory budget.
   Usage: bb nido:session:cold-starts :project <p> [<session>]"
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (first pos)
        rows    (cold-starts/starts project (some-> session str))]
    (if (empty? rows)
      (println (str "No on-demand JVM start recorded for " project (when session (str "/" session))))
      (do (println "On-demand JVM starts are not admitted against the fleet's memory budget.")
          (doseq [{:keys [session port requested-at outcome cause admission] :as r} rows
                  :let [ms (cold-starts/duration-ms r)]]
            (println (str requested-at "  " session "  " (name (or port :repl)) "  "
                          (name outcome) "  "
                          (if ms (format "%.1fs" (/ ms 1000.0)) "?")
                          "  admission " (name (or admission :bypassed))
                          (when cause (str "  — " cause)))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  isolate
  "Switch a session to a private Postgres clone so it can run destructive
   tests without affecting the shared cluster. Reverse with `share`.
   Usage: bb nido:session:isolate :project <p> <session>"
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/isolate! session (on-its-workstream project session opts))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  share
  "Switch a session back to the shared Postgres cluster, dropping its private
   clone. Usage: bb nido:session:share :project <p> <session>"
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/share! session (on-its-workstream project session opts))))





