(ns canvas.integration.github
  "Self-spec: `nido.github.*` — the GitHub adapter, over the `gh` CLI.

   A redef seam at the boundary, pure normalisation above it, and nothing that knows what a
   workstream is — which is what makes an adapter replaceable."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module github-client
  "Thin wrappers over the `gh` CLI.

   Uses the machine's existing auth rather than a nido-owned token, which is the whole reason
   this adapter needs no Keychain module of its own."
  (Operation sh! "Shell out to gh. A redef seam."
    {:signature [:=> [:catn [:args :any]] :map]})
  (Operation list-merged-prs "A repo's most recently merged pull requests."
    {:signature [:=> [:catn [:repo :string] [:limit [:? :int]]] :map] :delegates [sh!]})
  (Operation list-assigned-issues "Open issues assigned to someone."
    {:signature [:=> [:catn [:repo :string] [:assignee :string] [:limit [:? :int]]] :map]
     :delegates [sh!]})
  (Operation view-issue "One issue's metadata and body."
    {:signature [:=> [:catn [:repo :string] [:number :any]] :map] :delegates [sh!]}))

(Module github-config
  "Per-project GitHub configuration. Absent means the merge poller is simply off for that
   project, which is why nil is an answer rather than an error."
  (Operation load-config "A project's GitHub configuration, validated, or nil."
    {:signature [:=> [:catn [:project ProjectName]] [:maybe :map]]}))

(Module github-react
  "Pure helpers for the Notion reaction to a merged pull request."
  (Operation people-without
    "A Notion people property with one user removed, order preserved."
    {:signature [:=> [:catn [:people-prop :any] [:user-id :string]] :any]}))
