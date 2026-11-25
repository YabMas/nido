(ns canvas.claims.error-band
  "The claims the error-band design commits nido to, declared where they live on. A stopped
   conductor is a :system-error on Needs you (canvas.claims.activity-model); what its card says
   and how it is recovered are these.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.core.turn :refer [stderr-tail]]
            [canvas.coordinator.work :refer [gate resolve-gate! person-says!]]))

(Claim an-error-says-what-failed
  "A stopped conductor's :system-error card names what failed, says why from the conductor turns that failed since one last did not — each turn's recorded reason in words and, when the core kept it, the last its run wrote to stderr — and offers one action, :wake, and no question. A conductor run that ends without an outcome has that stderr tail recorded on its turn's :detail."
  {:about [gate stderr-tail] :evidence "test"})

(Claim wake-is-the-only-way-through-a-stop
  "resolve-gate! :wake answers the open machine failure and nothing else; an option, a reply or a withdrawal answers the oldest open question and never a machine failure. person-says! words on a workstream holding only a stop are posted to the conductor's chat and then wake it."
  {:about [resolve-gate! person-says!] :evidence "test"})
