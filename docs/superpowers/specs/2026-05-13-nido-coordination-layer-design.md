# nido coordination layer — the next chapter

**Status:** designed, not implemented
**Date:** 2026-05-13

## Context

Nido's first chapter focused on session lifecycle: bringing up isolated workspaces (worktree + PG + JVM + app) so the user can `cd` into one and run an agent. That works.

The next chapter adds a **coordination layer**: a long-running daemon that watches events, fires automated investigation Runs against configured triggers, and surfaces those Runs in their own TUI view. The goal is semi-autonomous behavior — nido brings work to the user, ready to review, instead of the user always initiating.

The motivating example: every weekday, the daemon reads the top 5 untriaged Notion tickets, invokes `/investigate-bug` against each, and waits for the user. The user opens the Run overview, sees which sessions need attention, drops into chat, reviews the analysis, redirects the agent if needed, and moves on. v1 of this spec does not wire up Notion — it builds the coordinator and the Run lifecycle, and the only event source initially is "manual trigger from CLI / TUI." Real event sources land in later increments.

## Goals

- A long-running coordinator process that survives login sessions and restarts.
- A configuration model where new triggers can be added with EDN edits, not code changes.
- A plugin model for event sources so Notion / cron / GitHub can be added without touching the rest of the system.
- A Run lifecycle clearly separate from session lifecycle.
- A Run overview TUI surface that tells the user where to check in, without duplicating the chat as a decision UI.
- Safety brakes (hard parallel caps, wall-clock budgets, circuit breaker, kill switch, anomaly auto-halt) that are always-on once enabled.
- An incremental rollout path that defers launchd auto-start until confidence is established.

## Non-goals

- An "Inbox" / notification timeline. Deferred — Run overview is enough for v1.
- Accept / reject / defer / redirect UI affordances on the Run overview. The chat session is the review UI; decisions live in the chat.
- A playbook primitive distinct from skills. v1 uses skills directly; a playbook primitive can be added later as a non-breaking extension if reuse / metadata / discovery earn it.
- Real-time agent progress streaming, agent SDK integration, agent vendor selection beyond claude. The launch model is designed to allow these later without rework.
- Multi-user / multi-host coordination. Single laptop, single user.

## Architecture

```
┌───────────────────────── coordination layer (NEW) ──────────────────────────┐
│                                                                             │
│   ┌──────────────┐    fires    ┌─────────┐   spawns    ┌────────────────┐   │
│   │   Trigger    │ ──────────▶ │   Run   │ ──────────▶ │  Session       │   │
│   │   (config)   │             │ (state) │             │  + agent       │   │
│   └──────────────┘             └─────────┘             │  + artifacts   │   │
│         ▲                          ▲                   └────────────────┘   │
│         │ evaluates                │ reflected by                           │
│   ┌──────────────┐            ┌─────────────────────────┐                   │
│   │  Coordinator │            │  Run overview (TUI)     │                   │
│   │  (daemon)    │            │  reflects state, routes │                   │
│   └──────────────┘            │  user into chat         │                   │
│         ▲                     └─────────────────────────┘                   │
│   ┌─────┴─────────────────────────────────────────────────────────────┐     │
│   │ Event sources (plugin): manual (v1); notion, cron, github (later) │     │
│   └───────────────────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────────────────┘
┌────────────────────────── session layer (existing) ─────────────────────────┐
│  bb nido:session:up / down / enter / destroy   •   TUI sessions screen      │
│  worktree • PG • app • REPL • per-session DB                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Glossary

- **Coordinator** — long-running babashka daemon. One per user. Owns event sources and Run state.
- **Event source** — plugin that produces events. Registered in code with a config schema, an event-payload schema, and a `start!` function.
- **Trigger** — per-project config binding an event source + filter to a skill + payload template, with optional per-trigger safety overrides.
- **Run** — one firing of a trigger. Lifecycle: `queued → running ⇄ awaiting-review → done | failed | halted`. Owns a session 1:1.
- **Session** — existing primitive (worktree + services). Run-owned sessions carry `:owned-by-run <run-id>` in their `session.edn`.
- **Agent** — claude-code (v1); design accommodates additional vendors via per-trigger `:agent` field.

### Layer separation

Sessions remain a primitive that can be created and destroyed by hand. The coordination layer uses sessions but never owns them in a way that prevents manual control. Run state and session state are independent: a Run can terminate without destroying its session; a session can be destroyed manually, which causes its Run to derive a terminal state from observation but doesn't reach into Run state directly.

## Staged rollout

The whole spec is in scope, but it ships in stages. Each stage is a separate piece of work and a separate merge. We don't move to the next stage until the previous is solid.

| Stage | Deliverable | Daemon mode |
|---|---|---|
| 1 | Foreground daemon + manual trigger source + Run lifecycle + minimal Run overview. Fire Runs by hand from CLI; observe the pipeline end to end. | `bb nido:coordinator:run` (foreground) |
| 2 | Wall-clock budget, circuit breaker, anomaly auto-halt, kill switch. Stress-test with intentionally broken skills. | Foreground |
| 3 | Background daemon (process manager but not launchd). Use it for real over several days with manual triggers only. | Background, manual start |
| 4 | launchd plist + `bb nido:coordinator:install`. Coordinator starts at login, restarts on crash. Still only manual triggers. | Auto-start |
| 5 | First real event source: Notion poller. Autonomous behavior begins here. | Auto-start |
| 6 | Additional sources (cron, github, …) one at a time. | Auto-start |

Stages 1–4 are confidence-building with no autonomous behavior. Stage 5 is where things fire on their own.

## Safety brakes (always on, once enabled)

Once the coordinator is running, triggers fire without per-trigger opt-in. These limits are always active:

| Brake | Default | Effect when hit |
|---|---|---|
| Global parallel cap | 2 Runs | New Runs queue, run when a slot frees |
| Per-project parallel cap | 1 Run | Same |
| Per-Run wall-clock budget | 30 min | Process `SIGTERM`→`SIGKILL`; Run `:failed :timeout`; session preserved |
| Circuit breaker | 3 consecutive failures on one trigger | Trigger auto-disabled; surfaced in overview |
| Anomaly auto-halt | >5 Runs/min spawning OR ≥3 failures in 5 min | Daemon halts itself; resume is manual |
| Kill switch | — | `bb nido:halt` — stops all in-flight Runs, pauses evaluation; resume with `bb nido:coordinator:resume` |

Per-trigger overrides exist for all of these. Defaults ship conservative.

**Dry-run mode** — a trigger may declare `:dry-run? true`. The trigger evaluates as normal and records a `:dry-run-would-fire` Run, but no session is spawned. Used to validate new trigger configs before going live.

**Visible state.** The Run overview header always shows:
- Coordinator status (`running` / `halted` / `auto-halted: <reason>`)
- Slot usage (`<used>/<cap>` global plus per-project)
- Any triggers in circuit-breaker or invalid state

## The coordinator daemon

**Process model.** One babashka process per user. Auto-started by launchd at login from stage 4 onward:

```
~/Library/LaunchAgents/dev.nido.coordinator.plist  (installed via bb nido:coordinator:install)
   └── exec: bb nido:coordinator:run
       └── reads ~/.nido/coordinator/config.edn
       └── boots event sources
       └── watches ~/.nido/coordinator/queue/
       └── owns the loop: evaluate → spawn → monitor
       └── persists state to ~/.nido/runs/<run-id>/
```

`KeepAlive=true` so it restarts on crash. Manual control via `bb nido:coordinator:status / restart / logs`.

**Comms with TUI/CLI: filesystem-canonical, no socket.** All Run state, all client→daemon requests, and the daemon's heartbeat are files on disk. The daemon watches well-known directories and reacts; TUI and CLI read the same files for display. No socket, no HTTP, no RPC.

Concretely:
- Run state — `~/.nido/runs/<run-id>/run.edn` plus artifacts.
- Manual trigger requests — `~/.nido/coordinator/queue/<uuid>.edn`. Daemon picks up, processes, deletes.
- Daemon heartbeat — `~/.nido/coordinator/status.edn`, rewritten every 2s. TUI uses age to detect "daemon is gone."
- Trigger config — `~/.nido/projects/<project>/triggers.edn`. Daemon hot-reloads on file change.

This makes the daemon "just" a smart watcher. Crash-resilience is automatic; no in-memory state to lose. A socket can be added later (for live log streaming or to cancel a Run mid-flight) as a non-breaking extension.

**Crash recovery.** On boot the daemon scans `~/.nido/runs/` for non-terminal Runs and reconciles: if the agent process is alive, monitoring resumes; otherwise the Run is given a terminal state based on observed evidence (artifacts, status file, claude JSONL ending events, exit code if captured).

**File watching.** Babashka uses `nio2` watch (or 500ms polling fallback). Good enough for human-driven workflows; trivially replaceable.

## Triggers

Per-project config at `~/.nido/projects/<project>/triggers.edn`, hot-reloaded on file change.

```edn
;; ~/.nido/projects/brian/triggers.edn
{:triggers
 [{:name      :investigate-bug
   :source    {:type :manual}
   :skill     :investigate-bug
   :payload   "{{event/url}}"
   :payload-key :ticket-id}             ; optional: which payload field to show in Run overview row

  ;; not wired in v1; shape sketch for plugin validation
  {:name      :notion-bug-triage
   :source    {:type     :notion-view
               :database "abc-123"
               :view     "untriaged-bugs"
               :poll     "5m"}
   :filter    {:status "Untriaged" :priority ["P0" "P1"]}
   :skill     :investigate-bug
   :payload   "{{event/url}}"
   :payload-key :ticket-id
   :limits    {:cooldown "10m" :budget "45m" :max-failures 3}
   :dry-run?  true}]}
```

### Fields

| Field | Required | Notes |
|---|---|---|
| `:name` | yes | Keyword, unique per project. Used in CLI / UI / logs. |
| `:source` | yes | `{:type … + source-specific keys}`. v1 ships `:manual`; others designed-in. |
| `:filter` | no | Predicate over event payload. v1: map-equality and set-membership. Richer DSL later. |
| `:skill` | yes | Keyword naming an existing claude-code skill from `.claude/skills/` (project or user-global). |
| `:payload` | yes | Template string. Placeholders `{{event/<key>}}` and `{{event/<key>/<subkey>}}` are interpolated against the event payload. |
| `:payload-key` | no | Path into the event payload to display as the "subject" of the Run row in the overview. Falls back to run-id suffix. |
| `:agent` | no | Default `:claude`. Designed-in for multi-vendor; only `:claude` implemented in v1. |
| `:limits` | no | Per-trigger overrides for `:cooldown`, `:budget`, `:max-failures`. Coordinator defaults if absent. |
| `:dry-run?` | no | If true, trigger evaluates and records a Run, but no session is spawned. |
| `:enabled?` | no | Default true. Set false to keep config but suspend firing. The circuit breaker uses runtime state separately, so it doesn't fight your edits. |

### Payload templating

Small, deliberately limited interpolation language over the event payload:
- `{{event/<key>}}` — top-level lookup
- `{{event/<key>/<subkey>}}` — nested
- Literal text passes through

The interpolated string is the only thing the skill sees. The skill doesn't need to know it was triggered automatically.

### Validation

On daemon boot and on file-change reload, each trigger is validated against a Malli schema:
- `:source.type` must be a registered source.
- `:source` block must match that source's `:schema`.
- `:filter` keys must be referenced in the source's `:events` schema.
- `:payload` template placeholders must match the source's `:events` schema.

Invalid triggers are logged, shown in the Run overview with an `:invalid` badge and the reason, and skipped at evaluation time. One broken trigger doesn't take down the others or crash the daemon.

## Event sources

### Plugin contract

```clojure
(register-source!
 :type :notion-view
 :schema <malli schema for the source config a trigger supplies>
 :events <malli schema for the event payloads this source emits>
 :start! (fn [source-config emit-fn] ...))   ; returns {:stop! ...}
```

- `:schema` validates the `:source` block inside trigger configs at load time.
- `:events` validates each event before it reaches a trigger filter.
- `:start!` is called by the coordinator once per *distinct source-config* (deduped — two triggers watching the same Notion view share one poller). Returns a stop handle.

### Two event-flow patterns

1. **Broadcast** — `:notion-view`, `:cron`, `:github` (future). Source emits events without knowing about triggers; coordinator routes each event to all triggers whose `:source` block matches and whose `:filter` accepts the payload.
2. **Direct-target** — `:manual` only. User names the trigger when firing, so the event is self-routed. No filter evaluation.

These collapse into one pipeline at the coordinator level: an envelope is either `{:broadcast event}` or `{:target {:project p :trigger t} :payload}`, the coordinator handles both, downstream sees no difference.

### v1: `:manual` only

The "source" is degenerate — there's no poller. The coordinator's filesystem watcher on `~/.nido/coordinator/queue/` *is* the manual source.

Queue file:

```edn
;; ~/.nido/coordinator/queue/<uuid>.edn
{:target  {:project :brian :trigger :investigate-bug}
 :payload {:url "https://notion.so/page/abc" :ticket-id "ABC-123"}
 :created-at "2026-05-13T09:14:22Z"}
```

CLI:

```
bb nido:trigger:fire :project brian investigate-bug \
   --url https://notion.so/page/abc --ticket-id ABC-123
```

The CLI just writes the queue file; the coordinator does everything else. CLI works whether the coordinator is up or not — files accumulate and get processed when the daemon returns.

### Future-source sketches (designed-in, not built in v1)

- **`:notion-view`** — `{:database :view :poll}`. Poller thread queries the Notion API, diffs against last-seen state, emits one event per new/changed record. Dedup key: `[:database :view]`.
- **`:cron`** — `{:expr "0 9 * * MON-FRI"}`. Emits a tick event when the cron expression fires. Payload `{:fired-at <ts>}`.
- **`:github`** — `{:repo :events [:pr-review-requested ...]}`. Webhook receiver or poller; one event per matching GitHub event.

## Skills as auto-trigger targets

A trigger's `:skill` field names an existing claude-code skill. Most skills can be used as-is. Skills written with auto-triggering in mind follow these conventions so the Run lifecycle reflects what they're doing. None are enforced — they're a contract.

### Conventions (light)

1. **Artifacts in `<session-home>/artifacts/`** with stable filenames (`analysis.md`, `proposal.md`, `plan.md`, …). Stable names let the Run overview and the user know what to look for.
2. **Status file** at `<session-home>/_run-status.edn`, updated at phase transitions:
   ```edn
   {:phase :awaiting-input
    :note "Analysis written; does this match your read of the ticket?"
    :artifact "artifacts/analysis.md"}
   ```
   Phases: `:investigating | :working | :awaiting-input | :complete | :error`. The daemon polls this file.
3. **Idempotency.** If the same skill is re-invoked in a session-home where prior artifacts exist, it should read them and resume — not blindly overwrite.

These live in `docs/skill-conventions-for-triggers.md` (skill authors read once). No CLI for skills to call (file convention only, per the earlier decision); a bubble-up CLI can be added later if a real use-case demands it.

## Runs

The Run record is the load-bearing data structure of the coordination layer. Everything else reads or writes Run state.

### Identity & storage

- **Run id:** `YYYY-MM-DD-<project>-<trigger>-<8-char-uuid>` — readable, sortable, collision-free.
- **Storage:** `~/.nido/runs/<run-id>/`. Self-contained directory; inspectable with `ls` and `cat`.

```
~/.nido/runs/2026-05-13-brian-investigate-bug-a1b2c3/
├── run.edn              # canonical state record
├── _run-status.edn      # written by the skill
├── artifacts/           # written by the skill
│   ├── analysis.md
│   └── proposal.md
├── agent.log            # captured stream-json + stdout/stderr
└── session-home -> ~/.nido/sessions/brian/run-2026-05-13-investigate-bug-a1b2c3/
```

### `run.edn` shape

```edn
{:id              "2026-05-13-brian-investigate-bug-a1b2c3"
 :project         :brian
 :trigger         :investigate-bug
 :source          {:type :manual :fired-at "..." :fired-by "yabmas"}
 :event-payload   {:url "..." :ticket-id "ABC-123"}
 :skill           :investigate-bug
 :first-message   "/investigate-bug {{interpolated}}"
 :agent           :claude
 :session-name    "run-2026-05-13-investigate-bug-a1b2c3"
 :claude-session-id "abcd-1234"          ; for --resume
 :limits          {:budget "30m"}        ; effective values after defaults
 :state           :awaiting-review
 :state-history   [{:at "..." :state :queued}
                   {:at "..." :state :running}
                   {:at "..." :state :awaiting-review}]
 :artifacts       [{:path "artifacts/analysis.md" :written-at "..."}]
 :error           nil}
```

### Lifecycle (6 + 1 states)

| State | Entry condition | Exit |
|---|---|---|
| `:queued` | Trigger fires; no slot available | Slot opens → `:running` |
| `:running` | Agent process spawned and alive | Process exits → `:awaiting-review` / `:done` / `:failed` |
| `:awaiting-review` | Process exited 0; `_run-status.edn` says `:awaiting-input` | User attaches & continues → `:running` again, OR work concludes → `:done` |
| `:done` | `_run-status.edn` says `:complete`, OR session destroyed | terminal |
| `:failed` | Process exit non-zero, OR wall-clock budget exceeded | terminal |
| `:halted` | `bb nido:halt` invoked | terminal — to resume, manually re-fire (creates a new Run) |
| `:dry-run-would-fire` | `:dry-run? true` trigger evaluated | terminal — never spawned a session |

A Run bounces between `:running` and `:awaiting-review` as the conversation progresses (agent yields → user attaches → agent works again → yields). Each transition records to `:state-history`.

### Relationship to sessions

- Run creation **spawns** a session, marked with `:owned-by-run <run-id>` in its `session.edn`.
- Run ↔ Session is 1:1.
- A Run reaching a terminal state **does not** destroy the session. The session lives on as a normal session.
- Destroying the session **does** push its Run to `:done` (state derived from observation: no agent + no further activity = work concluded).

### Slot accounting

- A `:queued` Run does not own a slot and has not spawned a session yet.
- A `:running` or `:awaiting-review` Run owns a slot and has a session.
- A terminal Run releases its slot but its session lingers per the rule above.

### Retention

`bb nido:runs:prune` removes `:done` Runs older than 30 days and `:failed` Runs older than 90 days (both config-overridable). Pruning a Run does not touch its session. `--dry-run` previews. Run records older than the threshold but still pointing at a live session are preserved regardless.

## Agent launch

When a Run transitions `:queued → :running`:

1. Coordinator brings the session up using existing `nido:session:up` machinery, with `:owned-by-run <run-id>` set in the session.edn template.
2. Coordinator spawns claude-code headlessly for the autonomous phase:

   ```
   cd <session-home>/worktree
   claude \
     --print \
     --output-format=stream-json \
     --dangerously-skip-permissions \
     --append-system-prompt "$NIDO_AGENT_PREAMBLE" \
     "$FIRST_MESSAGE"
   ```

3. Claude runs the skill, makes its own choices, writes artifacts, eventually exits. The session services (PG, app, REPL) stay up; idle-stop applies normally.
4. Stream-json output is tee'd to `<run-dir>/agent.log` and parsed live to extract the claude session-id (written into `run.edn` within seconds of launch) and tool-call counts.
5. On clean exit: read `_run-status.edn` → `:awaiting-input` ⇒ `:awaiting-review`; `:complete` ⇒ `:done`; absent ⇒ `:done`.
6. On non-zero exit: Run `:failed`, exit code in `:error`.

The `--append-system-prompt` injects a short nido preamble: where artifacts go, the `_run-status.edn` convention, the run-id, and a reminder that the user is not present yet. This is what makes a plain skill auto-trigger-aware without modifying the skill.

The session-home's `.mcp.json`, `CLAUDE.md`, and `.claude/` symlink load automatically because claude runs with cwd = `<session-home>/worktree`, exactly as if the user had typed `claude` themselves.

### Wall-clock budget enforcement

A small watcher loop per Run:
- Reads stream-json events as they arrive; updates `run.edn`.
- On budget hit: `SIGTERM` → wait 10s → `SIGKILL`. Run `:failed :timeout`. Session preserved.

### Resume from the session-home

After autonomous exit, the user enters the session-home (`nido` shell wrapper) and types `claude`. We make that work by writing a small shim into `<session-home>/bin/` and having the session-home's existing path-prepend (or shell startup hint) put `bin/` ahead of system `claude`.

```bash
# <session-home>/bin/claude — generated by nido:session:up for Run-owned sessions
#!/usr/bin/env bash
SESSION_ID=$(bb -e \
  "(-> (slurp \"$(dirname "$0")/../run-link/run.edn\") read-string :claude-session-id)" \
  2>/dev/null)
if [ -n "$SESSION_ID" ] && [ "$SESSION_ID" != "nil" ]; then
  exec command claude --resume "$SESSION_ID" "$@"
fi
exec command claude "$@"
```

The shim reads `run.edn` directly at invocation time (via a tiny bb one-liner), so there's no env-var coordination problem. If no claude-session-id is recorded yet (Run still starting up, or session not run-owned), it falls through to a normal `claude` invocation. `command claude` escapes the shim if the user wants a fresh conversation.

`run-link/` is a sibling symlink the session-home gets pointing at `~/.nido/runs/<run-id>/`, so the shim can find `run.edn` without hardcoding the run-id. For non-Run-owned sessions, no shim is written and `claude` resolves normally.

### No concurrent-access problem by construction

The autonomous `--print` invocation always exits before the user attaches — serial processes, never both writing to the conversation at once. The Run overview shows "agent working — attach when status flips" while autonomous; once the Run flips to `:awaiting-review`, the autonomous process is long gone and `--resume` is safe.

### Crash handling

- If the daemon dies mid-Run, the agent keeps running (spawned `:shutdown nil`). On daemon restart, it scans `~/.nido/runs/`, finds non-terminal Runs, checks whether their `:claude-session-id` corresponds to a live OS process, and either resumes monitoring or derives a terminal state from evidence.
- If the agent dies mid-Run while the daemon is alive, normal `:failed` path (non-zero exit captured).

### Multi-agent vendor support

The trigger config grows an optional `:agent` field (default `:claude`). Launch is dispatched through a small multimethod keyed on `:agent`. A future `:codex` impl is a single namespace addition. v1 implements `:claude` only.

## Run overview TUI surface

The existing TUI grows tabs: `[s]essions` and `[r]uns`. The sessions screen is untouched.

```
┌─ nido ─ [s]essions [r]uns ──────────────────────────────────────────┐
│ Coordinator: running  •  Slots: 1/2 global, 1/1 brian  •  ✓ no alerts│
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Needs attention (1)                                                │
│  ─────────────────                                                  │
│  ► [awaiting] brian · investigate-bug · ABC-123    12m ago          │
│      "Analysis written; does this match your read?"                 │
│                                                                     │
│  In flight (1)                                                      │
│  ─────────────                                                      │
│    [running]  brian · investigate-bug · XYZ-99      3m ago          │
│      tool calls: 14 · last activity: 8s ago                         │
│                                                                     │
│  Recent (3)                                                         │
│  ──────────                                                         │
│    [done]     brian · investigate-bug · DEF-456    yesterday        │
│    [failed]   brian · investigate-bug · GHI-789    2d ago · timeout │
│    [done]     brian · investigate-bug · JKL-012    3d ago           │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│ ↑/↓ select  ↵ enter session  w worktree  d details  f fire trigger  │
│ h halt  c clear circuit breaker  q quit                             │
└─────────────────────────────────────────────────────────────────────┘
```

### Header

| Slot | Content |
|---|---|
| Coordinator status | `running` / `halted (by you)` / `auto-halted: <reason>`. Color-coded. |
| Slot usage | `<used>/<cap>` global plus per-project breakdown for active projects. |
| Alerts | `✓ no alerts`, or `⚠ 1 trigger in circuit breaker · 1 invalid trigger`. |

### Groups (fixed order)

| Group | Runs in it |
|---|---|
| **Needs attention** | `:awaiting-review`, `:failed`, `:halted`. Includes the one-line note from `_run-status.edn`. |
| **In flight** | `:queued`, `:running`. Shows tool-call count and last-activity for `:running`. |
| **Recent** | Terminal Runs from the last 7 days, newest first, cap 10. |

Empty state: `No runs yet. Press 'f' to fire a manual trigger.`

### Row format

`[state] <project> · <trigger> · <payload-key>  <age>`. `<payload-key>` is the trigger's configured `:payload-key`; falls back to run-id suffix.

### Actions

| Key | Action |
|---|---|
| `↑` / `↓` | Move selection. |
| `↵` | Enter the selected Run's session-home. Same `.last-cd` handoff the sessions screen already uses. |
| `w` | Enter the worktree directly. |
| `d` | Details modal: pretty `run.edn`, artifact list with size + age, last 50 lines of `agent.log`. From here `o <n>` opens artifact `n` in `$PAGER`. |
| `f` | Fire-trigger modal. |
| `h` | Halt coordinator (asks confirm). Turns into `resume` when halted. |
| `c` | Clear a tripped circuit breaker (re-enables firing). Prompts to pick if multiple. |
| `q` | Quit TUI. |

No accept / reject / abandon / redirect keys. The chat is the review UI.

### Fire-trigger modal

1. Pick project (if more than one registered).
2. Pick a trigger from that project's `triggers.edn`. Only triggers with `:source.type :manual` appear.
3. One form field per `{{event/<key>}}` placeholder found in the trigger's `:payload`.
4. Confirm → TUI writes the queue file from §"Event sources / :manual" and toasts "queued as run-<id>". The new Run appears in "In flight" within a poll cycle.

The TUI form is an interactive wrapper over `bb nido:trigger:fire`. The CLI form still works for scripted use.

### Refresh cadence

TUI re-reads `~/.nido/runs/*/run.edn` and `~/.nido/coordinator/status.edn` once per second while on the runs screen. If the heartbeat in `status.edn` is older than 5 seconds, the header shows "Coordinator: unreachable" — the daemon is gone or stuck.

### Sessions-screen integration (light)

On the sessions screen, sessions whose `session.edn` has `:owned-by-run <run-id>` get a small `⚙` marker. Selecting one and pressing `i` (existing session-info modal) shows the Run details inline. The two screens stay distinct but discoverable.

### Out of scope for v1's overview

- Inline artifact viewing beyond the details modal.
- Accept / reject / abandon / redirect affordances.
- Snooze / defer.
- Batch operations.
- Notification timeline (the deferred Inbox).

## Directory layout summary

```
~/.nido/
├── coordinator/
│   ├── config.edn                    # global coordinator config (defaults, caps)
│   ├── status.edn                    # heartbeat (rewritten ~2s); used by TUI
│   ├── halted.edn                    # present when auto-halted; explains why
│   └── queue/
│       └── <uuid>.edn                # pending manual-trigger requests
├── projects/
│   └── <project>/
│       └── triggers.edn              # per-project trigger config
├── runs/
│   └── <run-id>/
│       ├── run.edn
│       ├── _run-status.edn
│       ├── artifacts/
│       ├── agent.log
│       └── session-home -> ~/.nido/sessions/...
└── sessions/                         # existing; unchanged
```

```
~/Library/LaunchAgents/
└── dev.nido.coordinator.plist        # installed via `bb nido:coordinator:install`
```

## CLI surface (additions)

```
bb nido:coordinator:install            # write launchd plist + load it (stage 4)
bb nido:coordinator:uninstall          # remove plist
bb nido:coordinator:run                # run foreground (stages 1-2)
bb nido:coordinator:status             # show heartbeat, caps, slot usage, halted state
bb nido:coordinator:restart            # via launchd, when installed
bb nido:coordinator:logs               # tail recent log lines

bb nido:halt                           # kill switch (alias for coordinator:halt)
bb nido:coordinator:resume             # resume after halt / auto-halt

bb nido:trigger:list :project <p>      # show triggers for a project
bb nido:trigger:fire :project <p> <name> --<key> <value> ...
bb nido:trigger:enable :project <p> <name>   # clears circuit-breaker / re-enables
bb nido:trigger:disable :project <p> <name>

bb nido:runs:list                      # list runs (filters: --state, --trigger, --project)
bb nido:runs:show <run-id>             # show run.edn + status + artifacts list
bb nido:runs:prune [--dry-run]         # apply retention policy
```

## Out of scope

- Notion / cron / GitHub event sources (later stages of the rollout).
- Notification timeline / Inbox.
- Accept / reject / defer / redirect UI affordances above the session.
- Multi-host or multi-user coordination.
- Persisted Run index (SQLite or otherwise) — filesystem scan is enough at v1 scale.
- Live progress streaming beyond what stream-json already gives us in `agent.log`.
- Agent SDK integration.
- A playbook primitive distinct from skills.

## Open questions

- **Notion API auth.** When stage 5 lands, the Notion poller needs an integration token. Store where (1Password CLI, keychain, env var)? Out of scope for v1; flag for stage 5.
- **Trigger config validation feedback loop.** The user edits `triggers.edn`; the daemon hot-reloads and may surface a validation error. Today there's no proactive notification — the user only sees it on next TUI open. A `bb nido:trigger:validate :project <p>` would let users check their edits before relying on the overview. Worth adding in stage 1 if cheap.
- **Multiple runs from one event.** Today: one event from a broadcast source can fan out to multiple triggers. If two triggers both fire on the same event, do they share a payload (yes — they each get their own copy and template independently)? Confirmed in §"Event sources / Two event-flow patterns."
- **Run resumability after halt.** A `:halted` Run is terminal — to "resume," the user re-fires the trigger, creating a new Run. Is that the right call, or should `:halted` be resumable? Picking terminal because resuming would mean re-attaching to a possibly-corrupted claude session-id; a fresh Run is cleaner.

## Implementation order — short version

Stage 1 (first PR cycle):
1. Coordinator skeleton + `bb nido:coordinator:run` (foreground only).
2. `~/.nido/coordinator/` layout including queue dir + status heartbeat.
3. `:manual` source — i.e., the queue watcher.
4. Trigger schema + per-project `triggers.edn` hot-reload.
5. Run lifecycle states + `run.edn` persistence + session spawning with `:owned-by-run`.
6. Headless claude launch + stream-json parsing + `--resume` shim.
7. `bb nido:trigger:fire`, `bb nido:runs:list`, `bb nido:runs:show`.
8. Minimal TUI runs screen (header + 3 groups + enter action + fire-trigger modal).

Stage 2: brakes (budget, circuit breaker, anomaly auto-halt, kill switch).

Stage 3: background daemon harness, manual start.

Stage 4: launchd plist + install/uninstall tasks.

Stages 5+: Notion source, cron source, GitHub source, each its own design slice if non-trivial.
