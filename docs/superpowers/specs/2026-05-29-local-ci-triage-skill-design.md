# /local-ci — run brian's CI in-session, triage failures, fix on approval

**Date:** 2026-05-29 (revised same day after the location decision)
**Status:** Design approved; ready for implementation
**Builds on:** `2026-05-29-nido-run-adopted-commands-design.md` (the `bb nido:run` passthrough)

## Problem

`bb nido:run :project brian <session> ci` already runs brian's own CI against a
session worktree (using the `--no-cache --all-failures` `:ci` command) and
streams its output, including brian's agent-oriented `ACTION REQUIRED:` tail.
What's missing is the agent-facing layer: "run /local-ci, look at the output,
and fix all findings." This spec covers that as a Claude skill the user invokes
**from inside a session, with no arguments**.

## Goals

- A `/local-ci` skill invokable **from the session home** with **no arguments** —
  the session is implicit from the working directory.
- It runs brian's CI for that session, **triages** failures into a structured,
  lane-grouped report, and makes **no edits until the user approves**; on
  approval it delegates fixes to brian's specialist agents.
- nido owns the skill (built in nido, per `feedback_nido_owns_integrations`) and
  delivers it into sessions via the launcher.

## Key facts that shape the design

The in-session agent runs with cwd = the **session home**
(`~/.nido/sessions/<project>/<session>/`), which the launcher provisions with:

- `bb.edn` → a symlink to **nido's** `bb.edn` (`ensure-bb-edn-symlink!`). So
  `bb nido:run …` **works from the session home** — that is how the skill runs
  CI. (From the worktree, `bb` resolves brian's `bb.edn` and `nido:run` is
  absent — so the skill is a session-home tool, not a worktree tool.)
- `.claude` → currently a single symlink to the worktree's `.claude` (brian's).
  So brian's lane/dev agents are available in-session, but **a nido-owned skill
  is not visible** — this is what the launcher change fixes.

The session home path encodes the project and session, so the skill derives
both from cwd — no arguments needed.

## Design

### Part 1 — Launcher: compose the session-home `.claude`

Replace `ensure-claude-symlink!` (which makes `.claude` a single symlink to the
worktree's `.claude`) with a composition that makes `.claude` a **real
directory**:

- For each top-level entry of the worktree's `.claude` **except `skills/`**
  (`agents/`, `commands/`, `settings.json`, `dev-rules.md`, `projects/`, …):
  create a relative symlink `<.claude>/<entry> → ../worktree/.claude/<entry>`.
  (Relative, through the session home's `worktree` symlink, so a moved worktree
  is still followed — preserving today's behaviour.)
- `skills/` becomes a **real directory** that symlinks:
  - each of the worktree's skills:
    `<.claude>/skills/<name> → ../../worktree/.claude/skills/<name>`, plus
  - each of nido's **native** harness skills (real, non-symlink dirs under
    `nido/.claude/skills/`, e.g. `local-ci`):
    `<.claude>/skills/<name> → <nido-source>/.claude/skills/<name>` (absolute).
    Mirrored brian skills (symlinks in nido's tree) are skipped — the session
    already gets brian's skills directly.

Result: in-session the agent sees brian's full `.claude` *and* nido's injected
skills; brian's worktree is untouched; it refreshes each `session:up`.

**Safety (critical):** when rebuilding, if the existing `.claude` is a **symlink**
(today's form), remove only the link (`fs/delete`) — never `delete-tree`, which
would follow into and destroy brian's real `.claude`. Only a previously-composed
real `.claude` (containing our own symlinks) is `delete-tree`d, and that deletes
the link entries, not their targets. A regression test must prove a rebuild
leaves the worktree's source `.claude` and its skills intact.

**Testable seam:** a `compose-claude-dir! [home nido-native-skill-dirs]` helper
does the filesystem work against explicit paths; `nido-native-skill-dirs []`
returns the absolute paths of nido's native (non-symlink) skill dirs. The
launcher wires them together and calls it from `write-artifacts!` where
`ensure-claude-symlink!` is called today.

### Part 2 — The skill (`nido/.claude/skills/local-ci/SKILL.md`)

Invoked in-session as `/local-ci` (no args). Flow:

1. **Resolve session from cwd.** Expect cwd to be a session home
   (`~/.nido/sessions/<project>/<session>/`); parse `<project>` and `<session>`
   from the path. If cwd is not a session home (e.g. the user is in the
   worktree or the nido repo), say so and tell them to run it from the session
   home (`bb nido:session:enter …` lands there).
2. **Run.** Warn it's a slow full Docker rebuild (`:ci` uses `--no-cache` for jj
   correctness), then run, capturing output:
   `bb nido:run :project <project> <session> ci`. No `session:up` needed —
   brian's CI is self-contained Docker.
3. **Green path.** Exit 0 → "CI green, nothing to fix." Stop.
4. **Triage.** Enumerate failed jobs (`:check`, `:unit`, `:integ-{a,b,c}`,
   `:e2e-{1,2,3}`) + the `ACTION REQUIRED:` tail; separate real failures from
   flake/infra; produce a lane-grouped report (job, error lines, proposed fix,
   owning agent routed via brian's `docs/reference/agent-delegation.md`).
5. **STOP for approval.** Present the report; ask which to fix (all/subset/none).
   Make no edits — the approval gate holds even for "obvious"/"trivial" fixes.
6. **Fix (on approval).** Delegate each approved finding to its owning brian
   agent (present in-session) with the worktree path + failing job + error.
   Mechanical fixes done directly. No auto-commit. Then suggest re-running
   `/local-ci`.

Routing table and the approval-gate rationalization table are as in the prior
revision (format/lint → direct; i18n → translate-i18n; migrations →
database-dev/lane-db-deploy; Allium → allium:weed; unit/integration → test-dev
or domain lane; e2e → e2e-dev; version gates/partition/unclear → surface).

## Non-goals

- No auto-loop to green (each run is a slow Docker cycle); one run, re-run on
  request.
- No auto-commit — fixes left in the worktree for review.
- No brian-repo changes. brian's `:ci` adopted command lives in nido runtime
  config (`~/.nido/projects/brian/session.edn`, already set to
  `bb ci --no-cache --all-failures`).
- The skill is session-home-only by construction (it is injected into the
  session-home `.claude`, and relies on the session-home `bb.edn` for
  `bb nido:run`). Not a worktree tool.

## Affected artifacts

- `src/nido/session/launcher.clj` — replace `ensure-claude-symlink!` with the
  composition (`compose-claude-dir!` + `nido-native-skill-dirs` + wiring).
- `test/nido/session/launcher_test.clj` — composition structure, skill merge,
  native-only detection, and the rebuild-safety regression test.
- `nido/.claude/skills/local-ci/SKILL.md` — rewrite for in-session/no-args.
- (Already done) `~/.nido/projects/brian/session.edn` — `:ci` with `--no-cache`.

## Testing / verification

- Launcher: unit tests for structure, skill merge, native-only skip, and the
  **rebuild-never-deletes-source** safety test (compose twice; assert the
  worktree's source `.claude`/skills survive).
- Skill: re-verify with a subagent that (a) it resolves project/session from a
  session-home cwd, (b) runs `bb nido:run … ci`, and (c) the approval gate holds
  under pressure ("just fix the trivial stuff directly"). A full Docker run is
  validated manually.
- Manual end-to-end: `session:up` a brian session, inspect the composed
  `.claude` (brian entries symlinked, `skills/` merged incl. `local-ci`),
  confirm `/local-ci` is discoverable from the session home.

## Implementation note

Part 1 (launcher) is real Clojure with a deletion-safety edge — implemented with
TDD and reviewed. Part 2 (skill) is prose, authored per `writing-skills`
conventions and re-verified with a subagent.
