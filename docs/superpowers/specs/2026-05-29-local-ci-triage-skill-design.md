# /local-ci — run brian's CI, triage failures, fix on approval

**Date:** 2026-05-29
**Status:** Design approved; ready for implementation
**Builds on:** `2026-05-29-nido-run-adopted-commands-design.md` (the `bb nido:run` passthrough)

## Problem

`bb nido:run :project brian <session> ci` already runs brian's own CI against a
session worktree and streams its output (including brian's agent-oriented
`ACTION REQUIRED:` tail) with a 0/1 exit code. What's missing is the agent-facing
layer the user originally asked for: "run /local-ci, look at the output, and fix
all findings." This spec covers that layer as a Claude skill.

## Goals

- A `/local-ci` skill that runs brian's CI for a session, then **triages**
  failures into a structured, lane-grouped report.
- **Human-in-the-loop:** the skill makes **no edits** until the user approves;
  on approval it delegates fixes to brian's specialist agents.
- Keep nido a thin orchestrator — the skill is prose that drives the existing
  `bb nido:run` task and the brian agents already mirrored into nido. No new
  nido source code, no output-parsing logic in Clojure.

## Non-goals

- No auto-loop until green (each CI run is a slow Docker cycle).
- No auto-commit — fixes are left in the worktree for the user to review.
- No changes to the brian repo. The skill lives in nido; the only brian-side
  touch is the runtime `:ci` adopted-command config under `~/.nido/`.
- No nido Clojure code changes (the `bb nido:run` engine already exists).

## Decisions (from brainstorming)

- **Location / invocation context.** Native nido skill at
  `nido/.claude/skills/local-ci/SKILL.md`. Invoked by a claude running in the
  **nido repo** (cwd `~/Code/nido`), not by the in-session agent. Rationale: the
  session-home `.claude` resolves to brian's worktree `.claude`, so a
  nido-native skill is not visible in-session; but a nido-repo claude sees
  nido's `.claude` **and** has all of brian's lane/dev agents mirrored in (via
  `harness.edn :agents :all`), so delegation works from here. This honors
  "build in nido, not brian" (see memory `feedback_nido_owns_integrations`).
- **Autonomy.** Triage-only, fix-on-approval. No edits before the user says go.
- **`--no-cache`.** The `:ci` adopted command becomes
  `bb ci --no-cache --all-failures`. Brian's CI derives its Docker image cache
  key from git HEAD/diff; under jj the colocated git view can be stale, so a
  cache hit could run images built against the wrong tree. `--no-cache` forces
  clean rebuilds. Tradeoff: slower runs — acceptable for a manual, infrequent,
  triage-only command. This flag lives in the adopted-command config, not the
  skill, keeping the skill generic.

## Design

### Invocation

```
/local-ci <session>            ; project defaults to brian
/local-ci <project> <session>  ; explicit project (only brian declares :ci today)
```

The skill resolves `<project>` (default `brian`) and `<session>` from its
arguments. If `<session>` is omitted, the skill lists candidate sessions
(`bb nido:session:list :project <project>` or `~/Code/<project>-worktrees` /
brian's in-repo `.worktrees/`) and asks which one.

### Flow

1. **Announce + run.** Tell the user this is a slow, full Docker rebuild
   (`--no-cache`). Run `bb nido:run :project <project> <session> ci` via Bash,
   capturing combined stdout/stderr. No session-up required (brian's CI is
   self-contained Docker, path-isolated by its own `CI_SUFFIX`).
2. **Green path.** Exit 0 → report "CI green, nothing to fix." Stop.
3. **Triage.** On non-zero exit, read the output: identify failed jobs
   (`checks`, `unit`, `integ-{a,b,c}`, `e2e-{1,2,3}`) and the `ACTION REQUIRED:`
   tail. Build a **lane-grouped findings report** — for each failure: the job,
   the salient error lines, a proposed fix, and the **owning agent**, routed via
   brian's `docs/reference/agent-delegation.md` (mirrored/readable from nido).
   Starter routing (consult agent-delegation.md for anything ambiguous):
   - format / lint (clj-kondo) / lint-deps / shellcheck / css build / js build
     → mechanical; fix directly in the fix phase
   - i18n / translation → `translate-i18n` skill
   - migrations → `database-dev` (deploy/safety angle: `lane-db-deploy`)
   - Allium specs → `allium:weed`
   - unit / integration test failures → `test-dev`, or the domain `lane-*` keyed
     to the failing namespace
   - e2e → `e2e-dev`
   - version gates / partition-rebalance / anything unclear → surface to the
     user with the exact `ACTION REQUIRED` instruction; do not guess an owner
4. **Stop for approval.** Present the report and ask which findings to fix:
   all / a named subset / none. Make no edits.
5. **Fix phase (a later turn, on approval).** For each approved finding,
   dispatch its owning agent with a focused prompt: the worktree path
   (for brian, `~/Code/brian/.worktrees/<session>` — its `:worktrees-dir` is
   `.worktrees` relative to the project dir), the specific failing job, and the
   relevant error/test.
   Agents edit the worktree. **No auto-commit** — leave changes for review
   (subagents working in the worktree must follow its VCS hygiene; see memory
   `feedback_jj_subagent_commit_hygiene` if it is a jj worktree). After the
   fixes, suggest re-running `/local-ci <session>` to verify.

### Why these boundaries

- **Skill = prose orchestration only.** It calls `bb nido:run` and the mirrored
  agents; it does not parse CI output in Clojure or re-encode routing. The
  failure→owner mapping defers to brian's `agent-delegation.md` so it stays
  correct as brian's lanes evolve.
- **HITL by construction.** Steps 1–4 never edit; step 5 only runs after
  explicit approval. Matches the user's safety-first / chat-is-review-ui stance
  (memories `feedback_autonomous_safety_first`, `feedback_chat_is_review_ui`).
- **jj-specific concern isolated.** `--no-cache` lives in the `:ci` command def,
  not the skill — the skill works unchanged for any future project.

## Affected artifacts

- Create `nido/.claude/skills/local-ci/SKILL.md` — the skill (prose).
- Edit `~/.nido/projects/brian/session.edn` — `:ci` cmd →
  `bb ci --no-cache --all-failures` (runtime config, NOT committed).
- No nido Clojure changes. No brian-repo changes.

## Testing / verification

A prose skill has no unit tests. Verification is manual and structural:
- Frontmatter is valid (name, description with trigger guidance) and the skill
  is discoverable from a nido-repo claude (`/local-ci`).
- The command the skill forms is exactly
  `bb nido:run :project <project> <session> ci`.
- Dry structural check: against a real brian session, the run step invokes the
  command and the triage step has concrete grouping/routing instructions (no
  placeholders). A full green/red end-to-end is a real Docker run, validated
  manually when convenient — not part of routine checks.

## Implementation note

This is a single prose skill file plus a one-line runtime-config edit. It will
be authored directly following the `superpowers:writing-skills` conventions
rather than via a multi-task subagent plan (which would be overkill here).
```
