---
name: plan-improvements
description: Read every proposal nido's review-loop analyses still owe an answer, group them by what one change would close, and append the day's plan. Fired once a day by the :improvement-sweep trigger. Reads and writes one ledger entry; never touches code.
---

# plan-improvements skill

> **Harness-side skill, owned by nido.** Fired by the `:improvement-sweep` trigger on project `nido` when the day has no plan and something is owed. **Read `~/Code/nido/docs/reference/self-improvement.md` first** — its rules are the bar every claim is held to.

You write **one record**: today's partition of the owed proposals into claims. You change no code. An implementing session is handed each `:land` claim tomorrow, so the plan is judged as reasoning, not as a list.

## Rules

1. **Every owed proposal is in exactly one claim.** The append derives the owed set itself and refuses a partition that misses or invents an address.
2. **You hold the only slot.** Close this workstream when the plan is appended, or `dropped` if you stop. An open one wedges the sweep.
3. **Not the planning machinery.** `record.proposal`, `source.sweep`, the plan and reservation ledger kinds, and these three skills are out of bounds for `:land`; a proposal about them is `:file`.
4. **Never `git`** — this is a jj workspace; bare git binds to the parent repo.

## 1. Read

```bash
bb nido:work:proposals :project nido     # every proposal, with what was decided and landed
bb nido:review:trend                     # which loop is regressing, on which measure
```

Each proposal names its run; `~/.nido/runs/<run-id>/report.json` is the evidence. Open `agent.log` only for a question the report cannot answer.

## 2. Group by cause

A claim is **one change that closes several proposals**, stated in one sentence. Five proposals with four `:where` values and no shared words can be one claim (one root cause); two proposals both "about the report" needing unrelated edits are two. If you cannot state the change in a sentence, it is not one claim.

## 3. Dispose of each claim

- **`:land`** — a change a session should make. Its statement names the trend measure and loop it moves, what it removes (or why nothing can go), and its evidence: the same behaviour in two runs, or a replay. Prefer the claim that deletes.
- **`:no-op`** — nothing to do: one observation, already fixed (cite the revision), or outgrown. The statement is the whole record.
- **`:file`** — real but not the sweep's: too large, out of bounds, or wanting a decision. Carries `:ref`; file first:

```bash
bb nido:followup:add :title "..." :kind cleanup :reason "..." :decay compounding :cold-start cheap :effort M
```

Order is firing order: what other claims depend on goes first, and a proposal that says "land with X, not before" is read only by your ordering.

## 4. Append, then close

```bash
cat > artifacts/plan.edn <<'EDN'
{:format   :improvement-plan
 :date     "<YYYY-MM-DD>"
 :frontier {:proposals [{:ws-id "ws-…" :at-seq 12} …]      ; each ws read, at the entry count you read
            :attempts  [{:ws-id "ws-…" :closed? true} …]}   ; each claim ws read, and whether it had closed
 :claims   [{:statement "…" :disposition :land  :addresses ["ws-…/1.1" "ws-…/1.2"]}
            {:statement "…" :disposition :no-op :addresses ["ws-…/1.3"]}
            {:statement "…" :disposition :file  :addresses ["ws-…/1.4"] :ref "FU-99 · <url>"}]}
EDN
bb nido:improvement:plan :project nido :ws-id <this-ws-id> :file artifacts/plan.edn
bb nido:workstream:close :project nido :ws-id <this-ws-id> :outcome done
```

A refusal prints what is owed but unclaimed and what is claimed but not owed; the second usually means the ledger moved — re-read and append again. Never append the plan with `bb nido:workstream:entry:add`: only `improvement:plan` checks it.
