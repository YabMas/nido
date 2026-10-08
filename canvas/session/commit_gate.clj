(ns canvas.session.commit-gate
  "Self-spec: `nido.session.commit-gate` — the project's commit convention, held in a session."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module session-commit-gate
  "Hold a session's agent to its project's own commit-message convention, by borrowing the
   project's git `commit-msg` hook rather than re-encoding its rule.

   It exists because a session worktree is a jj workspace and jj runs none of the source
   repository's git hooks, so a convention a project enforces only there is unenforced in every
   session. It hides how a shell command is read for what it would publish — the push's range,
   the PR's title — and how the hook is found behind a jj workspace.

   FAILING OPEN IS THE CONTRACT. Anything it cannot read or run lets the command through: it is
   installed in every session of every project, and one that blocked what it could not reason
   about would be worse than none."
  (Operation checks
    "What a Bash command would publish that the project's hook should judge: each `jj git push`
     with its targets, each titled `gh pr create`/`edit` with its title, and the directory each
     runs in. A part decided by a substitution contributes nothing."
    {:signature [:=> [:catn [:command :string] [:cwd :string]] [:vector :map]]})
  (Operation push-range
    "The described commits a `jj git push` would publish: reachable from a ref jj reports it would
     add or move when the same arguments are run with --dry-run, and from no remote ref. Nil when
     jj cannot answer."
    {:signature [:=> [:catn [:dir :string] [:args [:vector :string]]] [:maybe [:vector :map]]]})
  (Operation commit-msg-hook
    "The executable `commit-msg` hook of the git repository behind a directory — through `jj git
     root` for a jj workspace — or nil."
    {:signature [:=> [:catn [:dir :string]] [:maybe :string]]})
  (Operation verdict
    "Why a Claude Code `PreToolUse` input must not run — naming what was rejected and carrying the
     hook's own output — or nil to let it."
    {:signature [:=> [:catn [:hook-input :map]] [:maybe :string]]
     :delegates [checks push-range commit-msg-hook]})
  (Operation with-gate
    "A Claude settings map with the gate added as a `PreToolUse` hook on Bash; every hook it
     already declares stays."
    {:signature [:=> [:catn [:settings :map]] :map]})
  (Operation settings-json
    "The gate alone as a settings document, for `--settings` on a launch outside a session home."
    {:signature [:=> [:catn] :string]
     :delegates [with-gate]}))
