# Live-path cutover to workstreams/sessions — scoping & design

**Date:** 2026-06-08
**Status:** Scoping, pre-implementation
**Approach:** Strangler (A) — session is the authoritative model/surface record; the legacy `run` survives as the internal execution key. Triage dual-writes (legacy ticket + workstream ledger) during the transition.

## Problem

The TUI surface was migrated to the workstream/session model (Runs and Tickets screens replaced by a Workstreams surface) ahead of the *producer*. The live coordinator path still writes the **old** model:

- spawn: `core.clj:311,332` → `runs/create-run!` → `~/.nido/runs/*/run.edn`
- triage: writes `~/.nido/projects/*/tickets/BR-####/meta.edn` via `bb nido:ticket:*`
- `workstream/create!` / `session/create!` have **no live callers** — only the one-shot `bb nido:migrate`.

Result: live triage produces runs+tickets the new TUI cannot display; the Workstreams surface only shows whatever `bb nido:migrate` backfilled (a frozen snapshot). Until the producer is cut over, autonomous triage should stay paused (see [[project_triage_safety_model]]).

## Goal

Make the **live** path create and drive workstreams + sessions, so the running queue lights up the new TUI naturally — with minimum risk and without rewriting the proven execution machinery. Honor the unification intent ("the old Run, demoted to a field") incrementally: the run stops being a *surface*, which is the part that mattered; full dissolution of `run.edn` is deferred.

Non-goals (this cutover): retiring `run.edn` or the ticket store; re-keying the watchdog/agent.log/artifacts machinery onto session identity; reactivating `:triage-new`; any TUI change (the surface is already done).

## Approach (strangler)

The **session** (with its `:autonomy` facet) becomes the authoritative record for everything the model/surface reads — engagement, phase, weight, stage. The **run** remains the execution bookkeeping record that the existing machinery (executor, budget watchdog, agent.log, session-home symlink, artifacts, `runs-clean`, reconciliation) operates on unchanged. The run carries a pointer back to its authoritative session. State flows run → session via a single mirroring hook.

### 1. Spawn: find-or-create workstream + create session, then run

At the spawn site (`core.clj` ~311/332, both the dry-run and live arms), before/around `runs/create-run!`:

1. **Workstream find-or-create.** Derive an external ref from the routed event payload (for `:notion-view` sources: `{:adapter :notion :id <BR-id> :page-id :url :title}`). If `(workstream/find-by-ref project :notion <BR-id>)` returns a workstream, reuse it; else `(workstream/create! project {:stage <initial-stage> :external-refs [ref]})`.
   - **No external ref** (e.g. a manual `bb nido:trigger:fire` with no Notion row): always create a fresh workstream with `:external-refs []`.
   - **Initial stage default:** `:triaging` for triage triggers; otherwise a generic `:intake`. Make it a per-trigger config field (`:workstream-stage`) with a default, so triggers can name their stage.
2. **Session create.** `(session/create! project ws-id {:name <session-name> :weight <:light|:heavy from trigger profile> :autonomy <autonomy-map>})`. The autonomy map carries exactly what the run carries today (`:skill :trigger :first-message :agent :claude-session-id :limits :priority :uncapped? :on-promote`) plus `:phase :queued` and an initial `:phase-history`.
3. **Run create + linkage.** `runs/create-run!` as today, but the run record gains two fields: `:workstream-id` and (already present) `:session-name`. These are the run→session pointer the mirroring hook uses.
4. **Submit** unchanged: `executor/submit!` still takes the `run-id`.

Concurrency note: two triggers firing on the same Notion ref near-simultaneously must not create duplicate workstreams. `find-by-ref` + `create!` is racy under parallel spawns. Mitigate with `add-ref!`-style dedup or a per-project spawn lock at the find-or-create step (the executor already serializes submits; confirm the find-or-create runs inside that serialized section).

### 2. Phase mirroring: one hook in `runs/transition!`

`runs/transition!` is the single place run state changes. Extend it so that after persisting the new run state it loads `(:workstream-id run)` + `(:session-name run)` and calls `session/set-phase!` with the mapped phase. **Reuse the run-state→phase mapping already written in `migrate.clj`** (canonical transform) rather than duplicating it. Sketch of the mapping (confirm against `migrate.clj`):

| run state | session phase |
|---|---|
| queued | queued |
| running | running |
| awaiting-review | parked |
| done | done |
| failed | failed |
| halted | halted |
| dry-run-would-fire | (session not spawned for dry-run — see below) |

Because every run terminal/transition flows through `transition!`, reconciliation comes along for free: `reconcile.clj` already forces orphaned non-terminal runs to terminal via `transition!`, so the same hook mirrors the session to `:failed`/`:halted` on restart. Add a session-level reconciliation pass only if a session can exist without a run (it can't in the strangler — session is always created alongside a run).

Dry-run: the dry-run arm (`core.clj:311`) marks `:dry-run-would-fire` without spawning an agent. Decision: in dry-run, **still create the workstream+session** (so the surface shows the would-fire) but leave the session phase at `:queued`/a dedicated non-running phase, or **skip session creation entirely** for dry-run. Recommend skipping for dry-run to avoid phantom sessions; revisit if the TUI wants to show would-fire.

### 3. Gating swap

Replace the executor's per-trigger capacity check source from `runs/in-progress-count-by-trigger` to the already-built `session/in-flight-by-trigger` (`session.clj:142`, counts live autonomous sessions in `#{:preprocessing :running}`). **Correctness check:** confirm the phase set counted matches today's run-based gating semantics — specifically that a submitted-but-not-yet-running item is counted/uncounted consistently between the two implementations, so max-in-flight isn't loosened or tightened by the swap. Document any intentional difference.

### 4. Triage dual-write

The `triage-bug` skill currently writes only the legacy ticket (`bb nido:ticket:open` / append-entry / status). During the transition it **dual-writes**:

- keep the existing `bb nido:ticket:*` calls (legacy ticket store stays populated), AND
- append the triage finding to the workstream ledger and advance its stage.

This requires nido to expose workstream-ledger commands mirroring the ticket ones — e.g. `bb nido:workstream:entry:add`, `bb nido:workstream:stage:advance`, `bb nido:workstream:close` — thin wrappers over `workstream/append-entry!` / `advance-stage!` / `close!`. The skill resolves its workstream either from a ws-id passed in the session-home briefing (preferred) or via `find-by-ref :notion <BR-id>`.

Skill edits land in the brian harness (the triage-bug skill is brian-owned, mirrored into nido per `harness.edn`); nido owns the new `workstream:*` commands. See [[project_triage_record_store]] — the workstream **is** the per-ticket ledger the redesign called for; design the entry shape for that ledger now.

### 5. Session-home briefing carries the workstream-id

The launcher that writes the session home should include the `workstream-id` (and BR-id) in the briefing/`CLAUDE.md`, so the in-session agent, the dual-write step, and `/continue-ticket` (see [[project_promote_planning_leg]]) can target the workstream ledger without re-deriving it.

## What stays unchanged (explicitly)

Execution machinery keyed on run-id is **not** touched: budget watchdog (SIGTERM→SIGKILL), `run-agent-log`, `run-session-home-link`, artifacts dir, `runs-clean`, queue envelope routing. `run.edn` remains the execution record. This is the strangler boundary.

## What's deferred (named, for later)

- Retiring `run.edn` and the legacy ticket store (single-write only to workstream/session), and re-keying the execution machinery onto session identity — the full dissolution.
- Stopping the triage dual-write once the workstream ledger is trusted (drop the `bb nido:ticket:*` leg).
- Reactivating `:triage-new` (currently noted off: "teacher-bugs only").
- A TUI affordance to show dry-run would-fire, if wanted.

Per [[feedback_dormant_extension_points]], annotate the `run.edn` linkage fields and the dual-write as transitional, with the dissolution as the reactivation/removal trigger.

## Touch points (files)

- `src/nido/coordinator/core.clj` (~311, ~332) — spawn arms: insert workstream find-or-create + session create; pass ws-id into `create-run!`.
- `src/nido/coordinator/runs.clj` — `create-run!` accepts/persists `:workstream-id`; `transition!` mirrors run-state → `session/set-phase!`.
- `src/nido/coordinator/executor.clj` — gating source swap to `session/in-flight-by-trigger`.
- `src/nido/coordinator/migrate.clj` — extract the run-state→phase map into a shared fn both migrate and `transition!` call (no duplication).
- `src/nido/coordinator/reconcile.clj` — verify the `transition!` hook covers session mirroring on restart; add session pass only if needed.
- `src/tasks/nido_workstream.clj` (new) + `bb.edn` tasks — `workstream:entry:add` / `stage:advance` / `close`.
- Launcher (session-home writer) — add `workstream-id`/BR-id to the briefing.
- brian harness: `triage-bug` skill — dual-write the workstream ledger.

## Suggested commit sequence (strangler-safe; each step leaves the system runnable)

1. Shared run-state→phase mapping fn extracted in `migrate.clj` (pure; tested).
2. `create-run!` carries `:workstream-id`; spawn site does find-or-create workstream + create session (run still authoritative for execution). At this point sessions appear in the TUI but phase may lag.
3. `transition!` mirrors run-state → session phase (TUI phases now track live).
4. Gating swap to `session/in-flight-by-trigger` (+ correctness check).
5. nido `workstream:*` ledger commands.
6. Triage skill dual-write + session-home briefing ws-id.
7. Re-enable triage and observe the new TUI light up end-to-end.

## Open questions for review

- **Initial workstream stage taxonomy** — is `:triaging` / `:intake` the right vocabulary, or is there an established stage set to reuse?
- **Dry-run** — skip session creation (recommended) or create a parked/queued session to surface would-fire?
- **Gating** — is any intentional difference acceptable between run-based and session-based in-flight counting, or must they be exactly equivalent?
