# Promote a triaged ticket → autonomous planning leg

**Date:** 2026-06-05
**Status:** Design — approved for planning
**Depends on:** the coordination layer (Runs, executor, ticket ledger), the
Notion source, and the `triage-bug` HITL flow already shipped. Builds directly
on `docs/superpowers/specs/2026-06-04-triage-record-store-design.md`.

## Problem

The auto-triage pipeline is working: a `:triage-bug` Run spins up a `:lite`
session (read-only, no services), investigates a Notion ticket, writes a durable
report into the per-ticket ledger, and parks at `:awaiting-input` for the human
to review in chat. The human reads the brief, types `apply`, and the disposition
+ Notion writes land.

What's missing is the *next* rung: once a report has been read and accepted, how
do you turn that ticket into real implementation work — a session with a real
worktree, live app/db, and an agent that can actually build the fix?

## Goal

A second autonomous leg — **promote** — that takes a triaged ticket and spins up
a `:full` session in which an agent autonomously **gathers context and produces
an implementation plan**, then parks for human review. The autonomy is bounded
to planning; after the human vets the plan, they resume the warm session and
drive the implementation themselves. There is no end-to-end autonomous coding.

## Non-goals

- **No autonomous implementation.** The autonomous burst stops at "plan ready".
  Code gets written by the human driving the resumed session.
- **No chunked multi-leg ping-pong.** A single autonomous leg (planning), then
  the human takes the wheel. (Explicitly considered and rejected — see
  Alternatives.)
- **No auto-chain from `apply`.** Promotion is a separate, deliberate per-ticket
  decision, not a side effect of accepting the triage.
- The `plan-bug` skill's planning discipline is **brian-side content**, out of
  scope here. This spec owns the nido promotion *mechanism*; the skill is a
  named dependency built in the brian repo and mirrored into `nido/.claude/`
  alongside `triage-bug` (same pattern, same `harness.edn` machinery).

## Shape

The promote leg mirrors triage exactly, one rung up the ladder. The Run
lifecycle, the park-and-review-in-chat model, the resume shim, and the ledger
are all reused. Only three things change: it provisions `:full` instead of
`:lite`, it runs `:plan-bug` instead of `:triage-bug`, and it sits behind an
explicit promote gate.

```
triage Run (:lite, read-only)  ──park──►  you: `apply`     [GATE 1: settle triage]
                                                │
                                          you: `promote`    [GATE 2: spend planning budget]
                                                ▼
                                   nido flips Notion → "In progress"
                                                ▼
plan Run (:full, worktree + PG/JVM/app live) ──park──►  you review the plan in chat
                                                │
                                   you resume the warm session, DRIVE implementation
                                   (no further autonomous burst)
```

### Two gates

- **Gate 1 — `apply`** (existing): settles the triage. Disposition recorded,
  Notion writes for triage land. Status → `:triaged`.
- **Gate 2 — `promote`** (new): the deliberate decision to spend a planning
  budget on this ticket. Because tickets are fed from whole Notion views
  (teacher-bugs backlog, new-reports), most accepted triages should *not*
  auto-consume a planning run — promote is opt-in per ticket.

## Components

### 1. The promote gesture — two front doors, one mechanism

Both gestures resolve to the same act: emit a **direct-target envelope** into the
coordinator queue:

```clojure
{:target  {:project :brian :trigger :plan-bug}
 :payload {:id "BR-4659"
           :report-path "<abs path to latest ledger triage entry>"
           :notion-page-id "…" :url "…" :title "…"}}
```

`nido.coordinator.events/route-direct` already dispatches direct-target
envelopes by trigger *name alone* — no live source required. So no new routing
machinery is needed.

- **In-chat (primary).** While reviewing the parked triage, the human tells the
  agent `promote`. The `triage-bug` skill writes the envelope file into
  `~/.nido/coordinator/queue/` itself. This keeps the decision in the chat (per
  the "decisions live in the chat" principle); it is *not* the agent starting
  services — nido still owns the spawn. The skill must first read the ledger and
  refuse if the promote gate (below) is not `:promote`.
- **CLI (first-class).** `bb nido:ticket:promote :project <p> <BR-####>` — a
  standalone task for promoting a ticket later, outside any chat. It reads the
  ledger, applies the gate, and enqueues the same envelope. This is also what a
  future TUI `p` key will call.

Both paths share one `promote!` helper (gate-check + payload assembly + enqueue)
so they cannot drift.

### 2. The promote gate (ledger)

A sibling of `tickets/gate-decision`, reading ledger meta status:

```clojure
(defn promote-decision [project br-id]
  (case (tickets/status project br-id)
    :triaged                          :promote          ; the only promotable state
    (:planning)                       :skip-active      ; a plan Run already owns it
    (:skipped)                        :skip-completed   ; triage said don't bother
    nil                               :skip-no-record   ; never triaged
    :skip-untriaged))
```

Promote is allowed *only* from `:triaged`. On enqueue, status moves to
`:planning` (so a double-promote is refused). Both front doors call the gate;
the CLI prints the decision and exits non-zero on a skip.

### 3. The `:plan-bug` trigger (source-less)

Declared in `~/.nido/projects/brian/triggers.edn`:

```clojure
{:name            :plan-bug
 :skill           :plan-bug
 :session-profile :full
 ;; NO :source — fired only by promote. There is no autonomous fan-in;
 ;; this trigger is default-off by construction.
 :on-promote      {:notion-status "In progress"}
 :max-in-flight   3
 :limits          {:budget "45m" :max-failures 3}}
```

The absence of `:source` is the core safety property: a planning Run can only
exist because a human (or the agent on a human's instruction) explicitly
promoted a ticket. Nothing polls it into existence.

### 4. `:full` provisioning for the plan Run

The coordinator routes the envelope through the **unchanged** path:
`create-run!` → executor slot → `spawn-session-for-run!` → `agent/launch!`. The
only difference from a triage Run is `:session-profile :full`, which
`session-lifecycle/up!` already understands:

- worktree: real `:git-worktree` at `~/Code/brian-worktrees/<session>/` (vs the
  lite symlink-to-main),
- services: `:all` — PG + JVM + app boot, so the planning agent can exercise the
  live REPL/app/db while forming the plan. This is *why* we provision `:full` now
  rather than deferring services to drive-time: the plan is better when the
  planner can actually run things.

**Session identity is ticket-stable:** session-name `impl-<br-id>` (e.g.
`impl-BR-4659`), vs triage's `run-…-<hash>`. This makes re-promote idempotent and
the session discoverable by ticket.

### 5. Briefing the plan agent

- The triage report is read from the **durable ledger**
  (`~/.nido/projects/<p>/tickets/<BR>/entries/NNNN-triage.md`), so the lite
  triage session can be long gone. Its path rides in the envelope payload and is
  surfaced to the agent via `first-message`.
- A session-link to the Notion ticket is added (the mandatory, silent
  `session:link:add` convention).
- `first-message` ≈ "Plan the implementation of BR-4659. Triage report at
  <path>. Gather context, exercise the REPL/app, and produce an implementation
  plan. Do not modify code. Park at :awaiting-input when the plan is ready for
  review." The detailed discipline lives in the `plan-bug` skill.

The agent gathers context, may exercise live services, writes a plan, appends a
`:plan` ledger entry, sets `_run-status.edn {:phase :awaiting-input}`, and halts.
The Run parks at `:awaiting-review` exactly as triage does.

### 6. Notion status → "In progress"

nido owns this write (per the "nido owns cross-project integrations" principle),
at a **single chokepoint in the coordinator**: when the plan Run actually spawns
(in `run-blocking!` for a `:plan-bug` skill, just before/after
`spawn-session-for-run!`). Centralizing here means:

- both front doors are covered by one implementation, and
- "In progress" stays *honest* — it flips when work genuinely starts, not while
  the Run is still queued behind `:max-in-flight`.

The target status is config-driven (`:on-promote {:notion-status …}` on the
trigger, default `"In progress"`) so the mechanism stays generic across
projects/views. The write goes through the existing Notion integration
(2025-09-03 API: `patch-page` on the ticket's `:notion-page-id`, status property
on the data source). It is **best-effort**: a Notion failure logs a warning and
the planning Run proceeds — the status signal is valuable but must not strand the
work.

### 7. Ledger & visibility

- Status vocabulary gains `:planning`. The per-ticket history becomes
  **triage → plan → (human implementation)** — the append-only ledger the record
  store was designed for from day one.
- A `:kind :plan` ledger entry holds the plan document, written by the agent via
  the existing `tickets/append-entry!`.
- `on-run-terminal!` is extended so a `:plan-bug` Run reconciles ticket status
  the way triage does: parked `:awaiting-review` leaves `:planning` intact; an
  abnormal exit clears it back to `:triaged` (re-promotable).
- The plan Run shows up in `bb nido:runs:list` and the TUI natively — it is an
  ordinary Run.

## Safety

Inherited free from the Run infrastructure: wall-clock budget (SIGTERM→SIGKILL),
per-trigger circuit breaker, daemon anomaly auto-halt, kill switch
(`bb nido:halt`), and startup reconciliation of orphaned Runs.

New, specific to a code-capable session:

- **Default-off by construction.** The `:plan-bug` trigger has no `:source`, so
  it fires only on explicit promote. There is no autonomous fan-in.
- **Plan-only discipline.** The skill is instructed to plan, not edit code.
  Two-gate + park guarantees nothing lands without the human. (`--dangerously-
  skip-permissions` is on, as with triage, so this rests on skill discipline +
  the park, not a permission wall.)
- **Larger budget (45m).** Planning against live services takes longer than
  read-only triage; still bounded. This is the `:limits.budget` SIGTERM→SIGKILL
  cap on the **autonomous burst only** — it bounds how long the headless `claude`
  process may churn *before it parks*. It is **not** idle-suspension of the
  session (nido has none; sessions stay up until explicitly brought `down`), and
  it stops applying the moment the plan parks: the human's `claude --resume`
  drive phase is a fresh foreground process under no coordinator timer.

## Staging

- **Stage A (this spec).** Promote gate (`promote-decision`) + ledger
  `:planning` status; `promote!` helper; `bb nido:ticket:promote` task; in-chat
  promote envelope from the `triage-bug` skill; source-less `:plan-bug` trigger;
  `:full` provisioning for the plan Run (already supported); ledger `:plan`
  entry; Notion `:on-promote` status write at the spawn chokepoint;
  `on-run-terminal!` reconciliation for `:plan-bug`.
- **Dependency.** The brian-side `plan-bug` skill (parallel to `triage-bug`).
- **Later (not now).** TUI `p` promote key; auto-suggest `promote` after
  `apply`; the chunked-autonomous-leg variant.

## Alternatives considered

- **In-place upgrade of the lite triage session** (swap symlink→git-worktree,
  start services under a running agent). Rejected: mutates a running session
  across a structural boundary for no benefit, since the durable ledger report is
  a sufficient cold-start brief. A fresh `:full` session is a clean lifecycle.
- **One gate (auto-chain `apply`→implement).** Rejected: couples accepting a
  triage with spending a coding budget; wrong for view-fed backlogs.
- **Second autonomous leg implements end-to-end.** Rejected by the user: the
  autonomy must stop at a vetted plan; the human drives implementation.
- **Notion write in the gesture (task/agent) rather than the coordinator.**
  Rejected: would duplicate the write across both front doors and could flip
  "In progress" while the Run is still queued.

## Open questions

- Branch naming for the `:full` worktree (e.g. `nido/BR-4659` vs
  `fix/BR-4659-<slug>`) — defer to the implementation plan; follow brian's
  branch conventions.
- Whether a re-promote after an abnormal plan exit should append a fresh `:plan`
  entry or supersede the prior one — default: append (ledger is immutable).
