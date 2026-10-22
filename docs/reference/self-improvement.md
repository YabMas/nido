# Self-improvement

**Harness-side doctrine, owned by nido.** The three skills of the improvement
sweep cite it: `/analyze-review-loop` (observe one run), `/plan-improvements`
(group what is owed), `/implement-improvement` (land one claim). Read it at
`~/Code/nido/docs/reference/self-improvement.md`.

After *Harness²* (arXiv 2610.14224): propose, probe on matched conditions,
compose only what the evidence supports — and keep the current harness when
nothing does.

## Why this exists

From 2026-09-01 to 2026-10-22 the sweep landed ~226 changes to the review
machinery, each answering a real observation in one run. The machinery grew
from 6k to 42k lines, design prompts reached 180 KB, design runs handed to a
person went from 61% to 88%, and diff runs from 9 to 29 median minutes. Four
rules that were each right about their own run kept one design run circling for
25 rounds. Every change was locally right; nothing measured what they added up
to.

## The rules

1. **The aggregate is the yardstick.** A change is for moving a measure of
   `bb nido:review:trend` — handed share, median rounds, median minutes — in a
   named loop (design, baseline, diff). One that moves none is not an
   improvement, whatever it fixes in a single run. The sweep holds itself while
   the trend reads a regression.
2. **One run is one sample.** The judges are stochastic. Something seen once is
   a hypothesis: record it, change nothing. It becomes a change when a second run
   shows it, or a probe on recorded runs does.
3. **Probe before you compose.** A change to when a loop stops is run through
   `bb nido:review:replay` on main and at the tip; a run it would cut short that
   converged as recorded needs a sentence saying why it should have stopped. A
   change to what a judge is shown is run through `bb nido:review:probe`:
   recorded rounds re-judged under the change and unchanged, against one frozen
   tree, with the unchanged prompt judged twice so its own noise is measured. An
   effect smaller than that noise is no effect.
4. **Subtract first.** Every rule, field or prompt paragraph is one more thing a
   judge can skip and an analysis can report. A change names what it removes, or
   says in a sentence why nothing can go. A rule that fires in a handful of runs
   out of a thousand is a deletion candidate, not a foundation.
5. **Keeping the machinery is an outcome.** "One observation", "already fixed",
   "the code outgrew it" are dispositions. A plan in which every claim lands is
   a plan that did not read the evidence.
6. **Invariant, or domain?** The shared machinery takes a fix that holds for
   every project and every run. A lesson about one project's code or one
   record's subject belongs to that project's reviewers or briefing, not to a
   prompt every judge reads.
7. **One stopping rule.** A record loop ends when its record settles, when a
   person is asked, when a finding resists repair (`:unfixable`), or when rounds
   keep leaving the same things open (`:stalled`). Nothing is re-asked within a
   round. Adjust these; do not add a terminator beside them.

## Which command, when

| moment | command | what it answers |
|---|---|---|
| analysing a run | `bb nido:review:figures :project <p>` (no run id) | is this run's behaviour the pattern or the exception |
| analysing, planning | `bb nido:review:trend` | which loop is getting worse, on which measure (exit 1 on a regression) |
| changing a terminator | `bb nido:review:replay` at main and at the tip | which recorded runs the change ends sooner, and on what |
| changing a prompt | `bb nido:review:probe :plan <plan>` (`bb nido:review:probe:sample` drafts one; `:dry-run true` first) | what the change does to verdicts, findings and their level, against the judge's own noise |
| after a prompt change lands | `bb nido:review:prompts` | what rounds were shown, week by week, and how runs ended under each stance and check set |
| landing | `bb nido:test`, `bb nido:land:check` | the only gates; nothing runs after the push |
| a person, overriding the hold | `bb nido:improvement:release :until <date> :note <why>` | lets the sweep run through a regression, dated so it lapses |
