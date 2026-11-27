(ns canvas.claims.qa-roles
  "The claims the qa-roles design (ws-20261127-74df0e, design at ledger entry 18) commits nido to:
   a QA plan declares the roles its steps are performed as and the state each role's account must
   be in, a walk records which account played each role and is refused without one for every role
   its plan's steps use, the landing brief names roles and cast, the planner's and walker's
   directions make casting a precondition of a walk, and qa-brief.md says what nido owns and what
   each project's docs/reference/qa-roles.md does.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three things the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.core.buffer :refer [brief]]
            [canvas.tasks.nido-workstream :refer [entry-add* show*]]))

(Claim plan-declares-roles
  "A :product :qa-plan written now carries :roles — at least one, each {:id :kind :needs}: a plan-local id unique in the plan, the project's name for the role, and the state its account must be in — and every step a :role naming one of them; validate-event refuses a plan otherwise, naming the step or role. A :none plan carries no roles. Plans written before roles read under a read-era schema for :qa-plan, so their QA reading is unchanged."
  {:about [coordinator-report] :evidence "round"})

(Claim walk-records-cast
  "A :qa-walk carries :cast, one {:role :account} entry per role, where :account says which account in the walked environment played it. Appending a walk of a plan with roles is refused, naming the role, when a role some step of the plan performs is not cast, when a cast entry names a role the plan does not declare, or when a role is cast twice. A walk of a plan written before roles carries no cast."
  {:about [coordinator-report record-workstream entry-add*] :evidence "round"})

(Claim brief-names-roles-and-cast
  "For a plan with roles, the brief and the plan's rendering list each role under Before you start as its project name and the state it needs, and begin each step with the role that performs it; the brief, given the newest walk the passing QA reading counts, adds a line naming the account that played each role. show* hands the brief that walk. A plan without roles renders as it does today."
  {:about [coordinator-report show*] :evidence "round"})

(Claim walker-casts-before-walking
  "The :plan-qa direction tells the planner to declare the plan's roles with the state each needs and to give every step its role, naming roles as the project's docs/reference/qa-roles.md does where the project has one. The :walk-qa direction tells the walker to cast every role before walking — with the accounts and setup helpers that reference names, against the session's own app and database — to record the cast, and, when a role cannot be cast with an account in its declared state, to record no walk and ask the person, naming the role and the state, rather than play it with another account."
  {:about [brief] :evidence "round"})

(Claim ownership-documented
  "docs/reference/qa-brief.md says what a plan's roles and a walk's cast are, that Before you start names each role and its state, and that nido owns the plan and walk schema, the planner's and walker's directions and the brief, while each project owns docs/reference/qa-roles.md — its roles, the seed accounts for them per environment (session dev database, staging) and the helpers that make an account and its fixtures meet a stated state."
  {:about [coordinator-report] :evidence "round"})

(Claim roles-tested
  "test/nido/coordinator/record/qa_test.clj asserts each refusal of plan-declares-roles and walk-records-cast, that an earlier plan and walk still read, and the brief's role and cast lines; test/nido/core/buffer_test.clj asserts walker-casts-before-walking's wording; bb nido:test passes."
  {:about [coordinator-report brief] :evidence "round"})
