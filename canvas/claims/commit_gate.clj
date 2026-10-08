(ns canvas.claims.commit-gate
  "The claims the commit-gate design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.commit-gate :refer [session-commit-gate verdict checks push-range commit-msg-hook
                                                with-gate settings-json]]
            [canvas.session.briefing :refer [session-launcher session-briefing read-project-briefing]]
            [canvas.tasks.nido-work :refer [nido-work]]
            [canvas.coordinator.record.runs :refer [launch-context]]
            [canvas.coordinator.agent :refer [launch!]]))

(Claim rejected-push-is-blocked
  "For a `jj git push` the gate runs the project's executable commit-msg hook over every described commit the push would publish — as jj reports it for the same arguments with --dry-run: reachable from a ref it would add or move and from no remote ref, whatever selected those refs — and a non-zero exit blocks the command with a reason carrying the hook's own output."
  {:about [verdict push-range commit-msg-hook] :evidence "test"})

(Claim hook-found-in-either-worktree
  "The hook the gate runs is the executable commit-msg hook at git's hooks path for the repository behind the command's directory: the repository `jj git root` names in a jj workspace, the worktree's own repository in a plain-git worktree."
  {:about [commit-msg-hook] :evidence "round"})

(Claim rule-is-borrowed
  "The gate holds no commit convention of its own: whether a message passes is exactly the project's hook's exit status."
  {:about [session-commit-gate] :evidence "round"})

(Claim no-hook-no-change
  "Where the repository behind the command's directory has no executable commit-msg hook, the gate lets every command through."
  {:about [commit-msg-hook verdict] :evidence "test"})

(Claim gate-fails-open
  "A part of a command the gate cannot read (a substitution, an unknown directory), a jj or hook that cannot answer within its timeout, and any throw each let the command through."
  {:about [verdict checks] :evidence "round"})

(Claim titles-judged-like-commits
  "A `gh pr create` or `gh pr edit` with a literal title is judged by the same hook as a commit description, whatever the project's merge strategy."
  {:about [checks verdict] :evidence "test"})

(Claim gate-reaches-every-claude-launch
  "Every claude nido starts for a session agent carries the gate: the home's settings.local.json has it beside the Stop hook, nido work passes it as --settings, and a Run's launch-context carries it for the lanes to pass to launch! as --settings."
  {:about [with-gate settings-json session-launcher nido-work launch-context launch!] :evidence "round"})

(Claim briefing-defers-subject-form
  "The description doctrine in every briefing defers the subject's form to the project's convention, giving its own form only for a project that states none; brian's project briefing names docs/guidelines/commits.md."
  {:about [session-briefing read-project-briefing] :evidence "round"})
