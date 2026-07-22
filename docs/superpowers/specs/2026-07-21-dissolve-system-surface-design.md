# Dissolve the workstream/system split

**Date:** 2026-07-21
**Status:** Approved

## Problem

Nido's UIs carry two co-equal planes: the work surfaces (web `/` Needs-you +
`/workstreams`, TUI board) rendering the workstream spine, and a **system
surface** (web `/system`, TUI `s` screen) rendering a flat filesystem scan of
sessions — lifecycle buttons, ports, RSS, daemon banner, coordinator levers.
Most of the system surface's content is now duplicated per-workstream (the
detail pane already lists sessions on the autonomy axis), so the split clutters
the mental model without paying rent.

Three residues of the system surface are genuinely not workstream-shaped and
need homes before it can dissolve:

1. **Orphan visibility** — live sessions with no workstream record.
2. **Ops levers** — daemon health/halt, breakers, fire-trigger (global, not
   per-workstream).
3. **Leftover resources** — a closed (`:done`/`:dropped`) workstream's session
   keeps running (close! only stamps `:closed`); done workstreams are hidden
   from the board, so today only `/system` reveals the RAM-eater.

## Decision summary

- **Model**: durable adoption — every **live** session belongs to an open
  workstream. The daemon reconcile tick adopts live orphans into scratch
  workstreams (the existing one-off fold; no new nouns). Dormant record-less
  worktrees are *not* board content — they cost only disk; `bb nido:reclaim`
  remains their hygiene tool.
- **UI scope**: web **and** TUI in one arc.
- **Ops levers**: ambient chrome — the rail health dot expands into an ops
  panel (web) / an ops overlay (TUI). No third destination survives.
- **Leftovers**: a trailing **winding-down** band on the Active tab — closed
  workstreams still holding live sessions, with a one-click bring-down.

## 1. Model: the adoption invariant

Invariant: *every live session is reachable from an open workstream.*

Enforced by the daemon reconcile tick (same cadence as orphan-dir reclaim):

- Scan live sessions (registry × TCP check).
- Diff against coordinator session records under **open** workstreams.
- `scratch/birth!`-adopt any live orphan: label = session name, origin
  `:scratch`, stage `:in-progress` via the existing scratch fold.
- Adoption is idempotent and logged.

Closed-ws live sessions are **not** re-adopted — they render as winding-down
rows (§3). Dormant record-less worktrees: not adopted, not rendered.

## 2. Web UI: two destinations, ambient ops

The rail shrinks to **Needs you** and **Workstreams**.

- `/system` and `GET /_fragment/system` go away as a surface. `GET /system`
  returns a redirect to `/workstreams` (bookmarks/muscle memory don't 404).
- The lifecycle POST route re-homes:
  `/system/:project/:name/:action` →
  `/workstreams/:project/:ws-id/sessions/:session/lifecycle/:action`.
  `run-action!` and the `dev/set-app-state!` optimistic plumbing are reused
  as-is, just routed differently.
- **Workstream pane session rows absorb the system row.** Each session row in
  the detail pane (rendered via `dev/ws-session-dev-states`) grows the machine
  facts formerly exclusive to `/system`: state badge (running/starting/failed +
  error message), dev URL link, pg/repl/app ports, RSS + heap-max, and
  start/stop/restart buttons.
- **Ops panel.** The rail health dot becomes a button. Clicking expands a
  small Datastar-patched panel: daemon state + heartbeat, halt/resume, open
  breakers with per-source clear, and a fire-trigger form (project + trigger
  name). Chrome, not a route.

## 3. The winding-down band

`work/tab-bands` for `:active` gains a trailing band `:winding-down`: closed
workstreams (`:done`/`:dropped`) still holding ≥1 live session.

- Derived by threading the live-names set against closed workstreams.
- Rows show label, outcome, total RSS, and one action — **Bring down** (downs
  all live sessions; the row disappears on the next poll).
- Never gates: `needs-you` false, excluded from the badge count, rendered
  collapsed/muted so the Active tab stays about work.
- Union guarantee updates: every workstream row is reachable from exactly one
  tab **or** is closed-without-resources (correctly invisible). The
  `tab-bands-union` test oracle extends to cover this.

## 4. TUI

Dissolves symmetrically:

- Session plumbing keys (`u`/`d`/`x` up/down/destroy, `e`/`w` enter) move onto
  the workstream detail screen's session rows (`i` drill-in already lists
  sessions).
- The winding-down band appears at the bottom of the board's Active tab with
  the same bring-down action.
- Ops levers (`h` halt, `c` clear breaker, `f` fire) move to an ops overlay
  opened from the board — still the `s` key, new shape: a status/lever panel
  instead of a session table.
- Pickup (a text-input over `pickup/pickup!`) moves into the ops overlay's `p` —
  the board's `p` is already promote. (The web keeps its pickup bar.)
- The `:system` screen is deleted.

## 5. Derivation & data flow

`work/screen` stays the single pure derivation; it grows the winding-down
input.

- The scan moves into `nido.work` as `machine-rows`/`all-machine-rows` plus the
  `live-session-names` oracle (relocated from the TUI) — web, TUI, and the
  reconcile adopter read the same functions. `ui.server`'s own scan carries
  UI-optimistic state work must not know about, so it is not delegated to — it
  is deleted along with the `/system` surface.
- `work/all-grouped` threads `live-names` per project (already accepted; the
  web currently passes nil) so engagement is liveness-aware everywhere.
- New `work/winding-down`: project → closed-ws rows with live sessions + RSS.
  Injected into `screen` via the existing `data` map alongside
  `:groups`/`:gates`.
- The adopter is `work/adopt-orphans!` — the pure diff is exposed separately
  (`orphan-live-sessions`) for testability; the side-effecting wrapper is
  called from the reconcile tick.

## 6. Edge cases & failure handling

- **Adoption races**: the liveness oracle keys on registry port presence, which
  is written only post-boot, so a mid-boot session cannot be seen early. No
  grace window needed (unlike dir reclaim). (TCP probing stays a machine-facts
  display concern, not an adoption signal — ports flap during boot.)
- **Idempotency**: the adopter checks for an existing open workstream owning
  the session name before birthing; double-adoption is impossible.
- **Adopted-then-claimed**: if a session later gains a real owner (e.g.
  `/continue-ticket` associates it), the scratch ws yields — the adopter's
  next pass closes a scratch ws whose session is owned by another open
  workstream. One rule: a session has exactly one open owner; the newest real
  owner wins.
- **Wedged-model debugging**: CLI `session:status` / `session:list` remain
  scan-based and model-independent — the documented escape hatch (note in
  CLAUDE.md).
- **Bring-down failures**: reuses `run-action!`'s error path; a failed down
  shows the red badge + error on the row, and the row persists while the
  session is live — honest.

## 7. Testing

- `work_test`: adopter diff (orphan detection, idempotency, scratch-yields),
  winding-down derivation (closed+live → row; closed+down → gone), extended
  tab-bands union oracle.
- Views/fragment tests: pane session rows carry machine facts + lifecycle
  actions; ops panel renders breaker/halt state; `/system` redirect.
- TUI: keymap tests for relocated verbs (existing update-fn test style).
- Manual pass: live session → adopted onto board → ship → close →
  winding-down → bring down → gone.

## 8. Phasing (one arc, ~three commits)

1. **Model** — scan moves to `work`, adopter + winding-down derivations +
   reconcile wiring, tests. Board unchanged.
2. **Web** — pane session rows absorb machine facts + lifecycle, ops panel on
   the rail dot, winding-down band, delete `/system` surface (+ redirect).
3. **TUI** — relocate plumbing keys + ops overlay, delete the system screen.
   Then CLAUDE.md updates (surfaces list, debug escape hatch) and the daemon
   restart note (reconcile-tick adopter only loads on restart).

## Rejected alternatives

- **Projection-only unification** (synthetic scratch rows at render time, no
  writes): cheapest and reversible, but the model stays split and every
  surface must re-derive the union — the TUI and web could drift again.
- **Full merge** (re-key the session registry under workstreams, delete
  `nido.session.state` as an independent store): a real migration touching
  every lifecycle path, buying nothing user-visible beyond the invariant that
  durable adoption already achieves.
- **Auto-down on workstream close**: eliminates leftovers entirely but risks
  yanking a worktree you're still poking at when a merge lands.
- **Keeping `/system` as an unlinked debug page**: the CLI already covers the
  wedged-model escape hatch read-only; a hidden page is a second source of
  truth waiting to rot.
