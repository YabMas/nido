---
name: align
description: Rebase the current session's branch onto a fresh origin/main and auto-resolve ONLY trivial conflicts (import/require/ns unions, lockfiles & generated files), halting on any semantic conflict. Pure VCS operation — no commit, no ledger events. Run from a session worktree. Usage: /align
---

# /align

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/align/`
> and is injected into every spawned session's composed `.claude/skills/`.
> Sibling of `/local-ci`, `/squash`, and `/drive-home` — the rebase phase
> `/drive-home` composes.

## What this is

Rebase the current session's branch onto a freshly-fetched `origin/main` and
resolve **only** mechanically-unambiguous conflicts. It is a **pure VCS
operation**: it does not commit, it does not touch the coordinator ledger, and
it **halts** the moment a conflict needs real judgement. `/drive-home` (or you,
interactively) decides what to do with a halt.

## Where to run it

All work happens **inside the worktree**. If you're in a session home
(`…/.nido/sessions/<project>/<session>/`), `cd worktree` first. Sanity-check
you're in a jj worktree:

```bash
jj root   # errors if not a jj repo → stop and tell the user to run from the worktree
```

## Rebase onto origin/main

From the worktree:

```bash
jj git fetch
jj rebase -b @ -d 'trunk()'
```

`-b @` rebases the whole branch containing the working copy onto the freshly
fetched trunk — no bookmark name needed. `trunk()` resolves to the remote trunk
whatever the default branch is called, so nothing here hardcodes `main`. If the
branch is already on top of it, jj reports "Nothing changed" — that's fine,
continue.

Then check for conflicts:

```bash
jj resolve --list   # lists conflicted files; see below on the clean case
jj st               # also flags "There are unresolved conflicts"
```

**Read the listing, not the exit code.** When there are no conflicts,
`jj resolve --list` *errors*: exit 2, `Error: No conflicts found at this
revision`, empty stdout. That is the **success** case. An agent branching on exit
status here reads a clean rebase as a failure. `jj st` is the unambiguous
cross-check: it prints "There are unresolved conflicts" only when there are.

## Conflict policy — auto-resolve trivial, HALT on semantic

jj records conflicts first-class (in the commit), so you classify *after* the
rebase. For each conflicted file, inspect the conflicted regions (`jj resolve
--list`, then read the markers in the file).

**Auto-resolve — ONLY these mechanically-unambiguous cases:**
- both sides only **added import/`require`/`ns`-block lines** → union them.
- **lockfiles / generated files** (e.g. `package-lock.json`, generated EDN/CSS,
  i18n `.pot`) → regenerate via the file's own regen command, or take the union.

(Hunks jj merged cleanly on its own never appear in `jj resolve --list` — there is
nothing to classify, so a shorter-than-expected list is normal, not a problem.)

**HALT — everything else.** If a conflicted region overlaps *any* line of real
logic, or you cannot resolve it by a purely mechanical rule, **stop**: report the
conflicted files and which regions are semantic, and tell the user to resolve
them and re-run `/align` (or `/drive-home`). A wrong auto-resolution silently corrupts behavior
in ways CI won't catch — when unsure, halt. Never guess at logic.

### Reporting a conflict in a stack

When the session is a stack, name the layer that conflicted, not just the file.
`jj resolve --list` lists conflicted **files** in one commit — it never says
*which* commit, so it cannot on its own tell you the layer. Ask jj which changes
carry conflicts, with the `conflicts()` revset:

```bash
jj log -r 'trunk()..@ & conflicts()' --no-graph \
  -T 'change_id.short() ++ " " ++ bookmarks ++ " | " ++ description.first_line() ++ "\n"'
jj log -r 'trunk()..@' --no-graph -T 'change_id.short() ++ " " ++ bookmarks ++ " | " ++ description.first_line() ++ "\n"'
jj resolve --list   # the conflicted files, within the conflicted change
```

The first command names the conflicted change(s) — with the layer bookmark, when
one sits there. The second gives the full layer list, so you can compute the
layer's `[n/N]` position. The third gives the files.

Report "layer `<slug>` (`[n/N]`) conflicts in `<file>`". A conflict in a lower layer
usually means the stack needs reshaping, not just a merge resolution — the
foundation moved under the layers above it.

After resolving trivial conflicts (`jj resolve` or editing the files), confirm
`jj resolve --list` lists nothing before continuing — which, once everything is
resolved, means it exits 2 with `Error: No conflicts found at this revision`.

## What this skill does NOT do

- **No commit.** It leaves the resolved (or halted) worktree as-is; the next
  phase (`/local-ci auto`) commits a clean tree before CI.
- **No ledger events.** On a halt it reports the semantic conflict and stops;
  `/drive-home` is what records the blocker in the coordinator ledger when it
  composes this skill.
- **No push.**

## Common mistakes

- **Auto-resolving a semantic conflict** — guessing at logic. Halt instead.
- **Treating `jj resolve --list`'s non-zero exit as a failure** — the clean case
  exits 2 with `Error: No conflicts found at this revision`. Read the listing.
- **Hardcoding `main@origin`** — use the `trunk()` revset.
- **Running from the session home** — it's not git-colocated; `cd worktree` first.
- **Committing the resolution** — `/align` never commits; leave the worktree for
  the next phase.
