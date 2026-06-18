# Slack bug-channel intake — design

**Date:** 2026-06-16
**Status:** Approved (brainstorm), pending implementation plan
**Author:** brainstorm session

## Summary

Add a Slack bug channel as a **parallel input source** into nido's existing
triage/workstream pipeline, alongside the Notion-view source. Every new
top-level message in the channel becomes a triage-able workstream that
**auto-fires a triage session**; the human reviews the verdict in the session
chat (the skill's existing HITL halt). The verdict **stays in nido** — it lands
in the per-ticket ledger; there is no writeback to Slack or Notion in this
iteration.

The feature is "the Notion source, but for Slack." It reuses the entire
downstream pipeline (queue/envelope, runs, sessions, workstreams, the per-ticket
ledger, the executor, and every safety brake) and adds one client, one source
plugin, and one auth task — plus a single additive generalization of one shared
function.

## Decisions locked during brainstorm

1. **Relationship to Notion:** parallel intake into the *same* pipeline — a
   Slack post and a Notion ticket both become triage-able workstreams. Slack is
   not a pre-Notion stage and does not feed Notion.
2. **What counts as input:** every new *top-level* message (thread replies
   excluded). The triage agent decides bug-vs-not — we do not rely on a tag or
   reaction. Matches the existing "don't trust the tag, let the agent judge"
   philosophy.
3. **Disposition:** auto-fire triage (like Notion intake today), gated by the
   triage skill's in-session HITL halt — not queue-for-promotion.
4. **Where the verdict lands:** nido only (per-ticket ledger + session chat). No
   Slack thread reply, no Notion ticket — for now.
5. **Reach mechanism:** poll the Slack Web API (`conversations.history`) on an
   interval with a bot token from the macOS keychain. The coordinator is a
   localhost daemon with no public URL, so the Events API webhook path is not
   available; polling mirrors the Notion source exactly.
6. **Approach:** generalize the single hardcoded `:adapter :notion` seam in
   `spawn.clj` (default stays `:notion`), rather than bridging Slack→Notion or
   forking a parallel triage pipeline.

## Architecture & components

### New files (each mirrors a Notion-side counterpart)

- **`src/nido/slack/client.clj`** — keychain token read/set (service name
  `nido-slack`, mirroring `nido-notion`; `security find-/add-generic-password`,
  `-U` upsert), `conversations.history` (paged via `cursor`),
  `chat.getPermalink`, and `normalise-message` that flattens a raw Slack message
  into a plain map (`:ts :text :user :subtype …`). HTTP and `security` are redef
  seams so tests can stub them (same pattern as `nido/notion/client.clj`).
- **`src/nido/coordinator/sources/slack.clj`** — the `:slack-channel` source
  plugin: poll → watermark on `ts` → emit one event per new top-level message.
  Mirrors `sources/notion.clj`, including the half-open breaker and the
  `register!` / `start-instance!` / `poll-once!` shape.
- **`src/tasks/nido_slack.clj`** (+ `bb.edn` task wiring) — `bb
  nido:slack:auth:set` (token from **stdin**, never a CLI arg) and
  `bb nido:slack:auth:check`. Mirrors `nido_notion.clj`.

### Touched files (small, additive)

- **`src/nido/coordinator/spawn.clj`** — `external-ref` and the two
  `find-by-ref` calls become adapter-aware, defaulting to `:notion`.
- **`src/nido/coordinator/core.clj`** — register the Slack plugin at startup,
  next to the existing `(nsource/register!)`.
- **`src/nido/coordinator/workstreams_view.clj`** — `ws-source` learns to return
  `:slack` for a workstream carrying a `:slack-message` ref, so the TUI/dashboard
  get a Slack lane alongside Notion/GitHub/Scratch.
- **brian's `triage-bug` skill** — tolerate a run with no Notion ticket (triage
  from the brief, verdict to the ledger only). Brian-side; tracked as a cross-repo
  dependency (see below).
- **`~/.nido/projects/brian/triggers.edn`** — one new `:slack-channel` trigger.

### Unchanged and reused as-is

The queue/envelope machinery, `runs`, `session`, `workstream` create/find-by-ref,
the per-ticket ledger (`tickets.clj`), the executor, the safety brakes (budget,
breaker, halt/kill-switch), and `promote`.

## Polling & identity mechanics

### Source-config shape

```clojure
{:type    :slack-channel
 :channel "C0123ABC"            ; the bug channel id
 :poll    "2m"
 :subtypes-skip #{"channel_join" "channel_leave" "channel_topic"}}  ; optional; default baked in
```

### Polling — watermark, not set-diff

The Notion source keeps a *set* of page-ids and emits the set-difference each
poll. A chat channel is append-only and unbounded, so a growing id-set is the
wrong structure. The Slack source keeps a single **watermark**: `:last-seen-ts`.

Each poll:
1. Call `conversations.history` with `oldest = last-seen-ts` (exclusive).
2. Walk returned messages in ascending `ts`.
3. Emit one event per qualifying message.
4. Advance `:last-seen-ts` to the max `ts` seen.

Properties:
- **Top-level only, for free:** `conversations.history` returns only
  channel-level messages (thread *replies* live under `conversations.replies`,
  which we never call). Thread chatter is excluded without extra work.
- **Subtype skip:** system subtypes (`channel_join`, …) are filtered via
  `:subtypes-skip`. Plain user messages and bot posts both count as candidate
  bugs.
- **Cold start = seed, emit nothing:** the first poll (no `:last-seen-ts` on
  disk) sets the watermark to the latest message's `ts` and emits zero events, so
  turning the trigger on does not replay the channel's backlog as a flood of
  triage sessions. (Same spirit as Notion's "first poll seeds the snapshot.")
- **No re-emit under normal operation:** the watermark only moves forward, so
  each message crosses it exactly once.

### Crash-safety

Same ordering rule as the Notion source: write the envelope **first**
(content-addressed filename = hash of the broadcast contents), advance the
watermark **second**. A crash in between re-queries from the old watermark next
poll and re-writes the same envelope → identical filename → filesystem no-op.

### Identity — the payload the source emits

```clojure
{:adapter :slack-message
 :id      "slack-C0123ABC-1718000000.000123"  ; stable, unique, fs-safe
 :ts      "1718000000.000123"
 :channel "C0123ABC"
 :url     "https://….slack.com/archives/C0123ABC/p1718000000000123"  ; chat.getPermalink
 :title   "<first line, truncated>"   ; workstream title + TUI/dashboard display
 :text    "<full message text>"       ; the brief the triage skill works from
 :user    "U0456DEF"}
```

The single `:id` is the spine of the feature, used three ways:
1. **Workstream dedup** — generalized `external-ref` produces
   `{:adapter :slack-message :id <id> :url … :title …}`; `find-by-ref` on
   `(:slack-message, id)` means the same message never mints two workstreams.
2. **Ledger key** — `tickets.clj` keys the per-ticket dir by this id, so the
   Slack bug's triage record lives at
   `…/projects/brian/tickets/slack-C0123ABC-1718000000.000123/`, parallel to a
   `BR-####` dir.
3. **Envelope/run identity** — flows through as the run's payload `:id`.

### Breaker

Mirror the Notion source exactly:
- `401` opens immediately (a bad token won't self-heal).
- Other API errors open after 3 consecutive failures.
- Connectivity blips never trip (transient on a laptop that sleeps/roams) —
  tracked for visibility, polling continues.
- Slack's `429 + Retry-After` rate-limit is treated as transient (back off,
  don't trip).
- Half-open: a tripped breaker suppresses polling until a cooldown elapses, then
  one probe poll closes-on-success / re-arms-on-failure.

## Spawn generalization (the one shared-file edit)

Three spots in `spawn.clj` hardcode `:notion` — `external-ref` builds the ref,
and both `ensure-workstream!` and `spawn-records!` call
`find-by-ref project :notion …`. Derive the adapter once from the payload and
thread it:

```clojure
;; external-ref — was (cond-> {:adapter :notion :id id} …)
(let [adapter (or (:adapter payload) :notion)   ; Notion payloads have no :adapter → unchanged
      id      (:id payload)]
  (when (and id (not (str/blank? id)))
    (cond-> {:adapter adapter :id id}
      (:title payload)   (assoc :title (:title payload))
      (:url payload)     (assoc :url (:url payload))
      (:page-id payload) (assoc :page-id (:page-id payload)))))  ; Slack: absent → omitted

;; ensure-workstream! and spawn-records!: :notion → (:adapter ref)
```

Default-`:notion` keeps every existing Notion payload and the `:plan-*` promote
legs byte-for-byte unchanged (a regression test pins this). Slack payloads carry
`:adapter :slack-message`, so they dedup on their own adapter.

`ws-source` (workstreams-view) gains a branch:

```clojure
(some #(= :slack-message (:adapter %)) (:external-refs ws)) :slack
```

## Triage adaptation (brian-side — tracked cross-repo dependency)

This is the only piece that lands outside nido. nido's contract to the skill for
a Slack run:
- the run payload has `:adapter :slack-message`, no Notion `:page-id`, and the
  message text + permalink already present in the first-message brief;
- **no `:preprocess`** — nothing to fetch (a Slack post is not a ticket with
  videos/comments);
- ledger key = the Slack `:id`.

The skill requirement: triage from the brief, write the verdict to the nido
ledger as it already does, and **skip the Notion writeback when there is no
`:page-id`** (the in-chat `apply` commits only the ledger verdict; `promote`
still works).

During planning we verify whether brian's `triage-bug` already degrades
gracefully on a Notion-less run, and edit it only if needed. nido's half cannot
demo end-to-end until the skill tolerates a Notion-less run, so the plan
sequences this dependency explicitly rather than discovering it late.

## Trigger config (brian's `triggers.edn`)

```clojure
{:name            :triage-slack-bugs
 :source          {:type :slack-channel :channel "C0123ABC" :poll "2m"}
 :skill           :triage-bug
 :session-profile :lite
 ;; no :preprocess — message text is the brief
 ;; no :dry-run?   — the skill's HITL halt is the gate (same as Notion triage)
 :max-in-flight   3
 :payload         "Triage Slack bug: {{event/title}}\n\n{{event/text}}\n\n{{event/url}}"
 :payload-key     :id
 :limits          {:budget "15m" :max-failures 3}}
```

Differences from the Notion triage trigger: no `:preprocess [:notion-ticket]`,
the payload carries `{{event/text}}` (no preprocess to fetch the body), and
`:payload-key :id`.

## Safety (all reused — nothing new invented)

- **Default off:** the source only *starts* when a trigger references it
  (`reconcile-sources!`), and no trigger exists until one is added to
  `triggers.edn`.
- **Backlog flood:** cold-start watermark seeding is the first line of defense;
  `:max-in-flight 3` caps steady-state concurrent triage runs.
- **Per-run budget** 15m (SIGTERM→SIGKILL), **per-trigger breaker** (3
  failures), the **Slack source's own** 401/5xx breaker, and the **daemon-wide
  halt / kill-switch** all apply unchanged.

This matches nido's "autonomous = default off, hard caps, circuit breakers,
visible state" rule.

## Testing

Mirror the Notion source's test suite. No live Slack calls — HTTP and keychain
stubbed via the redef seams; clock and state-dir seams reused.

- **`slack/client`** — `normalise-message`, history paging, permalink, keychain
  set/get.
- **`sources/slack`** — cold-start seeds + emits nothing; a new message emits
  exactly one event and advances the watermark; subtype skip; breaker
  transitions (401 immediate, 5xx after 3, network never trips, 429 transient);
  crash-safety idempotent re-emit.
- **`spawn/external-ref`** — Notion payload (no `:adapter`) still → `:notion`
  (regression pin); Slack payload → `:slack-message`; the same message twice →
  one workstream (dedup).
- **`workstreams-view/ws-source`** → `:slack` for a slack-ref'd workstream.
- **End-to-end-ish path test:** a Slack envelope through `spawn-records!` yields a
  `:slack-message` workstream + ledger dir + session, idempotent on re-fire.

## Auth & operations

- `bb nido:slack:auth:set` — read a bot token from stdin, store in keychain
  (`nido-slack`). The token needs `channels:history` (public) or
  `groups:history` (private), plus the bot added as a channel member.
- `bb nido:slack:auth:check` — report token presence.
- **Token rotation:** the token shared in the brainstorm chat is burned; a
  freshly rotated token is stored here.

## Out of scope (this iteration)

- Slack thread-reply writeback of the verdict.
- Promoting a Slack message into a Notion ticket.
- Reaction-gated intake (only-emoji-flagged messages).
- Thread-reply context fetching (`conversations.replies`).

These are natural follow-ups once the parallel intake proves out.
