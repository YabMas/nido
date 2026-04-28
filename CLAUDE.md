# Nido

Agent orchestrator for managing development sessions across projects and providers. The goal is a coherent set of reusable tools and workflows that can be systematically applied regardless of which agent (Claude, Codex, etc.) or which project is in play — enabling fast iteration without ad-hoc one-off scripts.

Built as a standalone Babashka project. CLI entry points are defined in `bb.edn` as tasks.

## Current scope

Session lifecycle management (`src/tasks/nido_session.clj`): spinning up isolated dev environments per worktree with their own REPL, app server, and database. First target project is brian-next, but the session machinery is project-agnostic.

## Session lifecycle

Four destructive verbs + two read-only. All take `:project <project>` plus a positional `<session>` (any order):

- `bb nido:session:up :project <p> <session>` — create the worktree if missing, start PG + JVM + app. Idempotent: running on a live session is a no-op.
- `bb nido:session:down :project <p> <session>` — stop the session; worktree + on-disk state preserved.
- `bb nido:session:reset :project <p> <session>` — nuclear recovery. Down → drop PGDATA → re-clone from the current template → up. The "I'm wedged, fix it" button.
- `bb nido:session:destroy :project <p> <session>` — down + remove the worktree.
- `bb nido:session:status` / `nido:session:list` — read-only.

There is no `restart` task — use `down` then `up` (or just `up`, since it's idempotent on a running session and a no-op there). The dashboard's restart button still exists; it's an internal UI-only path.

The UI watchdog fully stops idle sessions (default 30 min of zero ESTABLISHED connections on the app port); wake is user-driven — an idle-stopped session stays down until the next `session:up`.

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

Specialist agents live in `.claude/agents/`. When a task falls inside one's domain, delegate to it via the `Agent` tool rather than doing the work directly — they carry domain rules and tooling the main agent does not.

- `architect` — high-level design and architectural trade-offs
- `datastar-dev` — Datastar server-driven UI work (HTML/signal/morph contracts, Tailwind/chassis styling). Symlinked from `~/Code/brian/.claude/agents/datastar-dev.md`; its `@.claude/dev-rules.md` import resolves via a sibling symlink.
