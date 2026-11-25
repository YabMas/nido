(ns canvas.claims.error-band
  "The claims the error-band design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.core.domain :refer [machine-ask? activity]]
            [canvas.core.turn :refer [stderr-tail]]
            [canvas.coordinator.view.activity :as act]
            [canvas.coordinator.work :refer [read-gates gate error resolve-gate! person-says!]]
            [canvas.ui.surface :refer [needs-fragment rail-status-fragment workstream-pane error-mark]]))

(Claim a-failure-is-no-question
  "An open ask the core raised because a workstream's conductor stopped is a machine failure. On a workstream that is not settled, read-gates lists it under :errors, gate answers no card for the failure, and its board row carries :failed; one holding only that failure is not under :gates and its row has :needs-you off; one holding an open question as well is under both lists, its gate shows the question, and its row carries :needs-you and :failed together; one holding only questions is under :gates, not under :errors, and its row has :failed off. A settled workstream — its ledger closed with any outcome but :between-phases, or the core closed it — is on neither list, whatever the core still holds open, and its row has both off."
  {:about [machine-ask? activity act/of read-gates gate error] :evidence "test"})

(Claim an-error-says-what-failed
  "An error card names what failed, says why from the conductor turns that failed since one last did not — each turn's recorded reason in words and, when the core kept it, the last its run wrote to stderr — and offers one action, :wake. A conductor run that ends without an outcome has that stderr tail recorded on its turn's :detail."
  {:about [error stderr-tail] :evidence "test"})

(Claim wake-is-the-only-way-through-a-stop
  "resolve-gate! :wake answers the open machine failure and nothing else; an option, a reply or a withdrawal answers the oldest open question and never a machine failure. person-says! words on a workstream holding only a stop are posted to the conductor's chat and then wake it."
  {:about [resolve-gate! person-says!] :evidence "test"})

(Claim errors-have-their-own-icon
  "The top bar carries a third icon and count, Errors, beside Needs you and the release candidates; the home column lists the errors in a section of their own, each row opening its workstream's pane, where the error, its reasons and its Wake button are shown apart from any question card."
  {:about [needs-fragment rail-status-fragment workstream-pane error-mark] :evidence "round"})
