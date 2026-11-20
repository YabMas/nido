(ns canvas.platform.jj
  "Self-spec: `nido.platform.jj` — running jj from nido."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [fukan.common.typing.malli]))

(Kind ShellResult
  "What a shell-out did: its exit code and both streams. Answered rather than thrown, so a
   caller decides whether a non-zero exit is a failure or an answer."
  [:map [:exit :int] [:out :string] [:err :string]])

(Module platform-jj
  "A jj command run in a directory, and a project's spec dirs read back as files at a revision.

   On the floor because jj is a tool, not a subject: the review loop, the landing and design tasks,
   the composition root and the dashboard's explorer all run it, and nothing about running it
   knows which of them asked. Not every jj call in nido comes through here yet — several session,
   lane and daemon namespaces still shell to jj themselves."
  {:child [ShellResult]}
  (Operation jj!
    "Run a jj command in a directory and answer with what happened."
    {:signature [:=> [:catn [:dir Path] [:args [:* :any]]] ShellResult]})
  (Operation current-change-id
    "The change id of the working copy."
    {:signature [:=> [:catn [:dir Path]] [:maybe :string]] :delegates [jj!]})
  (Operation materialize-spec-dirs!
    "The spec dirs as they stood at a revision, written into a fresh directory the caller deletes —
     nil when the revision names nothing. Everything else the worktree holds is linked in beside
     them, so fukan runs there on the project's own dependency configuration. The design seam is
     handed that directory, never the revision."
    {:signature [:=> [:catn [:worktree :string] [:rev :string] [:spec-dirs [:vector :string]]]
                 [:maybe :string]]
     :delegates [jj!]}))
