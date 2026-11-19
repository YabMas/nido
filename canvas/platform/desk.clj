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
  (Operation parse
    "The value as an Effect, or nil when it is not one. The only door from an untrusted value — a
     line off the wire, a caller's map — to something performed."
    {:signature [:=> [:catn [:x :any]] [:maybe Effect]]})
  (Operation person-elsewhere?
    "Whether the person sits at another machine than this host, as this host's desk config
     declares. Absent config means they are here, and effects are performed in place as always."
    {:signature [:=> [:catn] :boolean]})
  (Operation set-person!
    "Declare where the person sits relative to this host from now on."
    {:signature [:=> [:catn [:where [:enum :here :elsewhere]]] :any]})
  (Operation opens-terminals?
    "Whether a terminal effect will open a tab for the person: always when they are elsewhere,
     and here only inside Warp."
    {:signature [:=> [:catn] :boolean]
     :delegates [person-elsewhere?]})
  (Operation request!
    "Ask for one effect. Performed in place when the person is on this host, recorded for a desk
     otherwise; dropped, logged, when it does not parse. Never throws: an effect is never worth
     failing its caller over."
    {:signature [:=> [:catn [:effect :map]] :any]
     :delegates [parse person-elsewhere? perform!]})
  (Operation requests-since
    "The effects recorded after `cursor`, oldest first, with the cursor to read from next — what
     a desk on another machine reads."
    {:signature [:=> [:catn [:cursor [:maybe :int]]] :map]})
  (Operation stream!
    "On nido's host, under a desk's ssh connection: print every effect recorded from now on, one
     per line, until the connection closes. Starts at the log's current end, so nothing asked
     before the desk connected is performed. Given a source of loopback ports, it also announces
     the set the desk should forward whenever it changes."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [requests-since]})
  (Operation localize
    "An effect as the person's machine performs it when nido runs on `host`: a terminal effect
     becomes a local tab running `ssh -t host` into the requested directory and command."
    {:signature [:=> [:catn [:host :string] [:effect Effect]] Effect]})
  (Operation run!
    "On the person's machine: hold an ssh connection to nido's host that streams its effects and
     forwards the dashboard's loopback port and every port the stream announces; perform each
     effect that parses, here. Reconnects after a lost connection."
    {:signature [:=> [:catn [:opts :map]] :any]
     :delegates [parse localize perform!]})
  (Operation perform!
    "Parse one effect and carry it out on this machine, through platform-desktop; false when it
     does not parse or the machine refused it. An effect from another host reaches it localized."
    {:signature [:=> [:catn [:effect :map]] :boolean]
     :delegates [parse]}))
