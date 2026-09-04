---
name: implement-improvement
description: Carry ONE claim of the day's improvement plan — the several proposals one change closes — to landed on nido's main. Fired by the :improvement-sweep trigger. Reserves before it pushes, so a proposal declined in the meantime stops the claim; halts rather than landing anything that does not pass bb nido:test.
---

# implement-improvement skill

> **Harness-side skill, owned by nido.** Fired by the `:improvement-sweep` trigger on project `nido`, emitted by `nido.coordinator.source.sweep` for the next `:land` claim of the day's plan.

You are implementing **one claim** — the single change that closes the proposals listed in `Covers` above. Nobody approved them individually; a plan grouped them, and a human could still decline any one of them while you work. Your job is to find out whether the claim is still true, make the change, and hand the landing to the verb that may refuse it.

## The rules that are not yours to relax

1. **`bb nido:test` is the only gate this repo has.** Nothing runs it after you. If it is red, you do not land — you halt and say so. A red landing here is a red `main` that the next improvement session builds on.
2. **One claim.** The proposals it covers, and nothing else — not the one beside it that looks related, not the tidy-up you notice on the way. Anything else you find gets filed (§7), never fixed.
3. **You never push.** `jj git push` is not yours to run in this session. `bb nido:improvement:discharge` pushes, and it refuses without a standing reservation — which is what makes a decline a veto rather than a note. A revision you get onto main another way records no landing and closes nothing, so the claim stays owed and the slot stays held.
4. **You are holding the only slot.** The sweep starts nothing else until this workstream is closed. Finish it or close it — a session that stops without doing either wedges the pipeline until a human clears it.
5. **Never `git`.** This worktree is a jj workspace nested inside a colocated repo; bare `git` binds to the parent and returns the wrong history. `jj st`, `jj log`, `jj diff`, `jj file show -r <rev> <path>`.

## 1. Say you have started

```bash
cat > _run-status.edn <<'EDN'
{:phase :investigating :note "Reading the proposal against the code"}
EDN
```

## 2. Read the proposal, then check it is still true

The payload above carries the observation whole: what it saw, the evidence for it, and what it proposes. Read the analysis entry too, for the surrounding observations:

```bash
bb nido:workstream:show :project nido :ws-id <ws-id>
```

Read the plan entry too — `bb nido:workstream:show :project nido :ws-id <plan-ws>` — for the statement that grouped these and the claims either side of it.

Now go to the code and **verify the claim against what is in the tree right now**, proposal by proposal. A plan is written before the work and several changes land in between. Three outcomes:

- **Still true.** Go on to §3.
- **Already fixed.** Someone did it, or a neighbouring change made it moot. Discharge the claim citing the revision that carries it (§6). This is a good outcome, not a wasted session.
- **The claim was wrong.** The grouping does not hold, or the evidence does not show what it claims. Do NOT implement it. File a `:retraction` on each analysis workstream naming the entry and what the code actually does, close this workstream `dropped`, and stop — the addresses stay owed and tomorrow's plan groups them again.

Where the proposals disagree with each other, say so and implement the one the evidence supports; a plan that grouped two incompatible readings is a finding worth recording.

Say which one you found in `_run-status.edn` before you go on.

## 3. Decide the shape before you write anything

Read `~/Code/nido/CLAUDE.md`'s shipping doctrine, and `/design` if the change is more than a single seam. Two questions decide how much ceremony this needs:

**Is the proposal's remedy already named?** Most are — an analysis proposal says "call `layers/conflicted` after each `land-fix!`", which is a change, not a design question. Implement it.

**Does it ask a boundary to move?** If carrying it out means a new namespace, a new record kind, or a rule that some other module has to start honouring, write a `:design` entry on this workstream first, then implement against it. A design record is cheap; discovering mid-edit that you are redesigning the review loop is not.

## 4. Implement it

Ordinary work, in this worktree. What this codebase asks of you specifically:

- **Comments carry what the code cannot.** `~/Code/nido/docs/reference/comments.md` is the doctrine. A comment that restates the identifiers is deleted, not written.
- **Describe what is, never what happened.** The comment must read correctly to someone who never saw the previous version. The transition story goes in the commit message.
- **Tests are the claim.** Each one names the behaviour it defends, and its assertion message says why that behaviour matters — not what the code does.

## 5. Test, and halt if it is red

```bash
bb nido:test
```

**Green is required and is not negotiable.** If it is red:

1. If your own change caused it, fix it and re-run.
2. If it was red before you started (check by `jj new main` in a scratch commit and running there), you are blocked on something that is not yours. Halt.

To halt: write the blocker on this workstream and stop. Do not land, do not close.

```bash
cat > artifacts/blocker.edn <<'EDN'
{:format :blocker
 :summary "..."           ; what you were doing and what stopped you
 :needs "..."             ; the one thing a human must decide or fix
 :options [{:letter "a" :label "..." :costs "..."}
           {:letter "b" :label "..." :costs "..."}]}
EDN
bb nido:workstream:entry:add :project nido :ws-id <this-ws-id> :kind blocker :file artifacts/blocker.edn
```

Then run the landing gate, which is not advisory:

```bash
bb nido:land:check
```

Two questions, and only one of them applies to you. The standing half no-ops — this workstream holds no design record to stand on. The structure half runs fukan against nido's declared canvas and **refuses on a violation**, which is the check the old version of this skill ran as `bb nido:design:check` and then ignored. If it refuses, either the code moves or the declaration does; both are yours, and landing is where that gets decided rather than inherited.

## 6. Land it — by reserving, then discharging

nido lands on `main` directly; there is no PR. **You do not push.** Get the revision ready, then hand it over:

```bash
jj describe -m "$(cat artifacts/message.txt)"     # write it first; see below
jj new
jj git fetch
jj rebase -d main@origin                          # main moves under you; it will
bb nido:test                                      # again, at the rebased tip
```

Now reserve. This is the veto's deadline: it re-derives every decision under the ledger locks and refuses if any proposal your claim covers has been declined since the plan was written.

```bash
bb nido:improvement:reserve :project nido :ws-id <this-ws-id>   :plan-seq <n> :claim <n> :addresses <the Covers list, comma-separated>
```

**If it refuses, you are done.** A decline reached your claim first. The task closes this workstream `vetoed` and prints which address it was; the others go back to the owed set and tomorrow's plan groups them again. Nothing you built lands — say so in `_run-status.edn` and stop. This is the mechanism working, not a failure.

Then discharge. One call pushes and records a landing for every address the claim covers, at the one revision:

```bash
bb nido:improvement:discharge :project nido :ws-id <this-ws-id>   :worktree "$PWD" :rev <change-id> :addresses <the same list>
```

It refuses without the reservation, records nothing if the push did not land, and **closes this workstream itself** — which is what releases the slot. If it reports `NOT PUSHED`, read the reason: a rejected push usually means main moved again, so rebase, re-test and re-run. Re-running after an interruption is safe: it appends only for addresses not already recorded at that revision.

**The message is the only artifact a later reader gets.** `~/Code/nido/docs/reference/descriptions.md` is the doctrine; the shape:

- Subject: imperative, ~50 chars, never past 72, no "and".
- Lead with **the problem** — what the system did, why that was wrong, who it bit — then what you did, then why this way.
- A `Layer:` trailer: `mechanical`, `structural` or `behavioral`.
- A brief: `Claims:` / `Verify:` / `Lane:` / `Out of scope:`.
- Never restate the diff. It is attached.
- Name every proposal the claim covered. One landing discharges several, and the message is where a reader learns which.

Then leave the root checkout on what you landed, so the next session does not run stale code:

```bash
cd ~/Code/nido && jj git fetch && jj rebase -d main@origin
```

## 7. File what you did not do

The landing and the close were both done by `discharge`. What is left is anything you found and did not fix — a second defect, a proposal the code has outgrown, a boundary that wants moving:

```bash
bb nido:followup:add :project nido :title "..." :kind cleanup :reason "..." \
  :decay compounding :cold-start cheap :effort S
```

No spin-out without a ref. "Later" in a note is a wish.

If you stopped without landing — blocked, vetoed, or the claim turned out untrue — close it by hand instead, and say which:

```bash
bb nido:workstream:close :project nido :ws-id <this-ws-id> :outcome dropped
```

The only state that must never be left behind is an open workstream nobody is working in: it holds the slot, and nothing improves anything until a human clears it.
