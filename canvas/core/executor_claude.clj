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
   Every run is supervised, and the supervisor ends Claude and everything it started at the first
   deferred call: Claude otherwise stays alive past a deferral while a background task of its runs,
   and the task's end wakes the agent past its ask. A run woken past its ask anyway is still :asked,
   and its resumption gives the resolution as a prompt, since no call is left to re-offer.

   HIDES everything Claude-specific: flags, the permission policy passed on every run, the stream format, session
   ids, and the hook protocol: its hooks report to it and to no other
   module."
  (Operation executor
    "The Claude Code executor, for the turn port."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation settled?
    "Whether a run's output is at rest at a final answer, held by no background task it waits for."
    {:signature [:=> [:catn [:run-dir :string]] :boolean]})
  (Operation stop-decision
    "The Stop hook's answer: block once per background task still running to ask whether it is
     awaited or left over, and record as awaited one the model ended again over."
    {:signature [:=> [:catn [:run-dir :string] [:event :map]] [:maybe :map]]})
  (Operation hook-decision
    "The PreToolUse hook's answer: allow the resolved call with its resolution; deny any other whose
     question breaks the form, the reason the instructions core-domain's ask-problems gives; defer
     the rest."
    {:signature [:=> [:catn [:run-dir :string] [:event :map]] :map]})
  (Operation supervise
    "Run a Claude command line with its output passed through, ending it and everything it started
     at its first deferred call; answers the exit code."
    {:signature [:=> [:catn [:argv [:sequential :string]]] :int]}))
