---
name: implement-improvement
description: Carry ONE claim of the day's improvement plan — the several proposals one change closes — to landed on nido's main. Fired by the :improvement-sweep trigger. Reserves before it pushes, so a proposal declined in the meantime stops the claim; halts rather than landing anything that does not pass bb nido:test.
---

# implement-improvement skill

> **Harness-side skill, owned by nido.** Fired by the `:improvement-sweep` trigger on project `nido` for the next `:land` claim of the day's plan. **Read `~/Code/nido/docs/reference/self-improvement.md` first.**

You implement **one claim**: the change that closes the proposals in `Covers` above. A person can still decline any of them while you work.

## Rules

1. **`bb nido:test` green, or no landing.** Nothing runs after you; red here is red `main`.
2. **One claim.** Anything else you find is filed, never fixed.
3. **You never push.** `bb nido:improvement:discharge` does, and only under a standing reservation — that is what makes a decline a veto.
4. **You hold the only slot.** Land (discharge closes the workstream) or close it `dropped`. Never leave it open.
5. **Never `git`** — use `jj`.

## 1. Check the claim is still true

```bash
echo '{:phase :investigating :note "Reading the claim against the code"}' > _run-status.edn
bb nido:workstream:show :project nido :ws-id <analysis-ws>   # each proposal's evidence
bb nido:workstream:show :project nido :ws-id <plan-ws>       # the statement that grouped them
```

Verify each proposal against the tree as it is now:

- **Still true** → §2.
- **Already fixed** → discharge citing the revision that carries it (§4). A good outcome.
- **Wrong** — the grouping fails, or the evidence does not show it → file a `:retraction` on each analysis workstream saying what the code does, close `dropped`, stop.

## 2. Make the change

The claim says what it removes; remove it. A change that only adds needs the sentence the plan gave for why nothing could go. If carrying it out moves a boundary (a new namespace, record kind, or a rule another module must honour), write a `:design` entry on this workstream first.

Comments follow `~/Code/nido/docs/reference/comments.md`: what the code cannot say, describing what is, never what happened. Each test names the behaviour it defends.

## 3. Gate it

```bash
bb nido:test
bb nido:land:check
```

**A change to when a loop stops** — `nido.review.loop`, or a branch setting `:control :stop`/`:escalate` or a terminal `:status` in `nido.review.record`/`stages` — runs `bb nido:review:replay` at main and at your tip; quote both. A run your tip cuts short that converged as recorded needs a sentence on why it should have stopped.

Red because of you: fix and re-run. Red on main already (check with `jj new main`): halt —

```bash
cat > artifacts/blocker.edn <<'EDN'
{:format :blocker :summary "…" :needs "…"
 :options [{:letter "a" :label "…" :costs "…"} {:letter "b" :label "…" :costs "…"}]}
EDN
bb nido:workstream:entry:add :project nido :ws-id <this-ws-id> :kind blocker :file artifacts/blocker.edn
```

`land:check` refusing on the canvas means the code moves or the declaration does; both are yours.

## 4. Land: reserve, then discharge

```bash
jj describe -m "$(cat artifacts/message.txt)" && jj new
jj git fetch && jj rebase -d main@origin && bb nido:test
bb nido:improvement:reserve   :project nido :ws-id <this-ws-id> :plan-seq <n> :claim <n> :addresses <Covers, comma-separated>
bb nido:improvement:discharge :project nido :ws-id <this-ws-id> :worktree "$PWD" :rev <change-id> :addresses <same>
cd ~/Code/nido && jj git fetch && jj rebase -d main@origin
```

`reserve` refusing means a decline won: it closes the workstream `vetoed`; stop. `discharge` pushes, records a landing per address and closes the workstream; on `NOT PUSHED`, rebase, re-test, re-run (it is idempotent).

The message (`~/Code/nido/docs/reference/descriptions.md`): subject imperative, ≤72 chars, no "and"; lead with the problem; name every proposal covered, the trend measure targeted, and what was removed; trailers `Layer:` (`mechanical`|`structural`|`behavioral`), `Claims:`, `Verify:`, `Lane:`, `Out of scope:`.

## 5. File what you did not do

```bash
bb nido:followup:add :project nido :title "…" :kind cleanup :reason "…" :decay compounding :cold-start cheap :effort S
```

Stopped without landing (blocked, wrong claim)? `bb nido:workstream:close :project nido :ws-id <this-ws-id> :outcome dropped`.
