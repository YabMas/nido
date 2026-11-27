(ns canvas.claims.qa-walk-report
  "The claims the qa-walk-report design (workstream ws-20261127-130cb7, design at ledger entry 25)
   commits nido to: the QA report derived on read from the walks the QA reading names, the landing
   brief carrying it as text and as the Notion body /land sends, the QA screenshot root owned by
   the record layer, the dashboard drawing QA plans and walks and the Land row's QA line, and the
   docs and the test that hold the rendering. Each Claim's name is the claim's id, its docstring
   the statement, and its :about the elements it is about."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.standing :refer [qa*]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.coordinator.work :refer [coordinator-work]]
            [canvas.core.images :refer [core-images]]
            [canvas.tasks.nido-workstream :refer [nido-workstream show*]]
            [canvas.ui.surface :refer [ui-server ui-views]]))

(Claim report-is-derived
  "The QA report is one value, derived on read in coordinator-work and stored nowhere, from the QA reading and the plan and walks it names by seq: every plan step in plan order with its number, action and the result the newest counted walk recorded for it (outcome, note, screenshots); every counted walk with its :app and :note; every walk of the plan recorded before the newest build that did not pass, with its :app and :note and the steps it did not pass with their outcome and note; whether the plan carries Also check items, which no walk carries out; and the tally of passed steps in the newest counted walk."
  {:about [coordinator-work] :evidence "round"})

(Claim reading-names-earlier
  "The QA reading also names, as :earlier, the seqs of its plan's walks recorded before the newest build; they never enter its state, which stays exactly as one-qa-reading states it."
  {:about [qa*] :evidence "round"})

(Claim brief-carries-report
  "nido-workstream renders the landing brief from the QA reading, its plan and the QA report: under a :passed reading it is the plan's sections followed by one section, 'Tested before landing', rendered from the report; under :not-applicable it is the one-line reason, with no report; under any other state there is no brief. A failed or blocked step can therefore reach a brief only as an earlier walk's, before the newest build."
  {:about [nido-workstream] :evidence "round"})

(Claim one-report-two-forms
  "The brief's text that show* prints and the Notion callout body a nido-workstream verb prints for /land are both rendered in nido-workstream from the same brief and QA report, so the two say the same steps, outcomes, notes and caveats; /land sends the verb's body and composes no brief text of its own."
  {:about [show* nido-workstream] :evidence "round"})

(Claim notion-report-layout
  "In Notion the report sits inside the QA instructions callout after Not in this change: a 'Tested before landing' heading; one summary line (how many of the plan's steps passed, on how many walks of the build that lands); 'How it was walked' with each counted walk's note, attributed to its walk when there is more than one; 'Earlier walks' with each earlier walk that did not pass, giving its note and naming its steps that did not pass with their outcome and note; a line saying the Also check items were not walked when the plan has any; and a collapsed toggle 'Step results' holding one numbered item per step, matching the step's number above, with its outcome and note and its screenshots as images nested under it. A screenshot that could not be uploaded is replaced by a line saying so, and never stops the brief."
  {:about [nido-workstream] :evidence "round"})

(Claim screenshots-owned-by-record
  "The QA screenshot root is named in record-workstream alone, which keeps and fetches screenshots through core-images; the task layer's keeping, its pre-append digest check and the work plane's fetch for the dashboard all go through it."
  {:about [record-workstream core-images nido-workstream] :evidence "round"})

(Claim dashboard-draws-walks
  "The work plane hydrates an opened :qa-walk report with its cited plan's steps and whether the reading counts it, and ui-views draws a :qa-plan entry as its sections and numbered steps and a :qa-walk entry as its caveats and one row per step — outcome mark, step number and action, note, and its screenshots as thumbnails each opening the full image — from house components only; ui-server serves a screenshot by digest through the work plane, answering its kept media type, and nothing in nido.ui requires core-images."
  {:about [ui-views coordinator-work ui-server] :evidence "round"})

(Claim land-rung-shows-qa
  "The Land rung carries the QA reading's state and, when a QA report exists, the seqs of every walk the report is built from — its counted walks and its earlier walks that did not pass — with the report's tally; the Land row draws them as one line giving the tally, from which each of those walks opens as its entry, so the workstream page and the Needs you pane — which draw the same workstream pane — both show what the brief will report."
  {:about [coordinator-work ui-views] :evidence "round"})

(Claim docs-match-report
  "docs/reference/qa-brief.md describes the report section, its Notion layout and how failed, blocked and earlier walks and several walks show, and replaces 'never claim something was tested' with: only a recorded walk says what was tested, and the report is rendered from it, never written; /land §6 sends the verb's body; /review-queue §3 judges a brief carrying a report by the same four tests, keeps its report whole when it keeps or corrects the brief, and writes or restyles a brief with no report, since it has no walk to render one from."
  {:about [nido-workstream] :evidence "round"})

(Claim report-tested
  "A test run by `bb nido:test` renders the landing brief for a plan with two walks — one with a :fail result recorded before the newest build, one passing after it — and asserts the report shows them as docs/reference/qa-brief.md states; the change lands with `bb nido:test` passing, nido.ui.controls-test included."
  {:about [nido-workstream] :evidence "round"})
