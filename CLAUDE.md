# Nido

Agent orchestrator and harness for managing development sessions across projects and providers. Nido owns the agent workflow shell — session lifecycle, per-session services, and the launch path into Claude Code. Per-project domain knowledge (lane routing, REPL discipline, Datastar/statechart contracts, …) is borrowed from the target project on demand rather than re-stated here.

Built as a standalone Babashka project. CLI entry points are defined in `bb.edn` as tasks.

## Working model

Nido is the harness; the active project (e.g. brian) is the workspace. When you launch with `bb nido:session:claude <session>`, Claude Code starts with cwd at this directory but with the session's worktree available via `--add-dir`. A per-session briefing (under `~/.nido/state/<instance-id>/session-context.md`) is appended to the system prompt at launch — it tells you which worktree to operate on and which ports the live services are bound to.

Rules of engagement:

- **Edit code in the worktree, not in nido's source tree.** Use absolute paths under the worktree path provided in the session briefing.
- **Don't start your own services.** The REPL, app server, and database are managed by nido. Connect to them; don't spin up parallel ones via project-local scripts.
- **Don't edit a target project's main checkout in place.** Work always lands in a session worktree.
- **Trust nido's MCP wiring.** `--mcp-config` points the postgres MCP at the per-session DB on the session's PG port. Don't reach for the project's static `.mcp.json`.

## Routing domain work

Nido does not redocument target-project routing. When working in a brian session, consult brian's own routing reference:

- `~/Code/brian/docs/reference/agent-delegation.md` — which subagent owns which kind of change
- `~/Code/brian/docs/reference/agent-ownership.md` — handoff rules between lanes
- `~/Code/brian/CLAUDE.md` — brian's full working-model rules (read for reference; auto-load is intentionally suppressed when cwd is nido)

Brian's domain agents and most of its skills are mirrored under `nido/.claude/` via symlink, declared in `nido/.claude/harness.edn`. Run `bb nido:harness:sync` to reconcile after brian ships harness changes — it adds new entries, prunes deletions/exclusions, and leaves any user-overridden file (real, non-symlink) untouched. Currently excluded: `dev`, `push` (brian session/workflow plumbing nido owns).

## Session lifecycle

All session verbs take `:project <project>` plus a positional `<session>` (any order). Six verbs: four destructive, one launch-only, two read-only.

- `bb nido:session:up :project <p> <session>` — create the worktree if missing, start PG + JVM + app, **and launch Claude Code** against the session. Idempotent on the services side; the claude launch happens every time. Pass `:claude false` (or `:claude? false`) to skip the launch and just bring services up.
- `bb nido:session:down :project <p> <session>` — stop the session; worktree + on-disk state preserved.
- `bb nido:session:reset :project <p> <session>` — nuclear recovery. Down → drop PGDATA → re-clone from the current template → up. The "I'm wedged, fix it" button.
- `bb nido:session:destroy :project <p> <session>` — down + remove the worktree.
- `bb nido:session:claude :project <p> <session>` — launch claude against an already-running session (no service work). Useful when claude exited and you want to re-enter without bouncing services. Same flags as `up`'s launch path: cwd is forced to nido, worktree added via `--add-dir`, postgres MCP wired to the session port, briefing appended to the system prompt.
- `bb nido:session:status` / `nido:session:list` — read-only.

There is no `restart` task — use `down` then `up` (or just `up`, since it's idempotent on a running session and a no-op there). The dashboard's restart button still exists; it's an internal UI-only path.

The UI watchdog fully stops idle sessions (default 30 min of zero ESTABLISHED connections on the app port); wake is user-driven — an idle-stopped session stays down until the next `session:up`.

## Launcher artifacts

`session:up` writes two files into `~/.nido/state/<instance-id>/` for the launcher:

- `mcp.json` — postgres MCP wired to the per-session DB on the session's PG port.
- `session-context.md` — short briefing (project, worktree path, app/PG/REPL ports). Appended to the system prompt by `session:claude`.

Both are removed on `session:down`. If you run `session:claude` against a downed session, it will refuse — bring it `up` first.

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

## Delegation

Specialist agents live in `.claude/agents/`. Most domain agents are borrowed from brian via symlink — `datastar-dev` points at brian's, and `dev-rules.md` is symlinked too so its `@.claude/dev-rules.md` import resolves correctly. Native nido agents (currently just `architect`) live alongside the symlinks.

When a task falls inside an agent's domain, delegate to it via the `Agent` tool rather than doing the work directly — it carries domain rules and tooling the main agent does not.
