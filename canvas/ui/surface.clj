(ns canvas.ui.surface
  "Self-spec: `nido.ui.*` — the TUI, the web dashboard, and the views behind them.

   The band that is SEALED behind the work plane: these reach `WorkPlane` to act, `View` to
   read, `Control` for the daemon and `Report` for the value they render, and nothing beneath.
   That is what stops a surface inventing a stage transition or reading a record the work plane
   has an opinion about."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.control :as control]
            [canvas.coordinator.record.state :refer [SessionName WorkstreamId]]
            [canvas.coordinator.report :as report]
            [canvas.coordinator.work :as work :refer [Screen]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind ViewState
  "What a request or a keypress means, as state: which scope, which tab, which selection.

   The single input the screen is derived from — so a URL and a TUI keystroke that mean the same
   thing produce the same screen, and the dashboard is bookmarkable for free.")

(Module ui-view-state
  "Turning a request into view-state."
  {:child [ViewState]}
  (Operation parse "A request as view-state."
    {:signature [:=> [:catn [:req :map]] ViewState]}))

(Module ui-markdown
  "Markdown as renderable markup."
  (Operation render "A markdown string as hiccup."
    {:signature [:=> [:catn [:s [:maybe :string]]] :any]}))

(Module ui-views
  "The dashboard's markup: every page and every fragment.

   Fragment-first, because the dashboard updates in place — a click re-renders one fragment
   rather than a page, which is why almost everything here is addressable on its own."
  (Operation rail-status-fragment "The live rail: what needs you, and how the daemon is."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation fire-signal "The signal name for one field of a fire form."
    {:signature [:=> [:catn [:trigger-name :any] [:k :keyword]] :string]})
  (Operation fleet-card "What the fleet costs, and whether another session fits."
    {:signature [:=> [:catn [:fleet :map]] :any]})
  (Operation ops-panel-fragment "The ambient ops chrome: daemon, halt, breakers, triggers, fleet."
    {:signature [:=> [:catn [:ctx :map]] :any] :delegates [fleet-card fire-signal]})
  (Operation shell "The page chrome every page sits inside."
    {:signature [:=> [:catn [:ctx :map] [:body :any]] :any]})
  (Operation origin-badge "A workstream's origin as a badge."
    {:signature [:=> [:catn [:origin :keyword]] :any]})
  (Operation needs-fragment "The needs-you column."
    {:signature [:=> [:catn [:ctx :map]] :any] :delegates [origin-badge]})
  (Operation gate-action-confirm-fragment "The immediate confirmation a gate action swaps in."
    {:signature [:=> [:catn [:action-id :any] [:project ProjectName] [:ws-id WorkstreamId]
                            [:pane-id [:? :string]]] :any]})
  (Operation gate-action-skip-fragment "What is rendered when a gate action is not offered."
    {:signature [:=> [:catn [:msg :string] [:project ProjectName] [:ws-id WorkstreamId]
                            [:pane-id [:? :string]]] :any]})
  (Operation action-bar "One action set, rendered."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:actions :any]
                            [:session :any] [:route [:? :any]]] :any]})
  (Operation gate-pane "One gate in full: what it is, what it holds, what you can do."
    {:signature [:=> [:catn [:gate :map]] :any]
     :delegates [action-bar origin-badge report/report->markdown render]})
  (Operation needs-page "The home page: the needs-you master and the selected gate."
    {:signature [:=> [:catn [:ctx :map] [:data :map]] :any]
     :delegates [shell needs-fragment gate-pane]})
  (Operation workstreams-fragment "The selected tab's stage groups."
    {:signature [:=> [:catn [:ctx :map]] :any] :delegates [origin-badge]})
  (Operation status-heading "The status section's heading."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation workstream-pane "The read-only ledger pane for one workstream."
    {:signature [:=> [:catn [:ws :map] [:session-dev-states [:? :any]]] :any]
     :delegates [status-heading report/report->markdown render]})
  (Operation pickup-bar "The paste-a-ticket bar."
    {:signature [:=> [:catn [:project ProjectName]] :any]})
  (Operation pickup-result-fragment "What a pickup attempt swaps in."
    {:signature [:=> [:catn [:result :map] [:opts :map]] :string]})
  (Operation workstreams-page "The overview and its ledger pane."
    {:signature [:=> [:catn [:ctx :map] [:data :map]] :any]
     :delegates [shell workstreams-fragment workstream-pane pickup-bar]})
  (Operation operations-fragment "The proposal list."
    {:signature [:=> [:catn [:proposals :any]] :any]})
  (Operation operations-page "Every proposal the review analyses have made."
    {:signature [:=> [:catn [:ctx :map] [:proposals :any]] :any]
     :delegates [shell]})
  (Operation proposal-result-fragment "What deciding a proposal swaps in."
    {:signature [:=> [:catn [:result :map] [:proposals :any]] :any] :delegates [operations-fragment]})
  (Operation not-found-page "The 404."
    {:signature [:=> [:catn] :any] :delegates [shell]}))

(Module ui-server
  "The dashboard's HTTP surface.

   `read-rail-daemon` and `read-pickup-blocker` are named seams over the daemon readings so a
   test can stub them — and the second asks what blocks THIS envelope rather than reading the
   rail dot, because the dot ranks a breaker above a healthy daemon and reading it as go/no-go
   reported a healthy daemon as down whenever an unrelated trigger was tripped."
  (Operation read-rail-daemon "The rail's daemon reading. A stubbing seam."
    {:signature [:=> [:catn] :map] :delegates [control/read-daemon-health]})
  (Operation read-pickup-blocker "What would block a pickup for this project. A stubbing seam."
    {:signature [:=> [:catn [:project ProjectName]] [:maybe :keyword]]
     :delegates [control/read-queue-blocker]})
  (Operation derive-screen "Gather what the screen needs and derive it."
    {:signature [:=> [:catn [:view-state :any]] Screen] :delegates [work/screen]})
  (Operation parse-findings-lines "A textarea of findings as items."
    {:signature [:=> [:catn [:s :string]] :any]})
  (Operation resolve-failure-msg "Why a gate action did not do what was asked."
    {:signature [:=> [:catn [:result :map]] :string]})
  (Operation handle-request "One request, routed."
    {:signature [:=> [:catn [:req :map]] :map]
     :delegates [derive-screen parse-findings-lines resolve-failure-msg read-pickup-blocker]})
  (Operation start! "Start the dashboard server."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [handle-request]})
  (Operation stop! "Stop it."
    {:signature [:=> [:catn] :any]}))

(Module ui-tui
  "The terminal surface.

   One public entry, and everything else is private: the TUI is a rendering of the same screen
   the dashboard shows, so its internals are not a second model."
  (Operation run-once "Run the TUI to completion."
    {:signature [:=> [:catn] :any]}))

(Module ui-dev
  "The development-environment lever: what a session's app is doing, and how to restart it.

   Separate from the work plane on purpose — restarting an app server is a fact about a machine,
   not a move on a workstream, and putting it in `work` would have made the work vocabulary
   answer questions about processes."
  (Operation set-app-state! "Record what a session's app is doing."
    {:signature [:=> [:catn [:instance-id :any] [:state :any] [:error-msg [:? :any]]] :any]})
  (Operation clear-app-state! "Forget it."
    {:signature [:=> [:catn [:instance-id :any]] :any]})
  (Operation current-app-state "What a session's app was last reported doing."
    {:signature [:=> [:catn [:instance-id :any]] :any]})
  (Operation pending-resolve-keys "The gate resolutions currently in flight."
    {:signature [:=> [:catn] :any]})
  (Operation pending-winddown-keys "The wind-downs currently in flight."
    {:signature [:=> [:catn] :any]})
  (Operation failed-ws-errors "The workstreams whose last action failed, and why."
    {:signature [:=> [:catn] :map]})
  (Operation dev-state-for "A session's dev-resource state. Pure derivation."
    {:signature [:=> [:catn [:wt-path :any] [:instance-id :any] [:registry :map] [:probe :any]
                            [:app-state-fn :any]] :map]})
  (Operation session-dev-state "The derived dev state for one session."
    {:signature [:=> [:catn [:project ProjectName] [:session :any] [:registry [:? :map]]] :map]
     :delegates [dev-state-for]})
  (Operation ws-session-dev-states "Dev state for every session of a workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws :map]] :map] :delegates [session-dev-state]})
  (Operation app-port-for-instance "The app port an instance holds."
    {:signature [:=> [:catn [:instance-id :any]] [:maybe :int]]})
  (Operation stop-session! "Bring one session down."
    {:signature [:=> [:catn [:project ProjectName] [:session :any]] :any]})
  (Operation dev-action! "Run a dev-environment lever against a session."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session :any]
                            [:action :keyword]] :any]
     :delegates [set-app-state!]}))
