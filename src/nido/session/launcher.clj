(ns nido.session.launcher
  "Per-session artifacts written into the user-cd-able session home:

     ~/.nido/sessions/<project>/<session>/.mcp.json
     ~/.nido/sessions/<project>/<session>/CLAUDE.md
     ~/.nido/sessions/<project>/<session>/AGENTS.md
     ~/.nido/sessions/<project>/<session>/worktree -> <wt-path>
     ~/.nido/sessions/<project>/<session>/.claude  -> worktree/.claude

   Populated on session:up, removed on session:destroy. Internal nido
   bookkeeping (registry, session.edn, pg-data, logs) still lives under
   ~/.nido/state/<instance-id>/ — see nido.session.state."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as jio]
   [clojure.string :as str]
   [nido.config :as config]
   [nido.coordinator.shim :as coord-shim]
   [nido.coordinator.state :as cstate]
   [nido.core :as core]
   [nido.io :as io]
   [nido.session.agent-guidance :as agent-guidance]
   [nido.session.links :as links]
   [nido.session.profiles :as profiles]
   [nido.session.state :as state]))

(defn- run-workstream-context
  "Given a run-id or a session-home path, return {:workstream-id … :br-id …}
   for embedding in the briefing. Returns an empty map when no run is found or
   the run carries no :workstream-id — so callers can always safely merge the
   result without nil-checking. Safe to call on human/dry-run sessions."
  [& {:keys [run-id session-home]}]
  (let [run (cond
              run-id
              (let [path (cstate/run-edn-path run-id)]
                (when (fs/exists? path)
                  (io/read-edn path)))

              session-home
              (let [path (str (fs/path session-home "run-link" "run.edn"))]
                (when (fs/exists? path)
                  (io/read-edn path))))]
    (if-let [ws-id (:workstream-id run)]
      {:workstream-id ws-id
       :br-id         (get-in run [:event-payload :id])}
      {})))

(defn- pg-service-def [session-edn]
  (->> (:services session-edn)
       (filter #(= :postgresql (:type %)))
       first))

(defn- profile-path
  "Path to the persisted profile for a session. Mirrors the logic in
   engine.clj but kept local to avoid a circular require."
  [instance-id]
  (str (fs/path (state/instance-state-dir instance-id) "profile.edn")))

(defn- read-profile-for-session
  "Return the resolved profile persisted at session-up time. nil if absent
   (e.g. legacy sessions predating this feature)."
  [instance-id]
  (let [path (profile-path instance-id)]
    (when (fs/exists? path)
      (io/read-edn path))))

(defn- jj-source-repo?
  "True if `dir` is a jj-colocated source repo. The source's `.jj/repo`
   is a directory (the actual jj data); a workspace's `.jj/repo` is a
   pointer file. Mirrors nido.session.lifecycle/jj-source-repo? — kept
   private here to avoid a require cycle (lifecycle already pulls in
   launcher)."
  [dir]
  (fs/directory? (str (fs/path dir ".jj" "repo"))))

(defn- jj-workspace?
  "True if `wt-path` is a jj workspace (has any `.jj/` shape inside)."
  [wt-path]
  (fs/exists? (str (fs/path wt-path ".jj"))))

(defn- project-source-dir
  "Look up a project's source directory from the registry. Returns nil
   when the project isn't registered (rare — usually means a hand-edited
   projects.edn) so callers can fall through to a sensible default."
  [project-name]
  (some-> (config/read-projects)
          (get project-name)
          :directory))

(defn- vcs-mode
  "Where do edits land for this session? Resolved from filesystem state:

     :jj-workspace          — worktree has its own .jj/ (jj workspace add).
                              Edits in `worktree` are tracked by jj there.
     :jj-source-git-worktree — source repo is jj-colocated but this session
                              uses a legacy git worktree (no .jj/ inside).
                              jj's working copy is the *source* dir; the
                              worktree is invisible to jj.
     :plain-git             — plain-git source + git worktree. Edits in
                              `worktree` are tracked by git there.

   `source-dir` may be nil when the project's directory cannot be resolved
   from the registry; fall back to :plain-git in that case so the briefing
   stays useful even if the project entry was hand-edited."
  [worktree source-dir]
  (cond
    (jj-workspace? worktree)              :jj-workspace
    (and source-dir
         (jj-source-repo? source-dir))    :jj-source-git-worktree
    :else                                 :plain-git))

(defn mcp-path [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) ".mcp.json")))

(defn claude-md-path [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) "CLAUDE.md")))

(defn agents-md-path [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) "AGENTS.md")))

(defn worktree-link [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) "worktree")))

(defn- postgres-server
  "This session's postgres MCP entry, keyed for merging. nil when the session
   runs without a database."
  [pg-svc pg-port]
  (when (and pg-svc pg-port)
    (let [{:keys [db-name db-user db-password]} pg-svc
          url (format "postgresql://%s:%s@localhost:%s/%s"
                      db-user db-password pg-port db-name)]
      {:postgres
       {:type    "stdio"
        :command "npx"
        :args    ["-y" "@modelcontextprotocol/server-postgres" url]
        :env     {}}})))

(defn- repo-mcp-servers
  "MCP servers the project repo commits in `<worktree>/.mcp.json`. Read
   best-effort: a missing or malformed file yields no servers rather than
   failing session:up over a file nido doesn't own. `postgres` is dropped —
   the repo's entry points at the shared template port, and this session
   synthesises its own."
  [worktree]
  (when worktree
    (let [path (str (fs/path worktree ".mcp.json"))]
      (try
        (-> (io/read-json path) :mcpServers (dissoc :postgres))
        (catch Exception e
          (core/log-step (str "warning: unreadable " path " — its MCP servers "
                              "are omitted from this session: " (ex-message e)))
          nil)))))

(defn- registry-mcp-servers
  "MCP servers declared for a project in projects.edn under `:mcp-servers`.
   The home for servers that can't live in the project repo — host-local
   tooling, or anything whose config would pollute an unrelated branch."
  [project-name]
  (some-> (config/read-projects)
          (get project-name)
          :mcp-servers))

(defn- mcp-config
  "Compose the session's MCP config. Three layers, later wins:

     1. servers the project repo commits in `<worktree>/.mcp.json`
     2. servers projects.edn declares for the project (`:mcp-servers`)
     3. postgres, synthesised against this session's port

   Returns nil when no layer contributed a server, so callers can skip the
   write entirely. Claude Code resolves MCP servers per-cwd, and a nido
   session home is a cwd the user never registered anything against — so
   whatever a session should see has to be written here."
  [project-name worktree pg-svc pg-port]
  (let [servers (merge (repo-mcp-servers worktree)
                       (registry-mcp-servers project-name)
                       (postgres-server pg-svc pg-port))]
    (when (seq servers)
      {:mcpServers servers})))

(defn write-session-mcp!
  "Render the session's MCP config and write it to the instance-state dir as a
   launch input. Returns the path, or nil when no server is configured."
  [instance-id project-name worktree pg-svc pg-port]
  (when-let [doc (mcp-config project-name worktree pg-svc pg-port)]
    (let [path (state/session-mcp-path instance-id)]
      (fs/create-dirs (fs/parent path))
      (io/write-json! path doc)
      path)))

(defn- render-link-line
  "One bullet for a link: '- <url>' or '- <url> — <title>'."
  [{:keys [url title]}]
  (if (seq title)
    (str "- " url " — " title)
    (str "- " url)))

(defn- render-links-section
  "Render '## Relevant links' grouped by type. Returns nil when no links
   are present so the section is omitted entirely."
  [link-entries]
  (when (seq link-entries)
    (let [groups (links/group-by-type link-entries)]
      (str "## Relevant links\n"
           "\n"
           (->> groups
                (map (fn [[t ls]]
                       (str "**" (links/display-labels t (name t)) "**\n"
                            (str/join "\n" (map render-link-line ls))
                            "\n")))
                (str/join "\n"))
           "\n"))))

(def ^:private add-link-instructions
  (str "## Tracking session links\n"
       "\n"
       "When the user introduces a notion ticket, GitHub PR, slack thread,\n"
       "or any other context-worth URL during this session — or when you\n"
       "yourself create one (open a PR, file an issue, etc.) — persist it\n"
       "immediately so future sessions inherit it. **Do not ask permission**;\n"
       "adding relevant links is mandatory, not a suggestion. Run:\n"
       "\n"
       "    nido session:link:add :type <kw> :url <url> [:title \"...\"]\n"
       "\n"
       "Types: `:notion-ticket` `:pr` `:gh-issue` `:slack-thread` `:other`.\n"
       "Project + session resolve from the worktree. To remove:\n"
       "`nido session:link:remove :url <url>`. To inspect:\n"
       "`nido session:link:list`. Existing entries are listed under\n"
       "\"Relevant links\" above (when any).\n"))

(def ^:private shipping-doctrine-instructions
  (str "## Shipping doctrine\n"
       "\n"
       "Throughput here means **change landed per unit of reviewer attention**,\n"
       "at constant-or-rising trust. Agents make code cheap; verified\n"
       "comprehension is the bottleneck. So batch size is set by what a reviewer\n"
       "can hold in working memory — never by how much work happened to be in\n"
       "flight.\n"
       "\n"
       "**One batch = one claim.** Every batch asserts exactly one thing a\n"
       "reviewer can accept or reject, and ships the evidence for that claim\n"
       "(tests, a Verify list). \"Trust me\" spends the very resource this exists\n"
       "to conserve.\n"
       "\n"
       "### The frame both axes cut against\n"
       "\n"
       "Before either cut, say what is already there, then what the change is\n"
       "**for**. A finding is only meaningful relative to an intent: with none\n"
       "stated, \"this is wrong\" and \"this is not what we decided\" are\n"
       "indistinguishable, and every reviewer re-derives a different design from\n"
       "the diff.\n"
       "\n"
       "- **Survey before you decide.** One `:baseline` record per workstream,\n"
       "  written BEFORE the design and independent of it: the area, what bounds\n"
       "  it, the properties the code already relies on, and where it already\n"
       "  admits extension. Every field must be fillable without knowing the\n"
       "  change — an inference made by someone who already knows the fix is bent\n"
       "  toward the fix. See `/design` §4.\n"
       "- **Judge the work against it.** A defect that violates a load-bearing\n"
       "  property is an implementation defect; one that honours every one of\n"
       "  them and is still wrong indicts the design. A feature landing on an\n"
       "  existing extension point extends the design; one that does not is\n"
       "  asking the core to move. Same bit, derived not guessed, for a bug and\n"
       "  a feature alike.\n"
       "- **One design record per workstream**, in the ledger — shape, invariants,\n"
       "  what was rejected and why, the intended layer cut, and `:baseline`\n"
       "  declaring how the change stands to what was there (`:within`,\n"
       "  `:extends`, `:revisit` — and `:revisit` must name what it breaks).\n"
       "  Claims about structure, never a step list. See `/design`.\n"
       "- **The invariants are the yardstick.** They are what review checks\n"
       "  findings against and what `/spin-out`'s veto is run from. A design that\n"
       "  names none is unfalsifiable. On a PHASED record each also says when it\n"
       "  holds — `:always` at every phase boundary, or `:on-completion` — because\n"
       "  a plan makes intermediate states that are wrong on purpose.\n"
       "- **Declare the relation to the project stance** — conforms, extends, or\n"
       "  challenges. Silently challenging it is how architecture erodes with no\n"
       "  single change looking wrong.\n"
       "- **Cite the right layer.** The stance frames and cannot be violated by a\n"
       "  line; the review lanes and `docs/reference/` are what a diff can break.\n"
       "- **Amend a design that turns out wrong**, superseding and citing the old\n"
       "  record. Never quietly patch around it. A finding that shows the\n"
       "  BASELINE was wrong is a different failure — the design may be sound on\n"
       "  a bad premise, and the remedy is to re-survey, not to supersede.\n"
       "\n"
       "Work is then decomposed along three axes, and every unit lands in one of\n"
       "five destinations:\n"
       "\n"
       "| The unit is | Destination |\n"
       "|---|---|\n"
       "| same story, same claim | **this layer** |\n"
       "| same story, a different claim | **another layer** — vertical, `/stack` |\n"
       "| same story, a claim that needs the previous one **live** first | **another phase** — temporal, `/phase` |\n"
       "| a different story | **spun out** — horizontal, `/spin-out` |\n"
       "| a different story you will not do | **declined** — say so in the brief |\n"
       "\n"
       "Nothing is lost and nothing is smuggled: an observation either lands, is\n"
       "routed with a ref, or is explicitly declined.\n"
       "\n"
       "### Vertical — layers within one story\n"
       "\n"
       "- **Order by dependency.** If code in layer A depends on layer B, B is\n"
       "  in the same layer or lower. Common shape: foundation → core → wiring\n"
       "  → supersede (supersede always on top).\n"
       "- **One review mode per layer.** Mechanical or judgment, never mixed.\n"
       "- **One bookmark per layer**, named `<session>--<slug>`. Double dash,\n"
       "  content-named, never numbered. Never push the session bookmark.\n"
       "- **Every layer commit carries a `Layer:` trailer** — exactly one of\n"
       "  `mechanical`, `structural`, `behavioral` — plus a review brief\n"
       "  (Claims / Verify / Lane / Out of scope).\n"
       "- **One-sentence PR titles, no \"and\".** Needing \"and\" means it is\n"
       "  two layers.\n"
       "- **Don't stack small changes.** Under ~200 lines with no dependency\n"
       "  seam, ship one plain PR.\n"
       "- **How a batch LANDS is project-specific, and this section assumes the\n"
       "  most common answer** — a PR per layer. Some projects land by fast-\n"
       "  forwarding `main` locally and pushing, with no PR at all. **Read\n"
       "  `worktree/CLAUDE.md` before you land anything**; where it and this\n"
       "  section disagree, it wins. The decomposition above holds either way —\n"
       "  a layer is then a commit rather than a PR, and its brief goes in the\n"
       "  commit message, where it matters more rather than less: it is the only\n"
       "  artifact a later reader gets.\n"
       "\n"
       "### Temporal — what lands later, in production\n"
       "\n"
       "- **The boundary test is one question: must the system RUN in this\n"
       "  state?** No — it is a layer, and the merge dissolves the boundary\n"
       "  anyway. Yes — it is a phase, and the boundary is a deploy.\n"
       "- **Independent correctness is not independent deployability.** Green\n"
       "  tests at every layer is a fact about CI; habitable at every phase is a\n"
       "  fact about production. A stack lands in one merge; a phase does not.\n"
       "- **A stack lands in one merge only because someone collapses it.** A\n"
       "  merge queue merges its entries ONE AT A TIME, so a stack enqueued as n\n"
       "  PRs lands in pieces the moment anything fails mid-arc, and every layer\n"
       "  boundary becomes a deploy boundary. `/land` collapses the reviewed\n"
       "  stack into its top PR first; do not merge a stack any other way.\n"
       "- **Every phase must leave a system you would accept as permanent** — you\n"
       "  may be left in it. That bounds both failures at once: no big bang, and\n"
       "  a stall is a smaller win rather than a wound.\n"
       "- **Every phase names an exit criterion observed on the running system**\n"
       "  before the next one starts. No gate, no phase — it is a to-do with an\n"
       "  ordinal. Write it so it can fail.\n"
       "- **Say which phase is the point of no return**, put it as late as the\n"
       "  plan allows, and give it the strongest gate.\n"
       "- **Phase for exposure, never for size.** A large change is a stack; a\n"
       "  risky one is a plan. One phase is a shipment, not a plan; beyond that,\n"
       "  phases that share a cause are one boundary in the wrong place.\n"
       "- **Nothing sweeps for a phase plan mid-flight.** Name the next phase and\n"
       "  its gate in the last layer's `Out of scope:`, and keep the criterion\n"
       "  somewhere you will actually see it. See `/phase`.\n"
       "\n"
       "### Horizontal — what leaves the branch\n"
       "\n"
       "- **Never defer what would leave the branch untrue.** A half-applied\n"
       "  invariant or an invisibly-incomplete migration vetoes the spin-out.\n"
       "  Visibly incomplete (old path still standing) is fine; silently\n"
       "  incomplete is not.\n"
       "- **And every seam names what closes it** — a phase, a spun-out ref, or\n"
       "  `:permanent` with its reason. Visible but unscheduled is how a\n"
       "  temporary state becomes permanent with nobody deciding to let it.\n"
       "- **Did this branch create the problem, or reveal it?** Created → fix it\n"
       "  here. Revealed → candidate to spin out.\n"
       "- **Spend review cost, not lines.** Deleting the path you superseded\n"
       "  grows the diff and shrinks the review; a clever optimisation does the\n"
       "  reverse.\n"
       "- **Keep what is expensive to reload; spin out what is cheap to resume\n"
       "  cold.** You are holding context now that a follow-up has to rebuild.\n"
       "- **If you cannot write the ticket in three sentences with an acceptance\n"
       "  criterion, it is not a task** — it is an unresolved question. Resolve\n"
       "  it here, or spin out the question as a question.\n"
       "- **No spin-out without a ref.** \"Later\" in a PR comment is a wish.\n"
       "  File it, then name it in that layer's `Out of scope:`.\n"
       "- **Spin-outs that share a cause mean the boundary is wrong** — not\n"
       "  spin-outs that share a branch. Several unrelated stories leaving is\n"
       "  decomposition working; two that would both be answered by moving one\n"
       "  boundary is the ticket drawn across it.\n"
       "\n"
       "Each batch leaves the codebase better **along the axis it was already\n"
       "moving on** — not better everywhere (scope creep), not merely no worse\n"
       "(decay).\n"
       "\n"
       "Invoke `/design` first, then `/stack` and `/spin-out`, all **at planning\n"
       "time**. Read only at ship time, any of the three arrives after the work is\n"
       "already a heap — and a design written then is a description, not a\n"
       "decision.\n"))

(defn- render-edit-location
  "The 'where do edits land' paragraph, conditioned on vcs-mode so the
   briefing tells the truth for legacy git-worktree sessions inside a
   jj-colocated source. `source-dir` is shown only when it differs from
   the worktree (i.e. the legacy mixed case)."
  [vcs-mode source-dir]
  (case vcs-mode
    :jj-workspace
    (str "You are working through the nido orchestrator. You are in the session's\n"
         "worktree; source-code edits land here, NOT in nido's source tree. This\n"
         "worktree is a jj workspace; `jj st` / `jj log` / `jj git push` work in place.\n"
         "\n"
         "**Do not use bare `git` in this worktree.** It is a non-colocated jj\n"
         "workspace nested inside the colocated source repo, so bare `git` silently\n"
         "binds to the *parent source repo* and returns wrong content and history\n"
         "(`git rev-parse --show-toplevel` prints the source dir, not this worktree).\n"
         "jj is the source of truth here:\n"
         "\n"
         "- status / history / diff:   `jj st` / `jj log` / `jj diff`\n"
         "- a file's content at a rev:  `jj file show -r <rev> <path>`   (NOT `git show <rev>:<path>`)\n"
         "- diff a file across revs:    `jj diff --from <a> --to <b> <path>`\n"
         "- changes vs main:            `jj diff -r 'main..@'`\n"
         "- blame:                      `jj file annotate <path>`\n"
         "- push / fetch:               `jj git push` / `jj git fetch`\n"
         "\n"
         "**Bare `gh` cannot resolve the repo here either** — no git repository\n"
         "means `failed to run git: not a git repository`. Derive the slug and\n"
         "pass `-R \"$SLUG\"` to every `gh` call:\n"
         "\n"
         "```bash\n"
         "SLUG=$(jj git remote list | awk '/^origin/{print $2}' \\\n"
         "        | sed -E 's#^git@github\\.com:##; s#^https://github\\.com/##; s#\\.git$##')\n"
         "```\n"
         "\n"
         "`-R` alone is not enough for the PR-*resolving* subcommands\n"
         "(`view`/`edit`/`ready`/`merge`): they need `-R` **and an explicit PR\n"
         "number**, or they exit `argument required when using the --repo flag`.\n"
         "Only `gh pr create` and `gh pr list` work on `-R` alone.\n"
         "\n"
         "Shell variables do not persist between commands — each call is a fresh\n"
         "shell, so re-derive `$SLUG` in every block that uses it.\n")

    :jj-source-git-worktree
    (str "You are working through the nido orchestrator. This session was created\n"
         "as a legacy git worktree, but the project's source repo is jj-colocated\n"
         "and jj's working copy is the *source* directory below — NOT the worktree.\n"
         "\n"
         "- jj working copy (edit here for jj st/absorb/squash to see changes):\n"
         "    " source-dir "\n"
         "\n"
         "Edits made directly in the worktree are invisible to jj. To resync this\n"
         "session as a jj workspace, run `bb nido:session:destroy` followed by\n"
         "`bb nido:session:up` once the source has been jj-colocated.\n")

    :plain-git
    (str "You are working through the nido orchestrator. You are in the session's\n"
         "worktree; source-code edits land here, NOT in nido's source tree.\n")))

(defn read-project-briefing
  "Return the project-specific briefing markdown for <project-name>, or
   nil when no briefing resource exists. Looked up on the classpath at
   project-briefings/<project>.md so projects without dedicated briefings
   (most of them today) get a clean default. The content is embedded
   verbatim into the session-home CLAUDE.md/AGENTS.md by render-context — keep the
   resource focused on domain rules (routing, REPL discipline, …) that
   nido's generic briefing can't say."
  [project-name]
  (when project-name
    (when-let [resource (jio/resource (str "project-briefings/" project-name ".md"))]
      (slurp resource))))

(defn- render-workstream-line
  "Render the 'Workstream:' line when workstream-id is present.
   Appends ' (<br-id>)' when br-id is also available."
  [workstream-id br-id]
  (when workstream-id
    (str "- workstream: " workstream-id
         (when (seq br-id) (str " (" br-id ")"))
         "\n")))

(defn- render-context
  [{:keys [project-name session-name worktree source-dir
           app-port app-url nrepl-port pg-port
           profile links project-briefing
           workstream-id br-id]}]
  (let [services-active? (profiles/services-provisioned? profile)]
    (str
     "# Active nido session\n"
     "\n"
     (render-edit-location (vcs-mode worktree source-dir) source-dir)
     "\n"
     "- session: " session-name "\n"
     "- project: " project-name "\n"
     "- worktree: " worktree "\n"
     (when app-url    (str "- app: " app-url "\n"))
     (when app-port   (str "- app port: " app-port "\n"))
     (when nrepl-port (str "- nrepl port: " nrepl-port "\n"))
     (when pg-port    (str "- postgres port: " pg-port "\n"))
     (render-workstream-line workstream-id br-id)
     "\n"
     (if services-active?
       "## Services are already running\n\nThe REPL, app server, and database for this worktree are managed by\nnido. Don't run project-local scripts that spin up a REPL/app/DB —\nconnect to what's already live. The postgres MCP is preconfigured to\nthis session's DB.\n\n"
       "## Lite session\n\nThis is a lite session with no background services. The worktree is a\nread-only symlink to the project source directory.\n\n")
     (when-not (str/blank? project-briefing) (str project-briefing "\n"))
     shipping-doctrine-instructions
     "\n"
     "## Lifecycle\n"
     "\n"
     "Manage this session with nido (the session name is shown above):\n"
     "\n"
     "- `nido session:status <name>` — inspect\n"
     "- `nido session:down <name>` then `:up` — restart services in this worktree\n"
     "- `nido session:reset <name>` — nuclear recovery (drops PGDATA, re-clones template)\n"
     "- `nido session:destroy <name>` — stop and remove the worktree\n"
     "\n"
     (render-links-section links)
     add-link-instructions)))

(defn- ensure-worktree-symlink!
  "Create or refresh the `worktree` symlink inside the session-home so
   `cd worktree` reaches the code without callers needing to know the
   project's worktrees layout."
  [project-name session-name worktree]
  (let [link (worktree-link project-name session-name)]
    ;; fs/exists? follows symlinks; a dangling link returns false but
    ;; fs/sym-link? still recognises it. Always remove the existing link
    ;; (if any) before recreating so a moved worktree is reflected.
    (when (or (fs/exists? link) (fs/sym-link? link))
      (fs/delete link))
    (fs/create-sym-link link worktree)))

(defn nido-add-dirs
  "Directories to pass to claude's --add-dir so nido's harness artifacts resolve
   when the agent boots in the worktree. The nido source dir's .claude carries
   them (see nido-native-entries)."
  []
  [(str (core/nido-source-dir))])

(def ^:private merged-subdirs
  "The `.claude` subdirectories composed as REAL directories merging both trees.
   Every other top-level entry stays one symlink to the worktree's own, because
   the project owns it whole.

   `agents/` is merged for the same reason `skills/` is: nido ships harness
   artifacts that have to reach every project it drives, and a project cannot be
   asked to carry a copy of one — copies drift, and the harness then means
   different things in different repos. The consequence is deliberate and worth
   stating plainly, because it surprises a project author: a project's own
   `.claude/agents` is not the complete roster of what its sessions can
   dispatch."
  ["skills" "agents"])

(defn- nido-native-entries
  "Absolute paths of nido's *native* entries directly under `nido/.claude/<sub>`
   — the harness artifacts to inject into every session-home `.claude`.

   Native means real, not a symlink. That test is half the selection rule:
   nido's own tree mirrors a good deal of brian's tooling by symlink, and
   injecting a mirrored entry would relink the project's own artifact to
   whatever nido's tree happens to point at. `keep?` is the other half: it says
   what an artifact of this subdirectory IS — a skill is a directory, an agent
   is a file that defines one — and it is checked alongside `sym-link?` rather
   than instead of it, because `directory?` and `regular-file?` both follow
   links.

   Reads the WORKING TREE at launch time, not a revision, so a stale root
   checkout starves every session of anything merged since. See CLAUDE.md
   § Closing a work arc."
  [sub keep?]
  (let [dir (fs/path (core/nido-source-dir) ".claude" sub)]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter #(and (keep? %) (not (fs/sym-link? %))))
           (mapv str))
      [])))

(defn- agent-definition?
  "True when the file at `p` DEFINES a subagent, as against being a prose
   document that happens to sit beside one. Claude Code registers an agent from
   YAML frontmatter carrying `name:`; a file without it registers nothing, and
   injecting one puts an entry in every project's composed roster that no
   session can dispatch.

   The distinction is real and not hypothetical: `nido/.claude/agents` holds
   `architect.md`, which is a prompt with no frontmatter at all. It has always
   been inert, and the point of this predicate is that it stays inert on
   purpose rather than by accident.

   Frontmatter is the first thing in a file or it is not frontmatter, so only
   the leading block is read."
  [p]
  (and (fs/regular-file? p)
       (let [head (str/trim (slurp (str p)))]
         (and (str/starts-with? head "---")
              (when-let [end (str/index-of head "\n---" 3)]
                (some? (re-find #"(?m)^name:[ \t]*\S" (subs head 3 end))))))))

(defn- merge-subdir!
  "Compose `<claude>/<sub>` as a real directory: one relative link per entry of
   the worktree's own `<sub>` (so a moved worktree is still followed), then one
   absolute link per nido-native path, nido winning any name clash.

   Removing an existing entry before linking is what makes the clash a rule
   rather than a throw — and an exception here would be swallowed by
   `write-artifacts!` and leave the session with no composed `.claude` at all."
  [claude wt-claude sub native-paths]
  (let [dir    (fs/path claude sub)
        wt-sub (fs/path wt-claude sub)]
    (fs/create-dirs dir)
    (when (fs/exists? wt-sub)
      (doseq [entry (fs/list-dir wt-sub)
              :let  [nm (str (fs/file-name entry))]]
        (fs/create-sym-link (fs/path dir nm)
                            (fs/path ".." ".." "worktree" ".claude" sub nm))))
    (doseq [native native-paths
            :let   [link (fs/path dir (str (fs/file-name native)))]]
      (when (or (fs/exists? link) (fs/sym-link? link))
        (fs/delete link))
      (fs/create-sym-link link native))))

(defn- compose-claude-dir!
  "Compose `<home>/.claude` as a *real* directory so the in-session agent sees
   both the project's own tooling and nido's injected harness artifacts:

   - every top-level entry of the worktree's `.claude` except the
     `merged-subdirs` is re-exposed as a relative symlink through the
     session-home `worktree` link, which is what keeps a moved worktree
     followed;
   - each merged subdir is rebuilt by `merge-subdir!`.

   `natives` maps each merged subdir's name to the nido-native paths to inject
   into it; a name it does not carry gets the project's entries alone.

   Idempotent. SAFETY: if `<home>/.claude` is currently a *symlink* (the old
   single-link form) it is only unlinked — never `delete-tree`d — so we never
   follow it into the worktree and destroy the project's real `.claude`. A real
   dir here holds nothing but symlinks this function made, and `delete-tree`
   does not follow a symlink, so it removes link entries rather than their
   targets."
  [home natives]
  (let [claude    (fs/path home ".claude")
        wt-claude (fs/path home "worktree" ".claude")
        merged?   (set merged-subdirs)]
    (cond
      (fs/sym-link? claude) (fs/delete claude)        ; old single-symlink: unlink only
      (fs/exists? claude)   (fs/delete-tree claude))  ; prior composed dir (our symlinks)
    (fs/create-dirs claude)
    (when (fs/exists? wt-claude)
      (doseq [entry (fs/list-dir wt-claude)
              :let  [nm (str (fs/file-name entry))]
              :when (not (merged? nm))]
        (fs/create-sym-link (fs/path claude nm)
                            (fs/path ".." "worktree" ".claude" nm))))
    (doseq [sub merged-subdirs]
      (merge-subdir! claude wt-claude sub (get natives sub [])))))

(defn- ensure-claude-dir!
  "Compose the session-home `.claude` from the project's entries plus nido's
   native harness skills and agents, so the in-session agent sees nido's
   injected skills (e.g. local-ci) and its native agents alongside the
   project's own skills, agents, and commands."
  [project-name session-name]
  (compose-claude-dir! (state/session-home-dir project-name session-name)
                       {"skills" (nido-native-entries "skills" fs/directory?)
                        "agents" (nido-native-entries "agents" agent-definition?)}))

(defn- ensure-bb-edn-symlink!
  "Create or refresh a `bb.edn` symlink inside the session-home pointing
   at nido's own bb.edn. Lets the agent run nido bb tasks (e.g.
   `bb nido:session:link:add`) directly from the session-home cwd —
   without it, bb walks up looking for bb.edn and finds none. Project +
   session can then auto-resolve from cwd."
  [project-name session-name]
  (let [home    (state/session-home-dir project-name session-name)
        link    (str (fs/path home "bb.edn"))
        target  (str (fs/path (core/nido-source-dir) "bb.edn"))]
    (when (or (fs/exists? link) (fs/sym-link? link))
      (fs/delete link))
    (fs/create-sym-link link target)))

(defn- purge-legacy-artifacts!
  "Delete pre-refactor launcher artifacts at ~/.nido/state/<instance-id>/
   that the previous launcher wrote there. Sessions created before the
   session-home migration leave these on disk with stale port info, so
   anything (or anyone) grepping ~/.nido reads obsolete data. Idempotent."
  [instance-id]
  (when instance-id
    (let [base (state/instance-state-dir instance-id)]
      (doseq [legacy ["session-context.md" "mcp.json"]
              :let [path (str (fs/path base legacy))]
              :when (fs/exists? path)]
        (fs/delete path)
        (core/log-step (str "Removed legacy " path))))))

(defn write-artifacts!
  "Write per-session launcher artifacts into the session home. Called from
   start-services! after services are up. session-edn is passed in so we
   can read DB credentials without re-loading from disk."
  [ctx session-edn]
  (let [session-name (get-in ctx [:session :name])
        worktree     (get-in ctx [:session :project-dir])
        project-name (get-in ctx [:session :project-name])
        instance-id  (get-in ctx [:session :instance-id])
        pg-port      (get-in ctx [:pg :port])
        pg-svc       (pg-service-def session-edn)]
    (when-not session-name
      (throw (ex-info
              "Cannot write session-home artifacts: no :name in ctx :session"
              {:project-name project-name
               :hint (str "This session was started before the session-home "
                          "migration. Run `bb nido:session:down` then `:up` "
                          "to rebuild it.")})))
    (let [profile      (read-profile-for-session instance-id)
          home         (state/session-home-dir project-name session-name)
          link-entries (when instance-id (links/read-links instance-id))
          ws-ctx       (when-let [run-id (:owned-by-run session-edn)]
                         (run-workstream-context :run-id run-id))
          ctx-doc      (render-context (merge {:session-name     session-name
                                               :project-name     project-name
                                               :worktree         worktree
                                               :source-dir       (project-source-dir project-name)
                                               :app-port         (get-in ctx [:app :port])
                                               :app-url          (get-in ctx [:app :url])
                                               :nrepl-port       (get-in ctx [:repl :port])
                                               :pg-port          pg-port
                                               :profile          profile
                                               :links            link-entries
                                               :project-briefing (read-project-briefing project-name)}
                                              ws-ctx))
          mcp-doc      (mcp-config project-name worktree pg-svc pg-port)]
      (fs/create-dirs home)
      (when mcp-doc
        (let [path (mcp-path project-name session-name)]
          (io/write-json! path mcp-doc)
          (core/log-step (str "Wrote " path))))
      (when-let [svc-mcp-path (write-session-mcp! (get-in ctx [:session :instance-id])
                                                 project-name worktree pg-svc pg-port)]
        (core/log-step (str "Wrote " svc-mcp-path)))
      (doseq [path [(claude-md-path project-name session-name)
                    (agents-md-path project-name session-name)]]
        (io/write-text! path ctx-doc)
        (core/log-step (str "Wrote " path)))
      (try
        (agent-guidance/write-codex-override! worktree ctx-doc)
        (catch Exception e
          (core/log-step (str "warning: worktree AGENTS.override.md: "
                              (ex-message e)))))
      (try
        (ensure-worktree-symlink! project-name session-name worktree)
        (catch Exception e
          (core/log-step (str "warning: worktree symlink: " (ex-message e)))))
      (try
        (ensure-claude-dir! project-name session-name)
        (catch Exception e
          (core/log-step (str "warning: .claude symlink: " (ex-message e)))))
      (try
        (ensure-bb-edn-symlink! project-name session-name)
        (catch Exception e
          (core/log-step (str "warning: bb.edn symlink: " (ex-message e)))))
      (try
        (purge-legacy-artifacts! (get-in ctx [:session :instance-id]))
        (catch Exception e
          (core/log-step (str "warning: purge legacy artifacts: " (ex-message e)))))
      (when-let [run-id (:owned-by-run session-edn)]
        (try
          (coord-shim/write! home (cstate/run-dir run-id))
          (core/log-step (str "Wrote " home "/bin/claude (resume shim) + run-link"))
          (catch Exception e
            (core/log-step (str "warning: run-shim: " (ex-message e)))))))))

(defn session-briefing
  "Render the session briefing string from persisted state + links. Reusable as a
   launch input (claude --append-system-prompt). Source of truth for the home
   CLAUDE.md and AGENTS.md."
  [project-name session-name instance-id]
  (let [home         (state/session-home-dir project-name session-name)
        session      (some-> instance-id state/read-session)
        ctx          (:context session)
        worktree     (get-in ctx [:session :project-dir])
        profile      (read-profile-for-session instance-id)
        link-entries (links/read-links instance-id)
        ws-ctx       (run-workstream-context :session-home home)]
    (render-context
     (merge {:session-name     session-name
             :project-name     project-name
             :worktree         worktree
             :source-dir       (project-source-dir project-name)
             :app-port         (get-in ctx [:app :port])
             :app-url          (get-in ctx [:app :url])
             :nrepl-port       (get-in ctx [:repl :port])
             :pg-port          (get-in ctx [:pg :port])
             :profile          profile
             :links            link-entries
             :project-briefing (read-project-briefing project-name)}
            ws-ctx))))

(defn rerender-briefing!
  "Re-render only the session-home briefing files — used after a
   link mutation so the next session start sees the new entries.
   Reads the current ctx (ports etc.) from the persisted session.edn.
   No-op when the session is down (no session.edn) or when the
   session-home dir is missing — links land on disk regardless and the
   next `up` will rebuild the briefing fresh."
  [project-name session-name instance-id]
  (let [home    (state/session-home-dir project-name session-name)
        session (some-> instance-id state/read-session)
        ctx     (:context session)]
    (when (and ctx (fs/exists? home))
      (let [briefing (session-briefing project-name session-name instance-id)]
        (doseq [path [(claude-md-path project-name session-name)
                      (agents-md-path project-name session-name)]]
          (io/write-text! path briefing))
        (try
          (agent-guidance/write-codex-override!
           (get-in ctx [:session :project-dir])
           briefing)
          (catch Exception e
            (core/log-step (str "warning: worktree AGENTS.override.md: "
                                (ex-message e)))))))))

(defn remove-artifacts!
  "Remove the session home. Called from stop-session!. No-op if the session
   was never written there (e.g. a stale session-name lookup)."
  [project-name session-name]
  (when (and project-name session-name)
    (let [home (state/session-home-dir project-name session-name)]
      (when (fs/exists? home)
        (fs/delete-tree home)
        (core/log-step (str "Removed " home))))))
