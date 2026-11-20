(ns canvas.coordinator.view.host
  "Self-spec: `nido.coordinator.view.host` — the host as the Operations card reads it.

   Pure read model: handed two host readings and the coordinator's pid, it derives. No reads and
   no writes."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.platform.host :refer [HostReading]]
            [fukan.common.typing.malli]))

(Kind ProcessKind
  "What a person thinks a process is: a Claude session, one of its MCP servers, a REPL or app
   server, postgres, or none of those."
  [:enum :claude :mcp :repl :postgres :other])

(Kind HostModel
  "The host as the card and its page read it — one of two alternatives. Unreadable: no reading,
   so the model is only that, with no figure, tone or reason. Read: the figures the reading
   held, each nil where the host did not give it (a partial reading); :cpu-share is a fraction
   of one core, nil while it is being measured; :need is nil when the card's tone is nil, else
   the tone :you with the reasons that set it."
  [:or
   [:map {:closed true} [:unreadable [:enum :host]]]
   [:map {:closed true}
    [:cores :int]
    [:load [:maybe [:map [:one :double] [:five :double] [:fifteen :double]]]]
    [:memory [:maybe [:map [:total-bytes :int] [:used-bytes :int] [:available-bytes :int]
                      [:swap-used-bytes :int]]]]
    [:disk [:maybe [:map [:path :string] [:total-bytes :int] [:used-bytes :int] [:free-bytes :int]]]]
    [:coordinator [:maybe [:map [:pid :int] [:rss-bytes [:maybe :int]] [:cpu-share [:maybe :double]]]]]
    [:kinds [:maybe [:vector [:map [:kind [:enum :claude :mcp :repl :postgres :other]]
                              [:count :int] [:rss-bytes :int]
                              [:processes [:vector [:map [:pid :int] [:command :string]
                                                    [:rss-bytes [:maybe :int]]]]]]]]]
    [:need [:maybe [:map {:closed true} [:tone [:enum :you]]
                    [:reasons [:vector {:min 1} :string]]]]]]])

(Module view-host
  "The host as a person reads it: load against cores, memory, the volume, the coordinator's CPU
   share, and nido's processes grouped by kind — and whether any of it needs a person.

   HIDES how a process is told to be a Claude session, an MCP server, a REPL or postgres, and the
   thresholds past which the host asks for someone."
  {:child [ProcessKind HostModel]}
  (Operation host-model
    "The card and page model from the current reading (nil when it could not be read), the one
     before it (nil on the first) and the coordinator's pid (nil when unknown)."
    {:signature [:=> [:catn [:reading [:maybe HostReading]] [:previous [:maybe HostReading]]
                  [:coordinator-pid [:maybe :int]]] HostModel]})
  (Operation process-kind
    "The kind one process is counted under: its own, or its nearest classified ancestor's, else
     :other."
    {:signature [:=> [:catn [:by-pid :map] [:pid :int]] ProcessKind]}))
