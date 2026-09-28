(ns canvas.claims.goal-amendment
  "The claims the goal-amendment design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [lane-pipeline amendment-owed]]
            [canvas.coordinator.record.workstream :refer [append-entry! unit-of goal-amendment]]
            [canvas.tasks.nido-attach :refer [skip-lines amendment-lines]]
            [canvas.tasks.nido-workstream :refer [nido-workstream]]))

(Claim second-goal-said-at-append
  "An :intent appended to a workstream that already holds an intent is accepted only when it carries exactly one of :supersedes, naming an intent no other intent supersedes, or :independent true. One carrying neither is refused, and the refusal names the newest such intent as the :supersedes it most likely meant, and :independent true and a fork as the ways to open a second unit. The first intent on a workstream carries neither."
  {:about [append-entry!] :evidence "test"})

(Claim independent-opens-a-unit
  "An intent carrying :independent true is a root: it cites nothing, so unit-of, root agreement and standing read it exactly as they read an intent citing nothing, and it opens a unit of its own and continues none."
  {:about [unit-of] :evidence "test"})

(Claim one-rendering-of-an-owed-amendment
  "What one goal amendment has left to do is read once, off the ledger's citations and keyed by a goal of the unit asked about — the replaced goal a refused record reaches, or the goal the newest survey cites: that unit's live goal, the goal it replaced, and its newest baseline and newest design with whether each still reaches a replaced goal. The ledger answers seqs only; a replacement counts by the goal it cites, since the ledger does not require it to supersede what it replaces; and its append refusal of a record over a replaced goal names the records still standing on the replaced goal and carries the reading as data. The workstream position turns that reading, with whether standing verifies that unit's newest survey and decides its newest design, into the stages that unit still owes, in order — a rebaseline while its newest survey stands on the replaced goal, its verification while its newest survey is not verified, a design while its newest one stands on the replaced goal or a rebaseline is owed, its decision while its newest design is not decided — with their seqs and no text, as one function of a goal. A next action whose stage is the first of the stages owed by the unit of its newest survey's goal carries them as :owed; where a blocker or retraction outranks the amendment the next action carries none. The entry task's refusal of a record over a replaced goal does not read the next action: it asks that function for the unit of the replaced goal the refusal carries, so the first refusal a stale baseline or design meets names that unit's whole chain whatever holds the position. Those stages are worded once, at the task level beside attach's skip lines, and attach (the :owed of its next action) and the entry task (after appending an intent, what that intent's unit owes; printing a refusal over a replaced goal, what the refused unit owes) print them in that wording and no advice text of their own about the chain. The land gate's way-out for a moved goal keeps its own per-reason text, which already names the whole chain."
  {:about [goal-amendment amendment-owed lane-pipeline amendment-lines skip-lines nido-workstream] :evidence "test"})

(Claim goal-amendment-reseats-nothing
  "Every workstream on disk when this lands keeps the position it had before, and the stage and mode its next action fires: nothing in standing or the pipeline's position reads :independent, :owed only rides beside the stage, and the new refusal acts only on an append, and no ledger on disk carries a :beside, so trading it for :independent reads nothing differently and a ledger already holding several root intents reads exactly as it did."
  {:about [lane-pipeline] :evidence "round"})
