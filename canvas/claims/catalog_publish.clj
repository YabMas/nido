(ns canvas.claims.catalog-publish
  "The claims the catalog-publish design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.chat :refer [session-change onto-main!]]
            [canvas.tasks.nido-land :refer [gate! land]]
            [canvas.tasks.nido-recovery :refer [nido-recovery]]
            [canvas.ui.chat-host :refer [ui-chat-host]]
            [canvas.ui.catalog-chat :refer [ui-catalog-chat]]))

(Claim landing-names-its-revision
  "A landing lands one revision fixed when it starts — named by its caller, or the cwd's tip read once. Its tests run over that revision's arc duplicated onto origin's main, less each copy main's content empties, in a jj workspace the gate creates for the landing and forgets after it; when no copy is left the gate refuses, as there is nothing to land. The gate runs bb nido:test and bb nido:land:check each as a process in that workspace, so each runs the code and reads the files of that tree; the check takes its workstream from the landing's cwd. The landing's own code is what bb loads from the cwd's tree when bb nido:land starts, before it prints its first `land ·` line. Nothing written to any worktree after the start reaches the tested tree, the checking code, the checked files or the pushed commit, and nothing written after that first line reaches the landing's own code."
  {:about [gate! land] :evidence "round"})

(Claim landing-rewrites-only-its-own
  "The gate rewrites commits outside its own workspace in two ways, each leaving the cwd's workspace current: the snapshot jj records of the cwd's working copy when the gate runs jj there, and, for a landing its caller named no revision for, bringing the cwd onto origin's main once origin's main has taken the push — a rebase of the cwd's arc onto it that drops each commit main's content empties, so a later landing from that cwd does not carry again what this one landed; a rebase that conflicts stays in the cwd with its conflicts, and the gate names the paths. A landing told its revision, as Publish is, leaves the cwd's commits where they were. It undoes a conflicting duplicate by abandoning the commits it made. No step of a landing runs `jj op restore`: the restart step undoes a failed rebase of the daemon's checkout by reverting that one operation."
  {:about [gate! land] :evidence "round"})

(Claim one-landing-gate
  "bb nido:land (the catalog's Publish runs it) and bb nido:recovery:land push main only through gate!, which records nothing."
  {:about [gate! land nido-recovery] :evidence "round"})

(Claim landing-recorded-as-the-gates-commit
  "land records on its cwd's workstream, and the :merged it appends names the commit the gate returned, not the worktree's tip."
  {:about [land] :evidence "round"})

(Claim chat-open-while-publishing
  "A publish never refuses a send: the catalog host's :holds? does not read the publish state. The catalog chat accepts a send, seals for a Publish and rejoins main each under one lock of its own, the worktree lock, and under it seals or rejoins only when no turn and no publish runs; a send that finds the lock held waits for it. A Publish keeps the lock from its seal until bb nido:land has printed its first `land ·` line or exited, so the verb loads its code from the sealed revision's tree [landing-names-its-revision]. Publish carries the revision of the change the bar rendered, and seals exactly that revision, so the agent's next edits live on a change above it; when the worktree's change is another revision by then, it seals nothing and the bar is rendered again."
  {:about [ui-catalog-chat ui-chat-host] :evidence "round"})

(Claim newer-work-joins-main-between-turns
  "Whether the design worktree owes a rejoin is read from jj — origin's main is not an ancestor of its working copy — and never held. At a landing's end, a turn's end, a catalog stream opening or a Publish press, the catalog takes the worktree lock [chat-open-while-publishing] names and, when a rejoin is owed and neither a turn nor a publish runs, rebases the worktree onto origin's main under it, dropping commits that main's content empties; so a dashboard restarted after a landing rejoins the worktree when the catalog is next opened. A rebase that conflicts stays in the worktree with its conflicts, the chat says which paths, and Publish is withheld while the change holds a conflict."
  {:about [onto-main! ui-catalog-chat] :evidence "round"})

(Claim bar-shows-landing-apart
  "While the dashboard holds a publish's sealed revision — as the publish runs, and after it lands until the worktree is on main — the change bar shows as the change landing the sealed arc — the commits of origin's main..that revision, taken together, which is what the gate lands — with its step, and as pending the diff from that revision to the worktree's tip, each read from jj at render. A Publish seals only an arc that sits on origin's main: a press that finds a rejoin owed rejoins as [newer-work-joins-main-between-turns] says, seals nothing, and the bar is rendered again. The sealed revision is held in the dashboard's memory alone: a publish that pushes nothing drops it, and so does a dashboard restart, after which the bar shows one pending change."
  {:about [ui-catalog-chat] :evidence "round"})

(Claim refusal-keeps-newer-work
  "A publish refused at any step before origin's main takes the push loses none of the sealed change or the edits above it: the landing changes the content of none of them, the bar lists them together as the pending change, and a rejoin that follows carries them onto main, conflicts included, as [newer-work-joins-main-between-turns] says; the chat note says it was refused and why, and none of the newer edits is pushed. bb nido:land says when origin's main has taken the push, and the catalog reads a publish as landed by that line, not by the verb's exit code, so a landing whose recording or owed restart fails afterwards is reported as published, with what failed."
  {:about [session-change ui-catalog-chat land] :evidence "round"})
