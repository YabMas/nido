(ns canvas.claims.unit-of-work
  "The claims the unit-of-work design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.fork :refer [record-fork]]
            [canvas.coordinator.record.standing :refer [record-standing]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.report.model :refer [report-model]]
            [canvas.design.check :refer [design-check]]
            [canvas.review.core :refer [review-prompts]]
            [canvas.review.merge :refer [review-merge]]
            [canvas.review.passes :refer [review-stages review-verdict]]
            [canvas.review.record :refer [review-record]]
            [canvas.review.settled :refer [review-settled]]
            [canvas.tasks.nido-land :refer [nido-land]]))

(Claim one-shared-model
  "A baseline and a design written in the current era state their common part in one shared model, in every project whether or not it declares a design, and the append refuses a record written in the survey or invariant shape."
  {:about [coordinator-report report-model] :evidence "round"})

(Claim subjects-declared-where-a-design-is
  "Every claim has an id unique within its record and an :about naming elements the record lists; in a project that declares a design a round launches no judge while a claim subject is not declared under its recorded sort, and in a project that declares none nothing is resolved against a declaration."
  {:about [report-model review-record design-check] :evidence "round"})

(Claim one-evidence-kind
  "Every claim in a current-era record states exactly one evidence kind — judged by a round, covered by named tests, or checked by a named law — so how many of a design's claims rest on a round is a count over the ledger alone."
  {:about [report-model] :evidence "round"})

(Claim declared-claims-land-with-the-design
  "In a project that declares a design, a current-era design's claims are declared in the canvas it lands with, as Claim instances carrying the same ids, statements and subjects. The landing check holds a branch to its cleared design both ways: every claim the design states is declared with that statement and those subjects, wherever the declaration came from, and every Claim new or changed since main is one the design states; a Claim carried unchanged from main is an earlier landing's and is asked of no later design."
  {:about [nido-land] :evidence "round"})

(Claim claims-addressed-by-id
  "Record rounds address claims by id: baseline and design judges confirm and refute claims by id, a design finding against a claim is told from another by that claim's id, and the diff review and the post-loop verdict cite a design claim by its id — each id checked against the ids the record states — never by matching its wording."
  {:about [review-record review-stages review-verdict review-prompts] :evidence "round"})

(Claim one-claim-per-claim-id
  "In a project that declares a design, a claim id names exactly one declared Claim across the whole declaration: the declaration's own law refuses two Claims of one name, and no reader of fukan's listing guards against it."
  {:about [nido-land review-record] :evidence "law"})

(Claim one-element-id-rule
  "An amender re-stating a record in the shared model is given one rule for an element's id: in a project that declares a design, its canvas identity; in a project that declares none, the id the record gave it."
  {:about [review-record] :evidence "round"})

(Claim conclusions-derived-not-stored
  "Nothing new is stored as a conclusion: whether a claim is settled, a fork's lineage and a merge's conflicts are derived from ledger entries on every read, and the only facts added are what an author cites and what a judge observed."
  {:about [record-workstream record-standing review-settled] :evidence "round"})

(Claim no-provenance-in-judging
  "No judging prompt renders provenance: nothing a judge is shown says a record replaced another, was derived from a parent or was merged, or that a claim was confirmed or settled before; claims outside its checks are set apart with nothing saying why."
  {:about [review-record review-prompts] :evidence "round"})

(Claim older-eras-stay-readable
  "Records written before the current era stay readable and are judged as they are today; nothing rewrites them, and a fork or merge that needs a current-era record refuses an older one by naming it."
  {:about [coordinator-report] :evidence "round"})

(Claim fork-writes-nothing-on-parent
  "Forking a unit writes nothing on its parent: the parent's ledger and workstream record are identical before and after, and a fork is always a separate workstream."
  {:about [record-fork record-workstream] :evidence "round"})

(Claim child-baseline-derived
  "A child unit's first baseline is derived, never surveyed from scratch: its model is the parent's cited baseline model overlaid by that design's model by id, and it cites the :fork entry naming the parent workstream and both seqs."
  {:about [record-fork report-model] :evidence "round"})

(Claim merge-combines-by-id
  "A merge appends to the parent only a design whose effective model is the three-way combination by id of the fork's base, the parent's current design and the child's design, only when no conflict stands, and it names every conflict."
  {:about [review-merge report-model] :evidence "round"})

(Claim roles-state-their-players
  "A Role element in a current-era model states its players under :plays, by ids the same model lists, in every project; in a project that declares a design a round launches no judge while a Role its record's model holds — for a design, its effective model — states players other than those the declared Role lists; and a merge reads a role's players only off the Role elements of the three effective models it combines, never off a listing."
  {:about [coordinator-report review-record review-merge] :evidence "round"})

(Claim settled-at-text-declarations-code
  "A claim is settled only by a confirmation, in a baseline review or a design decision, on its own ledger or a ledger its unit's fork or merge cites, at its identical text, the identical declaration of every one of its subjects, and the identical content of those subjects' code correspondents, with no finding against it at that key; in a project that declares no design, at its text and the whole tree's identity."
  {:about [review-settled design-check] :evidence "round"})
