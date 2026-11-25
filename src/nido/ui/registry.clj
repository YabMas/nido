(ns nido.ui.registry
  "nido's registry of blocks, and the stylesheet the UI library assembles over it.

   HIDES which block registrations nido publishes, and that the stylesheet is assembled from them
   once rather than per request. Every chrome nido draws — a dashboard page, the catalog, the
   Structure frame — emits `assembled-stylesheet`, so a fragment Datastar patches in later is
   already styled and no chrome can emit a stylesheet of its own. `nido.ui.stylesheet` supplies
   the token values and the rules no registration carries, and never reads a registration."
  (:require [nido.libraries.ui.style :as style]
            [nido.ui.catalog.scenes :as scenes]
            [nido.ui.components.ask :as ask]
            [nido.ui.components.chat :as chat]
            [nido.ui.components.dialog :as dialog]
            [nido.ui.components.row :as row]
            [nido.ui.stylesheet :as css]))

(def ^:private surfaces
  "The backgrounds nido's blocks sit on, in nido's own tokens — the axis the catalog frames a
   scene on. A chip whose tint matches the surface under it disappears, and this shows it."
  [{:id "page" :label "page" :background "var(--bg)"}
   {:id "card" :label "card" :background "var(--surface)"}
   {:id "pane" :label "sunken pane" :background "var(--surface-sunken)"}])

(defn ^{:malli/schema [:=> [:cat] :map]}
  registry
  "nido's registry, as the library's catalog reads it: `:blocks`, every block registration of
   nido's component namespaces, and `:groups`, the scenes grouped by vocabulary — each scene's
   `:render` a thunk calling the block or function the dashboard calls."
  []
  {:title    "nido dashboard"
   :surfaces surfaces
   :blocks   [ask/card ask/bar row/board-row row/gate-row row/winddown-row row/arc-row
             chat/panel chat/change-bar dialog/dialog]
   :groups   (scenes/groups)})

(def ^:private assembled
  (delay (style/stylesheet (assoc (css/stylesheet) :blocks (:blocks (registry))))))

(defn ^{:malli/schema [:=> [:cat] :string]}
  assembled-stylesheet
  "The stylesheet every nido chrome emits: nido's tokens as the one :root block, the library's
   layout floor, every registered block's rules and nido's own rules, in their layers. Assembled
   on first use and never again in the process."
  []
  @assembled)
