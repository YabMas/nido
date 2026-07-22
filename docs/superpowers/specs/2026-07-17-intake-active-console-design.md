# Intake / Active console — step 1 of the post-Notion UI re-envisioning

**Date:** 2026-07-17
**Status:** Design approved; ready for implementation plan
**Scope:** The web dashboard's `/workstreams` surface only (`src/nido/ui/`), plus the source/facet filtering it drives in `nido.work/screen` + `nido.ui.view-state`. No TUI change. No coordinator change. No storage migration.

## Problem

The Notion refactor moved the backlog, the record, and pick-next into Notion. What is left for nido is two jobs, and the user states them as:

> nido is "just" for helping with the task-intake via various streams, and for managing/orchestrating the work in-progress.

The model already agrees: `grouped-by-stage` (`workstreams_view.clj:265-275`) emits exactly four groups — `:incoming`, `:triage`, `:in-progress`, `:shipping` — having deliberately dropped `:ready` and `:done` because "the backlog lives in Notion, not on the nido board". That is intake (incoming + triage) and active (in-progress + shipping).

`/workstreams` has not followed. It still renders the pre-Notion shape: one undifferentiated pipeline list, sliced by a **source filter** (`source-row`, `views.clj:681-693`) and **facet chips** (`facet-rows`, `views.clj:695-711`). Those chips are a lens for grazing a large backlog — the job that moved to Notion.

### The filter is at war with the Active half

This is not a matter of taste. Measured against live data (2026-07-17, 35 open workstreams across 4 projects):

- `:in-progress` is **100% `:scratch`** — 8 rows in brian, 5 in nido, all hand-made worktree sessions. Zero Notion-origin tickets.
- `/workstreams` defaults to `source=notion` (`view_state.clj:21-24`), and there is deliberately **no cross-source "All"** (`view_state.clj:14-19`).

Therefore the default landing view renders:

```
brian | incoming 0 | triage-in-flight 5 | triage-queued 4 | IN-PROGRESS 0 | shipping 0
nido  | incoming 0 | triage-in-flight 0 | triage-queued 0 | IN-PROGRESS 0 | shipping 0
source-counts (the chips): {:notion 9, :github 0, :slack 1, :scratch 15}
```

Every project reads `IN-PROGRESS 0` while the chips advertise `Scratch (15)`. **The "managing/orchestrating work in-progress" half of nido's stated purpose is invisible by default**, hidden behind a filter chip set to the wrong value. The source filter and the Active band are mutually exclusive in practice.

Dropping the filter is therefore the fix, not cleanup.

### `:incoming` is a ghost

`:incoming` is the *passive* Slack holding pen — session-less rows awaiting human promote/dismiss (`intake.clj:12-24`, "Queue-mode intake"). The reaction-gated owl flow that superseded it uses `:intake :spawn` (`~/.nido/projects/brian/triggers.edn:59`), which spawns a triage session immediately and never creates one. Across all four projects there is **1 open `:incoming` workstream**, a leftover.

It is not worth a band of its own. It folds into Intake and will disappear on its own.

## Goals

- `/workstreams` presents nido's two actual jobs as two tabs: **Intake** and **Active**.
- Every open workstream is reachable from a default landing — nothing hidden behind a filter.
- Origin (Notion / GitHub / Slack / Scratch) is demoted from *filter* to *badge*: intake arrives via various streams and should be seen whole.
- Remove the surface's dependence on source/facet filtering, and the now-dead `source-counts` derivation with it.

## Non-goals

- **No TUI change.** The TUI keeps its own origin filter and facet selectors; `work/facet-match?`, `work/facet-dimensions`, `work/facet-values` therefore stay in `nido.work` (`tui.clj:244`, `:485-488`, `:1493`).
- **No change to the Gate Inbox (`/`).** Whether "needs you" becomes a filter rather than a place is a later step.
- **No scope fix.** The rail's scope selector is half-dead (every scope link hrefs back to `/`, so scope cannot be changed from `/workstreams`). Left broken-but-untouched to keep this diff narrow.
- **No facet-model removal.** Facets stay stamped on workstreams and refreshed by `facets.clj`; only the web chips go.
- **No new Notion truth-telling** (link-outs, staleness, ball-in-court, bare-row markers). Real, and separately identified, but a different step.

## Design

**The tab is a band selector, not a row filter.** This is the whole move. Today's chips filter rows by a *property* (source); tabs instead choose which part of the spine is on screen, and every row in that part renders regardless of origin.

| Tab | Bands | Live count today |
|---|---|---|
| **Intake** | `:triage` (in-flight / queued split preserved) + `:incoming` | brian: 10 (9 notion + 1 slack) |
| **Active** | `:in-progress` + `:shipping` | 15 (all scratch, currently unreachable by default) |

Each open workstream lands in exactly one tab. Union of the two tabs = every row `grouped-by-stage` emits. Nothing is filtered away by default.

- **Default tab: Intake.** Reads left-to-right and is the first job. It partly overlaps the Gate Inbox (5 of brian's 10 Intake rows are parked gates) — accepted for this step; the overlap is the subject of the follow-on "needs-you as state, not place" step.
- Tabs are cross-project, as the list already is.
- Sections within a tab keep their existing per-stage fold behaviour.

### IA and URL

```
GET /workstreams                  → Intake (default)
GET /workstreams?tab=active       → Active
GET /workstreams?tab=intake       → Intake (explicit)
GET /_fragment/workstreams?tab=…  → SSE, same tab
```

- `?source=` and `?facets=` (any unreserved key) are **dropped** from the URL vocabulary.
- `?sel=`, `?scope=`, `?entry=` and the `datastar` reserved param are unchanged.
- An unknown/absent `?tab=` falls back to `:intake` — mirroring how `parse` already degrades an unknown `?source=` to a default rather than rendering an empty list.
- A stale bookmark carrying `?source=scratch` renders Intake and ignores the param. No redirects; single user.

### Model changes

`nido.ui.view-state`:
- Delete `sources`, `default-source`.
- Add `tabs` `[:intake :active]` + `default-tab` (`:intake`), same first-entry-is-default idiom.
- `parse` returns `:tab` instead of `:source` + `:facets`. The `reserved` set collapses to `#{"scope" "sel" "entry" "datastar" "tab"}` — and with `:facets` gone, the "unreserved key becomes a facet filter" rule that motivated the `datastar` reservation (`view_state.clj:48-51`) disappears; `datastar` stays reserved defensively but the comment must be rewritten, not carried over stale.

`nido.work`:
- `screen` takes `:tab`, drops `:source`/`:facets` handling and the `:facet-dims` injection. It no longer filters `:groups` at all — it scopes them and returns them whole. Output loses `:source`, `:facets`, `:facet-dims`, `:source-counts`; gains `:tab`.
- Delete `source-counts`, `visible-pred`, `source-match?`, `filter-grouped`. Verified to have no callers outside `work` itself and its tests; the TUI filters origin with its own `filter-origin` (`tui.clj:207-210`). Deleted rather than kept for the TUI's eventual move onto `screen` (`feedback_no_speculative_primitives`).
- **Keep** `facet-match?`, `facet-values`, `facet-dimensions`, `grouped-rows` — live TUI callers (`tui.clj:244`, `:488`, `:99`/`:485`/`:1493`).
- **Drop the `[nido.ui.view-state :as view-state]` require** (`work.clj:33`). It exists only to borrow `default-source`/`sources` for the source filter; with those gone the model core no longer depends on a UI namespace. This is a layering inversion the step removes for free — the plan should assert `work` requires nothing under `nido.ui`.
- Add a band→tab projection — the single place the mapping lives, e.g. `tab-bands` returning the ordered `[stage rows]` pairs for a tab, so views and any future surface cannot disagree about which band belongs where.

`nido.ui.server`:
- `derive-screen` stops computing `facet-dims-for` and injecting `:facet-dims`; delete `facet-dims-for` (its only caller). This also removes a per-project `list-projects` scan from the `/workstreams` render path.

### View changes

`nido.ui.views`:
- Delete `source-row` and `facet-rows`. Delete `chip-link` if unused after (check: it is used only by those two).
- Add `tab-row` — two links styled as the existing `.chip`s or a new `.tab` pair, active tab highlighted, each carrying `screen-query` with a `{:tab …}` override so selection/scope survive a tab switch.
- `screen-query` drops `:source`/`:facets` serialization, gains `:tab` (emitted only when not the default, keeping `/workstreams` clean). Drop `enc-val` if it becomes unused.
- `ws-stage-sections` takes the tab and derives its `[stage rows]` list from `work`'s `tab-bands` rather than hardcoding the four-band order (`views.clj:507-510`). Its docstring's dangling reference to "the old stage-sections" and the unexplained "SCI reason" should be corrected or dropped while in there — this is a JVM httpkit server.
- `ws-list-row`'s docstring ("carries the full view-state (scope + source + facets)") updates to scope + tab.
- `workstreams-page` renders `tab-row` in place of `.filters`. The `.filters` wrapper div goes; keep the `.queue-col`/`.pane` two-column structure and the fold-signal chrome exactly as-is.
- CSS: prune `.filter-row` / `.filter-label` if unused after; add the tab rule. Do not touch unrelated dead CSS in this diff.

The ledger pane (`workstream-pane`), the 5s poll, the origin badge, ship substate, and the findings flag are untouched.

## Testing

Follows the existing pure-render + routing split in `test/nido/ui/`.

- `view-state/parse`: `?tab=active` → `:active`; absent → `:intake`; unknown (`?tab=bogus`) → `:intake`; a legacy `?source=scratch` parses without error and contributes no filter; `sel`/`scope`/`entry` still parse alongside `tab`.
- `work/screen`: given fixture groups, `:tab :intake` yields triage + incoming rows only; `:tab :active` yields in-progress + shipping only; **union of both tabs = every row in the input** (the no-row-hidden guarantee — the regression test for this whole design); `scope` still filters; `:source-counts`/`:facet-dims` absent from the output.
- `tab-bands`: band order within each tab; empty bands dropped.
- Views: `workstreams-page` renders exactly two tabs, the active one marked; **no `.filter-row` / no `Source` label renders**; a scratch in-progress row renders on Active; tab links preserve `?sel=` and `?scope=`.
- Routing: `/workstreams` and `/workstreams?tab=active` render 200; `/_fragment/workstreams?tab=active` returns SSE patching `#workstreams`.
- A non-vacuous fixture is required for the union test — at minimum one row per band, including a scratch `:in-progress` row (the case the current default hides).
- `work` requires nothing under `nido.ui` (guards the layering fix).

Existing tests that must be deleted or rewritten (they assert the behaviour this step removes):

- `test/nido/work_test.clj` — `screen-source-counts-include-incoming-under-its-source` (`:821`), `screen-source-counts-under-selected-source` (`:843`), the `source-match?` deftest (`:682`), `filter-grouped-keeps-shape-drops-nonmatching` (`:693`). `facet-values-distinct-plus-unclassified` (`:646`) **stays** — the TUI still uses it.
- `test/nido/ui/views_test.clj` — the filter-bar tests (`:249`, `:261`, `workstreams-filter-bar-renders-facet-values-from-rows` `:266`, `screen-query-encodes-facet-values` `:282`) and the `:source-counts` keys in screen fixtures (`:194`).
- `test/nido/ui/view_state_test.clj` — the `default-source` assertion (`:9`) and `parse-url-decodes-facet-values` (`:32`).

## Rollout

Single in-place change; the daemon serves the dashboard in-process, so `bb nido:coordinator:restart` picks it up. No data migration. Land on main and push per project convention (no PR).

## Follow-on steps (not this design)

Named so this step is not mistaken for the whole re-envisioning:

1. **Needs-you as state, not place** — dissolve the Gate Inbox's duplication (a parked triage row currently appears both at `/` and in the triage band) by making needs-you a marker + filter across both tabs.
2. **Notion truth-telling** — the model computes and discards: `notion-driven?` (`workstreams_view.clj:142`, never returned), `:ball-ids` (`notion_cache.clj:31`, zero consumers), `:notion-priority` (orders the queue, never shown), `:bare?` (one write, no reads), and the `ExternalRef` `:url` that would link back to the ticket. Plus the pane's status card shows nido's *local* `tickets/read-meta` status while the row's stage derives from the *Notion* cache status — two sources free to contradict on one screen.
3. **Staleness** — `:last-polled-at` is written (`sources/notion.clj:121`) and never read; a row from a 3-day-old snapshot is indistinguishable from a fresh one.
4. **TUI** — bring it onto `work/screen` + `work/gate-actions` (it currently ignores both, and offers Dismiss on Notion triage rows where the model says it shouldn't), then apply the same Intake/Active cut.

### Two model forks to settle before they reach a UI

Out of scope here, but they will bite the steps above:

- **The spine has forked.** `work/stages` says `:intake`; `session/lifecycle-stages` says `:incoming` (`work.clj:38` vs `session.clj:188`). `work/gate-actions` dispatches on `:incoming` — a stage absent from `work`'s own `stages` vector.
- **Two Notion→stage mappings disagree on "Review".** `notion_sync.clj:22-24` gives stage `:done` but leaves it open; `session.clj:232` treats it as terminal. Same ticket, two answers depending on which path asks.

### Superseded

`2026-06-22-webui-sitemap-navigation-redesign.md:84` specifies the Workstreams surface as grouped `inbox → triage → ready → in-progress`. That describes a board the code no longer builds: it includes `:ready` (now Notion-owned backlog) and omits `:shipping`. Implementing it as written would undo the two-flows split. **This document supersedes that section.**
