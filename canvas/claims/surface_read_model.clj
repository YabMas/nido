(ns canvas.claims.surface-read-model
  "The claims the surface read-model design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [coordinator-work list-workstreams all-gates board-stamp grouped winding-down gates all-grouped facet-values]]
            [canvas.ui.surface :refer [ui-server ui-tui]]))

(Claim row-follows-own-stamp
  "When a refresh reaches a held row, as [refresh-paths] says, the row is recomputed from its records if its workstream's stamp differs from the one it was computed at, and is kept otherwise. A workstream's stamp is its ledger's stamp as the core holds it (its newest entry's seq and its identity), its directory, sessions/, registered runs with their locks, the event logs of the sessions nido drives under it and the report of the review whose activity claim its row shows, plus the Notion page facts of its own page and its committed state in the core, compared as values. The registry and page facts as a whole are held once per project, and a row for a watched Notion page no workstream covers is recomputed when that page's facts change, and added or dropped as coverage changes, by the refreshes [refresh-paths] names."
  {:about [list-workstreams board-stamp] :evidence "round"})

(Claim refresh-paths
  "Held rows are refreshed by request, as the surfaces choose per render, and by the sweep. A list refresh reaches every workstream not at rest [at-rest-defined]. It also takes the project's membership from over-project, the one request for every workstream of the project the core holds, which every row's fold in the refresh answers from [records-read-from-core], so a workstream the core newly holds is reached at once; and takes the project's Notion page facts as a value: when they moved, it recomputes the rows whose own page's facts moved and adds, recomputes or drops the rows for watched pages no workstream covers, against a coverage selection coordinator-work updates whenever a row is recomputed, so deciding coverage reads no workstream at rest. A selection refresh reaches its selected workstream alone, with only its own page's facts, and leaves the rest as held. On the dashboard, a request that renders through derive-screen makes a selection refresh when its view is the Workstreams surface with a selection (/workstreams/<project>/<id>, /workstreams?sel=<project>:<id>), no refresh on the Operations surface, and a list refresh otherwise — the gate inbox, the overview without a selection, and every polling fragment derive-screen renders, /_fragment/workstreams?sel=… among them, since its surface is not :workstreams; a request that answers with the workstream pane (the pane fragment, and the POSTs answering with it) makes a selection refresh of its workstream; the ops fragment refreshes nothing. On the TUI, the board and its tick make a list refresh, and the detail screen makes none. The sweep runs on its own thread every 10s. A sweep run that finds a project already held computes a refresh of it outside the project's lock, reaching every held row, then installs it under the lock: whole when the held value is still the one the run read; otherwise only its rows for the workstreams it read as held whose held row is still the one the run read, keeping the held order and page facts. A run that finds a project not yet held builds or seeds it as a render's first read would [cold-start-from-snapshot], and refreshes none of its rows. Each run then re-reads the held casualty reading whole. Every other read of the model returns what is held."
  {:about [coordinator-work list-workstreams] :evidence "test"})

(Claim settled-off-the-render-path
  "Once a process holds its rows, the refreshes the gate inbox, the Workstreams overview, a dashboard selection request and the TUI board make, and their polls, reach a workstream at rest [at-rest-defined] only where: a list refresh holds no row for it yet; a list refresh finds its own page's Notion facts moved; or a selection refresh reaches its selected workstream. Of each such workstream a list refresh reads only what the request that gives its membership answers [refresh-paths]. Every other such workstream is reached by the sweep alone, by every sweep run, so a record written into one before such a run begins — a proposal decision, a reopening — shows once that run installs its refresh whole. One the sweep finds no longer at rest is reached by list refreshes from then on. A process with no usable snapshot, or that has discarded its rows, builds them by reading every workstream once, which is how it learns which are at rest."
  {:about [list-workstreams] :evidence "test"})

(Claim passes-read-held-rows
  "grouped, all-grouped, winding-down and gates answer from selections coordinator-work updates whenever a held row is recomputed: each band's members, the gates, the finished workstreams holding a live session. None of them visits a held row it does not return, or reads a record other than through a refresh of [refresh-paths]. facet-values keeps no selection: it computes its values on each call from every non-done held row. all-gates reads the core's asks and, of the held rows, the gates alone [gates-from-the-core]."
  {:about [grouped all-grouped winding-down gates facet-values] :evidence "round"})

(Claim detail-derives-one
  "Once a process holds its rows, a dashboard selection request, meaning /workstreams/<project>/<id>, /workstreams?sel=<project>:<id>, or the pane fragment, refreshes no held row but its selected one's, as [refresh-paths] says, and draws the list beside the detail, where it draws one, from what is held; its gates and the rail badge's count come from read-gates and its pane's question card from gate [pane-gate-from-the-core]. The overview's poll, /_fragment/workstreams?sel=…, is not a selection request: it makes a list refresh. Before rows are held its list waits on the first build like any board page, and the response, badge included, goes out with it [needs-you-without-the-board]."
  {:about [ui-server all-gates] :evidence "round"})

(Claim tui-detail-reads-one
  "Rendering the TUI's detail screen, and each refresh tick while it is shown, reads the selected workstream's records and its sessions' state, and no other workstream's records."
  {:about [ui-tui] :evidence "round"})

(Claim board-stamp-skips-settled
  "Once a process holds a project's rows, board-stamp, which the TUI board's refresh tick takes, reads no file: it is the held rows' version. What the tick reads is the list refresh it makes just before, which reaches what [refresh-paths] says a list refresh reaches."
  {:about [board-stamp ui-tui] :evidence "round"})

(Claim casualties-held
  "coordinator-work holds a copy of owed-revivals' reading of the casualties across every recorded host boot, for renders, and a dashboard render shows the held copy. Each sweep run whose owed-revivals answers replaces the copy whole, after the sweep's projects. While the copy is unset — before a sweep's first such answer — a render computes owed-revivals itself, reading the casualty files and the workstreams they name, and holds what it answers. So, while the sweep's owed-revivals answers, a casualty written, settled, newly owed or no longer owed shows within 30s, and an older boot's outstanding one keeps showing. owed-revivals itself stays a direct computation for its other callers, the daemon's attention notifier and the revive command, which never read the held copy."
  {:about [coordinator-work] :evidence "round"})

(Claim cold-start-from-snapshot
  "A process starting cold seeds its held rows from the project's snapshot only when the snapshot parses, conforms to the snapshot schema coordinator-work declares, and was written by the same nido source revision; any other snapshot is discarded whole and every row recomputed. Seeding refreshes no row: a seeded row is held, and shown, as it was written until a refresh reaches it, as [refresh-paths] says which refresh does. A seeded row is otherwise held as [row-follows-own-stamp] says."
  {:about [coordinator-work ui-tui] :evidence "round"})

(Claim held-rows-are-disposable
  "The held rows, their selections, the held casualty reading and the snapshot hold no record: everything in them that comes from a record can be recomputed from the records, and nothing that writes a ledger entry, session or run reads them."
  {:about [coordinator-work] :evidence "round"})

(Claim surfaces-are-benched
  "A bench task times the gate inbox, the Workstreams overview, a single workstream's dashboard detail, the TUI board's open, the TUI's detail screen and the TUI's navigation between them, on a clone of the live store and on that clone enlarged with synthetic settled workstreams, running every measurement in a child process whose nido home is the clone. It prints each time per store, timing the cold first build of a process separately from renders once rows are held."
  {:about [ui-server ui-tui] :evidence "round"})

(Claim at-rest-defined
  "A held workstream is at rest when its row is settled. Being at rest is decided each time its row is recomputed, from that row alone, and decides nothing but which refreshes reach the row: a row's gate, its denials and its band are derived exactly as when nothing is at rest, and an at-rest row's gate is held as its row was last recomputed until a refresh reaches it."
  {:about [coordinator-work] :evidence "test"})
