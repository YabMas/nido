(ns canvas.claims.destroy-ownership
  "The claims the destroy-ownership design commits nido to, declared where they live on: a
   destroy removes only the session's own worktree, and the core records a refused one as such.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.lifecycle :refer [session-lifecycle destroy!]]
            [canvas.ui.surface :refer [ui-tui]]
            [canvas.core.provision :refer [outcome-command session-name]]
            [canvas.core.domain :refer [decide sessions-owed]]
            [canvas.coordinator.work :refer [coordinator-work]]))

(Claim remove-only-owned-worktree
  "Session-lifecycle removes a worktree directory only when that path, judged without following a final symlink, is the named session's own worktree-path (the project's worktrees dir joined with the session name), lies strictly inside the project's worktrees dir, is not the project directory or an ancestor of it, and holds neither a .jj/repo directory nor a .git directory. Otherwise destroy! (and the poisoned-worktree recreate in up!) throws {:reason :not-owned} before stopping, detaching or removing anything."
  {:about [session-lifecycle destroy!] :evidence "test"})

(Claim owned-destroy-unchanged
  "A destroy! of a session whose worktree is its own worktree-path — a jj workspace, git worktree or symlink inside the project's worktrees dir, under a slash-namespaced name as under any other — removes it exactly as before."
  {:about [destroy!] :evidence "test"})

(Claim tui-removes-nothing-itself
  "The TUI's destroy action removes no directory itself: it calls destroy! and, when the session's listed worktree survives it, reports that worktree and leaves it in place."
  {:about [ui-tui] :evidence "test"})

(Claim unit-named-session
  "core-provision names an attempt's session by its unit when the unit has no recorded cwd or the cwd's path ends in the unit's id, and by the cwd's last segment otherwise; so a unit that never carried an adopted :session-cwd, whose cwd only :up outcomes of the provision verb recorded, is destroyed under its unit's id, whose worktree-path that cwd is, a slash-namespaced unit included."
  {:about [session-name] :evidence "test"})

(Claim refusal-is-a-typed-outcome
  "The provision verb reports a :not-owned refusal from any of its calls as {:error … :refused :not-owned}. core-provision records it as outcome :refused for a :destroyed attempt and as an ordinary :failed outcome with its detail for any other attempt; decide accepts :refused only for a :destroyed attempt, and evolve folds it to session :status :refused keeping :cwd and the reason in :detail — never :destroyed."
  {:about [outcome-command decide] :evidence "test"})

(Claim refused-is-terminal
  "sessions-owed never offers a want for a unit whose session is :refused, so a refused destroy is attempted once and never again by the core."
  {:about [sessions-owed] :evidence "test"})

(Claim refused-is-shown
  "Every unit whose session is :refused, on any workstream including closed and discarded ones, is listed on the Operations page with its workstream, unit and kept path, for as long as it reads :refused."
  {:about [coordinator-work] :evidence "test"})
