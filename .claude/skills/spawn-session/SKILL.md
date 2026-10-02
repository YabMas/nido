---
name: spawn-session
description: Spin a child nido session off the one you are in, with its agent already working a brief in a Warp tab beside you, and keep talking to it by message. Pick the relation first — delta (fork this workstream's design, merge back) or delegate (the child owns its own design in its own focus area). Usage: /spawn-session <what the child should do>
---

# /spawn-session

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/spawn-session/`
> and is injected into every session's composed `.claude/skills/`. It owns the
> PARENT's half of the parent/child protocol; the child's half arrives in the
> child's first prompt, written by `bb nido:session:spawn`.

## 1. Choose the relation

The spawn is the same either way. What differs is whether the child shares your
design, and that is decided before you spawn.

| The child is | Relation | Before spawning |
|---|---|---|
| a piece of THIS unit's design — same scope, same structures, comes home into your design | **delta** | `bb nido:workstream:fork` → the child's ws-id |
| its own story inside the arc you oversee — its own baseline, its own design, lands on its own | **delegate** | nothing |

The test: **would the child's design be merged into yours?** If yes, it is a
delta, and `/design` §5 (fork, then merge back) governs it. If it would land
whatever your design says, it is a delegate — do not fork, or its baseline is
derived from a design it does not share and every round on it judges the wrong
thing.

A delta needs your newest design to stand (cleared or granted); fork refuses
otherwise.

```bash
bb nido:workstream:fork :project <p> :ws-id <yours> :goal "…" :done-when '["…"]'
```

## 2. Write the brief

A file, because a brief does not survive shell quoting. It goes to the child
verbatim, after nido's own lines telling it who you are and when to write to you.
Write what a person new to the arc needs and nothing it can read for itself:

- the goal and what done looks like, falsifiably;
- the part of the arc it owns and what it must not touch;
- for a delta: the fork's ws-id and that its design merges into yours;
- for a delegate: that it runs its own `/design` from intent onward;
- anything you already know that the code does not say.

## 3. Spawn

Run from inside your own session (its home or worktree). Your agent name is
the one other sessions message you by — `ListAgents` prints it on its first line.

```bash
bb nido:session:spawn :project <p> <child-session> \
  :parent-agent <your agent name> :brief-file <path> \
  [:ws-id <child ws-id>] \       # a delta only
  [:permission-mode <yours>]      # when you are NOT bypassing permissions
```

The child runs in `bypassPermissions` unless you pass your own mode. It has to
match yours: a message between sessions in different permission classes waits
for a person to approve it, so a mismatched child cannot hear you unattended.

It brings the child up exactly as `nido:session:up` does (a refusal there is a
refusal here, before anything is made), links the two sessions to each other,
and opens a tab beside yours where the child's agent starts on the brief. The
child's agent answers to `<child-session>`. Record nothing else — the links are
already written on both sides.

If it says the kickoff hook is missing, tell the person: the tab is a bare shell
until they install it (nido's CLAUDE.md, *Spawned sessions start their own
agent*), and the printed command starts the child now.

## 4. Talk to it

`SendMessage` to `<child-session>`. The child writes to you when the brief is
done, when it is blocked on your decision or the person's, and when it finds
something that changes the brief or reaches into another part of your arc.

- **Answer a block promptly** — the child is idle until you do.
- **Direct, don't do.** The child's focus area is its own; send the decision,
  not the code.
- **Each message stands alone.** It may be read after a compaction, so name the
  thing you are answering.
- **The person outranks you both.** A child that says the person redirected it
  is not disobeying you.

## 5. Bring it home

- **Delta:** when the child's design stands, `bb nido:workstream:merge
  :project <p> :ws-id <child>` from your session (`/design` §5).
- **Delegate:** nothing merges. It lands its own work; you note what it means
  for the arc.

Either way, the child's session is the person's to destroy, not yours.
