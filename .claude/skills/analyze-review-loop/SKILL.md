---
name: analyze-review-loop
description: Read one finished review-loop run WHOLE — the arc across all its rounds and how findings travelled between them — and say how the LOOP behaved: where it wasted rounds, what it missed, what it misjudged, and what in nido's own review machinery should change. Fires once per run, after the loop terminates. Analysis only; never edits code and never touches the reviewed branch.
---

# analyze-review-loop skill

> **Harness-side skill, owned by nido.** Fired by the `:review-analysis` trigger on project `nido` at the end of every review loop that was not a dry run. **Read `~/Code/nido/docs/reference/self-improvement.md` first** — what you propose is held to it.

You analyse **the loop, not the code it reviewed**, and **the whole run, not its rounds one by one**: whether the rounds added up, how findings travelled, and whether it stopped for the right reason. A per-round recap is the wrong artifact; the report already is one.

## Boundaries

1. **Never touch the reviewed branch.** You get `{{event/reviewed-project}} / {{event/reviewed-session}}` as names, not a path. Everything you need is in the run dir and nido's ledger; wanting the diff is itself a finding about what the report records.
2. **Never edit code.** This `:lite` worktree is a symlink to `~/Code/nido`; an edit lands outside any branch. You write `artifacts/analysis.md` and one ledger entry.

## 1. Read the run

```bash
echo '{:phase :investigating :note "Reading the run"}' > _run-status.edn
jq '{status, rounds: .summary.rounds, target, machinery}' "<report-path>"
bb nido:workstream:show :project <reviewed-project> :ws-id <reviewed-ws-id>
```

`report.json` holds every round's phases. A **diff run** has `review` (per-layer findings), `warden` (`:decision`, per-finding `:rulings` with `:handle` — the cross-round identity), and `fix` (`:fixes`, plus `:declined`, `:rolled-back`, `:launch-failed`, `:unattempted`). A **record run** (`baseline-loop-…`/`design-loop-…`) has `judge` and `amend`; its report lists only the broken checks, so count from `bb nido:review:figures :project <p> :run-id <run-id>` and quote it. Stderr is in `*-round-N.err.log` (the whole story of a `:review-failed`); each agent's transcript is in its own `*.log`.

## 2. Build the arc

One table: **every finding (or check/claim) against every round** — reported where, ruled what, owned by which layer, fixed or amended, and whether it came back (answered from cache, or re-adjudicated). Then read off it:

- **Shape.** Converging (each round less), flat (running without closing), or rising (fixes creating work).
- **Routing.** reported on X → attributed to Y → fixed on Y → re-reviewed → answered. A finding that keeps travelling is circling.
- **Termination.** Was the status the right one? A `:converged` because the judge went quiet, or a `:stalled`/`:no-progress` while real work remained, is the loop stopping on the wrong signal. Past ~5 rounds, say what kept it going.
- **Record runs.** A claim refuted again after every rewording, by new counterexamples each time, is one no wording settles. A weakening (`:retreats`) without a reason the judge gave is a retreat, even on a run that cleared.

## 3. Read the machinery at the revision that ran

```bash
REV=$(jq -r '.machinery.rev // empty' "<report-path>")
jj --ignore-working-copy file show -r "$REV" src/nido/review/<file>.clj
```

`:machinery.lacks` lists main's loop-changing commits the run did not have — **anything main has since fixed is not an observation**. When `:dirty?` is true, `:hash` (not `:rev`) is what ran. No `:machinery`? Read the checkout and say every observation is provisional.

The files: `loop.clj` (termination), `stages.clj` (diff stages, `apply-rulings`, caches), `prompts.clj` (diff prompts), `cache.clj`, `record.clj` (record judges and amenders), `retreat.clj`, `settled.clj`.

## 4. What to look for

- **Waste** — rounds that closed nothing new; a finding re-adjudicated instead of answered (its hash moved); layers re-reviewed after converging.
- **Miss** — a ruling the warden omitted (`because` reads "the warden did not rule on this finding"); `park`/`deviation` where a fix was due.
- **Misfire** — a `closed` with no authority; `owner_layer` outside the stack; a fix that did not stop the recurrence.
- **Friction** — the report lacked what reconstructing the arc needed.
- **Working well** — record it as an arc; it is how a regression is told from the normal state.

## 5. Propose only what the evidence carries

Before proposing, check whether this run is the pattern: `bb nido:review:figures :project <p>` (no run id) and `bb nido:review:trend`. A `:proposal`:

- names the **class** of defect and the **rule, branch or prompt section it removes or subsumes** — not only what it adds;
- says how often the behaviour is seen across runs (once is a hypothesis: observe it, propose nothing);
- says whether it is **invariant** (true of every project) or belongs to one project's reviewers or briefing.

`:evidence` is required and points at this run: a round, a finding id, a log line. Without it the observation does not go in.

## 6. Write and file

`artifacts/analysis.md`: the verdict, the arc in two sentences, the one thing most worth acting on. Then:

```bash
cat > artifacts/analysis.edn <<'EDN'
{:format :review-analysis
 :verdict :healthy            ; :healthy | :degraded | :broken — about the RUN
 :run-id "<run-id>" :status "<terminal status>" :rounds 3
 :reviewed "<project>/<session>"
 :summary "What this run says about the loop."
 :observations [{:kind :waste   ; :waste | :miss | :misfire | :friction | :working-well
                 :where "answered-cache" :summary "…" :evidence "…"
                 :proposal "…"}]  ; optional
 :report-path "<report-path>" :artifact "artifacts/analysis.md"}
EDN
bb nido:workstream:entry:add :project nido :ws-id <your ws-id, from CLAUDE.md> :kind review-analysis :file artifacts/analysis.edn
echo '{:phase :complete :note "Analysis filed" :artifact "artifacts/analysis.md"}' > _run-status.edn
```

A refused body prints why; fix and retry. **`:healthy` is the usual answer** — one omitted ruling in a run that converged is a `:miss` on a healthy run. `:degraded` is for rounds spent for nothing, `:broken` for a run that did not do its job. Never park at `:awaiting-input`.

Re-fired for a run already analysed (same workstream, keyed on run id): if the entry is there and the run is unchanged, finish `:complete` without filing again.
