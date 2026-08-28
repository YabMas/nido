# Nido

Agent orchestrator and harness for managing development sessions across projects and providers. Nido owns the workflow shell — session lifecycle, per-session services, and a session home you can `cd` into and launch whatever agent you like (claude, codex, …). Per-project domain knowledge (lane routing, REPL discipline, Datastar/statechart contracts, …) is borrowed from the target project on demand rather than re-stated here.

Built as a standalone Babashka project. CLI entry points are defined in `bb.edn` as tasks.

## Working model

Nido is the harness; the active project (e.g. brian) is the workspace. Each running session has a **session home** at `~/.nido/sessions/<project>/<session>/` containing:

- `CLAUDE.md` / `AGENTS.md` — short briefing with the worktree path and live service ports.
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

To launch an agent with nido's live-session context injected from inside a
session worktree, use `nido work`. It defaults to Claude Code. Use
`nido work :agent codex` for Codex; nido writes a managed
`AGENTS.override.md` into the worktree so Codex sees the active ports, lifecycle
rules, links, and project briefing from its normal instruction-discovery chain.

### Fail-loud `git` in jj-workspace worktrees

Session worktrees are non-colocated jj workspaces nested *inside* the colocated
source repo, so a bare `git` in a worktree finds no local `.git`, walks up, and
silently binds to the **parent source repo** — returning wrong content and
history without erroring (`git show <rev>:file` reads a blob from the wrong
repo). jj is the source of truth in these worktrees. Add this cwd-gated guard to
`~/.zshrc` so bare `git` fails loudly instead of lying; it passes through
untouched in colocated repos, plain-git worktrees, and non-repos.

```zsh
# Fail loud instead of silently binding to the PARENT source repo when a bare
# `git` runs inside a nido jj-workspace worktree. Block iff git and jj disagree
# on the repo root; passthrough everywhere else.
git() {
  local top jjroot
  top=$(command git rev-parse --show-toplevel 2>/dev/null)
  jjroot=$(command jj root 2>/dev/null)
  if [[ -n "$jjroot" && -n "$top" && "$top" != "$jjroot" ]]; then
    print -u2 "✋ nido: bare git binds to the SOURCE repo here ($top), not this"
    print -u2 "   jj workspace ($jjroot) — it returns wrong content/history. Use jj:"
    print -u2 "   jj st | jj log | jj diff | jj show <rev>"
    print -u2 "   file at a revision:  jj file show -r <rev> <path>   (NOT git show <rev>:<path>)"
    return 1
  fi
  command git "$@"
}
```

The guard reaches agents because Claude Code's Bash tool sources a snapshot of
your zsh environment (the same mechanism that makes `nido()` visible). It never
interferes with jj (which drives its git backend in-process) or nido's own
`git worktree add` for plain-git projects (babashka `execvp` bypasses shell
functions). It activates for claude sessions started *after* you add it (the
shell snapshot is captured at session start) — restart live sessions to pick it
up. Escape hatch for a deliberate raw-git call: `command git …`.

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

### Notion token in the agent's environment

A project's `bb notion:*` tasks read `NOTION_TOKEN`, and projects load it with
direnv from a gitignored secrets file. **direnv can never deliver it to an
agent.** It hooks interactive shells, and `claude` is launched from the session
home anyway — not from the project checkout — so the repo's `.envrc` never runs
in the shell the agent inherits from. Every `bb notion:*` call from an agent
therefore dies on `NOTION_TOKEN or NOTION_API_TOKEN env var required`, which
reads as *"you have no Notion access"* and stops the agent cold.

nido already holds the token: `bb nido:notion:auth:set` writes it to the macOS
Keychain under service `nido-notion`. Export it from there, once, in the shell
every agent inherits from. Add to `~/.zshrc`:

```zsh
# Publish nido's Notion PAT as NOTION_TOKEN so every project's `bb notion:*`
# task works in an agent session. direnv-loaded secrets never reach an agent
# (interactive-shell hook; and `claude` starts from the session home, not the
# repo), so the token has to be in the environment the agent inherits.
_nido_notion_token=$(security find-generic-password -s nido-notion -w 2>/dev/null)
[[ -n "$_nido_notion_token" ]] && export NOTION_TOKEN="$_nido_notion_token"
unset _nido_notion_token
```

**The non-empty guard is load-bearing.** `(System/getenv "NOTION_TOKEN")`
returning `""` is truthy in Clojure, so an unconditional export with no keychain
entry would shadow the `NOTION_API_TOKEN` fallback and turn a clear
missing-credential error into an opaque 401.

Two limits worth knowing:

- **It reaches sessions started after you add it** — the agent inherits the
  environment of the shell that launched it, so live sessions keep the old one.
  Same for rotating the token: new shells only.
- **It does not reach coordinator-spawned Runs.** The daemon runs under launchd,
  which does not source `~/.zshrc`. That is fine today because the autonomous
  skills (`/triage-bug`, `/triage-slack`) use the `notion` CLI, which carries
  its own credential — but a Run that shells `bb notion:*` would fail.

The `notion` CLI is unaffected by all of this and stays the path agents should
reach for first; see `docs/reference/notion-access.md`.

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

## Landing work — no pull requests

**Nido's own work lands by integrating with `main` locally and pushing. This
project does not use pull requests on itself.** The harness skills injected into
every session (`/stack`, `/prepare-draft-pr`, `/drive-home`) presume a PR per
layer, because they are written for the *target* projects nido manages. **Where
they and this section disagree, this section wins** — for this repo only.

From a session worktree, the whole landing:

```bash
jj git fetch                                  # main moves under you; other sessions land too
jj rebase -s <bottom-change-id> -d main       # replay the arc onto main's tip
jj resolve --list                             # exits 2 with "No conflicts" in the CLEAN case
bb nido:test                                  # green BEFORE it lands — nothing gates it after
bb nido:land:check                            # the design stands, and the code still obeys it
jj bookmark set main -r <top-change-id>       # fast-forward
jj git push -b main
```

**`main` is linear and stays that way** — it has no merge commit anywhere in its
history, and the rebase is what keeps that true. `jj git push -b main` refuses a
non-fast-forward, so a stale base surfaces as a rejected push rather than a merge
commit; fetch, rebase again, re-run the tests.

**Nothing runs CI after the push.** There is no merge queue and no required check
on this repo, so what runs before the fast-forward is the only gate there is.
Landing red means main is red for whoever fetches next.

**Clean up the local bookmarks.** Never push the session bookmark. Once `main`
points at the same commit, delete the `<session>--<slug>` layer bookmarks — they
are local-only, and leaving them lying around is how a later bare `jj git push`
resurrects branches nobody wanted.

## The declared design — `canvas/bands.clj`

Nido's high-level structure is a **model**, not a diagram: eight bands, each
claiming a namespace prefix, each declaring which bands it may depend on. Fukan
checks it against the extracted call graph, so the declaration and the code
cannot quietly disagree.

- **Membership is derived from the path.** A namespace's band is readable from
  its name and cannot drift from the tree, which is what the 2026-08-28
  restructure bought. Every namespace must belong to a band — an unbanded
  package is invisible to every other law, so that one is enforced too.
- **`bb nido:design:check`** answers directly (exit 1 on a violation, 2 when the
  check could not be decided — which is never read as a pass).
- **`bb nido:land:check`** refuses the landing on the same answer.
- **The review loop** runs it as one more reviewer in the round, so a violation
  gets a handle, an owner layer and a fixer like any other finding. A violation
  the loop cannot resolve is a `park`: "the code moves or the declaration does"
  is a decision, not a repair.
- **Every session briefing carries the declaration verbatim**, so an agent knows
  the bands before it writes a require.

Changing the declaration is a legitimate fix — but it is a design change. Say in
the commit why the rule was wrong, rather than widening it until the code fits.

Any project nido drives gets this if it has a `canvas/`: detection is by
convention, and a project without one is `:unmodelled`, never broken.

### What still applies, and what doesn't

**The decomposition doctrine is unaffected; only its destination changes.**
`/design` still writes the baseline and the design record to the ledger,
`/stack` still says how to cut layers, `/spin-out` still routes what leaves the
branch, and `/phase` still decides whether the change can arrive in one landing.
A layer is simply a **commit** here rather than a PR — same one-sentence title
with no "and", same `Layer:` trailer, same Claims / Verify / Lane / Out of scope
brief in the commit message. The brief is worth *more* in this mode, not less:
it is the only artifact a later reader gets, since there is no PR conversation
to reconstruct the reasoning from.

**What does not apply:** `/prepare-draft-pr`, `/drive-home`, every `gh stack`
recipe in `/stack` §4 and §6, and `nido ship`. `nido ship` hands a branch to the
coordinator's merge lane, which ends at `gh pr merge --auto` on GitHub's native
merge queue — that is the path for target projects with CI, not for nido.

**The PRs in this repo's history are not counter-examples.** Most of them are
throwaway probes (`probe stack alpha/beta/gamma/delta/epsilon`, `chore(probe):
layer one`) opened to work out `gh stack link`'s behaviour for the `/stack`
skill. Nido *builds* the PR machinery; the projects it manages are what consume
it.

## Closing a work arc

**End every work arc by rebasing the root `~/Code/nido` checkout onto local `main`** — `jj rebase -d main` (or `jj rebase -r @ -d main@origin` to carry uncommitted working-copy changes forward). Work lands from session worktrees, which share the store but not the root workspace's working copy, so the root checkout stays wherever it was and silently drifts behind `main` as arcs land elsewhere.

This is load-bearing because the **session launcher injects nido's native harness artifacts by listing the on-disk `~/Code/nido/.claude/` subdirectories** (`nido-native-entries` → `fs/list-dir`, at runtime — it reads the working tree, not git). Two subdirectories are injected: `skills/` (each native directory) and `agents/` (each native file). A stale root checkout that predates a newly-merged one therefore starves **every** session of it: it is committed, pushed, and in main's tree, yet missing from disk, so it's never composed in. The same hazard applies to any launcher input read from the working tree.

Symptom & fix: a shipped harness skill or reviewer "missing" from sessions → check `ls ~/Code/nido/.claude/skills/<name>` or `ls ~/Code/nido/.claude/agents/<name>.md` (on disk, not `jj file list`). Present in main's tree but absent on disk ⇒ stale checkout ⇒ rebase as above, then re-`session:up` the live sessions (or restart the in-session agent) so the launcher recomposes them.

**A nido-owned agent reaches every project nido drives, and a project's own `.claude/agents` is therefore not the whole roster its sessions can dispatch.** Same rule as skills: a native entry wins a name clash with the project's. Put an agent here only when it is genuinely harness-wide — a project's reviewer belongs in that project's tree.

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
- `bb nido:session:enter :project <p> <session>` — write the session-home path to `~/.nido/.last-cd` and exit. Pair with the `nido` shell function (see "Shell wrapper" above) to actually `cd` your shell there. Refuses if the session is down. Pass `:cd worktree` to land in the worktree (the actual code) instead — useful when you want to edit / git-grep without the extra `cd worktree`. The TUI exposes the same opt-in on a workstream's detail screen, on its session rows: <kbd>↵</kbd> for session-home/chat, <kbd>w</kbd> for worktree, plus <kbd>u</kbd>/<kbd>d</kbd>/<kbd>x</kbd> for up/down/destroy (and the board's <kbd>↵</kbd>/<kbd>o</kbd> opens a workstream's session-home directly) — but in Warp the TUI spawns a new tab in place rather than quitting to this `cd`-handoff (see "How land in the session works" above). The CLI verb always uses the `.last-cd` handoff.
- `bb nido:session:down :project <p> <session>` — stop the session; worktree + on-disk state preserved.
- `bb nido:session:reset :project <p> <session>` — nuclear recovery. Down → drop PGDATA → re-clone from the current template → up. The "I'm wedged, fix it" button.
- `bb nido:session:destroy :project <p> <session>` — down + remove the worktree.
- `bb nido:session:status` / `nido:session:list` — read-only.

There is no `restart` task — use `down` then `up` (or just `up`, since it's idempotent on a running session and a no-op there). The dashboard's restart button still exists; it's an internal UI-only path.

Sessions stay up until explicitly stopped. There is no automatic idle-suspension — bring a session `down` yourself when you're done with it.

Debug escape hatch: `bb nido:session:status` / `bb nido:session:list` stay scan-based and model-independent — they work even when the workstream model is wedged.

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
is gone: every live session belongs to a workstream (the daemon adopts orphans into scratch
workstreams every 5 minutes), machine facts (ports, RSS, lifecycle) live on each workstream's
session rows, closed-but-still-running workstreams surface in the Active tab's winding-down
band, and global ops levers (halt, breakers, fire-trigger) sit behind the rail's health dot.
`/system` redirects to `/workstreams`. The Workstreams overview (`/workstreams`) filters by **scope** (project), **source** (All/Notion/GitHub/Slack/Scratch, with per-source counts) and **context-dependent facets** (App Domain / Type for the Notion source) — the cockpit's overview surface. The TUI stays a lean session launcher. Override the port with `bb nido:coordinator:up :dashboard-port <n>`;
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

**Reaction-gated Slack intake (`:slack-reaction`).** Replaces the passive `:triage-slack-bugs` queue above with a human-gated pull: a human drops an `:owl:` reaction on a message in the bug channel, which starts a `triage-slack` session that investigates against the running brian codebase and parks a proposed ticket in the gate inbox. The human Approves → nido creates a "Not started" ticket in brian's Task DB and posts the link back to the Slack thread. The source (`:type :slack-reaction :channel "C..." :emoji "owl" :window "3d" :poll "2m"`) scans a rolling 3-day window for newly-owl'd messages, dedups against a seen-set, and cold-starts silently (first poll seeds, emits nothing). Trigger shape: `:triage-slack-reactions` (`:intake :spawn`, `:skill :triage-slack`, `:session-profile :full`). Slack scopes: `reactions:read`, `chat:write` (plus `reactions:write` only if the optional 👀 start-ack is later wired up) — `channels:history` is already granted for the passive queue above.

**Foreground (still supported for development):**

```
bb nido:coordinator:run :poll-ms 500
```

**Working with triggers/runs:**

```
bb nido:trigger:fire :project brian <name> :<key> <value>
bb nido:runs:list / bb nido:runs:show <id>
```

Or in the TUI: the spine board lists workstreams by stage — `↵`/`o` opens a workstream's session, `i` inspects its sessions (autonomous runs show on the autonomy axis), `n` starts a new one-off, `p`/`P` promote (default / pick-a-stage), `d` marks done, `tab` cycles the origin filter, and `s` opens the ops overlay (halt / clear breaker / fire / pickup).

### Classification-facet sub-queues

The TUI board can slice an origin (Notion) into composable sub-queues by durable
ticket classifiers. Add `:facets ["App Domain" "Type"]` to a project's
`~/.nido/projects/<project>/notion-views.edn`; the board then shows two
AND-composed selectors (`[`/`]` cycle App Domain, `{`/`}` cycle Type). Facets are
stored on each workstream — stamped at creation, refreshed when a triage verdict
is applied (`nido:ticket:complete`), and re-syncable on demand with
`bb nido:facets:refresh :project <p> [:ws <id>]`. They are a pure organizational
lens; the stage spine is unchanged.

### Board views (`:board-views`)

`:board-views` in `notion-views.edn` names the views whose cached pages the board
reads live Notion status from — and nido **polls them itself**, at `:board-poll`
(default 5m), for any view no trigger already covers. Such a source emits like any
other and routes to nothing (no trigger declares it), so nothing spawns: the
poll's page snapshot is the whole point.

This is what makes Notion's own lifecycle visible to nido. A ticket whose status
reaches a terminal value projects to the `:done` stage and leaves the Active band
— **"Review" included**, since that is the handoff where nido's involvement ends.
The projection is stateless (`nido.coordinator.session/notion-stage`): it writes
nothing, so a ticket bounced back to In progress simply returns to the board.
Keep the view narrow. Widening it to "Code Review" would put every merged-and-
closed workstream back on the Active band, because a Notion-driven row ignores
nido's own `:closed`.

**Safety brakes (Stage 2):** per-Run wall-clock budget (`:limits.budget`, default 30m, SIGTERM→SIGKILL), per-trigger circuit breaker (`:limits.max-failures`, default 3), daemon-wide anomaly auto-halt, kill switch (`bb nido:halt` + `bb nido:coordinator:resume`). On the TUI, `s` opens the ops overlay: `h` halts, `c` clears a breaker, `f` fires a trigger.

**Startup reconciliation (Stage 3):** when the daemon starts, any non-terminal Run on disk is forced to a terminal state from observable evidence (artifacts, `_run-status.edn`, agent.log). Crashed/orphaned Runs get marked `:failed :reason :orphaned-from-restart` so the dashboard stays honest.

Full design: `docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md`. Skill conventions: `docs/skill-conventions-for-triggers.md`.

Stage 4 added launchd auto-start at login; Stage 5 added the Notion source; cron / GitHub remain future work.

## Delegation

Specialist agents live in `.claude/agents/`. Most domain agents are borrowed from brian via symlink — `datastar-dev` points at brian's, and `dev-rules.md` is symlinked too so its `@.claude/dev-rules.md` import resolves correctly. Native nido agents (currently just `architect`) live alongside the symlinks.

When a task falls inside an agent's domain, delegate to it via the `Agent` tool rather than doing the work directly — it carries domain rules and tooling the main agent does not.
