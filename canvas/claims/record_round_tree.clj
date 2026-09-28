(ns canvas.claims.record-round-tree
  "The claims the record-round-tree design commits nido to: a record round given no tree reads the
   one its record is about, not whatever the session worktree holds.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.review.record :refer [review-record]]
            [canvas.review.tree :refer [review-tree with-reading!]]
            [canvas.tasks.nido-review :refer [baseline-cmd* claiming design-cmd*]]))

(Claim baseline-read-at-the-fork-point
  "Given no :code-cwd, every judge and amender a baseline round launches reads a tree whose content is the fork point of the worktree's @ with main. The round reads the worktree in place when @ does not differ from that commit, and when no fork point can be resolved, which it says."
  {:about [review-tree baseline-cmd*] :evidence "round"})

(Claim design-read-at-the-base-with-its-declaration
  "Given no :code-cwd, every judge and amender a design round launches reads the fork point's tree with the worktree's spec dirs in place of the fork point's. The round reads the worktree in place when @ differs from the fork point only inside those dirs."
  {:about [review-tree design-cmd*] :evidence "round"})

(Claim resurvey-read-as-a-baseline
  "A design round's re-survey reads the tree a baseline round given no :code-cwd would read, and the design round's own :code-cwd when it was given one."
  {:about [review-record review-tree] :evidence "round"})

(Claim named-tree-read-as-given
  "A round given :code-cwd reads exactly that directory, re-survey included, and no tree is produced for it."
  {:about [baseline-cmd* design-cmd*] :evidence "round"})

(Claim produced-tree-removed
  "A tree with-reading! produces is gone once the function it was handed returns or throws: its working-copy commit abandoned, its workspace forgotten and its directory deleted. A tree it cannot produce throws before the function is called."
  {:about [with-reading!] :evidence "round"})

(Claim round-names-its-tree
  "A round reading anything but the worktree as it stands prints the commit it reads before it takes the claim. Its claim target is the record :seq with the reading in place of a directory — the worktree it reads in place, or the fork point with the names of the directories laid over it — so a round of one kind given no tree joins one already running at the same :seq and the same reading, rather than being refused as other work."
  {:about [claiming review-tree] :evidence "round"})
