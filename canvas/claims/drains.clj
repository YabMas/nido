(ns canvas.claims.drains
  "The claims the drains design (ws-20261112-9a89a7) commits nido to: a drain is a workstream class
   of its own, with a versioned recipe, a census nido measures, a slice roster on its own ledger,
   one resumable coordinator, and landings by the person or under the person's standing grant.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.drain :refer [record-drain]]
            [canvas.coordinator.lane.drain :refer [lane-drain]]
            [canvas.coordinator.source.drain :refer [source-drain]]
            [canvas.coordinator.view.drains :refer [view-drains]]
            [canvas.coordinator.record.workstream :refer [Workstream]]
            [canvas.coordinator.lane.drive :refer [lane-pipeline]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor]]
            [canvas.coordinator.work :refer [coordinator-work]]
            [canvas.coordinator.view.workstreams :refer [view-workstreams]]
            [canvas.ui.surface :refer [ui-server]]))

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

(Claim slices-briefed-from-recipe
  "A slice session's brief is composed by lane-drain from its drain's current recipe: its steps, ready gates and standing orders verbatim, and the plan the slice was started from. A slice session's start is briefed so, and an instruction lane-conductor posts to a slice whose version is older than its drain's current recipe carries that brief, with a :slice-rebased citing the current :recipe entry appended to the slice."
  {:about [lane-drain lane-conductor] :evidence "round"})

(Claim census-measured-by-nido
  "A :census entry is written only by source-drain, which runs the current recipe's counter against the project's main and appends its reading, {:remaining n :groups [...]}, citing that recipe's seq. A run that fails (:counter-failed, :unreadable or :invalid) appends no :census, and source-drain fires that failure instead, naming the drain and its cause. A drain's progress is its newest :census."
  {:about [source-drain record-drain] :evidence "round"})

(Claim drain-kinds-appended-only-stamped
  "Every entry kind record-drain owns is appended only through record-drain's typed appends, stamped by the verb that writes it, and the ledger's other appends refuse one as they refuse a stamped kind. The slice-start gate, the brakes and the closure read these entries, so no turn writes one on its own word."
  {:about [record-drain] :evidence "round"})

(Claim coordinator-plans-each-slice
  "The coordinator plans the roll-out continuously, never once up front. Each slice it wants is a :slice-planned on the drain naming its sites, the slices it rests on and the census it was planned from. It may withdraw or replace any plan no start has consumed. lane-drain accepts a plan only from the drain's coordinator and against the newest census of the current recipe, and starts a slice only from an unconsumed plan."
  {:about [lane-drain record-drain] :evidence "round"})

(Claim one-coordinator-per-drain
  "A drain's coordinator is the drain workstream's conductor, and a slice has none. Every hand-back from a slice's session is posted to its drain's coordinator, and the coordinator reaches a slice only by deciding such a hand-back. While its drain is open a slice stays seated whether or not anyone has typed in it."
  {:about [lane-drain lane-conductor] :evidence "round"})

(Claim coordinator-resumes-from-records
  "A coordinator start is briefed with lane-drain's coordinator brief, composed from the drain's records alone: every reading of the drain that record-drain's interface offers, each of its slices' positions, and its open escalations. Its escalation policy comes from the conductor guidance document, as a conductor's brief does today. While the drain is open, each conduct pass of lane-conductor starts the coordinator whenever its session is not running, whether or not a person has typed in it."
  {:about [lane-drain lane-conductor] :evidence "round"})

(Claim conductor-lives-while-worked
  "For a workstream that is neither a drain nor a slice, a conduct pass that reads the prompt log starts a conductor, when the workstream is open and activated, only while it is worked, meaning a person typed in one of its sessions within the worked window (a day), and retires it when the window lapses. Turns nido gives, a spawned child's and a Run's, do not make it worked. A pass that cannot read the prompt log starts and retires no conductor of such a workstream."
  {:about [lane-conductor] :evidence "round"})

(Claim recipe-chosen-by-class
  "The pipeline reads a workstream's position against the recipe of its class. A drain follows the drain recipe (charter, recipe, census, running, closing), a slice follows the slice recipe (slice plan, implementation, review against its recipe version, ready, landing), and every other workstream follows the standard recipe, whose positions and next actions are unchanged. A drain's and a slice's positions are read through record-drain's interface, which the record-status stratum the pipeline rests on provides."
  {:about [lane-pipeline] :evidence "round"})

(Claim misfit-goes-to-coordinator
  "A slice that meets a site its recipe version does not cover records a :misfit on the drain through lane-drain, which refuses it from any session but one of a slice of that drain, and hands back. It writes no design of its own. The coordinator answers the misfit through lane-drain, which records a :misfit-answered naming the misfit's seq and one answer: proposed, citing the :recipe-proposed that would cover the site; carved, naming the site added to the drain's residue; or escalated, naming what the person is asked. A drain's open misfits are its :misfit entries no :misfit-answered names, and lane-conductor refuses the coordinator's decision on a hand-back from a slice that has an open misfit."
  {:about [lane-drain lane-conductor] :evidence "round"})

(Claim lands-only-by-person-or-standing-grant
  "A slice is landed only by a person's act on it, or by lane-drain while its drain's autonomy state is autonomous. Every turn nido gives a slice's session is given with given-by nil and for no Run, except a landing Run's turn, which nido gives only for a landing a person's act or lane-drain under the grant asked for. So the commit gate refuses a person's act from every other such turn as from the coordinator's own turns; asking for a slice's landing joins the acts it refuses, and :autonomy-granted the person kinds."
  {:about [lane-drain lane-conductor] :evidence "round"})

(Claim autonomy-is-the-persons-to-grant
  "The drain page offers Continue autonomously on any drain with a recipe whose autonomy is not autonomous, and Pause on one whose autonomy is autonomous or suspended. The first records :autonomy-granted naming the person, the second :autonomy-revoked, which returns the drain to supervised."
  {:about [ui-server lane-drain] :evidence "round"})

(Claim brakes-return-to-person
  "nido suspends an autonomous drain with an :autonomy-suspended entry naming the cause, and escalates it to the gate inbox, when a slice's landing fails or is evicted, when the first :census after a landed slice does not read fewer remaining than the :census before it citing the same :recipe entry, when lane-drain consumes a census failure source-drain fired for it, or when one of its misfits is answered escalated. Suspension stops lane-drain's own landings; the person's acts and the coordinator carry on. lane-drain lands no slice on its own unless a :census has been appended since its previous landing."
  {:about [lane-drain] :evidence "round"})

(Claim drain-closes-on-observed-exit
  "A drain is closed done only by the person, through lane-drain's close, which the drain page offers. It refuses unless the drain's newest census reads zero remaining and every residue item is covered by a landed slice, and otherwise appends an :exit-observed naming the person, the charter's exit criterion and that census's seq, then the drain's close."
  {:about [lane-drain ui-server] :evidence "round"})

(Claim overview-bands-derived-from-origin-and-position
  "An open workstream's band on the overview is band-of's, read from its origin, its position and the recorded stage choice. A workstream closed between phases is placed on :awaiting-gate, and a closed one on :done or :dismissed from its closure. :done and :analysed are not drawn on the overview, nor is any workstream whose origin is in the one set of origins owned by another page: :drain and :slice always, and :review-run unless status places it at a blocker."
  {:about [coordinator-work view-workstreams] :evidence "round"})

(Claim drains-page-and-one-inbox-row
  "/drains shows each open drain's phase, recipe version, newest census, slices by state and open escalations. A drain's escalations, and those its slices raise through it, reach the gate inbox as one row for the drain."
  {:about [view-drains ui-server coordinator-work] :evidence "round"})

(Claim slices-rest-on-slices
  "A slice whose plan names slices it rests on is started on top of their unlanded work, or after they land, as its plan says, and is never landed before every slice it rests on has landed."
  {:about [lane-drain] :evidence "round"})

(Claim landing-cleared-by-coordinator
  "A slice that hands back ready is landed by lane-drain only after the coordinator clears it with a :slice-cleared naming the residue it leaves. A person may land a slice the coordinator has not cleared, and that landing is recorded as the person's override."
  {:about [lane-drain record-drain] :evidence "round"})

(Claim residue-is-disposed
  "A drain's residue is a set of :residue items on its ledger: the sites misfits carve out and the residue each :slice-cleared names. lane-drain appends each :residue item only together with its first :residue-disposed, the coordinator's disposition of it, in the one checked append that records the :slice-cleared or carved :misfit-answered leaving it: either a follow-up citing the :slice-planned that covers it or the closing residue slice. The coordinator may dispose of it again until a plan covers it."
  {:about [lane-drain record-drain] :evidence "round"})

(Claim coordinator-woken-by-events
  "The coordinator is given a turn on every slice hand-back, as lane-conductor posts each to it, and on every new census, landing and failed landing of its drain, as a :wake lane-drain posts to the drain's conductor through lane-seats' post!, keyed by the event so it is posted once. A wake is not a hand-back and owes no decision. lane-drain starts no slice of a drain, and lands none under the grant, while the wake for the drain's newest census, landing or failed landing has not been delivered to the coordinator's session."
  {:about [lane-drain lane-conductor] :evidence "round"})

(Claim recipe-change-returns-the-choice
  "While a :recipe-proposed awaits the person, lane-drain lands nothing on its own. Approving a proposal on a drain that was autonomous leaves it supervised and offers the person, on the drain page, the choice to continue autonomously under the new version or to run a supervised iteration with it first."
  {:about [lane-drain ui-server] :evidence "round"})

(Claim slices-land-from-their-own-session
  "Every landing of a slice, a person's act's or lane-drain's, is carried out by a landing Run in the slice's own session once its recipe version's ready gates (tests and reviews among them) pass, never through the serial merge lane. Landing Runs of a drain's slices run concurrently: none waits on a sibling's landing Run unless it rests on that sibling. How often lane-drain asks for its own landings is paced by source-drain's poll, [brakes-return-to-person] and [coordinator-woken-by-events]."
  {:about [lane-drain] :evidence "round"})
