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
  "The kickoff starts the child agent by running nido work in the child's worktree, in the person's terminal and environment, so it gets the same briefing, MCP config and claude defaults a person typing nido work gets; the name and first prompt are the only additions, and nido work given neither assembles the command it did before."
  {:about [stage-kickoff! work-cmd*] :evidence "round"})

(Claim terminal-mechanism-hidden-in-lifecycle
  "Only the session lifecycle names the kickoff file and the terminal mechanism; spawn asks for a staged kickoff and a tab and knows neither."
  {:about [session-lifecycle spawn] :evidence "round"})
