(ns canvas.coordinator.report
  "Self-spec: `nido.coordinator.report` — the ledger's typed vocabulary.

   The first data shapes nido models. Every band above the floor speaks this namespace: a Record
   validating an entry on the way in, a Lane driving one, WorkPlane composing a workstream,
   Surface rendering it, Review judging it. It is its own band for that reason, and until now the
   model could say that six bands depend on it while being unable to name a single thing in it.

   What is modelled here is the VOCABULARY, not the shapes. `report.clj` holds 83 malli
   definitions; three Kinds appear below, and each is here because an operation's signature wants
   a name rather than `:any`. The rest stay in the code, where they are the write contract and are
   checked on every append. A Kind carries no correspondence — nothing extracts it, nothing
   compares it — so a `:shape` copied out of the code would be an unchecked second copy, drifting
   from the day it was written. `LedgerEvent` is deliberately shapeless: the union it names is 21
   schemas long and the registry that holds them is the truth."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

;; ── the ledger's vocabulary ──────────────────────────────────────────────────

(Kind EntryKind
  "Which typed event an entry is — the key `event-schemas` registers a schema under. A kind
   absent from that registry is not invalid; it is stored as verbatim markdown."
  :keyword)

(Kind LedgerEvent
  "One typed entry payload: a triage report, a baseline, a design, a blocker, a verdict — the
   union of everything `event-schemas` registers, plus freeform markdown.

   SHAPELESS on purpose. The union is 21 schemas and they live in the code, where they are the
   write contract and are enforced on every append. Naming it here is what lets a signature say
   which of its arguments is a ledger event; copying its shape here would be a second source of
   truth that nothing checks.")

(Kind Invariant
  "One `:invariants` entry, normalised — what must hold, and when. A plain string is what every
   record written before phasing carries and means `:always`, which is why every reader goes
   through the normaliser rather than testing `string?` at the point of use."
  [:map [:invariant :string] [:holds :keyword]])

;; ── the module ───────────────────────────────────────────────────────────────

(Module coordinator-report
  "The ledger's typed vocabulary: what may be written, what may be read back, and how each event
   renders."
  (Operation validate-event
    "THE WRITE CONTRACT. Validate a payload against the schema registered for `kind` — what may
     be appended today. Returns the report; throws with a malli explain on mismatch."
    {:signature [:=> [:catn [:kind EntryKind] [:report LedgerEvent]] LedgerEvent]
     :performs  [:throws]})
  (Operation parse-event
    "THE READ CONTRACT. Like `validate-event`, but accepts anything that was legitimately
     writable when it was written, not only what is writable now. Every reader uses this."
    {:signature [:=> [:catn [:kind EntryKind] [:report :any]] LedgerEvent]
     :performs  [:throws]
     :delegates [validate-event]})
  (Operation validate
    "The backward-compatible triage validator — `(validate-event :triage report)`."
    {:signature [:=> [:catn [:report LedgerEvent]] LedgerEvent]
     :performs  [:throws]
     :delegates [validate-event]})
  (Operation entry-payload
    "For a ledger append: given an entry `kind` and raw `content`, the [extension payload] pair
     to write. A registered kind parses and validates; an unregistered one is markdown."
    {:signature [:=> [:catn [:kind EntryKind] [:content :string]] [:tuple :string :string]]
     :performs  [:throws]
     :delegates [validate-event]})
  (Operation invariant
    "Normalise one `:invariants` entry to its `{:invariant :holds}` shape."
    {:signature [:=> [:catn [:x :any]] Invariant]})
  (Operation seam-closure
    "The one-line rendering of what closes a seam, or nil for a legacy seam that names none.
     nil is a real answer: the record predates the obligation."
    {:signature [:=> [:catn [:seam :map]] [:maybe :string]]})
  (Operation option-letter
    "The letter answering the option at position `i`, or nil past the cap. Derived from position
     and never stored — a stored letter is a second source of truth for ordering."
    {:signature [:=> [:catn [:i :int]] [:maybe :string]]})
  (Operation findings-heading
    "What the findings under a verdict ARE. `:insufficient` reports no refused claim, so heading
     it that way would tell a reader the opposite of what the round found."
    {:signature [:=> [:catn [:verdict :keyword]] :string]})
  (Operation report-title
    "Index title for the typed events carrying no top-level `:title`. nil otherwise, where the
     caller falls back to the event's own."
    {:signature [:=> [:catn [:report LedgerEvent]] [:maybe :string]]})
  (Operation report->markdown
    "Render a `:format`-tagged payload to markdown: each event type to its own headed section,
     `:markdown` to its body, anything unknown to the empty string."
    {:signature [:=> [:catn [:report [:maybe LedgerEvent]]] :string]}))
