# Triage: write enriched title + description to Notion

**Date:** 2026-06-12
**Scope:** `nido/.claude/skills/triage-bug/SKILL.md` only. No code changes.

## Problem

Today the triage-bug skill deliberately keeps the enriched narrative out of
Notion — only Type/Effort/Status are written, and the reporter's original
title/description are left verbatim. The enriched description lives only in the
nido ticket record. We want the bot, on `apply`, to also write an **enriched
title** and prepend an **enriched description** to the Notion page, while never
destroying the reporter's original words.

This reverses the current "never touch the title, description body, or comments"
hard contract — intentionally, and only for the title property and a prepended
description block. The HITL safety gate (no Notion write before a human `apply`)
is unchanged.

## Decisions

- **Layout:** enriched description on **top** (prepended), reporter's original
  body preserved **intact below** it. Non-destructive.
- **Title:** always propose an enriched title; keep it **identical** when the
  original is already good. Concise, no `BR-####` prefix, no trailing
  punctuation.
- **Timing:** title + description writes happen **only on `apply`**, after the
  existing optimistic-concurrency re-check. Same HITL hard contract. `cancel` /
  `skip` / `redo` branches unchanged.
- **Override grammar:** `apply: title="..."` already exists and keeps working.
  No new description-override grammar — description tweaks go through `redo:`.

## Mechanics

### Prepend is native

The Notion REST append-children endpoint supports
`position: { "type": "start" }`, which inserts blocks at the **beginning** of
the page's children — no deletion of the original body. This is the clean,
non-destructive prepend.

**Residual risk:** the nido Notion MCP tool `API-patch-block-children` only
*declares* the deprecated `after` parameter in its schema, not `position`. Its
top-level schema does not forbid extra params, so `position` will most likely
be forwarded to the REST API — but this is unverified against a live page.
Hence the verification step below.

### Enriched block = one callout

Prepend a single **callout** block (🤖 icon) holding the enriched description,
marked with the `BR-####` id in its text so it is identifiable and locatable.
One block → visually distinct, trivial to find for the idempotency guard.

### Idempotency guard

A ticket can hit both triage triggers (`:triage-new` then `:triage-backlog`).
Before prepending, check whether a prior enriched callout (our `BR-####`
marker) already sits at the top of the page. If so, **delete that one block**
first (it is *our* block, never the reporter's content) and prepend a fresh
one. No stacking of enriched callouts.

### Post-write verification

After prepending, re-read the page's first child block and confirm the enriched
callout is at index 0. If it is not (the MCP silently stripped `position` and
appended at the bottom), **warn in chat and log it** rather than leaving the
page in a wrong state. Treated like a partial-write: do not mark the record
terminal cleanly without surfacing the problem.

## SKILL.md edits

1. **Step 2 report template (§1):** add an `**Enriched title:** <text>` line
   above the enriched description.
2. **Step 2 report template (§3):** expand "Proposed Notion writes" to also list
   **Title** (property) and **Description** (prepended callout), so the human
   reviews both before `apply`.
3. **Step 4 (apply):** add two writes after the concurrency check —
   - Title via `API-patch-page` (title property → enriched title).
   - Description via `API-patch-block-children`, `position: {type: "start"}`,
     a `BR-####`-marked callout, preceded by the idempotency-guard delete and
     followed by the post-write verification.
4. **Step 4 audit log:** extend to `writes=type,effort,status,title,description`.
5. **Safety-contract prose:** update the "never touch the title, description
   body, or comments" lines (Step 4 intro, the Note-on-safety section, and the
   §3 parenthetical in Step 2) to reflect the new policy: title + a prepended
   enriched callout ARE written on `apply`; comments and the reporter's original
   body remain untouched.

## Out of scope

- No change to triggers, coordinator, ticket-record CLI, or any `.clj` code.
- No description-override chat grammar.
- Comments are still never written.
