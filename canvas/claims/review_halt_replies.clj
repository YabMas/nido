(ns canvas.claims.review-halt-replies
  "The claims the review-halt-replies design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor stops!]]
            [canvas.coordinator.lane.drive :refer [lane-drive halt-for owed-blocker]]))

(Claim reply-answers-a-review-halt
  "On a conduct pass over a workstream stops! visits — an open one with a session carrying a :claude facet — an open blocker whose last :tried attempt is at verify-baseline or decide-design is answered :by :nido, its summary naming the newer record, iff the ledger holds a record of the kind that stage reviews (baseline, design) with a seq above the blocker's that amended-seqs does not contain — whatever outcome that attempt ended at."
  {:about [stops!] :evidence "test"})

(Claim nido-answer-grants-nothing
  "nido answering a review-stage halt writes only a :blocker-answered naming it — no :design-approved or other grant. Once it is written, that blocker no longer places the position :blocked, and the position is read off the ledger by the rules that already read it; on a ledger shaped like nido ws-20261117-df1d96 entries 42–44, owed then reads decide-design on the newer design."
  {:about [stops! owed-blocker] :evidence "test"})

(Claim after-says-what-moves-it
  "A halt's default :after (halt-for given no :after) is read off the stage of the halt's last :tried attempt, the stage nido's answer reads. When that stage reviews a record kind, it says the workstream stays parked until the person answers or, while it has a session carrying a :claude facet, a newer record of that kind that no review loop's amender wrote is appended, which nido takes as the answer; for every other halt, one with no :tried among them, it says only an answer naming the blocker moves it on."
  {:about [halt-for] :evidence "test"})

(Claim lands-green
  "The change lands only with `bb nido:test` and `bb nido:land:check` both passing over its tip."
  {:about [lane-drive lane-conductor] :evidence "round"})
