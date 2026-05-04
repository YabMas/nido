# `session:enter` — opt into landing in the worktree

**Status:** designed, not implemented
**Date:** 2026-05-04

## Context

A nido session has two natural cwd targets:

- **Session home** — `~/.nido/sessions/<project>/<session>/`. Holds `CLAUDE.md` (briefing), `.mcp.json` (per-session postgres MCP), and a `worktree/` symlink. This is where you start `claude` / `codex`, because the agent picks up the briefing and MCP wiring from cwd.
- **Worktree** — `~/Code/<project>-worktrees/<session>/`. The actual code. Edits land here.

Today every entry path lands in session-home and the user `cd worktree` from there to reach code:

- `bb nido:session:enter :project <p> <s>` writes session-home to `~/.nido/.last-cd`; the `nido` shell wrapper `cd`s there.
- The TUI's `enter` / `e` key does the same thing.

For sessions where the user wants to be in the code immediately (reading diffs, editing, running git commands by hand) the second `cd` is friction. The harness should let the user opt into worktree-as-cwd without flipping the default — session-home stays right for agent launches.

## Decision

Add an opt-in target selector to both surfaces. Default unchanged.

### CLI: `:cd <target>` kwarg on `nido:session:enter`

```
bb nido:session:enter :project brian feat-auth                  # session-home (current default)
bb nido:session:enter :project brian feat-auth :cd worktree     # worktree
bb nido:session:enter :project brian feat-auth :cd home         # session-home (explicit)
```

Accepts `home` or `worktree`. Anything else throws with the valid set in the message.

A kwarg is preferred over a boolean (`:worktree? true`) so future targets (e.g. a configured project subdir) fit the same surface without churn.

### TUI: new `w` keybinding

`enter` / `e` keep current behavior (session-home). `w` is added → worktree. The footer hint line grows a `w worktree` segment next to `e enter`.

### Action shape

The TUI's terminal action carries the target:

```clojure
;; Old: [:enter p s]
;; New: [:enter p s :home]   or   [:enter p s :worktree]
```

The TUI task's `run-action` destructures the target and forwards `:cd <target>` to `session/enter`.

### Path resolution

`enter!` resolves the target inside the function:

- `:home` → `(state/session-home-dir project session)` (unchanged)
- `:worktree` → `(fs/path session-home "worktree")`

The `worktree/` entry inside session-home is a symlink. We do **not** `realpath` it. The shell's `cd` follows the symlink at lookup time, but the user's `$PWD` and prompt show the symlink path — shorter and more meaningful than the deep `~/Code/<project>-worktrees/<session>/` real path.

The existence check applies to the **resolved target**: if `:cd worktree` is requested but the symlink is missing or dangling, `enter!` throws with a hint to run `session:up`.

## Implementation surface

Three source files change, narrowly:

1. **`src/nido/session/lifecycle.clj`** — `enter!` learns a `:cd` opt. Validation + path resolution live here.
2. **`src/nido/tui.clj`** — bind `w` alongside `e`; queue actions with the target appended. Update the footer hint.
3. **`src/tasks/nido_tui.clj`** — `run-action`'s `:enter` branch destructures the target and forwards `:cd <target>` to `session/enter`.

`src/tasks/nido_session.clj` does **not** need parser changes — `split-args` already accepts arbitrary kwargs and `enter` passes `opts` straight through to `lifecycle/enter!`.

## Out of scope

- `session:up`'s "ready" output stays the same (prints session-home + the enter command).
- No new bb task.
- No config file knob; default stays session-home everywhere.
- No change to `.last-cd` semantics — still a single path the wrapper reads.

## Open questions

None at design time.
