(ns canvas.platform.task-args
  "Self-spec: `nido.platform.task-args` — the shared CLI argument parser."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]))

(Module platform-task-args
  "Split a bb task's flat string seq into [positionals opts-map]."
  ;; NO :signature, and the omission is not laziness. `split-args` has two arities and fukan's
  ;; signature sugar takes one `[:=> INPUT OUTPUT]` — a malli `[:function …]` is not an arrow, so
  ;; the extractor reads no types from it either. Declaring one arity would assert something
  ;; false about the other. The gap is fukan's; recorded here rather than papered over.
  (Operation split-args
    "Split CLI args into [positionals opts-map]. A token starting with ':' is a kwarg key and
     consumes the next token; every other token is a positional, order preserved."))
