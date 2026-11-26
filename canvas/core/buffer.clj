(ns canvas.core.buffer
  "Self-spec: `nido.core.buffer`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-buffer
  "The workstream's one conductor, as the core runs it. Every outcome of any of
   its units' turns, in any project, and every outside event that bears on them
   (a merge, a premise moved) comes to it, and it directs the arc: the
   unit's next turn, a new unit proposed for the person to grant, or an
   operator ask, which binds the pending calls of the units' asks it raises or,
   where no unit's call is pending, its own conductor turn's call [conductor-asks-go-to-the-person]. Each ask
   a unit's turn raises that no hold on its unit has taken over, it
   answers, returns for further exploration, or raises in an operator ask, alone or consolidated
   with asks from sibling units, bound to their pending calls; it withdraws an operator
   ask whose premise changed, including by a sibling unit — one binding a conductor
   turn's own call among them — which answers no call it binds. It runs
   asleep — woken per outcome or event — or resident, while a person chats
   with it.

   HIDES the policy that decides between them, so a unit's ask awaits the person only
   in an operator ask it raised."
  (Operation decisions
    "The keyed commands the rules issue for a workstream at a committed state, each admitted by the domain against the state the ones before it leave."
    {:signature [:=> [:catn [:opts :map] [:ws :string] [:state :map] [:pos :int]] [:sequential :map]]})
  (Operation brief
    "What a conductor turn is told: its role, what it has not yet heard, and the workstream now —
     past a cleared design, what the recipe owes next, as the pipeline places it."
    {:signature [:=> [:catn [:ws :string] [:state :map] [:items [:sequential :map]] [:owed [:? [:maybe :map]]]] :string]})
  (Operation owed-reader
    "What the recipe owes a workstream, asked of nido's own owed verb: the pipeline that places it
     is not the core's to require."
    {:signature [:=> [:catn [:opts :map]] fn?]})
  (Operation call-tool
    "Run one conductor tool for a conductor turn, through core-api; answers what the agent reads back."
    {:signature [:=> [:catn [:conn :map] [:ws :string] [:turn :string] [:tool :string] [:args :map]] :string]})
  (Operation respond
    "The MCP response to one message, for the conductor tools of one turn."
    {:signature [:=> [:catn [:ctx :map] [:msg :map]] [:maybe :map]]})
  (Operation mcp-server
    "The conductor tools as an MCP server entry an executor hands its agent."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation conductor-spec-fn
    "The spec of a conductor turn as the daemon runs it: the brief, in the workstream's directory, with its tools."
    {:signature [:=> [:catn [:opts :map]] fn?]})
  (Operation start
    "Start applying the rules to committed changes."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation changed!
    "Hand the rules a commit's changes without blocking."
    {:signature [:=> [:catn [:buffer :map] [:changes [:sequential :map]]] :nil]})
  (Operation stop!
    "Stop applying the rules."
    {:signature [:=> [:catn [:buffer :map]] :nil]})
  (Operation person-input?
    "Whether a state's inbox holds a person's input."
    {:signature [:=> [:catn [:state :map]] :boolean]})
  (Operation background-running
    "How many of a state's conductor turns run hearing no person's input."
    {:signature [:=> [:catn [:state :map]] :int]})
  (Operation wake-owed?
    "Whether a state owes its conductor a wake: its inbox holds items, it is not closed, no
     conductor turn runs or waits to resume, and it is not stopped."
    {:signature [:=> [:catn [:state :map] [:max-failures :int]] :boolean]})
  (Operation wakes
    "The conductor wakes to issue across every workstream: a wake for a person's input at once, the
     rest only while fewer than the wake cap of background conductor turns run, in the order they
     became owed. Nothing runs while idle."
    {:signature [:=> [:catn [:opts :map] [:states [:map-of :string :map]] [:owed-order [:sequential :string]]]
                 [:sequential :map]]})
  (Operation next-retry-in
    "Milliseconds until the earliest conductor backoff among the states ends, or nil when none is
     pending: a backoff ends with no commit to say so, so the rules wait no longer than this and
     then decide the wakes again. A workstream backing off is woken by nothing, a person's input
     included."
    {:signature [:=> [:catn [:states [:map-of :string :map]] [:now :int]] [:maybe :int]]}))
