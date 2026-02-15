# Nido

Standalone Babashka project for managing per-worktree agent sessions from one place.

For now, this ports the `agent-session` lifecycle that was previously embedded in `brian-next`.

## Commands

Run from this project:

```bash
bb tasks
```

### Start a session

```bash
bb nido:session:start :project-dir "/Users/yabmas/Code/brian-next"
```

Optional overrides:

```bash
bb nido:session:start \
  :project-dir "/Users/yabmas/.codex/worktrees/6aa4/brian-next" \
  :app-port 3901
```

### Check status

```bash
bb nido:session:status :project-dir "/Users/yabmas/.codex/worktrees/6aa4/brian-next"
```

### Stop a session

```bash
bb nido:session:stop :project-dir "/Users/yabmas/.codex/worktrees/6aa4/brian-next"
```

### List all tracked sessions

```bash
bb nido:session:list
```

## Backward compatibility

`agent:session:*` task names are still available as aliases.

## Notes

- Session state for each target project is written to `<project>/.codex/session.edn`.
- Nido registry is written to `$CODEX_HOME/nido/sessions.edn` (or `~/.codex/nido/sessions.edn`).
- Legacy registry at `$CODEX_HOME/agent-cockpit/sessions.edn` is still read for compatibility.
- The default startup path is compatible with `brian-next` without requiring local code changes:
  it attempts `(development/start {:datastar-port ...})` and falls back to an equivalent manual mount startup if needed.
