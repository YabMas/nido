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
     list     project-wide overview

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
   [clojure.string :as str]
   [nido.coordinator.lane.scratch :as scratch]
   [nido.platform.process :as process]
   [nido.platform.task-args :as task-args]
   [nido.session.fleet :as fleet]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]))

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

(defn budget-report
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

(defn interactive?
  "Whether a human is on the other end. Public because it and `confirm?` are the
   only two IO seams in the pre-flight — a test redefines them and exercises the
   real decision and the real wording."
  []
  (some? (System/console)))

(defn confirm?
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

(defn up
  "Bring the named session up. Creates the worktree (if missing) + starts
   PG/JVM/app, then prints the session-home path the user can cd into to
   start their preferred agent (claude, codex, …). Idempotent — running
   on a live session refreshes the session-home artifacts but doesn't
   restart services. Kwargs like :base, :branch, :session-profile, :jvm-heap-max
   flow into `up!`.

   Reports what the live fleet already costs before starting anything, and asks
   first when this session is projected to push the machine past the budget.
   `:yes true` skips the question."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (if-not (budget-ok? project session opts)
      ;; Declining is an ordinary outcome, not a failure — it prints one line
      ;; and stops, rather than throwing a task error at someone who said no.
      (println "Aborted — no session started.")
      (do
        (lifecycle/up! session opts)
        ;; Weight is read back AFTER up! — up! persists the resolved profile, so
        ;; the record describes what was really provisioned, not what was asked for.
        (scratch/birth! (keyword project) session (lifecycle/session-weight session opts))
        (let [home (state/session-home-dir project session)]
          (println)
          (println (str "Session ready: " project "/" session))
          (println (str "  cd " home))
          (println (str "  bb nido:session:enter :project " project " " session)))))))

(defn down
  "Stop the named session. Worktree and on-disk state are preserved."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/down! session opts)))

(defn reset
  "Nuclear recovery: bring the session down, drop its PGDATA, then bring
   it back up against a fresh template clone. Use after
   `bb nido:template:pg:refresh` or when a session has wedged into a
   bad state."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/reset! session opts)))

(defn destroy
  "Bring the named session down and remove its worktree.
   Pass :delete-branch? true to also drop the git branch.
   Reaps the session's loose (scratch) workstream when it never grew a ref or
   ledger entry; a Notion/GitHub workstream (carrying a ref) is left intact."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/destroy! session opts)
    (scratch/reap! (keyword project) session)))

(defn enter
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
        _project   (require-project opts)
        session    (require-session-name pos)
        opts'      (cond-> opts
                     (contains? opts :auto-up) (-> (assoc :auto-up? (:auto-up opts))
                                                   (dissoc :auto-up)))]
    (lifecycle/enter! session opts')))

(defn status
  "Print status for the named session."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/status session opts)))

(defn list-sessions
  "List every session for a project."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)]
    (require-no-positional pos)
    (lifecycle/list-all opts)))

(defn isolate
  "Switch a session to a private Postgres clone so it can run destructive
   tests without affecting the shared cluster. Reverse with `share`.
   Usage: bb nido:session:isolate :project <p> <session>"
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/isolate! session opts)))

(defn share
  "Switch a session back to the shared Postgres cluster, dropping its private
   clone. Usage: bb nido:session:share :project <p> <session>"
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/share! session opts)))
