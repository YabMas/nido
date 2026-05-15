# Nido

Agent orchestrator and harness for managing development sessions across projects and providers. Nido owns the workflow shell — session lifecycle, per-session services, and a session home you can `cd` into and launch whatever agent you like (claude, codex, …). Per-project domain knowledge (lane routing, REPL discipline, Datastar/statechart contracts, …) is borrowed from the target project on demand rather than re-stated here.

Built as a standalone Babashka project. CLI entry points are defined in `bb.edn` as tasks.

## Working model

Nido is the harness; the active project (e.g. brian) is the workspace. Each running session has a **session home** at `~/.nido/sessions/<project>/<session>/` containing:

- `CLAUDE.md` — short briefing with the worktree path and live service ports.
- `.mcp.json` — postgres MCP wired to the per-session DB.
- `worktree/` — symlink to the code (`~/Code/<project>-worktrees/<session>/`).
- `.claude/` — symlink to `worktree/.claude/` so project-local skills/agents/commands resolve from the session home.

`bb nido:session:up` brings the session up. To pick a session interactively, use the TUI (`bb nido:tui`) and press enter — your shell `cd`s into the session-home (see "Shell wrapper" below). From there, run `claude`, `codex`, or any other agent — they pick up the briefing and MCP config from cwd, and `cd worktree` reaches the code.

### Shell wrapper

`bb` is a child process and can't change its parent shell's cwd, so the TUI hands off via `~/.nido/.last-cd` and a tiny shell function does the actual `cd`. Add to `~/.zshrc` (or your shell's equivalent):

```zsh
nido() {
  local f=~/.nido/.last-cd
  rm -f "$f"
  (cd ~/Code/nido && command bb nido:tui "$@")
  [[ -s "$f" ]] && { cd "$(cat "$f")" && rm -f "$f"; }
}
```

Then launch the TUI as `nido` rather than `bb nido:tui`. Without the wrapper the TUI still works as a viewer — pressing enter writes the path but nothing reads it.

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

## Session lifecycle

All session verbs take `:project <project>` plus a positional `<session>` (any order).

- `bb nido:session:up :project <p> <session>` — create the worktree if missing, start PG + JVM + app, write the session home. Idempotent. Prints the session-home path on success.
- `bb nido:session:enter :project <p> <session>` — write the session-home path to `~/.nido/.last-cd` and exit. Pair with the `nido` shell function (see "Shell wrapper" above) to actually `cd` your shell there. Refuses if the session is down. Pass `:cd worktree` to land in the worktree (the actual code) instead — useful when you want to edit / git-grep without the extra `cd worktree`. The TUI exposes the same opt-in: <kbd>e</kbd> / <kbd>↵</kbd> for session-home, <kbd>w</kbd> for worktree.
- `bb nido:session:down :project <p> <session>` — stop the session; worktree + on-disk state preserved.
- `bb nido:session:reset :project <p> <session>` — nuclear recovery. Down → drop PGDATA → re-clone from the current template → up. The "I'm wedged, fix it" button.
- `bb nido:session:destroy :project <p> <session>` — down + remove the worktree.
- `bb nido:session:status` / `nido:session:list` — read-only.

There is no `restart` task — use `down` then `up` (or just `up`, since it's idempotent on a running session and a no-op there). The dashboard's restart button still exists; it's an internal UI-only path.

The UI watchdog fully stops idle sessions (default 30 min of zero ESTABLISHED connections on the app port); wake is user-driven — an idle-stopped session stays down until the next `session:up`.

## Launcher artifacts

`session:up` populates `~/.nido/sessions/<project>/<session>/` with the agent-discoverable files described in **Working model**. Everything is regenerated each `up` (idempotent) and removed on `session:destroy`. If you run `session:enter` against a downed session, it will refuse — bring it `up` first.

## PostgreSQL topology

Two clusters per project. Every session gets its own database — there is no shared runtime cluster.

- **Template cluster** — long-lived APFS clone source at `~/.nido/templates/<project>/pg-data/`. Initialized with `bb nido:template:pg:init :project <name>`; refreshed from a dump with `bb nido:template:pg:refresh`. Must always be stopped when not actively being refreshed (clones need a clean `postmaster.pid` absence).
- **Per-session cluster** — own cluster under `~/.nido/state/<instance-id>/pg-data/`, APFS-cloned from the template at `session:up` and torn down on `session:down`. Each session runs Flyway against its own DB on app boot, so destructive migrations can't leak between sessions.

After a `template:pg:refresh`, running sessions still hold their original clone — use `bb nido:session:reset :project <p> <session>` to drop their PGDATA and re-clone from the new template. APFS clones are essentially free, so this is fast.

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
   :clone-from-template true
   :baseline {...}}]}
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

`up` refuses if a live daemon already holds the PID file. `down` cleans the PID file even when the daemon was already gone (stale PID).

**Foreground (still supported for development):**

```
bb nido:coordinator:run :poll-ms 500
```

**Working with triggers/runs:**

```
bb nido:trigger:fire :project brian <name> :<key> <value>
bb nido:runs:list / bb nido:runs:show <id>
```

Or in the TUI press `r` for the runs surface.

**Safety brakes (Stage 2):** per-Run wall-clock budget (`:limits.budget`, default 30m, SIGTERM→SIGKILL), per-trigger circuit breaker (`:limits.max-failures`, default 3), daemon-wide anomaly auto-halt, kill switch (`bb nido:halt` + `bb nido:coordinator:resume`). TUI `h` halts, `c` clears a breaker.

**Startup reconciliation (Stage 3):** when the daemon starts, any non-terminal Run on disk is forced to a terminal state from observable evidence (artifacts, `_run-status.edn`, agent.log). Crashed/orphaned Runs get marked `:failed :reason :orphaned-from-restart` so the dashboard stays honest.

Full design: `docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md`. Skill conventions: `docs/skill-conventions-for-triggers.md`.

Stage 4 will add launchd auto-start at login; stages 5+ add Notion / cron / GitHub event sources.

## Delegation

Specialist agents live in `.claude/agents/`. Most domain agents are borrowed from brian via symlink — `datastar-dev` points at brian's, and `dev-rules.md` is symlinked too so its `@.claude/dev-rules.md` import resolves correctly. Native nido agents (currently just `architect`) live alongside the symlinks.

When a task falls inside an agent's domain, delegate to it via the `Agent` tool rather than doing the work directly — it carries domain rules and tooling the main agent does not.
