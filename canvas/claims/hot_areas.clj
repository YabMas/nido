(ns canvas.claims.hot-areas
  "The claims the hot-areas design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.review.record :refer [design-amend-prompt design-decision! design-prompt heat hot-areas hot-figures record-round-veto spent-at-cap]]
            [canvas.strata :refer [record-vocabulary]]))

(Claim hot-areas-derived
  "heat counts, per area of a design, the decisions of its lineage across runs that refuted it since the newest step-back carried out on it. A step-back carried out on an area is one of: a decision whose recorded :recommend is :recut or :resurvey, which re-does the whole design and so counts for every area of it, whether or not it carries a :step-back; a decision recommending :ask whose :step-back names the area, or a person's answer to that ask; or a design that adds to :rejected an entry whose :area is the area, credited to the decision it follows whatever that decision's :step-back says. A :step-back recorded on a decision that carries out none of these ends no count. An area is an element of the design that is neither a module nor a stratum, refuted by a decision with a finding that refutes a claim about it, as refuted-ids reads the finding, and counted once per decision; or a claim about no such element, alone. A confirmation ends no count. An area is hot when its heat reaches one threshold, a constant calibrated against recorded lineages, and hot-areas is computed from the ledger alone."
  {:about [heat hot-areas] :evidence "round"})

(Claim steering-unchanged
  "Hot areas change what a design round's judge and amender are shown and nothing else: whether a round proceeds, asks, stops or ends its run, what its parse accepts, what it hands the amender or withholds, the cap, spent and refuted-running, and the handoff of subjects no reading could rule on, are decided as before the change."
  {:about [design-decision! spent-at-cap record-round-veto] :evidence "round"})

(Claim judge-shown-hot-area
  "A design judge whose round checks a claim in a hot area is shown that area whole: its elements and claims, its whole finding history across the lineage with the findings since its last carried-out step-back marked, the approaches :rejected already gave up for it, and the person's answers bearing on it. It is told that findings repeating in one area say the area's approach is off, so it should step back on the area — :restate its approach, :recut, :resurvey, or :ask the person one area-level question stating the area's accumulated findings, the approaches given up and the trade-off — rather than repair or ask about the newest finding alone, and to record the step it takes under :step-back."
  {:about [design-prompt hot-areas] :evidence "round"})

(Claim amender-shown-restated-area
  "The design amend prompt after a decision whose :step-back restates an area shows the amender that area as the judge was shown it — its claims, its whole finding history with the findings since its last carried-out step-back marked, the approaches :rejected gave up and the person's answers — and asks for the area's approach to be restated rather than its newest finding repaired, with the approach given up added to :rejected under the area's id. A spent claim inside a restated area is repaired by that restatement: the prompt names it under the area and does not offer it the spent block's repairs of the one claim, which stay as they are for every spent claim outside a restated area."
  {:about [design-amend-prompt hot-areas] :evidence "round"})

(Claim record-schemas-carry-step-back
  "The record kinds gain two optional fields: a design decision's :step-back, whose entries carry an area id, a step among :restate, :recut, :resurvey and :ask, and the classes of finding that made the area hot as strings; and a :rejected entry's :area, an element or claim id. Entries written before carry neither and validate and parse as they did."
  {:about [record-vocabulary] :evidence "round"})

(Claim hot-areas-readable
  "bb nido:review:hot-areas lists, for any workstream it is given, the areas hot-areas finds in its newest design, each with its heat, read from that workstream's ledger alone."
  {:about [hot-areas heat] :evidence "round"})

(Claim hot-figures-readable
  "hot-figures reads, for one workstream or every ledger and over any window, by the same code before and after the landing: the share of claim findings landing on an area already hot, counted with no reset; the share of decisions ruling on a hot area that stepped back on it, a step-back being exactly one carried out as hot-areas-derived defines it, read off the recorded :recommend, with a :step-back recorded on a decision that carries none out counted apart; and the asks put to a person, by claim and by area. bb nido:review:trend reports these beside its run outcomes."
  {:about [hot-figures heat] :evidence "round"})

(Claim checked-against-recorded-runs
  "Before the change lands, bb nido:review:probe re-judges recorded design rounds that hot-areas, read over the ledger as it stood at each round, finds checking a claim in a hot area, under that round's prompt regenerated with its hot-area section and the decision schema carrying :step-back, beside the recorded prompt as control. The probe gains what that takes: choosing rounds by hot-areas and rendering each round's own variant. The landing waits on that probe's result being read."
  {:about [design-prompt hot-areas] :evidence "round"})
