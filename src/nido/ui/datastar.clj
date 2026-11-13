(ns nido.ui.datastar
  "The one place a dashboard chrome loads Datastar from.

   HIDES which bundle — which pinned version, from where. A namespace of its own because there
   are two chromes now, a dashboard page and the component catalog, and a pinned CDN URL written
   in each is the same defect as a hue written in each: two copies, one of which will be the one
   nobody updates. The catalog exists to render blocks in the app's real runtime, and it can only
   claim that while there is one runtime to name.

   Beside the visual language rather than inside it, and in no stratum. A block's classes are its
   vocabulary and `nido.ui.stylesheet` is the level they are written in; the Datastar bundle is
   not vocabulary at all — it is the runtime a block's `data-` attributes are interpreted by, and
   nothing above is written in terms of it.")

(def ^:private bundle
  "The pinned bundle. Pinned and not floating: the markup's `data-` attributes are written
   against one version's semantics, and a tag that moves would change how every block behaves
   without any of them changing."
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.1/bundles/datastar.js")

(defn ^{:malli/schema [:=> [:cat] :any]}
  datastar-script
  "The script element every dashboard chrome loads Datastar with, as hiccup for a page's <head>."
  []
  [:script {:type "module" :src bundle}])
