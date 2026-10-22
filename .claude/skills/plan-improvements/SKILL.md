---
name: plan-improvements
description: Read every proposal nido's review-loop analyses still owe an answer, group them by what one change would close, and append the day's plan. Fired once a day by the :improvement-sweep trigger. Reads and writes one ledger entry; never touches code.
---

# plan-improvements skill

> **Harness-side skill, owned by nido.** Fired by the `:improvement-sweep` trigger on project `nido` when the day has no plan and something is owed.

You are writing **one record**: today's partition of the open proposals into claims. You change no code. What you produce is what an implementing session is handed tomorrow, and what a reader gets in place of the per-proposal approval this replaces — so it is judged as a piece of reasoning, not as a list.

## The rules that are not yours to relax

1. **Every owed proposal appears in exactly one claim.** The verb that appends the plan derives the owed set itself and refuses a partition that does not cover it — you cannot omit an awkward one, and you cannot invent an address.
2. **You are holding the only slot.** Nothing else improves anything while this workstream is open. Close it when the plan is appended, or close it `dropped` if you stop — a session that does neither wedges the pipeline until a human clears it.
3. **You do not decide whether a proposal is worth doing.** Approval is not required any more and a decline is the only verdict that acts, so a proposal reaching you is one nobody has vetoed. What you decide is what closes it: a change, a filed ticket, or nothing.
4. **You may not plan a change to the machinery that plans.** `record.proposal`, `source.sweep`, the plan and reservation ledger kinds, and this skill are out of bounds for a `:land` claim: the sweep must not edit the code deciding what the sweep does. Everything else in nido, including the whole review loop, is in scope. A proposal about the carve-out gets `:file`.
5. **Never `git`.** This worktree is a jj workspace nested in a colocated repo; bare `git` binds to the parent. Use `jj`.
6. **The machinery is judged by the trend, not by the proposals.** Between 2026-09-01 and 2026-10-22 this sweep landed some 226 changes, every one answering a real observation, and the loops got worse: design runs handed to a person went from one in five to nine in ten, diff runs from 9 to 29 median minutes. Each change was right about its run; nothing checked what they added up to. The sweep now holds itself while `bb nido:review:trend` reads a regression — and a plan that cannot say how it moves that trend is not a plan.

## 1. Read what is owed

```bash
bb nido:work:proposals :project nido      # every proposal, with what was decided and landed
```

The owed set is what carries no landing, no decline, and no disposition from an earlier plan. You do not have to compute it — the append will refuse you if you get it wrong — but you do have to read every row, because grouping is the whole job.

## 1b. Read the trend

```bash
bb nido:review:trend            # weekly rounds, minutes, converged / handed shares per loop
```

Name, in each `:land` claim's statement, which measure it is meant to move (handed share, rounds, minutes) and in which loop. A claim that moves none of them — a report wording, a field nobody reads — is `:no-op` or `:file` unless it is a defect.

## 2. Read the analyses, and the transcripts when they help

Each proposal names the run it came from. The run directory is permanent and holds what the analysis was reading:

```bash
ls ~/.nido/runs/<run-id>/          # report.json, agent.log, per-round logs
```

Read `report.json` first. Go to `agent.log` only for a specific question you cannot answer otherwise — a six-round run's log is long, and the budget is an hour.

## 3. Group by cause, not by wording

A claim is **one change that closes several proposals**. The grouping is a claim about the code, and it is the thing a reader will judge you on.

What that looks like in practice: three analyses independently reported that `:patch-hash` is stamped on skipped rows but not reviewed ones, a fourth reported the answered-cache was inert, and a fifth reported `converged-targets` ignoring carried parks. Five proposals, four `:where` values, no shared vocabulary — and one cause, because the hash is computed over jj's raw `--git` output whose `index` lines move when a lower layer edits a shared file. One claim, five addresses.

The opposite mistake is grouping by theme. Two proposals both "about the report" that need two unrelated edits are two claims. If you cannot state the single change in one sentence, it is not one claim.

## 4. Give every claim a disposition

**The evidence bar for `:land`.** One run is one sample of a stochastic judge. A proposal about what a judge did, seen in one run, is a hypothesis: `:land` it only when a second run shows the same thing, or when `bb nido:review:replay` shows the change on recorded runs. Otherwise `:no-op` with the reason "one observation" — the address stays on record, and a second analysis that sees it again is what makes it a claim. Keeping the machinery as it stands is a disposition, not a failure to plan: the 86-of-86 `:land` plans of 2026-10-20 to 22 were the failure.

**A claim that adds must say what it removes.** Nearly every landing since September added a rule, a field or a prompt paragraph (266 of 273 commits to the review machinery were net additions; design prompts reached 180 KB), and every addition is one more thing a judge can skip and an analysis can report. A `:land` statement names the rule, branch or prompt section the change replaces, or says in a sentence why nothing can go. Prefer the claim that deletes.

- **`:land`** — a change you are asking a session to make. The statement says what that change is.
- **`:no-op`** — nothing to do. A proposal whose own text says "already fixed on main", or one the code has outgrown. The statement is the whole record: say why, with the evidence, because nothing else will be written about it. **Two of the currently-open proposals are this**, and each would otherwise get a worktree and four hours.
- **`:file`** — real work that is not the sweep's to make: too large, out of the carve-out, or wanting a decision. Carries `:ref`, the follow-up it was filed to. **A `:file` claim with no ref is refused by the schema**, and rightly: without one it is an address out of the owed set with the work it named held nowhere.

File first, then plan, so the ref exists:

```bash
bb nido:followup:add :title "..." :kind cleanup :reason "..." :decay compounding :cold-start cheap :effort M
```

## 5. Order the claims

The plan's order is the firing order. Put first what other claims depend on. One live case: a proposal that fixes `converged-targets` says in its own text *"land it with the patch-hash fix, not before it"*, because fixing it alone makes the waste worse. Nothing but your ordering reads that sentence.

## 6. Append it

```bash
cat > artifacts/plan.edn <<'EDN'
{:format   :improvement-plan
 :date     "<today, YYYY-MM-DD>"
 :frontier {:proposals [{:ws-id "ws-…" :at-seq 12} …]
            :attempts  [{:ws-id "ws-…" :closed? true} …]}
 :claims   [{:statement   "what one change would close these"
             :disposition :land
             :addresses   ["ws-…/1.1" "ws-…/1.2"]}
            {:statement   "already on main at <rev>; recorded as confirmation"
             :disposition :no-op
             :addresses   ["ws-…/1.3"]}
            {:statement   "real, and larger than a claim"
             :disposition :file
             :addresses   ["ws-…/1.4"]
             :ref         "FU-99 · <url>"}]}
EDN

bb nido:improvement:plan :project nido :ws-id <this-ws-id> :file artifacts/plan.edn
```

The **frontier** is what you read: for every workstream you took proposals from, its `:ws-id` and the ledger position you read it at (`bb nido:workstream:show` prints the entry count); for every claim workstream you read attempts from, whether it had closed. It is what makes the plan replayable — owedness is derived from proposals *and* attempts, so positions alone cannot reconstruct it.

**A refusal is informative, not a failure.** It prints what is owed but claimed by nothing, and what is claimed but not owed. The second usually means the ledger moved while you were reading; re-derive and append again.

Use this task and never `bb nido:workstream:entry:add`. Only this one derives the owed set, so a plan written the other way is unchecked.

## 7. Close the workstream

```bash
bb nido:workstream:close :project nido :ws-id <this-ws-id> :outcome done
```

**This is what releases the slot.** Nothing implements anything until you do.
