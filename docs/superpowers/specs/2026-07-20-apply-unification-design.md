# Apply unification — design

**Status:** approved-in-conversation (unify), pending written review
**Date:** 2026-07-20
**Follows:** the triage-routing arc (unlanded, 3 local commits). Fixes Important finding #1 from
that arc's final review and folds into it before landing.

## Problem

`nido.work/apply!` (`work.clj:483`) has two paths. The **Slack** path reads the typed
`:proposed-ticket` report and deterministically writes Notion (creates the page —
`apply-proposed!`). The **Notion** path just calls `tickets/complete! :triaged :applied`
and writes **nothing** to Notion — the notion-cli migration dropped its writeback. So a
routed triage's outcome (Ball Holder + App Domain, and deep enrichment) reaches Notion
**only** when the *agent* re-runs its skill Step 4 on a chat-`apply`. The prominent
one-click **Apply** button on the board/dashboard (→ `resolve-gate! :apply` → `apply!`)
therefore silently drops the entire routing outcome: the ticket leaves nido's radar as
`:triaged` but no owner and no area are ever written. For a shallow route the Notion
write IS the whole outcome, so it's lost.

This also means the routing write lives in **two** implementations (nido has none; the
skill has raw `notion api PATCH`/`notion page set`), which the review flagged as a
drift hazard.

## Goal

Make **nido the single executor of triage `apply`**: `apply!` reads the typed
`:triage-report` from the ledger and performs the Notion writes itself, exactly
mirroring the Slack `apply-proposed!` path. The board button and chat-`apply` become
one code path; the skill stops writing Notion directly.

---

## 1. nido writes the routing outcome (`work.clj` `apply!`)

Extend `apply!`'s Notion branch: when the latest ledger report is a `:triage-report`
carrying `:routing`, write Notion **before** completing the record, via
`nido.notion.client/update-page-properties!` (the `notify.clj/on-plan-spawn!` pattern —
one PATCH for all properties):

- **Always (both depths):**
  - **Ball Holder** — `{"Ball Holder" {:people [{:id <owner-id>}]}}` (replace; single owner).
  - **App Domain** — `{"App Domain" {:multi_select [{:name <n>} …]}}`, **additive**: read
    the page's current App Domain via `retrieve-page`, union in the routed value (mirror
    `notify.clj/merged-participants`, but for multi_select `:name`s). If the page can't be
    read, write just the routed value rather than clobber — but prefer the merge.
- **Deep only** (`:notion-writes` present):
  - **Type** `{:select {:name <type>}}`, **Effort** `{:select {:name <EFFORT>}}`,
    **Status** `{:status {:name <to>}}` (the `to` of `:status-transition`),
    **Task result** (title) `{:title [{:text {:content <enriched-title>}}]}`.
- Then the existing `tickets/complete! :triaged :applied` + best-effort facets refresh.

**owner → user-id map moves into nido** — a `def` next to the `Owner` enum in
`report.clj` (single source; the skill no longer carries ids):

```clojure
(def owner->user-id
  {:ataberk "3eb98667-d12e-4e9e-9342-48fec803b571"
   :eric    "955b4c25-7bce-4ca2-ab5e-d99acbcd423a"
   :jaap    "169d872b-594c-8160-b432-000250f98e86"})
```

**Failure semantics (important — this is the anti-silent-loss guarantee).** The Notion
property write is attempted first. Only on success does `apply!` `complete!` the record
and return `{:decision :applied}`. On a write failure (or missing token), `apply!` does
**not** complete the record — it returns `{:decision :notion-failed :error <kw>}`, leaving
the ticket parked so the human can retry. A failed Notion write must never leave the
ticket `:triaged`-but-unwritten — that is the exact bug being fixed.

A `:triage-report` with `:routing nil` (a Slack triage run) keeps the current nido-only
completion (no Notion write) — unchanged.

## 2. nido ports the deep description-callout prepend (kept)

The deep route keeps prepending the enriched-description callout above the reporter's
Notion body. This capability was never lost — the triage agent does it today via
`PATCH /v1/blocks/<page-id>/children` with `position: {type: "start"}`; only nido's own
client lacked a block-**write** helper (it reads via `retrieve-block-children`). We port
the skill's exact Step-4 mechanism into `nido.notion.client`:

- **New** `delete-block!` — `DELETE /v1/blocks/<block-id>` → `{:ok true}` | `{:error …}`.
- **New** `prepend-block-children!` — `PATCH /v1/blocks/<page-id>/children` with body
  `{:children [<callout>] :position {:type "start"}}` → `{:ok true}` | `{:error …}`.

In `apply!`'s deep branch, after the property writes:
1. **Idempotency guard** — read the page's first child (`retrieve-block-children`,
   `page_size` 1); if it is a callout whose text contains the marker
   `🤖 Enriched (triage BR-####)` for THIS BR, `delete-block!` it first (it's our own,
   never the reporter's content) so re-triage doesn't stack callouts.
2. **Prepend** the callout block: icon 🤖, rich-text = `"🤖 Enriched (triage BR-####)\n"`
   + the report's `:notion-writes :description-prepend`.
3. **Verify + warn** — re-read the first child; if it isn't our callout (Notion may strip
   `position` for the pinned Notion-Version, appending at the bottom instead), log a
   warning naming the ticket. This mirrors the skill's own Step-2c check.

**Callout is best-effort; properties are the gate.** The property writes (§1) decide
completion — a property-write failure returns `:notion-failed` and does not complete. The
callout is deep-only enrichment: a callout API failure or a bottom-landing logs a warning
but does **not** block completion (the routing/enrichment *properties* already landed, and
the enriched title + nido ledger carry the context). `apply!` returns `{:decision :applied
:callout :warn}` in that case so the degradation is visible without stranding the ticket.
This is a deliberate, minor simplification from the skill (which declined to complete
cleanly on a bottom-landing) — for a deterministic nido apply the properties are the real
outcome. Shallow routes have no callout.

## 3. `bb nido:ticket:apply` + skill Step 4 simplification

- **New task** `nido:ticket:apply :project <p> :br <BR>` — resolves the workstream owning
  `<BR>` and calls `work/apply!`, printing the decision. This is what chat-`apply` invokes.
- **Skill `triage-bug/SKILL.md` Step 4** — replace the raw `notion api PATCH` (routing
  write) and `notion page set` (deep properties) instructions with: on `apply`, run
  `bb nido:ticket:apply :br <BR>` — nido reads your typed report and writes Notion (Ball
  Holder + App Domain, deep properties, AND the deep callout prepend — all of it). Remove
  the owner→id table from the skill's Routing section (nido owns the ids now) and the raw
  `notion api PATCH` / `notion page set` / callout-`PATCH children` / `block delete`
  instructions from Step 4 (nido does them). The report still emits the semantic `:owner`
  keyword; the report schema and the routing/depth decision logic are unchanged.

Board Apply and chat-`apply` now both flow through `work/apply!` → one Notion-write impl.

---

## Non-goals
- Optimistic-concurrency (`last_edited_time`) re-check inside nido apply — deferred; the
  routing write is a small idempotent property set. Note it, don't build it.
- The nav / projects-filter fix — a separate follow-up spec.
- Retiring the skill's dormant "Source adapter (Slack)" branch — separate cleanup.

## Files touched
- `src/nido/coordinator/report.clj` — `owner->user-id` map (next to `Owner`).
- `src/nido/notion/client.clj` — `delete-block!` + `prepend-block-children!` (the block-
  write side, mirroring `retrieve-block-children`).
- `src/nido/work.clj` — `apply!` Notion-write branch + a `triage-report->notion-props`
  helper; App Domain additive-merge helper (mirror `notify.clj/merged-participants`); the
  deep callout idempotency-guard + prepend + verify-warn.
- `src/tasks/nido_ticket.clj` — `nido:ticket:apply` task.
- `.claude/skills/triage-bug/SKILL.md` — Step 4 → `bb nido:ticket:apply`; drop owner→id
  table + all raw Notion-write instructions (properties + callout).
- Tests: `work_test` (apply writes correct props shallow/deep; additive App Domain;
  owner→id; Notion-failure → non-completing `:notion-failed`; callout idempotency-delete +
  prepend + bottom-landing warn); `report_test` (owner->user-id covers the enum); a client
  test for the two new block-write fns if `notion.client` has a test home.
