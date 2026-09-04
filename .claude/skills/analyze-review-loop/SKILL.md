---
name: analyze-review-loop
description: Read one finished review-loop run WHOLE — the arc across all its rounds and how findings travelled between them — and say how the LOOP behaved: where it wasted rounds, what it missed, what it misjudged, and what in nido's own review machinery should change. Fires once per run, after the loop terminates. Analysis only; never edits code and never touches the reviewed branch.
---

# analyze-review-loop skill

> **Harness-side skill, owned by nido.** Fired by the `:review-analysis` trigger on project `nido`, enqueued by `nido.review.analysis/enqueue!` at the end of every review loop that was not a dry run — whatever project the loop reviewed.

## What you are looking at, and what you are not

You are analysing **the review loop**, not the code it reviewed. A run that correctly found twelve real bugs and fixed them in one round is a *good* run; a run that found nothing after six rounds of agents may be a *bad* one. The subject is the machinery.

**And you are analysing one whole run, not a sequence of rounds.** You fire once, after the loop has terminated, precisely so you can see what no round could see from inside itself: whether the rounds added up, how a finding travelled from the layer that reported it to the layer that fixed it, and whether the loop stopped for the right reason. A round-by-round write-up is the wrong artifact — the report already holds that, and reading it back adds nothing. Every observation you file has to say something about the **arc**.

**Two hard boundaries.**

1. **Never touch the reviewed branch.** You were given its project and session as *names* — `{{event/reviewed-project}} / {{event/reviewed-session}}` — deliberately, and not as a path. Do not `cd` to it, do not read files in it, do not run `jj` against it. Everything you need is under the run dir and in nido's own ledger. If you find yourself wanting the reviewed diff, say so as a gap in what the report records; that is itself a finding.
2. **Never edit code.** This session runs on the `:lite` profile, whose worktree is a **symlink to nido's main checkout** — an edit here lands in `~/Code/nido` directly, outside any branch. You write exactly two things: `artifacts/analysis.md` and one ledger entry. Improvements are *proposed*, never applied.

## 1. Say you have started

```bash
cat > _run-status.edn <<'EDN'
{:phase :investigating :note "Reading the run"}
EDN
```

## 2. Read the run

The payload names the run dir and the report. Start there:

```bash
ls "<run-dir>"
cat "<report-path>" | jq '{status: .status, rounds: .summary.rounds, target: .target}'
```

`report.json` is the whole run: `:target` (cwd, base, base-rev, layers, files), then one entry per round under `:rounds`, each with its phases — `review` (per-layer `:layers` rows and every `:findings` body), `warden` (`:decision`, `:reason`, and per-finding `:rulings`), and `fix` (`:fixes`, `:fixed-count`, and the three ways a repair does not happen — `:declined` a fixer that read the finding and refused, `:rolled-back` a repair the stack would not take, `:unattempted` a layer whose fixer was never launched because the stage stopped on a `:conflicted` it could not undo).

Also in the run dir: `agent.err.log` (the warden's stderr) and `*-round-N.err.log` per stage. A run that ended `:review-failed` or `:warden-indeterminate` has its whole story in those, not in the report.

Read the reviewed workstream's ledger too — **nido-side, via the CLI, not by opening the worktree**:

```bash
bb nido:workstream:show :project <reviewed-project> :ws-id <reviewed-ws-id>
```

The `:review` entry it holds is what the loop reported about itself; the `:design`/`:design-verdict` entries are the yardstick it judged against.

## 3. Reconstruct the arc — do this before anything else

Everything holistic falls out of one table, so build it first: **every finding id, against every round**, and what happened to it.

For each finding, across the whole run: which round first reported it and from which layer; what the warden ruled (`fix` / `closed` + authority / `deviation` / `park`) and which layer it attributed ownership to; whether a fixer on that layer committed for it; whether it came back in a later round; and if it came back, whether it returned *answered* from the cache or was re-adjudicated from scratch.

Then read three things off that table.

**The shape of the run.** Findings and fixes per round. A healthy arc converges — each round finds and fixes less than the last. A flat arc (the same count round after round) means the loop was running without closing anything. A rising one means fixes were creating work.

**The routing.** A finding's whole path is: *reported by* layer X → *attributed to* owner layer Y → *fixed on* Y → *re-reviewed* next round → *answered* from Y's cache. Every hop can go wrong, and only a cross-round view shows it: an attribution that sent a finding to a layer that could not fix it, a fix that landed but did not stop the finding recurring, an answer that should have been cached and was not. Where the same finding keeps travelling, the loop is not converging — it is circling.

**The termination.** The loop stopped for exactly one reason (`loop.clj`): converged, escalated, clean, no-progress, review-failed, fix-noop, warden-indeterminate, or — only if a cap was asked for — max-iters. Was it the right one? A `:no-progress` that fired while real work remained, or a `:converged` reached because the reviewer went quiet rather than because the code got fixed, are both the loop stopping on the wrong signal, and neither is visible from inside a single round.

## 4. Read the machinery the run exercised

You are in nido's checkout. The loop is:

| file | what to check it against |
|---|---|
| `src/nido/review/loop.clj` | termination — which terminal status fired, and whether it was the right one |
| `src/nido/review/stages.clj` | the three stages, `apply-rulings`, `converged-targets`, `answered-for` |
| `src/nido/review/prompts.clj` | every prompt the run's agents actually received |
| `src/nido/review/cache.clj` | the converged/answered cache, keyed by patch hash |
| `src/nido/review/codex.clj` | how the reviewer is invoked |

An observation that cannot be pinned to one of these is not yet actionable — keep digging until it is, or file it as `:friction` and say what you could not determine.

## 5. What to look for

These are the failure modes the machinery actually has. Not a checklist to fill — the table from §3 decides what is here, and evidence decides what gets filed.

**Waste** — rounds, agents or tokens spent for nothing. This is the most cross-round of the five, and the hardest to see from anywhere but here.
- Rounds that fixed nothing new. A round that reported findings and closed all of them is a round that cost a full fan-out to learn what the previous round already knew.
- The same finding re-adjudicated instead of answered. A finding closed in round 1 should return *answered* from that layer's cache, keyed on its patch hash. If it was ruled on again from scratch, the hash moved when it should not have — a real bug, and one that compounds every round.
- Layers re-reviewed after converging, or skipped as converged when their content had in fact moved.
- The loop is **uncapped** (no default `:max-iters`), so length is now a signal rather than a ceiling. Any run past ~5 rounds needs a sentence on what kept it going, and whether that reason was good.

**Miss** — something the loop should have caught and did not.
- A finding whose `because` reads *"the warden did not rule on this finding"* — `apply-rulings`' fail-safe firing, meaning the warden's JSON omitted it. One is a blip; the same layer omitted round after round is a prompt that has outgrown its context.
- `park` and `deviation` used where a `fix` was warranted, or the reverse.
- A composition (`stack`) finding spanning one layer that was not closed `duplicate`.

**Misfire** — the loop did something incorrect.
- A `closed` with no authority, or an authority that does not hold up against the layer's stated Out of scope (now visible to the warden via `toc-block`).
- `owner_layer` naming a layer that is not in the stack — the fix then falls to the top layer, quietly.
- A fixer that committed something outside its layer's scope, or one whose commit did not stop the finding recurring.

**Friction** — the loop worked but was hard to follow, or the report does not record what a reader needs. If reconstructing the arc in §3 required guessing, that gap is itself the finding: the report should have held it.

**Working well** — record these too, and record them as arcs. "The answered-cache held across all four rounds — nothing was re-adjudicated" is worth as much as a complaint, because without it there is no way to tell a regression from the normal state, and no way to know which mechanisms are safe to leave alone.

## 6. Write the artifact

`artifacts/analysis.md` — prose, for a human. Lead with the verdict, then **the arc in a sentence or two** — how the run went from its first round to its last, and why it stopped — and then the one thing most worth acting on.

Do not restate the report round by round. The reader can open it, and a per-round recap is exactly the artifact firing once per run was meant to avoid. The table from §3 is your working material, not the deliverable; include a compressed version of it only where it is the evidence for an observation.

## 7. File the ledger entry

Your own workstream id is in this session's briefing (`- workstream: <id>` in `CLAUDE.md`). Write the EDN to a file — a typed body does not survive a shell argument — then:

```bash
cat > artifacts/analysis.edn <<'EDN'
{:format :review-analysis
 :verdict :healthy            ; :healthy | :degraded | :broken — about the RUN, not the reviewed code
 :run-id "<run-id>"
 :status "<the run's terminal status>"
 :rounds 3
 :reviewed "<project>/<session>"
 :summary "One or two sentences: what this run says about the loop."
 :observations
 [{:kind :waste                ; :waste | :miss | :misfire | :friction | :working-well
   :where "answered-cache"     ; the machinery it is about
   :summary "..."
   :evidence "..."             ; REQUIRED — point at a round, a finding id, a log line
   :proposal "..."}]           ; optional; omit when you noticed it but cannot yet answer it
 :report-path "<report-path>"
 :artifact "artifacts/analysis.md"}
EDN

bb nido:workstream:entry:add :project nido :ws-id <ws-id> \
  :kind review-analysis :file artifacts/analysis.edn
```

The body is validated at the ledger boundary; a malformed one is rejected with an explain dump and a non-zero exit, so read the error and retry rather than dropping the entry.

**`:evidence` is what makes an observation worth keeping.** It must point at something in *this* run. "The warden prompt is too long" is a belief; "the warden omitted 3 of 11 findings in round 2, all from the layer whose brief runs 400 lines" is a finding. If you cannot produce evidence, the observation does not go in.

**Verdict, honestly.** `:healthy` is the right answer for most runs and is not a participation prize — a loop that converged in two rounds with every finding ruled on is healthy even if you can imagine improvements. Reserve `:degraded` for rounds or agents genuinely spent for nothing, and `:broken` for a run that failed to do its job at all.

**The verdict is about the arc, not the worst moment in it.** A single omitted ruling in a run that otherwise converged cleanly is one `:miss` observation on a `:healthy` run. What makes a run `:degraded` is the shape: rounds that did not pay for themselves, or a finding that circled without ever being closed.

## 8. Finish

```bash
cat > _run-status.edn <<'EDN'
{:phase :complete :note "Analysis filed" :artifact "artifacts/analysis.md"}
EDN
```

Do **not** park at `:awaiting-input`. This fires after every review loop; a gate per run would make the inbox useless. If something needs a human decision, say so plainly in the summary and let the verdict carry it — a `:broken` verdict is visible on the workstream without interrupting anyone.

## Idempotency

Re-fired for a run you have already analysed, you land in the same workstream (the external ref is the run id). Read the existing `artifacts/analysis.md` and the ledger first: if the analysis is already there and the run has not changed, say so and finish `:complete` rather than filing a second entry.
