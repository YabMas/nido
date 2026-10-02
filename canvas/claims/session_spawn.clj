(ns canvas.claims.session-spawn
  "The claims the session-spawn design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.lifecycle :refer [session-lifecycle stage-kickoff!]]
            [canvas.session.support :refer [session-links]]
            [canvas.tasks.nido-session :refer [spawn up]]
            [canvas.tasks.nido-work :refer [work-cmd*]]))

(Claim spawn-composes-up
  "spawn reaches a session only through the same path up takes — a workstream that cannot be joined is refused before anything is provisioned — and its only use of :ws-id is to hand it to that path."
  {:about [spawn up] :evidence "round"})

(Claim kickoff-runs-once-in-its-home
  "A staged kickoff starts at most one agent: the hook moves it out of the session home before running it, so a second shell opening there finds nothing; and it runs only in a shell whose cwd is that session's home."
  {:about [stage-kickoff!] :evidence "round"})

(Claim child-told-its-parent
  "The child agent's first prompt carries the brief verbatim, the parent agent's address, and when to message it; and the child agent is started under the child session's name, which is the address spawn reports to its caller."
  {:about [stage-kickoff! spawn] :evidence "round"})

(Claim relation-recorded-both-ways
  "After a spawn the parent session's links and the child session's links each hold a :session link naming the other, so each briefing names its counterpart across restarts until destroy."
  {:about [spawn session-links] :evidence "round"})

(Claim child-started-as-nido-work
  "The kickoff starts the child agent by running nido work in the child's worktree, in the person's terminal and environment, so it gets the same briefing and MCP config a person typing nido work gets; a name, a first prompt, a permission mode and a model are the only additions, and nido work given none of them assembles the command it did before."
  {:about [stage-kickoff! work-cmd*] :evidence "round"})

(Claim child-in-parents-permission-class
  "spawn starts the child agent in the permission mode it is given, bypassPermissions when given none, so a parent and child in one permission class exchange messages with no approval held on either side."
  {:about [spawn stage-kickoff!] :evidence "round"})

(Claim spawn-asks-no-question
  "spawn enters up's path with the fleet-budget question answered yes, as up given :yes does, so spawn neither prompts nor reads an answer from its caller's terminal whether or not that terminal has a console; the budget question is the only read of a person's answer on that path."
  {:about [spawn up] :evidence "round"})

(Claim child-model-as-given
  "spawn starts the child agent on the model it is given and, given none, passes no model, so the child runs on claude's default; the parent's model has no bearing on it."
  {:about [spawn stage-kickoff!] :evidence "round"})

(Claim terminal-mechanism-hidden-in-lifecycle
  "Only the session lifecycle names the kickoff file and the terminal mechanism; spawn asks for a staged kickoff and a tab and knows neither."
  {:about [session-lifecycle spawn] :evidence "round"})
