(ns canvas.coordinator.record.drain
  "Self-spec: `nido.coordinator.record.drain` — a drain's records, and its slices' tie to it."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]))

(Module record-drain
  "A drain's records: its charter, recipe versions, census readings, slice plans, slice roster, misfits and
   their answers, clearances, residue items and their dispositions, autonomy state and exit observation, all on the drain's ledger, and each
   slice's birth and rebase entries naming its drain and recipe version.

   READ, NEVER STORED. A workstream is a drain because its ledger holds a :drain birth entry, a
   slice because its ledger holds a :slice one; the recipe version is the seq of the :recipe entry,
   and the autonomy state (supervised, autonomous, suspended) is the newest autonomy entry's. The
   roster is on the DRAIN's ledger, so a drain lists its slices from its own records."
  (Operation answer-misfit!
    "The coordinator's answer to an open misfit. `answer` is `{:as :proposed :proposal n}`, `{:as :carved :site s :disposition d}` — whose residue item is appended with its disposition in the same step — or `{:as :escalated :asks s}`. Answers the :misfit-answered seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:misfit-seq :any] [:answer :any]] :any]})
  (Operation approve-recipe!
    "The person's approval of a pending proposal: a :recipe carrying its recipe, whose seq is the new version. Refuses a proposal already approved or older than the current recipe. Answers the seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:proposal-seq :any]] :any]})
  (Operation censuses
    "Every census reading, oldest first."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation charter
    "The drain's :drain birth entry, or nil for a workstream that is no drain."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation clear-slice!
    "The coordinator clearing a slice of this drain for landing, naming the residue it leaves — `[{:site s :disposition d} …]` — each appended as a residue item with its disposition in the same step. Answers the :slice-cleared seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:slice-ws-id :any] [:residue-items :any]] :any]})
  (Operation cleared?
    "Whether the coordinator has cleared the slice `slice-ws-id`."
    {:signature [:=> [:catn [:w :any] [:slice-ws-id :any]] :any]})
  (Operation dispose-residue!
    "The coordinator disposing again of a residue item no started plan covers yet. Answers the seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:residue-seq :any] [:disposition :any]] :any]})
  (Operation drain-of
    "The ws-id of the drain a slice belongs to, or nil for a workstream that is no slice."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation drain?
    "Whether `w` is a drain: its ledger holds a :drain birth entry."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation exit-observed
    "The :exit-observed entry, or nil."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation newest-census
    "The newest census reading citing the current recipe, or nil."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation observe-exit!
    "The person observing the exit criterion against the newest census, which must read zero, with every residue item covered by a landed slice. Answers the :exit-observed seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:landed? :any]] :any]})
  (Operation open-misfits
    "The misfits no :misfit-answered names."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation open-plans
    "The plans no start has consumed and none has withdrawn or replaced."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation pending-proposals
    "The proposals no approval has taken — newer than the current recipe, none approved since."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation plan-slice!
    "The coordinator's :slice-planned. Refuses one planned from any census but the newest citing the current recipe, resting on anything but a plan of this drain, or replacing a plan that is not open. Answers its seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:arg2 :any]] :any]})
  (Operation plans
    "Every slice plan on the drain, oldest first, each marked with how it stands: `:started` (a start cites it, `:slice` naming the workstream), `:withdrawn`, `:replaced` (a newer plan replaces it), or `:open`."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation propose-recipe!
    "The coordinator's :recipe-proposed. Answers its seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:arg2 :any]] :any]})
  (Operation rebase-slice!
    "Move a slice to its drain's current recipe: a :slice-rebased citing it, when the slice runs an older one. Answers the version the slice now runs under."
    {:signature [:=> [:catn [:project ProjectName] [:slice-ws-id :any]] :any]})
  (Operation recipe
    "The drain's current recipe — its newest :recipe entry, stamped with :seq — or nil."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation record-census!
    "nido's census reading under the recipe it cites, which must be the current one. Answers its seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:arg2 :any]] :any]})
  (Operation refuse-start!
    "lane-drain's :start-refused of a plan whose rested-on work cannot be read or merged, once per plan. Answers its seq, or nil when it was refused already."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:arg2 :any] [:arg3 :any] [:arg4 :any]] :any]})
  (Operation refused-plans
    "The seqs of the plans whose start lane-drain refused; a replacing plan is a new seq."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation record-misfit!
    "A slice's :misfit on its drain. Answers its seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:arg2 :any]] :any]})
  (Operation residue
    "The drain's residue items, oldest first, each with its newest `:disposition`."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation slice-birth
    "The slice's :slice birth entry — its drain, starting recipe version and plan — or nil."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation slice-version
    "The recipe version a slice runs under: the newest of its birth's and its :slice-rebased entries' recipe seqs. nil for a workstream that is no slice."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation slice?
    "Whether `w` is a slice: its ledger holds a :slice birth entry."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation slices
    "The drain's slices, from its own :slice-started entries: `{:ws-id :plan :started-at}`."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation start-drain!
    "Mint a drain workstream: its :drain birth, naming what established the pattern (`{:ws-id …}` or `{:commit …}`) and the exit criterion. Whether the named workstream or commit exists is the caller's to check. Answers the new workstream's id."
    {:signature [:=> [:catn [:project ProjectName] [:arg1 :any]] :any]})
  (Operation start-slice!
    "Start a slice from an open plan: refuse, naming what is missing, before anything is made when the drain has no recipe, no census under it, or the plan is not open; otherwise mint the slice workstream with its :slice birth and append the drain's :slice-started citing the plan. Answers the slice's ws-id."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:plan-seq :any] [:by :any]] :any]})
  (Operation startable
    "Why a slice may not start from `plan-seq` on drain `w` now — a string — or nil when it may: the drain must hold a recipe and a census citing it, and the plan must be open."
    {:signature [:=> [:catn [:w :any] [:plan-seq :any]] :any]})
  (Operation uncovered-residue
    "The residue items no landed slice covers. `landed?` answers whether a slice workstream has landed — the lane's reading, not this namespace's. A landed slice covers an item only when its plan covers the item's site (names it, and is not the plan of the slice that left it) — the follow-up plan its disposition cites, or, disposed :closing, any plan made after the item was left, so none planned without knowing of it covers it."
    {:signature [:=> [:catn [:w :any] [:landed? :any]] :any]})
  (Operation version
    "The drain's recipe version: the seq of its newest :recipe entry, or nil while it has none."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation landing-outcome
    "How a slice landing Run ended, read from its slice's own records — never the merge lane's ticket ledger: :done once the slice stands landed, or once /drive-home reported its phase :complete; :awaiting-review, a failed landing, otherwise and for a turn that could not start or ran past its budget. The one reading of a landing Run's outcome."
    {:signature [:=> [:catn [:run :any] [:result :any]] :keyword]})
  (Operation withdraw-plan!
    "The coordinator withdrawing an open plan. Answers the entry's seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:plan-seq :any] [:why :any]] :any]}))
