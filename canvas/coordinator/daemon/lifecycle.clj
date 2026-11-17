(ns canvas.coordinator.daemon.lifecycle
  "Self-spec: keeping the daemon running and saying so — `heartbeat`, `launchctl`, `reconcile`,
   `restart`, `preprocess`."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.clock :as clock]
            [canvas.coordinator.record.runs :as runs]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.state :as state :refer [Path]]
            [canvas.coordinator.record.workstream :as ws]
            [canvas.platform.process :as process]
            [fukan.common.typing.malli]))

(Module daemon-heartbeat
  "What the daemon last said about itself, stamped with when it said it.

   One function, and the timestamp is the whole point: the state alone cannot distinguish a
   daemon that is idle from one that stopped while idle."
  (Operation write!
    "Persist the daemon's state with a fresh timestamp."
    {:signature [:=> [:catn [:state :map]] :any]
     :delegates [state/status-path clock/now-iso]}))

(Module daemon-launchctl
  "The macOS LaunchAgent: rendering the plist, and the launchctl verbs around it.

   Rendering is PURE and separated from the shelling, so what gets installed can be asserted in
   a test without installing it."
  (Operation launch-agents-dir
    "Where LaunchAgents live. Wrapped so a test can redirect it."
    {:signature [:=> [:catn] Path]})
  (Operation label
    "The service label the daemon is known to launchd by."
    {:signature [:=> [:catn] :string]})
  (Operation plist-path
    "The plist file for this service."
    {:signature [:=> [:catn] Path]
     :delegates [launch-agents-dir label]})
  (Operation installed?
    "Whether the plist is on disk."
    {:signature [:=> [:catn] :boolean]
     :delegates [plist-path]})
  (Operation write-plist!
    "Install the plist."
    {:signature [:=> [:catn [:contents :string]] :any]
     :delegates [plist-path]})
  (Operation remove-plist!
    "Uninstall it. No-op when absent."
    {:signature [:=> [:catn] :any]
     :delegates [plist-path]})
  (Operation current-uid
    "The numeric user id, for the launchctl service target."
    {:signature [:=> [:catn] :string]})
  (Operation target
    "The launchctl service target — `gui/<uid>/<label>`."
    {:signature [:=> [:catn] :string]
     :delegates [current-uid label]})
  (Operation sh!
    "Shell out and answer with what happened rather than throwing — a redef seam, so the verbs
     below can be tested without a launchd."
    {:signature [:=> [:catn [:args :any]] :map]})
  (Operation loaded?
    "Whether launchd reports the service as loaded."
    {:signature [:=> [:catn] :boolean]
     :delegates [sh! target]})
  (Operation bootstrap!
    "Load the plist into the user's launchd domain."
    {:signature [:=> [:catn] :map]
     :delegates [sh! plist-path]})
  (Operation bootout!
    "Unload the service, which kills the running daemon and stops it coming back."
    {:signature [:=> [:catn] :map]
     :delegates [sh! target]})
  (Operation kickstart!
    "Restart the daemon in place."
    {:signature [:=> [:catn] :map]
     :delegates [sh! target]})
  (Operation render-plist
    "The plist XML for this machine's paths. Pure — what gets installed can be asserted without
     installing it."
    {:signature [:=> [:catn [:opts :map]] :string]
     :delegates [label]}))

(Kind Landing
  "The latest landing: owed onto a commit — when, and by which landing process — or running it, in
   which daemon process and since when. Only what nothing else can tell: what an owed restart waits
   for is derived when it is asked, never stored."
  [:or
   [:map [:state [:enum :owed]] [:commit :string] [:owed-at :string] [:by-pid :int]]
   [:map [:state [:enum :running]] [:commit :string] [:owed-at :string]
         [:since :string] [:pid :int]]])

(Module daemon-restart
  "The latest landing, as a restart owed onto its commit or as the commit the daemon runs, and
   what an owed restart still waits for.

   A file rather than a decision made in the landing, because the landing is often the one process
   that cannot make it — it runs inside the daemon, or runs are live — and a decision nobody holds
   afterwards is never carried out. The landing owes; the daemon pays, on its tick, by exiting for
   launchd to relaunch it onto the checkout that holds the landing, and the daemon that comes up
   records that it runs it. `landing` is the one place the file's shape becomes trusted, and
   `waits` the one reading of whether a debt is due — read from the system each time it is asked,
   never kept — so the landing's account, the daemon's act and what the surfaces show cannot
   disagree."
  {:child [Landing]}
  (Operation landing
    "The one parsing boundary of the file: {:landing <Landing>}, {:none true} when no file exists,
     or {:unreadable <why>} when one exists and does not parse to a Landing. Unreadable is never
     read as none."
    {:signature [:=> [:catn] [:or [:map [:landing Landing]] [:map [:none :boolean]] [:map [:unreadable :string]]]]
     :delegates [state/restart-owed-path]})
  (Operation owe!
    "Record the latest landing as owed onto `commit` by the landing process `by-pid`, replacing
     whatever was recorded, under the record's lock. {:landing <Landing>} as written, or
     {:failed <why>} when the file cannot be written."
    {:signature [:=> [:catn [:commit :string] [:by-pid :int]] [:or [:map [:landing Landing]] [:map [:failed :string]]]]
     :delegates [state/restart-owed-path clock/now-iso]})
  (Operation running!
    "Record an owed landing as running in this daemon, `pid`, since now — only while the record is
     still that owed landing, same commit and owed-at, compared and written under the lock `owe!`
     writes under, so a landing owed meanwhile is never overwritten. {:landing <Landing>} as
     written; {:moved true}, nothing written, when the record is no longer that owed landing; or
     {:failed <why>} when the write fails."
    {:signature [:=> [:catn [:owed Landing] [:pid :int]]
                 [:or [:map [:landing Landing]] [:map [:moved :boolean]] [:map [:failed :string]]]]
     :delegates [landing state/restart-owed-path clock/now-iso]})
  (Operation waits
    "What an owed landing still waits for, read when asked from what any process can read, so
     every asker gets the same answer: runs live? reports live, runs whose record says they are
     running, the owing landing still running, the checkout not holding the commit, the daemon not
     running under launchd, the running daemon having started after the debt. Each wait in words;
     empty when the restart is due."
    {:signature [:=> [:catn [:owed Landing]] [:vector :string]]
     :delegates [runs/list-run-ids runs/read-run runs/live? process/process-alive? loaded? state/pid-path]}))

(Module daemon-reconcile
  "Startup repair: force every non-terminal Run to a terminal state, and leave each Run's own
   session as the executor would have left it for that state.

   Runs on startup because a Run left mid-flight by a crash is indistinguishable from one still
   going, and the daemon that could have told them apart is the one that died. The session half
   is part of the same repair: the executor ends a session when it ends the Run, so a Run ended
   here with its session left :live is a record claiming work nothing is doing."
  (Operation reconcile!
    "Settle every Run the last daemon left in flight, and every session a settled Run still holds."
    {:signature [:=> [:catn] :any]
     :delegates [runs/read-run runs/write-run! state/runs-dir session/read-session
                 runs/owns-session? runs/teardown-session-for-run!
                 runs/stop-session-for-parked-run!]})
  (Operation release-improvement-hold!
    "Close, as :orphaned, the open improvement claim a resolved Run leaves behind, unless an
     unanswered blocker holds it for a person — the close is the only thing that releases the
     sweep, and a dead Run writes none."
    {:signature [:=> [:catn [:run :map]] :any]
     :delegates [ws/read-ws ws/entries-of ws/close!]}))
