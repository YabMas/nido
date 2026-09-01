---
name: implement-improvement
description: Carry ONE approved improvement proposal from nido's operations board to landed on nido's main — survey, implement, test, land, record. Fired by the :improvement trigger once a human has approved the proposal. Halts rather than landing anything that does not pass bb nido:test.
---

# implement-improvement skill

> **Harness-side skill, owned by nido.** Fired by the `:improvement` trigger on project `nido`, emitted by `nido.coordinator.source.improvement` when a proposal has been approved on the operations board and nothing else is being implemented.

You are implementing **one** proposal. It has already been read and approved by a human; your job is not to re-decide whether it is worth doing, but to find out whether it is still true, do it, and land it.

## The rules that are not yours to relax

1. **`bb nido:test` is the only gate this repo has.** Nothing runs it after you. If it is red, you do not land — you halt and say so. A red landing here is a red `main` that the next improvement session builds on.
2. **One proposal.** Not the two beside it that look related, not the tidy-up you notice on the way. Anything else you find gets filed (§7), never fixed.
3. **You are holding the only slot.** The source will start no other improvement until this workstream is closed. Finish it or close it — a session that stops without doing either wedges the pipeline until a human clears it.
4. **Never `git`.** This worktree is a jj workspace nested inside a colocated repo; bare `git` binds to the parent and returns the wrong history. `jj st`, `jj log`, `jj diff`, `jj file show -r <rev> <path>`.

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

Now go to the code the `:where` names and **verify the observation against what is in the tree right now**. Proposals are approved days after they are written and several land in between. Three outcomes:

- **Still true.** Go on to §3.
- **Already fixed.** Someone did it, or a neighbouring change made it moot. Record the landing citing what carries it (§6), close, and stop. This is a good outcome, not a wasted session.
- **The observation was wrong.** The evidence does not show what it claims. Do NOT implement it. Record nothing as landed; file a `:retraction` on the analysis workstream naming the entry and what the code actually does, then close and stop.

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

Also run `bb nido:design:check` and read it. It is not a gate — nothing fails on it — but a violation you introduced is a violation you own, and the canvas is where this project's declared design lives.

## 6. Land it

nido lands on `main` directly. There is no PR on this repo.

```bash
jj describe -m "$(cat artifacts/message.txt)"     # write it first; see below
jj new
jj git fetch
jj rebase -d main@origin                          # main moves under you; it will
bb nido:test                                      # again, at the rebased tip
jj bookmark set main -r @-
jj git push -b main
```

**The message is the only artifact a later reader gets.** `~/Code/nido/docs/reference/descriptions.md` is the doctrine; the shape:

- Subject: imperative, ~50 chars, never past 72, no "and".
- Lead with **the problem** — what the system did, why that was wrong, who it bit — then what you did, then why this way.
- A `Layer:` trailer: `mechanical`, `structural` or `behavioral`.
- A brief: `Claims:` / `Verify:` / `Lane:` / `Out of scope:`.
- Never restate the diff. It is attached.

Then leave the root checkout on what you landed, so the next session does not run stale code:

```bash
cd ~/Code/nido && jj git fetch && jj rebase -d main@origin
```

## 7. Record what became of it, and close

Two records, both required. First the landing, against the proposal it discharges — this is what turns the board row from "approved, not yet implemented" into "landed":

```bash
bb nido:improvement:landed :project nido :ws-id <analysis-ws-id> \
  :analysis-seq <n> :observation <n> :rev <change-id> \
  :note "what the landing did NOT cover, if anything"
```

Note the `:ws-id` is the **analysis** workstream from the payload, not this session's.

Then anything you found and did not do — a second defect, a proposal the code has outgrown, a boundary that wants moving:

```bash
bb nido:followup:add :project nido :title "..." :why "..."
```

No spin-out without a ref. "Later" in a note is a wish.

Finally, close this workstream. **This is what releases the slot** — the source starts no other improvement while it is open:

```bash
bb nido:workstream:close :project nido :ws-id <this-ws-id> :outcome done
```

Close it with `:outcome dropped` if you stopped at §2 without landing. Close it either way; the only state that must never be left behind is an open workstream nobody is working in.
