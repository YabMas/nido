(ns canvas.platform.io
  "Self-spec: `nido.platform.io` — reading and writing files without tearing them.

   Every write here is atomic (temp file + rename) so a reader never observes half a file, and
   `with-file-lock` is what makes a read-modify-write safe across nido's several processes."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]))

(Module platform-io
  "Atomic file reads and writes, plus the cross-process lock."
  (Operation with-file-lock
    "Run `f` holding an exclusive lock on `lock-path`. TWO locks, because each covers what the
     other cannot: the OS file lock excludes other PROCESSES, the interned monitor makes two
     threads in one JVM queue rather than collide."
    {:signature [:=> [:catn [:lock-path :string] [:f [:=> [:catn] :any]]] :any]})
  (Operation read-edn
    "Read an EDN file, or nil when it does not exist."
    {:signature [:=> [:catn [:path :string]] :any]})
  (Operation write-edn!
    "Atomically write EDN: a unique temp file, then a rename. A reader sees the old content or
     the new one, never a partial."
    {:signature [:=> [:catn [:path :string] [:data :any]] :any]})
  (Operation read-json
    "Read a JSON file into keyword-keyed maps, or nil when it does not exist."
    {:signature [:=> [:catn [:path :string]] :any]})
  (Operation write-json!
    "Write data as JSON, creating parent dirs."
    {:signature [:=> [:catn [:path :string] [:data :any]] :any]})
  (Operation read-text
    "Read a text file, or nil when it does not exist."
    {:signature [:=> [:catn [:path :string]] [:maybe :string]]})
  (Operation write-text!
    "Write text, creating parent dirs."
    {:signature [:=> [:catn [:path :string] [:text :string]] :any]}))
