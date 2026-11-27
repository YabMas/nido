(ns canvas.claims.drains
  "The claims the drains design (ws-20261112-9a89a7) commits nido to: a drain is a workstream class
   of its own, with a versioned recipe, a census nido measures, a slice roster on its own ledger,
   one coordinator, and landings by the person.

   The claims this design once made about autonomous drains — a standing grant, its brakes and a
   recipe change returning the choice — and about landing Runs in each slice's session are no
   longer declared: that machinery was removed, and a slice now lands by the person's :merge grant
   on its own workstream, as [slice-landing-heard-by-slice] (canvas.claims.drain-coordinators)
   states.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.drain :refer [record-drain]]
            [canvas.coordinator.lane.drain :refer [lane-drain]]
            [canvas.coordinator.source.drain :refer [source-drain]]
            [canvas.coordinator.view.drains :refer [view-drains]]
            [canvas.coordinator.record.workstream :refer [Workstream]]
            [canvas.coordinator.lane.drive :refer [lane-pipeline]]
            [canvas.coordinator.work :refer [coordinator-work]]
            [canvas.coordinator.view.workstreams :refer [view-workstreams]]
            [canvas.ui.surface :refer [drains-page ui-server]]))

(Claim drain-born-from-established-pattern
  "A workstream is a drain exactly when its ledger holds a :drain birth entry. lane-drain's start of a drain, which the /drains page offers, makes it: it refuses a named workstream or commit the project does not hold, and otherwise writes the :drain entry naming what established the pattern (a workstream or a landed commit) and the exit criterion. The drain class is read from that entry alone."
  {:about [record-drain lane-drain Workstream ui-server] :evidence "round"})

(Claim drain-lists-its-slices
  "A slice is a workstream born with a :slice birth entry naming its drain and the recipe version it starts under; each later version it moves to is a :slice-rebased entry on it citing that :recipe entry's seq, and the version it runs under is the newest of these. The drain's ledger holds a :slice-started entry for each slice it started. A drain's slices are read off the drain's own ledger."
  {:about [record-drain] :evidence "round"})

(Claim no-slice-without-recipe-and-census
  "A slice start on a drain whose ledger holds no :recipe, or no :census entry citing its current :recipe entry, is refused, naming which is missing, before any workstream or session is made."
  {:about [lane-drain] :evidence "round"})

(Claim recipe-versioned-by-amendment
  "A drain's recipe is its newest :recipe entry, and its version is that entry's seq. The coordinator prepares every recipe and every change to one as a :recipe-proposed naming its reason, and a :recipe entry is appended only by the person's approval of such a proposal. That approval joins the person's acts the commit gate refuses, so it is refused from the coordinator's own turns and from every turn nido gives a slice's session for no Run."
  {:about [record-drain] :evidence "round"})

(Claim census-measured-by-nido
  "A :census entry is written only by source-drain, which runs the current recipe's counter against the project's main and appends its reading, {:remaining n :groups [...]}, citing that recipe's seq. A run that fails (:counter-failed, :unreadable or :invalid) appends no :census, and source-drain fires that failure instead, naming the drain and its cause. A drain's progress is its newest :census."
  {:about [source-drain record-drain] :evidence "round"})

(Claim drain-kinds-appended-only-stamped
  "Every entry kind record-drain owns is appended only through record-drain's typed appends, stamped by the verb that writes it, and the ledger's other appends refuse one as they refuse a stamped kind. The slice-start gate, the brakes and the closure read these entries, so no turn writes one on its own word."
  {:about [record-drain] :evidence "round"})

(Claim coordinator-plans-each-slice
  "The coordinator plans the roll-out continuously, never once up front. Each slice it wants is a :slice-planned on the drain naming its sites, the slices it rests on and the census it was planned from. It may withdraw or replace any plan no start has consumed. lane-drain accepts a plan only from the drain's coordinator and against the newest census of the current recipe, and starts a slice only from an unconsumed plan."
  {:about [lane-drain record-drain] :evidence "round"})

(Claim recipe-chosen-by-class
  "The pipeline reads a workstream's position against the recipe of its class. A drain follows the drain recipe (charter, recipe, census, running, closing), a slice follows the slice recipe (slice plan, implementation, review against its recipe version, ready, landing), and every other workstream follows the standard recipe, whose positions and next actions are unchanged. A drain's and a slice's positions are read through record-drain's interface, which the record-status stratum the pipeline rests on provides."
  {:about [lane-pipeline] :evidence "round"})

(Claim drain-closes-on-observed-exit
  "A drain is closed done only by the person, through lane-drain's close, which the drain page offers. It refuses unless the drain's newest census reads zero remaining and every residue item is covered by a landed slice, and otherwise appends an :exit-observed naming the person, the charter's exit criterion and that census's seq, then the drain's close."
  {:about [lane-drain ui-server] :evidence "round"})

(Claim overview-bands-derived-from-origin-and-position
  "An open workstream's band on the overview is band-of's, read from its origin, its position and the recorded stage choice, with two overrides from the core: a workstream the core holds closed is :done, and one the core has placed at work while its ledger reads intake is :in-progress where band-of answers :triage or :ready. A workstream closed between phases and open in the core is placed on :awaiting-gate, and a closed one on :done or :dismissed from its closure. :done is not drawn on the overview, nor is any workstream whose origin is :drain or :slice, which another page owns."
  {:about [coordinator-work view-workstreams] :evidence "round"})

(Claim drains-page-and-one-inbox-row
  "/drains shows each open drain's phase, recipe version, newest census, slices by state and open escalations. A drain's escalations reach the gate inbox as the drain's own card, and each slice's as that slice's own [needs-you-list-is-the-activity]."
  {:about [drains-page coordinator-work] :evidence "round"})

(Claim slices-rest-on-slices
  "A slice whose plan names slices it rests on is started on top of their unlanded work, or after they land, as its plan says, and is never landed before every slice it rests on has landed."
  {:about [lane-drain] :evidence "round"})

(Claim residue-is-disposed
  "A drain's residue is a set of :residue items on its ledger: the sites misfits carve out and the residue each :slice-cleared names. lane-drain appends each :residue item only together with its first :residue-disposed, the coordinator's disposition of it, in the one checked append that records the :slice-cleared or carved :misfit-answered leaving it: either a follow-up citing the :slice-planned that covers it or the closing residue slice. The coordinator may dispose of it again until a plan covers it."
  {:about [lane-drain record-drain] :evidence "round"})
