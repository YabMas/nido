(ns canvas.design.check
  "Self-spec: `nido.design.check` — the seam between nido and fukan."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.platform.project :as project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind DesignConfig
  "How a project's design is laid out and how to ask fukan about it: the code root, the spec
   dirs, the command, an optional scope, and the files that were found.

   Detection is by CONVENTION, not registration — a project is modelled iff its spec dirs hold
   `.clj` files. The canvas is checked into the repo, so its presence in a worktree is already
   the truth about whether that branch has a design."
  [:map [:src :string]
        [:spec-dirs [:vector :string]]
        [:cmd [:vector :string]]
        [:select [:maybe :any]]
        [:files {:optional true} [:vector :string]]])

(Kind CheckResult
  "What the checker found: a status, the violations behind it, and where the declaration they
   violate is written.

   FOUR statuses, and the fourth is the point. `:unmodelled` is not a failure — most projects
   declare no design, and a seam that reported them as broken would be switched off within a
   week, after which it would not be there for the projects that do. `:undecidable` is not a
   pass: nobody could tell, and a consumer reading it as green waves through exactly the branch
   that broke the checker.

   `:files` is carried rather than looked up again. Every reading that prints a violation has to
   say where the declaration is, and each was asking the seam a second time for a config it had
   already resolved to run the check at all."
  [:map [:status [:enum :unmodelled :satisfied :violated :undecidable]]
        [:violations {:optional true} [:vector :any]]
        [:files {:optional true} [:vector :string]]
        [:error {:optional true} :any]])

(Kind DesignDocument
  "What the renderer found: the project's declared design, or why there is none to show.

   THREE statuses where the check has four, and the missing one is `:violated` — rendering a
   declaration asks nothing of the code, so there is nothing for it to disobey.

   `:undecidable` is why this is a record rather than a string. It used to be a nil, which the
   project's absence of a design also produced, so a briefing could not tell a project that
   declares nothing from a render that did not finish — and silently omitted its section on
   exactly the projects whose designs are large enough to be worth reading."
  [:map [:status [:enum :unmodelled :described :undecidable]]
        [:document {:optional true} :string]
        [:error {:optional true} :string]])

(Kind Refusal
  "A violation stated for a terminal: how many rows fired, and the body saying what they are and
   where the declaration they break is written.

   The count is ROWS rather than laws, and having one definition of that is most of why this
   exists — two readings counting differently would report one branch as one problem and as
   forty."
  [:map [:count :int] [:body :string]])

(Module design-check
  "Does this worktree's code still stand up the design its project declared?

   Two things it deliberately is NOT. Not a checker — fukan owns what a violation is, and a
   second opinion here would be a second design. And not an opinion about what to DO with one: a
   briefing warns, a landing gate refuses, a review loop hands it to a fixer, which is why every
   reading returns a status rather than exiting."
  {:child [DesignConfig CheckResult DesignDocument Refusal]}
  (Operation design-of
    "A project's design configuration, or nil when it declares none."
    {:signature [:=> [:catn [:project-name ProjectName] [:worktree :string]] [:maybe DesignConfig]]
     :delegates [project/get-project]})
  (Operation describe
    "The declared design as a document, asked of FUKAN rather than read off disk — fukan is what
     knows which vocabularies were instantiated and which nodes are the project's own.

     `scope` is optional and beats the project's configured default, because the caller with one
     got it from a baseline that read the code and the default was set by someone who had not.

     A render that did not finish answers `:undecidable`, never `:unmodelled`."
    {:signature [:=> [:catn [:project-name ProjectName] [:worktree :string]
                            [:scope [:? [:maybe :any]]]] DesignDocument]
     :delegates [design-of]})
  (Operation check
    "Run the checker and return what it found — its status, its violations, and the files the
     declaration is written in. Never an exit code."
    {:signature [:=> [:catn [:project-name ProjectName] [:worktree :string]
                            [:design [:? [:maybe DesignConfig]]]] CheckResult]
     :delegates [design-of]})
  (Operation offender-line
    "One offender row as a line, its columns labelled by the law's own variable names — which is
     what makes a four-name row readable instead of leaving the reader to guess which is which."
    {:signature [:=> [:catn [:vars [:maybe [:vector :string]]] [:row [:vector :string]]] :string]})
  (Operation violation-text
    "The findings as text for a human or an agent. Empty when there is nothing to say, so a
     caller can splice it in unconditionally."
    {:signature [:=> [:catn [:result CheckResult]] :string]
     :delegates [offender-line]})
  (Operation refusal
    "A refusal as both terminal readings state it: the offending-row count, and the body they
     say identically — the offenders, that one of the two sides is wrong, and where the
     declaration is written.

     The body and NOT the exit code. What a violation costs is the caller's decision — a
     briefing warns where a gate refuses — so a seam answering with an exit code would decide
     for both. Each reading keeps its own headline for the same reason: `design:check` answers
     someone who asked, and `land:check` stops someone who did not."
    {:signature [:=> [:catn [:result CheckResult]] Refusal]
     :delegates [violation-text]}))
