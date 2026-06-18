# Daemon-bundled dashboard + live-sessions board

**Date:** 2026-06-15
**Status:** Approved (design)

## Problem

Getting the dev-instance URL for a running session is too much friction. Today the
dev URL (`http://localhost:<app-port>`) only surfaces if you open the TUI
session-info modal or dig the port out of the session briefing. The web dashboard
*could* be the place you grab URLs, but it isn't running unless you remember to
launch `bb nido:ui` in a dedicated, blocking terminal — and even then its session
table renders the URL column as "—" because of a field-name bug (see below).

We want an **always-on dashboard tab**: a browser tab you leave open that lists
every live session across all projects, each with a clickable link, kept running
by the standard daemon.

## Goals

1. The dashboard starts and stops as part of the standard coordinator daemon — no
   separate process to remember, no blocked terminal.
2. The dashboard home page is a flat board of all sessions across all projects,
   live-first, each live session a clickable dev link.
3. Links use the per-session **friendly host**
   (`http://<session>.<project>.localhost:<port>`) so each session gets its own
   browser cookie jar.

## Non-goals (deferred "renewed attention", later passes)

Visual restyle/redesign beyond the board, auth or remote access, copy-to-clipboard
affordances, TUI→dashboard deep-linking. This unit is strictly: bundle + board +
working friendly-host links.

## Current state (what exists)

- **Dashboard** — `nido.ui.server`, a Datastar HTTP server. `start!`/`stop!` guard a
  `server-atom` defonce (idempotent). Routes: `GET /` (project grid),
  `GET /:project/sessions` (+ `_fragment/list` SSE tbody), per-session log pages,
  VSDD run views, and `POST /:project/sessions/:name/{start,stop,restart}` lifecycle
  actions. Started only by the standalone blocking task `bb nido:ui [:port 8800]`.
- **Coordinator daemon** — `nido.coordinator.core/run!`. Long-lived bb/JVM process
  (launchd or bare). `ensure-dirs!` → `reconcile!` → `resubmit-queued!` →
  `pid/write!` → `install-shutdown-hook!` → tick loop. Knows nothing about the
  dashboard.
- **Friendly host already exists end-to-end:** `eval.clj/friendly-host` builds
  `<session>.<project>.localhost` (`*.localhost` → 127.0.0.1 via RFC 6761, no
  `/etc/hosts` needed); `eval.clj` stores `[:app :url]` =
  `http://<friendly-host>:<app-port>`; `launcher.clj` persists it into the registry
  entry as **`:app-url`**.
- **The latent bug:** `views.clj/session-row` reads `(:url entry)`, but the registry
  key is `:app-url`. So the URL column has always rendered "—". Fixing this key
  alignment is most of "use the friendly host."

## Design

### 1 — Dashboard runs in-process with the daemon

The coordinator is already a long-lived bb/JVM process and httpkit runs its own
thread pool, so the dashboard is a passive, non-blocking in-process server. This is
the smallest change and keeps one PID, one log, one lifecycle.

- **Start:** in `core/run!`, after `reconcile!`/`resubmit-queued!` and before the
  tick loop, start the dashboard when enabled. The existing `server-atom` defonce
  already prevents a double-start.
- **Stop:** extend `install-shutdown-hook!` to call `ui.server/stop!` alongside the
  existing source teardown + heartbeat/pid cleanup.
- **Config:** extend coordinator `defaults` with `:dashboard {:enabled? true :port
  8800}` (8800 = the current standalone default). Overridable from the CLI:
  - `bb nido:coordinator:up :dashboard-port <n>` / `bb nido:coordinator:run
    :dashboard-port <n>` — bind a different port.
  - `bb nido:coordinator:up :no-dashboard true` — start the daemon without the
    dashboard.

  The `up` task threads these through to the spawned `run` invocation (same shape as
  the existing `:poll-ms` passthrough), and `run!` reads them into the dashboard
  start decision.
- **Foreground parity:** `bb nido:coordinator:run` starts the dashboard too, so the
  foreground and daemonized paths behave the same.
- **Discoverability:** `start!` already prints `Dashboard running at
  http://localhost:<port>`. Add a `Dashboard:` line to `bb nido:coordinator:status`
  reporting the configured port and whether the port is reachable.
- **Standalone task stays:** `bb nido:ui` remains for UI iteration and daemon-off
  use. Documented caveat in CLAUDE.md: it and the daemon both bind 8800 — don't run
  both at once.

### 2 — Flat live-sessions board as home

- `GET /` renders the board instead of the project grid. The project grid moves to
  `GET /projects` (reachable via a nav link); `/:project/sessions` drill-down is
  unchanged.
- **Aggregation:** extract a cross-project `all-session-rows` in `ui.server` that
  maps the existing per-project `session-rows` over `project/list-projects` and
  concats, tagging each row with its `:project`. All the live/TCP/RSS/pending-state
  logic is reused unchanged. Sort live-first, then by `project` then `session`.
- **Row contents:** `project · session · ● status · DEV URL`. Live sessions show the
  friendly-host link (`target="_blank"`); down sessions show a `start` button. Stop/
  restart and the per-session log page stay reachable (session name links to its log
  page, as today). Lifecycle `POST` routes are already keyed by project + session, so
  buttons on the board reuse them verbatim.
- **Auto-refresh:** new `GET /_fragment/live` returns the SSE tbody
  (`views/live-board-fragment`); the board page polls it via the existing
  `data-on-interval`/datastar pattern, so newly-up sessions and their links appear
  without a manual reload.
- **New views:** `views/live-board-page` (full page + nav) and
  `views/live-board-fragment` (tbody). The per-project `sessions-page` /
  `sessions-table-fragment` are unchanged except for the URL-key fix below.

### 3 — Clickable dev URL = friendly host

- Both the board and the existing per-project table render
  `[:a {:href url :target "_blank"} url]` from the registry's friendly-host URL.
- **Fix the key mismatch:** align `session-row` (and the board row) to read the
  registry's `:app-url` rather than the non-existent `:url`. This lights up the board
  links *and* un-darkens the long-dead URL column in the per-project table.
- New tab + friendly host ⇒ each session has its own cookie jar; several live brian
  sessions can be open at once without clobbering each other's logins.

## Testing

- **Unit (`all-session-rows`):** against a faked registry + tcp probe — multiple
  projects, a live/down mix, correct live-first sort, friendly-host URL surfaced from
  the entry's `:app-url`.
- **Lifecycle:** `start!`/`stop!` idempotency when driven from the daemon path;
  `:no-dashboard true` skips start; status reports the `Dashboard:` line. (httpkit
  bind is awkward to unit-test; a smoke check that the daemon start path invokes the
  server start without throwing and the shutdown hook stops it is sufficient.)
- **Manual:** daemon up → open `localhost:8800` → board lists live sessions across
  projects → click a link → lands in that session's app under its friendly host;
  bring a session up/down and watch the board refresh.

## Risks / notes

- **Shared JVM fate:** an OOM/crash in the coordinator takes the dashboard down with
  it. Accepted — both already share the "is nido running?" fate, and the dashboard is
  read-mostly.
- **Friendly-host reachability:** `*.localhost` resolves to 127.0.0.1, so requests
  reach the app's port regardless of host header. If a target app issued absolute
  redirects to a hard-coded host or set a `Domain` cookie, the friendly host could
  misbehave — verify against brian during implementation (the URL is already what
  `eval.clj` waits on, so this is expected to be fine).
- **Port collision:** the standalone `bb nido:ui` and the daemon both default to
  8800. Documented as "don't run both"; no programmatic guard.
