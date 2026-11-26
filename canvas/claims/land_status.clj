(ns canvas.claims.land-status
  "The claims the land-status design commits nido to: past the arc's stages, which stay the unit's,
   a Land rung projected from the row's activity and the core's grant, whose line tells the
   landing's trail from the steps nido observed, recorded in nido's ledger alone.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [arc stage-of]]
            [canvas.coordinator.lane.github :refer [lane-github-merge]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.view.activity :refer [of]]
            [canvas.coordinator.work :refer [coordinator-work workstream]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.integration.github :refer [github-client]]
            [canvas.tasks.nido-workstream :refer [nido-workstream]]
            [canvas.ui.surface :refer [ui-components-row ui-components-state ui-views]]))

(Claim land-rung-past-the-arc
  "A merge is no stage of the arc: the arc's stages stay unit-stages, and arc-at keeps its signature (entries position doing live? needs), its stage rows and its :past — an in-progress merge under :past {:doing …}. Its excursions change with the landing's records: :landing, like :merged, files under :shipping, off the arc, so the :shipping excursion counts :landing entries; and current-phase-entries drops the :landing and :merged entries written before the newest :reopened, so the excursions no longer count a reopened landing's records. Past the stages, coordinator-work's workstream adds to the arc it answers one Land rung, under :land, which no stage-of kind files under. The rung holds the records of the workstream's newest landing: the :landing and :merged entries written after its newest :phase-gate and its newest :reopened."
  {:about [arc stage-of workstream] :evidence "round"})

(Claim land-rung-reads-the-activity
  "The Land rung is a projection of the row's activity and position, never a stage the arc is read at. It reads :done once it holds its landing's :merged, whoever merged it, or while the position is :awaiting-gate (a workstream standing closed :between-phases, a close only a landing or a findings round re-settling the landed phase writes). Otherwise it reads :current while the core holds the workstream's :merge grant, and :ahead without one. While :current it carries what the arc says past its stages, and only that: the arc's :past :needs as its awaiting mark, titled with that category; the arc's :past :doing, a merge underway, as the merge's own words on its line; and :running while the activity answers :live and no stage of the arc carries a mark. Where the position names a spine stage, that stage keeps its marks and the rung carries none. A rung that is not :current carries no mark, and the arc's :past is then drawn as main draws it."
  {:about [coordinator-work of core-domain] :evidence "round"})

(Claim landing-steps-observed
  "Each :landing entry is one fact nido observed at a moment, written by whoever observed it. For a landing that names a PR, pr:land records :submitted (land! answered ok, with :via and the PR) or :refused (with land!'s words). A :push-main landing gets no :landing entry from pr:land: land! waits on bb nido:land, which records the :merged itself. The merge poller reads a queue timeline only for a workstream the core reported, at that poll, as holding a :merge grant, and only of the PR its current landing submitted — the PR the newest :submitted written after the newest :phase-gate and :reopened names; it records :queued for each added-to-merge-queue event and :ejected for each removed-from-merge-queue event on that PR whose reason is not the PR's merge, whether or not the same read found it merged, at most once per GitHub event id. No :landing entry states a step nothing observed."
  {:about [coordinator-report nido-workstream lane-github-merge github-client] :evidence "round"})

(Claim land-row-tells-the-trail
  "The Land rung's line is made of two parts. First, while the core holds the :merge grant, a granted step read from that grant at the moment of reading; no record stores it, so the step is absent once the merge has spent the grant. Then the landing's recorded steps, oldest first: the :landing and :merged entries the rung holds [land-rung-past-the-arc]. coordinator-work's workstream builds the recorded part from the active-ledger snapshot it already holds, hydrating the entries the rung names by seq, and hands it and the grant reading on the rung; ui-components-state's land-trail words them. An ejection stays in the trail after the re-queue that follows it. work/arc-rows names the rows the pane's arc block draws — the arc's stages, then Land — and is what the pane's ?stage= is checked against, so the Land rung's records open like any stage's; work/arc-stages stays the unit's spine."
  {:about [ui-components-state ui-components-row ui-views coordinator-work] :evidence "round"})

(Claim pane-draws-the-land-rung
  "The pane's arc block renders one arc-row block per arc stage and then one arc-row block for the Land rung, worded by ui-components-state's arc-label (Land among its entries) and marked by arc-mark from the rung's state and mark. While the rung is :current the arc's :past is said on its row — its awaiting mark and, on its line, the merge's words — and is not drawn again as separate lines; while it is not, the :past lines render as main renders them: the awaiting arc-mark with needs-words, and state-mark's merge badge. The excursions stay one 'off the arc · <stage> ×n' text line. The Status heading is status-heading's, unchanged [heading-is-the-position]."
  {:about [ui-views ui-components-row ui-components-state] :evidence "round"})

(Claim landing-touches-no-notion
  "Recording and showing a landing writes nothing outside nido's ledger: no :landing write and no queue read sends anything to Notion. The merge reaction's existing Notion write is unchanged."
  {:about [lane-github-merge nido-workstream] :evidence "round"})
