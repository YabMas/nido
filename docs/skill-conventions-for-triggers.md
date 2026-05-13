# Skill conventions for use as auto-trigger targets

When a nido trigger fires, it launches a claude session with `/<skill> <payload>` as the first message. Most skills work as-is. Skills written with auto-triggering in mind follow three light conventions so the Run lifecycle reflects what the skill is doing.

These are conventions, not enforced contracts. See the [coordination layer design](superpowers/specs/2026-05-13-nido-coordination-layer-design.md) for the full picture.

## 1. Artifacts go in `<session-home>/artifacts/`

Write meaningful intermediate outputs as files under `artifacts/` with stable names:

- `artifacts/analysis.md`
- `artifacts/proposal.md`
- `artifacts/plan.md`

Stable filenames let the Run overview surface them and let the user `cat artifacts/proposal.md` without searching.

## 2. Status file at `<session-home>/_run-status.edn`

Update this file at phase transitions:

```edn
{:phase :awaiting-input
 :note "Analysis written; does this match your read of the ticket?"
 :artifact "artifacts/analysis.md"}
```

Phases:

- `:investigating` — gathering context (ongoing — does not change Run state)
- `:working` — making changes (ongoing)
- `:awaiting-input` — yield to user → Run state becomes `:awaiting-review`
- `:complete` — done → Run state becomes `:done`
- `:error` — gave up → Run state becomes `:failed`

The daemon reads this file when the agent exits to decide the Run's terminal state. Ongoing phases (`:investigating`, `:working`) keep the Run in its current state.

## 3. Idempotency

If the skill is re-invoked in a session-home that already has artifacts (e.g., the user re-fired the trigger, or the daemon restarted), read existing artifacts and resume — don't blindly overwrite.
