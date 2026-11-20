(ns canvas.claims.blocker-stalls
  "The claims the blocker-means-stalled design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor decide! close-unanswered! ConductDecision]]
            [canvas.coordinator.lane.drive :refer [owed-blocker]]
            [canvas.coordinator.lane.seats :refer [owed]]
            [canvas.coordinator.work :refer [gates]]))

(Claim nothing-moves-under-a-blocker
  "An :instruct decision is recorded and posted only when the decided workstream's current generation holds no open blocker other than nido's stand-ins, which the same step withdraws: it appends the decision, then each stand-in's withdrawal, and only then posts to any seat; an instruction to a drain's slice also needs the slice to hold none. Otherwise decide! answers [:refused :blocked] and writes and posts nothing."
  {:about [decide! lane-conductor] :evidence "test"})

(Claim the-conductor-supersedes-nidos-stand-in
  "Every open blocker nido parked as a stand-in escalation of an undecided hand-back is answered, in the same append step as the conductor's next :instruct or :escalate on the workstream — on a hand-back from any session — after that step's decision is appended and before it posts to any seat, by a :blocker-answered :by :nido whose :superseded names that hand-back and the :conduct-decision entry the step appends; an :escalate that withdraws stand-ins and leaves nothing a person owes open parks its own blocker in their place."
  {:about [decide!] :evidence "test"})

(Claim a-withdrawal-is-no-answer-of-the-persons
  "A withdrawal is written :by :nido with :superseded and no :letter, :label, :answers or :grants, so it is never read as an act of the person's; and only a blocker a :by :nido :escalate decision parked is ever withdrawn — a person-owed blocker (a conductor's own escalation, a review halt, a stop, a seat's blocker) leaves Needs you only by an answer the person gives or one of the other cases [nido-answers-only-named-blockers] names."
  {:about [lane-conductor] :evidence "round"})

(Claim a-blocked-hand-back-is-held
  "[:hold {:blocker <seq>}] answers a hand-back while the workstream is blocked, naming the blocker it waits on, and posts nothing to any seat; decide! refuses it [:refused :not-blocked] when no blocker is open. When a conductor turn's last offer ends leaving its hand-back undecided and no later decided hand-back covers it, nido records :hold naming the open blocker while one is open, and escalates as before only while none is."
  {:about [decide! close-unanswered! ConductDecision] :evidence "test"})

(Claim on-needs-you-means-stalled
  "For every blocker the gates show, no :conduct-decision :instruct is recorded on that workstream between the blocker and the entry that answers it, except the one whose step withdrew it as superseded."
  {:about [gates owed-blocker lane-conductor] :evidence "test"})

(Claim a-posted-instruction-waits-out-a-blocker
  "A conductor's instruction already in a seat's log when a blocker opens is given to no seat while that blocker stands: owed answers a seat's occupant no :instruction message while the current generation of the workstream whose log carries it holds an open blocker, nido's stand-ins included, so neither a first delivery nor the retry of a failed one sends it until no blocker is open."
  {:about [owed] :evidence "test"})

(Claim the-change-lands-green
  "The change lands only at a revision where bb nido:test and bb nido:land:check both pass."
  {:about [lane-conductor] :evidence "round"})
