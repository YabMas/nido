(ns canvas.claims.coordinator-idle
  "The claims the coordinator-idle design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.core :refer [boot-core tick!]]
            [canvas.coordinator.lane.conduct :refer [lane-conduct]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor]]
            [canvas.coordinator.record.live :refer [census! record-live subjects]]))

(Claim subjects-are-live
  "A workstream is a subject while it can change, or a decision on another subject reads it, without anything writing to it: it is open and activated, it is an open drain or a slice an open drain has started, a session record under it names a session the registry or the agent listing holds live, or an unfinished Run names it. Open alone does not make one a subject, and neither does an activation a close has ended."
  {:about [record-live subjects] :evidence "round"})

(Claim picture-derived
  "record-live's picture is derived only, from the files and the agent listing a pass reads: built by one full read at start and corrected by each census for every workstream, conversation and Run whose stamps moved or that is new; nothing is written from it, a restart rebuilds it, and a process that has not started it is answered from disk."
  {:about [record-live] :evidence "round"})

(Claim census-sees-writes
  "The census stats, every tick, the stamps the record layer keys its own reads on, and the directories new records appear in: each workstream's directory (workstream.edn and seat-messages.edn are renamed into it) and its workstream.edn, entries/ and entries.meta/, the stamps read-ws's reconciled index is memoised on; each session directory; each run directory; and the events directory, which append! moves, with each live conversation's log, or every log when the events directory moved. So every write nido makes that a pass acts on — an append that died before its index row among them — moves a stamp the census reads."
  {:about [census!] :evidence "round"})

(Claim passes-on-movement
  "conduct and the conductor pass run in a tick only when that tick's census found something moved or the backstop period has passed since they last ran. They then visit record-live's subjects, every workstream the census found moved — closed or not, a drain counting as moved when a slice it started moved — and the workstreams the conductor's dead-turn asks name, as stops! visits them today; and of those workstreams' session records they read none that is torn down: its session home gone, its conversation not in the agent listing, and nothing left on it that a pass acts on — an instruction outstanding, a turn open, a dialog or an error recorded on it among them. A tick whose census found nothing and is inside the period runs neither."
  {:about [tick! lane-conduct lane-conductor] :evidence "round"})

(Claim backstop-bounds-loss
  "Except while a pass is starting sessions, which holds the loop for as long as the start takes (entry 42), what changes with no write — a process that died, a listing that moved, a budget or a bound that elapsed — is acted on within one backstop period (30 s), because the passes then run over the subjects whatever the census found and nothing else on the loop's thread waits on a session start [tick-placed]; and a written change is acted on by the pass after the census that found it, on whatever workstream it was written to, live or not, and on the drain a moved slice wakes [passes-on-movement]."
  {:about [boot-core census!] :evidence "round"})

(Claim tick-placed
  "No step of tick! on the loop's thread lists every workstream, session record, Run or event log of a project, or waits on a remote service or a session's start, save the session starts the conduct and conductor passes make — a conductor's or a seat's start, a wake's resume — which entry 42 excepts. On the loop's thread stay the census, conduct and the conductor pass (drain wakes among its steps) over what [passes-on-movement] gives them, drive's tick over its allow-list with in-flight? over unfinished-run-ids, and the steps over in-memory state and record-live's answers; the other steps — the dispatch of queued envelopes, with the session starts and the find-by-ref it makes, every source, GitHub and Notion-sync poll, notify-attention, the title sweep, and the hygiene sweeps reclaim, adopt, settle-reviews and expire-inbox at their existing throttles — run on threads of their own, one run of each at a time."
  {:about [tick!] :evidence "round"})

(Claim cost-follows-live
  "With nothing moving, the loop's per-tick work is the census [census-sees-writes] and drive's tick over its allow-list, plus the passes once per backstop period; it parses no ledger, session record, run record or event log of a workstream outside the set [passes-on-movement] visits and drive's allow-list, and no torn-down session record. The dispatch of envelopes and the hygiene sweeps, which may read every record, run off the loop [tick-placed]."
  {:about [boot-core record-live] :evidence "round"})

(Claim decisions-unchanged
  "Given the same records, conduct, the conductor, drain wakes and every lane decide what they decided before; only when they are asked and which workstreams they are given changes. The working count the executor promotes against is set by every pass, and every event that moves it — a turn ending, a dispatch, a session starting — moves a stamp, so it is current at the next census."
  {:about [lane-conduct lane-conductor] :evidence "round"})
