# Nido

Agent orchestrator and harness for managing development sessions across projects and providers. Nido owns the workflow shell — session lifecycle, per-session services, and a session home you can `cd` into and launch whatever agent you like (claude, codex, …). Per-project domain knowledge (lane routing, REPL discipline, Datastar/statechart contracts, …) is borrowed from the target project on demand rather than re-stated here.

Built as a standalone Babashka project. CLI entry points are defined in `bb.edn` as tasks.

## Working model

Nido is the harness; the active project (e.g. brian) is the workspace. Each running session has a **session home** at `~/.nido/sessions/<project>/<session>/` containing:

- `CLAUDE.md` — short briefing with the worktree path and live service ports.
- `.mcp.json` — postgres MCP wired to the per-session DB.
- `worktree/` — symlink to the code (`~/Code/<project>-worktrees/<session>/`).
- `.claude/` — symlink to `worktree/.claude/` so project-local skills/agents/commands resolve from the session home.

`bb nido:session:up` brings the session up. To pick a session interactively, use the TUI (`bb nido:tui`) and press enter to land in the session-home. From there, run `claude`, `codex`, or any other agent — they pick up the briefing and MCP config from cwd, and `cd worktree` reaches the code.

How "land in the session" works depends on the terminal:

- **Warp** — enter opens a **new tab in the current window** at the session-home (`<kbd>w</kbd>` targets the worktree instead) and **leaves the TUI running**, so you orchestrate from one persistent nido tab and spin off a tab per session. Warp's URI scheme (`warp://action/new_tab?path=…`) can't run a command or set a title, so the tab lands at a bare shell; name it with the precmd hook below.
- **Every other terminal** — enter falls back to the `cd`-handoff: the TUI writes `~/.nido/.last-cd`, quits, and a tiny shell function `cd`s the parent shell there (bb is a child process and can't change its parent's cwd itself).

### Shell wrapper (TUI launcher + verb dispatch + cd-handoff)

```zsh
nido() {
  local f=~/.nido/.last-cd
  rm -f "$f"
  if (( $# == 0 )) || [[ "$1" == tui ]]; then
    (cd ~/Code/nido && command bb nido:tui "${@:2}")
  else
    # Dispatch `nido <verb> [args]` to nido's tasks from the CURRENT cwd,
    # so tasks resolve the session via session-from-cwd. --config points bb
    # at nido's bb.edn regardless of the project's own bb.edn in the worktree.
    command bb --config ~/Code/nido/bb.edn "nido:$1" "${@:2}"
  fi
  [[ -s "$f" ]] && { cd "$(cat "$f")" && rm -f "$f"; }
}
```

Launch the TUI as `nido` (or `nido tui`) rather than `bb nido:tui`. (In Warp the wrapper is harmless — enter spawns a tab and never writes `.last-cd`, so the trailing `cd` is simply skipped.)

`nido <verb> [args]` runs `bb nido:<verb>` from your current directory — so `nido session:status`, `nido session:link:add …`, `nido review:loop …` all work from inside a project worktree, with no `cd` to the session home. `--config` makes `bb` load nido's `bb.edn` even though the worktree carries the project's own; the task then resolves which session you're in via `session-from-cwd`.

### Tab naming (Warp)

Warp's URI can't title the tab, so a shell hook does it from `$PWD` on the new tab's first prompt. Warp ignores shell-set titles unless `WARP_DISABLE_AUTO_TITLE=true`. Add to `~/.zshrc`:

```zsh
# Name a Warp tab opened into a nido session-home <project>/<session>.
autoload -Uz add-zsh-hook
_nido_tab_title() {
  case "$PWD" in
    "$HOME/.nido/sessions/"*)
      local rel="${PWD#$HOME/.nido/sessions/}"
      export WARP_DISABLE_AUTO_TITLE=true
      printf '\033]0;%s\007' "${rel%/}"
      ;;
  esac
  add-zsh-hook -d precmd _nido_tab_title   # one-shot: only the opening dir names the tab
}
add-zsh-hook precmd _nido_tab_title
```

Rules of engagement:

- **Edit code in the worktree, not in nido's source tree.** Use absolute paths or `cd worktree/` from the session home.
- **Don't start your own services.** The REPL, app server, and database are managed by nido. Connect to them; don't spin up parallel ones via project-local scripts.
- **Don't edit a target project's main checkout in place.** Work always lands in a session worktree.
- **Trust nido's MCP wiring.** The session home's `.mcp.json` points the postgres MCP at the per-session DB on the session's PG port. Don't reach for the project's static `.mcp.json`.

## Routing domain work

Nido does not redocument target-project routing. When working in a brian session, consult brian's own routing reference:

- `~/Code/brian/docs/reference/agent-delegation.md` — which subagent owns which kind of change
- `~/Code/brian/docs/reference/agent-ownership.md` — handoff rules between lanes
- `~/Code/brian/CLAUDE.md` — brian's full working-model rules (read for reference; auto-load is intentionally suppressed when cwd is nido)

Brian's domain agents and most of its skills are mirrored under `nido/.claude/` via symlink, declared in `nido/.claude/harness.edn`. Run `bb nido:harness:sync` to reconcile after brian ships harness changes — it adds new entries, prunes deletions/exclusions, and leaves any user-overridden file (real, non-symlink) untouched. Currently excluded: `dev`, `push` (brian session/workflow plumbing nido owns).

## Closing a work arc

**End every work arc by rebasing the root `~/Code/nido` checkout onto local `main`** — `jj rebase -d main` (or `jj rebase -r @ -d main@origin` to carry uncommitted working-copy changes forward). Work lands from session worktrees, which share the store but not the root workspace's working copy, so the root checkout stays wherever it was and silently drifts behind `main` as arcs land elsewhere.

This is load-bearing because the **session launcher injects nido's native harness skills by listing the on-disk `~/Code/nido/.claude/skills/` directory** (`nido-native-skill-dirs` → `fs/list-dir`, at runtime — it reads the working tree, not git). A stale root checkout that predates a newly-merged skill therefore starves **every** session of it: the skill is committed, pushed, and in main's tree, yet missing from disk, so it's never composed in. The same hazard applies to any launcher input read from the working tree.

Symptom & fix: a shipped harness skill "missing" from sessions → check `ls ~/Code/nido/.claude/skills/<name>` (on disk, not `jj file list`). Present in main's tree but absent on disk ⇒ stale checkout ⇒ rebase as above, then re-`session:up` the live sessions (or restart the in-session agent) so the launcher recomposes `.claude/skills/`.

## Local merge queue (nido ship)

`nido ship` (run from a session home/worktree) hands the branch to a strictly
serial **merge lane** in the coordinator: it writes a `:ship` envelope, the daemon
flips the workstream to the `:shipping` stage and runs `/drive-home` headless
(one branch at a time, on its own budget — never stealing a triage slot). Close
the tab; nido drives it home. On a clean finish the PR goes onto GitHub's native
merge queue (`gh pr merge --auto`) and the existing `github-merge` poller closes
the workstream to `:done`. On a halt it parks the branch as **blocked** in the
gate inbox with the blocker — fix it in the worktree and `nido ship` again
(idempotent). `bb nido:coordinator:status` shows a `Merge lane:` line. The lane is
cap-1 by design (one brian CI run already saturates ~8 test containers).

## Session lifecycle

All session verbs take `:project <project>` plus a positional `<session>` (any order).

- `bb nido:session:up :project <p> <session>` — create the worktree if missing, start PG + JVM + app, write the session home. Idempotent. Prints the session-home path on success.
- `bb nido:session:enter :project <p> <session>` — write the session-home path to `~/.nido/.last-cd` and exit. Pair with the `nido` shell function (see "Shell wrapper" above) to actually `cd` your shell there. Refuses if the session is down. Pass `:cd worktree` to land in the worktree (the actual code) instead — useful when you want to edit / git-grep without the extra `cd worktree`. The TUI exposes the same opt-in on its **system surface** (`s`): <kbd>e</kbd> / <kbd>↵</kbd> for session-home, <kbd>w</kbd> for worktree (and the board's <kbd>↵</kbd>/<kbd>o</kbd> opens a workstream's session-home directly) — but in Warp the TUI spawns a new tab in place rather than quitting to this `cd`-handoff (see "How land in the session works" above). The CLI verb always uses the `.last-cd` handoff.
- `bb nido:session:down :project <p> <session>` — stop the session; worktree + on-disk state preserved.
- `bb nido:session:reset :project <p> <session>` — nuclear recovery. Down → drop PGDATA → re-clone from the current template → up. The "I'm wedged, fix it" button.
- `bb nido:session:destroy :project <p> <session>` — down + remove the worktree.
- `bb nido:session:status` / `nido:session:list` — read-only.

There is no `restart` task — use `down` then `up` (or just `up`, since it's idempotent on a running session and a no-op there). The dashboard's restart button still exists; it's an internal UI-only path.

Sessions stay up until explicitly stopped. There is no automatic idle-suspension — bring a session `down` yourself when you're done with it.

## Launcher artifacts

`session:up` populates `~/.nido/sessions/<project>/<session>/` with the agent-discoverable files described in **Working model**. Everything is regenerated each `up` (idempotent) and removed on `session:destroy`. If you run `session:enter` against a downed session, it will refuse — bring it `up` first.

## PostgreSQL topology

The `:postgresql` service runs in one of three **modes** (`:mode` on the service def; resolved by `nido.session.services.postgresql/resolve-pg-mode`):

- **`:shared`** (default for brian) — every session connects to ONE long-lived running cluster per project at `~/.nido/shared/<project>/pg-data/`. No per-session PGDATA, so sessions don't each cost a 27 GB clone that diverges over time. Sessions share schema and data; each still runs Flyway on app boot (idempotent against the already-migrated shared schema).
- **`:clone` / `:isolated`** — the legacy behavior: a private cluster under `~/.nido/state/<instance-id>/pg-data/`, APFS-cloned from the template at `session:up`, torn down on `session:down`. Use when a session must mutate data destructively without affecting others. `:clone-from-template true` (no `:mode`) is a back-compat alias for `:clone`.

Three cluster roles:

- **Template cluster** — long-lived APFS clone source at `~/.nido/templates/<project>/pg-data/`. Initialized with `bb nido:template:pg:init :project <name>`; refreshed from a dump with `bb nido:template:pg:refresh`. Must always be stopped when not actively being refreshed — both clone paths (shared seed + per-session) need a clean `postmaster.pid` absence. `bb nido:template:pg:stop` clears a stale pid (kill fallback included).
- **Shared cluster** — the running per-project cluster sessions use in `:shared` mode. Seeded once by APFS-cloning the (stopped) template, then left running across sessions. Manage with `bb nido:shared:pg:{up,status,down,reset,destroy} :project <name>`. `reset` re-clones from the current template (recovery after a divergent migration lands on the shared DB, or to pick up a `template:pg:refresh`).
- **Per-session cluster** — only exists for `:clone`/`:isolated` sessions, as above.

**Per-session escape hatch.** A session on the shared cluster can opt into a private clone for destructive work:

- `bb nido:session:isolate :project <p> <session>` — sets a per-session `:isolated` override (persisted at `~/.nido/state/<instance-id>/pg-mode-override.edn`) and restarts the session against its own clone.
- `bb nido:session:share :project <p> <session>` — clears the override, drops the private PGDATA, and restarts against the shared cluster.

`bb nido:session:reset` refuses on a shared-mode session (it would reset the DB for everyone) — use `bb nido:shared:pg:reset` for the shared cluster, or `isolate` the session first. After a `template:pg:refresh`, `:clone`/`:isolated` sessions still hold their original clone — `reset` them to re-clone; for the shared cluster run `bb nido:shared:pg:reset`. APFS clones are essentially free, so this is fast.

### session.edn shape

The `:postgresql` service is a flat config map; the same fields apply to every session:

```clojure
{:services
 [{:type :postgresql
   :name :pg
   :db-name "brian"
   :db-user "user"
   :db-password "password"
   :schema "brian"
   :extensions ["vector"]
   :port-range [5500 7500]
   :mode :shared            ; :shared (default) | :clone | :isolated
   :clone-from-template true ; used only by :clone/:isolated mode
   :baseline {...}}]}        ; first-init seed; ignored in :shared mode
```

### local.edn keys (brian)

Mirror of keys in brian's `config/defaults.edn`. These are the seams nido writes through:

- `:org.httpkit.server/config` — HTTP bind port (per-session app port)
- `:postgres/config` — legacy HikariCP pool (`:jdbcUrl`, `:username`, `:password`)
- `:pg2/config` — pg2 direct (`:host`, `:port`, `:database`, `:user`, `:password`)
- `:com.fulcrologic.rad.database-adapters.sql/databases` — Fulcro RAD SQL adapter (Hikari with string keys + `:flyway/migrate?`)

### Port ranges

- App ports: 3100–5100 (deterministic hash of project-dir)
- Per-session PG ports: 5500–7500 (separate deterministic hash)
- Template PG port: fixed in `session.edn :templates :pg :port` (5499 for brian)

## JVM tuning from nido

The `:process` service accepts a `:command-template` (vector of tokens) that nido renders after context substitution. JVM heap, aliases, and extra opts flow from `session.edn :defaults :jvm`, with CLI overrides:

```clojure
:defaults {:jvm {:heap-max "2g"
                 :aliases [:dev :rad-dev :cider/nrepl]
                 :extra-opts []}}
```

```
bb nido:session:up :project brian foo :jvm-heap-max 1500m
bb nido:session:up :project brian foo :jvm-aliases [dev cider/nrepl]
```

These produce `-J-Xmx...` and `-M:a:b:c` on the `clojure` command line without any change to brian. The UI session list surfaces live RSS for the repl JVM and the PG process next to the port columns.

## Reclaim / cleanup

`bb nido:reclaim` lists per-instance state dirs under `~/.nido/state/` that have no matching registry entry; re-run with `:force? true` to delete. Useful after destroying sessions whose PGDATA was left behind (kill -9, host crash, manual rm).

The coordinator daemon also reclaims orphans automatically: once per hour (and on the first tick after startup) it deletes untracked state dirs older than a 1h grace window. The grace window is load-bearing — a session's state dir (PGDATA clone) exists for the whole boot *before* its registry entry is written, so a freshly-untracked dir may be a live boot in flight, not garbage. Tuned via `:reclaim-interval-ms` / `:reclaim-min-age-ms` in `nido.coordinator.core/defaults`. The manual `bb nido:reclaim :force? true` has no grace window (deletes all orphans now) — only run it when no session is mid-boot.

## Project-specific: brian-next

- `development/start` accepts `{:datastar-port N}` which threads through mount/args as `:datastar/port`
- `brian.server/server` reads `(:datastar/port (mount/args))` to bind the HTTP server
- The nido start form tries `(development/start {:datastar-port PORT})` first, with an ArityException fallback for older brian codebases

## Coordination layer (stages 1–3)

The coordinator daemon spawns Run-owned sessions that auto-launch claude with a configured skill. Triggers live at `~/.nido/projects/<project>/triggers.edn`; envelopes hit `~/.nido/coordinator/queue/`.

**Running the daemon (Stage 3):**

```
bb nido:coordinator:up          # background daemon; logs to coordinator.log
bb nido:coordinator:status      # process alive? + heartbeat + halt/breaker info
bb nido:coordinator:logs        # last 50 lines (or :follow true to tail -f)
bb nido:coordinator:down        # graceful stop (SIGTERM); :force true → SIGKILL
```

**Web dashboard (bundled with the daemon):** the coordinator runs the dashboard
in-process. With the daemon up it's always at `http://localhost:8800` — the home
page (`/`) is now the cross-project **Gate Inbox**: every parked workstream that
needs you, each with its report and one-click follow-actions (skip / promote /
drop / done / reply). The stage-grouped **Workstreams** overview is at `/workstreams`;
workstream detail is at `/workstreams/<project>/<ws-id>` — a ledger reader plus, below it,
a stage-appropriate action bar (the same gate actions as the inbox, e.g. parked triage →
Apply/Dismiss/Reply) shown only for the current ledger entry. The old flat live-sessions board
(each live session a clickable friendly-host link `<session>.<project>.localhost`)
moved to `/system`. The Workstreams overview (`/workstreams`) filters by **scope** (project), **source** (All/Notion/GitHub/Slack/Scratch, with per-source counts) and **context-dependent facets** (App Domain / Type for the Notion source) — the cockpit's overview surface. The TUI stays a lean session launcher. Override the port with `bb nido:coordinator:up :dashboard-port <n>`;
disable with `:no-dashboard true`. `bb nido:coordinator:status` shows a `Dashboard:`
line (port + reachability).

The standalone `bb nido:ui [:port 8800]` task still exists for UI iteration or when
the daemon is down — but it and the daemon both bind 8800, so don't run both.

`up` refuses if a live daemon already holds the PID file. `down` cleans the PID file even when the daemon was already gone (stale PID).

**The daemon is a single long-lived process that reads `src/` once at startup — editing coordinator code does NOT affect the running daemon.** After changing any coordinator namespace, restart it (`bb nido:coordinator:restart` under launchd, otherwise `down` then `up`) or the fix never loads. This bites silently: a fix can sit committed on disk for hours while the live daemon keeps running the old logic — when debugging behaviour that contradicts the on-disk code, check the daemon's start time (`ps -o lstart -p $(cat ~/.nido/coordinator/coordinator.pid)`) against the relevant commit before assuming the code is wrong. (Restarting also loads any uncommitted working-tree changes.)

**Auto-start at login (Stage 4):**

```
bb nido:coordinator:install     # write LaunchAgent plist + bootstrap; daemon runs now + at every login
bb nido:coordinator:uninstall   # bootout + remove plist
bb nido:coordinator:restart     # launchctl kickstart -k (requires install)
```

Once installed, `bb nido:coordinator:up` / `down` wrap `launchctl bootstrap` / `bootout` automatically; status surfaces a `Launchd:` line so you can see which lifecycle is in charge. `install` refuses if a bare daemon is already running — `down` it first.

**Autonomous sources (Stage 5):**

```
bb nido:notion:auth:set                # store Notion Personal Access Token (PAT) in macOS Keychain
bb nido:notion:auth:check              # confirm token presence
bb nido:slack:auth:set                 # store Slack bot token (xoxb-) in macOS Keychain
bb nido:slack:auth:check               # confirm token presence
bb nido:coordinator:source:list        # one row per source-instance on disk
bb nido:coordinator:source:reset :type notion-view :database <id> :view <name> :poll <dur>
                                       # clear an open breaker
```

A trigger with `:source {:type :notion-view :database "..." :view "..." :poll "5m"}` polls the database every 5 minutes and emits one event per row that newly enters the view. The first poll seeds the snapshot and emits nothing. Status shows per-source health under a `Sources:` section; breakers open after 3 consecutive failures (or immediately on 401).

A trigger with `:source {:type :slack-channel :channel "C..." :poll "2m"}` polls a Slack channel's top-level messages and emits one event per new message (watermarked on message `ts`). The first poll seeds the watermark and emits nothing — it does not replay channel history. Each new message auto-fires a triage session; the verdict stays in nido's ledger (no Slack/Notion writeback). The bot token needs `channels:history` (public) or `groups:history` (private) scope and the bot must be a channel member. Breaker opens on `invalid_auth` (or ≥3 consecutive server errors); rate-limit (429) and connectivity blips are transient and never trip it.

**Foreground (still supported for development):**

```
bb nido:coordinator:run :poll-ms 500
```

**Working with triggers/runs:**

```
bb nido:trigger:fire :project brian <name> :<key> <value>
bb nido:runs:list / bb nido:runs:show <id>
```

Or in the TUI: the spine board lists workstreams by stage — `↵`/`o` opens a workstream's session, `i` inspects its sessions (autonomous runs show on the autonomy axis), `n` starts a new one-off, `p`/`P` promote (default / pick-a-stage), `d` marks done, `tab` cycles the origin filter, and `s` opens the system surface.

### Classification-facet sub-queues

The TUI board can slice an origin (Notion) into composable sub-queues by durable
ticket classifiers. Add `:facets ["App Domain" "Type"]` to a project's
`~/.nido/projects/<project>/notion-views.edn`; the board then shows two
AND-composed selectors (`[`/`]` cycle App Domain, `{`/`}` cycle Type). Facets are
stored on each workstream — stamped at creation, refreshed when a triage verdict
is applied (`nido:ticket:complete`), and re-syncable on demand with
`bb nido:facets:refresh :project <p> [:ws <id>]`. They are a pure organizational
lens; the stage spine is unchanged.

**Safety brakes (Stage 2):** per-Run wall-clock budget (`:limits.budget`, default 30m, SIGTERM→SIGKILL), per-trigger circuit breaker (`:limits.max-failures`, default 3), daemon-wide anomaly auto-halt, kill switch (`bb nido:halt` + `bb nido:coordinator:resume`). On the TUI **system surface** (`s`): `h` halts, `c` clears a breaker, `f` fires a trigger.

**Startup reconciliation (Stage 3):** when the daemon starts, any non-terminal Run on disk is forced to a terminal state from observable evidence (artifacts, `_run-status.edn`, agent.log). Crashed/orphaned Runs get marked `:failed :reason :orphaned-from-restart` so the dashboard stays honest.

Full design: `docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md`. Skill conventions: `docs/skill-conventions-for-triggers.md`.

Stage 4 added launchd auto-start at login; Stage 5 added the Notion source; cron / GitHub remain future work.

## Delegation

Specialist agents live in `.claude/agents/`. Most domain agents are borrowed from brian via symlink — `datastar-dev` points at brian's, and `dev-rules.md` is symlinked too so its `@.claude/dev-rules.md` import resolves correctly. Native nido agents (currently just `architect`) live alongside the symlinks.

When a task falls inside an agent's domain, delegate to it via the `Agent` tool rather than doing the work directly — it carries domain rules and tooling the main agent does not.
