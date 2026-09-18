(ns canvas.claims.stratum-judges
  "The claims the stratum-judges design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.review.record :refer [design-decision! design-prompt parse-stratum-reading run-figures stratum-prompt]]))

(Claim levels-judged-apart
  "Before its decision judge, a design round launches one read-only judge per declared stratum the design names, each shown only that level — its declared vocabulary, its modules, the strata it rests on and those resting on it — and the parts of the design that touch it; a design naming no declared stratum launches none."
  {:about [design-decision!] :evidence "round"})

(Claim level-answers-three
  "A stratum judge answers three questions of its own level and nothing else — whether what the design needs from it can be built from what it already provides, whether the design asks it for something outside its vocabulary, whether the part placed in it belongs there — with a closed verdict of fits, widens or misplaced, and its reason; an answer outside that vocabulary is unusable."
  {:about [stratum-prompt parse-stratum-reading] :evidence "round"})

(Claim levels-inform-the-decision
  "The decision judge is shown each named stratum's declared vocabulary and what that stratum's judge concluded, attributed to the stratum under the stratified check, and remains the only judge whose answer decides the round."
  {:about [design-prompt] :evidence "round"})

(Claim decision-records-levels
  "The decision a round appends records, for each named stratum, the verdict and reason its judge gave or the outcome that stood in for one; a stratum judge appends nothing itself, and one that fails leaves the round to decide without it, saying so."
  {:about [coordinator-report design-decision!] :evidence "round"})

(Claim level-figures
  "run-figures derives, for each stratum a run's decisions read, the rounds it was read in and how many times each verdict was given, beside the per-check figures, from the decisions alone."
  {:about [run-figures] :evidence "round"})
