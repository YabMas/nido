# Triage routing model — design

**Status:** approved-in-conversation, pending written review
**Date:** 2026-07-17
**Supersedes intake behavior from:** the Notion-as-work-backend arc (auto-triage of the
whole backlog) and the half-wired board/TUI `dismiss` for Notion tickets.

## Problem

Two symptoms the user hit on the live board — no working `dismiss` on the Intake
queue, and a TUI `dismiss` that didn't change anything on the web — traced to one
fact: for a Notion-backed row, the board re-derives its stage from the Notion cache
and **ignores** the local `:dismissed` disposition (`session.clj:261`,
"Distinct from :dismissed, which we still ignore"). So `dismiss` on a Notion row is
a no-op on visibility everywhere; it only stops the auto-triage gate from re-spawning.

Rather than repair `dismiss`, the user reframed the intake model:

- **nido owns intake** — task-intake via streams + orchestrating work in progress.
- **Notion owns the backlog** and the pick-next decision.
- Automated intake should therefore be **one narrow stream, not the whole backlog**,
  and its outcome should be a **routing decision** — get every new report to the right
  owner — not necessarily a full analysis.

## Goal

Reduce automated intake to the **new-reports** Notion view only, and redefine the
triage outcome as *route the report to an owner*, with the **depth of triage
determined by who it routes to**. Retire `dismiss` for Notion tickets.

---

## 1. Scope reduction (config)

`~/.nido/projects/brian/triggers.edn`:
- **Remove** the `:triage-teacher-bugs` trigger (the backlog auto-re-triager).
- Keep `:triage-new` (new-reports), `:smoke-new-reports`, `:triage-slack-reactions`,
  `:video-provider-alerts`, `:plan-bug`, `:plan-github-issue`.

`~/.nido/projects/brian/notion-views.edn`:
- `:board-views` `[:teacher-bugs :new-reports]` → `[:new-reports]`, so the Intake
  radar shows only new-reports rather than a flood of untriaged backlog bare-rows
  that nothing triages anymore.

Result: the only view that auto-provisions triage sessions is `new-reports`
(`Status = "Needs verification"`).

> **Observation, out of scope:** `:smoke-new-reports` (a 30s `:investigate-bug`
> canary) also watches new-reports. Left unchanged this round; flag if the double
> watch on one view is unwanted.

---

## 2. Team + routing table

Three developers, routed by area of expertise. Owner → Notion Ball Holder (people)
and App Domain (multi_select). IDs harvested from the live Task DB Ball Holder values.

| Owner | Notion user id | Area of expertise | App Domain |
|---|---|---|---|
| **Ataberk Koroglu** | `3eb98667-d12e-4e9e-9342-48fec803b571` | Mobile app = the **Student** environment | `Student` |
| **Eric Dvorsak** | `955b4c25-7bce-4ca2-ab5e-d99acbcd423a` | Ops; large architecture / design-pattern concerns; **auth (backend, not mobile)**; **external integrations** (Canvas, Entra, …) | `Backend` |
| **Jaap Maaskant** | `169d872b-594c-8160-b432-000250f98e86` | Most **Teacher**-environment features; general backend | `Teacher`, or `Backend` for general backend |

**The one real judgment — the Backend split.** Anything about authentication / login /
SSO / Entra / Canvas / external-system integration / large architectural or
design-pattern concerns → **Eric**. Any other backend work → **Jaap**. When genuinely
unsure which side of the split a report falls on, route to **Jaap** and say so in chat.

The owner→id mapping lives in the **triage-bug skill** (it owns the Notion write); the
typed report carries only the semantic `:owner` keyword (see §4).

---

## 3. Depth policy

Depth follows routing:

- **Shallow (routed to Ataberk or Eric).** Classify the area, set **Ball Holder +
  App Domain, and nothing else**. No solution directions, no effort, no enriched
  title/description, no theories the agent can't verify from the codebase. **Notion
  Status stays "Needs verification"** — the owner picks it up from there. The agent
  may read the report and take a light look to *confirm the area*, but does not
  root-cause.
- **Deep (routed to Jaap).** The current fuller triage: investigate the codebase,
  propose 1–3 directions + effort (or `:squirrel`), enrich the title and prepend the
  enriched-description callout, and transition **Status → "Not started"**.

**Every new report routes to a human owner — there is no "off-radar without an owner"
outcome.** A report the agent reads as not-a-bug / noise / needs-info is not dismissed;
it routes to **Jaap** (default owner), Status stays "Needs verification", and Jaap
dispositions it in Notion. This is what replaces `dismiss` for Notion: nothing gets
silently hidden; everything gets an owner.

**HITL for every route.** The agent *proposes* the routing (shallow or deep) and halts
at `:awaiting-input`; the human acks with `apply` in chat before any Notion write.
Shallow routes are trivially approvable but still pass the human — the user explicitly
wants to ack every routing.

**Radar exit.** On `apply`, nido's ledger marks the record `:triaged`
(`bb nido:ticket:complete … :status triaged`). The board projects a `:triaged` row to
`:ready`, which is omitted from the Intake surface — so the ticket leaves the radar for
both depths. Because the pre-spawn gate keys dedup on the **nido** record status
(`tickets.clj` → `:triaged` ⇒ `:skip-completed`), a shallow ticket that keeps Notion
Status "Needs verification" is **not** re-triaged by the `reconcile? true` poller.

---

## 4. Skill + schema changes

### 4a. `TriageReport` schema (`src/nido/coordinator/report.clj`)

Add routing as a first-class, required field; make the deep-only analysis optional so a
shallow report validates without it.

```clojure
(def AppDomain [:enum "Student" "Teacher" "Backend" "Misc"])
(def Owner     [:enum :ataberk :eric :jaap])

(def Routing
  [:map {:closed true}
   [:owner      Owner]          ; → Ball Holder people id (mapped skill-side)
   [:app-domain AppDomain]      ; → App Domain multi_select value
   [:depth      [:enum :shallow :deep]]])
```

`TriageReport` gains `[:routing Routing]` (required). `:directions` becomes
`{:optional true}` (omitted/empty for shallow). `:notion-writes` is reshaped so the
always-written routing fields are separate from the deep-only enrichment:

```clojure
(def NotionWrites
  [:map {:closed true}
   [:ball-holder        [:maybe string?]]   ; owner user-id; nil for Slack
   [:app-domain         [:maybe string?]]   ; nil for Slack
   ;; deep-only, all optional:
   [:type               {:optional true} [:maybe string?]]
   [:effort             {:optional true} TriageEffort]
   [:status-transition  {:optional true} [:maybe [:tuple string? string?]]]
   [:title              {:optional true} string?]
   [:description-prepend {:optional true} string?]])
```

(Exact malli finalized in the plan; `report.clj`'s `triage->markdown` renderer and any
fixtures/tests that build a `TriageReport` update accordingly.)

### 4b. Notion writes (skill `apply` step)

- **Always** (both depths): set **Ball Holder** (people, replace) from `:owner`, and
  ensure **App Domain** (multi_select) includes the routed value (additive — do not
  clobber existing tags). Ball Holder + multi_select go via `notion api PATCH`
  (the `notify.clj` on-promote pattern: `{"Ball Holder" {:people [{:id …}]}}`).
- **Deep only:** the existing `notion page set` for Type / Effort / Status
  ("Needs verification" → "Not started") / enriched `Task result` title, plus the
  enriched-description callout prepend.
- **Shallow:** no Type, no Effort, no Status write, no title/description enrichment.

### 4c. System prompt (`.claude/skills/triage-bug/SKILL.md`)

- Add a **Routing** section: the table (§2), the owner→id map, the Backend-split
  heuristics, and the "unsure → Jaap, say so" rule.
- Rewrite **"Lifecycle / triage outcome"**: the outcome is a routing decision; depth
  follows the owner (§3). Replace the "two terminal outcomes: apply | dismiss" framing —
  for Notion there is now **one** terminal outcome, `apply` (route). Every new report
  gets an owner; not-a-bug/needs-info routes to Jaap.
- Update the **report schema** block and the **Step 4 apply** block to the §4a/§4b shape.
- Keep the video-transcript / preprocess guidance and the optimistic-concurrency check.

---

## 5. Retire `dismiss` for Notion tickets

`dismiss` stays **Slack-only** (ledger-only, where it actually works). Removed from the
Notion path:

- **Skill (`triage-bug/SKILL.md`):** drop `dismiss` from the Notion verb table and the
  Step 5 Notion branch. Slack keeps its ledger-only `dismiss`. (`work/dismiss!` and
  `tickets/dismiss!` stay — Slack uses them.)
- **TUI (`src/nido/tui.clj` `dismiss-selected`, bound to `x`):** gate on origin. For a
  `:notion` row, no-op with an honest status line ("dismiss doesn't apply to Notion
  tickets — they're owned in Notion"); keep the action for `:slack`/`:scratch` rows.
  Update the board keybar hint accordingly.
- **Board (`src/nido/work.clj` `gate-actions`):** already omits Dismiss for `:notion`
  triage rows — no behavior change. Simplify the now-permanent `:notion` conditional and
  refresh the docstring/comment to state that Notion dismiss is retired by design.

No change to `notion-stage-projection`'s deliberate ignoring of `:dismissed` — with
dismiss retired for Notion, that path is simply never exercised by a Notion row.

---

## Non-goals (this round)

- The follow-on UI steps from the Intake/Active spec (needs-you as state; Notion
  truth-telling; staleness; the TUI's own Intake/Active cut).
- Auto-apply for shallow routes — the user wants to ack every routing.
- Any writeback that closes a not-a-bug ticket automatically — it routes to Jaap, who
  dispositions it in Notion by hand.
- Touching `:smoke-new-reports`.
- Backfilling / re-triaging the existing teacher-bugs backlog — dropping the trigger
  simply stops future auto-triage; existing records are untouched.

## Files touched

- `~/.nido/projects/brian/triggers.edn` — remove `:triage-teacher-bugs`.
- `~/.nido/projects/brian/notion-views.edn` — `:board-views` → `[:new-reports]`.
- `src/nido/coordinator/report.clj` — `Routing`/`Owner`/`AppDomain`, `TriageReport` +
  `NotionWrites` reshape, renderer.
- `.claude/skills/triage-bug/SKILL.md` — routing section, outcome/lifecycle rewrite,
  schema + apply blocks, dismiss removal.
- `src/nido/tui.clj` — `dismiss-selected` origin gate + keybar hint.
- `src/nido/work.clj` — `gate-actions` `:triage` simplification + docstring.
- Tests: `report` schema/renderer, `work` gate-actions, `tui` dismiss (as they exist).
