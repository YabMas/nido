# Nido

Agent orchestrator for managing development sessions across projects and providers. The goal is a coherent set of reusable tools and workflows that can be systematically applied regardless of which agent (Claude, Codex, etc.) or which project is in play — enabling fast iteration without ad-hoc one-off scripts.

Built as a standalone Babashka project. CLI entry points are defined in `bb.edn` as tasks.

## Current scope

Session lifecycle management (`src/tasks/nido_session.clj`): spinning up isolated dev environments per worktree with their own REPL, app server, and database. First target project is brian-next, but the session machinery is project-agnostic.

## Per-Session PostgreSQL

Each session starts a dedicated PostgreSQL instance to avoid "too many connections" errors when multiple agent sessions run concurrently.

### How it works

1. `start-pg-instance!` runs `initdb` + `pg_ctl start` with a unique port per worktree
2. `write-local-edn!` writes a `local.edn` in the worktree root with DB connection overrides
3. Brian's config system (`config.clj`) deep-merges `local.edn` on top of `config/dev.edn` at mount start
4. Flyway runs migrations automatically against the fresh database (`flyway/migrate? true`)
5. On stop, PG is shut down, data dir is deleted, and `local.edn` is removed (or the original restored from backup)

### DB config keys in local.edn

The three keys that must be overridden to point brian at a session-specific PG:

- `:postgres/config` — HikariCP pool used by pg2 legacy path (`:jdbcUrl`, `:username`, `:password`)
- `:pg2/config` — pg2 direct config (`:host`, `:port`, `:database`, `:user`, `:password`)
- `:com.fulcrologic.rad.database-adapters.sql/databases` — Fulcro RAD SQL adapter with Flyway (`:hikaricp/config` uses string keys: `"jdbcUrl"`, `"dataSource.user"`, `"dataSource.password"`, `"driverClassName"`)

These must match the key names in brian-next's `config/defaults.edn`.

### Port ranges

- App ports: 3100-5100 (deterministic hash of project-dir)
- PG ports: 5500-7500 (separate deterministic hash)

### Skipping per-session PG

Pass `:shared-pg? true` to `nido:session:start` to use an existing shared PostgreSQL instance instead.

## Project-specific: brian-next

- `development/start` accepts `{:datastar-port N}` which threads through mount/args as `:datastar/port`
- `brian.server/server` reads `(:datastar/port (mount/args))` to bind the HTTP server
- The nido start form tries `(development/start {:datastar-port PORT})` first, with an ArityException fallback for older brian codebases
