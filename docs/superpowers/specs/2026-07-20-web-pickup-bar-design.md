# Web pickup bar — start/continue a session from a pasted Notion ticket

**Date:** 2026-07-20
**Status:** Design approved

## Problem

Nido is moving toward Notion as the work queue. There is no convenient way to
hand nido a backlog item from the **web dashboard**: you paste a Notion ticket
URL (or page id, or `BR-####`) and nido should spin up a session — continuing an
existing workstream ledger if it already knows the ticket, or starting a fresh
one if not.

The orchestration for this already exists and is only reachable from the
CLI/TUI today. What is missing is a **web entry point** plus honest feedback
about which path (continue vs. start) was taken.

## What already exists (reused as-is)

`nido.coordinator.pickup/pickup!` does the hard part:

1. Resolves a pasted **Notion URL / page-id / `BR-####`** to a ticket ref via a
   synchronous Notion lookup (`resolve-ref`).
2. Enqueues a `:plan-bug` envelope targeting the project.

The daemon then consumes the envelope and **find-or-creates the workstream by
its Notion ref** (`workstream/find-by-ref-id` → the shared Phase-B ledger),
bringing up a `:full` session that runs `/continue-ticket` headlessly. So
"continue if we know the ticket, else start fresh" is already deterministic in
the daemon — this design adds only the web door and the reporting of which path
the daemon will take.

`bb nido:pickup` (task `tasks.nido-pickup`) is the existing CLI front-end and
stays unchanged in behavior.

## Design

### Backend

#### `pickup!` reports continuing vs. new

`pickup!` gains a lookup, before it enqueues, of whether a workstream already
exists for the resolved ref:

- Call `ws/find-by-ref-id project (:id ref)` (the `:id` is the `BR-####` unique
  id).
- Add two keys to the existing success return map:
  - `:continuing?` — `true` when a workstream already carries this ref, else
    `false`.
  - `:ws-id` — the existing workstream id when continuing, else `nil`.

The enqueue and daemon path are unchanged; these keys are pure reporting. The
CLI ignores the extra keys, so this is backward-compatible.

Return shape (success):

```clojure
{:decision    :driving
 :ref         {:id :page-id :url :title}
 :continuing? <bool>
 :ws-id       <existing-ws-id-or-nil>
 :queued      <envelope-path>}
```

Error shape is unchanged: `{:decision :unresolved :error <kw>}`.

#### New POST route

`POST /workstreams/pickup/:project` in `nido.ui.server`:

- Read the pasted text from the Datastar signal body: `{:pickup "…"}` (parsed
  with the existing `parse-json-body`).
- Call `pickup! (keyword project) input (client/keychain-token)`.
- **Project resolution:** the `:project` segment is computed by the view from
  the current scope — a concrete scope yields that project; scope `all` yields
  `brian` (the Notion-owning project). This is a sensible default, not a hard
  rule; if nido grows a second Notion project, the view's fallback is the single
  place to revisit.
- **Daemon-down guard:** consult `read-rail-daemon` (the existing seam over
  `health/read-daemon-health`). If the daemon is not alive, the confirmation
  still renders (the envelope IS queued) but appends a warning that it will not
  run until the daemon is back up.
- Respond with an SSE fragment (via the existing `sse-response` +
  `sse-fragment`) that patches `#pickup-result`.

Blank/whitespace input short-circuits to a gentle "paste a ticket" prompt
without calling Notion.

The Notion resolution is synchronous inside the handler (one network call, sub-
second, same as the CLI). No background future is needed because the confirm
text needs the resolved title anyway.

### UI

#### `pickup-bar` view fn

Rendered at the top of the `.queue-col` in `workstreams-page`, **above**
`tab-row` — on the stable page chrome, NOT inside `workstreams-fragment` (which
the 5s poll overwrites and which has no daemon/project context threaded in).

Structure:

- A text `input` bound to a `pickup` signal (`data-bind="pickup"`), placeholder
  `paste Notion URL / BR-#…`.
- A **Drive →** button: `@post('/workstreams/pickup/<project>')`, where
  `<project>` is computed once at render from the screen's scope (concrete →
  that project; `all` → `brian`). Datastar auto-serializes the `pickup` signal
  into the JSON body.
- **Enter-to-submit:** a `data-on:keydown` handler filtered to the Enter key
  fires the same `@post`, so the button is optional.
- An empty `#pickup-result` div directly below, patched by the SSE response.

Because the bar lives on the page chrome (outside the `.inbox` that the 5s poll
replaces), the result is not clobbered by polling — it persists until the next
submit.

#### `pickup-result-fragment`

Renders one of:

- **Continuing:** `✓ Continuing BR-104 "Fix login redirect"` with a link to
  `/workstreams/<project>/<ws-id>` and "session spinning up…". The `ws-id` is
  known via `find-by-ref-id`.
- **Starting fresh:** `✓ Starting BR-104 "Fix login redirect" (new workstream)`
  — no deep link yet (the daemon mints the id on consume), so it notes "it'll
  appear in the spine shortly." The existing 5s poll surfaces the new row.
- **Error:** friendly per-kw text:
  - `:no-token` → "No Notion token in keychain."
  - `:not-found` / `:not-a-ticket` → "Couldn't find that ticket."
  - `:unrecognized-input` → "Paste a Notion URL, page id, or BR-####."
  - `:notion-error` (and any other) → "Notion lookup failed — try again."
- **Daemon-down warning** appended to a success confirmation when applicable.

## Testing

- Unit-test the enhanced `pickup!`:
  - existing ref → `:continuing? true` + `:ws-id` populated.
  - unknown ref → `:continuing? false` + `:ws-id nil`.
  - (Notion resolution + daemon consume already have coverage.)
- Unit-test `pickup-result-fragment` rendering for each decision and error kind:
  continuing (with link), starting-fresh (no link), each error kw, and the
  daemon-down warning append.

## Out of scope

- No new orchestration: the `:plan-bug` leg and daemon find-or-create are reused
  unchanged.
- No pickup bar on other surfaces (Needs / System) — `/workstreams` only, per
  the placement decision.
- No redirect-into-pane on submit — inline confirmation only, so several tickets
  can be queued in a row.
