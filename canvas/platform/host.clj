(ns canvas.platform.host
  "Self-spec: `nido.platform.host` — one reading of the machine nido runs on."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Kind HostReading
  "The machine at one instant: its cores, load, memory, the volume a path lives on, and the
   reading user's own processes. Bytes are bytes and CPU time is seconds, whatever unit the
   kernel reported. Load, memory and processes come from /proc and are nil on a host without
   it; cores and disk come from the JVM, and disk is nil only when the volume cannot be read.
   :cpu-seconds is present only on the pids the read was asked to time."
  [:map [:at-ms :int] [:cores :int]
   [:load [:maybe [:map [:one :double] [:five :double] [:fifteen :double]]]]
   [:memory [:maybe [:map [:total-bytes :int] [:available-bytes :int]
                     [:swap-total-bytes :int] [:swap-free-bytes :int]]]]
   [:disk [:maybe [:map [:path :string] [:total-bytes :int] [:free-bytes :int]]]]
   [:processes [:maybe [:vector [:map [:pid :int] [:ppid :int] [:argv [:vector :string]]
                                 [:rss-bytes [:maybe :int]]
                                 [:cpu-seconds {:optional true} :double]]]]]])

(Module platform-host
  "Read the host from the kernel's own files and the JVM, without starting a process.

   HIDES where each figure comes from on each OS — /proc's loadavg, meminfo and per-pid status,
   cmdline and stat on Linux; Runtime and the filesystem everywhere — how each is parsed into
   bytes and seconds, and that a figure the host will not give is nil rather than an error."
  {:child [HostReading]}
  (Operation read-host
    "The host now: load, memory, the volume `:path` lives on, every process the reading user
     owns, and CPU seconds for the `:cpu-pids` asked. Never throws."
    {:signature [:=> [:catn [:opts [:map [:path :string] [:cpu-pids {:optional true} [:set :int]]]]] HostReading]}))
