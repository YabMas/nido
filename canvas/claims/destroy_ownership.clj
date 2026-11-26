(ns canvas.claims.destroy-ownership
  "The claims the destroy-ownership design commits nido to, declared where they live on: a
   destroy removes only the session's own worktree, and the core records a refused one as such.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.lifecycle :refer [session-lifecycle destroy!]]))

(Claim remove-only-owned-worktree
  "Session-lifecycle removes a worktree directory only when that path, judged without following a final symlink, is the named session's own worktree-path (the project's worktrees dir joined with the session name), lies strictly inside the project's worktrees dir, is not the project directory or an ancestor of it, and holds neither a .jj/repo directory nor a .git directory. Otherwise destroy! (and the poisoned-worktree recreate in up!) throws {:reason :not-owned} before stopping, detaching or removing anything."
  {:about [session-lifecycle destroy!] :evidence "test"})

(Claim owned-destroy-unchanged
  "A destroy! of a session whose worktree is its own worktree-path — a jj workspace, git worktree or symlink inside the project's worktrees dir, under a slash-namespaced name as under any other — removes it exactly as before."
  {:about [destroy!] :evidence "test"})
