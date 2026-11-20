(ns canvas.ui.presentation
  "Self-spec: `nido.ui.presentation` — the Presentation slice's catalog, as a project's own app
   serves it."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.platform.project :as project]
            [fukan.common.typing.malli]))

(Module ui-presentation
  "A project's component catalog as that project's own running app serves it, reached through
   the dashboard.

   HIDES where a project's catalog is served — which live session's app, at which path the
   project declares, or the dashboard itself for nido — and that the browser reaches it only
   through a forward under the project's prefix, so the framed page shares the dashboard's origin
   and no app port ever leaves the dashboard. Like `ui-structure`, the catalog for brian runs in
   brian's process, so nido frames it and never renders it; unlike it, the forward carries a
   request's method and body, because a catalog's controls post."
  (Operation target
    "Where the project's catalog is served now, tagged by `:status`: `:dashboard` for nido,
     `:session` for a live session's app at the path the project declares — each with `:src`, the
     same-origin address to frame — or why there is none, with `:why` saying so: `:no-catalog`
     when the project declares none, `:no-app` when no session of it has an app answering. Never
     an error, and never a port."
    {:signature [:=> [:catn [:project :string]]
                 [:or [:map [:status [:enum :dashboard]] [:src :string]]
                      [:map [:status [:enum :session]] [:session :string] [:src :string]]
                      [:map [:status [:enum :no-catalog]] [:why :string]]
                      [:map [:status [:enum :no-app]] [:why :string]]]]
     :delegates [project/get-project]})
  (Operation forward
    "Answer a request under a project's catalog prefix, whatever its method, with what its target
     answers to the same method, path, query string and body, or a notice saying why there is no
     target. Starts nothing."
    {:signature [:=> [:catn [:project :string]
                            [:request [:map [:method [:enum :get :post]] [:path :string]
                                       [:query-string [:maybe :string]] [:body [:maybe :string]]
                                       [:content-type [:maybe :string]]]]]
                 :map]
     :delegates [target]}))
