(ns canvas.claims.background-boot
  "The claims the background-boot design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.lifecycle :refer [up!]]
            [canvas.session.provisioning :refer [start-session!]]
            [canvas.session.state :refer [upsert-registry! RegistryEntry entry-phase]]
            [canvas.session.briefing :refer [session-launcher session-briefing write-artifacts!]]
            [canvas.session.failure :refer [session-failure]]
            [canvas.coordinator.work :refer [prune-dead-registry! live-session-names]]
            [canvas.ui.surface :refer [dev-state-for]]))

(Claim addressable-before-services
  "up! with :wait false returns once the worktree, the profile snapshot, the session home and a registry entry naming a live boot process exist, without waiting on any service of the session."
  {:about [up!] :evidence "round"})

(Claim blocking-up-is-the-default
  "up! without :wait false returns only after start-session! has returned, exactly as before; only the person-facing starts — the session:up task, the TUI's up and add (work/start! and work/new!) — pass :wait false."
  {:about [up!] :evidence "round"})

(Claim the-boot-is-an-ordinary-up
  "The background boot is a detached process running the blocking up! for the same session on the same runtime key, under the same lifecycle locks; it does not depend on the process that spawned it staying alive, and nothing in it is specific to having been spawned."
  {:about [up! start-session!] :evidence "round"})

(Claim placeholder-names-a-boot-and-no-port
  "A registry entry written before services are up is a RegistryEntry boot placeholder — the boot process's pid, the runtime key and session name the boot comes up under, and no app, nREPL or Postgres port — and the boot's own final upsert replaces it whole. Readers decide what kind of entry they hold only through entry-phase, which reads a placeholder :booting, so no liveness reading treats it as up."
  {:about [upsert-registry! RegistryEntry entry-phase live-session-names] :evidence "round"})

(Claim starting-is-derived-from-the-placeholder
  "A session reads :starting in every process while its registry entry names a live boot pid and its app port does not answer; once the boot ends it reads running or down from the same probe as before."
  {:about [dev-state-for] :evidence "round"})

(Claim briefing-never-claims-unstarted-services
  "The briefing says services are running only when a port for them is known; a session whose profile provisions services and has no known port is briefed that they are starting and how to check, and a profile with none is briefed as lite."
  {:about [session-launcher session-briefing write-artifacts!] :evidence "round"})

(Claim a-failed-boot-is-kept-and-cleared
  "A background boot whose start throws keeps the failure as any start does, rolls back what it started and removes its placeholder; a placeholder whose boot process died without finishing is prunable."
  {:about [up! session-failure prune-dead-registry!] :evidence "round"})

(Claim one-boot-per-session
  "A :wait false up! on a session whose placeholder names a live boot process, or whose runtime is already up — which it joins in place, as a blocking up! would — spawns no boot."
  {:about [up!] :evidence "round"})
