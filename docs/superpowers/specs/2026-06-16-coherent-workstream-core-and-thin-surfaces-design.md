# Coherent workstream core + thin surfaces — design

Date: 2026-06-16
Status: approved (brainstorm), pending implementation plan

## Problem

The TUI accumulated actions across three unrelated scopes (per-session lifecycle,
per-workstream levers, coordinator-global controls) jammed into one flat keymap.
It feels cluttered because it *is* incoherent — and the incoherence is not skin
deep. Two root causes:

1. **Noun soup.** Nido has at least four top-level nouns for "a piece of work":
   `workstream`, `ticket`, `run`, `session`. Some are the same thing seen from a
   different angle; some are genuinely distinct; nothing declares how they relate.
   Each surface (bb tasks, TUI, web dashboard) picks its own subset and naming.

2. **Two tangled planes.** Nido is really two systems wearing one coat — a
   **workspace plane** ("give me an isolated place to work": projects, sessions,
   pg, enter) and a **coordination plane** ("track and route units of intended
   work, some autonomous": workstreams, runs, sources, triggers, promote, halt,
   breakers). The TUI mixes both, plus three scopes, in a single screen.

A symptom of the drift: `CLAUDE.md` still documents "press `r` for the runs
surface" — a screen that no longer exists in the code.

The user's reframing: **nido core should expose one clean, coherent interface;
the TUI is a small wrapper over it, and so is the (soon-to-be-re-envisioned) web
interface.** This document defines that core and the model it speaks.

## The model

### One object: the Workstream

The workstream is the spine. Everything else is a facet of it. A workstream
belongs to a project and is:

- **origin** — `notion` · `github` · `slack` · `scratch` (how it entered)
- **stage** — its position on the single lifecycle (below)
- **sessions** — the episodes of work against it (below)
- **ledger** — its durable record: id, origin, stage, refs, and the reports each
  session appended. The ledger is what the workstream *is* when no session is
  live. (Kept as a named facet for now rather than folded into "the record".)

The former top-level nouns `ticket` and `run` dissolve into this: a "ticket" is
the workstream's ledger; a "run" is an autonomous session (see autonomy axis).

### One lifecycle, multiple entry points

There is a single canonical spine of stages:

```
  intake ──▶ triage ──▶ ready ──▶ in-progress ──▶ done
```

- A merged PR is the **event** that auto-advances `in-progress → done`; it is not
  a stage of its own.
- **Multiple entry points.** Ref-origins (notion/github/slack) enter at `intake`
  and ride the autonomous escalator (triage + HITL gate, merge → done). **Scratch
  enters at `in-progress`** — it skips intake/triage/ready. This replaces the
  current fork where ref-sourced workstreams are grouped by *stage* and scratch
  is grouped by *engagement* (Active/Idle). One state model, not two.
- The coordinator drives the autonomous transitions. A human only pulls a lever
  to move something by hand.

### The autonomy axis (sessions absorb runs)

A "run" was never a different noun — it was a session the coordinator started
that runs with little or no human input. The thing that varies is **autonomy**,
not kind:

```
  interactive ───────────────▶ guided / HITL ───────────────▶ autonomous
  you drive the chat           auto-starts, parks at a         runs to terminal,
  (scratch impl)               gate for your nod (triage)      no human input (impl burst)
```

So a workstream **has sessions**, each carrying:

- an **autonomy level** — `:interactive` · `:hitl` · `:autonomous` (derived from
  whether the session is coordinator/run-owned and whether it parks at a gate)
- a **status** — live / parked-at-gate / ended (done · failed · orphaned) for the
  autonomous end; up / down for the interactive end
- **brakes** — budget + circuit breaker — present **only** toward the autonomous
  end. Interactive sessions carry none.

This is a payoff, not just a tidy-up: it tells the **system surface** what it is
*for* — watching and braking the autonomous sessions. Halt, breakers, and budget
exist precisely to govern that right-hand end of the axis; the interactive end
needs none of them. The two planes now have a principled reason to be separate.

### The work verbs

With a single spine, every work action is either a lever that moves a workstream
along it, or a way into a facet. The whole work keymap is:

- **`open`** — drop into the workstream's session/chat. The only facet verb, and
  the one pressed all day.
- **`new` / `promote` / `done`** — three faces of one operation, **set-stage**:
  - `new` — create a workstream and place it at a target stage
  - `promote` — move an existing workstream to a target stage
  - `done` — place a workstream at the last stage
  - Each resolves its target via a **per-project default + interactive override**:
    the bare keypress uses the configured default (muscle memory); an override
    (e.g. capital `P`, or a stage-picker modal) lets you aim it in the moment.

Everything else the surfaces do today is **not** a work verb — it is system /
machine-health (next section).

## Two surfaces over one core

### The work surface (the board)

Workstreams for a project, grouped by the **single spine** (stage). Origin
(notion/github/slack/scratch) is a **badge on the row and an optional filter** —
*not* a separate screen. This is the most visible change from today's five
per-source tabs, and it is the direct consequence of "the stage is the spine":
the board is organized by where work *is*, not by where it *came from*.

The board carries only the work verbs (`open`, `new`, `promote`, `done`) plus
navigation (filter by origin, drill into a workstream's sessions/ledger). It is
the TUI's default screen and what the web board becomes.

### The system surface

A separate screen for machine-health, never mixed into the board:

- daemon health (coordinator reachable? slots, in-flight/queued)
- the autonomous-session governors: **halt/resume**, **clear breaker**
- sources health (notion/slack poll state, breakers)
- **fire** a manual trigger
- session plumbing — `up` / `down` / `destroy` / `worktree` / `reset` / `isolate`

The high-value, frequently-glanced items (daemon health, halt, breaker, fire,
sources, session plumbing) get a dedicated TUI system screen. The rarely-touched
infra (pg template/shared clusters, reclaim, migrate, harness:sync) stays
CLI-only — it is touched roughly once a month and does not earn a key.

The **workstream-detail** view is then read-only: it shows the workstream's
sessions (autonomy · status · brakes) and ledger, and `open` is its only verb.
Session plumbing (`up`/`down`/`destroy`/`reset`/`isolate`) is *not* a detail
action — it lives on the system surface, keyed by session. This keeps the work
plane (board + detail) carrying nothing but work verbs.

## Architecture — decision B: a real work-plane core

Both surfaces become **thin projections over a shared work-plane core**, a single
namespace (working name `nido.workstream`) that *is* the work vocabulary:

- **`list-workstreams project`** → workstreams along the single spine, each with
  origin, stage, a sessions summary (with autonomy + status + brakes), a ledger
  summary, refs, title, last-activity. Computes the one-lifecycle grouping
  (including scratch-as-`in-progress`) here, once. Replaces the row-building and
  the stage-vs-engagement fork now split across `workstreams-view`.
- **`workstream project ws-id`** → one workstream with full facets (its sessions,
  its ledger).
- **`set-stage project ws-id target`** → the single mutation behind
  `new`/`promote`/`done`. Routes through the existing `workstream` /`promote`
  /`scratch` machinery underneath.
- **`default-target project action`** → resolve the per-project default target
  stage for `promote` / `new` (config-driven), so a surface can offer the
  one-keypress path and the override path.
- **`open-target project ws-id`** → resolve which session/chat `open` lands in and
  return enough for the surface to route (Warp tab spawn vs `cd` handoff).
- **`stages`** → the canonical ordered spine `[:intake :triage :ready
  :in-progress :done]`.

The TUI and the web call **only this** for work. They render rows and route
keypresses; neither holds model logic. The core consolidates today's scattered
`workstreams-view` / `workstream` / `promote` / `scratch` / `tickets` logic behind
that vocabulary.

**The system plane stays thin (rejecting option C).** Daemon control, brakes,
sources, triggers, pg, session plumbing keep their current direct CLI/namespace
calls. There is no second consumer for a system facade yet, and building one now
is a speculative primitive — promote it later when the web actually needs it.

### Projection over storage (no migration up front)

The unified model is, first, a **view-layer** unification over today's storage:

- the single lifecycle (incl. scratch-as-`in-progress`),
- runs presented as autonomous sessions,
- the ledger as a facet,

are all computed by the core as it *reads* existing on-disk records — not a
storage rewrite. This keeps the branch shippable and decouples the coherence win
from any migration. Storage cleanup (e.g. actually merging the runs and sessions
records) can follow lazily, once the projection has proven the model.

## Slicing the work

This is more than one spec. Decomposition:

### Sub-project 1 (now): the work core + the TUI as its first projection

Build `nido.workstream` **and** rebuild the TUI as a thin client of it, together,
so the API is shaped by a real consumer (an API with no client drifts). Scope:

- the `nido.workstream` core API above, as a projection over current storage;
- the TUI work board: one stage-grouped list, origin as badge + filter, the four
  work verbs with default+override set-stage;
- relegation of system levers to a separate TUI system surface (daemon
  health, halt/resume, clear breaker, fire, sources), with infra left CLI-only;
- delete the now-defunct per-source tabs, the Sessions/ops keymap on the board,
  and reconcile `CLAUDE.md` (the stale `r`-runs-surface line, the keymaps).

### Sub-project 2 (later): the web, re-envisioned as the second projection

The web dashboard re-built on the *same* `nido.workstream` core. This is the
reason to do the core properly now rather than TUI-only: the web rework is
imminent, so the core should exist *before* it — not be reverse-engineered out of
two divergent UIs afterward.

## Action mapping (current → new)

| Today | Fate |
|---|---|
| Projects screen `↵` open / `q` quit | unchanged (project is the top scope) |
| Board: 5 per-source tabs | one stage-grouped board; origin = badge + filter |
| Board Sessions/ops `u`/`d`/`x`/`w` | → system surface (session plumbing) |
| Board Sessions/ops `i` info | → folds into workstream detail |
| Board Sessions/ops `a` add | → `new` verb (set-stage create) |
| Board Scratch `a` add | → `new` verb |
| Board Notion/GitHub/Slack `p` promote | → `promote` verb (default + override) |
| Board `d` done | → `done` verb |
| Board `f` fire / `h` halt / `c` clear-breaker | → system surface |
| Workstream detail `↵` open | unchanged; detail now shows sessions (with autonomy) + ledger |

## Non-goals / deferred

- A system/ops core facade (option C) — deferred until the web needs it.
- Any storage migration — the model ships as a projection first.
- The web re-build itself — sub-project 2, later.
- Changing the coordinator's autonomous behavior (triage, merge poll, brakes) —
  this work re-presents it, it does not re-mechanize it.

## Risks / open questions

- **Stage board vs per-origin tabs** is the most visible UX change; validate it
  feels right early in the TUI plan (it follows directly from "stage is the
  spine", but it is worth seeing on screen).
- **Autonomy derivation** — `:interactive` / `:hitl` / `:autonomous` must be
  derivable from existing session/run records; confirm the signal exists (run
  ownership + presence of a HITL gate) before relying on it in the core.
- **Per-project default target config** — needs a home (likely the project's
  trigger/config edn); define the key in the plan.
- **`open-target` for multi-session workstreams** — a workstream may have several
  sessions over its life; define which one `open` lands in (most-recent live, else
  most-recent).
```
