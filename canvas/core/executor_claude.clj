(ns canvas.core.executor-claude
  "Self-spec: `nido.executor.claude`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module executor-claude
  "The Claude Code executor: a turn is one `claude -p` run, detached from the
   daemon, with nido's ask tool, a PreToolUse hook that defers the ask, and `--resume`
   to deliver a resolution and, interactively, to hand the session to a person. A
   turn whose run ended without an outcome is taken back as the outcome failed,
   never resumed. A first run whose spec names images is prompted on stdin, as one stream-json
   user message: the instructions, then one image block per image, read from the image store.

   HIDES everything Claude-specific: flags, the permission policy passed on every run, the stream format, session
   ids, and the hook protocol: its hooks report to it and to no other
   module."
  (Operation executor
    "The Claude Code executor, for the turn port."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation hook-decision
    "The PreToolUse hook's answer: allow the resolved call with its resolution, defer any other."
    {:signature [:=> [:catn [:run-dir :string] [:event :map]] :map]}))
