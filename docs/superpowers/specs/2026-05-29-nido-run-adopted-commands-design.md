# nido:run — adopted target-project commands

**Date:** 2026-05-29
**Status:** Design approved; ready for implementation plan

## Problem

Nido's heavy local-CI orchestration (`src/nido/ci/`, `nido:ci:run`/`rerun`,
`specs/local_ci.allium`) was removed — it duplicated infrastructure that the
target project already owns. Brian, for example, has a single, mature local-CI
entry point: `bb ci` (fully Dockerized, parallel, self-contained, with an
agent-oriented `ACTION REQUIRED:` summary tail and a 0/1 exit code).

What's left missing is a way to **invoke the target project's own CI from a
nido-managed session**. A developer or agent working in a brian session should
be able to run brian's CI against that session's worktree without leaving nido
and without nido re-implementing any CI logic.

The desired end state (a later step) is a `/local-ci` affordance the in-session
agent invokes — "run CI, read the output, fix all findings." This spec covers
**step 1 only**: the callable primitive that runs the project's CI command. The
fix-findings loop layers on top of it later.

## Goals

- Expose a project's own CI command through nido, callable from a nido-managed
  session.
- Keep nido a **thin passthrough** — nido runs the command and propagates its
  exit code; it does not parse, orchestrate, sed, or interpret CI output.
- Make the mechanism **generic**: a structure for adopting any target-project
  `bb` (or shell) command, not a CI-specific feature.
- Keep it **per-project**: a command is available only where the project has
  declared it. Brian is the only project that declares CI today; other projects
  are unaffected and see nothing.
- **No TUI surface.** It is purely a callable `bb` task.

## Non-goals

- No "fix all findings" loop, no delegation to lane agents, no output parsing.
  That is a later step built on this primitive.
- No `/local-ci` skill or slash command in this step.
- No changes to the brian repo. Adoption config lives in nido-owned runtime
  config (`~/.nido/projects/brian/session.edn`).
- No reintroduction of nido-side CI orchestration, isolation, or service
  management.

## Design

### Reuse the existing command layer

Nido already has the generic structure this needs: `nido.commands`
(`src/nido/commands.clj`) is "a keyword-addressable layer over shell invocations
so nido can call project-specific tasks without knowing their shell form."
Commands are declared under `:project-commands` in a project's `session.edn` and
resolved by keyword via `run-command!`, which already supports `:cwd`, `:env`,
`{{ref}}` template substitution, `:out`/`:err` control, and `:continue?`.

This step adds **no new config noun and no new engine**. It adds:

1. one new `bb` task (`nido:run`) that runs a named `:project-commands` entry in
   a session's worktree context, and
2. one adoption entry in brian's nido config.

### Config: brian adopts its CI

In `~/.nido/projects/brian/session.edn`, alongside the existing
`:db/get-dump` / `:db/restore-into-template` entries:

```clojure
:project-commands
{;; … existing entries …
 :ci {:cwd "{{session.worktree}}"
      :cmd "bb ci --all-failures"}}
```

`--all-failures` runs every job and reports all failures (rather than
fail-fast), which is the right default for "show me everything to fix."

### Task: `bb nido:run :project <p> <session> <command-ref>`

New namespace `tasks.nido-run` (reusing the slot freed by the deleted
`tasks.nido-ci`), registered in `bb.edn` as `nido:run`.

Behavior:

1. Parse `:project <p>` plus the positional `<session>` (the standard
   nido arg shape) and a positional command ref. The ref is coerced to a
   keyword before lookup (`"ci"` or `":ci"` → `:ci`), since
   `:project-commands` keys are keywords.
2. Resolve the session's worktree path via
   `nido.session.lifecycle/worktree-path` (project dir comes from the project
   registry, `nido.config/read-projects`). If the worktree does not exist on
   disk, throw a clear error pointing the user to `bb nido:session:up` first.
3. Build a **session-scoped substitution context**:
   ```clojure
   {:project {:name <name> :dir <project-dir>}
    :session {:name <session> :worktree <worktree-path>}}
   ```
   This adds the `:session` layer that adopted commands template against
   (`{{session.worktree}}`). Existing template steps use `{{project.dir}}` and
   `{{template…}}`; this context is a superset for the session-run case.
4. Call `nido.commands/run-command!` with the project's `:project-commands`
   map, the ref, the session context, and opts
   `{:continue? true :out :inherit :err :inherit}`. Inherited IO streams the
   command's live output (including brian's `ACTION REQUIRED:` tail).
5. Exit the `bb` process with the child command's exit code (`System/exit`),
   so callers — and the future `/local-ci` wrapper — can detect pass/fail.

### Why this satisfies the constraints

- **Per-project / not over-exposed.** A command runs only if the project's
  `session.edn` declares it. An unknown ref produces the existing
  `"Unknown project-command: <ref>"` error with the available list. There is no
  `"brian"` string or CI knowledge in nido source — brian is simply the only
  project that has adopted a CI command.
- **No TUI.** `nido:run` is a plain `bb` task; nothing is added to
  `src/nido/tui.clj`.
- **No session-up dependency.** Brian's `bb ci` is fully Dockerized
  (own postgres/app) and path-isolated via its own `CI_SUFFIX`, so it only needs
  the worktree on disk and will not collide with nido's managed PG/JVM/app for
  that session. The task therefore works against a worktree regardless of
  whether the session's nido services are up.
- **Thin passthrough.** Nido resolves where to run and forwards the command;
  output and semantics belong entirely to the target project.

### Decisions made during design

- **Task name `nido:run`** (positional command ref). Considered `nido:cmd` and
  `nido:session:run`; `nido:run` is short and reads as "run an adopted command."
- **Reuse `:project-commands`** rather than introduce a separate `:commands`
  map. Keeps adopted CI next to existing declared commands with zero new
  structure. Accepted downside: the map mixes internal template steps (db
  restore, which need `{{template…}}` context) with session-callable ones. This
  is tolerable because internal steps templated against missing context simply
  fail loudly when run via `nido:run`; promoting a dedicated callable set can
  happen later if curation/discovery earns it.

## Affected files

- `bb.edn` — add `nido:run` task + require `tasks.nido-run`.
- `src/tasks/nido_run.clj` — new thin task namespace (worktree resolution,
  context build, `run-command!` call, exit-code propagation).
- `~/.nido/projects/brian/session.edn` — add the `:ci` entry to
  `:project-commands` (runtime config, outside the repo).
- (Optional) `src/tasks/nido_help.clj` — a one-line generic mention of
  `nido:run` in the help overview. Generic only; no brian/CI specifics.

## Testing

- Unit: given a fake project registry + `session.edn` with a `:project-commands`
  entry and an existing worktree, `nido:run` resolves the worktree, builds the
  expected session context, and invokes `run-command!` with the right ref/opts.
  Use a no-op/`echo` command so the test does not require Docker.
- Unit: unknown command ref → error listing available commands.
- Unit: missing worktree → error pointing to `session:up`.
- Exit-code propagation is verified at the `run-command!` boundary (mock the
  shell result); a full `bb ci` invocation is out of scope for automated tests
  (requires Docker) and is validated manually.

## Future steps (out of scope here)

1. `/local-ci` agent affordance: run `bb nido:run :project brian <s> :ci`, read
   the `ACTION REQUIRED:` tail, fix findings — delegating to brian's lane agents
   (`e2e-dev`, `lane-*`, `test-dev`) — and re-run until green or a budget is hit.
2. Optional trailing-arg forwarding (`bb nido:run … :ci -- --no-cache`).
