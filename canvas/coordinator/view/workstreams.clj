(ns canvas.coordinator.view.workstreams
  "Self-spec: `nido.coordinator.view.workstreams` — the workstreams surface, read-only.

   Pure read model: read, classify, render. NO WRITES — which is what lets a surface show the
   work plane without being able to disturb it."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream :refer [Workstream]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind WorkstreamRow
  "One workstream as a surface shows it: its label, where it stands, whether it needs a person,
   how engaged it is, and what is underway against it right now.

   ENGAGEMENT AND ACTIVITY ARE TWO FACETS, not one. Engagement says whether anyone is engaged
   and whether they are waiting on you; activity says which of the things that can be underway
   IS. A row commonly carries an engagement and no activity — a human is in the session and has
   started nothing nido can name — and that is the resting state rather than a gap.

   DERIVED, never stored. A row is recomputed from the workstream and its sessions every time,
   which is why a board cannot show a stage the records do not support.")

(Module view-workstreams
  "The workstreams surface: rows, their grouping, and the session rows beneath them.

   The largest read model, because a workstream's display state is derived from three places at
   once — the record, its sessions, and whatever the external ref's source says. Doing that in a
   surface is how two surfaces come to disagree about what stage something is at."
  {:child [WorkstreamRow]}
  (Operation notion-ref
    "The workstream's Notion ref, or nil."
    {:signature [:=> [:catn [:ws Workstream]] [:maybe :map]]})
  (Operation ref-links
    "The workstream's followable external refs, for display."
    {:signature [:=> [:catn [:ws Workstream]] [:vector :map]]})
  (Operation ledger-ref
    "The external ref whose id keys this workstream's ledger."
    {:signature [:=> [:catn [:ws Workstream]] [:maybe :map]]})
  (Operation ws-source
    "Which source bucket a workstream came from, from its refs."
    {:signature [:=> [:catn [:ws Workstream]] :keyword]})
  (Operation label
    "A workstream's display label, resolved by fallback — the ticket title if there is one, then
     the ref, then the id."
    {:signature [:=> [:catn [:ws Workstream] [:sessions :any]] :string]})
  (Operation last-activity
    "The latest timestamp across the workstream and its sessions."
    {:signature [:=> [:catn [:ws Workstream] [:sessions :any]] [:maybe :string]]})
  (Operation workstream-row
    "One display row, reading the workstream's sessions unless they are handed in.

     A defaults chain — `[:?]` rather than three arities, because each form fills the next
     argument in rather than being a different contract."
    {:signature [:=> [:catn [:project ProjectName] [:ws Workstream]
                            [:live-names [:? :any]] [:facts [:? :any]]] WorkstreamRow]
     :delegates [label last-activity ws-source session/list-sessions session/stage-projection]})
  (Operation bare-row
    "A display row for a watched Notion page that has no workstream yet — what the board shows
     before anything local exists."
    {:signature [:=> [:catn [:project ProjectName] [:page-id :string] [:page :map]] WorkstreamRow]})
  (Operation workstream-rows
    "Every display row for a project."
    {:signature [:=> [:catn [:project ProjectName] [:live-names [:? :any]]] [:vector WorkstreamRow]]
     :delegates [workstream/list-ids workstream/read-ws workstream-row]})
  (Operation grouped-by-stage
    "Rows partitioned by lifecycle stage."
    {:signature [:=> [:catn [:rows [:vector WorkstreamRow]]] :map]})
  (Operation grouped-by-engagement
    "Scratch rows grouped by whether anything is live on them."
    {:signature [:=> [:catn [:rows [:vector WorkstreamRow]]] :map]})
  (Operation session-rows
    "Display rows for one workstream's sessions."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:vector :map]]
     :delegates [session/list-sessions]})
  (Operation engagement-substatus
    "The short liveness tag shown beside a label."
    {:signature [:=> [:catn [:eng :keyword]] :string]})
  (Operation format-row
    "One workstream row as a display line."
    {:signature [:=> [:catn [:row WorkstreamRow]] :string]
     :delegates [engagement-substatus]})
  (Operation promote-result-message
    "The status line after a promote attempt — what happened, or why it could not."
    {:signature [:=> [:catn [:br :any] [:decision :map]] :string]})
  (Operation format-session-row
    "One session row as a display line."
    {:signature [:=> [:catn [:row :map]] :string]})
  (Operation doing-label
    "One short phrase for what a workstream is doing, or nothing when nothing is. Display only
     and pure — the SAME string for every surface, which is what stops the board and the pane
     describing one workstream two ways. It names the kind rather than saying `busy`, so an
     absent label reads as `none of the things that can say so are running`."
    {:signature [:=> [:catn [:doing [:maybe :map]]] [:maybe :string]]}))
