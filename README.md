# Nido

Standalone Babashka project for managing per-worktree agent sessions from one place.

For now, this ports the `agent-session` lifecycle that was previously embedded in `brian-next`.

## Commands

Run from this project:

```bash
bb tasks
```

### Bring a session up

```bash
bb nido:session:up :project brian feature-x
```

Optional overrides (heap, aliases, branch, base):

```bash
bb nido:session:up :project brian feature-x :jvm-heap-max 1500m
bb nido:session:up :project brian feature-x :base develop
```

### Check status

```bash
bb nido:session:status :project brian feature-x
```

### Bring a session down (worktree preserved)

```bash
bb nido:session:down :project brian feature-x
```

### Nuclear recovery (drops PGDATA, re-clones template)

```bash
bb nido:session:reset :project brian feature-x
```

### Destroy a session (down + remove worktree)

```bash
bb nido:session:destroy :project brian feature-x
```

### List all sessions for a project

```bash
bb nido:session:list :project brian
```

## Notes

- Session state for each target project is written to `<project>/.codex/session.edn`.
- Nido registry is written to `$CODEX_HOME/nido/sessions.edn` (or `~/.codex/nido/sessions.edn`).
- Legacy registry at `$CODEX_HOME/agent-cockpit/sessions.edn` is still read for compatibility.
- The default startup path is compatible with `brian-next` without requiring local code changes:
  it attempts `(development/start {:datastar-port ...})` and falls back to an equivalent manual mount startup if needed.
