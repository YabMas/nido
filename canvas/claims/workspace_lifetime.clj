(ns canvas.claims.workspace-lifetime
  "The claims the workspace-lifetime design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.review.tree :refer [reap-ended!]]
            [canvas.session.misc :refer [forget-dangling-everywhere! forget-dangling-workspaces!]]))

(Claim live-run-tree-is-kept
  "reap-ended! leaves the tree of a run whose report says running, or that has no readable report, registered and on disk."
  {:about [reap-ended!] :evidence "round"})

(Claim forgetting-keeps-what-was-written
  "forget-dangling-workspaces! never forgets the default workspace or a workspace whose directory exists, and issues no abandon: a forgotten workspace's working-copy commit is left to jj's forget, which drops an empty, undescribed one and keeps a non-empty one as a visible head."
  {:about [forget-dangling-workspaces!] :evidence "round"})

(Claim tested-against-real-jj
  "bb nido:test passes and includes tests that run reap-ended! and forget-dangling-workspaces! against a jj repo the test creates, covering each of [ended-run-tree-is-reaped], [live-run-tree-is-kept], [dangling-registration-is-forgotten] and [forgetting-keeps-what-was-written]."
  {:about [reap-ended! forget-dangling-workspaces!] :evidence "round"})
