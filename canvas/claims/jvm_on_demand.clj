(ns canvas.claims.jvm-on-demand
  "The claims the on-demand dev JVM design commits nido to: a project may declare its REPL JVM
   `:on-demand`, and a front holding the session's ports starts it on a first byte and stops it
   when idle.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.service :refer [session-service]]
            [canvas.session.provisioning :refer [session-engine]]
            [canvas.session.state :refer [session-state]]
            [canvas.session.briefing :refer [session-launcher]]
            [canvas.session.fleet :refer [session-fleet]]
            [canvas.session.lifecycle :refer [session-lifecycle]]))

(Claim jvm-absent-until-first-byte
  "When a project's :process service declares :on-demand, a cold runtime start starts every other declared service and the front, and no JVM. The JVM is started only when a client sends at least one byte to the front's nREPL or app port. A connection that is opened and closed with nothing sent starts nothing."
  {:about [session-service session-engine] :evidence "round"})

(Claim front-ports-are-the-sessions
  "For an on-demand runtime, the registry entries, the session context and the briefing name the front's nREPL and app ports. Those ports are allocated before the runtime starts and are stable for its life, whether or not the JVM runs, so session-live? reads the session as up while the front answers. The JVM's own nREPL port and the app's bound port are inner ports and are named to no agent."
  {:about [session-state session-launcher] :evidence "round"})

(Claim first-call-waits-and-runs
  "Bytes a client sends while the JVM is absent or starting are held and relayed in order once the inner port answers, after the app start form for the app port, and the replies are relayed back unchanged. So the client speaks the protocol it would speak to the JVM directly, and needs no step of its own whenever the JVM is ready within the client read timeout. A client that gives up first sees its call fail, the start goes on, and its retry is relayed like any call."
  {:about [session-service] :evidence "round"})

(Claim one-start-per-runtime
  "However many clients trigger it at once, an on-demand runtime's JVM is started once. Starting and stopping it run under the runtime's lifecycle lock, so no up, down, sync or join of that runtime interleaves with them."
  {:about [session-engine] :evidence "round"})

(Claim idle-stop-preserves-session
  "The front stops the JVM (the app's stop form, then the process) once no byte has passed through its nREPL port for the declared idle period AND no connection through either front port is open. It stops nothing else. The runtime, its registry entries and front, its database claim, test database, config file, worktree and session home all stay, and the next byte starts the JVM again."
  {:about [session-engine session-lifecycle] :evidence "round"})

(Claim idle-period-declared
  "The idle period is declared with :on-demand on the project's :process service in session.edn (brian declares 30 minutes). A :process service with no :on-demand starts its JVM in the runtime start, as before."
  {:about [session-engine] :evidence "round"})

(Claim every-start-timed
  "Every on-demand JVM start appends one record of project, session, instance, triggering port, the instant the first byte arrived, the instant it ended, the duration between them, and an outcome: ready (the JVM, and for the app port the app, answered), failed (with the cause), or ready after its first client gave up. The records go to a per-project log outside the per-instance state, so a destroy keeps them, and a nido verb reads them back per session."
  {:about [session-engine] :evidence "round"})

(Claim lazy-failures-kept
  "An on-demand start that throws is kept under ~/.nido/failures/ like a failed lifecycle start, with an origin naming the on-demand trigger, so it reaches session recovery. The connections it was holding are closed."
  {:about [session-lifecycle] :evidence "round"})

(Claim fleet-counts-running-jvm
  "An on-demand runtime's registry entries carry the JVM's pid while it runs and none while it does not, so the fleet charges a session's memory only while its JVM runs."
  {:about [session-fleet session-state] :evidence "round"})

(Claim front-ends-with-runtime
  "Every stop of an on-demand runtime (down, restart, reset, destroy, or a held unit's session) stops its JVM, if one runs, and its front. A front whose runtime record is gone exits, so no front outlives its runtime."
  {:about [session-engine] :evidence "round"})

(Claim briefing-says-first-call-waits
  "For an on-demand runtime, the briefing names the front's nREPL port with the same `clj-nrepl-eval -p <port>` command, and says that the JVM starts on the first call, which waits for it, rather than that it is already warm."
  {:about [session-launcher] :evidence "round"})
