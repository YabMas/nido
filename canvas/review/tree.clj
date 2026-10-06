(ns canvas.review.tree
  "Self-spec: `nido.review.tree` — which tree a record round reads when its caller names none,
   and producing it."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module review-tree
  "The tree a record is judged against, chosen by what the record is ABOUT rather than by what
   the session worktree happens to hold.

   A baseline describes the area before the change, so it is read at the fork point of the
   worktree's @ with main — the commit a diff review measures the branch from. A design adds its
   declaration to that base and no code, so it is read at the fork point with the worktree's spec
   dirs laid over it: the bare base would not declare its subjects, and the worktree would show
   code nobody has decided on yet.

   Where the worktree already is that tree it is read in place and nothing is produced. Otherwise
   the tree is a jj workspace added for the length of the round and removed after it, so the
   stages that read jj at the tree — its identity, the amender's guard — read it as they read a
   worktree."
  (Operation reading
    "Where a round of a kind reads when it is given no tree: the worktree, or a revision with the
     directories of the worktree to lay over it. Reads jj and writes nothing."
    {:signature [:=> [:catn [:kind [:enum :baseline :design]] [:project [:maybe ProjectName]]
                            [:worktree Path]] :map]})
  (Operation line
    "What a round says about its tree before it launches anything, or nil when it reads the
     worktree as it is."
    {:signature [:=> [:catn [:reading :map]] [:maybe :string]]})
  (Operation stamp
    "Which revision a round reading a tree in a directory judges, as a judgement records it: the
     fork point, or the working-copy commit of a tree read as it stands. Reads jj, or git, and
     never throws."
    {:signature [:=> [:catn [:reading :map] [:dir Path]] :map]})
  (Operation heal-stale!
    "Heal a working copy jj refuses as stale, only when the files on disk are both the tree it was
     checked out from and the tree it would move to, so the heal changes and records nothing; else
     leave it and say what a person must run. Nil when the copy is not stale."
    {:signature [:=> [:catn [:dir Path]] [:maybe [:map [:healed :boolean] [:line :string]]]]})
  (Operation with-reading!
    "Call a function with the directory a reading names, producing it first when it names a
     revision and removing it when the function returns or throws."
    {:signature [:=> [:catn [:worktree Path] [:reading :map] [:ws-name :string] [:dir Path]
                            [:f [:=> [:cat Path] :any]]] :any]}))
