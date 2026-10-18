(ns canvas.claims.review-queue
  "The claims the review-queue grooming design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.control :refer [fire!]]
            [canvas.coordinator.source.core :refer [source-queue]]
            [canvas.coordinator.source.review-queue-nightly :refer [due-day poll-once!]]
            [canvas.coordinator.work :refer [begin-review-apply! decide-review-item! review-queues]]
            [canvas.ui.surface :refer [handle-request review-queue-fragment]]))

(Claim review-apply-fires-once-on-frozen-decisions
  "A review-queue plan's decisions are recorded per item number, only for items the plan holds and only as approved or skipped. begin-review-apply! refuses, freezing nothing, while any item holds no decision; otherwise it freezes the decisions, after which no decision changes, and under the plan's lock queues the apply through the control/fire! handle-request hands it, keyed by the plan run's id. control/fire! queues a keyed envelope and takes its key in one atomic write that the drain never undoes, and a fire whose key is taken queues nothing — so one plan's apply trigger is queued exactly once however often Apply is pressed and wherever a process stops: a stop after queueing leaves the key taken and the retry queues nothing, a stop before it leaves the key free and the retry queues the one envelope. A plan whose fire is recorded is refused; a frozen plan whose fire is unrecorded is fired again with the same decisions on the next Apply."
  {:about [decide-review-item! begin-review-apply! handle-request fire!] :evidence "round"})

(Claim triggers-fire-through-control
  "Every envelope the Operations surface queues, it queues through control/fire! — the review-queue page's run lever calling it, and its apply lever handing it to begin-review-apply! to call under the plan's lock — and never through the queue namespace or an envelope the work plane builds."
  {:about [handle-request fire!] :evidence "round"})

(Claim review-queue-in-rank-order
  "review-queues presents a plan's tickets in the order of their rank, and the review-queue page renders them in that order, each with its rank and its reason; every rank the plan would write to Notion, adding the Review rank property, clearing a departed ticket's rank and sorting the Review view by Review rank are each a numbered item, decided like any other write — by a person through decide-review-item!, or for a nightly grooming by the nightly source — so no rank reaches Notion that was not approved as a numbered item."
  {:about [review-queues review-queue-fragment decide-review-item!] :evidence "round"})

(Claim nightly-plan-once-a-day
  "The nightly source queues a project's plan trigger at most once per calendar day in its zone — the configured :zone, or the daemon's system default zone when none is configured — on its first poll at or after :at that day, under a key naming the project and that day; a daemon down at :at queues it on its first poll later the same day, and none before :at. brian's :review-queue trigger declares this source with :at \"00:00\"."
  {:about [poll-once! due-day source-queue] :evidence "round"})

(Claim nightly-plan-applied-in-full-once
  "For each plan run the nightly source fired whose plan is written and whose apply fire is not recorded, a later poll records every undecided item :approved, freezes the decisions as decided by :nightly unless they are already frozen, and queues the apply under the key begin-review-apply! uses for that plan run, whether or not any item is :approved, so the plan is applied once whether the source or the page queues it; an item a person already decided keeps that decision, and the poll that queued a plan run queues no apply."
  {:about [poll-once! begin-review-apply! source-queue] :evidence "round"})

(Claim person-groomings-stay-human
  "A plan run the nightly source did not fire is decided and applied only through the page's levers; the nightly source never decides or freezes it."
  {:about [poll-once! decide-review-item! handle-request] :evidence "round"})

(Claim groomings-logged-with-decider
  "review-queues gives each project a log of its 14 newest plan runs, newest first, each with when it started, whether :nightly or :person decided it once frozen — :person when the freeze names no decider — its stage, and how many of its writes the apply run recorded :applied, :failed and :skipped; the page renders that log under the project's grooming."
  {:about [review-queues review-queue-fragment] :evidence "round"})
