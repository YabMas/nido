(ns canvas.claims.silent-stall
  "The claims the silent-stall design commits nido to: a person's blocker answer and their Wake
   reach the conductor's inbox, and a workstream the core is the mover of that nothing will move
   needs the person as a system error, wherever Needs you is read.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.view.activity :refer [of]]
            [canvas.coordinator.work :refer [coordinator-work resolve-gate!]]
            [canvas.core.buffer :refer [core-buffer]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.tasks.nido-workstream :refer [blocker-answer*]]))

(Claim blocker-answer-wakes
  "An :append-entry of a :blocker-answered row is decided to events that put exactly one item, naming the entry's seq, in the conductor's inbox. It is one of person-input-kinds, and the gate resolver and the blocker-answer verb both record their answer through that command, so either one wakes a conductor that is open, not stopped and running no turn, with or without a live session."
  {:about [core-domain resolve-gate! blocker-answer*] :evidence "test"})

(Claim recorded-events-fold-as-before
  ":entry-appended's fold changes only the ledger index, whatever the entry's kind. The inbox item a blocker answer carries comes from a separate event that only new decisions yield, so folding events recorded before this change gives the state it gave before, inbox ids included."
  {:about [core-domain] :evidence "test"})

(Claim stall-needs-you-as-error
  "of reads a workstream as :stalled when nido owes its next move and nothing will make it: the core holds it, with the recipe activated on it or an open unit of it nobody holds; the move is an agent's or the machine's; the core does nothing and is owed nothing, nothing is underway (a merge waiting its turn included), no pause stands and no failure only the person clears stands; and its observation's :waits-on names no deliberate wait. coordinator-work sets :waits-on for two waits only, each a move something other than the idle core makes: :person-act for a row on an intake band (:incoming, :triage or :ready), where the board offers the person the Promote or Apply that moves it, and :open-slice for a drain while a slice of it is open, that slice read by this same rule. A stalled answer has :needs-you, :needs :system-error and :owed-by :person. needs-categories stays :ask, :landing and :system-error."
  {:about [of coordinator-work] :evidence "test"})

(Claim stalls-carded-without-rows
  "read-gates cards, for each project, every workstream the core holds whose observation of answers :stalled, reading the core's held state and the work records its observation is built from rather than this process's held rows, so the Needs-you list and the tally read from read-gates include it in a process that holds no rows or has built none. The CLI list's 'waiting on a person' count stays the list-workstreams rows whose of-answer has :needs-you, which a stall now has."
  {:about [coordinator-work of] :evidence "test"})

(Claim stall-card-wakes
  "A stalled workstream's Needs you card offers Wake, which takes no words. resolve-gate! answers a Wake on that card with one :wake-conductor command, whose admitted event puts a :woken item, one of person-input-kinds, in the conductor's inbox. The command is refused on a closed workstream, a stopped conductor (its stop ask is how that one is woken) and a conductor turn already running or resolved."
  {:about [coordinator-work resolve-gate! core-domain] :evidence "test"})

(Claim conductor-hears-what-woke-it
  "The conductor's brief describes a blocker-answer item as the person's answer at that ledger seq, to read and act on, and a :woken item as the person waking a stalled workstream with nothing running or owed. Neither is rendered as its raw kind."
  {:about [core-buffer] :evidence "test"})
