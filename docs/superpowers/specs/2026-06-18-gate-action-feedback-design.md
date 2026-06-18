# Gate-action feedback + follow-handoff — design

**Date:** 2026-06-18
**Status:** approved (brainstorm), spec under review

## Goal

When you act on a gate (Promote / Skip / Drop / Done / Reply), the **pane you clicked in** confirms what happened and hands you a one-click path to follow the item — instead of silently patching only the left inbox column and leaving the pane stale.

## Problem (observed live)

A user clicked Reply ("Apply") on a triage gate, then Promote on the re-appeared gate. Both **worked** (ticket `disposition :applied`, then `:planning`), but the UI showed nothing useful: mutations return the inbox fragment (which patches the *left* column, not the pane the user clicked in), and an item that advances past "needs-you" silently leaves the inbox. Result: "Promote did nothing visually" and "I lost track of the item after acking." The gate inbox is, by design, a "what needs me *now*" queue — acting removes the item — so the surface owes the user **closure + a trail**, which it currently doesn't give.

(Decided model: option 1 — confirm + follow-link. Not option 2, where the pane tracks the item live through its stages.)

## The fix

**Every gate action returns a tailored confirmation into the pane**, with follow-links. The inbox stays a clean now-queue (its 3s poll already reflects the item leaving/advancing on the left); continuity comes from the pane confirmation + the links + the Board.

Per-action pane message:
- **`:promote`** → "Promoting → in-progress… provisioning the work session."
- **`:skip`** → "✓ Skipped — dropped, not pursued."
- **`:drop`** → "✓ Dropped — not pursued."
- **`:done`** → "✓ Marked done."
- **`:reply`** → "Resuming… re-hydrating the session if needed, then resuming the conversation." (the current reply behavior, now one case of the unified fragment)

Each confirmation carries two **follow-links**:
- **"open workstream →"** → `/ws/<project>/<ws-id>` (the read-only detail: current stage, sessions on the autonomy axis, ledger — shows where the item is now).
- **"board →"** → `/board` (the spine board, where it lives grouped by stage).

## Components & mechanics

- **New view `gate-action-confirm-fragment [action-id project ws-id]`** (`nido.ui.views`) → a `[:div {:id "gate-pane"} …]` patching the pane: the per-action message (a `case` on `action-id`, with a generic fallback) + the two follow-link anchors. This **replaces `gate-resuming-fragment`** (the `:reply` message becomes one `case` branch); update its one caller.
- **`server.clj` gate POST branch** returns `(gate-action-confirm-fragment action-id project ws-id)` for **all** gate actions. Today it returns the inbox fragment for mutations and the resuming fragment only for `:reply`; both are replaced by the single confirm fragment. `gate-resolve!` is **unchanged** — it still runs the action on a background future. The confirmation is action-keyed / optimistic, which is honest: the fast settling actions (skip/drop/done = `close!`) are reliable, and the slow advancing ones (promote/reply) read "…ing".
- **The resume-error badge** (shipped in the re-hydratable-resume sub-project) still surfaces reply *failures* on the gate when it re-appears; nothing there changes.

## Data flow

Action click in the pane → `POST /gate/:project/:ws-id/:action` → `gate-resolve!` (background future runs the real action) → handler **immediately** returns `gate-action-confirm-fragment` → Datastar patches `#gate-pane` with the confirmation + follow-links. Meanwhile the inbox's 3s poll reflects the item leaving/advancing on the left. The user reads the confirmation and, if they want, clicks "open workstream →" / "board →" to follow it.

## Error handling

- Reply failures: covered by the existing `:resume-error` badge (the gate re-appears with "⚠ resume failed: …"). Unchanged.
- Mutation failures (rare — Promote only appears at `:ready` where the ticket is `:triaged`, so the promote gesture succeeds): the optimistic confirmation is accepted for v1; a failed mutation surfaces via the item simply not appearing where expected on the Board/detail. Result-aware mutation confirmation (run fast actions synchronously for a definitive "✓/✗") is a possible later refinement, not in scope.

## Testing

- **`gate-action-confirm-fragment`** (pure view test): each `action-id` (`:promote` `:skip` `:drop` `:done` `:reply`) renders its message; the fragment targets `id="gate-pane"`; both follow-links (`/ws/<p>/<ws>`, `/board`) are present; an unknown action-id renders the generic fallback.
- **server**: the gate POST returns a fragment containing the per-action confirmation + the follow-links (for a mutation AND a reply); `gate-resolve!` is still called (redef to capture). The pre-existing reply test (asserts `@calls`) still passes.

## Non-goals

- Option 2 (the pane actively tracking the item live through its stages). The inbox keeps its now-queue semantics; no lingering/linger-then-drop.
- The **promote → impl-session provisioning** question (Promote set the ticket `:planning` but the impl session wasn't seen in the registry) — a separate *functional* investigation, not this UI change. This design assumes the underlying resolve actions work (they do).
- No change to `gate-resolve!`'s async model, the inbox fragment, the spine board, or `/ws` detail beyond linking to them.
