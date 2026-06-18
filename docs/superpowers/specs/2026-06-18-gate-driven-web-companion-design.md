# Gate-driven web companion — design

**Date:** 2026-06-18
**Status:** approved (brainstorm), spec under review
**Sub-project:** #2 of the coherent-workstream-core arc — the **second projection** over `nido.work` (the first being the TUI). See [[2026-06-16-coherent-workstream-core-and-thin-surfaces-design.md]].

## Goal

Re-envision the nido web dashboard as a **companion with targeted depth** over the existing `nido.work` core: a cross-project surface that *reflects* the workstream spine and lets you *act on gates* — points where an autonomous session has parked, produced a report, and is waiting on a human decision — without leaving the browser. Anything needing real back-and-forth still routes to the terminal.

## Context: where the web is today

The current dashboard (`src/nido/ui/{server,views,discovery}.clj`) speaks the **old, session-centric** vocabulary — it never met `nido.work`:

- `/` — a flat, cross-project, live-first board of every *session* (project · session · status · dev-url).
- `/:project/sessions` — per-project session table with start/stop/restart, ports, RSS.
- `/:project/sessions/:name/logs/:service` — log tailing (repl/eval/pg).
- `/:project/vsdd/…` — the VSDD run dashboard (a separate subsystem).

Rendering is httpkit + hiccup2 + Datastar SSE (3s polling fragments), run in-process by the coordinator daemon at `:8800`.

## The model: a gate

A **gate** is a workstream that wants you *right now*. The predicate already exists: `nido.coordinator.session/stage-projection` returns `{:stage … :needs-you <bool>}`, and `stage-needs-you` already encodes the rule:

- **`:ready`** always wants you (decide promote/drop),
- **`:triage` / `:in-progress`** want you only when a session is **parked at the gate** (`engagement :parked-at-gate`),
- **`:done`** never.

`nido.coordinator.workstreams-view/workstream-row` already surfaces `:needs-you` and `:engagement` on every row, and `sort-rows` already orders needs-you-first. So `nido.work/list-workstreams` rows **already carry the gate predicate** — the gate facet only adds the two things the human needs: a **report** and **follow-actions**.

### Report

The workstream-level ledger (`nido.coordinator.workstream/append-entry!` + the record's `:entries`) holds immutable markdown entries (`{:kind :seq :at :file}`, `:file` → markdown under `entries/`). It is keyed by **ws-id**, so it works for *every* origin including ref-less scratch. The triage skill already dual-writes to it.

A gate's **report** is the workstream's most recent ledger entry:

```clojure
{:kind     <keyword>   ; e.g. :triage, :plan, :note
 :at       <iso-8601>
 :title    <string?>   ; first heading if present, else nil
 :markdown <string>}   ; the entry file's contents
```

### Follow-actions

An **action** is what the human can do at the gate:

```clojure
{:id    <keyword>           ; :promote :skip :drop :done :reply — also the resolve dispatch key
 :label <string>            ; "Promote", "Skip", …
 :kind  :mutation | :reply} ; render hint only: button vs textarea
```

`resolve-gate!` dispatches on **`:id`** (a small known set) — this sidesteps a target-vs-outcome modeling tension (Skip/Drop close with a *disposition*, not a stage):

| `:id` | resolves to |
|-------|-------------|
| `:promote` | `set-stage!` → `:in-progress` (the promote gesture) |
| `:skip` | `close!` → `:dropped` (not-a-bug; workstream settled — the finer ticket-level `:skipped` disposition is the triage skill's concern) |
| `:drop` | `close!` → `:dropped` |
| `:done` | `set-stage!` → `:done` (i.e. `close! :done`) |
| `:reply` | `resume!` with the human's input |

> **Workstream close outcomes are only `:done | :dropped`** (the `Workstream` schema's `:closed` enum). `:skipped` is a *ticket-level* status, not a workstream outcome — so Skip and Drop both settle the workstream `:dropped`; they differ only in label/stage context.

`:kind` is **only** a render hint — `:mutation` → one-click button (optimistic reflect); `:reply` → textarea + "Send & resume". The two resolver families are the formalization:

- **mutations** (`:promote`/`:skip`/`:drop`/`:done`) — pure state changes via `nido.work/set-stage!` / `close!` (we already have these). Headless, instant-ish.
- **reply** — freeform human input delivered to the parked agent via **resume-with-input** (see runtime capability below).

The action set is **derived from stage** (+ whether a session is parked) — the simplest clean version, no skill-declared action protocol (add that only if a gate ever needs choices the stage can't imply — YAGNI):

| Stage | Parked? | Actions |
|-------|---------|---------|
| `:intake` | — | none (reflect only) |
| `:triage` | parked | **Promote** (→ `:in-progress`) · **Skip** (→ `close! :dropped`) · **Reply** |
| `:ready` | always needs-you | **Promote** (→ `:in-progress`) · **Drop** (→ `close! :dropped`) |
| `:in-progress` | parked | **Reply** · **Done** (→ `close! :done`) |
| `:done` | — | none |

### Gate

```clojure
{:ws-id   <string>
 :project <string>
 :origin  :notion | :github | :slack | :scratch
 :stage   <spine-stage>
 :label   <string>
 :report  <report-or-nil>
 :actions [<action> …]
 :session <parked-session-name-or-nil>}  ; the session a :reply resumes
```

## Execution-model decision (recorded)

The gate's `:reply` needs to deliver input into a parked agent. We evaluated two execution models:

- **A — headless bursts + resume (today).** `claude --print`, stdin closed, blocks → exits; park = process gone after writing its report; un-park = re-launch `claude --resume <session-id> -p "<input>"`.
- **B — persistent sessions + Claude Code channels.** Long-lived `claude --channels …`; un-park = push into the live session via an MCP channel server.

**Decision: A.** Mid-work steering (the only thing B uniquely enables) is explicitly **not a need now or foreseeably**. B's costs cut against nido's DNA: a resident claude+MCP process per parked gate (this is the project that built `:shared` PG to avoid 27 GB clones); the safety brakes (per-run budget, breaker, kill-switch, anomaly-halt) all assume *bounded runs*, not session lifetimes; long-lived agents are the orphan/liveness problem the watchdog exists to fight; and channels is **research preview** (protocol "may change", auth-gated, custom plugin is a Bun script). Model A is GA, free-while-parked, and drops into the coordinator we already have.

The gate **resolver is kept pluggable** so channels could later become one opt-in "attach & live-steer this session" resolver kind — but that is out of scope here.

## Architecture

**Core-first, again.** The gate is a new **facet in `nido.work`** (surface-agnostic). The web is a thin projection over it. Exactly **one new runtime capability** is introduced: resume-with-input. Ships as a **projection over today's storage — no migration.**

Build order:
1. **Resume capability** (coordinator/agent) — the load-bearing new piece.
2. **Gate facet** in `nido.work`.
3. **Web surface** rebuild (`nido.ui`).

### 1. Resume capability (coordinator)

`nido.coordinator.agent/build-cmd` already wires `--session-id <uuid>`; add `--resume <session-id>` support. A new entry point — `resume!` (in the coordinator spawn/agent layer, exposed to `nido.work`):

> Given a workstream's **parked** session, read its recorded `:claude-session-id`, and re-launch `claude --resume <id> -p "<input>"` in the session's worktree as a **fresh bounded run** through the existing spawn/executor path. The agent continues its own transcript, runs one turn, parks or finishes, and is observed/reconciled exactly as the original burst was. The new park writes the next report entry.

Nothing about the safety model changes — a reply is one more bounded turn (existing budget/breaker apply). If the session has no recorded `:claude-session-id` (or isn't parked), `resume!` returns an error the web surfaces; the human falls back to the terminal.

### 2. Gate facet (`nido.work`)

New public fns (alongside the existing `stages`/`classify-origin`/`list-workstreams`/`grouped`/`workstream`/`default-target`/`set-stage!`/`new!`/`open-target`):

```clojure
(gate-actions [stage parked?])          ; pure → [action …]   (the table above)
(gates        [project])                ; needs-you rows → [gate …], hydrated
(all-gates    [])                       ; cross-project merge, needs-you-first
(gate         [project ws-id])          ; full gate detail, or nil
(resolve-gate! [project ws-id action-id input?])  ; dispatch by action :kind
```

- `gates` filters `list-workstreams` rows on `:needs-you`, then hydrates each with `(latest-report project ws-id)` + `(gate-actions stage parked?)` + the parked session name.
- `latest-report` reads the workstream record's last `:entries` element and slurps its `:file`.
- `resolve-gate!` dispatches on action `:id` (see the table above): `:promote` → `set-stage! :in-progress`; `:skip`/`:drop`/`:done` → `close!` with the disposition; `:reply` → `resume!`.
- `all-gates` reuses `project/list-projects`, maps `gates`, concatenates, and re-sorts needs-you-first (mirrors `all-session-rows`).

### 3. Web surface (`nido.ui` rebuild)

Same stack (httpkit + hiccup2 + Datastar SSE, in-process at `:8800`); **data comes only from `nido.work`.**

**Home = the Gate Inbox (primary), cross-project.** A master-detail (validated mock):
- **Left — inbox list:** needs-you workstreams across all projects, sorted by urgency. Card = origin badge (`N`/`G`/`S`/`·`) · label · needs-you dot · project + stage chip + park reason · one-line report preview.
- **Right — gate pane:** the report **rendered as markdown**, then the action row. **Promote/Skip/Drop/Done** POST a mutation with optimistic "working…" reflect (the existing start-button pattern). **Reply** opens a textarea → POSTs `{input}` → `resume!` → reflects "resuming…". A **route-in** link ("open session ↗") to the live session's friendly-host URL.

**Board (secondary tab)** — the stage-grouped spine, reflect-only, origin badge + filter, route-in. Reuses `nido.work/grouped`.

**Workstream detail** — read-only (origin · stage · ledger · sessions on the autonomy axis), plus route-in + the existing **start/stop** lifecycle + **logs**.

The current flat session-ops board moves *under* detail / a system view; the new home leads with gates. **VSDD views are untouched.**

### Routing (new/changed)

- `GET /` → Gate Inbox page (`all-gates`).
- `GET /_fragment/gates` → SSE inbox refresh.
- `GET /gate/:project/:ws-id` → gate pane (full page or fragment).
- `POST /gate/:project/:ws-id/:action` → `resolve-gate!` (body carries `input` for `:reply`); responds with the refreshed pane/inbox fragment + optimistic state.
- `GET /board` → spine board page; `GET /_fragment/board` → SSE refresh.
- Existing `/:project/sessions…`, `/:project/sessions/:name/logs/…`, `/:project/vsdd/…` retained (sessions board relocated from `/` to a system/ops view).

## Data flow

**Read (inbox):** `all-gates` → per-project `gates` → `list-workstreams` (rows w/ `:needs-you`) → hydrate report (slurp latest entry) + actions (pure). Datastar polls `/_fragment/gates` every 3s.

**Resolve (mutation):** `POST /gate/.../promote` → set optimistic state in the server-side atom → `future` runs `resolve-gate!` (`set-stage!`) → respond with refreshed inbox fragment → next poll reflects the new spine state (the promoted ws leaves the inbox; an impl session appears).

**Resolve (reply):** `POST /gate/.../reply {input}` → optimistic "resuming…" → `future` runs `resolve-gate!` (`resume!`) → the bounded turn runs in the coordinator; the gate shows the session as `:running`, then re-parks with a new report (or advances). Polling reflects each transition.

## Error handling

- **Optimistic + sticky state atom** — reuse the existing `app-states` pattern (`:starting`/`:failed` with an error message), generalized to gate resolutions (`:promoting`/`:resuming`/`:failed`). A failed `resolve-gate!` surfaces the error under the gate with a link to the session's eval/agent log.
- **`resume!` preconditions** — no `:claude-session-id` or not-parked → a clear "can't resume from here — open in terminal" message, not a crash.
- **Markdown rendering** — reports are agent-written markdown. v1 renders with a lightweight clj converter on the bb classpath (library choice resolved in the plan); raw `<pre>` is the acceptable fallback if a clean bb-compatible lib isn't available. Not a blocker.
- **Unreadable project / missing entry** — a project that can't be read contributes no gates (mirrors `all-session-rows`'s per-project try/catch); a gate with no ledger entry renders with an empty report + its actions.

## Testing

- **`nido.work` gate facet** (pure where possible, `with-tmp` fixture like `work_test.clj`): `gate-actions` table per stage/parked; `gates` filters on needs-you and hydrates report+actions; `resolve-gate!` dispatches mutation vs reply (with-redefs on `set-stage!`/`resume!`); `all-gates` cross-project merge + ordering; nil/absent cases.
- **Resume capability**: `build-cmd` includes `--resume`; `resume!` reads the parked session's `:claude-session-id` and spawns a bounded run (with-redefs on the agent launcher); precondition failures return errors.
- **Web**: handler routing for the new paths; the inbox/pane/board fragments render from injected `nido.work` data (pure view fns, hermetic); POST resolve sets optimistic state and dispatches on a background future (redef the resolver). Mirror `tui_test.clj`'s hermetic style.

## Non-goals (explicit)

- No Claude Code **channels**, no **persistent sessions**, no **mid-work steering**.
- **VSDD views unchanged.**
- No full work-verb parity — **creating new workstreams stays in the TUI/CLI** (the web reflects + acts on existing gates; it is not a second cockpit).
- Any decision needing real back-and-forth → **Reply once, or open in terminal.**
- No storage migration — pure projection over today's records.

## Open decisions (for the plan)

- **Markdown library** — pick a bb-compatible pure-clj renderer (e.g. evaluate `markdown-clj` / `nextjournal/markdown` on the babashka classpath), else `<pre>` fallback.
- **Gate pane: page vs fragment-only** — whether the pane is a deep-linkable page (`/gate/:project/:ws-id`) or only an in-place fragment of the inbox. Lean: deep-linkable page that also renders as the master-detail right side.
