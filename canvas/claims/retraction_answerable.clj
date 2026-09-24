(ns canvas.claims.retraction-answerable
  "The claims the retraction-answerable design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [lane-pipeline of]]
            [canvas.coordinator.record.workstream :refer [append-entry!]]))

(Claim only-supersedable-kinds-retractable
  "A :retraction is accepted only when it names a :baseline, :design or :intent on the same workstream; one naming a :triage or any other kind is refused, and the refusal says that nothing can supersede that kind."
  {:about [append-entry!] :evidence "test"})

(Claim retracted-intent-answered-by-superseding-intent
  "When the newest retraction names an intent, the workstream is at :intent-retracted until an intent whose :supersedes names that seq is appended, and is then placed as though the retraction were answered, like a baseline or design supersession answers its own kind's retraction."
  {:about [of] :evidence "test"})

(Claim retracted-position-advises-its-own-repair
  "Each retracted position's next stage writes a record of the retracted kind: :design-retracted goes to design, :premise-retracted to rebaseline, :intent-retracted to establish-intent. No retracted position advises a stage whose record could not answer it."
  {:about [of] :evidence "test"})

(Claim no-live-workstream-reseated
  "Every workstream on disk when this lands keeps the position it had, except one whose newest retraction names an intent, which moves from :premise-retracted to :intent-retracted."
  {:about [lane-pipeline] :evidence "round"})
