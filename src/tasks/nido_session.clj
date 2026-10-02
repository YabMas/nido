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
  "Bring `session` up on `ws-id` (nil: the name's holder, else a minted
   one-off) and print where it lives. Returns the session-home, or nil when
   the person declined at the budget question. Exits 1 — before anything is
   provisioned — when the session cannot join `ws-id`."
  [project session ws-id opts]
  (let [p (keyword project)]
    (when-let [why (and ws-id (scratch/joinable p session ws-id))]
      (println (str "Refused — " session " cannot start on " ws-id ": "
                    (case (:reason why)
                      :no-such-workstream "no such workstream"
                      :closed             "that workstream is closed"
                      :name-held          (str "the name already belongs to workstream " (:holder why)))))
      (exit! 1))
    (if-not (budget-ok? project session opts)
      ;; Declining is an ordinary outcome, not a failure — it prints one line
      ;; and stops, rather than throwing a task error at someone who said no.
      (do (println "Aborted — no session started.") nil)
      (do
        (when ws-id (scratch/birth! p session nil ws-id))
        (lifecycle/up! session opts)
        ;; Weight is read back AFTER up! — up! persists the resolved profile, so
        ;; the record describes what was really provisioned, not what was asked for.
        (scratch/birth! p session (lifecycle/session-weight session opts) ws-id)
        (let [home (state/session-home-dir project session)]
          (println)
          (println (str "Session ready: " project "/" session
                        (when ws-id (str " on workstream " ws-id))))
          (println (str "  cd " home))
          (println (str "  bb nido:session:enter :project " project " " session))
          home)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  up
  "Bring the named session up. Creates the worktree (if missing) + starts
   PG/JVM/app, then prints the session-home path the user can cd into to
   start their preferred agent (claude, codex, …). Idempotent — running
   on a live session refreshes the session-home artifacts but doesn't
   restart services. Kwargs like :base, :branch, :session-profile, :jvm-heap-max
   flow into `up!`.

   `:ws-id <id>` puts the session on that existing workstream — how a forked
   child gets a session — instead of on a minted one-off. A workstream the
   session cannot join (missing, closed, or the name already another's) is
   refused and exits 1 before anything is provisioned; otherwise the session
   record is written first, so the name is claimed before the worktree exists.

   Reports what the live fleet already costs before starting anything, and asks
   first when this session is projected to push the machine past the budget.
   `:yes true` skips the question."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (up* project session (some-> (:ws-id opts) str) (dissoc opts :ws-id))
    nil))

;; ── spawn: a child session with an agent already on its brief ────────────────

(defn- session-uri
  "The :session link URL naming a session. A URI rather than a web URL because
   a session has none; it only has to be unique and stable for link dedupe."
  [project session]
  (str "nido://session/" project "/" session))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  kickoff-prompt
  "The child agent's first turn: who it answers to, when to write, then the
   brief verbatim. Only the child's half of the protocol lives here; the
   parent's half is in the spawn-session skill, which is what chooses to spawn."
  [{:keys [parent-agent parent-project parent-session child brief]}]
  (str "You are the agent of a child nido session, `" child "`, spawned by the agent `"
       parent-agent "` (session " parent-project "/" parent-session ") to work on the brief "
       "below in its own focus area.\n"
       "\n"
       "## Talking to your parent\n"
       "\n"
       "Your parent is reachable with SendMessage to `" parent-agent "`; it reaches you as `"
       child "`. Message it:\n"
       "\n"
       "- when the brief is done — what landed, where, and anything you left open;\n"
       "- when you are blocked on a decision that is the parent's or the person's to make;\n"
       "- when you find something that changes the brief's premise or touches another part "
       "of the parent's arc.\n"
       "\n"
       "Not for progress narration. Each message must stand on its own — it may be read "
       "long after you send it. A message from your parent is direction on this work; the "
       "person at this terminal outranks you both.\n"
       "\n"
       "## Brief\n"
       "\n"
       brief "\n"))

(defn- parent-coords!
  "[project session] of the session this command runs inside — the parent."
  []
  (or (when-let [s (lifecycle/session-from-cwd)] [(:project s) (:session s)])
      (lifecycle/session-home-coords-from-cwd)
      (throw (ex-info "spawn must run inside the parent session — its worktree or session home"
                      {:cwd (System/getProperty "user.dir")}))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  spawn
  "Bring a child session up and start its agent on a brief, in a new Warp tab
   beside the caller. Run from inside the parent session.

     bb nido:session:spawn :project <p> <child> :parent-agent <name> :brief-file <path>
                           [:ws-id <id>] [any up option]

   `:parent-agent` is the caller's own agent name, the one SendMessage reaches
   it by. The child goes through exactly what `up` does — `:ws-id` included,
   which is how a forked child is spawned — then each session gets a :session
   link naming the other, a kickoff is staged, and a tab opens on the child.
   The child's agent answers to the child's session name.

   The kickoff runs from a zsh hook; without it the tab is a bare shell and
   the printed command starts the agent by hand. Outside Warp no tab opens."
  [& args]
  (let [[pos opts]   (task-args/split-args args #{:parent-agent :brief-file})
        project      (require-project opts)
        child        (require-session-name pos)
        parent-agent (or (:parent-agent opts)
                         (throw (ex-info "Missing :parent-agent <your agent name>"
                                         {:hint "Your own name as other sessions message you — ListAgents prints it."})))
        brief-file   (or (:brief-file opts)
                         (throw (ex-info "Missing :brief-file <path>" {})))
        brief        (slurp brief-file)
        [pp ps]      (parent-coords!)
        hook?        (lifecycle/kickoff-hook-installed?)]
    (when (and (= pp project) (= ps child))
      (throw (ex-info "A session cannot spawn itself" {:session child})))
    (when-not hook?
      (println "The kickoff hook is not in ~/.zshrc, so the child's agent will not start by itself.")
      (println "Add this once, then open new shells:")
      (println)
      (println lifecycle/kickoff-hook-snippet))
    (when-let [home (up* project child (some-> (:ws-id opts) str)
                         (dissoc opts :ws-id :parent-agent :brief-file))]
      (lifecycle/link-add! ps {:project pp :type :session :url (session-uri project child)
                               :title (str "child · agent " child)})
      (lifecycle/link-add! child {:project project :type :session :url (session-uri pp ps)
                                  :title (str "parent · agent " parent-agent)})
      (let [by-hand (lifecycle/stage-kickoff!
                     child {:project project :agent-name child
                            :prompt (kickoff-prompt {:parent-agent parent-agent :parent-project pp
                                                     :parent-session ps :child child :brief brief})})
            tab?    (lifecycle/warp?)]
        (when tab? (lifecycle/spawn-tab! child {:project project :cd :home}))
        (println)
        (println (str "Spawned " project "/" child " — its agent answers to `" child "`."))
        (cond
          (not tab?) (println (str "  Not in Warp: open a terminal and run  " by-hand))
          hook?      (println "  Its tab is open; the agent starts on the tab's first prompt.")
          :else      (println (str "  Its tab is open; start the agent there with  " by-hand)))
        home))))

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
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/reset! session opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  destroy
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
        _project   (require-project opts)
        session    (require-session-name pos)
        opts'      (cond-> opts
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
  list-sessions
  "List every session for a project."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)]
    (require-no-positional pos)
    (lifecycle/list-all opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  isolate
  "Switch a session to a private Postgres clone so it can run destructive
   tests without affecting the shared cluster. Reverse with `share`.
   Usage: bb nido:session:isolate :project <p> <session>"
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/isolate! session opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  share
  "Switch a session back to the shared Postgres cluster, dropping its private
   clone. Usage: bb nido:session:share :project <p> <session>"
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/share! session opts)))
