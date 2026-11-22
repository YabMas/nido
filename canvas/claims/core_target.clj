(ns canvas.claims.core-target
  "The claims the target-core design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.core.store :refer [core-store]]
            [canvas.core.writer :refer [core-writer]]
            [canvas.core.api :refer [core-api]]
            [canvas.core.feed :refer [core-feed]]
            [canvas.core.turn :refer [core-turn]]
            [canvas.core.executor-claude :refer [executor-claude]]
            [canvas.core.ask-tool :refer [core-ask-tool]]
            [canvas.core.buffer :refer [core-buffer]]
            [canvas.core.reconcile :refer [core-reconcile]]))

(Claim one-writer
  "Every durable change to the coordination state the core holds — workstreams, units of work, turns, asks and each conductor's inbox — goes through core-writer: one thread applies commands and is the only writer of core-store. No other thread or process — CLI, surface, hook, review loop, agent tool — writes the store. Work records — design and baseline records, review rounds, phases, forks, standing — are not held by the core yet; until they move into it (FU-795) they are written outside it, and only then does every durable change to nido's state go through core-writer."
  {:about [core-writer core-store core-api] :evidence "round"})

(Claim commands-are-keyed
  "Every command sent to core-api carries a key its sender chose, and core-writer decides a key at most once: a command whose key is already committed is answered with the outcome committed under it and is not decided again, so a sender that resends after losing its answer gets the original outcome."
  {:about [core-api core-writer core-store] :evidence "round"})

(Claim commit-is-the-fold
  "A batch's ledger events, the workstream state rows they produce and the open-ask index commit in one core-store transaction, so stored state always equals the fold of the ledger. Rebuilding the rows from the ledgers yields the stored rows, which tests and an explicit verify command check; a release that changes the fold ships a migration that rebuilds the rows before it serves. No ordinary start rebuilds or compares anything."
  {:about [core-store core-domain] :evidence "round"})

(Claim output-gate
  "core-writer releases nothing a command produced — a caller result, a change-feed message, or an external effect to be attempted — before the commit that recorded it is durable."
  {:about [core-writer core-feed] :evidence "round"})

(Claim ask-ends-the-turn
  "A turn that asks ends: its executor returns the outcome asked, carrying the pending call, and no process runs that turn until a resolution is recorded and the turn is resumed with it. An asked turn produces no further effects."
  {:about [core-turn core-ask-tool executor-claude] :evidence "round"})

(Claim asks-equal-asked-turns
  "In every committed state the open asks are exactly the asks of turns in state asked, one per asked turn. A unit of work has at most one active turn — running, asked, or resolved and not yet resumed. A unit an open operator ask concerns — the unit it names, or every unit of its workstream when it names none — has no running turn and admits no new one: the commit raising the ask records each such unit's running turn as cancelled, and that turn's process is cancelled only after the commit [output-gate], whatever it reports afterwards discarded. A workstream's conductor has at most one turn running; an asked or resolved conductor turn does not count against it. core-domain admits no command that commits otherwise, and core-store's declared table constraints refuse such a commit whatever was decided."
  {:about [core-domain core-store] :evidence "round"})

(Claim buffer-answers-first
  "Every ask a unit's turn raises is resolved in exactly one way: by its workstream's core-buffer — answered (the turn resumes with the answer), returned for exploration (the turn resumes with instructions), or raised in one operator ask, alone or consolidated with asks from sibling units, bound to its pending call — or closed as taken over by a hold on its unit [attach-is-a-hold]. A unit's ask is shown as awaiting the person only inside an operator ask core-buffer raised bound to its call; conversation in a resident chat may carry its question, and resolves it only as one of those recorded dispositions."
  {:about [core-buffer] :evidence "round"})

(Claim operator-ask-lives-with-its-call
  "Every operator ask binds at least one pending call — a unit turn's, or a conductor turn's own [conductor-asks-go-to-the-person] — and is open exactly while every call it binds is pending. Whatever closes one of them closes the ask and all its calls in one commit, each call in exactly one way: as taken over, where a hold on its unit took it [attach-is-a-hold], or with a resolution — the person's answer, or, where the ask is withdrawn on a premise change or because a hold took another of its calls, a refusal and its reason. Each turn whose call closed with a resolution is resumed with it only after that commit is durable [output-gate] [effects-recorded-before-attempted]; a turn whose call was taken over is never resumed."
  {:about [core-buffer core-domain] :evidence "round"})

(Claim turn-knows-no-provider
  "core-turn and everything above it name no provider. An executor adapter alone maps the Turn's operations — run from instructions and context to one outcome of completed, asked, failed or cancelled; cancel a running turn; resume an asked turn with a resolution; take back a turn by its recorded handle; hand a unit's session to a person and take it back — onto a provider's mechanism, and a provider's hooks and output reach nido only through its adapter, parsed at the executor port."
  {:about [core-turn executor-claude] :evidence "round"})

(Claim observations-expire
  "Every fact core-reconcile holds about something nido does not own carries when it was observed and how long it holds; past that, or when observing failed, it reads as unknown, never as absent or empty."
  {:about [core-reconcile] :evidence "round"})

(Claim events-before-probes
  "core-reconcile learns of change from events — process exit handles, executor output, filesystem notifications — and resyncs at most once a minute; no process is spawned to observe a fact an event delivers."
  {:about [core-reconcile] :evidence "round"})

(Claim idle-does-nothing
  "With no command, event or due timer, the daemon executes nothing — its threads block — and routine coordination (every command, event and ordinary start) costs what it touches, not the size of the ledger; only explicitly requested work — a verification of stored state, or a migration (one that changes the fold, or the legacy import) — reads all of history."
  {:about [core-writer core-reconcile] :evidence "round"})

(Claim external-calls-are-bounded
  "Every call the core makes outside the daemon, through core-turn's executor port or by core-reconcile — a turn's run, a command such as git or gh, an observation — carries a deadline. A turn past its deadline is cancelled; any other call past its deadline is ended and its result is unknown, never success or empty."
  {:about [core-turn core-reconcile] :evidence "round"})

(Claim attach-is-a-hold
  "Attaching a person to a unit's raw agent session is a fault-recovery hatch, not a flow. The hold's commit marks the unit held, records the unit's active turn's outcome as cancelled by takeover, closes any pending call as taken over and withdraws any operator ask binding it; only after that commit is the turn's process cancelled through the executor port, and whatever it reports afterwards is discarded. The cancelled outcome is the one that reaches the conductor, which starts nothing on a held unit. While held, nido routes nothing asked inside the session through a conductor and records nothing of its activity; releasing it reaches the conductor as an outside event. A hold stops no sibling unit."
  {:about [core-domain core-turn] :evidence "round"})

(Claim workstream-composes-units
  "A workstream composes its units of work — each one goal and its argument in exactly one project, with its own ledger, turns and asks — and is the boundary every command is decided against, so an invariant spanning units holds in the same commit that changes any unit: a unit's landing is refused while a unit it is ordered after has no recorded merge, naming that unit; the workstream closes only once every unit has merged or had its goal retracted; an operator ask binds calls from several units; a sibling's change withdraws an ask."
  {:about [core-domain core-buffer] :evidence "round"})

(Claim conductor-hears-every-outcome
  "Every outcome of a turn of any unit of a workstream — completed, asked, failed or cancelled, in any project — and every observed event bearing on its units, such as a merge, reaches that workstream's conductor exactly once, and what runs next on a unit is decided there: a next turn, a proposed unit for the person to grant, or an operator ask, which binds the unit's pending call where its turn asked and is otherwise its conductor turn's own ask [conductor-asks-go-to-the-person], so the person's answer reaches the conductor as that turn's resolution."
  {:about [core-buffer core-turn] :evidence "round"})

(Claim input-ahead-of-background
  "A command a surface sends on a person's input is taken by core-writer ahead of every background command (turn outcomes, observations, due timers) not already in the batch being committed, and a batch commits a bounded number of commands; core-api answers reads from committed state without waiting on core-writer; and core-feed delivers to each subscriber from a buffer of its own, cutting off one that falls behind to resume from its last position. So input a surface sends to core-api waits on at most the batch in progress and earlier input, whatever the number of workstreams or the background backlog."
  {:about [core-writer core-api core-feed] :evidence "round"})

(Claim conductor-asks-go-to-the-person
  "An ask raised by a conductor turn is never answered or returned for exploration by a conductor: it is raised as an operator ask bound to that turn's own pending call. Withdrawing that operator ask when its premise changes [operator-ask-lives-with-its-call] answers and returns nothing."
  {:about [core-buffer core-turn] :evidence "round"})

(Claim operator-asks-are-decision-ready
  "Every operator ask, and every unit proposed for the person to grant, states what was explored, the options with their consequences, and a recommendation."
  {:about [core-buffer core-ask-tool] :evidence "round"})

(Claim effects-recorded-before-attempted
  "An external effect a command core-writer decides produces is attempted only after a commit records it under that command's key, and once the migration is complete every external effect nido attempts is produced so. Recovery records as unknown both an attempt whose completion is unrecorded and the actions of a turn whose run a crash ended, and the workstream's conductor settles each before anything on that unit proceeds — an attempt by carrying it out again where its receiver deduplicates on its key, otherwise by observing the outside world, or, where that cannot settle it, by an operator ask its conductor turn raises as its own [conductor-asks-go-to-the-person]. No effect takes place twice for one recorded command, and none is lost without an operator ask saying so."
  {:about [core-writer core-buffer core-reconcile] :evidence "round"})

(Claim events-are-inputs
  "Every event core-domain yields records an input nido cannot recompute: a person's command, a turn's outcome, a conductor's judgement — every decision core-buffer makes, whether by rule or by a turn of its own — or a point-in-time fact about the outside world such as a merge. Nothing core-domain's fold of earlier events yields is committed as such an event, and no current condition that can be observed again, such as a process being alive, is ever one. An entry imported from a legacy ledger is not such an event: it is held with its content as written, marked as imported, and read as the legacy record it is."
  {:about [core-domain core-store] :evidence "round"})

(Claim executor-state-is-a-handle
  "The store holds a turn's executor handle, never the provider's session state; a handle the executor can no longer resume or take back is that turn's failed outcome, recorded like any other."
  {:about [core-turn core-store] :evidence "round"})
