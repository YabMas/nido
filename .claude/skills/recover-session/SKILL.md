---
name: recover-session
description: Recover sessions that failed to start. Fired by the :session-recovery trigger on project nido, once per cause of kept start failures. Diagnoses the cause as a nido defect, a project defect or a one-off; lands a nido fix through a gated verb, opens a PR for a project defect, remediates a one-off; then restores every failed session and what was waiting on it. Fully autonomous; parks only when restoring would destroy shared or uncommitted state.
---

# /recover-session

> **Harness-side skill, owned by nido.** Fired by the `:session-recovery`
> trigger (`:source {:type :session-failure}`) on project `nido`, whatever
> project the failed sessions belong to.

One or more session starts failed with the same cause. nido kept each failure —
the error chain, the tails of the services' logs, what was being started and on
whose behalf — and fired you. Your job, in order: find out **why**, repair it,
and **bring every one of those sessions back**. Nobody is watching; the safety
comes from the verbs below refusing what you must not do, and from the rules
you do not relax.

## The rules that are not yours to relax

1. **Diagnose before you act.** `bb nido:recovery:restore` and
   `bb nido:recovery:land` refuse on a workstream with no `:session-diagnosis`,
   and a recovery Run that ends without one of its own is failed whatever it
   reports.
2. **You never push.** `bb nido:recovery:land` is the only way a nido fix
   reaches main: it runs `bb nido:test` and `bb nido:land:check` at the exact
   tip it pushes and refuses otherwise. `jj git push` is not yours to run.
3. **You never land in a target project.** A defect in brian (or any project
   nido drives) ends in a ready-for-review PR in that project. Merging it —
   locally, with `gh pr merge`, or with `nido ship` — is not yours.
4. **Destroy only what belongs to the failed session.** You may clear a failed
   session's own stale pid files, its own orphaned processes, its own private
   PGDATA (`bb nido:session:reset` on an `:isolated`/`:clone` session), or
   recreate its own worktree when `jj st` there shows no changes. You may NOT
   reset or refresh a shared cluster or a template, touch another session, or
   discard uncommitted work anywhere. If restoring needs any of that, **park**
   (§6).
5. **Paths.** Your cwd is a nido worktree. Write the status file and artifacts
   to the absolute Run-directory paths in your system prompt — a relative
   `_run-status.edn` or `artifacts/` lands in the worktree and gets committed.
6. **Never `git`.** jj workspaces nest inside colocated repos; bare `git` binds
   to the parent. `jj st`, `jj log`, `jj diff`, `jj file show -r <rev> <path>`.

## 1. Say you have started

Your system prompt names the Run directory: artifacts go under
`<run-dir>/artifacts/`, the status file is `<run-dir>/_run-status.edn`. Write
`{:phase :investigating :note "Reading the kept failures"}` there, and read your
workstream id once — the ledger verbs below take it explicitly:

```bash
WS=$(bb -e '(:workstream-id (clojure.edn/read-string (slurp "<run-dir>/run.edn")))' | tr -d '"')
```

## 2. Read the evidence

The payload names the cause and every failure it covers. Read them whole:

```bash
bb nido:recovery:failures :cause <cause>
```

Each failure shows what was started (`:verb`, `:session`, `:project`, `:opts`),
on whose behalf (`:origin` — a person, a Run, a reply resuming a parked Run),
the exception chain outermost first (the innermost cause is usually the real
one), and the log tails captured at the moment it failed. The instance's own
logs may already be gone — a failed Run is torn down at once — which is why the
record carries them.

The same listing shows earlier recovery workstreams for this cause and what
they concluded. Read those before deciding: a cause that was "a one-off" three
times is not a one-off.

## 3. Find the cause

Go to the code that threw — nido's source for the session substrate
(`src/nido/session/**`), the project's own config and code for what nido ran
on its behalf. Then look at the environment as it is **now**, not as it was:

- `bb nido:session:status :project <p> <session>` — is it already up again?
- `bb nido:shared:pg:status :project <p>` — a shared cluster's state.
- `lsof -i :<port>`, `ps -p <pid>` — ports and processes the error names.
- `jj log` in the project repo — did main move under the session?

When the failure is not obvious, reproduce it with a probe session you own and
destroy afterwards: `bb nido:session:up :project <p> recover-probe-<n>`.

Classify, by what would have to change for it never to happen again:

| verdict | the cause is | the fix lives in |
|---|---|---|
| `:nido-defect` | nido does the wrong thing in a state a working system reaches — a race, a wrong ordering, an unhandled case | nido's code, landed on main |
| `:project-defect` | the project's code or build is broken — a migration that cannot apply, a dependency that does not resolve | the project's repo, as a PR |
| `:one-off` | an environment state that clears or can be cleared — a stale pid, a port an orphan holds, a network timeout, two starts racing for a resource once | nothing in code; remediation |

If it is both — a project defect nido handles badly — pick the one whose fix
makes the session start, and file the other (§8).

## 4. Record the diagnosis

```bash
cat > <run-artifacts-dir>/diagnosis.edn <<'EDN'
{:format   :session-diagnosis
 :failures ["<failure-id>" …]           ; every failure the payload named
 :verdict  :nido-defect                 ; | :project-defect | :one-off
 :cause    "what went wrong, in a sentence a person reads cold"
 :evidence ["the log line, file:line or observation it rests on" …]
 :remedy   "what you are about to do about it"}
EDN
bb nido:workstream:entry:add :project nido :ws-id "$WS" :kind session-diagnosis \
  :file <run-artifacts-dir>/diagnosis.edn
```

A recovery Run that ends without appending one is failed whatever its status
file says, and its cause is fired again after a delay.

## 5. Repair, by class

### A nido defect

Ordinary work in this worktree, held to nido's doctrine (`~/Code/nido/CLAUDE.md`,
`docs/reference/comments.md`): a test that names the behaviour the fix defends
and fails without it, comments that carry what the code cannot, one claim.
Then:

```bash
bb nido:test                                   # green before anything else
jj describe -m "$(cat <run-artifacts-dir>/message.txt)"
jj new && jj git fetch && jj rebase -d main@origin
bb nido:recovery:land
```

The message leads with the problem — what failed, for whom, why — then the fix,
with a `Layer:` trailer and a Claims / Verify / Lane / Out of scope brief, and
names the failure ids it answers. If the verb reports `NOT PUSHED` because main
moved, fetch, rebase and run it again. Once it lands, move the root checkout
onto it, so every fresh process — the restore included — runs the fix:

```bash
cd ~/Code/nido && jj git fetch && jj rebase -d main@origin
```

### A project defect

Fix it in a jj workspace of the project's repo — not a nido session, whose
start may be the thing that is broken:

```bash
cd <project-dir> && jj git fetch
jj workspace add ../<project>-recover-<slug> -r main@origin
```

Run the project's own focused tests, push a branch, and open a **ready** PR
(`gh pr create -R <owner/repo>`) whose body leads with the failed starts it
answers. Record it: `bb nido:workstream:ref:add :project nido :ws-id "$WS"
:adapter github :id <owner/repo#n> :url <url> :title "…"`. Forget the jj
workspace when done.

Then find a way the failed sessions can start without the fix merged, within
rule 4 — typically moving a failed session off shared state onto its own
(`bb nido:session:isolate`). If there is none, park (§6) naming the PR.

### A one-off

Remediate within rule 4 and say in the diagnosis what you cleared. Nothing to
land.

## 6. Restore

```bash
bb nido:recovery:restore
```

It covers every still-owed failure of this cause, starts each session in its
own process, hands the daemon whatever the failure interrupted — the Run, its
successor, or the reply that was being resumed — and records one outcome per
failure. It closes the workstream when every failure is restored or withdrawn.

A `not-restored` outcome names the new failure its attempt left. Read it (§2):
either your repair was incomplete — repair and restore again — or restoring
needs something rule 4 forbids. Then **park**:

```bash
cat > <run-artifacts-dir>/blocker.edn <<'EDN'
{:format  :blocker
 :summary "what failed, what you found, what you did"
 :needs   "the one thing a person must decide or do"
 :options [{:label "…" :summary "…" :consequence "…" :recommended? true}
           {:label "…" :summary "…"}]}
EDN
bb nido:workstream:entry:add :project nido :ws-id "$WS" :kind blocker :file <run-artifacts-dir>/blocker.edn
```

and write `{:phase :awaiting-input :note "…"}` to the status path. Parked, this
recovery holds its cause: no other recovery of it starts while you wait. The
answer comes back as a reply to this session; restore again after acting on it.

## 7. Finish

Write `{:phase :complete :note "<verdict> · <what you did> · <n> restored"}`.
Never close the recovery workstream by hand while a failure is not restored —
closing it tells nido those failures are settled.

## 8. File what you did not fix

A second defect, the other half of a both-sided cause, a brake that should have
caught it:

```bash
bb nido:followup:add :project nido :title "…" :kind bug-found-not-caused \
  :reason "…" :decay compounding :cold-start cheap :effort S
```
