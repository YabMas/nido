(ns canvas.tasks.nido-review
  "Self-spec: `tasks.nido-review` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.drive :as pipeline]
            [canvas.coordinator.record.state :refer [Path WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module nido-review
  "bb-task entrypoints for the three judgment loops — over a baseline record,"
  (Operation exit-code
    "CLI exit code for a terminal review status. review-failed is the only"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation refused-repairs
    "The repairs the stack refused that a run is still holding, one row per layer. Read off the
     carry rather than the terminal context, because a context is rebuilt every round and a run
     can refuse a repair in one round and converge in the next — which is the case the terminal
     context has nothing to say about."
    {:signature [:=> [:catn [:final :map]] :any]})
  (Operation review-event
    "Pure: build a :review ledger payload from the loop's terminal value `final`"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]
     :delegates [refused-repairs]})
  (Operation append-review-entry!
    "Resolve cwd → session → workstream (the tasks.nido-ship path) and append one :review"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation parked-blocker
    "Pure: the halt a run holding parked findings owes a human, or nil."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation append-blocker!
    "Append the halt, if there is one. Best-effort for the same reason"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation queue-analysis!
    "Queue this run for nido-side analysis. Best-effort, for the same reason"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation verdict-worth-running?
    "Whether the verdict pass has anything to judge."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation unreadable-tree
    "Conflict markers in `base..@`: the change ids holding them, or nil for none."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation append-design-verdict!
    "Run the design verdict and append it as a ledger event. Best-effort throughout,"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation off-position-line
    "One line when the stage a verb was told to run is not the stage the ledger says is due, or
     nil when they agree or there is nothing to compare against.

     NOT A REFUSAL, and that is why it is a line. Every legitimate use of these verbs is a
     person who knows the position and means something else — re-judging a superseded record
     with `:seq`, judging against another tree with `:code-cwd`. Refusing would take the
     override away at the moment it is most wanted; saying nothing leaves a caller unable to
     tell an override from a mistake, which is what a verb naming its own stage always did."
    {:signature [:=> [:catn [:cwd :any] [:kind :keyword]] [:maybe :string]]
     :delegates [pipeline/of]})
  (Operation refusal-lines
    "What to tell someone whose workstream is already busy with something ELSE. Pure, and it
     says WHAT is running rather than that something is — a refusal with no subject is the
     message that sends a person to `ps`. It offers `kill` only against a process the reader
     owns: a round hosted by the coordinator daemon shares it with everything else that daemon
     drives."
    {:signature [:=> [:catn [:mine :any] [:their :any] [:coordinator-pid [:? :any]]] :string]})
  (Operation orphans-settled-lines
    "What this run closed on its way in: the review runs that died on this tree without a
     terminal status, forced to `orphaned` and queued for analysis. Said out loud because it is
     the only notice those analyses were queued — several sessions can appear on nido's board
     from one invocation, and a reader who was not told reads them as invented work."
    {:signature [:=> [:catn [:orphaned :map]] [:sequential :string]]})
  (Operation orphans-refusal-lines
    "Why nothing was reviewed, when a run that died on this tree stopped in its fix phase.

     Two refusals, and the difference is whether the dead run's agents are still going. One
     that has gone quiet is history and its report has just been closed, so the refusal fires
     once and the next invocation reviews the branch as it now stands. One still writing is
     not: its fixers outlived the loop, and reviewing now would read a tree they are part-way
     through rewriting."
    {:signature [:=> [:catn [:orphaned :map]] [:sequential :string]]})
  (Operation join-or-refuse!
    "The branch a second invocation takes: JOIN the holder when it is doing the same work,
     refuse when it is doing something else — same work being the same KIND of round on the same
     TARGET. Asking for the round already running means you want to see it, so a join returns
     the holder's own terminal status; asking for anything else means you want work the holder
     is not doing, and running it anyway would put two agents on one tree."
    {:signature [:=> [:catn [:mine :map] [:their :any] [:project ProjectName]
                            [:ws-id WorkstreamId]] :any]
     :delegates [refusal-lines]})
  (Operation claiming
    "Run a command holding the workstream's activity claim, or hand the caller to whoever has
     it. A review outside a nido session takes no claim and simply runs: there is no workstream
     to be the singleton of."
    {:signature [:=> [:catn [:opts :map] [:f [:=> [:catn] :any]]] :any]
     :delegates [join-or-refuse!]})
  (Operation outcome-lines
    "What a finished diff run says on the terminal: the status, the particulars only that run
     holds, and the sentence saying what the status asks of the reader. Pure, so the sentences
     can be asserted on — for a round somebody ran themselves these lines are the whole of what
     they get, the report they point at being a JSON file in a run dir.

     It takes the terminal context AND the whole report, because a context is rebuilt every
     round: what a middle round did to the branch is remembered by the report alone."
    {:signature [:=> [:catn [:final :map] [:report :map] [:report-path :string]]
                 [:sequential :string]]
     :delegates [refused-repairs]})
  (Operation no-yardstick
    "Why this cwd may not have its implementation reviewed, or nil when it may.

     A REFUSAL, where `off-position-line` beside it is a line, and the two differ on whether an
     override is a thing a caller can legitimately want. Naming a stage the ledger did not choose
     is a person meaning something else; reviewing an implementation against no design is not a
     stronger reading of the loop, it is a weaker one — the warden is told \"do NOT park anything
     for contradicting an invariant\" and the one clause that keeps a design question away from a
     fixer cannot fire.

     Taken before the run id, the report and the activity claim, so a refusal leaves nothing on
     disk for `reconcile` to mistake for a run that died."
    {:signature [:=> [:catn [:cwd :any]] [:maybe :map]]})
  (Operation loop-cmd*
    "The `loop-cmd*` entry point."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation loop-cmd
    "The `loop-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation baseline-cmd*
    "Verify a baseline against the code, and keep correcting it until the code stops refuting it.
     `:seq` names WHICH baseline; the newest by default."
    {:signature [:=> [:catn [:opts :map]] :any]})
  (Operation design-cmd*
    "Decide, against the latest design record, whether this should be executed —"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation baseline-cmd
    "The `baseline-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation design-cmd
    "The `design-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation run-context
    "What this run can and cannot reach, as {:has [..] :missing [..]}. A run outside a session
     silently loses the cache and the ledger; this is what says so before it starts."
    {:signature [:=> [:catn [:cwd Path]] :any]}))
