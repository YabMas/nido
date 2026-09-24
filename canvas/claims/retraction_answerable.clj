(ns canvas.claims.retraction-answerable
  "The claims the retraction-answerable design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.workstream :refer [append-entry!]]))

(Claim only-supersedable-kinds-retractable
  "A :retraction is accepted only when it names a :baseline, :design or :intent on the same workstream; one naming a :triage or any other kind is refused, and the refusal says that nothing can supersede that kind."
  {:about [append-entry!] :evidence "test"})
