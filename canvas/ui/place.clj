(ns canvas.ui.place
  "Self-spec: `nido.ui.place` — where the person is in the dashboard, as a chat's agent is told it."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.work :as work]
            [canvas.ui.surface :refer [parse]]
            [fukan.common.typing.malli]))

(Kind Place
  "Where the person is, as one of two alternatives. Known: the page they are on, and the subject it
   has open — a workstream with its stage, the gate open on it and that gate's question, and the
   ledger entry open in its pane — or no subject, said as none. Unknown: the request stated no
   location that can be read into a path and query, so no page is named; it is never a known page with no subject. Derived,
   never stored: it is read from the page's location and the work plane at the moment it is asked
   for.")

(Module ui-place
  "Where the person is in the dashboard, as one value every chat reads.

   HIDES which surface declares which subject — the one table, keyed by every surface a ViewState
   can name, that says what each page shows a person could mean by 'this' — how the location of
   the page a request came from is read, and how a place is worded for an agent. A surface whose
   entry names no subject is declared that way; a surface with no entry is not a page a chat can
   be on."
  {:child [Place]}
  (Operation place
    "The place of the page a request was sent from, read from the location that request states
     and the work plane now; a place saying it is unknown when the request states none."
    {:signature [:=> [:catn [:req :map]] Place]
     :delegates [parse work/workstream work/gates]})
  (Operation references
    "A place as the references a message carries: one per thing it names — the page, then its
     subject's parts — each a label and its detail; one saying the place is unknown when it is."
    {:signature [:=> [:catn [:place Place]] [:sequential :map]]}))
