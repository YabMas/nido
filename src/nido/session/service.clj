(ns nido.session.service)

(defmulti ^{:malli/schema [:=> [:cat :map :map :any] :map]}
  start-service!
  "Start a service. Returns {:state {...} :context {...}}.
   :state is persisted for stop/status. :context is merged into the session context."
  (fn [service-def _ctx _opts] (:type service-def)))

(defmulti ^{:malli/schema [:=> [:cat :map :any] :any]}
  stop-service!
  "Stop a service. Takes the service-def and saved state from start."
  (fn [service-def _saved-state] (:type service-def)))

(defmulti ^{:malli/schema [:=> [:cat :map :any] :map]}
  service-status
  "Check if a service is alive. Returns {:alive? bool ...}."
  (fn [service-def _saved-state] (:type service-def)))
