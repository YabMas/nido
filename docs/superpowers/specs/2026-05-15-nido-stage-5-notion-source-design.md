# nido Stage 5 — event-source plugin infrastructure + `:notion-view`

**Status:** designed, not implemented
**Date:** 2026-05-15
**Parent spec:** [`2026-05-13-nido-coordination-layer-design.md`](./2026-05-13-nido-coordination-layer-design.md)

## Context

Stages 1–4 built the coordinator daemon, Run lifecycle, brakes, background-daemon harness, and launchd auto-start. The only event source so far is `:manual` — the filesystem queue at `~/.nido/coordinator/queue/`. Stage 5 introduces the first **autonomous** source: a Notion view poller that emits an event for every new row appearing in a configured view.

To get there, two things have to land together:

1. **The event-source plugin infrastructure** the parent spec sketched in §Event sources but didn't build. Today `events.clj` returns `{:error :broadcast-not-implemented}` for `:broadcast` envelopes, and `core.clj` only drains the queue. There's no source registry, no `start!`/`stop!` lifecycle, no source-config dedup.
2. **The `:notion-view` source itself** — auth, polling, dedup, HTTP error handling.

Cron and GitHub sources stay out of scope; they slot in later by calling the same `register-source!` API.

## Goals

- Establish a source-plugin model that supports broadcast and direct-target patterns side by side.
- Ship `:notion-view` as the first real autonomous source: at every poll interval, query a Notion database, diff against the last seen snapshot, emit one event per *new* row.
- Predictable cold-start: the first poll after install seeds the snapshot and emits nothing. Only entries that *enter* the view after the poller starts produce Runs.
- Notion API token stays out of plaintext (macOS Keychain).
- Source-level health surfaces in `bb nido:coordinator:status` so a broken Notion integration is visible.
- One bad source does not stall the daemon: bounded HTTP timeouts, per-source circuit-breaker.

## Non-goals

- Cron source. Designed-in, not built; this spec keeps the registry generic enough to add later.
- GitHub source.
- A TUI affordance for clearing source-level breakers. CLI only.
- Webhooks. Polling only.
- "View" as a Notion API concept — Notion's REST API doesn't expose view filtering directly. The `:view` field on a trigger's source config becomes a human-readable label; real filtering happens via the trigger's `:filter` field, evaluated client-side after `database/query`.
- Rate-limit tracking beyond a fixed timeout. Notion's documented rate limit is 3 req/s per integration; with default `:poll` ≥ 30s this is moot.
- Pagination. v1 of the source fetches the first page only (default 100 rows). A view with > 100 untriaged tickets is bigger than the motivating use case; flagged as a follow-up.

## Design choices (decided)

These came out of brainstorming on 2026-05-15:

1. **One spec for both infrastructure and first source.** Notion can't ship without the plugin model, and the model isn't worth designing in the abstract — the first source pins down the contract.
2. **Polling lives in the main `tick!`**, not on side threads. Each tick visits every distinct source-config, polls those whose `:poll` interval has elapsed since their last poll. Single-threaded daemon stays single-threaded; halt / anomaly / breaker semantics keep working. Hard 10s timeout per HTTP call so one slow Notion endpoint can't stall the loop.
3. **Set-diff dedup with seeded cold start.** Each `[database, view]` keeps `last-rows: #{page-id …}` on disk. Each poll computes `(set/difference current-rows last-rows)` and emits one event per addition. The very first poll for a fresh source-config writes the snapshot and emits *nothing* — only entries that enter the view after install fire.
4. **Auth in macOS Keychain.** Token stored once via `security add-generic-password -s nido-notion -a <user> -w <token>`. Coordinator reads at source-start time via `security find-generic-password -s nido-notion -a <user> -w`. Token held in source closure; never written to disk, never logged, never injected via env.
5. **Events emitted as broadcast envelopes through the queue dir.** Crash-safe ordering: write envelope file first (filename = content hash → idempotent on retry), then update the snapshot. This also finally implements the parent spec's `:broadcast` routing.

## Plugin contract

`nido.coordinator.sources` exposes:

```clojure
(register-source!
  :type    :notion-view
  :schema  <Malli schema for the :source map a trigger supplies>
  :events  <Malli schema for the event payloads this source emits>
  :start!  (fn [source-config emit-fn] {:poll! (fn [] ...)  :stop! (fn [] ...)}))
```

`:start!` is called once per *distinct value* of `source-config` (the trigger's `:source` map, sans `:type`). It returns:

- `:poll!` — zero-arg function the coordinator calls each tick (when due). May call `emit-fn` zero or more times. Must not block more than ~10s.
- `:stop!` — zero-arg function called when the daemon shuts down or when no triggers reference this source-config anymore.

`emit-fn` is supplied by the coordinator and writes a broadcast envelope into the queue dir.

**Failure semantics for `:poll!`:** any exception is caught by the coordinator, logged, and counts as a consecutive failure for that source-config. The source's `:start!` does not need to defend against transient errors itself.

**The `:manual` source stays special.** It's not a registered broadcast source — it's the existing queue-dir watcher producing direct-target envelopes. The plugin contract above covers broadcast sources only. Routing for both shapes lives in `events.clj` (see §Coordinator changes).

## State surface

```
~/.nido/coordinator/
├── coordinator.pid           # daemon (existing)
├── status.edn                # daemon heartbeat (existing)
├── halted.edn                # halt info (existing)
├── coordinator.log           # daemon log (existing)
├── queue/                    # envelopes (existing)
└── sources/                  # NEW (Stage 5)
    └── <config-hash>.edn     # one file per distinct source-config
```

Each `sources/<config-hash>.edn` holds:

```edn
{:type                 :notion-view
 :source-config        {:database "abc-123" :view "untriaged-bugs" :poll "5m"}
 :last-rows            #{"page-id-1" "page-id-2" ...}    ; absent on first poll
 :last-polled-at       "2026-05-15T13:30:00Z"
 :last-poll-result     :ok | {:error :http-503}          ; absent before first poll
 :consecutive-failures 0
 :breaker              :closed | :open}                  ; absent when :closed
```

`<config-hash>` is the SHA-1 of the pr-str'd canonical source-config (sorted-map). Truncated to 12 hex chars for human-readability. Two triggers with identical `:source` maps produce identical hashes and share one state file.

## Source: `:notion-view`

### Trigger-side config

```edn
{:name      :investigate-untriaged
 :source    {:type     :notion-view
             :database "abc-123-database-id"
             :view     "untriaged-bugs"        ; label only; not an API parameter
             :poll     "5m"}
 :filter    {:status "Untriaged" :priority ["P0" "P1"]}
 :skill     :investigate-bug
 :payload   "{{event/url}}"
 :payload-key :ticket-id
 :limits    {:cooldown "10m" :budget "45m" :max-failures 3}}
```

`:poll` is a duration string (`"30s" "5m" "1h"`); parsed once at source-start. Must be ≥ 30s; below that, validation rejects the trigger.

### Event-payload schema

Every emitted event has:

```edn
{:source       :notion-view
 :page-id      "abc-page-id"
 :url          "https://notion.so/Title-abc-page-id"
 :title        "Login redirect loops on Safari"
 :ticket-id    "ABC-123"                  ; from a Notion property if present, else nil
 :status       "Untriaged"
 :priority     "P0"
 :created-time "2026-05-15T13:00:00Z"
 :edited-time  "2026-05-15T13:30:00Z"
 :properties   {<keyword-of-property-name> <value>}}   ; all Notion properties, normalised
```

The coordinator routes the event to every trigger whose `:source` matches this source-config *and* whose `:filter` accepts the payload. Filter evaluation reads keys directly off the event map — including any inside `:properties` (`{:status "Untriaged"}` matches both top-level and nested forms uniformly via the existing filter DSL).

Filter is map-equality and set-membership (per parent spec). Richer DSL is out of scope.

### Poll loop

For each tick where `now - last-polled-at ≥ :poll`:

1. `client/query-database` with the configured `:database` and a soft cap of 100 results. 10s connect/read timeout. Authorization header from the keychain-resolved token.
2. On HTTP error: increment `:consecutive-failures`. After 3 consecutive failures, set `:breaker :open` and stop polling this source-config. Surface as `Sources: notion (breaker open: <reason>)` in status.
3. On HTTP 401: open the breaker immediately with reason `:auth`. Don't retry — token is bad and won't get better by waiting.
4. On success: normalise the response into the event-payload shape above. Compute `current-rows` (a set of page IDs).
5. If `:last-rows` is absent (first poll): write the snapshot, emit nothing.
6. Else: compute `additions = (set/difference current-rows last-rows)`. For each added page, write a broadcast envelope to the queue dir, then update `:last-rows` to `current-rows`.

### Envelope shape (broadcast)

```edn
;; ~/.nido/coordinator/queue/<hash>.edn
{:broadcast {:type    :notion-view
             :source-config {:database "abc-123" :view "untriaged-bugs" :poll "5m"}
             :payload <event-payload>}
 :created-at "2026-05-15T13:35:00Z"}
```

Filename is the SHA-1 of `pr-str`'d envelope content. Re-emission of the same payload (e.g. after a crash between envelope-write and snapshot-write) produces the same filename and is a filesystem no-op. The coordinator's existing queue-draining logic deletes envelopes after processing — so the "duplicate-write" window is between envelope-write and successful queue-drain, which is exactly the right idempotency window.

## Coordinator changes

### `events.clj` — breaking signature change

`route` today returns a single fire-request or error map. After Stage 5 it returns a **vector** of zero or more fire-requests (broadcast envelopes can fan out to many triggers, or to zero, or one). `core.clj`'s `process-envelope!` becomes a loop over the returned vector.

Implement the broadcast branch:

```clojure
(defn- route-broadcast
  [{:keys [broadcast]} triggers-by-project]
  ;; For each (project, trigger), if (= (:type broadcast) (-> trigger :source :type))
  ;;   AND (source-config-match? (:source-config broadcast) (-> trigger :source))
  ;;   AND (filter/accept? (:filter trigger) (:payload broadcast)),
  ;; produce a fire-request {:project p :trigger t :payload (:payload broadcast)}.
  ;; Returns a vector — possibly empty if nothing matched.
  ...)
```

`source-config-match?` is a private helper that strips `:type` from both maps and compares with value-equality.

### `filter.clj` (new) — trigger filter evaluation

Today's codebase has no `filter/accept?` function — trigger filtering is part of Stage 5. New namespace `nido.coordinator.filter` exposes one function:

```clojure
(defn accept?
  "Map-equality + set-membership filter, per parent spec.
   Filter keys must appear at the top level of the event payload OR
   under :properties — both are checked uniformly."
  [filter-map event-payload] ...)
```

For each filter key:
- if filter value is a primitive: equality with the event's value
- if filter value is a collection (vector/set): set-membership of the event's value
Filter map is closed-AND across all keys; an empty filter matches everything.

### `core.clj`

`tick!` gains a step:

```
tick!:
  read halt-info
  if halted: heartbeat :halted; return
  heartbeat :running
  drain queue → process envelopes
  poll due sources → write broadcast envelopes (next tick drains)
  check anomalies
```

Crucially, polled events land in the queue on this tick but are *not* processed until the next tick. This gives one clear unit of work per tick and avoids "the poll emitted 50 events, now we're spawning 50 sessions inside this tick."

On `run!` startup, after `pid/write!`:

1. Discover all triggers across all projects.
2. Compute distinct source-configs by `(:source trigger)`.
3. For each, look up the source type in the registry and call `:start!`. Pass an `emit-fn` closed over the source-config-hash.
4. Hold `{config-hash → {:poll! :stop! :source-config :poll-interval-ms :last-polled-at}}` in an atom keyed by hash.
5. On shutdown hook: call every `:stop!`.

On trigger config hot-reload (file watcher already in place):

- New source-configs: start them.
- Removed source-configs (no triggers reference them anymore): stop them.
- Changed `:poll` on an existing config: reuse the running source-config (since the value-equality changed, this is a remove + add, which is correct).

### `sources.clj` (new)

Minimal registry:

```clojure
(def ^:private !registry (atom {}))

(defn register-source! [{:keys [type schema events start!]}]
  (swap! !registry assoc type {:schema schema :events events :start! start!}))

(defn lookup [type]
  (get @!registry type))

(defn config-hash [source-config]
  (let [stripped (dissoc source-config :type)
        canonical (pr-str (into (sorted-map) stripped))]
    (subs (sha1 canonical) 0 12)))
```

### Module layout

New files:

- `src/nido/coordinator/sources.clj` — `register-source!`, `lookup`, `config-hash`, source-instance atom
- `src/nido/coordinator/sources/notion.clj` — `:notion-view` plugin (`start!` returning `{:poll! :stop!}`)
- `src/nido/coordinator/sources/state.clj` — read/write `~/.nido/coordinator/sources/<hash>.edn`
- `src/nido/coordinator/filter.clj` — `accept?` for trigger filters
- `src/nido/notion/client.clj` — HTTP wrapper, response normalisation, keychain token retrieval
- `src/tasks/nido_notion.clj` — `bb nido:notion:auth:set` / `:check`
- `src/tasks/nido_coordinator_source.clj` — `bb nido:coordinator:source:list` / `:reset`

Modified files:

- `src/nido/coordinator/events.clj` — broadcast routing; `route` returns a vector
- `src/nido/coordinator/core.clj` — source lifecycle on boot/shutdown; tick! polls due sources; `process-envelope!` iterates over `route`'s vector
- `src/tasks/nido_coordinator.clj` — `status` surfaces the `Sources:` section
- `bb.edn` — register the four new tasks

### Status surface additions

`bb nido:coordinator:status` gains:

```
Sources:      :manual (active)
              :notion-view a3f7c2 (last-polled 2m ago, OK)
              :notion-view b9e1d4 (breaker OPEN: auth)
```

When no sources beyond `:manual` are configured, the section is omitted to keep the default output clean.

## CLI changes

New tasks:

```
bb nido:coordinator:source:list                # one row per active source-config
bb nido:coordinator:source:reset :notion-view :database <id> :view <name>
                                                # clears breaker + consecutive-failures
bb nido:notion:auth:set                         # prompts for token, stores in keychain
bb nido:notion:auth:check                       # prints "OK" / "missing" / "invalid"
```

`source:reset` matches by source-config equality; the user passes the same keys that appear in their `triggers.edn`.

`notion:auth:set` reads the token from stdin (no terminal echo) and shells out `security add-generic-password -s nido-notion -a $(whoami) -U -w <token>`. The `-U` upserts if an existing entry is present.

## Failure modes & user-visible behavior

| Situation | What user sees |
|---|---|
| Keychain entry missing on `start!` | Source registers but `breaker :open` with reason `:auth`. `bb nido:coordinator:status` shows it. User runs `bb nido:notion:auth:set` then `bb nido:coordinator:source:reset` (or restart the daemon). |
| Notion HTTP 401 | Same as missing keychain — breaker opens with `:auth`. |
| Notion HTTP 5xx / network error | Consecutive failure counter increments. Breaker opens after 3 consecutive errors. Surfaced in status. |
| Notion view has > 100 entries | Only first page is fetched. v1 limitation; log a warning if a `has_more: true` response is returned. |
| Daemon crashes mid-poll (after envelope write, before snapshot update) | Envelope is durable in queue dir. Next start: snapshot is unchanged so the same row is re-detected. Envelope filename is content-hash so the second write is a no-op. Queue drain consumes the envelope exactly once. |
| Daemon crashes mid-poll (before envelope write) | Snapshot unchanged. Next poll re-detects and emits cleanly. |
| Trigger config references unknown `:source.type` | Existing trigger validation catches this; trigger is logged invalid + shown with `:invalid` badge in TUI. Daemon does not crash. |
| Two triggers share one source-config | Both registered; one poller emits; broadcast routing fans out to both at envelope-drain time. |
| User edits `:poll` on a trigger | Source-config is now structurally different, so it counts as a new source-config: the old one's `:stop!` fires (no triggers reference it), the new one's `:start!` fires. Snapshot file for the new config doesn't exist → fresh seed. Acceptable. |

## Testing

- **Pure tests** (`test/nido/coordinator/sources_test.clj`): `register-source!`, `lookup`, `config-hash` round-trip + canonical hashing.
- **Pure tests** (`test/nido/coordinator/events_test.clj`): broadcast routing — `route` against synthetic registries, fan-out cases, filter rejection.
- **Pure tests** (`test/nido/notion/client_test.clj`): response normalisation — feed recorded fixture JSON, assert the produced event payload. Use `with-redefs` on the HTTP client to inject fixtures.
- **Pure tests** (`test/nido/coordinator/sources/notion_test.clj`): set-diff dedup, cold-start seeding, envelope-then-snapshot ordering, breaker behavior. HTTP stubbed.
- **Manual smoke test**: keychain auth, single trigger against a real Notion test database; verify a new row produces a Run; verify the second poll doesn't double-fire; verify daemon restart picks up the snapshot.

No live Notion calls in CI. Fixture JSON checked into `test/fixtures/notion/`.

## Out of scope (for reviewer)

Deferred:

- Pagination (`has_more` → fetch next cursor). v1 caps at 100 rows; warn when exceeded.
- Rich filter DSL (regex, comparisons, OR). Current map-equality + set-membership is enough for the motivating use case.
- TUI affordance for source breaker reset / source-config list. CLI suffices.
- Cron source, GitHub source — separate Stage 5+ slices.
- Notion API webhooks (would require nido to receive inbound HTTP — out of scope architecturally).
- "Re-investigate this row" — once a page is in `:last-rows`, it's not re-emitted until it leaves and re-enters the view. Workaround: `bb nido:coordinator:source:reset` re-seeds.

## Implementation order

1. `sources.clj` registry + `config-hash` with unit tests.
2. `filter.clj` + tests — needed by broadcast routing.
3. `events.clj` broadcast routing + tests; `route` becomes multi-valued.
4. `core.clj` update: `process-envelope!` iterates `route`'s vector (no behavior change for direct-target envelopes).
5. `sources/state.clj` — read/write the per-source snapshot files + tests.
6. `notion/client.clj` — HTTP wrapper, response normalisation, keychain helper + tests with stubbed HTTP.
7. `sources/notion.clj` — `:notion-view` plugin (`start!`, `poll!`, `stop!`) + tests with stubbed HTTP. Includes breaker / consecutive-failures.
8. `core.clj` source lifecycle: discover at boot, start each, hold in atom, stop on shutdown; tick! polls due sources.
9. `bb nido:notion:auth:set` / `:check` tasks.
10. `bb nido:coordinator:source:list` / `:reset` tasks.
11. `status` task: `Sources:` section.
12. End-to-end smoke test against a real Notion database.
13. CLAUDE.md updated to point at this spec for source-plugin conventions.
