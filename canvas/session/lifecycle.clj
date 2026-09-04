(ns canvas.session.lifecycle
  "Self-spec: `nido.session.lifecycle` — bringing a session up, down and back."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.state :as sstate :refer [InstanceId]]
            [fukan.common.typing.malli]))

(Module session-lifecycle
  "The verbs a person or a lane uses on a session: up, down, restart, reset, destroy, enter.

   Everything here is keyed by a session NAME and resolves its own coordinates, which is what
   lets a task say `up! \"fix-thing\"` without knowing where a worktree lives or what an instance
   id is. The resolution order — explicit option, then cwd, then registry — is why a command run
   inside a worktree needs no arguments at all.

   Down PRESERVES the worktree and the state directory; destroy is the one that does not. Two
   verbs rather than a flag, because the destructive one should have to be named."
  (Operation resolve-project "The active project: explicit, then cwd, then registry."
    {:signature [:=> [:catn [:opts :map]] [:maybe ProjectName]]})
  (Operation worktrees-dir "Where this project's worktrees live."
    {:signature [:=> [:catn [:project-name ProjectName] [:project-dir Path]] Path]})
  (Operation worktree-path "A named session's worktree."
    {:signature [:=> [:catn [:project-name ProjectName] [:project-dir Path] [:name :string]] Path]
     :delegates [worktrees-dir]})
  (Operation canonical "An absolute, symlink-resolved path — how two spellings of one worktree
     are recognised as the same worktree."
    {:signature [:=> [:catn [:p :any]] Path]})
  (Operation session-from-cwd "Which session a directory belongs to, via the worktree registry."
    {:signature [:=> [:catn [:cwd [:? :any]]] [:maybe :map]] :delegates [canonical sstate/read-registry]})
  (Operation create-symlink-worktree!
    "Point a worktree at an existing checkout. Refuses when something is already there — a lite
     session must never overwrite a real worktree."
    {:signature [:=> [:catn [:wt-path Path] [:target Path]] :any]})
  (Operation remove-symlink-worktree!
    "Delete the symlink. Refuses to recurse, because following it would delete the checkout."
    {:signature [:=> [:catn [:wt-path Path]] :any]})
  (Operation session-coords "A named session's worktree path and instance id."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :map]
     :delegates [resolve-project worktree-path]})
  (Operation session-weight "The weight a named session's record should carry, read from its profile."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :keyword] :delegates [session-coords]})
  (Operation up! "Bring a session up: worktree if missing, then its services."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :any] :delegates [worktree-path]})
  (Operation down! "Stop a session. Worktree and state are PRESERVED."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :any]})
  (Operation restart! "Stop then start, worktree untouched."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :any]})
  (Operation reset!
    "Recovery for a session in a bad state: stop it, clear what can be rebuilt, bring it back."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :any]})
  (Operation isolate!
    "Move a session onto a PRIVATE Postgres clone — what you do before something destructive,
     so the shared cluster is not what you are experimenting on."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :any]
     :delegates [sstate/write-pg-mode-override!]})
  (Operation share! "Move a session back onto the shared cluster."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :any]
     :delegates [sstate/clear-pg-mode-override!]})
  (Operation destroy! "Bring a session down and remove its worktree and state. The one that does not preserve."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :any]})
  (Operation status "What state a named session is in."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :any]})
  (Operation cd-target-file
    "The file a shell wrapper polls to learn where to cd. A process cannot change its parent's
     directory, so the handoff is a file the wrapper reads after nido exits."
    {:signature [:=> [:catn] Path]})
  (Operation resolve-cd-target "The directory entering a session should land in."
    {:signature [:=> [:catn [:name :string] [:opts :map]] [:maybe Path]]})
  (Operation enter! "Hand a directory to the parent shell."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :any] :delegates [resolve-cd-target cd-target-file]})
  (Operation warp? "Whether this is running inside Warp — the one terminal that can open a tab where we want it."
    {:signature [:=> [:catn] :boolean]})
  (Operation spawn-tab! "Open a terminal tab at a session's worktree."
    {:signature [:=> [:catn [:name :string] [:opts :map]] :any] :delegates [resolve-cd-target]})
  (Operation list-all-data "Every session for a project, as data."
    {:signature [:=> [:catn [:opts :map]] :map] :delegates [resolve-project sstate/read-registry]})
  (Operation list-all "Every session for a project, printed."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [list-all-data]})
  (Operation session-home-coords-from-cwd "The session whose HOME a directory is, if it is one."
    {:signature [:=> [:catn [:cwd [:? :any]]] [:maybe :map]]})
  (Operation worktree-from-cwd "The worktree a directory belongs to, from anywhere inside it."
    {:signature [:=> [:catn [:cwd [:? :any]]] [:maybe Path]]
     :delegates [session-home-coords-from-cwd]})
  (Operation link-add! "Add a link to the resolved session."
    {:signature [:=> [:catn [:session-arg :any] [:opts :map]] :any]})
  (Operation link-remove! "Drop a link from the resolved session."
    {:signature [:=> [:catn [:session-arg :any] [:opts :map]] :any]})
  (Operation link-list "Print the resolved session's links, grouped."
    {:signature [:=> [:catn [:session-arg :any] [:opts :map]] :any]})

  (Operation classify-push
    "What a `jj git push` actually did, read from its output rather than its exit code.
     Pure, and separate from the push because this is the fact a landing is recorded on:
     jj exits 0 whether it pushed, the remote already matched, or there was no such
     bookmark, and the last two both print `Nothing changed.` — so exit status cannot
     tell a landing from a bookmark that was never set. :advanced and :already-there are
     both safe to record against; an unrecognised shape is refused rather than assumed."
    {:signature [:=> [:catn [:result :map]] :map]})
  (Operation advance-remote!
    "Point a bookmark at a revision and push it — the only thing in nido that advances a
     remote ref. Never throws on a push that did not land: the outcome is the caller's to
     read, because the caller is the only one that knows what an unpushed revision means
     for the record it was about to write."
    {:signature [:=> [:catn [:worktree Path] [:bookmark :string] [:rev :string]] :map]
     :delegates [classify-push]}))
