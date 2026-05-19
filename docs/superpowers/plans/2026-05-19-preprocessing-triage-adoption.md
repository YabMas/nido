# Preprocessing — Triage Adoption — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **jj commit hygiene:** This is a jujutsu repo. In every commit step, run `jj log -r '@-..@' --no-graph` BEFORE editing files (if `@` has a description, `jj new` first), and AGAIN AFTER editing (confirm parent is the previous task's commit).

**Goal:** Turn the preprocessing infrastructure on for the two triage triggers (`triage-new`, `triage-backlog`) and update the triage-bug skill so the agent reads pre-staged transcripts as part of its investigation instead of trying to watch videos.

**Architecture:** No new code. Two configuration changes + one skill-instruction change. The triage triggers gain `:preprocess [:notion-ticket]` and an explicit `:limits.preprocess-budget`. The triage-bug skill's Step 1 (investigate) gains a line directing the agent to `./run-link/preprocess/transcripts.md` before grepping the worktree. Shipped behind the existing `:dry-run? true` on both triggers so quality can be assessed before any Notion writes happen.

**Tech Stack:** Configuration (edn) + skill markdown. Depends on Plans A, B, and C being shipped and verified. Also depends on the triage-bug skill existing — see the cross-spec ordering note in the [preprocessing spec](../specs/2026-05-19-notion-ticket-preprocessing-design.md) — if the skill hasn't landed yet via the triage spec's Plan D, ship preprocessing's Plans A–C and pause here.

**Spec reference:** [2026-05-19-notion-ticket-preprocessing-design.md §Rollout step 4](../specs/2026-05-19-notion-ticket-preprocessing-design.md). This plan delivers **Stage 4** of the four-stage rollout.

---

## File Structure

**Modified:**
- `~/.nido/projects/brian/triggers.edn` — add `:preprocess` + `:limits.preprocess-budget` to both triage triggers.
- `~/Code/brian/.claude/skills/triage-bug/SKILL.md` — add a "pre-staged transcripts" pointer to Step 1.

**Created:** none.

**Untouched:** All nido source, all tests. This stage is pure config + skill copy.

---

## Pre-flight checklist

Before touching configs, confirm the previous three stages are live:

- [ ] `bb tasks | grep nido:transcribe-video` returns a match.
- [ ] `bb tasks | grep nido:notion:preprocess-ticket` returns a match.
- [ ] `bb nido:test :only nido.coordinator.preprocess` passes (run inside the nido worktree).
- [ ] `bb nido:coordinator:status` shows the daemon running.
- [ ] `~/Code/brian/.claude/skills/triage-bug/SKILL.md` exists (triage skill has landed via the triage spec's plan).

If any of these fail, do NOT proceed. Fix the prerequisite or wait for the triage skill to ship.

---

## Task 1 — Add `:preprocess` to triage triggers

**Files:**
- Modify: `~/.nido/projects/brian/triggers.edn`

Both triage triggers gain two new keys:

```clojure
   :preprocess [:notion-ticket]
   :limits {:budget "15m"
            :preprocess-budget "10m"   ; NEW key alongside existing limits
            :max-failures 3}
```

`:dry-run? true` stays on both — preprocessing runs in dry-run too, since it's read-only against Notion. The dry-run gate only short-circuits the Notion-write step in the triage skill itself.

- [ ] **Step 1: jj hygiene check (in the nido repo).** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

Note: `~/.nido/projects/brian/triggers.edn` is NOT under nido's git, but the jj commit at the end captures any nido-side documentation/CHANGELOG that this stage touches. Keep the changeset description clean.

- [ ] **Step 2: Snapshot the current file** for rollback safety.

```
cp ~/.nido/projects/brian/triggers.edn ~/.nido/projects/brian/triggers.edn.bak-preprocess
```

- [ ] **Step 3: Edit the file.** Locate the two triage trigger entries (`:name :triage-new` and `:name :triage-backlog`). For each, add `:preprocess [:notion-ticket]` (place it on its own line near `:session-profile` for readability) and add `:preprocess-budget "10m"` inside `:limits`.

Example final shape for `:triage-new`:

```clojure
{:name :triage-new
 :source {:type :notion-view :view :new-reports :poll "2m"}
 :preprocess [:notion-ticket]
 :skill :triage-bug
 :session-profile :lite
 :priority 10
 :limits {:budget "15m"
          :preprocess-budget "10m"
          :max-failures 3}
 :dry-run? true}
```

Same shape for `:triage-backlog` (preserve its `:source` block — `:additional-filter` and `:priority-from` stay).

- [ ] **Step 4: Validate the file parses.**

```
bb -e "(clojure.edn/read-string (slurp \"~/.nido/projects/brian/triggers.edn\"))" \
   | head -5
```

(If `~` doesn't expand, use the absolute path.) Expected: no exception, the printed map starts with `{:triggers [...`.

- [ ] **Step 5: Restart the coordinator** so it reloads triggers.

```
bb nido:coordinator:restart   # if launchd-installed
# OR
bb nido:coordinator:down ; bb nido:coordinator:up
```

- [ ] **Step 6: Verify both triggers loaded.**

```
bb nido:coordinator:status
```

Expected: status shows the daemon running. No load errors in `bb nido:coordinator:logs`.

```
bb nido:coordinator:logs | grep -i preprocess
```

Expected: nothing (no errors). A subsequent envelope fire will produce log lines mentioning preprocessing.

- [ ] **Step 7: Commit (nido side).** This stage doesn't change nido's source, so the commit is just the plan-checkpoint marker. Skip the commit if nothing in nido's repo changed; otherwise:

```
jj desc -m "docs(plans): mark Stage 4 of preprocessing rollout complete"
jj log -r '@-..@' --no-graph
```

---

## Task 2 — Update the `/triage-bug` skill briefing

**Files:**
- Modify: `~/Code/brian/.claude/skills/triage-bug/SKILL.md`

The skill currently instructs the agent to:
1. Read the Notion page via MCP.
2. Grep the brian worktree.
3. Draft a triage report.

We add one block, near the top of the investigation step, telling the agent that transcripts are already on disk. The agent reads them before (or in parallel with) the codebase walk.

- [ ] **Step 1: Read the existing SKILL.md** to find the right insertion point. The "Step 1 — investigate (autonomous)" section is the target. Look for the numbered list starting "1. Read envelope payload …" or the equivalent in the actual file (the existing triage plan's wording may vary).

- [ ] **Step 2: Insert the pre-staged transcripts block.** After the Notion-page fetch step and BEFORE the codebase grep step, add:

```markdown
### Pre-staged artifacts

Before grepping the worktree, **read `./run-link/preprocess/manifest.edn` and
`./run-link/preprocess/transcripts.md`**. The coordinator has already walked
the Notion page and transcribed any embedded videos (Loom share/embed URLs
and Notion-uploaded screen captures). The manifest lists each video with
its source kind, transcript source (Loom official caption track vs. local
whisper), and full-VTT path; the digest gives a one-paragraph preview per
video.

- If the user's bug report leans on a video (Loom screen-share, repro
  recording), the relevant content is in those VTTs — quote timestamps
  in your triage report (e.g. `at 0:42 the modal closes`).
- If a video shows `:status :failed` in the manifest, note the reason
  in your report's Investigation Trail and proceed without it. Do not
  retry transcription from inside the skill — that's the coordinator's
  job and a one-off retry won't change anything.
- If `./run-link/preprocess/manifest.edn` doesn't exist, the
  preprocessor wasn't run for this Run — proceed without transcripts.
```

- [ ] **Step 3: Adjust the "Investigation trail" section** of the report format to mention transcripts. In the section that lists what the agent grep'd / read, add a bullet:

```markdown
- Transcripts read (if any): list manifest entries the report drew from.
```

- [ ] **Step 4: Verify the change renders.** Run `head -200 ~/Code/brian/.claude/skills/triage-bug/SKILL.md` and visually confirm the inserted block is well-formatted.

- [ ] **Step 5: Commit (brian side).** This is in the brian repo, which is also jj (per the brian working model). Switch context:

```
cd ~/Code/brian
jj log -r '@-..@' --no-graph     # check state
jj new                            # if needed
# (edits already on disk; jj will auto-snapshot)
jj desc -m "feat(skills/triage-bug): read pre-staged transcripts before grep"
jj log -r '@-..@' --no-graph
```

Return to nido for any further work:

```
cd ~/Code/nido
```

---

## Task 3 — End-to-end quality check (dry-run)

**Files:** none (manual sanity check; no commit).

The triggers are now wired and the skill reads transcripts. With `:dry-run? true` still set, every Notion write is short-circuited but the agent still investigates and drafts a report. This is exactly the right gate to confirm transcript-driven triage produces better reports than text-only triage did.

- [ ] **Step 1: Confirm there's a known Notion bug page with a Loom video.** Note its page id.

- [ ] **Step 2: Force-fire the `triage-new` trigger against that page.** Either wait for the natural poll (2 min) or fire manually:

```
bb nido:trigger:fire :project brian :triage-new :page-id "<page-id>"
```

- [ ] **Step 3: Watch the Run.**

```
bb nido:runs:list
bb nido:runs:show <run-id>
```

Expected sequence in `state-history`:
- `:queued` → `:preprocessing` (within ~1s)
- `:preprocessing` → `:running` (within budget, typically 1–3 min for a single short Loom)
- `:running` → `:awaiting-input` (or whatever terminal phase the dry-run skill ends at)

- [ ] **Step 4: Inspect the artifacts.**

```
ls <run-dir>/preprocess
cat <run-dir>/preprocess/manifest.edn
cat <run-dir>/preprocess/transcripts.md
cat <run-dir>/artifacts/triage-report.md
```

Expected:
- `manifest.edn` lists the video(s) with `:status :ok`.
- `transcripts.md` shows a preview per video.
- `triage-report.md` references the video content (e.g. quotes a timestamp or paraphrases a step from the recording).

- [ ] **Step 5: Repeat on a ticket WITHOUT videos.** Confirm:
- `manifest.edn` has `:videos []`.
- `triage-report.md` reads normally.
- No spurious preprocessor errors.

- [ ] **Step 6: Repeat on a ticket with a private/password-protected Loom.** Confirm:
- `manifest.edn` entry shows `:status :failed` with structured reason (`:loom-api-error` or `:loom-transcript-unavailable`).
- `triage-report.md`'s Investigation Trail notes the failure.
- Run state lands at `:awaiting-input` (or skill terminal), NOT `:failed`. Per-video failures must never abort the Run.

No commit — manual verification only.

---

## Task 4 — Go live

**Files:**
- Modify: `~/.nido/projects/brian/triggers.edn`

After Task 3's quality assessment looks clean (give it a few days against the live backlog), flip dry-run off so the triage agent's apply step actually writes to Notion. Preprocessing was already running in Task 1–3; this step doesn't change preprocessing behavior at all.

- [ ] **Step 1: Confirm at least 5–10 dry-run triages produced sensible reports.** Read several `triage-report.md` files from recent Runs. If any are confusing or wrong, fix the skill first before flipping dry-run off.

- [ ] **Step 2: Edit triggers.edn.** Change `:dry-run? true` to `:dry-run? false` on both `:triage-new` and `:triage-backlog`. Leave preprocessing untouched.

- [ ] **Step 3: Restart the coordinator.**

```
bb nido:coordinator:restart
```

- [ ] **Step 4: Watch the first live triage** end-to-end. Approve or override in the session chat per the triage skill's confirmation grammar. Confirm Notion writes land cleanly.

- [ ] **Step 5: If anything looks wrong, flip dry-run back on immediately:**

```
# in triggers.edn: :dry-run? true on both
bb nido:coordinator:restart
```

No commit on the nido side (this is pure config in `~/.nido/`).

---

## Self-Review

After all four stages of the preprocessing rollout are live:

- **`bb nido:coordinator:logs | tail -100`** shows clean preprocessing transitions and no breaker trips on the triage triggers.
- **A spot-check of 5 recent triage reports** confirms the agent references transcript content (timestamps, quoted phrases) on tickets that had videos.
- **`~/.nido/coordinator/runs/*/preprocess/manifest.edn`** files for recent triage Runs show `:transcript-source :loom-graphql` for most Loom videos and `:whisper` only as fallback.
- **No regressions on the smoke trigger** or any other non-triage trigger — those triggers don't declare `:preprocess` and behave identically to before.

The full preprocessing rollout (Stages 1–4) is complete. Future work (cross-Run cache, whisper.cpp, image OCR preprocessor, per-trigger whisper-model knob) is captured in the spec's "Open follow-ups" section and is genuinely deferrable — wait for the constraint that forces each one to land before adding it.
