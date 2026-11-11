(ns canvas.claims.surface-read-model
  "The claims the surface read-model design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [coordinator-work list-workstreams all-gates board-stamp grouped winding-down gates all-grouped facet-values]]
            [canvas.ui.surface :refer [ui-server ui-tui]]))

(Claim row-follows-own-stamp
  "When a refresh reaches a held row, as [refresh-paths] says, the row is recomputed from its records if its workstream's stamp differs from the one it was computed at, or if its liveness probe finds a change, and is kept otherwise. A workstream's stamp is its directory, entries/, entries.meta/, sessions/, registered runs with their locks and the event logs of the sessions nido drives under it, plus the session registry's entries for its own sessions and the Notion page facts of its own page, compared as values. The registry and page facts as a whole are held once per project, and a row for a watched Notion page no workstream covers is recomputed when that page's facts change, and added or dropped as coverage changes, by the refreshes [refresh-paths] names."
  {:about [list-workstreams board-stamp] :evidence "round"})

(Claim refresh-paths
  "Held rows are refreshed by request, as the surfaces choose per render, and by the sweep. An at-rest analysis is an at-rest row [at-rest-defined] carrying position :analysed. A list refresh reaches every workstream not at rest and every at-rest one whose own sessions the session registry shows live. It also takes the project's workstreams-directory stamp, listing that directory when the stamp moved so a newly created workstream is reached at once, and statting no listed entry it holds an at-rest analysis's row for; and takes the project's Notion page facts as a value: when they moved, it recomputes the rows other than at-rest analyses whose own page's facts moved and adds, recomputes or drops the rows for watched pages no workstream covers, against a coverage selection coordinator-work updates whenever a row is recomputed, so deciding coverage reads no workstream at rest. A selection refresh reaches its selected workstream alone, with only its own page's facts, and leaves the rest as held. On the dashboard, a request that renders through derive-screen makes a selection refresh when its view is the Workstreams surface with a selection (/workstreams/<project>/<id>, /workstreams?sel=<project>:<id>), no refresh on the Operations surface, and a list refresh otherwise — the gate inbox, the overview without a selection, and every polling fragment derive-screen renders, /_fragment/workstreams?sel=… among them, since its surface is not :workstreams; a request that answers with the workstream pane (the pane fragment, and the POSTs answering with it) makes a selection refresh of its workstream; the ops fragment refreshes nothing. On the TUI, the board and its tick make a list refresh, and the detail screen makes none. The sweep runs on its own thread at a period no longer than the liveness interval less the listing's cache lifetime. A sweep run that finds a project already held computes a refresh of it outside the project's lock, taking its workstreams-directory stamp and page facts as a list refresh does and reaching every held at-rest analysis and every held row whose liveness deadline [liveness-on-a-clock] falls within one sweep period, then installs it under the lock: whole when the held value is still the one the run read; otherwise only its rows for the workstreams it read as held whose held row nothing else replaced meanwhile with a reading begun after the run's own, keeping the held order, directory stamp and page facts. A run that finds a project not yet held builds or seeds it as a render's first read would [cold-start-from-snapshot], and refreshes none of its rows. Each run then re-reads the held casualty reading whole. Every other read of the model returns what is held."
  {:about [coordinator-work list-workstreams] :evidence "test"})

(Claim liveness-on-a-clock
  "The parts of a row that come from no file (an activity claim or run lock released by a process exiting, a session port gone dead, claude's agent listing) each carry the time their source observed them: the listing's own read time, which can precede the refresh that took it by up to the listing's 5s cache lifetime, and the probe time for locks and ports. A row's liveness deadline is its oldest such observation plus one liveness interval, and no held row is shown past its deadline without a refresh having replaced those observations. The liveness interval is no longer than the TUI board's 30s backstop and longer than the listing's cache lifetime."
  {:about [coordinator-work] :evidence "round"})

(Claim settled-off-the-render-path
  "Once a process holds its rows, the refreshes the gate inbox, the Workstreams overview, a dashboard selection request and the TUI board make, and their polls, reach a workstream at rest [at-rest-defined] whose own sessions the session registry shows no live session for only where: a list refresh holds no row for it yet; a list refresh finds its own page's Notion facts moved, or lists the workstreams directory, statting its entry, because its stamp moved, and it is not an at-rest analysis [refresh-paths]; or a selection refresh reaches its selected workstream. Every other such workstream is reached by the sweep alone, and every sweep run of a project already held reaches each at-rest analysis it holds, so a record written into one before a sweep run begins — a proposal decision, a reopening — shows once that run installs its refresh. One the sweep finds no longer at rest is reached by list refreshes from then on. A process with no usable snapshot, or that has discarded its rows, builds them by reading every workstream once, which is how it learns which are at rest."
  {:about [list-workstreams] :evidence "test"})

(Claim passes-read-held-rows
  "grouped, all-grouped, winding-down, gates, all-gates and facet-values answer from selections coordinator-work updates whenever a held row is recomputed: each band's members, the gates, the finished workstreams holding a live session, each facet's values. None of them visits a held row it does not return, or reads a record other than through a refresh of [refresh-paths]."
  {:about [grouped all-grouped winding-down gates all-gates facet-values] :evidence "round"})

(Claim detail-derives-one
  "Once a process holds its rows, a dashboard selection request, meaning /workstreams/<project>/<id>, /workstreams?sel=<project>:<id> with its polls, or the pane fragment, refreshes and derives its selected workstream alone, and draws everything else from what is held: the open, parked and winding-down rows of the list beside the detail, other gates, the rail badge, the recovery notices. It reads no other workstream's files and no casualty file. Its other reads serve what the page shows of the machine and of the selected workstream's sources, never of other workstreams: the session registry, the daemon's status and brakes, the projects config, the Notion source snapshots that give the selected workstream's page facts, and machine facts for the sessions of the winding-down rows it draws. So its cost follows the selected workstream and the held rows its list draws, never closed history, other workstreams' files or the number of host boots. While such a view stays open, only the sweep refreshes the rows beside it, so each shows its records as of the sweep's last run and its liveness within its deadline, as [refresh-paths] and [liveness-on-a-clock] say. Before rows are held it waits on the first build like any page."
  {:about [ui-server all-gates] :evidence "round"})

(Claim tui-detail-reads-one
  "Rendering the TUI's detail screen, and each refresh tick while it is shown, reads the selected workstream's records and its sessions' state, and no other workstream's records."
  {:about [ui-tui] :evidence "round"})

(Claim board-stamp-skips-settled
  "Once a process holds its rows, board-stamp, which the TUI board's refresh tick takes, stats no settled workstream the session registry shows no live session for, leaving those to the sweep of [settled-off-the-render-path], so the tick's cost follows the workstreams the board shows rather than closed history."
  {:about [board-stamp ui-tui] :evidence "round"})

(Claim casualties-held
  "coordinator-work holds a copy of owed-revivals' reading of the casualties across every recorded host boot, for renders. Only the sweep computes that copy, whole, at least once every 30s; no render reads a casualty file or computes the reading. So a casualty written, settled, newly owed or no longer owed shows within 30s, and an older boot's outstanding one keeps showing. owed-revivals itself stays a direct computation for its other callers, the daemon's attention notifier and the revive command, which never read the held copy."
  {:about [coordinator-work] :evidence "round"})

(Claim cold-start-from-snapshot
  "A process starting cold seeds its held rows from the project's snapshot only when the snapshot parses, conforms to the snapshot schema coordinator-work declares, and was written by the same nido source revision; any other snapshot is discarded whole and every row recomputed. The snapshot keeps each row's liveness observations with the time their source observed them, and neither writing nor seeding it renews them, so a seeded row's liveness deadline is the one [liveness-on-a-clock] gives those observations and a row whose deadline has passed is reached by the first refresh. A seeded row is otherwise held as [row-follows-own-stamp] says."
  {:about [coordinator-work ui-tui] :evidence "round"})

(Claim held-rows-are-disposable
  "The held rows, their selections, the held casualty reading and the snapshot hold no record: everything in them that comes from a file can be recomputed from the record files, and nothing that writes a ledger entry, session or run reads them."
  {:about [coordinator-work] :evidence "round"})

(Claim surfaces-are-benched
  "A bench task times the gate inbox, the Workstreams overview, a single workstream's dashboard detail, the TUI board's open, the TUI's detail screen and the TUI's navigation between them, against the live store and against a copy enlarged with synthetic settled workstreams, writing nothing to the live store's records. It prints each time per store, timing the cold first build of a process separately from renders once rows are held. Its times on today's store before and after this change are recorded on this workstream, and after it each of those dashboard pages and the TUI's open and navigation are materially faster than before."
  {:about [ui-server ui-tui] :evidence "round"})

(Claim at-rest-defined
  "A held workstream is at rest when its row is settled, or when the position its row carries from status is :analysed — a review-loop analysis — whatever its band and whether or not it draws a gate. Being at rest is decided each time its row is recomputed, from that row alone, and decides nothing but which refreshes reach the row: a row's gate, its denials and its band are derived exactly as when nothing is at rest, and an at-rest row's gate is held as its row was last recomputed until a refresh reaches it."
  {:about [coordinator-work] :evidence "test"})

(Claim analyses-off-the-list-refresh
  "The surface bench can enlarge its clone with synthetic finished review-loop analysis workstreams — open, with a ledger whose position status reads as :analysed and no live session — as it already can with synthetic settled ones, and times the gate inbox, the Workstreams overview and the TUI board on that clone as on its others. The code this change adds or changes appends no ledger entry to any analysis workstream and closes none."
  {:about [ui-server ui-tui] :evidence "round"})
