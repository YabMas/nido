(ns canvas.coordinator.record.state
  "Self-spec: `nido.coordinator.record.state` — the filesystem layout the coordinator owns."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Path
  "An absolute filesystem path the coordinator owns.

   Owned HERE because this is the module that hides how one is built. Every path in the
   coordinator is derived from the nido home by a function in this file, and no caller anywhere
   assembles one — which is what lets the whole layout move, and what lets a test point a run at
   a temp directory by redefining one root."
  :string)

;; ── the identifiers the layout is addressed by ───────────────────────────────
;; Owned HERE rather than where they are MINTED, and that is the decision rather than an
;; oversight. Each is constructed in exactly one place — `record-workstream/mint-id` is the only
;; source of a `ws-` id anywhere in nido — and by Parnas that module hides the format and should
;; own it. It cannot: the path functions below TAKE these ids, so ownership there would add
;; `state ⟶ workstream` while `workstream ⟶ state` already exists, and the module graph would
;; cycle. The format has TWO knowers — the minting module decides it, this one embeds it in a
;; directory name — which is why neither owns it comfortably.
;;
;; So it goes to the knower that nothing reaches THROUGH: everything taking one of these already
;; depends on this module for its paths, so the edges ownership adds point the way they already
;; pointed. The alternative is a leaf module that mints all three and depends only on the clock,
;; which everything else depends on — the shape `platform-project` already uses for
;; `ProjectName`, and a bigger move. This is revisitable, and the trade to weigh when revisiting
;; is that centralising the layout is what lets it MOVE (no caller anywhere builds a path, and a
;; test redirects a whole run by redefining one root) while centralising it puts each record's
;; layout knowledge outside that record.
;;
;; They earn their place in the model by appearing in signatures. `[:=> [:catn
;; [:project ProjectName] [:ws-id WorkstreamId]] Path]` says what a path function is for;
;; `[:cat :keyword :string]` says it takes a keyword and a string, which every function in the
;; file also does.

(Kind WorkstreamId
  "A workstream's identity — `ws-<date>-<hash>`, minted once and never derived from anything that
   can change."
  :string)

(Kind RunId
  "One execution of a trigger against a workstream. The directory under `runs/` is named by it."
  :string)

(Kind SessionName
  "A work-episode's name within its workstream, unique per workstream rather than globally."
  :string)

(Module record-state
  "Where everything the coordinator owns lives on disk.

   Twenty-eight functions and one shape: a path, derived from the nido home. The value is not any
   one of them but that no caller anywhere builds a path itself — which is what lets the whole
   layout move, and what lets a test point a run at a temp directory by redefining one root.

   It also owns the three identifiers the layout is addressed by, for the reason set out above
   them: not because it mints them, but because it is the knower nothing else reaches through."
  {:child [Path WorkstreamId RunId SessionName]}
  (Operation nido-root
    "The nido home. Every other path here is derived from it, which is what makes a whole coordinator relocatable by moving one directory."
    {:signature [:=> [:catn ] Path]})
  (Operation coordinator-root
    "The coordinator's own subtree of the nido home."
    {:signature [:=> [:catn ] Path]})
  (Operation coordinator-dir
    "Where the coordinator's own state files live."
    {:signature [:=> [:catn ] Path]})
  (Operation queue-dir
    "The manual event source — one envelope file per queued fire."
    {:signature [:=> [:catn ] Path]})
  (Operation status-path
    "The heartbeat file: what the daemon last reported about itself."
    {:signature [:=> [:catn ] Path]})
  (Operation halted-path
    "The kill switch. Its PRESENCE is the halt; its contents say who and why."
    {:signature [:=> [:catn ] Path]})
  (Operation pid-path
    "The background daemon's pid file."
    {:signature [:=> [:catn ] Path]})
  (Operation log-path
    "The daemon's log."
    {:signature [:=> [:catn ] Path]})
  (Operation config-path
    "The coordinator's own configuration."
    {:signature [:=> [:catn ] Path]})
  (Operation driving-path
    "The allow-list of workstreams the driver may advance."
    {:signature [:=> [:catn ] Path]})
  (Operation breakers-path
    "Per-trigger circuit-breaker counts."
    {:signature [:=> [:catn ] Path]})
  (Operation runs-dir
    "Where every run's directory lives."
    {:signature [:=> [:catn ] Path]})
  (Operation run-dir
    "One run's directory."
    {:signature [:=> [:catn [:run-id RunId]] Path]})
  (Operation run-edn-path
    "One run's record."
    {:signature [:=> [:catn [:run-id RunId]] Path]})
  (Operation run-status-path
    "The status file a skill writes inside a run, reporting its own phase."
    {:signature [:=> [:catn [:run-id RunId]] Path]})
  (Operation run-artifacts-dir
    "Where a run's agent leaves what it produced."
    {:signature [:=> [:catn [:run-id RunId]] Path]})
  (Operation run-agent-log
    "One run's agent transcript."
    {:signature [:=> [:catn [:run-id RunId]] Path]})
  (Operation run-session-home-link
    "The link from a run to the session home it was provisioned into."
    {:signature [:=> [:catn [:run-id RunId]] Path]})
  (Operation triggers-path
    "A project's trigger configuration."
    {:signature [:=> [:catn [:project ProjectName]] Path]})
  (Operation workstreams-dir
    "Where a project's workstreams live."
    {:signature [:=> [:catn [:project ProjectName]] Path]})
  (Operation pre-unification-dir
    "Where a project's records lived before the workstream unification. Read-only; kept so an old record stays findable."
    {:signature [:=> [:catn [:project ProjectName]] Path]})
  (Operation workstream-dir
    "One workstream's directory."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Path]})
  (Operation workstream-edn-path
    "One workstream's record."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Path]})
  (Operation ws-entries-dir
    "A workstream's append-only ledger — one immutable file per entry."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Path]})
  (Operation ws-sessions-dir
    "The work episodes recorded against a workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Path]})
  (Operation session-dir
    "One session's directory within its workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] Path]})
  (Operation session-edn-path
    "One session's record."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] Path]})
  (Operation ensure-dirs!
    "Create the coordinator and runs directories if absent. Idempotent, and called on every write
     path rather than once at boot: a coordinator whose home was cleaned out mid-run repairs
     itself instead of failing on the next append."
    {:signature [:=> [:catn] :any]}))
