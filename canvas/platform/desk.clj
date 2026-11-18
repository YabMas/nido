(ns canvas.platform.desk
  "Self-spec: `nido.platform.desk` — the person's machine, as a boundary.

   nido's host and the machine the person sits at are the same machine only by circumstance.
   Whatever decides a person-facing effect — open a terminal at a directory running a command,
   open a URL, post a notification — asks the desk for it and never performs it itself. On the
   person's own machine the desk performs it in place; when nido runs elsewhere, the effect is
   recorded on nido's host and a desk process on the person's machine reads the requests over
   ssh and performs them there."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]))

(Kind Effect
  "One person-facing effect, as a closed map of one of three kinds: {:kind :terminal :dir :command
   :title}, {:kind :url :url} with an http(s) URL, or {:kind :notify :title :subtitle :message
   :sound}. Recorded with the instant it was requested and a sequence number, so a desk can read
   only what came after it connected.

   This is the one value the desk parses and performs. The desk parses
   every request against the closed schema before performing it and drops — logging — any that
   fail; nothing it performs comes from an unparsed line. A terminal effect's command runs on
   nido's host: in place when nido's host is the person's machine, inside the ssh session the
   desk opens otherwise.

   SHAPELESS. The closed malli schema in the code is the one copy that is checked.")

(Module platform-desk
  "Where the person is relative to nido's host, and how an effect and the dashboard reach them.
   On another machine than nido's host, the desk's ssh connection to it also forwards the
   dashboard's loopback port."
  {:child [Effect]}
  (Operation request!
    "Ask for one effect. Performed in place when the person is on this host, recorded for a desk
     otherwise. Never throws: an effect is never worth failing its caller over."
    {:signature [:=> [:catn [:effect Effect]] :any]})
  (Operation requests-since
    "The effects recorded after `cursor`, oldest first, with the cursor to read from next — what
     a desk on another machine reads."
    {:signature [:=> [:catn [:cursor :any]] :map]})
  (Operation perform!
    "Parse one effect and carry it out on this machine; false when it does not parse. A terminal
     effect whose directory is on another host opens a local tab running `ssh -t <host>` into
     that directory and command."
    {:signature [:=> [:catn [:effect :map]] :boolean]}))
