(ns canvas.claims.grant-over-sendback
  "The claims the grant-over-a-send-back design commits nido to: a person can end a design round
   that never stops sending back, on a design they accept, and a record loop runs only the round
   its command line asked for.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [coordinator-work grant-design! grantable?]]
            [canvas.platform.task-args :refer [unknown-opts-refusal]]
            [canvas.tasks.nido-review :refer [design-cmd baseline-cmd]]))

(Claim one-grant-writer
  "Every grant a person gives on the gate (Approve, Accept over an invalidating verdict) or in chat (`bb nido:design:approve`) is written by one private writer in the work plane, in one shape {:design {:seq} :at-seq [:note] [:overrules]}. The design round writes its :carried grants itself, and the generic entry-add task appends a DesignApproved it is handed through the ledger's own append; neither goes through work."
  {:about [coordinator-work] :evidence "round"})

(Claim grant-follows-a-judged-decision
  "The chat grant (`bb nido:design:approve`, through grant-design!) is written only for the newest design, only while it stands, and only when a decision names it. When the newest decision naming it neither proceeds by report/proceeds? nor asks — it sent the record back — the grant is written only when the person asks for it explicitly with their words, and then carries :overrules true and nothing copied from the decision it answers at :at-seq; the findings that decision leaves open are read off it and named in the answer. Without that, the refusal names the grant over the send-back as its way out. A design no decision names is refused naming the judge-only round, `bb nido:review:design :dry-run? true`."
  {:about [grant-design!] :evidence "test"})

(Claim the-gate-grants-no-send-back
  "The gate's Approve is offered and accepted only when the newest decision naming the newest design proceeds by report/proceeds?; a grant over a decision that does not is given in chat, with words, never by a click."
  {:about [grantable?] :evidence "round"})

(Claim record-loops-run-only-what-was-asked
  "bb nido:review:design and bb nido:review:baseline refuse, before any round runs and exiting non-zero, a command line naming any option the loop does not read, naming each such option with the nearest one it does read; unknown-opts-refusal is what names them."
  {:about [design-cmd baseline-cmd unknown-opts-refusal] :evidence "test"})
